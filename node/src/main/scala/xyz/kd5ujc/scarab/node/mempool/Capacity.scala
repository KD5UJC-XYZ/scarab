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
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTx
import xyz.kd5ujc.scarab.ledger.rules.DijkstraTxMeasures
import xyz.kd5ujc.scarab.network.Word32
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.protocol.{Era, LeiosFetch}
import xyz.kd5ujc.scarab.storage.LeiosEb

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
    * override and otherwise at least one and the least number whose bytes reach the override; one
    * when a block takes no transaction bytes.
    *
    * Scarab does not wrap where the reference does (approved deviation: the owner's standing rule,
    * https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849, and its record for
    * this case, https://github.com/KD5UJC-XYZ/scarab/issues/845#issuecomment-6747479546). The
    * reference takes the number and the capacity in `Word32`, rounding the number down where
    * rounding it up would overflow and adding the byte sizes modulo 2^32 (ouroboros-consensus
    * 82ecba32 `Mempool/Capacity.hs:62-79`, `Ledger/SupportsMempool.hs:468-504`), so a large
    * override can leave it no reference-script bytes at all (4,096 blocks of 1 MiB are 2^32, which
    * is 0). Here the override is a `Word32`, as the reference reads it, and the number and the
    * capacity are exact: a `Word32` divided by a positive block size, and that many blocks, fit a
    * `Long`.
    */
  def of(params: ProtocolParams, capacityOverride: Option[Word32]): TxMeasure =
    blocks(blockUnit(params), capacityOverride)

  /** `computeMempoolCapacity` of the ledger's era (`Mempool/Capacity.hs:69-95`, ouroboros-consensus
    * 2416493b): before Dijkstra [[of]] in the measure of the era ([[Shape]]),
    * `blockCapacityTxMeasure` of the era injected into the hard-fork combinator's measure, whose
    * endorser-block reservation is zero; under Dijkstra one ranking block and one endorser block's
    * reservation ([[ofDijkstra]]), the reservation the ledger computed, and checked, when it was
    * made ([[DijkstraLedger.of]]).
    */
  def of(ledger: PoolLedger, capacityOverride: Option[Word32]): TxMeasure = ledger match
    case flat: FlatLedger =>
      blocks(Shape.of(flat.era).project(blockUnit(flat.params)), capacityOverride)
    case d: DijkstraLedger => blocks(dijkstraBlockUnit(d.params) + d.reservation, capacityOverride)

  /** `blockCapacityTxMeasure` of Dijkstra (`TxLimits (ShelleyBlock p DijkstraEra)`,
    * ouroboros-consensus 2416493b
    * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:980-994`):
    * Conway's `blockCapacityConwayMeasure` (`:759-773`), the body size less
    * [[FixedBlockBodyOverhead]] and the block's execution units (`blockCapacityAlonzoMeasure`,
    * `txsMaxBytes`, `:433-443, 641-652`), and the reference-script bytes of a block, which Dijkstra
    * reads from its parameters (`ppMaxRefScriptSizePerBlockG = ppLensHKD .
    * hkdMaxRefScriptSizePerBlockL`, cardano-ledger 9df9f98c
    * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/PParams.hs:1195`) where Conway's is the
    * constant of [[blockUnit]]. What one ranking block takes from the pool.
    */
  def dijkstraBlockUnit(params: ProtocolParams): TxMeasure =
    TxMeasure(
      (params.maxBlockBodySize - FixedBlockBodyOverhead).max(0).toLong,
      params.maxBlockExUnits.mem,
      params.maxBlockExUnits.steps,
      params.maxRefScriptSizePerBlock.toLong
    )

  /** The parameters' `maxEndorserBlockReferencesSize` is more than the LeiosFetch codec's message
    * limit can carry (`maxLeiosEbBytesSize`, [[LeiosFetch.MaxEbBytes]]): `guardLeiosWireLimit`
    * (ouroboros-consensus 2416493b `Shelley/Ledger/Mempool.hs:924-939`) fails hard there, when the
    * mempool's capacity is first computed from such parameters, and the node stops.
    */
  final case class EndorserBlockReferencesOverWire(parameter: BigInt, wire: Int) extends DomainError

  /** `leiosEndorserBlockCapacity` (ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:884-913`), `ebCapacityTxMeasure` under Dijkstra (`:994`): what one
    * endorser block may hold, from the parameters. The closure takes the transaction bytes, the
    * execution units and the reference-script bytes of `maxEndorserBlockTxsSize`,
    * `maxEndorserBlockExUnits` and `maxRefScriptSizePerEndorserBlock`; the references take
    * `maxEndorserBlockReferencesSize` less the widest head of a body
    * ([[LeiosEb.referencesCapacity]]), as a transaction is charged its reference only. The capacity
    * passes `guardLeiosWireLimit` first, as the reference's pattern on the record forces it
    * ([[EndorserBlockReferencesOverWire]]), so the references' bytes are a `Word32` 's.
    */
  def endorserBlockCapacity(
      params: ProtocolParams
  ): Either[EndorserBlockReferencesOverWire, EbMeasure] =
    Either.cond(
      params.maxEndorserBlockReferencesSize <= LeiosFetch.MaxEbBytes,
      EbMeasure(
        TxMeasure(
          params.maxEndorserBlockTxsSize.toLong,
          params.maxEndorserBlockExUnits.mem,
          params.maxEndorserBlockExUnits.steps,
          params.maxRefScriptSizePerEndorserBlock.toLong
        ),
        LeiosEb.referencesCapacity(params.maxEndorserBlockReferencesSize.toLong)
      ),
      EndorserBlockReferencesOverWire(params.maxEndorserBlockReferencesSize, LeiosFetch.MaxEbBytes)
    )

  /** `mempoolEbReservation` of `ebCapacityTxMeasure` under Dijkstra (ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:994-995`): the closure part of [[endorserBlockCapacity]], the
    * transaction bytes, the execution units and the reference-script bytes one endorser block may
    * hold, unscaled: the scale of the forger's share applies to an endorser block it forges, not to
    * the pool (`computeMempoolCapacity`, `Mempool/Capacity.hs:69-82`).
    */
  def endorserBlockReservation(
      params: ProtocolParams
  ): Either[EndorserBlockReferencesOverWire, TxMeasure] =
    endorserBlockCapacity(params).map(_.closure)

  /** `computeMempoolCapacity` under Dijkstra (ouroboros-consensus 2416493b
    * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Capacity.hs:69-95`):
    * the unit is one ranking block ([[dijkstraBlockUnit]]) plus one endorser block's reservation
    * ([[endorserBlockReservation]]), and the override is counted in units of their bytes together,
    * as [[of]] counts Conway's, and exactly, as [[of]] is, where the reference adds and multiplies
    * in `Word32` (the same approved deviation). The pool's capacity under Dijkstra.
    */
  def ofDijkstra(
      params: ProtocolParams,
      capacityOverride: Option[Word32]
  ): Either[EndorserBlockReferencesOverWire, TxMeasure] =
    endorserBlockReservation(params).map(eb =>
      blocks(dijkstraBlockUnit(params) + eb, capacityOverride)
    )

  /** The measure a Dijkstra transaction is admitted with and keeps (`txMeasure` of
    * `TxLimits (ShelleyBlock p DijkstraEra)`, ouroboros-consensus 2416493b
    * `Shelley/Ledger/Mempool.hs:980-987`): `txMeasureAlonzo` 's size, [[DijkstraTx.size]] plus
    * [[PerTxOverhead]] (`txInBlockSize`, `:445-458`), and execution units, `totExUnits`, which is
    * `getTotalExUnits` (cardano-ledger 9df9f98c
    * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:510-515`) and in Dijkstra counts the
    * sub-transactions' redeemers too (`Dijkstra/Tx.hs:417-421`,
    * [[DijkstraTxMeasures.totalExUnits]]); and `txMeasureRefScripts` 's `refScriptBytes`
    * (`Mempool.hs:775-798`), the top-level body's only ([[dijkstraRefScriptBytes]]). The checks of
    * the three against `maxTxSize`, `maxTxExUnits` and `maxRefScriptSizePerTx` are admission's,
    * with Dijkstra's failures, and are not here.
    */
  def dijkstraMeasure(tx: DijkstraTx, refScriptBytes: Long): TxMeasure =
    val units = DijkstraTxMeasures.totalExUnits(tx)
    TxMeasure(tx.size + PerTxOverhead, units.mem, units.steps, refScriptBytes)

  /** `txNonDistinctRefScriptsSize` as the mempool's measure reads it (cardano-ledger 9df9f98c
    * `eras/conway/impl/src/Cardano/Ledger/Conway/UTxO.hs:160-170`, from
    * `Shelley/Ledger/Mempool.hs:789`, ouroboros-consensus 2416493b): the reference scripts of the
    * transaction's own inputs and reference inputs, not its sub-transactions', which LEDGER's
    * `batchNonDistinctRefScriptsSize` counts. `size` names the reference script of an output of the
    * UTxO set the pool holds the transaction against.
    */
  def dijkstraRefScriptBytes(
      size: xyz.kd5ujc.scarab.ledger.TxIn => Option[Long],
      tx: DijkstraTx
  ): Long =
    DijkstraTxMeasures.refScriptsSize(size, tx.body)

  private def blocks(unit: TxMeasure, capacityOverride: Option[Word32]): TxMeasure =
    val count = capacityOverride.fold(DefaultBlocks) { o =>
      val bytes = o.toLong
      if unit.bytes <= 0 then 1L
      else (bytes / unit.bytes + (if bytes % unit.bytes == 0 then 0L else 1L)).max(1L)
    }
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
  def measure(entry: Entry): TxMeasure = entry.held match
    case Held.Flat(_, _, tx) =>
      val units = tx.witnesses.totalExUnits
      TxMeasure(tx.size + PerTxOverhead, units.mem, units.steps, entry.refScriptBytes)
    case Held.InDijkstra(held) => dijkstraMeasure(held.read.flat, entry.refScriptBytes)

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

  /** [[phase1]] in the measure of the ledger's era ([[Shape]]): from Shelley to Mary the size alone
    * (`txInBlockSize`, `Shelley/Ledger/Mempool.hs:402-415`, ouroboros-consensus 82ecba32, the
    * instances of lines 522-544), from Alonzo the size and the execution units (`txMeasureAlonzo`,
    * lines 590-612, 668-677, 752-772).
    */
  def phase1(tx: Tx, ledger: FlatLedger): Either[NonEmptyList[PredicateFailure], TxMeasure] =
    val params = ledger.params
    Shape.of(ledger.era) match
      case Shape.Bytes =>
        Either.cond(
          tx.size <= params.maxTxSize,
          TxMeasure(tx.size + PerTxOverhead, BigInt(0), BigInt(0), 0L),
          NonEmptyList.one(
            PredicateFailure
              .utxo(UtxoFailure.MaxTxSize(Mismatch(BigInt(tx.size), params.maxTxSize)))
          )
        )
      case Shape.Alonzo | Shape.Conway => phase1(tx, params)

  /** The second phase (`txMeasurePhase2`, `txMeasureRefScripts`, Conway): the reference-script
    * bytes of the transaction's inputs and reference inputs in the state after the held
    * transactions against the per-transaction maximum, both the reference's 64-bit `Int`. Before
    * Conway the phase is trivial (`TrivialTxMeasurePhase2`, `Shelley/Ledger/Mempool.hs:522-544,
    * 668-677, 751-761`, ouroboros-consensus 82ecba32, Babbage's included): nothing is measured.
    */
  def phase2(ledger: FlatLedger, tx: Tx): Either[PredicateFailure, Long] = ledger match
    case conway: MempoolLedger =>
      val size = MempoolState.refScriptBytes(conway, tx)
      val limit = RefScripts.MaxRefScriptSizePerTx.toLong
      Either.cond(
        size <= limit,
        size,
        PredicateFailure.TxRefScriptsSizeTooBig(Mismatch(size, limit))
      )
    case _: EarlierLedger[?] => Right(0L)

  /** Which components of the measure an era has, in the hard-fork combinator's measure
    * (`hardForkInjTxMeasurePhase1` and `hardForkInjTxMeasurePhase2`,
    * `Cardano/CanHardFork.hs:226-278`, ouroboros-consensus 82ecba32): Byron to Mary measure bytes
    * alone (`fromByteSize`, no execution units), Alonzo and Babbage bytes and execution units, and
    * only Conway and Dijkstra reference-script bytes (`fromTrivial` before them).
    */
  enum Shape:
    case Bytes, Alonzo, Conway

    /** `m` with the components this shape does not have set to zero. */
    def project(m: TxMeasure): TxMeasure = this match
      case Bytes  => TxMeasure(m.bytes, BigInt(0), BigInt(0), 0L)
      case Alonzo => m.copy(refScriptsBytes = 0L)
      case Conway => m

  object Shape:
    def of(era: Era): Shape = era match
      case Era.Byron | Era.Shelley | Era.Allegra | Era.Mary => Bytes
      case Era.Alonzo | Era.Babbage                         => Alonzo
      case Era.Conway | Era.Dijkstra                        => Conway

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
