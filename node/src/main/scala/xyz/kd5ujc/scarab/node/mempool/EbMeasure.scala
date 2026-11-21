// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.storage.LeiosEb

/** What transactions count for in a Dijkstra endorser block, `DijkstraEbMeasure`
  * (ouroboros-consensus 2416493b
  * `ouroboros-consensus-cardano/src/shelley/Ouroboros/Consensus/Shelley/Ledger/Mempool.hs:822-843`):
  * the measure of the closure, whose transactions meet block-like limits, and the bytes of the
  * references the body holds for them, without the body's head. Its sum and order are pointwise, as
  * the reference derives them (`Measure via InstantiatedAt Generic`).
  */
final case class EbMeasure(closure: TxMeasure, references: Long):
  def +(other: EbMeasure): EbMeasure =
    EbMeasure(closure + other.closure, references + other.references)

  /** Within `limit` in every component. */
  def within(limit: EbMeasure): Boolean =
    Capacity.fits(closure)(limit.closure) && references <= limit.references

object EbMeasure:
  val zero: EbMeasure = EbMeasure(TxMeasure.zero, 0L)

  /** `txEbMeasureDijkstra` (`Mempool.hs:845-855` at 2416493b): one transaction's cost in an
    * endorser block, its pool measure for the closure and, for its reference, the
    * [[LeiosEb.itemSize]] of the measure's bytes. Those are the transaction's size plus
    * [[Capacity.PerTxOverhead]], not the length the body records, which is the block form's: the
    * reference prices the reference so, and the two differ in width only past a boundary of the
    * size's head.
    */
  def of(measure: TxMeasure): EbMeasure = EbMeasure(measure, LeiosEb.itemSize(measure.bytes))

/** The share of the endorser-block capacity the forger forges with, `readEbCapacityScale`
  * (`NodeKernel.hs:497-512` at ouroboros-consensus 2416493b): a factor in `[0, 1]`, the whole
  * capacity without adaptive endorsements. The reference's factor is a `Rational` its controller
  * keeps in `[MinScale, 1]`; the range is in the type here.
  */
opaque type EbCapacityScale = Rational

object EbCapacityScale:
  /** The whole capacity: the scale of a node that does not adapt its endorser blocks. */
  val full: EbCapacityScale = Rational.one

  /** `factor` as a scale, if it is in `[0, 1]`. */
  def of(factor: Rational): Option[EbCapacityScale] =
    Option.when(factor >= Rational.zero && factor <= Rational.one)(factor)

  extension (scale: EbCapacityScale)
    /** `scaleDijkstraEbMeasure` (`Mempool.hs:856-882` at 2416493b): every component of `measure`
      * scaled by the factor and rounded down.
      */
    def apply(measure: EbMeasure): EbMeasure =
      def scaled(n: BigInt): BigInt = (scale * Rational(n)).floor
      def bytes(n: Long): Long = scaled(BigInt(n)).toLong
      EbMeasure(
        TxMeasure(
          bytes(measure.closure.bytes),
          scaled(measure.closure.exUnitsMemory),
          scaled(measure.closure.exUnitsSteps),
          bytes(measure.closure.refScriptsBytes)
        ),
        bytes(measure.references)
      )

    def factor: Rational = scale
