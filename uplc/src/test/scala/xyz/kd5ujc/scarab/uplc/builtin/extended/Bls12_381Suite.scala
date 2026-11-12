// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import io.circe.parser
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop, Test}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import supranational.blst.{blstJNI, P1, P2}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.bls.Blst
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni}
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.bls.{
  Bls12_381,
  BlsFailure,
  BlstError,
  G1Element,
  G2Element,
  Group,
  MlResult,
  ScalarOperation
}
import xyz.kd5ujc.scarab.uplc.builtin.EvaluationFailure
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreVariant, Probe}

import ExtendedTesting.*

/** The BLS12-381 builtins (`PlutusCore/Crypto/BLS12_381/G1.hs`, `G2.hs`, `Pairing.hs`, `Bounds.hs`
  * and `Default/Builtins.hs:1886-2058,2410-2425`, plutus 1.70.0.0): decompression and its refusals,
  * the group laws, the scalar bounds of variants D and E, the multi-scalar sums, hashing to the
  * groups against the points the reference's crypto module exported, and the pairing.
  */
final class Bls12_381Suite extends ScalaCheckSuite:
  override def scalaCheckTestParameters: Test.Parameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(60)

  private val g1Hex =
    "97f1d3a73197d7942695638c4fa9ac0fc3688c4f9774b905a14e3a3f171bac586c55e83ff97a1aeffb3af00adb22c6bb"
  private val g2Hex =
    "93e02b6052719f607dacd3a088274f65596bd0d09920b61ab5da61bbdc7f5049334cf11213945d57e5ac7d055d042b7e024aa2b2f08f0a91260805272dc51051c6e47ad4fa403b02b4510b647ae3d1770bac0326a805bbefd48056c8c121bdb8"

  private def g1(hex: String): G1Element =
    Bls12_381.G1.uncompress(hexBytes(hex)).fold(e => sys.error(e.toString), identity)
  private def g2(hex: String): G2Element =
    Bls12_381.G2.uncompress(hexBytes(hex)).fold(e => sys.error(e.toString), identity)
  private val G1 = g1(g1Hex)
  private val G2 = g2(g2Hex)
  private def p1(e: G1Element): Probe = con(Constant.Bls12_381_G1_Element(e))
  private def p2(e: G2Element): Probe = con(Constant.Bls12_381_G2_Element(e))
  private def g1Of(o: xyz.kd5ujc.scarab.uplc.builtin.Outcome[Probe]): G1Element = value(o) match
    case Constant.Bls12_381_G1_Element(e) => e
    case other                            => sys.error(s"$other")
  private def g2Of(o: xyz.kd5ujc.scarab.uplc.builtin.Outcome[Probe]): G2Element = value(o) match
    case Constant.Bls12_381_G2_Element(e) => e
    case other                            => sys.error(s"$other")
  private def mlOf(o: xyz.kd5ujc.scarab.uplc.builtin.Outcome[Probe]): MlResult = value(o) match
    case Constant.Bls12_381_MlResult(m) => m
    case other                          => sys.error(s"$other")
  private val p = BigInt(Bls12_381.FieldModulus)
  private val r = BigInt(Bls12_381.GroupOrder)
  private def be48(x: BigInt): String = f"$x%096x"

  test("the field modulus and group order are BLS12-381's") {
    assertEquals(
      p.toString(16),
      "1a0111ea397fe69a4b1ba7b6434bacd764774b84f38512bf6730d2a0f6b0f6241eabfffeb153ffffb9feffffffffaaab"
    )
    assertEquals(r.toString(16), "73eda753299d7d483339d80809a1d80553bda402fffe5bfeffffffff00000001")
  }

  private def uncompress1(hex: String) = e(Bls12_381_G1_uncompress, bytes(hex))
  private def refusal(group: Group, error: BlstError) =
    EvaluationFailure.Bls(BlsFailure.Uncompress(group, error)) -> Vector(error.show)

  test("G1 uncompress: length and flags, x < p (bad encoding; G1.hs:140-150)") {
    val bad = refusal(Group.G1, BlstError.BadEncoding)
    assertEquals(failureOf(uncompress1(g1Hex.drop(2))), bad)
    assertEquals(failureOf(uncompress1(g1Hex + "00")), bad)
    assertEquals(failureOf(uncompress1("")), bad)
    // the compression bit cleared
    assertEquals(failureOf(uncompress1("17" + g1Hex.drop(2))), bad)
    // infinity is c0 and zeros only
    assertEquals(g1Of(uncompress1("c0" + "00" * 47)), Bls12_381.G1.Zero)
    assertEquals(failureOf(uncompress1("e0" + "00" * 47)), bad)
    assertEquals(failureOf(uncompress1("c0" + "00" * 46 + "01")), bad)
    assertEquals(failureOf(uncompress1("40" + "00" * 47)), bad)
    // x = p, with the compression flag
    assertEquals(failureOf(uncompress1(be48(p + (BigInt(0x80) << 376)))), bad)
  }

  /** The least x > 0 for which `x^3 + 4` is, or is not, a square modulo p. */
  private def least(square: Boolean): BigInt =
    Iterator
      .from(1)
      .map(BigInt(_))
      .find(x => ((x.pow(3) + 4).modPow((p - 1) / 2, p) == 1) == square)
      .getOrElse(sys.error("none"))

  test("G1 uncompress: off the curve, x = 0, outside the subgroup") {
    val flag = BigInt(0x80) << 376
    assertEquals(
      failureOf(uncompress1(be48(least(square = false) + flag))),
      refusal(Group.G1, BlstError.PointNotOnCurve)
    )
    assertEquals(failureOf(uncompress1(be48(flag))), refusal(Group.G1, BlstError.PointNotInGroup))
    assertEquals(
      failureOf(uncompress1(be48(least(square = true) + flag))),
      refusal(Group.G1, BlstError.PointNotInGroup)
    )
    assertEquals(bytesOf(e(Bls12_381_G1_compress, p1(G1))).hex, g1Hex)
  }

  private def uncompress2(hex: String) = e(Bls12_381_G2_uncompress, bytes(hex))

  test("G2 uncompress: length, flags, both halves of x below p, on the curve (G2.hs:121-131)") {
    val bad = refusal(Group.G2, BlstError.BadEncoding)
    assertEquals(failureOf(uncompress2(g2Hex.drop(2))), bad)
    assertEquals(failureOf(uncompress2(g1Hex)), bad)
    assertEquals(g2Of(uncompress2("c0" + "00" * 95)), Bls12_381.G2.Zero)
    assertEquals(failureOf(uncompress2("c0" + "00" * 94 + "01")), bad)
    val flag = BigInt(0x80) << 376
    assertEquals(failureOf(uncompress2(be48(p + flag) + be48(0))), bad)
    assertEquals(failureOf(uncompress2(be48(flag) + be48(p))), bad)
    // x = 0: 4(1 + i) has norm 32, not a square as p = 3 (mod 8)
    assertEquals(
      failureOf(uncompress2(be48(flag) + be48(0))),
      refusal(Group.G2, BlstError.PointNotOnCurve)
    )
    assertEquals(bytesOf(e(Bls12_381_G2_compress, p2(G2))).hex, g2Hex)
  }

  test("the group laws through add, neg, scalarMul and equal (G1.hs:107-128, G2.hs)") {
    val twice = g1Of(e(Bls12_381_G1_add, p1(G1), p1(G1)))
    assertEquals(g1Of(e(Bls12_381_G1_scalarMul, int(2), p1(G1))), twice)
    assertEquals(
      g1Of(e(Bls12_381_G1_add, p1(G1), p1(g1Of(e(Bls12_381_G1_neg, p1(G1)))))),
      Bls12_381.G1.Zero
    )
    assertEquals(g1Of(e(Bls12_381_G1_scalarMul, int(r), p1(G1))), Bls12_381.G1.Zero)
    assertEquals(
      g1Of(e(Bls12_381_G1_scalarMul, int(-1), p1(G1))),
      g1Of(e(Bls12_381_G1_neg, p1(G1)))
    )
    assertEquals(
      g1Of(e(Bls12_381_G1_scalarMul, int(r + 5), p1(G1))),
      g1Of(e(Bls12_381_G1_scalarMul, int(5), p1(G1)))
    )
    assertEquals(g1Of(e(Bls12_381_G1_scalarMul, int(0), p1(twice))), Bls12_381.G1.Zero)
    assert(boolOf(e(Bls12_381_G1_equal, p1(twice), p1(twice))))
    assert(!boolOf(e(Bls12_381_G1_equal, p1(twice), p1(G1))))
    val twice2 = g2Of(e(Bls12_381_G2_add, p2(G2), p2(G2)))
    assertEquals(g2Of(e(Bls12_381_G2_scalarMul, int(2), p2(G2))), twice2)
    assertEquals(
      g2Of(e(Bls12_381_G2_add, p2(G2), p2(g2Of(e(Bls12_381_G2_neg, p2(G2)))))),
      Bls12_381.G2.Zero
    )
    assertEquals(
      g2Of(e(Bls12_381_G2_scalarMul, int(-r - 3), p2(G2))),
      g2Of(e(Bls12_381_G2_scalarMul, int(-3), p2(G2)))
    )
    assert(boolOf(e(Bls12_381_G2_equal, p2(twice2), p2(twice2))))
  }

  test(
    "scalarMul: D and E bound the scalar to [-2^4095, 2^4095 - 1]; A to C do not (Builtins.hs:1901-1917)"
  ) {
    val top = BigInt(1) << 4095
    for (f, probe, group) <- Vector(
        (Bls12_381_G1_scalarMul, p1(G1), Group.G1),
        (Bls12_381_G2_scalarMul, p2(G2), Group.G2)
      )
    do
      for v <- Vector(CoreVariant.D, CoreVariant.E); k <- Vector(top, -top - 1) do
        assertEquals(
          failureOf(ExtendedTesting(v, f, int(k), probe)),
          EvaluationFailure.Bls(BlsFailure.ScalarOutOfBounds(ScalarOperation.ScalarMul(group))) ->
            Vector(s"Scalar exceeds 512-byte bound for $group.scalarMul"),
          s"$v $f"
        )
      for k <- Vector(top - 1, -top) do assert(result(e(f, int(k), probe)).isRight, s"$f $k")
      for v <- Vector(CoreVariant.A, CoreVariant.B, CoreVariant.C) do
        assert(result(ExtendedTesting(v, f, int(top << 10), probe)).isRight, s"$v $f")
  }

  private def g1s(es: G1Element*): Probe =
    con(list(DefaultUni.Bls12_381_G1_Element, es.map(Constant.Bls12_381_G1_Element(_))*))

  test(
    "multiScalarMul: the sum over the pairs zip makes; every scalar is bounded (G1.hs:209-214)"
  ) {
    val two = g1Of(e(Bls12_381_G1_add, p1(G1), p1(G1)))
    assertEquals(g1Of(e(Bls12_381_G1_multiScalarMul, integers(), g1s())), Bls12_381.G1.Zero)
    assertEquals(
      g1Of(e(Bls12_381_G1_multiScalarMul, integers(3, 5, 7), g1s(G1, two))),
      g1Of(e(Bls12_381_G1_scalarMul, int(13), p1(G1)))
    )
    assertEquals(
      g1Of(e(Bls12_381_G1_multiScalarMul, integers(3), g1s(G1, two))),
      g1Of(e(Bls12_381_G1_scalarMul, int(3), p1(G1)))
    )
    assertEquals(
      g1Of(e(Bls12_381_G1_multiScalarMul, integers(-1, r), g1s(G1, two))),
      g1Of(e(Bls12_381_G1_neg, p1(G1)))
    )
    // a scalar out of bounds fails even beyond the points
    assertEquals(
      failureOf(e(Bls12_381_G1_multiScalarMul, integers(1, BigInt(1) << 4095), g1s(G1))),
      EvaluationFailure.Bls(
        BlsFailure.ScalarOutOfBounds(ScalarOperation.MultiScalarMul(Group.G1))
      ) ->
        Vector("Scalar exceeds 512-byte bound for G1.multiScalarMul")
    )
    val g2s = con(list(DefaultUni.Bls12_381_G2_Element, Constant.Bls12_381_G2_Element(G2)))
    assertEquals(
      g2Of(e(Bls12_381_G2_multiScalarMul, integers(4), g2s)),
      g2Of(e(Bls12_381_G2_scalarMul, int(4), p2(G2)))
    )
  }

  /** The points the reference's crypto module computed for these messages and tags
    * (`fixtures/reference/plutus-boundaries/hash-vectors.jsonl`): empty, ASCII, high-bit,
    * zero-containing and 255-byte tags.
    */
  private val vectors: Vector[(Bytes, Bytes, String, String)] =
    Files
      .readAllLines(
        Path.of("fixtures/reference/plutus-boundaries/hash-vectors.jsonl"),
        StandardCharsets.UTF_8
      )
      .asScala
      .toVector
      .filter(_.trim.nonEmpty)
      .map { line =>
        val c = parser.parse(line).fold(e => sys.error(e.toString), _.hcursor)
        def s(k: String) = c.get[String](k).fold(e => sys.error(e.toString), identity)
        (hexBytes(s("message")), hexBytes(s("dst")), s("g1"), s("g2"))
      }

  test("hashToGroup: the reference's points for every tag shape (G1.hs:175-181, G2.hs:135-139)") {
    assertEquals(vectors.map(_._2.size), Vector(0, 1, 1, 2, 255))
    for (message, dst, h1, h2) <- vectors do
      assertEquals(
        bytesOf(
          e(
            Bls12_381_G1_compress,
            p1(g1Of(e(Bls12_381_G1_hashToGroup, bytes(message), bytes(dst))))
          )
        ).hex,
        h1,
        dst.hex
      )
      assertEquals(
        bytesOf(
          e(
            Bls12_381_G2_compress,
            p2(g2Of(e(Bls12_381_G2_hashToGroup, bytes(message), bytes(dst))))
          )
        ).hex,
        h2,
        dst.hex
      )
  }

  test("hashToGroup: a tag of more than 255 bytes fails (G1.hs:179-180)") {
    for (f, group) <- Vector(
        (Bls12_381_G1_hashToGroup, Group.G1),
        (Bls12_381_G2_hashToGroup, Group.G2)
      )
    do
      assertEquals(
        failureOf(e(f, bytes(""), bytes("41" * 256))),
        EvaluationFailure.Bls(BlsFailure.DstTooBig(group, 256)) -> Vector("HashToCurveDstTooBig")
      )
  }

  /** Tags jblst can take: its `hash_to` passes the tag as a Java string through JNI's modified
    * UTF-8, which carries the bytes 1 to 127 unchanged.
    */
  private val asciiTag: Gen[Array[Byte]] =
    Gen.choose(1, 255).flatMap(n => Gen.listOfN(n, Gen.choose(1, 127)).map(_.map(_.toByte).toArray))
  private val message: Gen[Array[Byte]] =
    Gen.choose(0, 200).flatMap(n => Gen.listOfN(n, Gen.choose(0, 255)).map(_.map(_.toByte).toArray))

  property("hashToGroup through Bouncy Castle is jblst's hash_to on the tags both take") {
    Prop.forAll(message, asciiTag) { (m, dst) =>
      val tag = new String(dst, StandardCharsets.ISO_8859_1)
      val ours1 = Bls12_381.G1.hashToGroup(Bytes.wrap(m), Bytes.wrap(dst)).map(_.compressed.hex)
      assertEquals(ours1, Right(Bytes.wrap(new P1().hash_to(m, tag).compress()).hex))
      val ours2 = Bls12_381.G2.hashToGroup(Bytes.wrap(m), Bytes.wrap(dst)).map(_.compressed.hex)
      assertEquals(ours2, Right(Bytes.wrap(new P2().hash_to(m, tag).compress()).hex))
    }
  }

  test(
    "the pairing: bilinearity through millerLoop, mulMlResult and finalVerify (Pairing.hs:66-75)"
  ) {
    def ml(a: G1Element, b: G2Element) = mlOf(e(Bls12_381_millerLoop, p1(a), p2(b)))
    def times1(k: BigInt) = g1Of(e(Bls12_381_G1_scalarMul, int(k), p1(G1)))
    def times2(k: BigInt) = g2Of(e(Bls12_381_G2_scalarMul, int(k), p2(G2)))
    def verify(a: MlResult, b: MlResult) =
      boolOf(
        e(
          Bls12_381_finalVerify,
          con(Constant.Bls12_381_MlResult(a)),
          con(Constant.Bls12_381_MlResult(b))
        )
      )
    def mul(a: MlResult, b: MlResult) =
      mlOf(
        e(
          Bls12_381_mulMlResult,
          con(Constant.Bls12_381_MlResult(a)),
          con(Constant.Bls12_381_MlResult(b))
        )
      )
    assert(verify(ml(times1(2), G2), ml(G1, times2(2))))
    assert(!verify(ml(times1(2), G2), ml(G1, times2(3))))
    assert(verify(mul(ml(G1, G2), ml(G1, G2)), ml(times1(2), G2)))
    assert(verify(ml(Bls12_381.G1.Zero, Bls12_381.G2.Zero), ml(Bls12_381.G1.Zero, G2)))
    assertEquals(ml(G1, G2).raw.size, 576)
    assertEquals(Constant.Bls12_381_MlResult(ml(G1, G2)), Constant.Bls12_381_MlResult(ml(G1, G2)))
  }

  test("a Miller-loop result made from bytes alone is a defect of the caller, not a verdict") {
    val detached = con(Constant.Bls12_381_MlResult(MlResult.fromRaw(Bytes.empty)))
    assertEquals(
      failureOf(e(Bls12_381_finalVerify, detached, detached)),
      EvaluationFailure.Defect(
        Bls12_381_finalVerify,
        LibraryDefect.DetachedMlResult
      ) -> Vector.empty
    )
    assertEquals(
      failureOf(e(Bls12_381_mulMlResult, detached, detached))._1,
      EvaluationFailure.Defect(Bls12_381_mulMlResult, LibraryDefect.DetachedMlResult)
    )
  }

  test("jblst is the pinned build of blst v0.3.17 (ADR 0025)") {
    val name = Blst.resourceName(k => Option(java.lang.System.getProperty(k)))
    val sha = Using.resource(classOf[blstJNI].getResourceAsStream(name))(in =>
      Blst.sha256Hex(in.readAllBytes())
    )
    assertEquals(Blst.pinned.get(name), Some(sha))
  }

  // The reference links blst 0.3.15 (ADR 0020); jblst carries v0.3.17. ADR 0025 records the owner's
  // review of the commits between them (no change in decompression, subgroup checks, hashing to the
  // groups or the pairing). The reference's `blsMSM` sums by blst's Pippenger code, which
  // `multiScalarMul` here does not call (it adds the products), so a defect of that code at 0.3.15
  // would not be reproduced. Neither blst release is vendored, so this stays unverified.
  test(
    "multiScalarMul against blst 0.3.15's Pippenger sum: unverified, blst is not vendored".ignore
  ) {}
