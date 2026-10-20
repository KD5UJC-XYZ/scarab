// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import cats.kernel.{Eq, Order}

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

  def apply(n: BigInt, d: BigInt = 1): Rational =
    require(d != 0, "zero denominator")
    val g = n.gcd(d) * d.signum
    new Rational(n / g, d / g)

  /** Exact conversion of a decimal JSON number such as `0.003` or `5e-2`. */
  def fromDecimal(d: BigDecimal): Rational =
    val unscaled = BigInt(d.bigDecimal.unscaledValue)
    val scale = d.bigDecimal.scale
    if scale >= 0 then Rational(unscaled, BigInt(10).pow(scale))
    else Rational(unscaled * BigInt(10).pow(-scale))

  given Order[Rational] = Order.from((a, b) => if a < b then -1 else if b < a then 1 else 0)
  given Eq[Rational] = Eq.fromUniversalEquals
