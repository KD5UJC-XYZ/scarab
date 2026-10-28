// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

/** The reference's `CostingInteger`: a 64-bit integer whose arithmetic saturates at the bounds
  * instead of wrapping (`Data/SatInt.hs` of plutus 1.70.0.0 at `507cab8c`, lines 51-108 and the
  * 64-bit branch at lines 139-195; `type CostingInteger = SatInt`, `ExMemory` and `ExCPU` wrap it,
  * `PlutusCore/Evaluation/Machine/ExMemory.hs` lines 72-75 and 114). Every cost-model parameter,
  * memory usage, cost and remaining budget of the reference is one of these.
  *
  * Each operation is the exact result clamped to `[minBound, maxBound]`; `plusSI`, `minusSI` and
  * `timesSI` pick the bound by the signs of the operands only when the machine operation overflows,
  * which is the same thing.
  */
opaque type SatInt = Long

object SatInt:
  val MaxBound: SatInt = Long.MaxValue
  val MinBound: SatInt = Long.MinValue
  val Zero: SatInt = 0L

  def apply(value: Long): SatInt = value

  /** `fromInteger` (lines 96-99): an integer beyond the bounds becomes the nearer bound. */
  def fromBigInt(value: BigInt): SatInt =
    if value.isValidLong then value.toLong
    else if value.signum > 0 then Long.MaxValue
    else Long.MinValue

  extension (a: SatInt)
    def toLong: Long = a

    /** `plusSI`: on overflow both operands have the sign of the bound. */
    def +(b: SatInt): SatInt =
      val r = a + b
      if ((a ^ r) & (b ^ r)) >= 0 then r
      else if a > 0 then Long.MaxValue
      else Long.MinValue

    /** `minusSI`: on overflow the operands have different signs; `b < 0` means upwards. */
    def -(b: SatInt): SatInt =
      val r = a - b
      if ((a ^ b) & (a ^ r)) >= 0 then r
      else if b < 0 then Long.MaxValue
      else Long.MinValue

    /** `timesSI`: the product fits when the high word is the sign extension of the low one. */
    def *(b: SatInt): SatInt =
      val low = a * b
      if Math.multiplyHigh(a, b) == (low >> 63) then low
      else if (a ^ b) < 0 then Long.MinValue
      else Long.MaxValue

    /** `dividedBy` (lines 104-108): a bound is left as it is, otherwise Haskell's `div`, which
      * rounds towards negative infinity.
      */
    def dividedBy(divisor: Int): SatInt =
      if divisor == 0 then Long.MaxValue
      else if a == Long.MaxValue || a == Long.MinValue then a
      else Math.floorDiv(a, divisor.toLong)

    def max(b: SatInt): SatInt = Math.max(a, b)
    def min(b: SatInt): SatInt = Math.min(a, b)
    def <(b: SatInt): Boolean = a < b
    def >(b: SatInt): Boolean = a > b
    def <=(b: SatInt): Boolean = a <= b
