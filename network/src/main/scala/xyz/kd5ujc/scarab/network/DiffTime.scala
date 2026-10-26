// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import scala.concurrent.duration.FiniteDuration

/** `Word32`: the two's-complement bits of an `Int`, read as unsigned. Every bit pattern is a word,
  * so there is no failure to report.
  */
opaque type Word32 = Int

object Word32:
  def fromUnsigned(bits: Int): Word32 = bits

  /** The word of `n`, if it is in `0 .. 2^32 - 1`. */
  def fromLong(n: Long): Option[Word32] = Option.when(n >= 0 && n <= 0xffffffffL)(n.toInt)

  extension (w: Word32) def toLong: Long = Integer.toUnsignedLong(w)

/** `DiffTime` (`Data.Time.Clock`): an exact, signed number of picoseconds without bound (a `Pico`
  * `Fixed`). Its arithmetic is the reference's: products are exact and a quotient is floored to a
  * picosecond (`Fixed` `/` is `div`), so no intermediate overflows.
  */
opaque type DiffTime = BigInt

object DiffTime:
  val Zero: DiffTime = BigInt(0)

  def picoseconds(picos: BigInt): DiffTime = picos

  def of(duration: FiniteDuration): DiffTime = BigInt(duration.toNanos) * 1000

  extension (d: DiffTime)
    def toPicoseconds: BigInt = d
    def -(other: DiffTime): DiffTime = d - other

    /** `fromIntegral n * d`. */
    def times(n: Long): DiffTime = d * n

    /** `d / fromIntegral n` for a positive `n`: floored to a picosecond. */
    def dividedBy(n: Long): DiffTime =
      val (quotient, remainder) = d /% n
      if remainder.signum < 0 then quotient - 1 else quotient
