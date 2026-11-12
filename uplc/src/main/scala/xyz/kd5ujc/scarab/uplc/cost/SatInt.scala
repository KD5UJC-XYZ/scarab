// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

/** The reference's saturating 64-bit integer, `CostingInteger` (`Data.SatInt`,
  * `satint/src/Data/SatInt.hs` at plutus 1.70.0.0 `507cab8c`, lines 51-230): addition, subtraction
  * and multiplication that would overflow give `maxBound` or `minBound` by the sign of the true
  * result, `negate minBound` is `maxBound`, and an integer outside the range clamps to the nearer
  * bound (`fromInteger`, lines 96-99).
  */
opaque type SatInt = Long

object SatInt:
  val zero: SatInt = 0L
  val maxValue: SatInt = Long.MaxValue
  val minValue: SatInt = Long.MinValue

  def apply(value: Long): SatInt = value

  /** `fromInteger`: an integer outside the 64-bit range is the nearer bound. */
  def clamp(value: BigInt): SatInt =
    if value > BigInt(Long.MaxValue) then Long.MaxValue
    else if value < BigInt(Long.MinValue) then Long.MinValue
    else value.toLong

  extension (a: SatInt)
    def toLong: Long = a
    def toBigInt: BigInt = BigInt(a)

    /** `plusSI`: overflow only when both operands have the sign of the bound it reaches. */
    def +(b: SatInt): SatInt =
      val r = a + b
      if ((a ^ r) & (b ^ r)) >= 0 then r
      else if a > 0 then Long.MaxValue
      else Long.MinValue

    /** `minusSI`. */
    def -(b: SatInt): SatInt =
      val r = a - b
      if ((a ^ b) & (a ^ r)) >= 0 then r
      else if a >= 0 then Long.MaxValue
      else Long.MinValue

    /** `timesSI`: a product that needs the high word saturates by the signs of the operands. */
    def *(b: SatInt): SatInt =
      val lo = a * b
      val hi = Math.multiplyHigh(a, b)
      if hi == (lo >> 63) then lo
      else if (a > 0) == (b > 0) then Long.MaxValue
      else Long.MinValue

    /** `dividedBy` (`Data/SatInt.hs:101-110`): by a natural `divisor`; a zero divisor gives
      * `maxBound`, a saturated value is left as it is, otherwise Haskell's flooring `div`.
      */
    def dividedBy(divisor: Long): SatInt =
      if divisor == 0L then Long.MaxValue
      else if a == Long.MaxValue || a == Long.MinValue then a
      else Math.floorDiv(a, divisor)

    def unary_- : SatInt = if a == Long.MinValue then Long.MaxValue else -a

    def max(b: SatInt): SatInt = math.max(a, b)
    def min(b: SatInt): SatInt = math.min(a, b)
    def compare(b: SatInt): Int = java.lang.Long.compare(a, b)
    def <(b: SatInt): Boolean = a < b
    def <=(b: SatInt): Boolean = a <= b
    def >(b: SatInt): Boolean = a > b
    def >=(b: SatInt): Boolean = a >= b

  given Ordering[SatInt] = Ordering.Long
