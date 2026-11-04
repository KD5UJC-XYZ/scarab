// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.vrf

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import scala.annotation.tailrec

/** The reference's leader check in its own E34 fixed-point arithmetic (`FixedPoint`, cardano-ledger
  * f649f975 `BaseTypes.hs:265-275`): `ln'` with `splitLn` for the logarithm of `1 - f`, and the
  * bounded `taylorExpCmp` comparison of `checkLeaderNatValue` (`BlockHeader.hs:373-402`), for any
  * active-slot coefficient in (0, 1]. Every multiplication and division floors at 10^-34 as
  * `Data.Fixed` does; no Double/Float.
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
    case NonPositiveActiveSlotsCoefficient(numerator: BigInt, denominator: BigInt)
    case ActiveSlotLogOutsideDomain(numerator: BigInt, denominator: BigInt)

    def message: String = this match
      case MissingNumerator | MissingDenominator | InvalidFraction(_, _) =>
        "fraction must satisfy 0 <= numerator <= denominator <= Word64Max"
      case MissingVrfOutput | InvalidVrfOutputLength(_) =>
        "verified VRF output must contain 64 bytes"
      case MissingLeaderValue | MissingRange | InvalidRange(_) | LeaderValueOutOfRange(_, _) |
          MissingStake | MissingActiveSlotsCoefficient =>
        "malformed leader comparison"
      case NonPositiveActiveSlotsCoefficient(_, _) =>
        "active slot coefficient must be in (0,1]"
      case ActiveSlotLogOutsideDomain(_, _) =>
        "ln(1 - active slot coefficient) is outside the reference continued fraction's domain"

  val Scale: BigInt = BigInt(10).pow(34)
  val Range: BigInt = BigInt(1) << 256
  private val Epsilon = BigInt(10).pow(10) // 1e-24 at E34 resolution
  private val MaxWord = (BigInt(1) << 64) - 1
  final class Fraction private (val numerator: BigInt, val denominator: BigInt)
  object Fraction:
    /** A ledger forecast's stored pool share is already a Rational, with no machine-word bound
      * (`IndividualPoolStake`, ledger 9df9f98c `State/PoolDistr.hs:54-67`). It is consumed directly
      * by `checkLeaderNatValue`; its coin amount may separately include proposal deposits.
      */
    private[scarab] def storedStake(value: Rational): Fraction =
      new Fraction(value.numerator, value.denominator)

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

  private enum Power:
    case Square, Multiply

  // ipow' (NonIntegral.hs:31-37) for n >= 0: its squarings and multiplications, from the innermost
  // out, applied to 1, so each rounds as the recursion does.
  private def ipowNatural(x: BigInt, n: BigInt): BigInt =
    @tailrec def steps(m: BigInt, acc: List[Power]): List[Power] =
      if m == 0 then acc
      else if !m.testBit(0) then steps(m >> 1, Power.Square :: acc)
      else steps(m - 1, Power.Multiply :: acc)
    steps(n, Nil).foldLeft(Scale) {
      case (y, Power.Square)   => mul(y, y)
      case (y, Power.Multiply) => mul(x, y)
    }

  // ipow (NonIntegral.hs:39-42)
  private def ipow(x: BigInt, n: BigInt): BigInt =
    if n < 0 then div(Scale, ipowNatural(x, -n)) else ipowNatural(x, n)

  // findE (NonIntegral.hs:155-158): `bound` squares 1/e and e until they enclose x (117-128), then
  // `contract` bisects with `ipow` for e^n <= x < e^(n+1) (132-149).
  private def findE(e: BigInt, x: BigInt): BigInt =
    @tailrec def bound(low: BigInt, high: BigInt, l: BigInt, u: BigInt): (BigInt, BigInt) =
      if low <= x && x <= high then (l, u) else bound(mul(low, low), mul(high, high), 2 * l, 2 * u)
    @tailrec def contract(l: BigInt, u: BigInt): BigInt =
      if l + 1 == u then l
      else
        val mid = l + floorDiv(u - l, 2)
        if x < ipow(e, mid) then contract(l, mid) else contract(mid, u)
    val (lower, upper) = bound(div(Scale, e), e, -1, 1)
    contract(lower, upper)

  // lncf 1000 (NonIntegral.hs:52-57) through cf (89-111): the convergents A_i / B_i from the two
  // before them; it stops at the thousandth or when two in a row are within eps.
  private def lncf(x: BigInt): BigInt =
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
    go(0, Scale, BigInt(0), BigInt(0), Scale, None)

  // ln(1 - f) depends only on the active slot coefficient, a constant of the network: computed once
  // per coefficient instead of once per block (the continued fraction is a thousand big divisions).
  private val logs =
    new java.util.concurrent.ConcurrentHashMap[(BigInt, BigInt), Either[Error, BigInt]]()

  /** `unActiveSlotLog` of `mkActiveSlotCoeff` (`BaseTypes.hs:735-749`) in E34 units: 0 for f = 1,
    * else `floor (fpPrecision * ln' (1 - fromRational f))`. Multiplying by `fpPrecision` and taking
    * the floor gives the E34 units of `ln'` exactly, and `activeSlotLog` divides them back
    * (`BaseTypes.hs:754-755`).
    */
  def activeSlotLog(f: Fraction): Either[Error, BigInt] =
    if f.numerator == 0 then
      Left(Error.NonPositiveActiveSlotsCoefficient(f.numerator, f.denominator))
    else if f.numerator == f.denominator then Right(BigInt(0))
    else logs.computeIfAbsent((f.numerator, f.denominator), _ => computeActiveLog(f))

  // ln' and splitLn (NonIntegral.hs:162-174) of 1 - f, which is positive for f < 1: `lncf` refuses
  // a negative remainder with `error`, which this returns as a typed cause.
  private def computeActiveLog(f: Fraction): Either[Error, BigInt] =
    val x = Scale - fixed(f)
    val n = findE(expOne, x)
    val remainder = div(x, ipow(expOne, n)) - Scale
    Either.cond(
      remainder >= 0,
      n * Scale + lncf(remainder),
      Error.ActiveSlotLogOutsideDomain(f.numerator, f.denominator)
    )

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
    else if active.numerator == active.denominator then Right(Decision.Eligible)
    else activeSlotLog(active).map(log => compare(value, stake, log, range))

  // taylorExpCmp 3 recip_q x (NonIntegral.hs:196-209): MaxReached is IterationLimit, which callers
  // treat as not leader, as checkLeaderNatValue does.
  private def compare(value: BigInt, stake: Fraction, log: BigInt, range: BigInt): Decision =
    val cmp = range * Scale / (range - value)
    // Haskell2010 prefix negation has precedence 6; multiplication (7) happens first.
    val x = -mul(fixed(stake), log)
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
    go(0, x, Scale, Scale)
