// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import com.weavechain.curve25519.Curve25519Gen.*
import com.weavechain.curve25519.VendoredOps
import com.weavechain.curve25519.VendoredOps.{Four, Limbs, Three}
import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink
import xyz.kd5ujc.scarab.crypto.witness.Ed25519Model as M
import xyz.kd5ujc.scarab.crypto.witness.VartimeEd25519 as V

/** `VartimeEd25519` 's operations against exact arithmetic on `BigInt` (`Curve25519Gen`,
  * `Ed25519Model`) and against the vendored operations they are written from (`VendoredOps`), which
  * must give the same limbs, not only the same values.
  *
  * The field operations are checked over the limb ranges their documentation admits, to the edges
  * of those ranges (every sign pattern of limbs at their bounds, values near multiples of p), and
  * with the result written over each operand in turn: they write into an array the caller passes,
  * and an operation that wrote a limb before it had read all of its operands would go wrong only
  * when the result is one of them. The encodings are written at an offset into a larger array,
  * whose other bytes must be left alone. The point operations are checked on points of every order,
  * in the representations the vendored operations produce (Z = 1 after decoding, and products after
  * a doubling or an addition).
  */
class VartimeEd25519Suite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private def vec(t: Array[Int]): Vector[Int] = t.toVector
  private def value(t: Limbs): BigInt = limbValue(t).mod(p)

  private def withinBounds(t: Limbs, bounds: Int => Int, what: String): Unit =
    t.indices.foreach { i =>
      assert(
        math.abs(t(i).toLong) <= bounds(i),
        s"$what: limb $i is ${t(i)}, bound ${bounds(i)} (limbs ${t.mkString(",")})"
      )
    }

  private def same(got: Limbs, expected: Limbs, what: String): Unit =
    assertEquals(vec(got), vec(expected), what)

  // ---- field elements

  /** `f` binary operation into a fresh result, and into each operand in turn. */
  private def binaryEverywhere(
      op: (Limbs, Limbs, Limbs) => Unit,
      f: Limbs,
      g: Limbs
  ): Vector[(String, Limbs)] =
    val fresh = V.fe()
    op(fresh, f.clone(), g.clone())
    val intoF = f.clone()
    op(intoF, intoF, g.clone())
    val intoG = g.clone()
    op(intoG, f.clone(), intoG)
    Vector("fresh" -> fresh, "result over f" -> intoF, "result over g" -> intoG)

  /** `op(x, x, x)`: both operands and the result one array. */
  private def binarySelf(op: (Limbs, Limbs, Limbs) => Unit, f: Limbs): Limbs =
    val all = f.clone()
    op(all, all, all)
    all

  private def unaryEverywhere(op: (Limbs, Limbs) => Unit, f: Limbs): Vector[(String, Limbs)] =
    val fresh = V.fe()
    op(fresh, f.clone())
    val over = f.clone()
    op(over, over)
    Vector("fresh" -> fresh, "result over f" -> over)

  private val reduced: Gen[Limbs] = limbs(reducedBound)
  private val loose: Gen[Limbs] = limbs(looseBound)

  property("fromBytes gives the vendored limbs, read at an offset, within the reduced bounds") {
    forAllNoShrink(fieldInput, Gen.choose(0, 40), Gen.choose(0, 255)) { (v, offset, fill) =>
      val bytes = le(v, 32)
      val padded = Array.fill[Byte](offset + 32 + 7)(fill.toByte)
      System.arraycopy(bytes, 0, padded, offset, 32)
      val h = V.fe()
      V.fromBytes(h, padded, offset)
      same(h, VendoredOps.fromByteArray(bytes), s"fromBytes(${v.toString(16)}) at $offset")
      assertEquals(value(h), (v & ((BigInt(1) << 255) - 1)).mod(p), "value")
      withinBounds(h, reducedBound, "fromBytes")
    }
  }

  /** toBytes, reduce, isZero, isNegative and equal on limbs `t`, the result written at `offset`. */
  private def encodes(t: Limbs, offset: Int, what: String): Unit =
    val expected = VendoredOps.toByteArray(t)
    val v = limbValue(t).mod(p)
    val clue = s"$what: limbs ${t.mkString(",")}"
    val out = Array.fill[Byte](offset + 32 + 5)(0x5a)
    val w = new VartimeEd25519()
    val operand = t.clone()
    w.toBytes(out, offset, operand)
    same(operand, t, s"toBytes leaves its operand, $clue")
    assertEquals(out.slice(offset, offset + 32).toVector, expected.toVector, s"toBytes, $clue")
    assertEquals(fromLe(out.slice(offset, offset + 32)), v, s"toBytes value, $clue")
    assert(
      (out.take(offset) ++ out.drop(offset + 32)).forall(_ == 0x5a),
      s"toBytes writes only its 32 bytes, $clue"
    )
    val fresh = V.fe()
    V.reduce(fresh, t.clone())
    val over = t.clone()
    V.reduce(over, over)
    same(over, fresh, s"reduce over its operand, $clue")
    assertEquals(limbValue(over), v, s"reduce is canonical, $clue")
    assertEquals(w.isZero(operand), v == 0, s"isZero, $clue")
    assertEquals(w.isZero(operand), VendoredOps.isZero(t) == 1, s"isZero as vendored, $clue")
    assertEquals(w.isNegative(operand), if v.testBit(0) then 1 else 0, s"isNegative, $clue")
    assertEquals(w.isNegative(operand), VendoredOps.isNegative(t), s"isNegative as vendored, $clue")
    same(operand, t, s"isZero and isNegative leave their operand, $clue")

  property("toBytes, reduce, isZero and isNegative over every limb below 2^27, as vendored") {
    forAllNoShrink(limbs(documentedEncodableBound), Gen.choose(0, 40))((t, offset) =>
      encodes(t, offset, "documented range")
    )
  }

  test("toBytes at every sign pattern of limbs at 2^27 - 1 and at the sum bounds, and 2^257 - 76") {
    signPatterns(documentedEncodableBound).foreach(encodes(_, 3, "limbs at 2^27 - 1"))
    signPatterns(sumBound).foreach(encodes(_, 0, "limbs at the sum bounds"))
    // FieldElementSuite's case of 19 * h9 beyond 32 bits: 2^257 - 76, a multiple of p
    val t =
      Array((1 << 26) - 76) ++ (1 until 9).map(i => (1 << (if i % 2 == 0 then 26 else 25)) - 1) ++
        Array((1 << 27) - 1)
    encodes(t, 1, "2^257 - 76")
    assertEquals(fromLe(VendoredOps.toByteArray(t)), BigInt(0))
  }

  test("toBytes of k p + delta, |k| up to 4, written with limbs as large as they go") {
    val patterns = Vector(-2, -1, 1, 2).flatMap(c =>
      Vector(Vector.fill(9)(c), Vector.tabulate(9)(i => if i % 2 == 0 then c else -c))
    )
    for
      k <- -4 to 4
      delta <- -2 to 2
      moves <- patterns
      t <- limbsFor(k * p + delta, documentedEncodableBound, moves)
    do encodes(t, 2, s"$k p + $delta")
  }

  property("equal is equality modulo p, as the vendored ctEquals") {
    forAllNoShrink(limbs(sumBound), limbs(sumBound)) { (s, t) =>
      val w = new VartimeEd25519()
      val (f, g) = (s.clone(), t.clone())
      assertEquals(w.equal(f, g), value(s) == value(t), "equal")
      assertEquals(w.equal(f, g), VendoredOps.ctEquals(s, t) == 1, "as vendored")
      same(f, s, "equal leaves its first operand")
      same(g, t, "equal leaves its second operand")
      assertEquals(w.equal(f, f), true, "an element is equal to itself, one array")
      assertEquals(w.equal(s, s.clone()), true, "equal limbs")
      // the same value with other limbs: p added (2^25 in limb 9, less 19 in limb 0)
      val shifted = s.clone()
      shifted(9) = shifted(9) + (1 << 25)
      shifted(0) = shifted(0) - 19
      assertEquals(w.equal(s, shifted), true, "s and s + p")
    }
  }

  private def sumsAndDifferences(f: Limbs, g: Limbs, clue: String): Unit =
    binaryEverywhere(V.add, f, g).foreach { (where, h) =>
      same(h, VendoredOps.add(f, g), s"add, $where, $clue")
      assertEquals(value(h), (limbValue(f) + limbValue(g)).mod(p), s"add value, $where, $clue")
    }
    binaryEverywhere(V.sub, f, g).foreach { (where, h) =>
      same(h, VendoredOps.subtract(f, g), s"sub, $where, $clue")
      assertEquals(value(h), (limbValue(f) - limbValue(g)).mod(p), s"sub value, $where, $clue")
    }
    unaryEverywhere(V.neg, f).foreach { (where, h) =>
      same(h, VendoredOps.negate(f), s"neg, $where, $clue")
      withinBounds(h, reducedBound, s"neg, $where, $clue")
    }
    same(binarySelf(V.add, f), VendoredOps.add(f, f), s"add, all one array, $clue")
    same(binarySelf(V.sub, f), Array.fill(10)(0), s"sub, all one array, $clue")
    withinBounds(VendoredOps.add(f, g), sumBound, s"add, $clue")
    withinBounds(VendoredOps.subtract(f, g), sumBound, s"sub, $clue")

  property("add, sub and neg are the vendored operations, wherever the result is written") {
    forAllNoShrink(reduced, reduced)((f, g) => sumsAndDifferences(f, g, "reduced"))
  }

  test("add, sub and neg at every sign pattern of the reduced bounds") {
    val patterns = signPatterns(reducedBound)
    for
      s <- patterns
      t <- Vector(patterns.head, patterns.last, s, s.map(-_))
    do sumsAndDifferences(s, t, s"limbs ${s.mkString(",")} and ${t.mkString(",")}")
  }

  private def products(f: Limbs, g: Limbs, clue: String): Unit =
    val (x, y) = (limbValue(f), limbValue(g))
    binaryEverywhere(V.mul, f, g).foreach { (where, h) =>
      same(h, VendoredOps.multiply(f, g), s"mul, $where, $clue")
      assertEquals(value(h), (x * y).mod(p), s"mul value, $where, $clue")
      withinBounds(h, productBound, s"mul, $where, $clue")
    }
    same(binarySelf(V.mul, f), VendoredOps.multiply(f, f), s"mul, all one array, $clue")
    unaryEverywhere(V.sq, f).foreach { (where, h) =>
      same(h, VendoredOps.square(f), s"sq, $where, $clue")
      assertEquals(value(h), (x * x).mod(p), s"sq value, $where, $clue")
      withinBounds(h, productBound, s"sq, $where, $clue")
    }
    unaryEverywhere(V.sq2, f).foreach { (where, h) =>
      same(h, VendoredOps.squareAndDouble(f), s"sq2, $where, $clue")
      assertEquals(value(h), (2 * x * x).mod(p), s"sq2 value, $where, $clue")
      withinBounds(h, productBound, s"sq2, $where, $clue")
    }

  property("mul, sq and sq2 are the vendored operations, wherever the result is written") {
    forAllNoShrink(loose, loose)((f, g) => products(f, g, "loose"))
  }

  test("mul, sq and sq2 at every sign pattern of the loose bounds") {
    val patterns = signPatterns(looseBound)
    for
      s <- patterns
      t <- Vector(patterns.head, patterns.last, s, s.map(-_))
    do products(s, t, s"limbs ${s.mkString(",")} and ${t.mkString(",")}")
  }

  property("mul is exact when the product is at the edges: 0, 1, p - 1 and their neighbours") {
    val operands = for
      x <- fieldInput.map(_.mod(p)).suchThat(_ != 0)
      e <- Gen.oneOf(Vector[BigInt](0, 1, 2, 3, p - 1, p - 2, p - 3, (p - 1) / 2))
      kx <- Gen.choose(-1, 1)
      ky <- Gen.choose(-1, 1)
      mx <- Gen.listOfN(9, Gen.oneOf(-1, 0, 1))
      my <- Gen.listOfN(9, Gen.oneOf(-1, 0, 1))
      a <- Gen.const(limbsFor(x + kx * p, looseBound, mx))
      b <- Gen.const(limbsFor((e * x.modPow(p - 2, p)).mod(p) + ky * p, looseBound, my))
      if a.isDefined && b.isDefined
    yield (a.get, b.get, e)
    forAllNoShrink(operands) { (a, b, e) =>
      val h = V.fe()
      V.mul(h, a, b)
      assertEquals(value(h), e, s"x * (e / x) for e = ${e.toString(16)}")
      same(h, VendoredOps.multiply(a, b), "as vendored")
    }
  }

  /** Elements as the arithmetic makes them: decoded from bytes, and products. */
  private val made: Gen[Limbs] =
    Gen.frequency(
      2 -> fieldInput.map(v => VendoredOps.fromByteArray(le(v, 32))),
      1 -> Gen.zip(loose, loose).map(VendoredOps.multiply)
    )

  property("invert and powP58 are the vendored chains, also with the result over the operand") {
    forAllNoShrink(Gen.oneOf(made, loose)) { z =>
      val x = limbValue(z)
      val w = new VartimeEd25519()
      unaryEverywhere((h, f) => w.invert(h, f), z)
        .foreach { (where, h) =>
          same(h, VendoredOps.invert(z), s"invert, $where")
          assertEquals(value(h), x.mod(p).modPow(p - 2, p), s"invert value, $where")
        }
      unaryEverywhere((h, f) => w.powP58(h, f), z).foreach { (where, h) =>
        same(h, VendoredOps.powP58(z), s"powP58, $where")
        assertEquals(value(h), x.mod(p).modPow((p - 5) / 8, p), s"powP58 value, $where")
      }
    }
  }

  property("sqrtRatioM1 gives the vendored root and verdict") {
    val sums = limbs(sumBound)
    val operands = Gen.frequency(
      4 -> Gen.zip(made, made),
      2 -> Gen.zip(sums, sums),
      1 -> made.map(u => (u, V.fe())),
      1 -> made.map(v => (V.fe(), v)),
      1 -> made.map(u => (VendoredOps.square(u), VendoredOps.one)),
      1 -> Gen.zip(made, made).map((r, v) => (VendoredOps.multiply(VendoredOps.square(r), v), v))
    )
    forAllNoShrink(operands) { (u, v) =>
      val w = new VartimeEd25519()
      val (expectedSquare, expectedRoot) = VendoredOps.sqrtRatioM1(u, v)
      val clue = s"u=${value(u).toString(16)} v=${value(v).toString(16)}"
      val (uIn, vIn) = (u.clone(), v.clone())
      val r = V.fe()
      val square = w.sqrtRatioM1(r, uIn, vIn)
      assertEquals(square, expectedSquare == 1, s"wasSquare, $clue")
      same(r, expectedRoot, s"root, $clue")
      same(uIn, u, s"u is left, $clue")
      same(vIn, v, s"v is left, $clue")
      assert(!value(r).testBit(0), s"the root is non-negative, $clue")
      // the root written over u, and over v
      val overU = u.clone()
      assertEquals(w.sqrtRatioM1(overU, overU, v.clone()), square, s"wasSquare, root over u, $clue")
      same(overU, expectedRoot, s"root over u, $clue")
      val overV = v.clone()
      assertEquals(w.sqrtRatioM1(overV, u.clone(), overV), square, s"wasSquare, root over v, $clue")
      same(overV, expectedRoot, s"root over v, $clue")
    }
  }

  test("the constants are the vendored ones and those of edwards25519") {
    same(V.copyOfD(), VendoredOps.edwardsD, "d")
    same(V.copyOfD2(), VendoredOps.edwards2D, "2d")
    same(V.copyOfSqrtM1(), VendoredOps.sqrtM1, "sqrt(-1)")
    same(V.copyOfOne(), VendoredOps.one, "one")
    assertEquals(value(V.copyOfD()), M.d, "d")
    assertEquals(value(V.copyOfD2()), (2 * M.d).mod(p), "2d")
    assertEquals(value(V.copyOfSqrtM1()), M.sqrtM1, "sqrt(-1)")
    // a copy is a copy: writing to it changes no later copy, and no verification
    val scribbled = V.copyOfD()
    V.zero(scribbled)
    same(V.copyOfD(), VendoredOps.edwardsD, "d after a copy was overwritten")
    val b = V.copyOfBasepoint()
    Vector(b.X, b.Y, b.Z, b.T).zip(fourVector(VendoredOps.basepoint)).zipWithIndex.foreach {
      case ((got, expected), i) => same(got, expected, s"basepoint coordinate $i")
    }
  }

  test("the basepoint's odd multiples are the vendored table, and [1, 3, ..., 15]B") {
    val table = (0 until 8).map(V.copyOfBasepointOddMultiple)
    table.zip(VendoredOps.basepointOddMultiples).zipWithIndex.foreach { case ((got, expected), i) =>
      same(got.yPlusx, expected._1, s"${2 * i + 1}B y + x")
      same(got.yMinusx, expected._2, s"${2 * i + 1}B y - x")
      same(got.xy2D, expected._3, s"${2 * i + 1}B 2d x y")
      val m = M.basepoint * (2 * i + 1)
      val (x, y) = (m.affineX, m.affineY)
      assertEquals(value(got.yPlusx), (y + x).mod(p), s"${2 * i + 1}B y + x value")
      assertEquals(value(got.yMinusx), (y - x).mod(p), s"${2 * i + 1}B y - x value")
      assertEquals(value(got.xy2D), (2 * M.d * x * y).mod(p), s"${2 * i + 1}B 2d x y value")
    }
  }

  // ---- points

  private def fourVector(f: Four): Vector[Limbs] = Vector(f._1, f._2, f._3, f._4)

  private def extended(f: Four): V.Extended =
    val e = new V.Extended()
    V.copy(e.X, f._1); V.copy(e.Y, f._2); V.copy(e.Z, f._3); V.copy(e.T, f._4)
    e
  private def completed(f: Four): V.Completed =
    val c = new V.Completed()
    V.copy(c.X, f._1); V.copy(c.Y, f._2); V.copy(c.Z, f._3); V.copy(c.T, f._4)
    c
  private def projective(t: Three): V.Projective =
    val r = new V.Projective()
    V.copy(r.X, t._1); V.copy(r.Y, t._2); V.copy(r.Z, t._3)
    r
  private def cached(f: Four): V.Cached =
    val c = new V.Cached()
    V.copy(c.YPlusX, f._1); V.copy(c.YMinusX, f._2); V.copy(c.Z, f._3); V.copy(c.T2D, f._4)
    c
  private def affine(t: Three): V.Affine =
    val a = new V.Affine()
    V.copy(a.yPlusx, t._1); V.copy(a.yMinusx, t._2); V.copy(a.xy2D, t._3)
    a

  private def four(e: V.Extended): Four = (e.X.clone(), e.Y.clone(), e.Z.clone(), e.T.clone())
  private def four(c: V.Completed): Four = (c.X.clone(), c.Y.clone(), c.Z.clone(), c.T.clone())
  private def four(c: V.Cached): Four =
    (c.YPlusX.clone(), c.YMinusX.clone(), c.Z.clone(), c.T2D.clone())
  private def three(r: V.Projective): Three = (r.X.clone(), r.Y.clone(), r.Z.clone())
  private def three(a: V.Affine): Three = (a.yPlusx.clone(), a.yMinusx.clone(), a.xy2D.clone())

  private def sameFour(got: Four, expected: Four, what: String): Unit =
    fourVector(got).zip(fourVector(expected)).zipWithIndex.foreach { case ((g, e), i) =>
      same(g, e, s"$what, coordinate $i")
    }
  private def sameThree(got: Three, expected: Three, what: String): Unit =
    Vector(got._1, got._2, got._3)
      .zip(Vector(expected._1, expected._2, expected._3))
      .zipWithIndex
      .foreach { case ((g, e), i) => same(g, e, s"$what, coordinate $i") }

  /** Points of every order, [a]B + T for each torsion point T. */
  private val modelPoint: Gen[M.Point] =
    Gen.frequency(
      4 -> Gen.zip(canonicalScalar, Gen.choose(0, 7)).map((a, i) => M.basepoint * a + M.torsion(i)),
      2 -> canonicalScalar.map(M.basepoint * _),
      1 -> Gen.oneOf(M.torsion),
      1 -> Gen.const(M.basepoint)
    )

  /** The vendored extended limbs of a point: decoded (Z = 1), doubled, or a sum, so Z is general.
    */
  private val extendedPoint: Gen[(M.Point, Four)] =
    for
      m <- modelPoint
      n <- modelPoint
      form <- Gen.choose(0, 2)
    yield
      val e = VendoredOps.decompress(m.encode).get
      form match
        case 0 => (m, e)
        case 1 => (m + m, VendoredOps.dbl(e))
        case _ =>
          val f = VendoredOps.decompress(n.encode).get
          (
            m + n,
            VendoredOps.completedToExtended(
              VendoredOps.addProjectiveNiels(e, VendoredOps.toProjectiveNiels(f))
            )
          )

  private def affineOf(e: Four): (BigInt, BigInt) =
    val z = value(e._3).modPow(p - 2, p)
    ((value(e._1) * z).mod(p), (value(e._2) * z).mod(p))

  private def matches(e: Four, m: M.Point, what: String): Unit =
    assertEquals(affineOf(e), (m.affineX, m.affineY), what)

  property("the conversions between representations are the vendored ones") {
    forAllNoShrink(extendedPoint) { (m, e) =>
      val p2 = new V.Projective()
      V.toProjective(p2, extended(e))
      sameThree(three(p2), VendoredOps.toProjective(e), "EdwardsPoint.toProjective")
      val c = new V.Cached()
      V.toCached(c, extended(e))
      sameFour(four(c), VendoredOps.toProjectiveNiels(e), "toProjectiveNiels")
      val n = new V.Extended()
      V.negate(n, extended(e))
      sameFour(four(n), VendoredOps.negate(e), "negate")
      val self = extended(e)
      V.negate(self, self)
      sameFour(four(self), VendoredOps.negate(e), "negate over itself")
      matches(four(self), -m, "negate is -P")

      val done = VendoredOps.projectiveDbl(VendoredOps.toProjective(e))
      val r = new V.Projective()
      V.toProjective(r, completed(done))
      sameThree(three(r), VendoredOps.completedToProjective(done), "CompletedPoint.toProjective")
      val x = new V.Extended()
      V.toExtended(x, completed(done))
      sameFour(four(x), VendoredOps.completedToExtended(done), "CompletedPoint.toExtended")
      val y = new V.Extended()
      V.toExtended(y, projective(VendoredOps.toProjective(e)))
      sameFour(
        four(y),
        VendoredOps.projectiveToExtended(VendoredOps.toProjective(e)),
        "ProjectivePoint.toExtended"
      )

      val a = new V.Affine()
      new VartimeEd25519().toAffine(a, extended(e))
      sameThree(three(a), VendoredOps.toAffineNiels(e), "toAffineNiels")
    }
  }

  property("doubling is the vendored doubling, also over itself, and [2]P") {
    forAllNoShrink(extendedPoint) { (m, e) =>
      val c = new V.Completed()
      new VartimeEd25519().dbl(c, projective(VendoredOps.toProjective(e)))
      sameFour(
        four(c),
        VendoredOps.projectiveDbl(VendoredOps.toProjective(e)),
        "ProjectivePoint.dbl"
      )
      val w = new VartimeEd25519()
      val d = new V.Extended()
      w.dbl(d, extended(e))
      sameFour(four(d), VendoredOps.dbl(e), "EdwardsPoint.dbl")
      matches(four(d), m + m, "[2]P")
      val self = extended(e)
      w.dbl(self, self)
      sameFour(four(self), VendoredOps.dbl(e), "EdwardsPoint.dbl over itself")
    }
  }

  property("addition and subtraction of Niels points are the vendored ones, and P + Q, P - Q") {
    forAllNoShrink(extendedPoint, extendedPoint) { case ((m, e), (n, f)) =>
      val q = VendoredOps.toProjectiveNiels(f)
      val w = new VartimeEd25519()
      val sum = new V.Completed()
      w.add(sum, extended(e), cached(q))
      sameFour(four(sum), VendoredOps.addProjectiveNiels(e, q), "add(ProjectiveNiels)")
      matches(VendoredOps.completedToExtended(four(sum)), m + n, "P + Q")
      val difference = new V.Completed()
      w.sub(difference, extended(e), cached(q))
      sameFour(four(difference), VendoredOps.subtractProjectiveNiels(e, q), "sub(ProjectiveNiels)")
      matches(VendoredOps.completedToExtended(four(difference)), m - n, "P - Q")

      val a = VendoredOps.toAffineNiels(f)
      val affineSum = new V.Completed()
      w.add(affineSum, extended(e), affine(a))
      sameFour(four(affineSum), VendoredOps.addAffineNiels(e, a), "add(AffineNiels)")
      matches(VendoredOps.completedToExtended(four(affineSum)), m + n, "P + q")
      val affineDifference = new V.Completed()
      w.sub(affineDifference, extended(e), affine(a))
      sameFour(four(affineDifference), VendoredOps.subtractAffineNiels(e, a), "sub(AffineNiels)")
      matches(VendoredOps.completedToExtended(four(affineDifference)), m - n, "P - q")
    }
  }

  property(
    "the odd multiples of a point are the vendored table, also built over the point itself"
  ) {
    forAllNoShrink(extendedPoint) { (m, e) =>
      val expected = VendoredOps.nafLookupTable(e)
      val w = new VartimeEd25519()
      w.oddMultiples(extended(e))
      expected.zipWithIndex.foreach { (t, i) => sameFour(four(w.table(i)), t, s"${2 * i + 1}P") }
      val own = new VartimeEd25519()
      V.copy(own.point.X, e._1); V.copy(own.point.Y, e._2); V.copy(own.point.Z, e._3)
      V.copy(own.point.T, e._4)
      own.oddMultiples(own.point)
      expected.zipWithIndex.foreach { (t, i) =>
        sameFour(four(own.table(i)), t, s"${2 * i + 1}P from the instance's own point")
      }
      expected.indices.foreach { i =>
        val c = own.table(i)
        val z = value(c.Z).modPow(p - 2, p)
        val multiple = m * (2 * i + 1)
        val (x, y) = (multiple.affineX, multiple.affineY)
        assertEquals((value(c.YPlusX) * z).mod(p), (y + x).mod(p), s"${2 * i + 1}P y + x")
        assertEquals((value(c.YMinusX) * z).mod(p), (y - x).mod(p), s"${2 * i + 1}P y - x")
        assertEquals((value(c.T2D) * z).mod(p), (2 * M.d * x * y).mod(p), s"${2 * i + 1}P 2d x y")
      }
    }
  }

  // ---- scalars

  /** The width-5 form: odd digits of at most 15 in absolute value, summing to the integer. */
  private def nafValue(naf: Array[Byte]): BigInt =
    naf.indices.map(i => BigInt(naf(i).toInt) << i).sum

  property("nonAdjacentForm is the vendored form, read at an offset, and sums to the scalar") {
    forAllNoShrink(Gen.oneOf(scalar255, canonicalScalar), Gen.choose(0, 40)) { (s, offset) =>
      val bytes = le(s, 32)
      val padded = Array.fill[Byte](offset + 40)(0x7f)
      System.arraycopy(bytes, 0, padded, offset, 32)
      val naf = new Array[Byte](256)
      V.nonAdjacentForm(naf, padded, offset)
      assertEquals(
        naf.toVector,
        VendoredOps.nonAdjacentForm(bytes).toVector,
        s"naf of ${s.toString(16)}"
      )
      assertEquals(nafValue(naf), s, "the digits sum to the scalar")
      assert(
        naf.forall(d => d == 0 || (d % 2 != 0 && math.abs(d.toInt) <= 15)),
        "odd digits up to 15"
      )
    }
  }

  property("canonicalScalar is S < L, as Scalar.fromCanonicalBytes accepts") {
    forAllNoShrink(scalar256, Gen.choose(0, 8)) { (s, offset) =>
      val bytes = le(s, 32)
      val padded = Array.fill[Byte](offset + 32)(0xff.toByte) ++ Array.fill[Byte](3)(0)
      System.arraycopy(bytes, 0, padded, offset, 32)
      assertEquals(V.canonicalScalar(padded, offset), s < L, s.toString(16))
      assertEquals(
        V.canonicalScalar(padded, offset),
        VendoredOps.canonicalScalar(bytes),
        "as vendored"
      )
    }
  }

  test("canonicalScalar on both sides of L, and of 2^255") {
    Vector(
      L - 2,
      L - 1,
      L,
      L + 1,
      2 * L,
      (BigInt(1) << 252) - 1,
      BigInt(1) << 252,
      (BigInt(1) << 253) - 1,
      (BigInt(1) << 255) - 1,
      BigInt(1) << 255,
      (BigInt(1) << 256) - 1,
      BigInt(0),
      BigInt(1)
    ).foreach { s =>
      val bytes = le(s, 32)
      assertEquals(V.canonicalScalar(bytes, 0), s < L, s.toString(16))
      assertEquals(
        V.canonicalScalar(bytes, 0),
        VendoredOps.canonicalScalar(bytes),
        s"as vendored ${s.toString(16)}"
      )
    }
  }

  // ---- decoding, order, and the double multiplication

  /** Encodings at the edges of y, both signs, points of every order, and random bytes. */
  private val edgeEncoding: Gen[Array[Byte]] =
    val ys = (BigInt(0) until 24).toVector ++ (BigInt(-24) until 24).map(p + _) ++
      Vector((BigInt(1) << 255) - 1, BigInt(1) << 254, (p - 1) / 2) ++ M.torsion.map(_.affineY)
    Gen.frequency(
      3 -> Gen
        .zip(Gen.oneOf(ys.filter(y => y >= 0 && y < (BigInt(1) << 255))), Gen.oneOf(0, 1))
        .map((y, s) => le(if s == 1 then y.setBit(255) else y, 32)),
      2 -> modelPoint.map(_.encode),
      2 -> uniform(256).map(le(_, 32))
    )

  property("decode gives the vendored point, or fails as decompress does; canonicalY is y < p") {
    forAllNoShrink(edgeEncoding, Gen.choose(0, 40)) { (e, offset) =>
      val padded = Array.fill[Byte](offset + 32 + 3)(0x33)
      System.arraycopy(e, 0, padded, offset, 32)
      val w = new VartimeEd25519()
      val decoded = w.decode(w.point, padded, offset)
      val expected = VendoredOps.decompress(e)
      assertEquals(decoded, expected.isDefined, s"decodes ${hex(e)}")
      expected.foreach(x => sameFour(four(w.point), x, s"point of ${hex(e)}"))
      assertEquals(decoded, M.decode(e, M.Reading.Lenient).isDefined, s"as the model, ${hex(e)}")
      assertEquals(V.canonicalY(padded, offset), M.y(e) < p, s"canonicalY ${hex(e)}")
    }
  }

  property("isSmallOrder is [8]P = O, as the vendored test, on points of every order") {
    forAllNoShrink(extendedPoint) { (m, e) =>
      val w = new VartimeEd25519()
      V.toProjective(w.projective, extended(e))
      assertEquals(w.isSmallOrder(), VendoredOps.isSmallOrder(e), s"order of ${hex(m.encode)}")
      assertEquals(VendoredOps.isSmallOrder(e), m.isSmallOrder, "the model")
    }
  }

  test("each point of small order is of small order, and the identity the identity") {
    M.torsion.foreach { t =>
      val w = new VartimeEd25519()
      assert(w.decode(w.point, t.encode, 0), s"${hex(t.encode)} decodes")
      V.toProjective(w.projective, w.point)
      assertEquals(w.isIdentity(w.projective), t.isIdentity, s"identity ${hex(t.encode)}")
      assert(w.isSmallOrder(), s"${hex(t.encode)} is of small order")
    }
    val w = new VartimeEd25519()
    assert(w.decode(w.point, (M.basepoint * 3).encode, 0))
    V.toProjective(w.projective, w.point)
    assert(!w.isIdentity(w.projective), "3B is not the identity")
    assert(!w.isSmallOrder(), "3B is not of small order")
  }

  private def hex(bytes: Array[Byte]): String = bytes.map(b => f"${b & 0xff}%02x").mkString

  property("[a]A + [b]B is the vendored product and the model's, A of every order") {
    val scalars =
      Gen.oneOf(canonicalScalar, Gen.oneOf(Vector[BigInt](0, 1, 2, L - 1, L - 2, (L - 1) / 2)))
    forAllNoShrink(modelPoint, scalars, scalars) { (m, a, b) =>
      val point = VendoredOps.decompress(m.encode).get
      val (aBytes, bBytes) = (le(a, 32), le(b, 32))
      val w = new VartimeEd25519()
      w.oddMultiples(extended(point))
      V.nonAdjacentForm(w.nafA, aBytes, 0)
      V.nonAdjacentForm(w.nafB, bBytes, 0)
      w.doubleScalarMultiplyBasepoint()
      w.encodeProjective()
      val clue = s"a=${a.toString(16)} b=${b.toString(16)} A=${hex(m.encode)}"
      assertEquals(
        hex(w.encoding),
        hex(VendoredOps.vartimeDoubleScalarMultiplyBasepoint(aBytes, point, bBytes)),
        s"as vendored, $clue"
      )
      assertEquals(hex(w.encoding), hex((m * a + M.basepoint * b).encode), s"the model, $clue")
    }
  }
