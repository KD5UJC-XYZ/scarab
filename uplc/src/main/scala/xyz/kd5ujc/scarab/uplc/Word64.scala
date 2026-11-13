// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

/** An unsigned 64-bit integer, the reference's `Word64`: the tag of a `constr` term
  * (`UntypedPlutusCore/Core/Type.hs:101`) and, through `Index`, a de Bruijn index
  * (`PlutusCore/DeBruijn/Internal.hs:102`), plutus 1.70.0.0 (`507cab8c`). The bits are kept in a
  * `Long`; order, text and conversion read them unsigned.
  */
opaque type Word64 = Long

object Word64:
  val Zero: Word64 = 0L
  val MaxValue: Word64 = -1L

  private val Modulus = BigInt(1) << 64

  /** The word whose bits are `bits` (a negative `Long` is a word of 2^63 or more). */
  def fromBits(bits: Long): Word64 = bits

  /** `n` when it is in `[0, 2^64)`. */
  def fromBigInt(n: BigInt): Option[Word64] =
    Option.when(n.signum >= 0 && n.bitLength <= 64)(n.longValue)

  /** `n` when it is not negative. */
  def fromInt(n: Int): Option[Word64] = Option.when(n >= 0)(n.toLong)

  extension (w: Word64)
    def bits: Long = w
    def toBigInt: BigInt = if w >= 0 then BigInt(w) else BigInt(w) + Modulus

    /** The value as an `Int` when it fits one (an index into a vector, say). */
    def toIntOption: Option[Int] = Option.when(w >= 0 && w <= Int.MaxValue)(w.toInt)
    def show: String = java.lang.Long.toUnsignedString(w)

  given Ordering[Word64] = (x, y) => java.lang.Long.compareUnsigned(x, y)
