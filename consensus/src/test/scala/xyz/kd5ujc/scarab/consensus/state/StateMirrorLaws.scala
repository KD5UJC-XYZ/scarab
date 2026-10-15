// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import cats.arrow.FunctionK
import cats.syntax.all.*
import org.scalacheck.{Arbitrary, Prop}
import org.scalacheck.Prop.{forAll, propBoolean}
import org.typelevel.discipline.Laws
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.NodeState
import xyz.kd5ujc.scarab.ledger.Nonce
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  Gap,
  Genesis,
  Hash32,
  KeyHash,
  NodeExtras,
  Residue,
  SnapshotsResidue,
  Word64,
  LedgerImage,
  MirrorError,
  MirrorLaws,
  ShelleyBasedEra,
  SlotNo,
  WithOrigin
}

/** A mirror that was changed and the refusal it must meet, with a name for the report. */
final case class Tampered(name: String, expected: StateMirrorError, state: MirrorState)

/** The changes to a mirror that [[StateMirror.fromMirror]] must refuse. Each is a function of the
  * mirror (and of the state it is of), and `None` when the mirror has nothing to change that way;
  * the suites count what applied, so that no change is a vacuous one.
  */
object StateTampers:
  private def retarget[A: EraIndexed](
      past: Vector[Past],
      current: Current[A]
  ): Option[Telescope[A]] = Telescope.make(past, current).toOption

  private def moveState[A: EraIndexed](t: Telescope[A], f: A => A): Option[Telescope[A]] =
    retarget(t.past, Current(t.current.start, f(t.state)))

  private def later(b: Bound): Bound = b.copy(slot = b.slot + 1)

  /** The telescope one era shorter (the last past era becomes the current one) or, failing that,
    * one era longer (an empty era is inserted): the era a protocol state of the same family is in.
    */
  private def otherEra[A: EraIndexed](t: Telescope[A]): Option[Telescope[A]] =
    val shorter =
      t.past.lastOption.flatMap(p => retarget(t.past.dropRight(1), Current(p.start, t.state)))
    lazy val longer =
      retarget(t.past :+ Past(t.current.start, t.current.start), Current(t.current.start, t.state))
    shorter.orElse(longer)

  private def withLedger(m: MirrorState, t: Telescope[EraLedgerState]): MirrorState =
    m.copy(ext = m.ext.copy(ledgerState = t))

  private def withHeader(m: MirrorState, h: HeaderState): MirrorState =
    m.copy(ext = m.ext.copy(headerState = h))

  private def flip(at: WithOrigin[SlotNo]): WithOrigin[SlotNo] = at match
    case WithOrigin.Origin => WithOrigin.At(SlotNo.fromUnsigned(0L))
    case WithOrigin.At(_)  => WithOrigin.Origin

  private val fakeHash = Hash32
    .fromBytes(Bytes.fromArray(Array.fill(32)(0x33.toByte)))
    .fold(e => sys.error(e.show), identity)

  /** The tips of the header and ledger states, and the chain-dependent state, changed. */
  private def structural(m: MirrorState): List[Tampered] =
    val header = m.ext.headerState
    def tampered(name: String, expected: ConsensusDerived, s: Option[MirrorState]) =
      s.map(Tampered(name, StateMirrorError.Derived(expected), _))

    val bounds = retarget(
      m.ext.ledgerState.past.map(p => Past(later(p.start), later(p.end))),
      Current(later(m.ext.ledgerState.current.start), m.ext.ledgerState.state)
    ).map(withLedger(m, _))

    val annotation = header.tip match
      case WithOrigin.Origin => None
      case WithOrigin.At(tip) =>
        val info = tip.info match
          case TipInfo.ByronTip(h, _)        => TipInfo.ShelleyBasedTip(ShelleyBasedEra.SConway, h)
          case TipInfo.ShelleyBasedTip(_, h) => TipInfo.ByronTip(h, IsEBB.NotEBB)
        Some(withHeader(m, header.copy(tip = WithOrigin.At(tip.copy(info = info)))))

    val lastSlot = flipLastSlot(header.chainDep.state)
      .flatMap(d => moveState(header.chainDep, _ => d))
      .map(d => withHeader(m, header.copy(chainDep = d)))

    val ledgerTip = m.ext.ledgerState.state match
      case _: EraLedgerState.ByronCurrent => None
      case i: EraLedgerState.InShelleyEra[e] =>
        val tip: WithOrigin[ShelleyTip] = i.state.tip match
          case WithOrigin.Origin =>
            WithOrigin.At(ShelleyTip(SlotNo.fromUnsigned(0L), BlockNo.fromUnsigned(0L), fakeHash))
          case WithOrigin.At(_) => WithOrigin.Origin
        moveState(
          m.ext.ledgerState,
          _ => EraLedgerState.inEra[e](i.state.copy[e](tip = tip))(using i.era)
        )
          .map(withLedger(m, _))

    List(
      tampered("the telescope bounds, later by a slot", ConsensusDerived.TelescopeBounds, bounds),
      tampered("the annotation of the header tip", ConsensusDerived.HeaderTip, annotation),
      tampered("the last slot of the chain-dependent state", ConsensusDerived.ChainDep, lastSlot),
      tampered("the tip of the ledger state", ConsensusDerived.LedgerTip, ledgerTip),
      tampered("the PBFT count of a genesis key", ConsensusDerived.ChainDep, pbftCount(m))
    ).flatten

  private def flipLastSlot(d: ChainDepState): Option[ChainDepState] = d match
    case _: ChainDepState.PBft   => None
    case ChainDepState.TPraos(t) => Some(ChainDepState.TPraos(t.copy(lastSlot = flip(t.lastSlot))))
    case ChainDepState.Praos(p)  => Some(ChainDepState.Praos(p.copy(lastSlot = flip(p.lastSlot))))

  /** Byron only: one more block counted for a genesis key (a new key if the window is empty). */
  private def pbftCount(m: MirrorState): Option[MirrorState] =
    m.ext.headerState.chainDep.state match
      case ChainDepState.PBft(p) =>
        val key = p.counts.headOption.fold(
          KeyHash
            .fromBytes[Genesis](
              Bytes.fromArray(Array.fill(28)(0x44.toByte))
            )
            .fold(e => sys.error(e.show), identity)
        )(_._1)
        val one = Word64.fromUnsigned(1L)
        val bumped = p.counts.updated(
          key,
          p.counts.get(key).flatMap(_.successor).getOrElse(one)
        )
        moveState(m.ext.headerState.chainDep, _ => ChainDepState.PBft(p.copy(counts = bumped)))
          .map(d => withHeader(m, m.ext.headerState.copy(chainDep = d)))
      case _ => None

  /** The ledger and the header in different eras, and extras of another kind of state. */
  private def shapes(s: NodeState, m: MirrorState): List[Tampered] =
    val era = m.ext.ledgerState.era
    val eraMismatch = otherEra(m.ext.headerState.chainDep).map(d =>
      Tampered(
        "the header telescope in another era",
        StateMirrorError.EraMismatch(era, d.era),
        withHeader(m, m.ext.headerState.copy(chainDep = d))
      )
    )
    val extras: Option[ConsensusExtras] = m.extras match
      case _: ConsensusExtras.InByron =>
        Some(
          ConsensusExtras.InShelley(
            NodeExtras(
              s.ledger.plutus,
              None,
              None,
              Map.empty,
              false,
              false,
              SnapshotsResidue.empty,
              None,
              Residue.empty
            ),
            s.byron,
            s.boot,
            ProtocolExtras.OfPraos(0)
          )
        )
      case _: ConsensusExtras.InShelley => Some(ConsensusExtras.InByron(s.ledger, s.praos, s.boot))
    val wrongKind = extras.map(e =>
      Tampered(
        "extras of the other kind of state",
        StateMirrorError.ExtrasMismatch(era),
        m.copy(extras = e)
      )
    )
    val protocol = m.extras match
      case ConsensusExtras.InShelley(l, b, bo, ProtocolExtras.OfTPraos(e, _)) =>
        Some(ConsensusExtras.InShelley(l, b, bo, ProtocolExtras.OfPraos(e)))
      case ConsensusExtras.InShelley(l, b, bo, ProtocolExtras.OfPraos(e)) =>
        Some(ConsensusExtras.InShelley(l, b, bo, ProtocolExtras.OfTPraos(e, Nonce.Neutral)))
      case _: ConsensusExtras.InByron => None
    val wrongProtocol = protocol.map(e =>
      Tampered(
        "protocol extras of the other protocol",
        StateMirrorError.ChainDepFamily(era),
        m.copy(extras = e)
      )
    )
    List(eraMismatch, wrongKind, wrongProtocol).flatten

  /** The derived-piece tampers of the ledger side (`MirrorLaws.tampers`), applied to the ledger
    * state inside the telescope: the consensus side must pass the ledger side's refusal on.
    */
  private def ledger(m: MirrorState): List[Tampered] =
    (m.ext.ledgerState.state, m.extras) match
      case (i: EraLedgerState.InShelleyEra[e], ConsensusExtras.InShelley(lx, b, bo, p)) =>
        given ShelleyBasedEra[e] = i.era
        MirrorLaws.tampers[e].flatMap { t =>
          for
            bad <- t.apply(LedgerImage[e](i.state.nes, lx))
            telescope <- moveState(
              m.ext.ledgerState,
              _ => EraLedgerState.inEra[e](i.state.copy[e](nes = bad.nes))
            )
          yield Tampered(
            s"the ledger side: ${t.name}",
            StateMirrorError.Ledger(MirrorError.DerivedMismatch(t.field)),
            withLedger(m, telescope).copy(extras = ConsensusExtras.InShelley(bad.extras, b, bo, p))
          )
        }
      case _ => Nil

  /** Every change that applies to the mirror `m` of `s`. */
  def all(s: NodeState, m: MirrorState): List[Tampered] =
    structural(m) ++ shapes(s, m) ++ ledger(m)

