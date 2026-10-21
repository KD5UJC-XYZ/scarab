// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.ledger.{
  CapacityDimension,
  Mismatch,
  PredicateFailure,
  ProtocolParams,
  RefScripts,
  Tx,
  UtxoFailure
}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure

/** What a transaction counts for in the mempool, and how much the mempool may hold: the measures of
  * `ouroboros-consensus-cardano` `Shelley/Ledger/Mempool.hs` (`txInBlockSize`, `txMeasureAlonzo`,
  * `txMeasureRefScripts`, `blockCapacityConwayMeasure`) and `Mempool/Capacity.hs`
  * (`computeMempoolCapacity`) at `82ecba329d7d054340bf707d44fe6e9ac27cec40`. The vector is bytes,
  * execution-unit memory, execution-unit steps and reference-script bytes. The mempool enforces
  * [[of]] and LocalTxMonitor reports it, so the two are one value.
  */
object Capacity:
  /** `perTxOverhead`: what a transaction counts for beyond its ledger size. */
  val PerTxOverhead = 4L

  /** `fixedBlockBodyOverhead`: what a block's body spends beyond its transactions. */
  val FixedBlockBodyOverhead = 1024L

  /** The number of blocks the capacity is when no override is configured. */
  val DefaultBlocks = 2L

  /** One block's worth of the measures (`blockCapacityConwayMeasure`). */
  def blockUnit(params: ProtocolParams): TxMeasure =
    TxMeasure(
      (params.maxBlockBodySize - FixedBlockBodyOverhead).max(0).toLong,
      params.maxBlockExUnits.mem,
      params.maxBlockExUnits.steps,
      RefScripts.MaxRefScriptSizePerBlock.toLong
    )

  /** `computeMempoolCapacity`: a whole number of blocks, [[DefaultBlocks]] when there is no
    * override and otherwise at least one and the least number whose bytes reach the override.
    */
  def of(params: ProtocolParams, capacityOverride: Option[Long]): TxMeasure =
    val unit = blockUnit(params)
    val count = capacityOverride.fold(DefaultBlocks)(bytes =>
      if unit.bytes <= 0 then 1L else math.max(1L, (bytes + unit.bytes - 1) / unit.bytes)
    )
    TxMeasure(
      unit.bytes * count,
      unit.exUnitsMemory * count,
      unit.exUnitsSteps * count,
      unit.refScriptsBytes * count
    )

  /** The measure a held transaction counts for: its ledger size (`sizeTxF`, which leaves out the
    * `is_valid` flag, not the bytes received) plus [[PerTxOverhead]], its execution units and the
    * reference-script bytes it was measured with. It is fixed when the transaction is added and
    * kept through revalidation, as the reference keeps a ticket's measure.
    */
  def measure(entry: Entry): TxMeasure =
    val units = entry.tx.witnesses.totalExUnits
    TxMeasure(entry.tx.size + PerTxOverhead, units.mem, units.steps, entry.refScriptBytes.toLong)

  /** The first phase (`txMeasurePhase1`, `txMeasureAlonzo`): the transaction's size against the
    * maximum and its execution units, pointwise, against the maximum. The two checks accumulate,
    * size first, as `Validation` does in the reference, so a transaction over both limits is
    * refused with both failures. The reference-script component is zero until [[phase2]].
    */
  def phase1(tx: Tx, params: ProtocolParams): Either[NonEmptyList[PredicateFailure], TxMeasure] =
    val units = tx.witnesses.totalExUnits
    val tooBig = Option.unless(tx.size <= params.maxTxSize)(
      PredicateFailure.utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), params.maxTxSize)))
    )
    val tooMuch = Option.unless(units.fits(params.maxTxExUnits))(
      PredicateFailure.utxo(UtxoFailure.ExUnitsTooBig(Mismatch(units, params.maxTxExUnits)))
    )
    NonEmptyList
      .fromList(tooBig.toList ++ tooMuch.toList)
      .toLeft(TxMeasure(tx.size + PerTxOverhead, units.mem, units.steps, 0L))

  /** The second phase (`txMeasurePhase2`, `txMeasureRefScripts`, Conway): the reference-script
    * bytes of the transaction's inputs and reference inputs in the state after the held
    * transactions against the per-transaction maximum.
    */
  def phase2(ledger: MempoolLedger, tx: Tx): Either[PredicateFailure, Int] =
    val size = MempoolState.refScriptBytes(ledger, tx)
    Either.cond(
      size <= RefScripts.MaxRefScriptSizePerTx,
      size,
      PredicateFailure.TxRefScriptsSizeTooBig(Mismatch(size, RefScripts.MaxRefScriptSizePerTx))
    )

  extension (m: TxMeasure)
    /** Within `limit` in every component. */
    def fits(limit: TxMeasure): Boolean =
      m.bytes <= limit.bytes && m.exUnitsMemory <= limit.exUnitsMemory &&
        m.exUnitsSteps <= limit.exUnitsSteps && m.refScriptsBytes <= limit.refScriptsBytes

    /** Component-wise difference; a component is negative when `other` exceeds it. */
    def -(other: TxMeasure): TxMeasure =
      TxMeasure(
        m.bytes - other.bytes,
        m.exUnitsMemory - other.exUnitsMemory,
        m.exUnitsSteps - other.exUnitsSteps,
        m.refScriptsBytes - other.refScriptsBytes
      )

    /** The first component, in the order of the vector, that exceeds `limit`. */
    def exceeded(limit: TxMeasure): Option[CapacityDimension] =
      if m.bytes > limit.bytes then Some(CapacityDimension.Bytes)
      else if m.exUnitsMemory > limit.exUnitsMemory then Some(CapacityDimension.ExUnitsMemory)
      else if m.exUnitsSteps > limit.exUnitsSteps then Some(CapacityDimension.ExUnitsSteps)
      else if m.refScriptsBytes > limit.refScriptsBytes then Some(CapacityDimension.RefScriptBytes)
      else None

    def component(dimension: CapacityDimension): BigInt = dimension match
      case CapacityDimension.Bytes          => BigInt(m.bytes)
      case CapacityDimension.ExUnitsMemory  => m.exUnitsMemory
      case CapacityDimension.ExUnitsSteps   => m.exUnitsSteps
      case CapacityDimension.RefScriptBytes => BigInt(m.refScriptsBytes)
