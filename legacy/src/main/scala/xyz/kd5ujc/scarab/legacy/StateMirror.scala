// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.legacy

import cats.Monad
import cats.mtl.{Ask, Raise}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.BlockRef
import xyz.kd5ujc.scarab.consensus.state.*
import xyz.kd5ujc.scarab.ledger.state.{
  BlockNo,
  EraTag,
  Gap,
  Hash32,
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

/** The conversion of the flat node state of the old rules ([[FlatNodeState]]) to the
  * [[MirrorState]] (FROZEN, J2.9; the other direction went with the old rules). The position of the
  * node's tip is an argument (it is not a property of the configuration): the anchor, the last
  * block applied, or none before the first.
  *
  * `toMirror` derives what Haskell holds and the node does not (the telescope bounds from the era
  * history, the tips, the PBFT counts, the Shelley transition), and puts the state in the terms of
  * the era-indexed rules ([[LegacySeed]]).
  */
trait StateMirror[F[_]]:
  def toMirror(anchor: Option[BlockRef], s: FlatNodeState): F[Mirrored[MirrorState]]

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
  def eraOf(env: ConsensusMirrorEnv, s: FlatNodeState): Era =
    if s.byron.ledger.isDefined && s.byron.firstShelleyEpoch.isEmpty then Era.Byron
    else
      s.boot.era
        .filter(_ != Era.Byron)
        .getOrElse(env.forks.eraAt(s.ledger.epoch, s.ledger.params.protocolMajor))

  /** The gaps of the mirror of `s`, a function of the state alone: the ledger side's, and the
    * Shelley transition that a Shelley-based era's state has and the node does not keep. Byron has
    * no gaps: its state is carried whole.
    */
  def gaps(env: ConsensusMirrorEnv, s: FlatNodeState): Set[Gap] =
    if eraOf(env, s) == Era.Byron then Set.empty
    else LedgerMirror.gaps(s.ledger) + Gap.ShelleyTransition

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
        s: FlatNodeState,
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

    def toMirror(anchor: Option[BlockRef], s: FlatNodeState): F[Mirrored[MirrorState]] =
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
        s: FlatNodeState
    ): F[Mirrored[MirrorState]] =
      ByronMirror
        .of(
          env,
          anchor,
          s.header.chainDep.state,
          s.extras,
          MirrorProvenance.recorded(Set.empty, env.observed)
        )
        .fold(fail(_), m => monad.pure(Mirrored(m, Set.empty)))

    private def shelley[E <: EraTag](
        env: ConsensusMirrorEnv,
        era: Era,
        anchor: Option[BlockRef],
        s: FlatNodeState
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
          LegacySeed(
            MirrorState(
              ExtLedgerState(ledgerTelescope, HeaderState(tip, depTelescope)),
              MirrorExtras.InShelley(NodeExtras(image.value.extras.plutus), s.extras),
              MirrorProvenance.recorded(image.gaps + Gap.ShelleyTransition, env.observed)
            ),
            image.value.extras
          ),
          image.gaps + Gap.ShelleyTransition
        )
