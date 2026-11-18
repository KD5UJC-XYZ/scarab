// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import cats.kernel.{Eq, Order}
import scala.annotation.tailrec

/** Exact rational, reduced on construction with a positive denominator. Arithmetic preserves exact
  * fractions; callers choose [[floor]] or [[ceil]] where the ledger specification requires
  * rounding.
  */
final class Rational private (val numerator: BigInt, val denominator: BigInt):
  def +(o: Rational): Rational =
    Rational(numerator * o.denominator + o.numerator * denominator, denominator * o.denominator)
  def -(o: Rational): Rational =
    Rational(numerator * o.denominator - o.numerator * denominator, denominator * o.denominator)
  def *(o: Rational): Rational = Rational(numerator * o.numerator, denominator * o.denominator)
  def /(o: Rational): Rational = Rational(numerator * o.denominator, denominator * o.numerator)
  def min(o: Rational): Rational = if this <= o then this else o
  def max(o: Rational): Rational = if this >= o then this else o
  def <=(o: Rational): Boolean = numerator * o.denominator <= o.numerator * denominator
  def <(o: Rational): Boolean = numerator * o.denominator < o.numerator * denominator
  def >=(o: Rational): Boolean = o <= this
  def >(o: Rational): Boolean = o < this
  def isZero: Boolean = numerator == 0

  /** Round towards positive infinity, as Haskell `ceiling`. */
  def ceil: BigInt = -Rational(-numerator, denominator).floor

  /** Round towards negative infinity, as Haskell `floor`. */
  def floor: BigInt =
    val (q, r) = numerator /% denominator
    if r < 0 then q - 1 else q

  override def equals(other: Any): Boolean = other match
    case r: Rational => numerator == r.numerator && denominator == r.denominator
    case _           => false
  override def hashCode: Int = (numerator, denominator).hashCode
  override def toString: String = s"$numerator/$denominator"

object Rational:
  val zero: Rational = Rational(0)
  val one: Rational = Rational(1)

  // A kept site of ERR-4 (#1035): a zero denominator is a defect of the caller, as a division of a `BigInt` by
  // zero is, and `apply` has no error channel; a total `apply` would change every caller.
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  def apply(n: BigInt, d: BigInt = 1): Rational =
    require(d != 0, "zero denominator")
    val g = n.gcd(d) * d.signum
    new Rational(n / g, d / g)

  /** `Rational(n, d)` when its numerator's magnitude and its denominator are both below `2^bits`,
    * and `None` when either is not, in time linear in the length of `n` and `d`.
    *
    * [[apply]] reduces with `BigInteger.gcd`, whose time grows with the square of the operands'
    * length. This runs Euclid's algorithm on `|n|` and `|d|` instead, given up once the reduced
    * value is shown not to fit ([[euclid]]). When it finishes, the reduced value is read off the
    * greatest common divisor by two divisions, run only when the bit lengths show quotients of at
    * most `bits + 1` bits, and is then checked against `2^bits`: a value just past the bound, such
    * as `2^bits / 1`, is reduced cheaply and then refused.
    */
  // A kept site of ERR-4, as `apply` is: a zero denominator is a defect of the caller.
  @SuppressWarnings(Array("xyz.kd5ujc.scarab.warts.SystemTimeAndUnsafeRun"))
  def within(n: BigInt, d: BigInt, bits: Int): Option[Rational] =
    require(d != 0, "zero denominator")
    val (a, b) = (n.abs, d.abs)
    euclid(a, b, bits).gcd
      .filter(g => a.bitLength - g.bitLength <= bits && b.bitLength - g.bitLength <= bits)
      .map(g => new Rational(n / g * d.signum, b / g))
      .filter(r => r.numerator.abs.bitLength <= bits && r.denominator.bitLength <= bits)

  /** The greatest common divisor of `a` and `b` (`a` at least zero, `b` above zero) by Euclid's
    * algorithm, given up (`gcd` empty) once `a / gcd` or `b / gcd` is known to be `2^bits` or more,
    * with the number of divisions it made.
    *
    * On `g * x` and `g * y` the algorithm divides as on `x` and `y`: the same quotients, every
    * remainder times `g`. So when `x` and `y` are below `2^bits`, every quotient is below `2^bits`
    * too, and there are at most [[divisions]]`(bits)` divisions. A dividend whose bit length
    * exceeds the divisor's by more than `bits`, which makes the quotient `2^bits` or more, or one
    * division more, shows that the reduced value does not fit, and the run stops. The test on bit
    * lengths is sufficient, not exact: a quotient of `2^bits` with a difference of exactly `bits`
    * is divided. Each division therefore has a quotient of at most `bits + 1` bits, and costs time
    * linear in its operands' length.
    */
  private[codec] def euclid(a: BigInt, b: BigInt, bits: Int): Euclid =
    val most = divisions(bits)
    @tailrec
    def go(x: BigInt, y: BigInt, made: Int): Euclid =
      if y.signum == 0 then Euclid(Some(x), made)
      else if made == most || x.bitLength - y.bitLength > bits then Euclid(None, made)
      else go(y, x % y, made + 1)
    go(a, b, 0)

  /** What [[euclid]] found: the greatest common divisor, unless it gave up, and its divisions. */
  private[codec] final case class Euclid(gcd: Option[BigInt], divisions: Int)

  /** The most divisions Euclid's algorithm makes on two numbers below `2^bits`. On `u > v > 0` it
    * makes `k` only if `u` is at least the Fibonacci number `F(k + 2)` (Lamé's theorem; Knuth, The
    * Art of Computer Programming, vol. 2, 4.5.3), and a first number below the second costs one
    * division more, the one that swaps them: so one more than the largest `k` with
    * `F(k + 2) < 2^bits`, which is the index of the first pair `(F(j + 1), F(j + 2))` from
    * `(F(1), F(2))` whose second is `2^bits` or more. For 64 bits it is 92: `F(93) < 2^64 < F(94)`.
    */
  private[codec] def divisions(bits: Int): Int =
    val limit = BigInt(1) << bits
    Iterator.iterate((BigInt(1), BigInt(1)))((f, g) => (g, f + g)).indexWhere(_._2 >= limit)

  /** Exact conversion of a decimal JSON number such as `0.003` or `5e-2`. */
  def fromDecimal(d: BigDecimal): Rational =
    val unscaled = BigInt(d.bigDecimal.unscaledValue)
    val scale = d.bigDecimal.scale
    if scale >= 0 then Rational(unscaled, BigInt(10).pow(scale))
    else Rational(unscaled * BigInt(10).pow(-scale))

  given Order[Rational] = Order.from((a, b) => if a < b then -1 else if b < a then 1 else 0)
  given Eq[Rational] = Eq.fromUniversalEquals
