// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import xyz.kd5ujc.scarab.ledger.{Header, PeerError}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** The node's chain-extension rule: the state after `header` /`block`, or a [[ValidationError]].
  * Two outcomes are no verdict on the block: a [[xyz.kd5ujc.scarab.ledger.StorageError]] and a
  * [[xyz.kd5ujc.scarab.ledger.ChainError.StateOutOfRange]] (the ledger state left the range of its
  * types, which stops the node as the reference's `compactCoinOrError` does).
  */
trait BlockRules[F[_], S]:
  def apply(state: S, header: Header, block: Bytes)(using
      cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ValidationError],
      cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError],
      cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ChainError]
  ): F[S]

object BlockRules:
  /** Build a rule whose failures are supplied when it is applied, never when it is built. */
  def from[F[_], S](
      run: (S, Header, Bytes) => (
          cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ValidationError],
          cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError],
          cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ChainError]
      ) ?=> F[S]
  ): BlockRules[F, S] = new BlockRules[F, S]:
    def apply(state: S, header: Header, block: Bytes)(using
        cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ValidationError],
        cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.StorageError],
        cats.mtl.Raise[F, xyz.kd5ujc.scarab.ledger.ChainError]
    ): F[S] = run(state, header, block)

/** Why the node has no verdict on a block: its own implementation could not judge it. None of these
  * is a refusal of the block by the protocol's rules, so nobody is blamed for it.
  */
enum BlockNoVerdict:
  /** The evaluator cannot evaluate a script of the block at `point` (`LedgerFailure.Unavailable`).
    */
  case Evaluation(
      point: ChainSync.Point,
      unavailable: xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable
  )

  /** A crypto provider failed on the block (`VerificationDefect`). */
  case Verification(defect: xyz.kd5ujc.scarab.ledger.VerificationDefect)

  /** A quantity of the ledger state the block's rules convert is outside its implementation type
    * (`ChainError.StateOutOfRange`). The specification gives no verdict (`Coin` is a natural
    * number); only a malformed genesis reaches it: a Conway genesis deposit outside `Word64` at the
    * upgrade into Conway, or an epoch of 2^32 slots or more for the transition counter (#573, ADR
    * 0017's amendment of 2026-11-07).
    */
  case StateOutOfRange(error: xyz.kd5ujc.scarab.ledger.ChainError)

  /** The rules threw while they judged the block. */
  case Defect(cause: Throwable)

  /** The evaluator's failure as a refusal would carry it. */
  def message: String = this match
    case Evaluation(point, unavailable) =>
      val error = xyz.kd5ujc.scarab.ledger
        .ValidationError(point, xyz.kd5ujc.scarab.ledger.BlockFailure.Tx(unavailable))
      s"evaluation unavailable: ${error.message}"
    case Verification(defect)   => s"verification failed: ${defect.message}"
    case StateOutOfRange(error) => error.message
    case Defect(cause)          => s"the rules threw: $cause"

  /** Whether the cause can pass on its own, so that the block is judged again (issue 693). A total
    * match with no default, so a new cause must be placed here.
    */
  def recovery: BlockNoVerdict.Recovery =
    import BlockNoVerdict.Recovery.{Deterministic, Passing}
    import xyz.kd5ujc.scarab.plutus.BackendFailure
    this match
      case Evaluation(_, unavailable) =>
        unavailable.reason.reason match
          // a Throwable on the evaluator's worker (an OutOfMemoryError, a rejected task) or no
          // result from it: the environment, which may change
          case BackendFailure.Defect(_) | BackendFailure.NoResult => Passing
          // the same script exhausts the evaluator's stack of fixed size again, and a budget, a
          // builtin or a costing function this build lacks stays lacking until a restart
          case BackendFailure.StackExhausted | BackendFailure.BudgetOutOfRange(_) |
              BackendFailure.BuiltinUnavailable(_) | BackendFailure.CostingUnmirrored(_) =>
            Deterministic
      case Verification(_) => Passing
      // a quantity of the state stays out of range for the same state
      case StateOutOfRange(_) => Deterministic
      case Defect(_)          => Passing

object BlockNoVerdict:
  /** Whether a cause of no verdict can pass on its own. */
  enum Recovery:
    /** It may pass (memory pressure, a rejected task, a provider's failure): the block is judged
      * again on a capped schedule.
      */
    case Passing

    /** The same build and state answer the same: the block is judged once, until a restart. */
    case Deterministic

/** When a block without a verdict is judged again (issue 693). */
enum NoVerdictRetry:
  /** Not in this run: the cause does not pass on its own, or the block is our own. */
  case Never

  /** After `delay` from the end of the attempt. */
  case After(delay: scala.concurrent.duration.FiniteDuration)

/** Observable follower milestones. */
enum FollowerEvent:
  case Connected(intersection: ChainSync.Point)
  case Extended(block: BlockRef, epoch: BigInt, peerTip: ChainSync.Tip, era: Era)
  case RolledBack(from: ChainSync.Point, to: ChainSync.Point, depth: Int)
  case EpochEntered(epoch: BigInt, first: BlockRef)
  case Immutable(block: BlockRef)
  case Reconnecting(reason: PeerError, attempt: Int)

  /** Attempt `attempt` on `block` ended without a verdict: the node's own implementation could not
    * judge it. The block is neither adopted nor recorded as invalid, nobody is blamed, and the node
    * goes on (issue 567); `retry` says when it is judged again (issue 693).
    */
  case NoVerdict(block: ChainSync.Point, cause: BlockNoVerdict, attempt: Int, retry: NoVerdictRetry)

  /** The node judges again `block`, which it had no verdict on: attempt `attempt` (issue 693). */
  case NoVerdictRetried(block: ChainSync.Point, attempt: Int)

  /** The first block of a new era was applied (`from` is empty for the chain's first era). */
  case EraEntered(from: Option[Era], to: Era, first: BlockRef, epoch: BigInt)

  /** An epoch of bootstrap-mode headers is complete: how each one was established. */
  case EpochValidated(
      epoch: BigInt,
      era: Era,
      threshold: Long,
      overlay: Long,
      unchecked: Long
  )

  /** A Byron epoch is complete: its boundary and main blocks (every main block is fully validated),
    * how many of them were made by a genesis key other than the round-robin one for their slot, and
    * the transactions they carried.
    */
  case ByronEpochValidated(
      epoch: BigInt,
      boundary: Long,
      verified: Long,
      offSchedule: Long,
      txs: Long
  )

  /** The full ledger entered `epoch`: sizes, pots, protocol version and the running count of
    * transactions whose Plutus scripts were evaluated (phase 2).
    */
  case LedgerEpoch(
      epoch: BigInt,
      utxo: Int,
      accounts: Int,
      pools: Int,
      treasury: BigInt,
      reserves: BigInt,
      protocolVersion: (BigInt, BigInt),
      plutus: xyz.kd5ujc.scarab.ledger.PlutusStats
  )

trait FollowerEvents[F[_]]:
  def emit(event: FollowerEvent): F[Unit]
