// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.leios.EbClosureVerdict
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
  * holds them, applied in order by the mempool's `applyTx` with `Intervene` to the announcing
  * block's ledger state ticked to the endorser block's slot, which is the state they meet if a
  * later block certifies the endorser block.
  *
  * Every transaction is validated in full. The reference reapplies the ones its `LeiosTxCache` says
  * were validated before under the protocol version of the state (`decide`, `:495-502`), so only
  * the state-dependent checks run again for them (`reapplyTx`, `:511-518`). A full application runs
  * those checks too, and the static ones a cached transaction passed already under that version:
  * its verdict is the reference's, or stricter where a static check that passed then fails now; and
  * it takes longer (#54).
  */
object LeiosClosureCheck:

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

  /** `goValidate` (`LeiosVoting.hs:504-542` at 2416493b): each transaction of the closure, in its
    * order, by `applyTx lcfg Intervene slot` on the state the ones before it left
    * ([[DijkstraState.applyIntervening]]), at the slot and in the context of `env`. The first that
    * does not apply ends the check, its refusal the verdict (`abandon`, `:555-557`); a refusal
    * without a verdict of the rules ([[Rejection.DijkstraUnjudged]]) ends it as well, and no vote
    * is cast for the block.
    */
  def applyClosure(
      closure: Vector[(LeiosTxHash, Tx[TxLevel.Top, Dijkstra])],
      state: DijkstraState,
      env: DijkstraPoolEnv
  ): EbClosureVerdict[Rejection, Nothing] =
    closure
      .foldLeftM(state)((current, entry) => current.applyIntervening(entry._2, env))
      .fold(EbClosureVerdict.Invalid(_), _ => EbClosureVerdict.Valid)

  /** `validateEbClosure` (`LeiosVoting.hs:457-490` at 2416493b) of the endorser block at `point` on
    * `state`, the announcing block's ticked to its slot ([[ticked]]): the closure from `lookup`
    * (`lookupEbClosure`), resolved ([[DijkstraClosure.resolveLeiosClosure]]), which is
    * [[EbClosureVerdict.Unreadable]] when it is missing or a transaction does not decode
    * (`EbClosureUnreadable`, `:475-476`); then the stored outputs every transaction's inputs name
    * read from `utxoStore` (`getTransactionKeySets` of all of them, and `resolveValues`,
    * `:478-485`); then [[applyClosure]].
    */
  def validateEbClosure[F[_]: Sync](
      lookup: EbHash => F[Option[Vector[(LeiosTxHash, Bytes)]]],
      utxoStore: Option[UtxoReadView[F]],
      point: EbPoint,
      state: DijkstraState,
      env: DijkstraPoolEnv
  )(using Raise[F, StorageError]): F[EbClosureVerdict[Rejection, LeiosClosureDefect]] =
    lookup(point.hash).flatMap { stored =>
      DijkstraClosure.resolveLeiosClosure(point.hash, stored) match
        case Left(unreadable) =>
          Sync[F].pure(EbClosureVerdict.Unreadable(unreadable))
        case Right(closure) =>
          UtxoStore
            .complete(utxoStore, state.utxo, MirrorDijkstra.inputs(closure.map(_._2)))
            .flatMap(utxo => Sync[F].blocking(applyClosure(closure, state.withUtxo(utxo), env)))
    }
