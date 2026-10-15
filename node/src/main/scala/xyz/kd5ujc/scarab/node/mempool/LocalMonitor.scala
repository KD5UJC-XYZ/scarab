// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.effect.Async
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.{Held, Snapshot, TxMeasure}
import xyz.kd5ujc.scarab.ledger.{ConwayRules, ProtocolParams}
import xyz.kd5ujc.scarab.node.ntc.CborOut

/** The mempool as the node-to-client LocalTxMonitor sees it (ouroboros-consensus
  * `Ouroboros.Consensus.MiniProtocol.LocalTxMonitor.Server`): a frozen snapshot of the held
  * transactions in arrival order, the slot they were validated for, the size and the capacity.
  *
  * What the reference reports, reproduced here:
  *   - the slot is the one after the tip (the state is ticked to the successor slot);
  *   - a transaction counts for its ledger size (the serialised transaction without its `is_valid`
  *     flag, `sizeTxF`) plus 4 bytes (`perTxOverhead`), its execution units and its
  *     reference-script bytes;
  *   - the capacity is `computeMempoolCapacity`: a whole number of block-sized units, two by
  *     default, `ceil(bytes / unit)` of them when the byte capacity is overridden, where a unit is
  *     the maximum block body less 1024 bytes, the maximum block execution units and the 1 MiB
  *     reference-script limit of a block.
  */
object LocalMonitor:
  /** The era index of Conway in the hard-fork combinator's era list. */
  private val Era = LocalSubmission.ConwayEra
  private val PerTxOverhead = 4L
  private val FixedBlockBodyOverhead = 1024L

  /** `[era, #6.24(tx)]`: the transaction as `LocalTxSubmission` takes it. */
  def wrapTx(raw: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(Era).tag(24).bytes(raw); ()
    }

  /** `[era, h'id']`: the transaction id as `LocalTxMonitor` takes and returns it. */
  def wrapId(id: Bytes): Bytes =
    CborOut.build { w =>
      w.array(2).uint(Era).bytes(id); ()
    }

  /** The id inside a wrapped one, if the era is Conway (another era's id is never held). */
  def unwrapId(item: Bytes): Option[Bytes] =
    Cbor.decode(item).toOption.flatMap { root =>
      root.value match
        case Value.Arr(Vector(era, id)) =>
          (era.value, id.value) match
            case (Value.UInt(e), Value.ByteString(b)) if e == BigInt(Era) => Some(b)
            case _                                                        => None
        case _ => None
    }

  /** One block's worth of the mempool's measures. */
  def blockUnit(params: ProtocolParams): TxMeasure =
    TxMeasure(
      (params.maxBlockBodySize - FixedBlockBodyOverhead).max(0).toLong,
      params.maxBlockExUnits.mem,
      params.maxBlockExUnits.steps,
      ConwayRules.MaxRefScriptSizePerBlock.toLong
    )

  /** `computeMempoolCapacity` for a mempool of `capacityBytes`. */
  def capacity(params: ProtocolParams, capacityBytes: Long): TxMeasure =
    val unit = blockUnit(params)
    val count =
      if unit.bytes <= 0 then 1L else math.max(1L, (capacityBytes + unit.bytes - 1) / unit.bytes)
    TxMeasure(
      unit.bytes * count,
      unit.exUnitsMemory * count,
      unit.exUnitsSteps * count,
      unit.refScriptsBytes * count
    )

  def measure(entry: Entry): TxMeasure =
    val units = entry.tx.witnesses.totalExUnits
    // the ledger's size of the transaction (`sizeTxF`, which leaves out the is_valid flag), not the
    // bytes received
    TxMeasure(entry.tx.size + PerTxOverhead, units.mem, units.steps, entry.refScriptBytes.toLong)

  /** The snapshot of a pool state. Pure. */
  def snapshot(state: MempoolState): Snapshot =
    val ids = state.entries.map(_.id)
    val held = state.entries.toSet.map((e: Entry) => e.id)
    Snapshot(
      state.tipSlot + 1,
      state.entries.map(e => Held(wrapTx(e.raw), measure(e))),
      item => unwrapId(item).exists(held),
      capacity(state.base.params, state.capacityBytes),
      (state.tipSlot + 1, ids)
    )

  def source[F[_]: Async](mempool: Mempool[F]): LocalTxMonitorServer.Source[F] =
    new LocalTxMonitorServer.Source[F]:
      def acquire = mempool.snapshot.map(snapshot)
      def awaitChange(seen: Snapshot) =
        mempool
          .awaitChange(s => (s.tipSlot + 1, s.entries.map(_.id)) == seen.identity)
          .map(snapshot)