/** The laws of [[StateMirror]] over any effect `F` that has the capabilities (`run` interprets it).
  *
  *   - retraction: `fromMirror(toMirror(a, s))` is `(a, s)`, up to [[StateMirror.normalised]];
  *   - the mirror is stable: `toMirror(fromMirror(toMirror(a, s)))` is `toMirror(a, s)`;
  *   - the gaps are exactly the ones [[StateMirror.gaps]] says, and the Shelley transition is one
  *     exactly when the mirror has a Shelley-based state;
  *   - a mirror with a derived piece (or its shape) changed is refused, naming it.
  */
final class StateMirrorLaws[F[_]](
    mirror: StateMirror[F],
    run: FunctionK[F, [A] =>> Either[StateMirrorError, A]],
    env: ConsensusMirrorEnv
) extends Laws:

  private def to(l: Located) = run(mirror.toMirror(l.anchor, l.state))
  private def from(m: MirrorState) = run(mirror.fromMirror(m))

  def laws(using located: Arbitrary[Located]): RuleSet =
    new SimpleRuleSet(
      "state mirror",
      "retraction" -> forAll { (l: Located) =>
        val back = to(l).flatMap(m => from(m.value))
        (back == Right((l.anchor, StateMirror.normalised(env, l.state)))) :| s"$back"
      },
      "the mirror of the retraction is the mirror" -> forAll { (l: Located) =>
        val again = to(l).flatMap(m =>
          from(m.value).flatMap(back => to(Located(back._1, back._2))).map(n => (m, n))
        )
        again.exists { case (m, n) => m === n } :| s"$again"
      },
      "the gaps are the ones the state has, the transition when there is a Shelley-based state" ->
        forAll { (l: Located) =>
          val result = to(l)
          result.exists { m =>
            val shelley = m.value.extras match
              case _: ConsensusExtras.InShelley => true
              case _: ConsensusExtras.InByron   => false
            m.gaps == StateMirror.gaps(env, l.state) && m.gaps.contains(
              Gap.ShelleyTransition
            ) == shelley
          } :| s"$result"
        },
      "a mirror with a derived piece changed is refused" -> forAll { (l: Located) =>
        to(l).fold(
          e => Prop.falsified :| s"$e",
          m =>
            StateTampers.all(l.state, m.value).foldLeft(Prop.passed) { (prop, t) =>
              val verdict = from(t.state)
              prop && (verdict == Left(t.expected)) :| s"${t.name}: $verdict"
            }
        )
      }
    )
