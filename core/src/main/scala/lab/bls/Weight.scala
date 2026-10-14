// SPDX-License-Identifier: Apache-2.0
package lab.bls

/** A non-negative stake weight (lovelace). Exact integer arithmetic: the quorum test is an integer
  * cross-multiplication, no rationals or floating point.
  */
opaque type Weight = BigInt

object Weight:
  val zero: Weight = BigInt(0)
  def fromNatural(n: BigInt): Option[Weight] = if n.signum >= 0 then Some(n) else None
  given Ordering[Weight] = Ordering.BigInt

  /** The CIP's quorum `tau` of `total` (the *total active stake*, not the stake seated on the
    * committee), kept exact: the smallest integer weight W with `W * denominator >= total *
    * numerator`. `None` when the denominator is not positive or the numerator is negative.
    */
  def fractionCeil(total: Weight, numerator: BigInt, denominator: BigInt): Option[Weight] =
    if denominator.signum <= 0 || numerator.signum < 0 then None
    else fromNatural((total * numerator + denominator - 1) / denominator)
  extension (w: Weight)
    def +(o: Weight): Weight = w + o
    def value: BigInt = w
