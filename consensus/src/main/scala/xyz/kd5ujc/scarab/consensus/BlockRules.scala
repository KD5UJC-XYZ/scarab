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

/** Observable follower milestones. */
enum FollowerEvent:
  case Connected(intersection: ChainSync.Point)
  case Extended(block: BlockRef, epoch: BigInt, peerTip: ChainSync.Tip, era: Era)
  case RolledBack(from: ChainSync.Point, to: ChainSync.Point, depth: Int)
  case EpochEntered(epoch: BigInt, first: BlockRef)
  case Immutable(block: BlockRef)
  case Reconnecting(reason: PeerError, attempt: Int)

  /** The block at `point` has no verdict here: the evaluator failed on its own scripts (a defect
    * the block triggers, not a missing implementation). It is not adopted, not recorded as invalid,
    * and the chain stays behind it.
    */
  case Held(point: ChainSync.Point, failure: xyz.kd5ujc.scarab.ledger.LedgerFailure.Unavailable)

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
