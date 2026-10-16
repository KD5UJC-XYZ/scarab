// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus.state

import xyz.kd5ujc.scarab.ledger.state.Diff
import cats.{Eq, Monad, Show}
import cats.derived.*
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.{
  BlockRef,
  BootstrapState,
  ByronState,
  ConsensusExtras,
  HardForkConfig,
  NodeState
}
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  EraTag,
  Gap,
  Hash32,
  LedgerImage,
  LedgerMirror,
  MirrorEnv,
  MirrorError,
  Mirrored,
  NodeExtras,
  ShelleyBasedEra,
  SlotNo,
  VocabError,
  WithOrigin
}
import xyz.kd5ujc.scarab.protocol.Era

// The consensus side of the mirror: the node's `NodeState` (the flat ledger state, the header state
// with the chain-dependent state of the protocol, the Byron and bootstrap state) to the Haskell
// `ExtLedgerState` of the Cardano block (`Ledger/Extended.hs:70-73`) and back. Since J1.5 the
// chain-dependent state is the Haskell one in the node too, so this side puts the telescopes on the
// era history and the tip on the anchor, and takes them off again. Plan sections 2 and 3 of the ledger-state
// mirror, stage S5. The ledger part is `LedgerMirror` (S4), reached through the environment and
// the error of this side.

/** What the consensus side of the conversion reads from the node's configuration: the ledger side's
  * ([[MirrorEnv]]) and what the era history is made from.
  *
  * @param ledger
  *   the environment of the ledger side (network, epoch geometry, update quorum)
  * @param forks
  *   how each Shelley-based era is entered (`HardForkConfig`)
  * @param byron
  *   the Byron parameters of the era history (epoch size, slot length, security parameter)
  * @param observed
  *   the epoch in which the node saw each era begin, where the configuration does not say
  *   (`EraHistory.summary`)
  */
final case class ConsensusMirrorEnv(
    ledger: MirrorEnv,
    forks: HardForkConfig,
    byron: EraHistory.ByronParams,
    observed: Map[Era, BigInt] = Map.empty
)

/** The pieces of a [[MirrorState]] that `fromMirror` derives from the rest and refuses to find
  * different (a tampered or stale mirror). Those of the ledger side are
  * [[xyz.kd5ujc.scarab.ledger.state.Derived]], inside [[StateMirrorError.Ledger]].
  */
enum ConsensusDerived derives Show:
  /** The era the node's state is in, from its version and epoch. */
  case CurrentEra

  /** The bounds of the telescopes (`EraHistory.summary`), the ones of the past eras and the start
    * of the current one.
    */
  case TelescopeBounds

  /** The tip of the ledger state, which is the anchor. */
  case LedgerTip

  /** The tip of the header state, which is the anchor, in the annotation the era has. */
  case HeaderTip

  /** The chain-dependent state: the last slot, the counts of the PBFT window, the nonces. */
  case ChainDep

  /** The consensus-side extras. */
  case Extras

/** Why a conversion refused. Closed, and over the ledger side's [[MirrorError]] rather than an
  * extension of it: the consensus cases name types (`TelescopeError`) that the ledger module, where
  * [[MirrorError]] is, cannot see.
  */
enum StateMirrorError derives Show:
  /** The ledger side refused. */
  case Ledger(error: MirrorError)

  /** A telescope cannot be built. */
  case Telescope(error: TelescopeError)

  /** The era history has no summary of the era. */
  case MissingEra(era: Era)

  /** The era history has no end for the past era at `index`. */
  case OpenPastEra(index: Int)

  /** The ledger and the header telescopes are in different eras (`ExtLedgerState` does not forbid
    * it; Haskell's single type-level list does).
    */
  case EraMismatch(ledger: Era, header: Era)

  /** The extras are those of another kind of state than the era's (Byron's or a Shelley-based
    * era's).
    */
  case ExtrasMismatch(era: Era)

  /** The chain-dependent state is of another protocol than the era runs, or the extras are. */
  case ChainDepFamily(era: Era)

  /** The anchor is a Byron epoch boundary block, which no Shelley-based era has. */
  case BoundaryOutsideByron(era: Era)

  /** A piece the mirror derives is not what the mirror holds. */
  case Derived(field: ConsensusDerived)

