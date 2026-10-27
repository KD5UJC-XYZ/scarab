// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import scala.annotation.tailrec

/** scientific 0.3.8.1 `Scientific` (`Data/Scientific.hs:139-167`):
  * `coefficient * 10^base10Exponent` with a machine-Int exponent, stored as parsed and never
  * normalized. Equality and hashing normalize first, as the reference's `Eq` and `Hashable` do
  * (`Data/Scientific.hs:194-213`), so `Scientific(1, 1) == Scientific(10, 0)`; read the fields to
  * observe the representation.
  */
final case class Scientific(coefficient: BigInt, base10Exponent: Long):
  /** `normalize` (`Data/Scientific.hs:1101-1111`): trailing decimal zeros of the coefficient move
    * into the exponent, which wraps as the reference's 64-bit `Int` does; zero is `Scientific 0 0`.
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
