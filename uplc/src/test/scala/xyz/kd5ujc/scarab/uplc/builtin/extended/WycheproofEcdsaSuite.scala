// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import io.circe.Json
import io.circe.parser.parse
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.bouncycastle.asn1.sec.SECNamedCurves
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.DefaultFun.VerifyEcdsaSecp256k1Signature
import xyz.kd5ujc.scarab.uplc.builtin.EvaluationFailure

import ExtendedTesting.*

/** The Google Wycheproof ECDSA secp256k1/SHA-256 vectors (the `plutus` test resource
  * `wycheproof/ecdsa_secp256k1_sha256_test.json`, read here without depending on `plutus`) through
  * `verifyEcdsaSecp256k1Signature`, projected into the builtin's input domain as the `plutus` suite
  * `WycheproofSecp256k1Suite` projects them: the key compressed, the message hashed with SHA-256, a
  * strict-DER signature as compact `r || s`. Where a compact form exists the builtin answers the
  * vector's verdict, except that a high `s` is `False` (libsecp256k1 verifies normalised signatures
  * only) and `r = 0` or `s = 0` is `False`; `r` or `s` at least `n` fails the builtin. DER
  * signatures with no compact form are outside the builtin's domain.
  */
final class WycheproofEcdsaSuite extends munit.FunSuite:
  private val curve = SECNamedCurves.getByName("secp256k1")
  private val n = BigInt(curve.getN)

  private def hex(text: String): Array[Byte] = hexBytes(text).toArray

  private def compressed(uncompressedHex: String): Option[Bytes] =
    Option
      .when(uncompressedHex.length == 130 && uncompressedHex.startsWith("04")) {
        val raw = hex(uncompressedHex)
        curve.getCurve.createPoint(
          BigInt(1, raw.slice(1, 33)).bigInteger,
          BigInt(1, raw.slice(33, 65)).bigInteger
        )
      }
      .filter(_.isValid)
      .map(p => Bytes.wrap(p.normalize().getEncoded(true)))

  /** A DER element at `at` of `bytes`: its tag, its value and the index after it. Lengths are
    * minimal: short, or `0x81` for 128 to 255, or `0x82` for 256 and more.
    */
  private def tlv(bytes: Array[Byte], at: Int): Option[(Int, Array[Byte], Int)] =
    def of(length: Int, body: Int) =
      Option.when(body + length <= bytes.length)(
        (bytes(at) & 0xff, bytes.slice(body, body + length), body + length)
      )
    if at + 1 >= bytes.length || (bytes(at) & 0x1f) == 0x1f then None
    else
      val l0 = bytes(at + 1) & 0xff
      if l0 < 0x80 then of(l0, at + 2)
      else if l0 == 0x81 && at + 2 < bytes.length && (bytes(at + 2) & 0xff) >= 0x80 then
        of(bytes(at + 2) & 0xff, at + 3)
      else if l0 == 0x82 && at + 3 < bytes.length then
        val length = ((bytes(at + 2) & 0xff) << 8) | (bytes(at + 3) & 0xff)
        if length < 0x100 then None else of(length, at + 4)
      else None

  /** A DER INTEGER that is non-negative and has no redundant leading zero. */
  private def integer(value: Array[Byte]): Option[BigInt] =
    Option.when(
      value.nonEmpty && (value(0) & 0x80) == 0 &&
        !(value.length > 1 && value(0) == 0 && (value(1) & 0x80) == 0)
    )(BigInt(1, value))

  /** A strict DER `SEQUENCE { INTEGER r, INTEGER s }`. */
  private def der(bytes: Array[Byte]): Option[(BigInt, BigInt)] =
    for
      (seqTag, seq, end) <- tlv(bytes, 0)
      if seqTag == 0x30 && end == bytes.length
      (rt, rv, next) <- tlv(seq, 0)
      if rt == 0x02
      (st, sv, last) <- tlv(seq, next)
      if st == 0x02 && last == seq.length
      r <- integer(rv)
      s <- integer(sv)
    yield (r, s)

  private enum Expect:
    case Verdict(value: Boolean)
    case Fails
    case NoCompactForm

  private final case class Case(
      id: Int,
      key: Bytes,
      message: Bytes,
      signature: Bytes,
      expect: Expect
  )

  private def need[A](r: Either[io.circe.Error, A]): A = r.fold(e => fail(e.getMessage), identity)

  private lazy val cases: Vector[Case] =
    val raw = Using.resource(
      getClass.getResourceAsStream("/wycheproof/ecdsa_secp256k1_sha256_test.json")
    )(_.readAllBytes())
    assertEquals(
      Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(raw)).hex,
      "43db761c0a2eae71fb0755d355d5130e28ce64a5b07846cf27e7072082597a81"
    )
    val json = need(parse(new String(raw, StandardCharsets.UTF_8)))
    need(json.hcursor.downField("testGroups").as[Vector[Json]]).flatMap { group =>
      val key =
        compressed(need(group.hcursor.downField("publicKey").downField("uncompressed").as[String]))
          .getOrElse(fail("a key that is not an on-curve point"))
      need(group.hcursor.downField("tests").as[Vector[Json]]).map { t =>
        val c = t.hcursor
        val id = need(c.get[Int]("tcId"))
        val message =
          Bytes.wrap(MessageDigest.getInstance("SHA-256").digest(hex(need(c.get[String]("msg")))))
        val valid = need(c.get[String]("result")) == "valid"
        der(hex(need(c.get[String]("sig")))) match
          case Some((r, s)) if r.bitLength <= 256 && s.bitLength <= 256 =>
            val compact = hexBytes(f"$r%064x$s%064x")
            if r >= n || s >= n then Case(id, key, message, compact, Expect.Fails)
            else
              Case(
                id,
                key,
                message,
                compact,
                Expect.Verdict(valid && r != 0 && s != 0 && s <= n / 2)
              )
          case _ => Case(id, key, message, Bytes.empty, Expect.NoCompactForm)
      }
    }

  test("the vectors fall into the pinned classes (96 True, 102 False, 44 failures, 234 DER-only)") {
    assertEquals(cases.map(_.id).sorted, (1 to 476).toVector)
    assertEquals(cases.count(_.expect == Expect.Verdict(true)), 96)
    assertEquals(cases.count(_.expect == Expect.Verdict(false)), 102)
    assertEquals(cases.count(_.expect == Expect.Fails), 44)
    assertEquals(cases.count(_.expect == Expect.NoCompactForm), 234)
  }

  test("every vector with a compact form gets the builtin's answer") {
    for c <- cases do
      val outcome =
        e(VerifyEcdsaSecp256k1Signature, bytes(c.key), bytes(c.message), bytes(c.signature))
      c.expect match
        case Expect.Verdict(v) => assertEquals(boolOf(outcome), v, s"tcId ${c.id}")
        case Expect.Fails =>
          assertEquals(
            failureOf(outcome)._1,
            EvaluationFailure.SignatureInput(
              SignatureInputFailure.InvalidSignature(SignatureScheme.EcdsaSecp256k1)
            ),
            s"tcId ${c.id}"
          )
        case Expect.NoCompactForm => ()
  }