object StateMirrorError:
  private given Show[Era] = Show.fromToString

/** What the node holds that the Haskell state has no place for, on the consensus side. The ledger
  * side's ([[NodeExtras]]) is inside the Shelley-based case.
  *
  * Haskell's `Past` eras keep bounds only, so the Byron ledger and its chain-follow state go here
  * once the chain has left Byron (in [[ConsensusExtras]]); and a chain still in Byron has no
  * `NewEpochState`, so the flat ledger state and the state that Shelley will start from (initial,
  * until Shelley) go here instead.
  */
enum MirrorExtras:
  case InByron(
      ledger: node.LedgerState,
      pending: ChainDepState,
      boot: BootstrapState,
      protocol: ProtocolExtras
  )
  case InShelley(ledger: NodeExtras, consensus: ConsensusExtras)

object MirrorExtras:
  // The node's own records, compared structurally and rendered by their `toString`.
  given Eq[MirrorExtras] = Eq.fromUniversalEquals
  given Show[MirrorExtras] = Show.fromToString

/** The mirror of the node's state: the Haskell `ExtLedgerState` and the node-only bookkeeping. */
final case class MirrorState(ext: ExtLedgerState, extras: MirrorExtras)

object MirrorState:
  given Eq[MirrorState] = Eq.instance((a, b) => a.ext === b.ext && a.extras === b.extras)

  given Diff[MirrorState] = Diff.record(Diff.at("ext")(_.ext), Diff.at("extras")(_.extras))

  given Show[MirrorState] =
    Show.show(m => s"MirrorState(ext = ${m.ext.show}, extras = ${m.extras.show})")

/** The conversion between the node's [[NodeState]] and the [[MirrorState]]. The position of the
  * node's tip is an argument (it is not a property of the configuration): the anchor, the last
  * block applied, or none before the first.
  *
  * `toMirror` derives what Haskell holds and the node does not (the telescope bounds from the era
  * history, the tips, the PBFT counts, the Shelley transition); `fromMirror` recomputes it from the
  * rest and refuses a mirror that disagrees ([[StateMirrorError.Derived]]). The state of the node
  * is recovered whole: `fromMirror(toMirror(a, s))` is `(a, ` [[StateMirror.normalised]]`(env,
  * s))`.
  */
trait StateMirror[F[_]]:
  def toMirror(anchor: Option[BlockRef], s: NodeState): F[Mirrored[MirrorState]]

  def fromMirror(m: MirrorState): F[(Option[BlockRef], NodeState)]

