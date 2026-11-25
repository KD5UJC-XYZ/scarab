// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.effect.Sync
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{PosixClock, StorageError}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{
  ConwayAdmitted,
  ConwayPoolEnv,
  ConwayPoolLedger,
  Held,
  Mempool,
  MempoolEnv,
  MempoolState,
  Rejection
}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** A transaction of a Conway forging snapshot: held by the pool as validated (`Validated (GenTx
  * (ShelleyBlock proto ConwayEra))`, [[ConwayAdmitted]]), applied again on the ledger of the slot
  * being forged, and the measure it was admitted with, which the pool keeps through revalidation as
  * the reference keeps a ticket's (`TxTicket`, ouroboros-consensus 82ecba32
  * `Mempool/Impl/Common.hs:459-484`). The block takes the transaction as it was validated
  * (`extractTx`, `Shelley/Ledger/Forge.hs:85-92`), its parts as they were received with the flag of
  * its verdict ([[BlockBuilder.piece]]).
  */
final case class ConwayHeld(admitted: ConwayAdmitted, measure: TxMeasure)

/** `getSnapshotFor` of the pool under Conway (`implGetSnapshotFor`, ouroboros-consensus 82ecba32
  * `Mempool/Query.hs:14-55`, as the forge loop takes it, `NodeKernel.hs:697-707`; in the Leios
  * build through `partitionMempool`, `NodeKernel/Forge.hs:786-801` at 2416493b, whose endorser
  * block of a Conway block takes nothing, `ebCapacityTxMeasure` being zero,
  * `Shelley/Ledger/Mempool.hs:962-975`): the held transactions, oldest first, that still apply on
  * the ticked ledger of the slot, each with its measure, and the held ones the rules have no
  * verdict on, by id with the reason, which the forger withdraws from the pool. Only a snapshot is
  * cut into a block ([[BlockBuilder.take]]), so a block is built only of transactions revalidated
  * on the state it is forged on. The constructor is the pool's: the snapshot is made where the
  * transactions were revalidated.
  */
final case class ConwaySnapshot private[node] (
    held: Vector[ConwayHeld],
    withheld: Map[Bytes, Rejection]
)

object ConwaySnapshot:
  /** A snapshot that holds no transaction. */
  val empty: ConwaySnapshot = ConwaySnapshot(Vector.empty, Map.empty)

/** Where the forger takes its Conway snapshot from: the pool's `getSnapshotFor` of the slot, on the
  * ledger state ticked into it, whose tip `tip` is the block's parent (`getTipHash ticked`), with
  * the clock of a transaction in a block of that slot, the era history of the chain the block is
  * forged on ([[ConwayPoolEnv.of]]).
  */
trait ConwaySnapshots[F[_]]:
  def forSlot(
      ticked: ConwayPoolLedger,
      tip: ChainSync.Point,
      slot: BigInt,
      clock: PosixClock
  )(using Raise[F, StorageError]): F[ConwaySnapshot]

object ConwaySnapshots:
  /** A source of empty snapshots, for forgers of tests that take no transactions. */
  def unadmitted[F[_]: Applicative]: ConwaySnapshots[F] = new ConwaySnapshots[F]:
    def forSlot(ticked: ConwayPoolLedger, tip: ChainSync.Point, slot: BigInt, clock: PosixClock)(
        using Raise[F, StorageError]
    ) = Applicative[F].pure(ConwaySnapshot.empty)

  /** The node's: the snapshot of `mempool` 's held Conway transactions on `ticked`, the stored
    * outputs their rules read fetched from `utxoStore` first, as the reference reads them from the
    * forker of the unticked state (`roforkerReadTables`, `NodeKernel.hs:697-703` at
    * ouroboros-consensus 82ecba32), revalidated at `slot` in the environment `environment` gives,
    * with `clock` ([[MempoolState.conwaySnapshot]]). The reference keeps the pool's own snapshot
    * where `tip` and `slot` are the pool's (`Query.hs:29-36`); this always revalidates.
    */
  def ofPool[F[_]: Sync](
      mempool: Mempool[F],
      utxoStore: Option[UtxoReadView[F]],
      environment: F[MempoolEnv]
  ): ConwaySnapshots[F] = new ConwaySnapshots[F]:
    def forSlot(ticked: ConwayPoolLedger, tip: ChainSync.Point, slot: BigInt, clock: PosixClock)(
        using Raise[F, StorageError]
    ) =
      for
        pool <- mempool.snapshot
        env <- environment
        reads = pool.entries.map(_.held).collect { case h @ Held.InConway(_) => h.in }
        utxo <- UtxoStore.complete(utxoStore, ticked.utxo, MempoolState.readSet(reads))
        judged <- Sync[F].blocking(
          MempoolState.conwaySnapshot(
            pool.entries,
            ticked.withUtxo(utxo),
            ConwayPoolEnv.of(env, slot, clock)
          )
        )
        (kept, withheld) = judged
      yield ConwaySnapshot(
        kept.map((entry, admitted) => ConwayHeld(admitted, entry.measure)),
        withheld.map((entry, reason) => entry.id -> reason).toMap
      )
