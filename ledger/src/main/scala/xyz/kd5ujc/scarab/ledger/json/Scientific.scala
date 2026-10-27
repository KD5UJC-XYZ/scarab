// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import cats.{Order, Show}
import cats.data.NonEmptyList
import scala.annotation.tailrec

/** scientific 0.3.8.1 `Scientific` (`Data/Scientific.hs:139-167`):
  * `coefficient * 10^base10Exponent` with a machine-Int exponent, stored as parsed and never
  * normalized. Equality, ordering and hashing normalize first, as the reference's `Eq`, `Ord` and
  * `Hashable` do (`Data/Scientific.hs:194-241`), so `Scientific(1, 1) == Scientific(10, 0)`; read
  * the fields to observe the representation. `toString` is the reference's `show`.
  */
final case class Scientific(coefficient: BigInt, base10Exponent: Long):
  /** `normalize` (`Data/Scientific.hs:1101-1111`): trailing decimal zeros of the coefficient move
    * into the exponent, which wraps as the reference's 64-bit `Int` does; zero is `Scientific 0 0`.
    * Its logarithmic number of divisions, instead of the reference's one per zero, is an
    * owner-approved implementation difference with the same result, recorded in #474 (2026-10-27).
    */
  def normalize: Scientific =
    if coefficient == 0 then Scientific(0, 0)
    else
      val (stripped, zeros) = Scientific.stripZeros(coefficient)
      Scientific(stripped, base10Exponent + zeros)

  override def equals(other: Any): Boolean = other match
    case that: Scientific =>
      val (a, b) = (normalize, that.normalize)
      a.coefficient == b.coefficient && a.base10Exponent == b.base10Exponent
    case _ => false

  override def hashCode: Int =
    val n = normalize
    (n.coefficient, n.base10Exponent).hashCode

  /** `showsPrec d` (`Data/Scientific.hs:958-973`): a negative number is parenthesized above the
    * prefix-minus precedence 6.
    */
  def showsPrec(d: Int): String =
    if coefficient < 0 then
      val shown = "-" + Scientific.showPositive(Scientific(-coefficient, base10Exponent))
      if d > 6 then s"($shown)" else shown
    else Scientific.showPositive(this)

  override def toString: String = showsPrec(0)

