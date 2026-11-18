// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import java.math.BigInteger
import org.bouncycastle.crypto.bls.{BLS12_381G2Point, Fp2Element}
import scala.annotation.tailrec

/** The last step of hashing to G2 (RFC 9380 section 8.8.2, suite
  * `BLS12381G2_XMD:SHA-256_SSWU_RO_`): the sum of the two mapped points, and the cofactor cleared
  * by the endomorphism `psi` of Budroni and Pintore (RFC 9380 appendix G.3,
  * `clear_cofactor_bls12381_g2`), which is the same map as multiplying by the effective cofactor
  * `h_eff`. Bouncy Castle multiplies by `h_eff`, a 636-bit scalar, in affine coordinates; here the
  * points are in Jacobian coordinates over its `Fp2Element`, and the scalar is the curve's 64-bit
  * parameter `x`, twice.
  */
private[bls] object G2Cofactor:
  private val P: BigInteger = Fp2Element.P

  /** A point `(X / Z^2, Y / Z^3)` of `y^2 = x^3 + 4(1 + i)`; the point at infinity when `Z = 0`. */
  final case class Jacobian(x: Fp2Element, y: Fp2Element, z: Fp2Element):
    def isInfinity: Boolean = z.isZero
    def negate: Jacobian = Jacobian(x, y.neg(), z)

  val Infinity: Jacobian = Jacobian(Fp2Element.ONE, Fp2Element.ONE, Fp2Element.ZERO)

  def of(p: BLS12_381G2Point): Jacobian =
    if p.isInfinity then Infinity else Jacobian(p.x(), p.y(), Fp2Element.ONE)

  def affine(p: Jacobian): BLS12_381G2Point =
    if p.isInfinity then BLS12_381G2Point.INFINITY
    else
      val zInverse = p.z.inverse()
      val zInverse2 = zInverse.square()
      BLS12_381G2Point.of(p.x.mul(zInverse2), p.y.mul(zInverse2.mul(zInverse)))

  private def twice(a: Fp2Element): Fp2Element = a.add(a)

  /** `dbl-2009-l` of the Explicit-Formulas Database, for `a = 0`. */
  def double(p: Jacobian): Jacobian =
    if p.isInfinity || p.y.isZero then Infinity
    else
      val a = p.x.square()
      val b = p.y.square()
      val c = b.square()
      val d = twice(p.x.add(b).square().sub(a).sub(c))
      val e = twice(a).add(a)
      val f = e.square()
      val x3 = f.sub(twice(d))
      val c8 = twice(twice(twice(c)))
      Jacobian(x3, e.mul(d.sub(x3)).sub(c8), twice(p.y.mul(p.z)))

  /** `add-2007-bl` of the Explicit-Formulas Database, with its exceptional cases. */
  def add(p: Jacobian, q: Jacobian): Jacobian =
    if p.isInfinity then q
    else if q.isInfinity then p
    else
      val z1z1 = p.z.square()
      val z2z2 = q.z.square()
      val u1 = p.x.mul(z2z2)
      val u2 = q.x.mul(z1z1)
      val s1 = p.y.mul(q.z).mul(z2z2)
      val s2 = q.y.mul(p.z).mul(z1z1)
      val h = u2.sub(u1)
      val r = twice(s2.sub(s1))
      if h.isZero then if r.isZero then double(p) else Infinity
      else
        val i = twice(h).square()
        val j = h.mul(i)
        val v = u1.mul(i)
        val x3 = r.square().sub(j).sub(twice(v))
        val y3 = r.mul(v.sub(x3)).sub(twice(s1.mul(j)))
        val z3 = p.z.add(q.z).square().sub(z1z1).sub(z2z2).mul(h)
        Jacobian(x3, y3, z3)

  /** `|x|` for the curve's parameter `x = -0xd201000000010000`. */
  private val X: BigInteger = new BigInteger("d201000000010000", 16)

  /** `[x] p`: double and add over the bits of `|x|` from the top, then the negation. */
  def timesX(p: Jacobian): Jacobian =
    @tailrec def go(bit: Int, acc: Jacobian): Jacobian =
      if bit < 0 then acc
      else
        val doubled = double(acc)
        go(bit - 1, if X.testBit(bit) then add(doubled, p) else doubled)
    go(X.bitLength - 1, Infinity).negate

  private def conjugate(a: Fp2Element): Fp2Element = Fp2Element.of(a.c0(), a.c1().negate().mod(P))

  /** `psi`'s constants: `1 / (1 + i)^((p - 1) / 3)` and `1 / (1 + i)^((p - 1) / 2)`. */
  private val (psiX, psiY) =
    val base = Fp2Element.of(BigInteger.ONE, BigInteger.ONE)
    val pMinus1 = P.subtract(BigInteger.ONE)
    (
      base.modPow(pMinus1.divide(BigInteger.valueOf(3))).inverse(),
      base.modPow(pMinus1.shiftRight(1)).inverse()
    )

  /** `psi` (RFC 9380 appendix G.3): conjugation of the coordinates, which commutes with the
    * Jacobian scaling, and the two constants.
    */
  def psi(p: Jacobian): Jacobian =
    if p.isInfinity then p
    else Jacobian(conjugate(p.x).mul(psiX), conjugate(p.y).mul(psiY), conjugate(p.z))

  /** `clear_cofactor_bls12381_g2` (RFC 9380 appendix G.3), line by line. */
  def clear(p: Jacobian): Jacobian =
    val t1 = timesX(p)
    val t2 = psi(p)
    val t3 = psi(psi(double(p)))
    val t3b = add(t3, t2.negate)
    val t2b = timesX(add(t1, t2))
    add(add(add(t3b, t2b), t1.negate), p.negate)

  /** The hashed point of the two field elements' mapped points, `q0` and `q1`. */
  def hashed(q0: BLS12_381G2Point, q1: BLS12_381G2Point): BLS12_381G2Point =
    affine(clear(add(of(q0), of(q1))))
