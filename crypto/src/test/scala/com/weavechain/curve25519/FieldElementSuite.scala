// SPDX-License-Identifier: Apache-2.0
package com.weavechain.curve25519

import org.scalacheck.Gen
import org.scalacheck.Prop.forAllNoShrink

import Curve25519Gen.*

/** The vendored `FieldElement` against exact arithmetic modulo p = 2^255 - 19 on `BigInt`.
  *
  * Each operation is checked on the inputs its documentation admits, the limbs within its
  * precondition (`reducedBound`, `looseBound`, `documentedEncodableBound`), to the edges of that
  * range (every sign pattern with each limb at its bound), not only on canonical values, and its
  * result is checked against its postcondition (`sumBound`, `productBound`): a later rewrite that
  * keeps the values but lets a limb grow past its bound would overflow only in a later operation.
  * The limb-level properties read the private limbs by reflection and are tied to the radix-2^25.5
  * representation; the value-level properties are not.
  */
class FieldElementSuite extends munit.ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(500)

  private val limbField =
    val field = classOf[FieldElement].getDeclaredField("t")
    field.setAccessible(true)
    field

  private def limbsOf(fe: FieldElement): Array[Int] = limbField.get(fe).asInstanceOf[Array[Int]]

  private def value(fe: FieldElement): BigInt = fromLe(fe.toByteArray)

  private def element(v: BigInt): FieldElement = FieldElement.fromByteArray(le(v, 32))

  private def withinBounds(fe: FieldElement, bounds: Int => Int, what: String): Unit =
    val t = limbsOf(fe)
    t.indices.foreach { i =>
      assert(
        math.abs(t(i).toLong) <= bounds(i),
        s"$what: limb $i is ${t(i)}, bound ${bounds(i)} (limbs ${t.mkString(",")})"
      )
    }

  private val reduced: Gen[FieldElement] = limbs(reducedBound).map(new FieldElement(_))
  private val loose: Gen[FieldElement] = limbs(looseBound).map(new FieldElement(_))

  /** Elements in the forms the arithmetic makes: decoded from bytes, and products. */
  private val made: Gen[FieldElement] =
    Gen.frequency(2 -> fieldInput.map(element), 1 -> Gen.zip(loose, loose).map(_.multiply(_)))

  property("fromByteArray reads the low 255 bits modulo p within the reduced bounds") {
    forAllNoShrink(fieldInput) { v =>
      val fe = element(v)
      val expected = (v & ((BigInt(1) << 255) - 1)).mod(p)
      assertEquals(
        limbValue(limbsOf(fe)).mod(p),
        expected,
        s"value of the limbs of ${v.toString(16)}"
      )
      withinBounds(fe, reducedBound, s"fromByteArray(${v.toString(16)})")
    }
  }

  /** What `toByteArray`, `isZero`, `isNegative` and `ctEquals` must give for limbs `t`. */
  private def encodes(t: Array[Int], what: String): Unit =
    val fe = new FieldElement(t)
    val v = limbValue(t).mod(p)
    val bytes = fe.toByteArray
    val clue = s"$what: limbs ${t.mkString(",")}"
    assertEquals(fromLe(bytes), v, s"toByteArray, $clue")
    assertEquals(bytes(31) & 0x80, 0, s"bit 255 of a canonical encoding, $clue")
    assertEquals(fe.isZero(), if v == 0 then 1 else 0, s"isZero, $clue")
    assertEquals(fe.isNegative(), if v.testBit(0) then 1 else 0, s"isNegative, $clue")
    assertEquals(fe.ctEquals(element(v)), 1, s"ctEquals the canonical element, $clue")

  property(
    "toByteArray, isZero, isNegative and ctEquals reduce any limbs below 2^27, as documented"
  ) {
    forAllNoShrink(limbs(documentedEncodableBound))(encodes(_, "documented range"))
  }

  test("toByteArray reduces every sign pattern of limbs at +-(2^27 - 1), and of the sum bounds") {
    signPatterns(documentedEncodableBound).foreach(encodes(_, "limbs at 2^27 - 1"))
    signPatterns(sumBound).foreach(encodes(_, "limbs at the sum bounds"))
  }

  test("toByteArray reduces k p + delta, |k| up to 4, written with limbs as large as they go") {
    val patterns = Vector(-2, -1, 1, 2).flatMap(c =>
      Vector(Vector.fill(9)(c), Vector.tabulate(9)(i => if i % 2 == 0 then c else -c))
    )
    for
      k <- -4 to 4
      delta <- -2 to 2
      moves <- patterns
      t <- limbsFor(k * p + delta, documentedEncodableBound, moves)
    do encodes(t, s"$k p + $delta")
  }

  // The edge of toByteArray's documented range at the top limb: 19 * h9 there needs more than 32
  // bits (it does from h9 = 112142444 on). These limbs, 2^26 - 76 and then each limb at its maximum
  // digit with 2^27 - 1 in limb 9, are 2^257 - 76, a multiple of p, whose encoding is zero; reduced
  // with a quotient one too small they would be written as the non-canonical encoding of p.
  test("toByteArray reduces limbs below 2^27, as documented: 2^257 - 76 is zero modulo p") {
    val t =
      Array((1 << 26) - 76) ++ (1 until 9).map(i => (1 << (if i % 2 == 0 then 26 else 25)) - 1) ++
        Array((1 << 27) - 1)
    assert(
      t.indices.forall(i => math.abs(t(i)) <= documentedEncodableBound(i)),
      "within the documented bound"
    )
    assertEquals(limbValue(t), (BigInt(1) << 257) - 76, "the value of the limbs")
    assertEquals(limbValue(t).mod(p), BigInt(0), "a multiple of p")
    assertEquals(fromLe(new FieldElement(t).toByteArray), BigInt(0), "toByteArray writes zero")
  }

  property("a canonical encoding is read and written back unchanged") {
    forAllNoShrink(weighted(255, fieldEdges).map(_.mod(p))) { v =>
      assertEquals(fromLe(element(v).toByteArray), v)
    }
  }

  property("add and subtract are the sum and the difference modulo p, within the sum bounds") {
    forAllNoShrink(reduced, reduced) { (a, b) =>
      val (x, y) = (limbValue(limbsOf(a)), limbValue(limbsOf(b)))
      assertEquals(value(a.add(b)), (x + y).mod(p), "add")
      assertEquals(value(a.subtract(b)), (x - y).mod(p), "subtract")
      withinBounds(a.add(b), sumBound, "add")
      withinBounds(a.subtract(b), sumBound, "subtract")
    }
  }

  property("negate is the additive inverse, within the reduced bounds") {
    forAllNoShrink(reduced) { a =>
      assertEquals(value(a.negate()), (-limbValue(limbsOf(a))).mod(p))
      withinBounds(a.negate(), reducedBound, "negate")
    }
  }

  property("multiply, square and squareAndDouble are exact modulo p, within the product bounds") {
    forAllNoShrink(loose, loose) { (a, b) =>
      val (x, y) = (limbValue(limbsOf(a)), limbValue(limbsOf(b)))
      assertEquals(value(a.multiply(b)), (x * y).mod(p), "multiply")
      assertEquals(value(a.square()), (x * x).mod(p), "square")
      assertEquals(value(a.squareAndDouble()), (2 * x * x).mod(p), "squareAndDouble")
      withinBounds(a.multiply(b), productBound, "multiply")
      withinBounds(a.square(), productBound, "square")
      withinBounds(a.squareAndDouble(), productBound, "squareAndDouble")
    }
  }

  test("add, subtract and negate at every sign pattern of their bounds") {
    val patterns = signPatterns(reducedBound)
    val others = Vector(patterns.head, patterns.last)
    for
      s <- patterns
      t <- others :+ s :+ s.map(-_)
    do
      val (a, b) = (new FieldElement(s), new FieldElement(t))
      val (x, y) = (limbValue(s), limbValue(t))
      val clue = s"limbs ${s.mkString(",")} and ${t.mkString(",")}"
      assertEquals(value(a.add(b)), (x + y).mod(p), s"add, $clue")
      assertEquals(value(a.subtract(b)), (x - y).mod(p), s"subtract, $clue")
      assertEquals(value(a.negate()), (-x).mod(p), s"negate, $clue")
      withinBounds(a.add(b), sumBound, s"add, $clue")
      withinBounds(a.subtract(b), sumBound, s"subtract, $clue")
      withinBounds(a.negate(), reducedBound, s"negate, $clue")
  }

  test("multiply, square and squareAndDouble at every sign pattern of their bounds") {
    val patterns = signPatterns(looseBound)
    val others = Vector(patterns.head, patterns.last)
    for
      s <- patterns
      t <- others :+ s :+ s.map(-_)
    do
      val (a, b) = (new FieldElement(s), new FieldElement(t))
      val (x, y) = (limbValue(s), limbValue(t))
      val clue = s"limbs ${s.mkString(",")} and ${t.mkString(",")}"
      assertEquals(value(a.multiply(b)), (x * y).mod(p), s"multiply, $clue")
      assertEquals(value(a.square()), (x * x).mod(p), s"square, $clue")
      assertEquals(value(a.squareAndDouble()), (2 * x * x).mod(p), s"squareAndDouble, $clue")
      withinBounds(a.multiply(b), productBound, s"multiply, $clue")
      withinBounds(a.square(), productBound, s"square, $clue")
      withinBounds(a.squareAndDouble(), productBound, s"squareAndDouble, $clue")
  }

  property("multiply is exact when the product is at the edges: 0, 1, p - 1 and their neighbours") {
    // y = e / x, both written in large limbs of their value or of their value plus a multiple of p
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
    yield (new FieldElement(a.get), new FieldElement(b.get), e)
    forAllNoShrink(operands) { (a, b, e) =>
      assertEquals(value(a.multiply(b)), e, s"x * (e / x) for e = ${e.toString(16)}")
      withinBounds(a.multiply(b), productBound, "multiply")
    }
  }

  property("invert is the power p - 2 and powP58 the power (p - 5)/8") {
    // their first operations are square and multiply, so they take what those take
    forAllNoShrink(Gen.oneOf(made, loose)) { a =>
      val x = value(a)
      assertEquals(value(a.invert()), x.modPow(p - 2, p), "invert")
      assertEquals(value(a.powP58()), x.modPow((p - 5) / 8, p), "powP58")
      if x != 0 then assertEquals(value(a.multiply(a.invert())), BigInt(1), "a * a^-1")
      withinBounds(a.invert(), productBound, "invert")
    }
  }

  property(
    "sqrtRatioM1 gives the non-negative root of u/v, or of i*u/v, and whether u/v is square"
  ) {
    // decompress passes u = y^2 - 1 and v = d y^2 + 1, sums; the arithmetic takes them as products
    val sums = limbs(sumBound).map(new FieldElement(_))
    val operands = Gen.frequency(
      4 -> Gen.zip(made, made),
      2 -> Gen.zip(sums, sums),
      1 -> made.map(u => (u, FieldElement.ZERO)),
      1 -> made.map(v => (FieldElement.ZERO, v)),
      1 -> made.map(u => (u.square(), FieldElement.ONE)),
      1 -> Gen.zip(made, made).map((r, v) => (r.square().multiply(v), v))
    )
    forAllNoShrink(operands) { (u, v) =>
      val (x, y) = (value(u), value(v))
      val (square, root) = sqrtRatio(x, y)
      val result = FieldElement.sqrtRatioM1(u, v)
      assertEquals(
        result.wasSquare,
        square,
        s"wasSquare for u=${x.toString(16)} v=${y.toString(16)}"
      )
      assertEquals(value(result.result), root, s"root for u=${x.toString(16)} v=${y.toString(16)}")
    }
  }

  property("isNegative, isZero, ctEquals, ctSelect and ctAbs read the canonical value") {
    forAllNoShrink(limbs(sumBound), limbs(sumBound), reduced) { (s, t, r) =>
      val (a, b) = (new FieldElement(s), new FieldElement(t))
      val (x, y) = (limbValue(s).mod(p), limbValue(t).mod(p))
      assertEquals(a.isNegative(), if x.testBit(0) then 1 else 0, "isNegative")
      assertEquals(a.isZero(), if x == 0 then 1 else 0, "isZero")
      assertEquals(a.ctEquals(b), if x == y then 1 else 0, "ctEquals")
      assertEquals(a.ctEquals(new FieldElement(s.clone())), 1, "ctEquals of equal limbs")
      assertEquals(a.equals(b), x == y, "equals")
      assertEquals(value(a.ctSelect(b, 0)), x, "ctSelect 0")
      assertEquals(value(a.ctSelect(b, 1)), y, "ctSelect 1")
      val z = limbValue(limbsOf(r)).mod(p)
      assertEquals(value(r.ctAbs()), if z.testBit(0) then p - z else z, "ctAbs")
    }
  }

  property("elements equal modulo p but with other limbs are equal") {
    forAllNoShrink(reduced) { a =>
      val shifted = limbsOf(a).clone()
      // add p = 2^255 - 19 to the value: 2^255 is 2^25 in limb 9 (offset 230), less 19 in limb 0
      shifted(9) = shifted(9) + (1 << 25)
      shifted(0) = shifted(0) - 19
      val b = new FieldElement(shifted)
      assertEquals(a.ctEquals(b), 1)
      assertEquals(a.hashCode(), b.hashCode(), "hashCode of equal elements")
    }
  }

  /** An expression over field elements whose every operation takes operands within its
    * precondition: what the point formulas do, chaining sums into products without reducing.
    */
  private enum Expr:
    case Leaf(v: BigInt)
    case Constant(name: String, fe: FieldElement)
    case Add(a: Expr, b: Expr)
    case Sub(a: Expr, b: Expr)
    case Neg(a: Expr)
    case Mul(a: Expr, b: Expr)
    case Sq(a: Expr)
    case Sq2(a: Expr)
    case Inv(a: Expr)

  import Expr.*

  private def evaluate(e: Expr): (FieldElement, BigInt) = e match
    case Leaf(v)        => (element(v), (v & ((BigInt(1) << 255) - 1)).mod(p))
    case Constant(_, c) => (c, value(c))
    case Add(a, b)      => both(a, b)((x, y) => x.add(y), _ + _)
    case Sub(a, b)      => both(a, b)((x, y) => x.subtract(y), _ - _)
    case Mul(a, b)      => both(a, b)((x, y) => x.multiply(y), _ * _)
    case Neg(a)         => one(a)(_.negate(), -_)
    case Sq(a)          => one(a)(_.square(), x => x * x)
    case Sq2(a)         => one(a)(_.squareAndDouble(), x => 2 * x * x)
    case Inv(a)         => one(a)(_.invert(), _.modPow(p - 2, p))

  private def one(a: Expr)(f: FieldElement => FieldElement, g: BigInt => BigInt) =
    val (x, v) = evaluate(a)
    (f(x), g(v).mod(p))

  private def both(a: Expr, b: Expr)(
      f: (FieldElement, FieldElement) => FieldElement,
      g: (BigInt, BigInt) => BigInt
  ) =
    val (x, v) = evaluate(a)
    val (y, w) = evaluate(b)
    (f(x, y), g(v, w).mod(p))

  private val constants = Vector(
    Constant("ZERO", FieldElement.ZERO),
    Constant("ONE", FieldElement.ONE),
    Constant("EDWARDS_D", Constants.EDWARDS_D),
    Constant("EDWARDS_2D", Constants.EDWARDS_2D),
    Constant("SQRT_M1", Constants.SQRT_M1)
  )

  /** Results within the reduced bounds: leaves, constants, negations of those, and products. */
  private def reducedExpr(depth: Int): Gen[Expr] =
    val leaves = Gen.frequency(3 -> fieldInput.map(Leaf(_)), 1 -> Gen.oneOf(constants))
    if depth == 0 then leaves
    else
      Gen.frequency(
        2 -> leaves,
        1 -> Gen.lzy(reducedExpr(depth - 1)).map(Neg(_)),
        2 -> Gen.zip(anyExpr(depth - 1), anyExpr(depth - 1)).map(Mul(_, _)),
        1 -> anyExpr(depth - 1).map(Sq(_)),
        1 -> anyExpr(depth - 1).map(Sq2(_)),
        1 -> Gen.lzy(reducedExpr(depth - 1)).map(Inv(_))
      )

  /** Results within the sum bounds: sums and differences of reduced results. */
  private def anyExpr(depth: Int): Gen[Expr] =
    Gen.frequency(
      2 -> Gen.lzy(reducedExpr(depth)),
      1 -> Gen.zip(reducedExpr(depth), reducedExpr(depth)).map(Add(_, _)),
      1 -> Gen.zip(reducedExpr(depth), reducedExpr(depth)).map(Sub(_, _))
    )

  property("chains of operations keep their value modulo p") {
    forAllNoShrink(Gen.choose(1, 4).flatMap(reducedExpr)) { e =>
      val (fe, expected) = evaluate(e)
      assertEquals(value(fe), expected, e.toString)
    }
  }

  test("the field constants are those of edwards25519") {
    assertEquals(value(Constants.EDWARDS_D), Curve25519Gen.d, "d")
    assertEquals(value(Constants.EDWARDS_2D), (2 * Curve25519Gen.d).mod(p), "2d")
    assertEquals(value(Constants.SQRT_M1), Curve25519Gen.sqrtM1, "sqrt(-1)")
    assertEquals((Curve25519Gen.sqrtM1 * Curve25519Gen.sqrtM1).mod(p), p - 1, "sqrt(-1)^2")
  }
