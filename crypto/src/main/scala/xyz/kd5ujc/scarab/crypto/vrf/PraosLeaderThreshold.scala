// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import scala.annotation.tailrec

/** Source-derived E34 Fixed arithmetic and bounded reference Taylor comparison. No Double/Float, no
  * claim of an independently checked runtime oracle. Active coefficient >1/2 except 1 is outside
  * this experimental profile (the source's error bound assumes the normal range).
  */
object PraosLeaderThreshold:
  enum Error:
    case MissingNumerator, MissingDenominator
    case InvalidFraction(numerator: BigInt, denominator: BigInt)
    case MissingVrfOutput
    case InvalidVrfOutputLength(actual: Int)
    case MissingLeaderValue, MissingRange
    case InvalidRange(actual: BigInt)
    case LeaderValueOutOfRange(value: BigInt, range: BigInt)
    case MissingStake, MissingActiveSlotsCoefficient
    case UnsupportedActiveSlotsCoefficient(numerator: BigInt, denominator: BigInt)

    def message: String = this match
      case MissingNumerator | MissingDenominator | InvalidFraction(_, _) =>
        "fraction must satisfy 0 <= numerator <= denominator <= Word64Max"
      case MissingVrfOutput | InvalidVrfOutputLength(_) =>
        "verified VRF output must contain 64 bytes"
      case MissingLeaderValue | MissingRange | InvalidRange(_) | LeaderValueOutOfRange(_, _) |
          MissingStake | MissingActiveSlotsCoefficient =>
        "malformed leader comparison"
      case UnsupportedActiveSlotsCoefficient(_, _) =>
        "supported active coefficient is (0,1/2] or the reference testing case 1"

  val Scale: BigInt = BigInt(10).pow(34)
  val Range: BigInt = BigInt(1) << 256
  private val Epsilon = BigInt(10).pow(10) // 1e-24 at E34 resolution
  private val MaxWord = (BigInt(1) << 64) - 1
  final class Fraction private (val numerator: BigInt, val denominator: BigInt)
  object Fraction:
    def checked(n: BigInt, d: BigInt): Either[Error, Fraction] =
      if n == null then Left(Error.MissingNumerator)
      else if d == null then Left(Error.MissingDenominator)
      else if n < 0 || d <= 0 || n > d || d > MaxWord then Left(Error.InvalidFraction(n, d))
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
    @tailrec def go(n: Int, last: BigInt, acc: BigInt, divisor: BigInt): BigInt =
      if n >= 1000 then acc
      else
        val next = div(last, divisor)
        if next.abs < Epsilon then acc else go(n + 1, next, acc + next, divisor + Scale)
    go(1, Scale, Scale, Scale)

  // For 0 < f <= 1/2, ln'(1-f)'s splitLn exponent is exactly -1.
  // This bounded specialization follows the source continued fraction without real arithmetic.
  // ln(1 - f) depends only on the active slot coefficient, a constant of the network: computed once
  // per coefficient instead of once per block (the continued fraction is a thousand big divisions).
  private val logs = new java.util.concurrent.ConcurrentHashMap[(BigInt, BigInt), BigInt]()

  private def activeLog(f: Fraction): BigInt =
    logs.computeIfAbsent((f.numerator, f.denominator), _ => computeActiveLog(f))

  private def computeActiveLog(f: Fraction): BigInt =
    val x = div(Scale - fixed(f), div(Scale, expOne)) - Scale
    // the convergents A_i / B_i from the two before them; it stops at the thousandth or when two in
    // a row are within Epsilon
    @tailrec def go(
        i: Int,
        a2: BigInt,
        b2: BigInt,
        a1: BigInt,
        b1: BigInt,
        last: Option[BigInt]
    ): BigInt =
      val k = if i == 0 then 1 else (i + 1) / 2
      val an = x * k * k
      val bn = Scale * (i + 1)
      val a = mul(bn, a1) + mul(an, a2)
      val b = mul(bn, b1) + mul(an, b2)
      val result = div(a, b)
      if i == 1000 || last.exists(v => (v - result).abs < Epsilon) then result
      else go(i + 1, a1, b1, a, b, Some(result))
    go(0, Scale, BigInt(0), BigInt(0), Scale, None) - Scale

  def leaderValue(verifiedOutput: Bytes): Either[Error, BigInt] =
    if verifiedOutput == null then Left(Error.MissingVrfOutput)
    else if verifiedOutput.size != 64 then Left(Error.InvalidVrfOutputLength(verifiedOutput.size))
    else
      Right(
        BigInt(1, Blake2b.hash256.hash(Bytes(Vector(0x4c.toByte) ++ verifiedOutput.value)).toArray)
      )

  /** Leader value as a natural over 2^512: the raw 64-byte certified output (TPraos, Shelley). */
  def rawLeaderValue(verifiedOutput: Bytes): Either[Error, BigInt] =
    if verifiedOutput == null then Left(Error.MissingVrfOutput)
    else if verifiedOutput.size != 64 then Left(Error.InvalidVrfOutputLength(verifiedOutput.size))
    else Right(BigInt(1, verifiedOutput.toArray))

  /** Range of a 64-byte output, for [[rawLeaderValue]]. */
  val Range512: BigInt = BigInt(1) << 512

  /** Praos check: `value` is the 32-byte "L"-hash natural, over 2^256. */
  def check(value: BigInt, stake: Fraction, active: Fraction): Either[Error, Decision] =
    check(value, stake, active, Range)

  /** Leader iff `value / range < 1 - (1 - active)^stake`, decided by the reference Taylor series.
    * `range` is the size of the value's domain (2^256 for Praos, 2^512 for TPraos).
    */
  def check(
      value: BigInt,
      stake: Fraction,
      active: Fraction,
      range: BigInt
  ): Either[Error, Decision] =
    if value == null then Left(Error.MissingLeaderValue)
    else if range == null then Left(Error.MissingRange)
    else if range <= 0 then Left(Error.InvalidRange(range))
    else if value < 0 || value >= range then Left(Error.LeaderValueOutOfRange(value, range))
    else if stake == null then Left(Error.MissingStake)
    else if active == null then Left(Error.MissingActiveSlotsCoefficient)
    else if active.numerator == 0 ||
      (active.numerator != active.denominator && active.numerator * 2 > active.denominator)
    then Left(Error.UnsupportedActiveSlotsCoefficient(active.numerator, active.denominator))
    else if active.numerator == active.denominator then Right(Decision.Eligible)
    else
      val cmp = range * Scale / (range - value)
      // Haskell2010 prefix negation has precedence 6; multiplication (7) happens first.
      val x = -mul(fixed(stake), activeLog(active))
      @tailrec def go(n: Int, err: BigInt, acc: BigInt, divisor: BigInt): Decision =
        if n >= 1000 then Decision.IterationLimit
        else
          val nextDivisor = divisor + Scale
          val nextErr = div(mul(err, x), nextDivisor)
          val nextAcc = acc + err
          val error = mul(nextErr, 3 * Scale).abs
          if cmp >= nextAcc + error then Decision.Ineligible
          else if cmp < nextAcc - error then Decision.Eligible
          else go(n + 1, nextErr, nextAcc, nextDivisor)
      Right(go(0, x, Scale, Scale))