object StateMirror:
  def apply[F[_]](using
      monad: Monad[F],
      env: Ask[F, ConsensusMirrorEnv],
      errors: Raise[F, StateMirrorError]
  ): StateMirror[F] = Impl[F]

  /** The era a state is in: Byron while the chain-follow state holds the Byron ledger, else the era
    * of the bootstrap mode, else the one the version and epoch put it in (the rule of the
    * node-to-client `GetCurrentEra`, `QueryAnswers.currentEra`).
    */
  def eraOf(env: ConsensusMirrorEnv, s: NodeState): Era =
    if s.byron.ledger.isDefined && s.byron.firstShelleyEpoch.isEmpty then Era.Byron
    else
      s.boot.era
        .filter(_ != Era.Byron)
        .getOrElse(env.forks.eraAt(s.ledger.epoch, s.ledger.params.protocolMajor))

  /** What a state becomes through the mirror: a Shelley-based era's through the ledger side's
    * ([[LedgerMirror.normalised]]), a Byron one's as it is (the flat ledger state is carried
    * whole).
    */
  def normalised(env: ConsensusMirrorEnv, s: NodeState): NodeState =
    if eraOf(env, s) == Era.Byron then s
    else s.copy(ledger = LedgerMirror.normalised(s.ledger))

  /** The gaps of the mirror of `s`, a function of the state alone: the ledger side's, and the
    * Shelley transition that a Shelley-based era's state has and the node does not keep. Byron has
    * no gaps: its state is carried whole.
    */
  def gaps(env: ConsensusMirrorEnv, s: NodeState): Set[Gap] =
    if eraOf(env, s) == Era.Byron then Set.empty
    else LedgerMirror.gaps(s.ledger) + Gap.ShelleyTransition

  /** The block of an annotated tip, none at the origin. */
  private def anchorOf(tip: WithOrigin[AnnTip]): Option[BlockRef] = tip match
    case WithOrigin.Origin => None
    case WithOrigin.At(t) =>
      val (hash, boundary) = t.info match
        case TipInfo.ByronTip(h, ebb)      => (h, ebb == IsEBB.EBB)
        case TipInfo.ShelleyBasedTip(_, h) => (h, false)
      Some(BlockRef(t.slot.toBigInt, hash.bytes, t.blockNo.toBigInt, boundary))

  /** The bounds of the history up to an era. */
  private final case class Bounds(past: Vector[Past], start: Bound)

  private final class Impl[F[_]](using
      monad: Monad[F],
      asks: Ask[F, ConsensusMirrorEnv],
      errors: Raise[F, StateMirrorError]
  ) extends StateMirror[F]:

    // ---- the ledger side, composed: its environment is a part of ours, its errors are wrapped

    private given Ask[F, MirrorEnv] with
      val applicative = monad
      def ask[E2 >: MirrorEnv]: F[E2] = monad.map(asks.ask[ConsensusMirrorEnv])(e => e.ledger)

    private given Raise[F, MirrorError] with
      val functor = monad
      def raise[E2 <: MirrorError, A](e: E2): F[A] = errors.raise(StateMirrorError.Ledger(e))

    private val ledgerMirror: LedgerMirror[F] = LedgerMirror[F]

    // ---- the capabilities, in the shapes the conversions use them

    private def fail[A](e: StateMirrorError): F[A] = errors.raise(e)

    private def check(ok: Boolean, e: => StateMirrorError): F[Unit] =
      if ok then monad.unit else fail(e)

    private def vocab[A](e: Either[VocabError, A]): F[A] =
      e.fold(v => fail(StateMirrorError.Ledger(MirrorError.Vocab(v))), monad.pure)

    private def telescope[A: EraIndexed](
        past: Vector[Past],
        current: Current[A]
    ): F[Telescope[A]] =
      Telescope
        .make(past, current)
        .fold(e => fail(StateMirrorError.Telescope(e)), monad.pure)

    // ---- the pieces

    private def slotNo(n: BigInt): F[SlotNo] = vocab(SlotNo.fromBigInt(n))

    private def withOrigin(anchor: Option[BlockRef]): F[WithOrigin[SlotNo]] =
      anchor.traverse(a => slotNo(a.slot)).map {
        case None       => WithOrigin.Origin
        case Some(slot) => WithOrigin.At(slot)
      }

    /** The slot, block number and hash of the anchor. */
    private def blockOf(a: BlockRef): F[(SlotNo, BlockNo, Hash32)] =
      (slotNo(a.slot), vocab(BlockNo.fromBigInt(a.blockNo)), vocab(Hash32.fromBytes(a.hash))).tupled

    /** The bounds of the history up to `era`: the past eras' and where `era` starts. Total: an era
      * the history does not list, or a past one it leaves open, is an error.
      */
    private def history(
        env: ConsensusMirrorEnv,
        s: NodeState,
        era: Era,
        anchor: Option[BlockRef]
    ): F[Bounds] =
      val summaries = EraHistory.summary(
        s.geometry(env.ledger.geometry),
        env.byron,
        env.forks,
        env.observed,
        era,
        anchor.fold(BigInt(0))(_.slot)
      )
      for
        past <- summaries.zipWithIndex
          .take(era.index)
          .toList
          .traverse((summary, index) =>
            summary.end.fold[F[Past]](fail(StateMirrorError.OpenPastEra(index)))(end =>
              monad.pure(Past(summary.start, end))
            )
          )
        current <- summaries
          .lift(era.index)
          .fold(fail[EraSummary](StateMirrorError.MissingEra(era)))(
            monad.pure
          )
      yield Bounds(past.toVector, current.start)

    // ---- to the mirror

    def toMirror(anchor: Option[BlockRef], s: NodeState): F[Mirrored[MirrorState]] =
      for
        env <- asks.ask[ConsensusMirrorEnv]
        era = eraOf(env, s)
        result <- SomeEra.of(era) match
          case None                    => byron(env, anchor, s)
          case Some(of: SomeEra.Of[e]) => shelley[e](env, era, anchor, s)(using of.era)
      yield result

    private def byron(
        env: ConsensusMirrorEnv,
        anchor: Option[BlockRef],
        s: NodeState
    ): F[Mirrored[MirrorState]] =
      for
        bounds <- history(env, s, Era.Byron, anchor)
        block <- anchor.traverse(blockOf)
        ledger <- telescope(
          bounds.past,
          Current(bounds.start, EraLedgerState.ByronCurrent(s.byron))
        )
        dep <- telescope(bounds.past, Current(bounds.start, ChainDepState.PBft(s.byron.pbft)))
      yield
        val tip = block.fold[WithOrigin[AnnTip]](WithOrigin.Origin) { (slot, blockNo, hash) =>
          val ebb = if anchor.exists(_.boundary) then IsEBB.EBB else IsEBB.NotEBB
          WithOrigin.At(AnnTip(slot, blockNo, TipInfo.ByronTip(hash, ebb)))
        }
        Mirrored(
          MirrorState(
            ExtLedgerState(ledger, HeaderState(tip, dep)),
            MirrorExtras.InByron(s.ledger, s.header.chainDep.state, s.boot, s.extras.protocol)
          ),
          Set.empty
        )

    private def shelley[E <: EraTag](
        env: ConsensusMirrorEnv,
        era: Era,
        anchor: Option[BlockRef],
        s: NodeState
    )(using witness: ShelleyBasedEra[E]): F[Mirrored[MirrorState]] =
      for
        _ <- check(!anchor.exists(_.boundary), StateMirrorError.BoundaryOutsideByron(era))
        at <- withOrigin(anchor)
        bounds <- history(env, s, era, anchor)
        block <- anchor.traverse(blockOf)
        image <- ledgerMirror.to[E](at, s.ledger)
        dep = s.header.chainDep.state
        _ <- check(
          summon[EraIndexed[ChainDepState]].admits(era, dep),
          StateMirrorError.ChainDepFamily(era)
        )
        ledgerTelescope <- telescope(
          bounds.past,
          Current(
            bounds.start,
            EraLedgerState.inEra[E](
              ShelleyLedgerState[E](
                block.fold[WithOrigin[ShelleyTip]](WithOrigin.Origin)((slot, blockNo, hash) =>
                  WithOrigin.At(ShelleyTip(slot, blockNo, hash))
                ),
                image.value.nes,
                ShelleyTransition(Word32.fromUnsigned(0)),
                None
              )
            )
          )
        )
        depTelescope <- telescope(bounds.past, Current(bounds.start, dep))
      yield
        val tip = block.fold[WithOrigin[AnnTip]](WithOrigin.Origin) { (slot, blockNo, hash) =>
          WithOrigin.At(AnnTip(slot, blockNo, TipInfo.ShelleyBasedTip(witness, hash)))
        }
        Mirrored(
          MirrorState(
            ExtLedgerState(ledgerTelescope, HeaderState(tip, depTelescope)),
            MirrorExtras.InShelley(image.value.extras, s.extras)
          ),
          image.gaps + Gap.ShelleyTransition
        )

    // ---- from the mirror

    def fromMirror(m: MirrorState): F[(Option[BlockRef], NodeState)] =
      val ledgerEra = m.ext.ledgerState.era
      val headerEra = m.ext.headerState.chainDep.era
      val anchor = anchorOf(m.ext.headerState.tip)
      for
        env <- asks.ask[ConsensusMirrorEnv]
        _ <- check(ledgerEra == headerEra, StateMirrorError.EraMismatch(ledgerEra, headerEra))
        at <- withOrigin(anchor)
        state <- m.ext.ledgerState.state match
          case EraLedgerState.ByronCurrent(b) => byronBack(ledgerEra, b, m.extras)
          case i: EraLedgerState.InShelleyEra[e] =>
            shelleyBack[e](ledgerEra, at, i.state, m)(using i.era)
        again <- toMirror(anchor, state)
        _ <- verify(env, m, again.value, state)
      yield (anchor, state)

    private def byronBack(era: Era, b: ByronState, extras: MirrorExtras): F[NodeState] =
      extras match
        case MirrorExtras.InByron(ledger, pending, boot, protocol) =>
          monad.pure(
            NodeState(HeaderState.live(pending), ledger, ConsensusExtras(boot, b, protocol))
          )
        case _: MirrorExtras.InShelley => fail(StateMirrorError.ExtrasMismatch(era))

    private def shelleyBack[E <: EraTag](
        era: Era,
        at: WithOrigin[SlotNo],
        s: ShelleyLedgerState[E],
        m: MirrorState
    )(using ShelleyBasedEra[E]): F[NodeState] =
      m.extras match
        case _: MirrorExtras.InByron => fail(StateMirrorError.ExtrasMismatch(era))
        case MirrorExtras.InShelley(ledgerExtras, consensus) =>
          ledgerMirror
            .from[E](at, LedgerImage[E](s.nes, ledgerExtras))
            .map(ledger =>
              NodeState(HeaderState.live(m.ext.headerState.chainDep.state), ledger, consensus)
            )

    /** The mirror of the recovered state must be the mirror it was recovered from, in what is
      * derived: the first piece that is not, is named. The Shelley transition and the Peras round
      * are not compared: the node keeps neither, they are the gaps.
      */
    private def verify(
        env: ConsensusMirrorEnv,
        m: MirrorState,
        again: MirrorState,
        state: NodeState
    ): F[Unit] =
      val (a, b) = (m.ext, again.ext)
      val checks: List[(ConsensusDerived, Boolean)] = List(
        ConsensusDerived.CurrentEra -> (eraOf(env, state) == a.ledgerState.era),
        ConsensusDerived.TelescopeBounds ->
          (a.ledgerState.past === b.ledgerState.past &&
            a.ledgerState.current.start === b.ledgerState.current.start &&
            a.headerState.chainDep.past === b.headerState.chainDep.past &&
            a.headerState.chainDep.current.start === b.headerState.chainDep.current.start),
        ConsensusDerived.LedgerTip ->
          tipsAgree(ledgerTip(a.ledgerState.state), ledgerTip(b.ledgerState.state)),
        ConsensusDerived.HeaderTip -> StateEq.withOrigin(a.headerState.tip, b.headerState.tip),
        ConsensusDerived.ChainDep ->
          (a.headerState.chainDep.state === b.headerState.chainDep.state &&
            lastSlotAgrees(a.headerState.chainDep.state, a.headerState.tip)),
        ConsensusDerived.Extras -> (m.extras === again.extras)
      )
      checks.collectFirst { case (field, false) => field } match
        case Some(field) => fail(StateMirrorError.Derived(field))
        case None        => monad.unit

    /** The last slot of a Praos or TPraos state is the slot of the tip (the last header applied is
      * the last block applied).
      */
    private def lastSlotAgrees(dep: ChainDepState, tip: WithOrigin[AnnTip]): Boolean =
      val last = dep match
        case ChainDepState.PBft(_)   => None
        case ChainDepState.TPraos(t) => Some(t.lastSlot)
        case ChainDepState.Praos(p)  => Some(p.lastSlot)
      val slot: WithOrigin[SlotNo] = tip match
        case WithOrigin.Origin => WithOrigin.Origin
        case WithOrigin.At(t)  => WithOrigin.At(t.slot)
      last.forall(StateEq.withOrigin(_, slot))

    private def tipsAgree(x: Option[WithOrigin[ShelleyTip]], y: Option[WithOrigin[ShelleyTip]]) =
      (x, y) match
        case (Some(a), Some(b)) => StateEq.withOrigin(a, b)
        case (None, None)       => true
        case _                  => false

    /** The tip of a ledger state that has one (a Shelley-based era's). */
    private def ledgerTip(s: EraLedgerState): Option[WithOrigin[ShelleyTip]] = s match
      case EraLedgerState.ByronCurrent(_)    => None
      case i: EraLedgerState.InShelleyEra[e] => Some(i.state.tip)
