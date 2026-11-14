// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import scala.annotation.tailrec

/** GHC 9.6.7 base-4.18.3.0, GHC/Float.hs:839-900,953-1042,1516-1524. The integer
  * Burger/Dybvig digit generator preserves the native shortest decimal and rounding decisions;
  * JVM Float/Double.toString implement a different selection rule and are not used.
  */
private[scarab] object HaskellFloat:
  private final case class Binary(mantissa: BigInt, exponent: Int, digits: Int, minimum: Int)

  def float(value: Float, precedence: Int = 0): String =
    val bits = java.lang.Float.floatToRawIntBits(value)
    render(BigInt(bits & 0x7fffffff), (bits & Int.MinValue) != 0, 23, 8, 127, precedence)

  def double(value: Double, precedence: Int = 0): String =
    val bits = java.lang.Double.doubleToRawLongBits(value)
    render(BigInt(bits & Long.MaxValue), (bits & Long.MinValue) != 0, 52, 11, 1023, precedence)

  private def render(
      magnitude: BigInt,
      negative: Boolean,
      fractionBits: Int,
      exponentBits: Int,
      bias: Int,
      precedence: Int
  ): String =
    val fraction = magnitude & ((BigInt(1) << fractionBits) - 1)
    val exponent = (magnitude >> fractionBits).toInt
    val special = exponent == (1 << exponentBits) - 1
    val positive =
      if special then if fraction == 0 then "Infinity" else "NaN"
      else
        val binary =
          if exponent == 0 && fraction != 0 then
            val shift = fractionBits + 1 - fraction.bitLength
            Binary(fraction << shift, 1 - bias - fractionBits - shift, fractionBits + 1, 1 - bias - fractionBits)
          else
            Binary(
              if exponent == 0 then BigInt(0) else (BigInt(1) << fractionBits) + fraction,
              exponent - bias - fractionBits,
              fractionBits + 1,
              1 - bias - fractionBits
            )
        val (digits, power) = decimal(binary)
        val raw = digits.mkString
        if power < 0 || power > 7 then
          raw.take(1) + "." + (if raw.length == 1 then "0" else raw.drop(1)) + "e" + (power - 1)
        else if power <= 0 then "0." + "0" * (-power) + raw
        else
          val integral = raw.take(power) + "0" * math.max(0, power - raw.length)
          val fractional = raw.drop(power)
          integral + "." + (if fractional.isEmpty then "0" else fractional)
    if negative && !(special && fraction != 0) then
      val signed = "-" + positive
      if precedence > 6 then "(" + signed + ")" else signed
    else positive

  private def decimal(binary: Binary): (List[Int], Int) =
    if binary.mantissa == 0 then (List(0), 0)
    else
      val adjustment = math.max(0, binary.minimum - binary.exponent)
      val f = binary.mantissa >> adjustment
      val e = binary.exponent + adjustment
      val boundary = f == (BigInt(1) << (binary.digits - 1))
      val (r, s, upper, lower) =
        if e >= 0 then
          val power = BigInt(1) << e
          if boundary then (f * power * 4, BigInt(4), power * 2, power)
          else (f * power * 2, BigInt(2), power, power)
        else if e > binary.minimum && boundary then
          (f * 4, (BigInt(1) << (-e + 1)) * 2, BigInt(2), BigInt(1))
        else (f * 2, (BigInt(1) << -e) * 2, BigInt(1), BigInt(1))
      val lx = binary.digits - 1 + binary.exponent
      val estimate = lx * 8651 / 28738 + (if lx >= 0 then 1 else 0)
      @tailrec def fixup(n: Int): Int =
        val fits =
          if n >= 0 then r + upper <= BigInt(10).pow(n) * s
          else BigInt(10).pow(-n) * (r + upper) <= s
        if fits then n else fixup(n + 1)
      val k = fixup(estimate)
      @tailrec def generate(
          reversed: List[Int],
          numerator: BigInt,
          denominator: BigInt,
          high: BigInt,
          low: BigInt
      ): List[Int] =
        val (digit, rest) = (numerator * 10) /% denominator
        val nextHigh = high * 10
        val nextLow = low * 10
        (rest < nextLow, rest + nextHigh > denominator) match
          case (true, false) => (digit.toInt :: reversed).reverse
          case (false, true) => ((digit + 1).toInt :: reversed).reverse
          case (true, true) =>
            ((if rest * 2 < denominator then digit else digit + 1).toInt :: reversed).reverse
          case (false, false) => generate(digit.toInt :: reversed, rest, denominator, nextHigh, nextLow)
      val result =
        if k >= 0 then generate(Nil, r, s * BigInt(10).pow(k), upper, lower)
        else
          val power = BigInt(10).pow(-k)
          generate(Nil, r * power, s, upper * power, lower * power)
      (result, k)
