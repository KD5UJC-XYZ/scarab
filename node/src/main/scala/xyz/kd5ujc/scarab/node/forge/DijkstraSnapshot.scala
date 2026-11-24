// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{PosixClock, StorageError}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{
  Capacity,
  DijkstraPoolEnv,
  DijkstraState,
  Held,
  Mempool,
  MempoolEnv,
  MempoolState,
  Rejection
}
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** A transaction of a Dijkstra forging snapshot: held by the pool, applied again on the ledger of
  * the slot being forged, and the measure it was admitted with, which the pool keeps through
  * revalidation as the reference keeps a ticket's (`snapshotTxs`, ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/API.hs:447-450`). The
  * transaction is the era's (`Tx TopTx DijkstraEra`) as it was validated, with the flag of its
  * verdict (`extractTx` of the snapshot's `Validated` transaction,
  * `Shelley/Ledger/Forge.hs:162-163` of ouroboros-consensus-cardano at 2416493b), which the ranking
  * block and the endorser block write in its block form
  * ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.forBlockInclusion]]). Unlike [[ConwayHeld]]
  * it keeps the transaction, not what it was admitted with: the era's transaction holds the bytes
  * of its parts, which both blocks are made of, and the forger reads nothing else of it.
  */
final case class DijkstraHeld(tx: Tx[TxLevel.Top, Dijkstra], measure: TxMeasure)

/** `getSnapshotFor` of the pool under Dijkstra (`Mempool/API.hs:187-203`, ouroboros-consensus
  * 2416493b, as the forge loop takes it, `NodeKernel/Forge.hs:795-797`): the held transactions,
  * oldest first, that still apply on the ticked ledger of the slot, each with its measure, and the
  * held ones the rules have no verdict on, by id with the reason, which the forger withdraws from
  * the pool. Only a snapshot is partitioned into a ranking block ([[BlockBuilder.ranking]]), so a
  * block is built only of transactions the pool revalidated. The constructor is the pool's: the
  * snapshot is made where the transactions were revalidated.
  */
final case class DijkstraSnapshot private[node] (
    held: Vector[DijkstraHeld],
    withheld: Map[Bytes, Rejection]
)

object DijkstraSnapshot:
  /** A snapshot that holds no transaction. */
  val empty: DijkstraSnapshot = DijkstraSnapshot(Vector.empty, Map.empty)

  /** A snapshot of `held`, with none withheld. */
  private[node] def apply(held: Vector[DijkstraHeld]): DijkstraSnapshot =
    DijkstraSnapshot(held, Map.empty)

/** Where the forger takes its Dijkstra snapshot from: the pool's `getSnapshotFor` of the slot, on
  * the state ticked into it, with the clock of a transaction in a block of that slot.
  */
trait DijkstraSnapshots[F[_]]:
  def forSlot(
      ticked: NewEpochState[Dijkstra],
      slot: BigInt,
      clock: PosixClock
  )(using Raise[F, StorageError]): F[DijkstraSnapshot]

object DijkstraSnapshots:
  /** A source of empty snapshots, for forgers of tests that take no transactions. */
  def unadmitted[F[_]: Applicative]: DijkstraSnapshots[F] = new DijkstraSnapshots[F]:
    def forSlot(ticked: NewEpochState[Dijkstra], slot: BigInt, clock: PosixClock)(using
        Raise[F, StorageError]
    ) = Applicative[F].pure(DijkstraSnapshot.empty)

  /** The node's: the snapshot of `mempool` 's held Dijkstra transactions on `ticked`, the stored
    * outputs their rules read fetched from `utxoStore` first, as the reference reads them from the
    * forker of the unticked state (`readUntickedTables`, `Mempool/Query.hs:107-117`,
    * ouroboros-consensus 2416493b), revalidated at `slot` in the environment `environment` gives
    * ([[MempoolState.dijkstraSnapshot]]).
    */
  def ofPool[F[_]: Sync](
      mempool: Mempool[F],
      utxoStore: Option[UtxoReadView[F]],
      environment: F[MempoolEnv]
  ): DijkstraSnapshots[F] = new DijkstraSnapshots[F]:
    def forSlot(ticked: NewEpochState[Dijkstra], slot: BigInt, clock: PosixClock)(using
        Raise[F, StorageError]
    ) =
      for
        pool <- mempool.snapshot
        env <- environment
        state = DijkstraState.of(ticked)
        reads = pool.entries.map(_.held).collect { case h @ Held.InDijkstra(_) => h.in }
        utxo <- UtxoStore.complete(utxoStore, state.utxo, MempoolState.readSet(reads))
        judged <- Sync[F].blocking(
          MempoolState.dijkstraSnapshot(
            pool.entries,
            state.withUtxo(utxo),
            DijkstraPoolEnv.of(env, slot).copy(clock = clock)
          )
        )
        (kept, withheld) = judged
      yield DijkstraSnapshot(
        kept.map((entry, admitted) => DijkstraHeld(admitted.read.tx, Capacity.measure(entry))),
        withheld.map((entry, reason) => entry.id -> reason).toMap
      )
