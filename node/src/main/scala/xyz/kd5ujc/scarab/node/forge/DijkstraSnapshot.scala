// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure

/** A transaction of a Dijkstra forging snapshot: held by the pool, applied again on the ledger of
  * the slot being forged, and the measure it was admitted with, which the pool keeps through
  * revalidation as the reference keeps a ticket's (`snapshotTxs`, ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/API.hs:447-450`).
  */
final case class DijkstraHeld(tx: DijkstraTx, measure: TxMeasure)

/** `getSnapshotFor` of the pool under Dijkstra (`Mempool/API.hs:187-203`, ouroboros-consensus
  * 2416493b, as the forge loop takes it, `NodeKernel/Forge.hs:795-797`): the held transactions,
  * oldest first, that still apply on the ticked ledger of the slot, each with its measure. Only a
  * snapshot is partitioned into a ranking block ([[BlockBuilder.ranking]]), so a block is built
  * only of transactions the pool revalidated. The constructor is the pool's: the snapshot is made
  * where the transactions were revalidated.
  */
final case class DijkstraSnapshot private[node] (held: Vector[DijkstraHeld])

object DijkstraSnapshot:
  /** A snapshot that holds no transaction. */
  val empty: DijkstraSnapshot = DijkstraSnapshot(Vector.empty)

/** Where the forger takes its Dijkstra snapshot from: the pool's `getSnapshotFor` of the slot, on
  * the state ticked into it.
  */
trait DijkstraSnapshots[F[_]]:
  def forSlot(ticked: NewEpochState[Dijkstra], slot: BigInt): F[DijkstraSnapshot]

object DijkstraSnapshots:
  /** The node's, for now: the pool admits no Dijkstra transaction (it has no ledger of Dijkstra,
    * [[xyz.kd5ujc.scarab.node.mempool.PoolLedger.of]]; Dijkstra's MEMPOOL rule is #1281's, and the
    * pool's admission and revalidation of Dijkstra transactions follow it, #54), so every snapshot
    * is [[DijkstraSnapshot.empty]] and every ranking block is forged without transactions.
    */
  def unadmitted[F[_]: Applicative]: DijkstraSnapshots[F] =
    (_, _) => Applicative[F].pure(DijkstraSnapshot.empty)
