// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.Async
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.{Held, Snapshot}
import xyz.kd5ujc.scarab.node.ntc.CborOut
import xyz.kd5ujc.scarab.protocol.ProtocolError

/** The mempool as the node-to-client LocalTxMonitor sees it (ouroboros-consensus
  * `Ouroboros.Consensus.MiniProtocol.LocalTxMonitor.Server`): a frozen snapshot of the held
  * transactions in arrival order, the slot they were validated for, the size and the capacity.
  *
  * What the reference reports, reproduced here:
  *   - the slot is the one after the tip (the state is ticked to the successor slot);
  *   - a transaction counts for its [[Capacity.measure]];
  *   - the capacity is the one the pool enforces ([[MempoolState.capacity]], [[Capacity]]), taken
  *     from the state as it is, never recomputed here.
  */
object LocalMonitor:
  /** The era index of Conway in the hard-fork combinator's era list. */
  private val Era = LocalSubmission.ConwayEra

  /** `[era, #6.24(tx)]`: the transaction as `LocalTxSubmission` takes it. */
  def wrapTx(raw: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(Era).tag(24).bytes(raw); ()
    }

  /** `[era, h'id']`: a Conway transaction id as `LocalTxMonitor` takes it. */
  def wrapId(id: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(Era).bytes(id); ()
    }

  /** `HasTx`: whether a held transaction has the id `item` names, read as the reference reads it
    * ([[GenTxId.decode]]). The reference answers with `Set.member` over the ids of its snapshot
    * (`snapshotHasTx`, lines 549 and 583-586 of
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Impl/Common.hs`,
    * ouroboros-consensus 82ecba32), whose order compares the hash alone ([[GenTxId.hash]]): a held
    * transaction is found under any era's tag, and under any of Byron's four ids with its hash,
    * though the pool holds Conway transactions only. An id the era's decoder refuses fails the
    * reference's codec (`decodeTxId` of `MsgHasTx`,
    * `ouroboros-network/protocols/lib/Ouroboros/Network/Protocol/LocalTxMonitor/Codec.hs:135-137`,
    * ouroboros-network c45735a5), which ends the connection: here a refusal of the decode, which
    * the server raises as one.
    */
  def has(held: Set[Bytes])(item: Bytes): Either[ProtocolError, Boolean] =
    GenTxId.decode(item).bimap(ProtocolError.UndecodableTxId(_), id => held(id.hash.bytes))

  /** The snapshot of a pool state. Pure. */
  def snapshot(state: MempoolState): Snapshot =
    val ids = state.entries.map(_.id)
    val held = state.entries.toSet.map((e: Entry) => e.id)
    Snapshot(
      state.tipSlot + 1,
      state.entries.map(e => Held(wrapTx(e.raw), Capacity.measure(e))),
      has(held),
      state.capacity,
      (state.tipSlot + 1, ids)
    )

  def source[F[_]: Async](mempool: Mempool[F]): LocalTxMonitorServer.Source[F] =
    new LocalTxMonitorServer.Source[F]:
      def acquire = mempool.snapshot.map(snapshot)
      def awaitChange(seen: Snapshot) =
        mempool
          .awaitChange(s => (s.tipSlot + 1, s.entries.map(_.id)) == seen.identity)
          .map(snapshot)
