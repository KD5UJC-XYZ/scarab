// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.Async
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.{Held, Snapshot}
import xyz.kd5ujc.scarab.node.ntc.CborOut
import xyz.kd5ujc.scarab.protocol.{Era, ProtocolError}

/** The mempool as the node-to-client LocalTxMonitor sees it (ouroboros-consensus
  * `Ouroboros.Consensus.MiniProtocol.LocalTxMonitor.Server`): a frozen snapshot of the held
  * transactions in arrival order, the slot they were validated for, the size and the capacity.
  *
  * What the reference reports, reproduced here:
  *   - the transactions are those of the era of the pool's ledger, the era of the slot after the
  *     tip, each tagged with it (`LocalTxMonitor/Server.hs:21-106`, ouroboros-consensus 82ecba32);
  *   - the slot is the one after the tip (the state is ticked to the successor slot);
  *   - a transaction counts for its [[Capacity.measure]];
  *   - the capacity is the one the pool enforces ([[MempoolState.capacity]], [[Capacity]]), taken
  *     from the state as it is, never recomputed here.
  */
object LocalMonitor:
  /** `[era, #6.24(tx)]`: a transaction of `era` as `LocalTxSubmission` takes it and `NextTx` lists
    * it: the hard-fork combinator's `GenTx` of the era the pool holds it in (`dispatchEncoder`,
    * `HardFork/Combinator/Serialisation/SerialiseNodeToClient.hs:362-374`, ouroboros-consensus
    * 82ecba32), the Shelley-based `GenTx` being CBOR-in-CBOR (`wrapCBORinCBOR`,
    * `Shelley/Ledger/Mempool.hs:256-259`).
    */
  def wrapTx(era: Era, raw: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(era.index).tag(24).bytes(raw); ()
    }

  /** `[era, h'id']`: a transaction id of `era` as `LocalTxMonitor` takes it. */
  def wrapId(era: Era, id: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(era.index).bytes(id); ()
    }

  /** `HasTx`: whether a held transaction has the id `item` names, read as the reference reads it
    * ([[GenTxId.decode]]). The reference answers with `Set.member` over the ids of its snapshot
    * (`snapshotHasTx`, lines 549 and 583-586 of
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Impl/Common.hs`,
    * ouroboros-consensus 82ecba32), whose order compares the hash alone ([[GenTxId.hash]]): a held
    * transaction is found under any era's tag, and under any of Byron's four ids with its hash,
    * though the pool holds transactions of its ledger's era only. An id the era's decoder refuses
    * fails the reference's codec (`decodeTxId` of `MsgHasTx`,
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
      state.entries.map(e => Held(wrapTx(e.era, e.raw), Capacity.measure(e))),
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
