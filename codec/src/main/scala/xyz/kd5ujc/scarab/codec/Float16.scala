// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

/** The IEEE 754 binary16 ("half") format, which CBOR major type 7 carries as additional information
  * 25 (RFC 8949 section 3.3, appendix D). Written out here because the JDK method that does this
  * (`Float.float16ToFloat`) is from JDK 20.
  */
object Float16:
  /** The value of the 16 bits `bits` (the low 16 bits of the argument). */
  def toFloat(bits: Int): Float =
    val sign = (bits >> 15) & 1
    val exponent = (bits >> 10) & 0x1f
    val fraction = bits & 0x3ff
    val magnitude: Float =
      if exponent == 0 then fraction * (1.0f / (1 << 24)) // zero and the subnormals, 2^-24 steps
      else if exponent == 31 then if fraction == 0 then Float.PositiveInfinity else Float.NaN
      else java.lang.Float.intBitsToFloat(((exponent + 112) << 23) | (fraction << 13))
    if sign == 1 then -magnitude else magnitude

  /** The 16 bits nearest to `f` (round half to even; overflow is infinity, any NaN is the quiet NaN
    * `0x7e00`).
    */
  def fromFloat(f: Float): Int =
    val bits = java.lang.Float.floatToRawIntBits(f)
    val sign = (bits >>> 16) & 0x8000
    val exponent = (bits >>> 23) & 0xff
    val fraction = bits & 0x7fffff
    if exponent == 0xff then sign | (if fraction == 0 then 0x7c00 else 0x7e00)
    else
      val unbiased = exponent - 127
      if unbiased > 15 then sign | 0x7c00 // too large for a half
      else if unbiased >= -14 then
        // a normal half: keep ten fraction bits, round the other thirteen to nearest even
        val half = ((unbiased + 15) << 10) | (fraction >> 13)
        sign | (half + rounding(fraction, 13))
      else if unbiased >= -25 then
        // a subnormal half: the implicit one joins the fraction, shifted by the missing exponent
        val full = fraction | 0x800000
        val shift = 13 + (-14 - unbiased)
        sign | ((full >> shift) + rounding(full, shift))
      else sign // underflows to zero

  /** 1 when the bits below `shift` round the kept part up (nearest, ties to the even kept part). */
  private def rounding(value: Int, shift: Int): Int =
    val dropped = value & ((1 << shift) - 1)
    val half = 1 << (shift - 1)
    val odd = (value >> shift) & 1
    if dropped > half || (dropped == half && odd == 1) then 1 else 0
