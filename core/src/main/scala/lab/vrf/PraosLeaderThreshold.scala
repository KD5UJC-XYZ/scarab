// SPDX-License-Identifier: Apache-2.0
package lab.vrf

import lab.Blake2b
import lab.cbor.Bytes

/** Source-derived E34 Fixed arithmetic and bounded reference Taylor comparison. No Double/Float, no
  * claim of an independently checked runtime oracle. Active coefficient >1/2 except 1 is outside
  * this experimental profile (the source's error bound assumes the normal range).
  */
object PraosLeaderThreshold:
  val Scale: BigInt = BigInt(10).pow(34)
  val Range: BigInt = BigInt(1) << 256
  private val Epsilon = BigInt(10).pow(10) // 1e-24 at E34 resolution
  private val MaxWord = (BigInt(1) << 64) - 1
  final class Fraction private (val numerator: BigInt, val denominator: BigInt)
  object Fraction:
    def checked(n: BigInt, d: BigInt): Either[String, Fraction] =
      if n == null || d == null || n < 0 || d <= 0 || n > d || d > MaxWord then
        Left("fraction must satisfy 0 <= numerator <= denominator <= Word64Max")
      else
        val gcd = n.gcd(d)
        Right(new Fraction(n / gcd, d / gcd))
  enum Decision:
    case Eligible, Ineligible, IterationLimit

  private def floorDiv(n: BigInt, d: BigInt): BigInt =
    val (q, r) = n /% d
    if r != 0 && n.signum != d.signum then q - 1 else q
  private def mul(a: BigInt, b: BigInt): BigInt = floorDiv(a * b, Scale)
  private def div(a: BigInt, b: BigInt): BigInt = floorDiv(a * Scale, b)
  private def fixed(f: Fraction): BigInt = f.numerator * Scale / f.denominator

  // exp' 1: source taylorExp 1000 1 1 1 1 1; term and iteration stop precede addition.
  private lazy val expOne: BigInt =
    var n = 1
    var last = Scale
    var acc = Scale
    var divisor = Scale
    var stop = false
    while n < 1000 && !stop do
      val next = div(last, divisor)
      if next.abs < Epsilon then stop = true
      else
        acc += next
        last = next
        divisor += Scale
        n += 1
    acc

  // For 0 < f <= 1/2, ln'(1-f)'s splitLn exponent is exactly -1.
  // This bounded specialization follows the source continued fraction without real arithmetic.
  private def activeLog(f: Fraction): BigInt =
    val x = div(Scale - fixed(f), div(Scale, expOne)) - Scale
    var a2 = Scale; var b2 = BigInt(0); var a1 = BigInt(0); var b1 = Scale
    var last: Option[BigInt] = None
    var i = 0
    var result = BigInt(0)
    var stop = false
    while i <= 1000 && !stop do
      val k = if i == 0 then 1 else (i + 1) / 2
      val an = x * k * k
      val bn = Scale * (i + 1)
      val a = mul(bn, a1) + mul(an, a2)
      val b = mul(bn, b1) + mul(an, b2)
      result = div(a, b)
      stop = i == 1000 || last.exists(v => (v - result).abs < Epsilon)
      last = Some(result)
      a2 = a1; b2 = b1; a1 = a; b1 = b
      i += 1
    result - Scale

  def leaderValue(verifiedOutput: Bytes): Either[String, BigInt] =
    if verifiedOutput == null || verifiedOutput.value == null || verifiedOutput.size != 64 then
      Left("verified VRF output must contain 64 bytes")
    else
      Right(
        BigInt(1, Blake2b.hash256.hash(Bytes(Vector(0x4c.toByte) ++ verifiedOutput.value)).toArray)
      )

  def check(value: BigInt, stake: Fraction, active: Fraction): Either[String, Decision] =
    if value == null || value < 0 || value >= Range || stake == null || active == null then
      Left("malformed leader comparison")
    else if active.numerator == 0 ||
      (active.numerator != active.denominator && active.numerator * 2 > active.denominator)
    then Left("supported active coefficient is (0,1/2] or the reference testing case 1")
    else if active.numerator == active.denominator then Right(Decision.Eligible)
    else
      val cmp = Range * Scale / (Range - value)
      // Haskell2010 prefix negation has precedence 6; multiplication (7) happens first.
      val x = -mul(fixed(stake), activeLog(active))
      var err = x; var acc = Scale; var divisor = Scale
      var n = 0
      var result: Option[Decision] = None
      while n < 1000 && result.isEmpty do
        val nextDivisor = divisor + Scale
        val nextErr = div(mul(err, x), nextDivisor)
        val nextAcc = acc + err
        val error = mul(nextErr, 3 * Scale).abs
        if cmp >= nextAcc + error then result = Some(Decision.Ineligible)
        else if cmp < nextAcc - error then result = Some(Decision.Eligible)
        err = nextErr; acc = nextAcc; divisor = nextDivisor; n += 1
      Right(result.getOrElse(Decision.IterationLimit))
