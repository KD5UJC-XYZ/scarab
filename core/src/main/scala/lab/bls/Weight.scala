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
  extension (w: Weight)
    def +(o: Weight): Weight = w + o
    def value: BigInt = w
