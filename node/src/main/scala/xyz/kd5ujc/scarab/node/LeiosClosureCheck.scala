// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.{EbClosureVerdict, ProtVerParity}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.era.rules.{DijkstraClosure, LeiosClosureDefect}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Sts, Tick, TickFailure}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState, ShelleyBasedEra, SlotNo}
import xyz.kd5ujc.scarab.node.mempool.{DijkstraPoolEnv, DijkstraState, Rejection}
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** The check a voter runs on an endorser block's closure before it votes (`validateEbClosure`,
  * `ouroboros-consensus/src/ouroboros-consensus/LeiosVoting.hs:446-565`, ouroboros-consensus
  * 2416493b): the closure's transactions, read back from the store and decoded as an endorser block
  * holds them, applied in order with `Intervene` to the announcing block's ledger state ticked to
  * the endorser block's slot, which is the state they meet if a later block certifies the endorser
  * block. A transaction the node's [[LeiosTxCache]] holds as validated under the protocol version's
  * parity of that state is reapplied, only its state-dependent checks run again (`reapplyTx`,
  * `:511-518`); every other one is applied in full (`applyTx`, `:519-542`), and those that apply
  * are recorded in the cache as validated under that parity, also when a later one sinks the
  * closure (`recordValidated`, `abandon`, `:544-565`).
  */
object LeiosClosureCheck:

  /** How a closure's transaction is taken (`decide`, `LeiosVoting.hs:495-502` at 2416493b). */
  enum Step derives CanEqual:
    /** The cache holds it as validated under the state's parity: `reapplyTx`. */
    case Reapply

    /** Anything else: `applyTx`, with every check. */
    case Apply

  object Step:
    /** `decide` of a transaction against the cache's `cached` under `parity`. */
    def of(cached: Option[Either[Unit, ProtVerParity]], parity: ProtVerParity): Step =
      if cached.contains(Right(parity)) then Reapply else Apply

  /** What [[goValidate]] reached: the verdict, and the transactions it validated in full that
    * applied, which the cache records (`newly`).
    */
  final case class Checked(
      verdict: EbClosureVerdict[Rejection, Nothing],
      validated: Vector[LeiosTxHash]
  )

  /** `applyChainTick OmitLedgerEvents lcfg slot lsBase` (`LeiosVoting.hs:485, 491-493` at
    * 2416493b): the announcing block's state `nes`, unticked, ticked to the endorser block's slot
    * by Dijkstra's TICK. That slot is the announcing block's own, so the tick never leaves the
    * epoch, and so never the era.
    */
  def ticked(
      rules: Globals,
      nes: NewEpochState[Dijkstra],
      slot: SlotNo
  ): Either[TickFailure, DijkstraState] =
    Sts(Tick.sts[Dijkstra](using ShelleyBasedEra.SDijkstra))(rules, (), nes, slot)
      .map(DijkstraState.of)

  /** `parity = protVerParity (leiosCurrentProtVer lcfg st0)` (`LeiosVoting.hs:486` at 2416493b). */
  def parityOf(state: DijkstraState): ProtVerParity = ProtVerParity.of(BigInt(state.protocolMajor))

  /** `goValidate` (`LeiosVoting.hs:504-542` at 2416493b): each transaction of the closure, in its
    * order, on the state the ones before it left, at the slot and in the context of `env`: by
    * [[DijkstraState.reapplyIntervening]] where its step is [[Step.Reapply]], by
    * [[DijkstraState.applyIntervening]] (`applyTx lcfg Intervene slot`) where it is [[Step.Apply]].
    * The first that does not apply ends the check, its refusal the verdict (`abandon`, `:555-557`);
    * a refusal without a verdict of the rules ([[Rejection.DijkstraUnjudged]]) ends it as well, and
    * no vote is cast for the block. A valid closure counts the transactions reapplied and those
    * applied (`EbClosureValid`).
    */
  def goValidate(
      closure: Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra], Step)],
      state: DijkstraState,
      env: DijkstraPoolEnv
  ): Checked =
    final case class At(state: DijkstraState, reapplied: Int, validated: Vector[LeiosTxHash])
    closure
      .foldLeft[Either[(Rejection, Vector[LeiosTxHash]), At]](Right(At(state, 0, Vector.empty))) {
        case (Right(at), (_, tx, Step.Reapply)) =>
          at.state
            .reapplyIntervening(tx, env)
            .bimap(_ -> at.validated, next => at.copy(state = next, reapplied = at.reapplied + 1))
        case (Right(at), (txh, tx, Step.Apply)) =>
          at.state
            .applyIntervening(tx, env)
            .bimap(
              _ -> at.validated,
              next => at.copy(state = next, validated = at.validated :+ txh)
            )
        case (stopped @ Left(_), _) => stopped
      }
      .fold(
        (refusal, validated) => Checked(EbClosureVerdict.Invalid(refusal), validated),
        at => Checked(EbClosureVerdict.Valid(at.reapplied, at.validated.size), at.validated)
      )

  /** `goValidate` with every transaction applied in full: the check of a node with an empty cache.
    */
  def applyClosure(
      closure: Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])],
      state: DijkstraState,
      env: DijkstraPoolEnv
  ): EbClosureVerdict[Rejection, Nothing] =
    goValidate(closure.map((h, t) => (h, t, Step.Apply)), state, env).verdict

  /** `validateEbClosure` (`LeiosVoting.hs:457-490` at 2416493b) of the endorser block at `point` on
    * `state`, the announcing block's ticked to its slot ([[ticked]]): the closure from `lookup`
    * (`lookupEbClosure`), resolved ([[DijkstraClosure.resolveLeiosClosure]]), which is
    * [[EbClosureVerdict.Unreadable]] when it is missing or a transaction does not decode
    * (`EbClosureUnreadable`, `:475-476`); then the stored outputs every transaction's inputs name
    * read from `utxoStore` (`getTransactionKeySets` of all of them, and `resolveValues`,
    * `:478-485`); then each transaction's step decided against one read of `cache` under the
    * state's parity, [[goValidate]], and the transactions it validated in full recorded in `cache`
    * in one update, whatever the verdict.
    */
  def validateEbClosure[F[_]: Sync](
      lookup: EbHash => F[Option[Vector[(LeiosTxHash, Bytes)]]],
      utxoStore: Option[UtxoReadView[F]],
      cache: LeiosTxCache[F],
      point: EbPoint,
      state: DijkstraState,
      env: DijkstraPoolEnv
  )(using Raise[F, StorageError]): F[EbClosureVerdict[Rejection, LeiosClosureDefect]] =
    lookup(point.hash).flatMap { stored =>
      DijkstraClosure.resolveLeiosClosure(point.hash, stored) match
        case Left(unreadable) =>
          Sync[F].pure(EbClosureVerdict.Unreadable(unreadable))
        case Right(closure) =>
          val parity = parityOf(state)
          for
            utxo <- UtxoStore.complete(
              utxoStore,
              state.utxo,
              MirrorDijkstra.inputs(closure.map(_._2))
            )
            cached <- cache.lookup
            decided = closure.map((h, t) => (h, t, Step.of(cached(h), parity)))
            checked <- Sync[F].blocking(goValidate(decided, state.withUtxo(utxo), env))
            _ <- cache.insertApplied(checked.validated, parity).whenA(checked.validated.nonEmpty)
          yield checked.verdict
    }
