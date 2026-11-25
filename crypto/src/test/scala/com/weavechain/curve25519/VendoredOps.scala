// SPDX-License-Identifier: Apache-2.0
package com.weavechain.curve25519

/** The vendored field and point operations on raw limbs, for the suites of other packages that
  * compare an arithmetic with them limb for limb (the verifiers' arithmetic). Every argument is
  * copied before the vendored object is made, and every result read out as a fresh array, so
  * nothing here shares storage with its caller. The private limbs and tables are read by
  * reflection, as `FieldElementSuite` reads them.
  */
object VendoredOps:
  type Limbs = Array[Int]

  /** Four coordinates: an `EdwardsPoint` or `CompletedPoint` (X, Y, Z, T), or a
    * `ProjectiveNielsPoint` (Y + X, Y - X, Z, 2d T).
    */
  type Four = (Limbs, Limbs, Limbs, Limbs)

  /** Three coordinates: a `ProjectivePoint` (X, Y, Z), or an `AffineNielsPoint` (y + x, y - x, 2d x
    * y).
    */
  type Three = (Limbs, Limbs, Limbs)

  private val limbField =
    val field = classOf[FieldElement].getDeclaredField("t")
    field.setAccessible(true)
    field

  private def tableField(c: Class[?]) =
    val field = c.getDeclaredField("table")
    field.setAccessible(true)
    field

  def limbs(fe: FieldElement): Limbs = limbField.get(fe).asInstanceOf[Array[Int]].clone()
  private def fe(t: Limbs): FieldElement = new FieldElement(t.clone())

  def fromByteArray(bytes: Array[Byte]): Limbs = limbs(FieldElement.fromByteArray(bytes.clone()))
  def toByteArray(t: Limbs): Array[Byte] = fe(t).toByteArray
  def isNegative(t: Limbs): Int = fe(t).isNegative()
  def isZero(t: Limbs): Int = fe(t).isZero()
  def ctEquals(f: Limbs, g: Limbs): Int = fe(f).ctEquals(fe(g))

  def add(f: Limbs, g: Limbs): Limbs = limbs(fe(f).add(fe(g)))
  def subtract(f: Limbs, g: Limbs): Limbs = limbs(fe(f).subtract(fe(g)))
  def negate(f: Limbs): Limbs = limbs(fe(f).negate())
  def multiply(f: Limbs, g: Limbs): Limbs = limbs(fe(f).multiply(fe(g)))
  def square(f: Limbs): Limbs = limbs(fe(f).square())
  def squareAndDouble(f: Limbs): Limbs = limbs(fe(f).squareAndDouble())
  def invert(f: Limbs): Limbs = limbs(fe(f).invert())
  def powP58(f: Limbs): Limbs = limbs(fe(f).powP58())

  def sqrtRatioM1(u: Limbs, v: Limbs): (Int, Limbs) =
    val r = FieldElement.sqrtRatioM1(fe(u), fe(v))
    (r.wasSquare, limbs(r.result))

  val one: Limbs = limbs(FieldElement.ONE)
  val edwardsD: Limbs = limbs(Constants.EDWARDS_D)
  val edwards2D: Limbs = limbs(Constants.EDWARDS_2D)
  val sqrtM1: Limbs = limbs(Constants.SQRT_M1)

  private def four(e: EdwardsPoint): Four = (limbs(e.X), limbs(e.Y), limbs(e.Z), limbs(e.T))
  private def four(c: CompletedPoint): Four = (limbs(c.X), limbs(c.Y), limbs(c.Z), limbs(c.T))
  private def four(n: ProjectiveNielsPoint): Four =
    (limbs(n.YPlusX), limbs(n.YMinusX), limbs(n.Z), limbs(n.T2D))
  private def three(p: ProjectivePoint): Three = (limbs(p.X), limbs(p.Y), limbs(p.Z))
  private def three(a: AffineNielsPoint): Three = (limbs(a.yPlusx), limbs(a.yMinusx), limbs(a.xy2D))

  private def edwards(e: Four): EdwardsPoint =
    new EdwardsPoint(fe(e._1), fe(e._2), fe(e._3), fe(e._4))
  private def completed(c: Four): CompletedPoint =
    new CompletedPoint(fe(c._1), fe(c._2), fe(c._3), fe(c._4))
  private def projective(p: Three): ProjectivePoint =
    new ProjectivePoint(fe(p._1), fe(p._2), fe(p._3))
  private def projectiveNiels(n: Four): ProjectiveNielsPoint =
    new ProjectiveNielsPoint(fe(n._1), fe(n._2), fe(n._3), fe(n._4))
  private def affineNiels(a: Three): AffineNielsPoint =
    new AffineNielsPoint(fe(a._1), fe(a._2), fe(a._3))

  val basepoint: Four = four(Constants.ED25519_BASEPOINT)

  /** `Constants.AFFINE_ODD_MULTIPLES_OF_BASEPOINT`: [B, 3B, ..., 15B]. */
  val basepointOddMultiples: Vector[Three] =
    tableField(classOf[AffineNielsPoint.NafLookupTable])
      .get(Constants.AFFINE_ODD_MULTIPLES_OF_BASEPOINT)
      .asInstanceOf[Array[AffineNielsPoint]]
      .toVector
      .map(three)

  def decompress(encoding: Array[Byte]): Option[Four] =
    scala.util.Try(new CompressedEdwardsY(encoding.clone()).decompress()).toOption.map(four)

  def compress(e: Four): Array[Byte] = edwards(e).compress().toByteArray

  def projectiveDbl(p: Three): Four = four(projective(p).dbl())
  def completedToProjective(c: Four): Three = three(completed(c).toProjective())
  def completedToExtended(c: Four): Four = four(completed(c).toExtended())
  def projectiveToExtended(p: Three): Four = four(projective(p).toExtended())
  def toProjective(e: Four): Three = three(edwards(e).toProjective())
  def toProjectiveNiels(e: Four): Four = four(edwards(e).toProjectiveNiels())
  def toAffineNiels(e: Four): Three = three(edwards(e).toAffineNiels())
  def negate(e: Four): Four = four(edwards(e).negate())
  def dbl(e: Four): Four = four(edwards(e).dbl())

  def addProjectiveNiels(e: Four, q: Four): Four = four(edwards(e).add(projectiveNiels(q)))
  def subtractProjectiveNiels(e: Four, q: Four): Four = four(
    edwards(e).subtract(projectiveNiels(q))
  )
  def addAffineNiels(e: Four, q: Three): Four = four(edwards(e).add(affineNiels(q)))
  def subtractAffineNiels(e: Four, q: Three): Four = four(edwards(e).subtract(affineNiels(q)))

  def isSmallOrder(e: Four): Boolean = edwards(e).isSmallOrder()

  /** `ProjectiveNielsPoint.buildNafLookupTable`: [P, 3P, ..., 15P]. */
  def nafLookupTable(e: Four): Vector[Four] =
    tableField(classOf[ProjectiveNielsPoint.NafLookupTable])
      .get(ProjectiveNielsPoint.buildNafLookupTable(edwards(e)))
      .asInstanceOf[Array[ProjectiveNielsPoint]]
      .toVector
      .map(four)

  /** `Scalar.nonAdjacentForm` of 32 bytes with bit 255 clear. */
  def nonAdjacentForm(s: Array[Byte]): Array[Byte] = new Scalar(s).nonAdjacentForm()

  /** `EdwardsPoint.vartimeDoubleScalarMultiplyBasepoint(a, A, b)`, encoded. */
  def vartimeDoubleScalarMultiplyBasepoint(
      a: Array[Byte],
      point: Four,
      b: Array[Byte]
  ): Array[Byte] =
    EdwardsPoint
      .vartimeDoubleScalarMultiplyBasepoint(new Scalar(a), edwards(point), new Scalar(b))
      .compress()
      .toByteArray

  /** Whether `Scalar.fromCanonicalBytes` accepts the 32 bytes. */
  def canonicalScalar(s: Array[Byte]): Boolean =
    scala.util.Try(Scalar.fromCanonicalBytes(s.clone())).isSuccess