object Scientific:
  // The reference divides once per zero. Dividing by 10^1, 10^2, 10^4, … and then by the same
  // powers in descending order removes the same zeros in a logarithmic number of divisions.
  private def stripZeros(c: BigInt): (BigInt, Long) =
    @tailrec def up(
        c: BigInt,
        power: BigInt,
        width: Long,
        tried: List[(BigInt, Long)],
        zeros: Long
    ): (BigInt, List[(BigInt, Long)], Long) =
      val (q, r) = c /% power
      if r == 0 then up(q, power * power, width * 2, (power, width) :: tried, zeros + width)
      else (c, tried, zeros)
    @tailrec def down(c: BigInt, powers: List[(BigInt, Long)], zeros: Long): (BigInt, Long) =
      powers match
        case Nil => (c, zeros)
        case (power, width) :: rest =>
          val (q, r) = c /% power
          if r == 0 then down(q, rest, zeros + width) else down(c, rest, zeros)
    val (partial, powers, zeros) = up(c, BigInt(10), 1, Nil, 0)
    down(partial, powers, zeros)

  /** integer-logarithms 1.0.5 `integerLog10'` (`Math/NumberTheory/Logarithms.hs:164-175`) of a
    * positive integer, with the reference's estimate `ex` of the logarithm from the bit length.
    */
  private[json] def integerLog10(n: BigInt): Long =
    @tailrec def loop(n: BigInt, acc: Long): Long =
      if n < 10 then acc
      else if n < 100 then acc + 1
      else
        val ln = n.bitLength - 1L
        val ex = 1936274L * ln / 6432163L
        loop(n / BigInt(10).pow(ex.toInt), acc + ex)
    loop(n, 0)

  /** `instance Ord Scientific` (`Data/Scientific.hs:218-241`): compares digit counts and only
    * divides by `10^d` for the difference `d` of the coefficients' digit counts, never by a power
    * from the exponents. Exponent sums wrap as the reference's `Int` does.
    */
  private def compare(s1: Scientific, s2: Scientific): Int =
    val Scientific(c1, e1) = s1.normalize
    val Scientific(c2, e2) = s2.normalize
    def cmp(cx: BigInt, ex: Long, cy: BigInt, ey: Long): Int =
      val log10cx = integerLog10(cx)
      val log10cy = integerLog10(cy)
      val log10sx = log10cx + ex
      val log10sy = log10cy + ey
      val d = log10cx - log10cy
      if log10sx < log10sy then -1
      else if log10sx > log10sy then 1
      else if d < 0 then if cx <= cy / BigInt(10).pow((-d).toInt) then -1 else 1
      else if d > 0 then if cy > cx / BigInt(10).pow(d.toInt) then -1 else 1
      else if cx < cy then -1
      else 1
    if c1 == c2 && e1 == e2 then 0
    else if c1 < 0 then if c2 < 0 then cmp(-c2, e2, -c1, e1) else -1
    else if c1 > 0 then if c2 > 0 then cmp(c1, e1, c2, e2) else 1
    else if c2 > 0 then -1
    else 1

  given Order[Scientific] = Order.from(compare)
  given Ordering[Scientific] = Order[Scientific].toOrdering
  given Show[Scientific] = Show.fromToString

  /** `toDecimalDigits` (`Data/Scientific.hs:1078-1089`) of a non-negative number: the digits of the
    * normalized coefficient and the exponent of `0.d1d2…dn`, summed in the reference's `Int`.
    */
  private def toDecimalDigits(s: Scientific): (NonEmptyList[Int], Long) =
    if s.coefficient == 0 then (NonEmptyList.one(0), 0)
    else
      val Scientific(c, e) = s.normalize
      @tailrec def go(i: BigInt, n: Long, ds: NonEmptyList[Int]): (NonEmptyList[Int], Long) =
        if i == 0 then (ds, n + e)
        else
          val (q, r) = i /% 10
          go(q, n + 1, r.toInt :: ds)
      val (q, r) = c /% 10
      go(q, 1, NonEmptyList.one(r.toInt))

  // `showPositive` and `fmtAsGeneric` (`Data/Scientific.hs:966-973`).
  private def showPositive(s: Scientific): String =
    val (is, e) = toDecimalDigits(s)
    if e < 0 || e > 7 then fmtAsExponent(is, e) else fmtAsFixed(is, e.toInt)

  /** `fmtAsExponent` (`Data/Scientific.hs:975-985`). */
  private def fmtAsExponent(is: NonEmptyList[Int], e: Long): String =
    val showE = (e - 1).toString
    is.map(Character.forDigit(_, 10)) match
      case NonEmptyList('0', Nil) => "0.0e0"
      case NonEmptyList(d, Nil)   => s"$d.0e$showE"
      case NonEmptyList(d, ds)    => s"$d.${ds.mkString}e$showE"

  /** `fmtAsFixed` (`Data/Scientific.hs:987-1001`), for the exponents 0 to 7 that `fmtAsGeneric`
    * passes: the first `e` digits, padded with zeros, before the point, and `0` for an empty side.
    */
  private def fmtAsFixed(is: NonEmptyList[Int], e: Int): String =
    val ds = is.toList.map(Character.forDigit(_, 10)).mkString
    def mk0(ls: String) = if ls.isEmpty then "0" else ls
    if e <= 0 then "0." + "0" * -e + ds
    else
      val (whole, fraction) = ds.splitAt(e)
      mk0(whole + "0" * (e - whole.length)) + "." + mk0(fraction)
