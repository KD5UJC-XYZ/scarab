// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.bls

import java.math.BigInteger
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop, Test}
import org.bouncycastle.crypto.bls.{BLS12_381G2, BLS12_381G2HashToCurve, BLS12_381Serialization}
import supranational.blst.{P1, P1_Affine, P2, P2_Affine, PT}
import xyz.kd5ujc.scarab.codec.Bytes

/** An element keeps its point between operations. Chains of the group operations give, step by
  * step, the encodings, equalities and Miller loops of the path they replaced, which decompressed
  * every operand and compressed every result; and no operation changes an operand's point.
  */
class PointsSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  private def right[A](e: Either[?, A]): A = e.fold(err => fail(err.toString), identity)

  private val g1 = right(Bls12_381.G1.uncompress(Bytes.wrap(P1.generator().compress())))
  private val g2 = right(Bls12_381.G2.uncompress(Bytes.wrap(P2.generator().compress())))

  /** The operations of a chain, on a register of elements: an index counts back from the newest
    * element, modulo the register's size, so that results are operands of later steps.
    */
  private enum Step:
    case Add(i: Int, j: Int)
    case Neg(i: Int)
    case Mul(k: BigInt, i: Int)
    case Msm(ks: List[BigInt], is: List[Int])
    case Hash(message: Int)
    case Uncompress(i: Int)

    /** Asks for an element's encoding, so that both elements with and without one are operands. */
    case Encode(i: Int)

  private val steps: Gen[List[Step]] =
    val index = Gen.frequency(3 -> Gen.choose(0, 2), 1 -> Gen.choose(0, 1000))
    val scalar = Gen.oneOf(
      Gen.choose(-5L, 5L).map(BigInt(_)),
      Gen.choose(1, 300).map(bits => (BigInt(1) << bits) - 1),
      Gen.const(BigInt(Bls12_381.GroupOrder))
    )
    val step = Gen.oneOf(
      Gen.zip(index, index).map(Step.Add.apply),
      index.map(Step.Neg.apply),
      Gen.zip(scalar, index).map(Step.Mul.apply),
      Gen
        .choose(0, 3)
        .flatMap(n => Gen.zip(Gen.listOfN(n, scalar), Gen.listOfN(n, index)))
        .map(Step.Msm.apply),
      Gen.choose(0, 255).map(Step.Hash.apply),
      index.map(Step.Uncompress.apply),
      index.map(Step.Encode.apply)
    )
    Gen.choose(1, 16).flatMap(Gen.listOfN(_, step))

  /** The path before: every operand decompressed from its encoding, every result compressed. */
  private object Previous:
    def reduced(k: BigInt): BigInteger = k.bigInteger.mod(Bls12_381.GroupOrder)
    def add1(a: Bytes, b: Bytes): Bytes =
      Bytes.wrap(new P1(a.toArray).add(new P1(b.toArray)).compress())
    def neg1(a: Bytes): Bytes = Bytes.wrap(new P1(a.toArray).neg().compress())
    def mul1(k: BigInt, a: Bytes): Bytes =
      val s = reduced(k)
      if s.signum == 0 then Bls12_381.G1.Zero.compressed
      else Bytes.wrap(new P1(a.toArray).mult(s).compress())
    def msm1(ks: List[BigInt], as: List[Bytes]): Bytes =
      Bytes.wrap(
        ks.zip(as)
          .foldLeft(new P1()) { case (sum, (k, a)) =>
            val s = reduced(k)
            if s.signum == 0 then sum else sum.add(new P1(a.toArray).mult(s))
          }
          .compress()
      )
    def add2(a: Bytes, b: Bytes): Bytes =
      Bytes.wrap(new P2(a.toArray).add(new P2(b.toArray)).compress())
    def neg2(a: Bytes): Bytes = Bytes.wrap(new P2(a.toArray).neg().compress())
    def mul2(k: BigInt, a: Bytes): Bytes =
      val s = reduced(k)
      if s.signum == 0 then Bls12_381.G2.Zero.compressed
      else Bytes.wrap(new P2(a.toArray).mult(s).compress())
    def msm2(ks: List[BigInt], as: List[Bytes]): Bytes =
      Bytes.wrap(
        ks.zip(as)
          .foldLeft(new P2()) { case (sum, (k, a)) =>
            val s = reduced(k)
            if s.signum == 0 then sum else sum.add(new P2(a.toArray).mult(s))
          }
          .compress()
      )
    def miller(a: Bytes, b: Bytes): Bytes =
      Bytes.wrap(new PT(new P1_Affine(a.toArray), new P2_Affine(b.toArray)).to_bendian())

  private def message(i: Int): Bytes = Bytes.wrap(Array(i.toByte))
  private val Tag = Bytes.wrap("BLS_TEST_DST".getBytes("US-ASCII"))

  property(
    "chains of G1 and G2 operations give the previous path's results, and change no operand"
  ) {
    Prop.forAll(steps) { chain =>
      val start1 = Vector.tabulate(8)(i => right(Bls12_381.G1.scalarMul(BigInt(i + 1), g1, false)))
      val start2 = Vector.tabulate(8)(i => right(Bls12_381.G2.scalarMul(BigInt(i + 1), g2, false)))
      // the encodings the previous path gives each element, computed apart from the elements
      val model1 = start1.map(e => Bytes.wrap(e.point.compress()))
      val model2 = start2.map(e => Bytes.wrap(e.point.compress()))
      val (end1, end2, final1, final2) = chain.foldLeft((start1, start2, model1, model2)) {
        case ((r1, r2, m1, m2), step) =>
          def back(i: Int, size: Int) = size - 1 - i % size
          def at1(i: Int) = r1(back(i, r1.size))
          def at2(i: Int) = r2(back(i, r2.size))
          def was1(i: Int) = m1(back(i, m1.size))
          def was2(i: Int) = m2(back(i, m2.size))
          val (n1, n2, expected1, expected2) = step match
            case Step.Add(i, j) =>
              (
                right(Bls12_381.G1.add(at1(i), at1(j))),
                right(Bls12_381.G2.add(at2(i), at2(j))),
                Previous.add1(was1(i), was1(j)),
                Previous.add2(was2(i), was2(j))
              )
            case Step.Neg(i) =>
              (
                right(Bls12_381.G1.neg(at1(i))),
                right(Bls12_381.G2.neg(at2(i))),
                Previous.neg1(was1(i)),
                Previous.neg2(was2(i))
              )
            case Step.Mul(k, i) =>
              (
                right(Bls12_381.G1.scalarMul(k, at1(i), true)),
                right(Bls12_381.G2.scalarMul(k, at2(i), true)),
                Previous.mul1(k, was1(i)),
                Previous.mul2(k, was2(i))
              )
            case Step.Msm(ks, is) =>
              (
                right(Bls12_381.G1.multiScalarMul(ks.toVector, is.toVector.map(at1))),
                right(Bls12_381.G2.multiScalarMul(ks.toVector, is.toVector.map(at2))),
                Previous.msm1(ks, is.map(was1)),
                Previous.msm2(ks, is.map(was2))
              )
            case Step.Hash(m) =>
              val h1 = right(Bls12_381.G1.hashToGroup(message(m), Tag))
              val h2 = right(Bls12_381.G2.hashToGroup(message(m), Tag))
              (h1, h2, Bytes.wrap(h1.point.compress()), Bytes.wrap(h2.point.compress()))
            case Step.Uncompress(i) =>
              (
                right(Bls12_381.G1.uncompress(at1(i).compressed)),
                right(Bls12_381.G2.uncompress(at2(i).compressed)),
                was1(i),
                was2(i)
              )
            case Step.Encode(i) =>
              val (e1, e2) = (at1(i), at2(i))
              assertEquals(e1.compressed, was1(i), s"G1 $step")
              assertEquals(e2.compressed, was2(i), s"G2 $step")
              (e1, e2, was1(i), was2(i))
          (r1 :+ n1, r2 :+ n2, m1 :+ expected1, m2 :+ expected2)
      }
      // every element, operands included, still holds the point the previous path computed: its
      // point compressed afresh, not only the encoding it keeps
      end1.zip(final1).zipWithIndex.foreach { case ((e, b), i) =>
        assertEquals(Bytes.wrap(e.point.compress()), b, s"G1 element $i of $chain")
        assertEquals(e.compressed, b, s"G1 element $i's encoding")
      }
      end2.zip(final2).zipWithIndex.foreach { case ((e, b), i) =>
        assertEquals(Bytes.wrap(e.point.compress()), b, s"G2 element $i of $chain")
        assertEquals(e.compressed, b, s"G2 element $i's encoding")
      }
      // equality of the points is equality of the encodings, and the Miller loop is the previous one
      val pairs = end1.indices.zip(end1.indices.reverse).take(6)
      pairs.foreach { (i, j) =>
        assertEquals(right(Bls12_381.G1.equal(end1(i), end1(j))), final1(i) == final1(j))
        assertEquals(right(Bls12_381.G2.equal(end2(i), end2(j))), final2(i) == final2(j))
        assertEquals(
          right(Bls12_381.Pairing.millerLoop(end1(i), end2(j))).raw,
          Previous.miller(final1(i), final2(j))
        )
      }
    }
  }

  test("an element made from an encoding and one made from its point are equal and hash alike") {
    val point = right(Bls12_381.G1.scalarMul(BigInt(12345), g1, false))
    val encoded = G1Element.fromCompressed(point.compressed)
    assertEquals(encoded, point)
    assertEquals(encoded.hashCode, point.hashCode)
    assertEquals(right(Bls12_381.G1.equal(encoded, point)), true)
    assertEquals(right(Bls12_381.G1.add(encoded, encoded)), right(Bls12_381.G1.add(point, point)))
  }

  private val anyBytes: Gen[Array[Byte]] =
    Gen
      .frequency(3 -> Gen.choose(0, 40), 1 -> Gen.choose(41, 255))
      .flatMap(n => Gen.listOfN(n, Gen.choose(-128, 127).map(_.toByte)).map(_.toArray))

  property("G2 hashToGroup is Bouncy Castle's whole hash to the curve, for any message and tag") {
    Prop.forAll(anyBytes, anyBytes) { (message, dst) =>
      val previous = BLS12_381Serialization.compressG2(
        new BLS12_381G2HashToCurve(dst).hashToCurve(message)
      )
      assertEquals(
        Bls12_381.G2.hashToGroup(Bytes.wrap(message), Bytes.wrap(dst)).map(_.compressed),
        Right(Bytes.wrap(previous))
      )
    }
  }

  property("clearing the cofactor by psi is multiplying by h_eff; the Jacobian sums are affine") {
    Prop.forAll(anyBytes) { message =>
      val suite = new BLS12_381G2HashToCurve(Array[Byte](1, 2, 3))
      val u = suite.hashToField(message)
      val q = suite.mapToCurveAndIso3(u(0))
      val cleared = G2Cofactor.affine(G2Cofactor.clear(G2Cofactor.of(q)))
      assertEquals(cleared, q.multiply(BLS12_381G2.H_EFF))
      // the Jacobian sum, double and sum with the inverse are Bouncy Castle's affine ones
      val r = suite.mapToCurveAndIso3(u(1))
      assertEquals(G2Cofactor.affine(G2Cofactor.add(G2Cofactor.of(q), G2Cofactor.of(r))), q.add(r))
      assertEquals(G2Cofactor.affine(G2Cofactor.double(G2Cofactor.of(q))), q.doublePoint())
      assertEquals(
        G2Cofactor.affine(G2Cofactor.add(G2Cofactor.of(q), G2Cofactor.of(q.negate()))),
        q.add(q.negate())
      )
    }
  }
