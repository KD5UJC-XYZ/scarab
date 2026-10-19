// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import io.circe.parser.parse
import java.math.BigInteger
import java.security.MessageDigest
import munit.FunSuite
import org.bouncycastle.asn1.sec.SECNamedCurves
import scala.util.control.NonFatal

/** The Google Wycheproof ECDSA secp256k1/SHA-256 corpus through the reference semantics of
  * [[Secp256k1.verifyEcdsa]] (documented there): a 33-byte compressed key, the 32-byte message, a
  * compact 64-byte `r || s`; a malformed argument fails the builtin, anything well-formed is a
  * verdict, and a high `s` verifies to `False` (libsecp256k1 does not normalise, Plutus does not).
  * Wycheproof keys are uncompressed `04 || X || Y`, its messages are pre-images signed over
  * `SHA-256(msg)`, and its signatures are DER, so the suite converts each case to the builtin's
  * input domain: the key to its compressed form, the message to its digest, and the signature to
  * `r || s`. A malformed DER signature has no builtin input (the chain carries compact signatures
  * only); those cases pin the harness's own strictness instead.
  *
  * The corpus marks 72 signatures valid that the reference refuses: exactly the high-`s`
  * signatures, the documented divergence from a normalising ECDSA verifier. DER is read strictly
  * (sign bytes, minimal lengths, no redundant zeros), so encoding-only malformations reject like a
  * strict verifier would. The case counts are asserted so that a changed corpus fails loudly: 96
  * verdicts `True`, 102 `False`, 44 compact parse failures, 234 strict-DER rejections.
  *
  * Fixture provenance: `plutus/src/test/resources/wycheproof/README.md` (#211).
  */
class WycheproofSecp256k1Suite extends FunSuite:

  private val n = Secp256k1.Order
  private val curve = SECNamedCurves.getByName("secp256k1")
  private val sha256 = MessageDigest.getInstance("SHA-256")

  private def hex(text: String): Array[Byte] =
    val out = new Array[Byte](text.length / 2)
    text.grouped(2).zipWithIndex.foreach { case (pair, i) =>
      out(i) = Integer.parseInt(pair, 16).toByte
    }
    out

  private def be32(v: BigInt): Array[Byte] =
    val b = v.toByteArray.dropWhile(_ == 0)
    require(b.length <= 32, "compact component above 32 bytes")
    Array.fill(32 - b.length)(0.toByte) ++ b

  /** The uncompressed Wycheproof key as the builtin's 33-byte compressed point; `None` when the key
    * is not a 65-byte on-curve point (then the builtin would fail).
    */
  private def compressed(uncompressedHex: String): Option[Array[Byte]] =
    try
      if uncompressedHex.length != 130 || !uncompressedHex.startsWith("04") then None
      else
        val raw = hex(uncompressedHex)
        val point = curve.getCurve.createPoint(
          new BigInteger(1, raw.slice(1, 33)),
          new BigInteger(1, raw.slice(33, 65))
        )
        Option.when(point.isValid)(point.normalize().getEncoded(true))
    catch case NonFatal(_) => None

  /** A DER TLV at `at`: tag, value bytes, index after the value. */
  private final case class Tlv(tag: Int, value: Array[Byte], next: Int)

  private def tlv(bytes: Array[Byte], at: Int): Option[Tlv] =
    if at + 1 >= bytes.length || (bytes(at) & 0x1f) == 0x1f then None
    else
      val length0 = bytes(at + 1) & 0xff
      def of(length: Int, body: Int) =
        Option.when(body + length <= bytes.length)(
          Tlv(bytes(at) & 0xff, bytes.slice(body, body + length), body + length)
        )
      if length0 < 0x80 then of(length0, at + 2)
      else if length0 == 0x81 && at + 2 < bytes.length && (bytes(at + 2) & 0xff) >= 0x80 then
        of(bytes(at + 2) & 0xff, at + 3)
      else if length0 == 0x82 && at + 3 < bytes.length then
        val length = ((bytes(at + 2) & 0xff) << 8) | (bytes(at + 3) & 0xff)
        if length < 0x100 then None else of(length, at + 4)
      else None

  /** A strict DER INTEGER: non-empty, no missing sign byte, no redundant leading zero. */
  private def strictInt(value: Array[Byte]): Option[BigInt] =
    if value.isEmpty || (value(0) & 0x80) != 0 then None
    else if value.length > 1 && value(0) == 0 && (value(1) & 0x80) == 0 then None
    else Some(BigInt(1, value))

  /** The DER signature as `(r, s)`, under strict DER. The builtin's input is compact `r || s`,
    * which exists when `r, s < n`; `r = 0` and `s = 0` parse and verify to `False` (the case of
    * preprod 97b31e35), so they keep their compact form.
    */
  private def parseSig(derHex: String): Option[(BigInt, BigInt)] =
    val bytes = hex(derHex)
    for
      seq <- tlv(bytes, 0)
      if seq.tag == 0x30 && seq.next == bytes.length
      rTlv <- tlv(seq.value, 0)
      if rTlv.tag == 0x02
      sTlv <- tlv(seq.value, rTlv.next)
      if sTlv.tag == 0x02 && sTlv.next == seq.value.length
      r <- strictInt(rTlv.value)
      s <- strictInt(sTlv.value)
    yield (r, s)

  private enum Outcome:
    case Verdict(value: Boolean)
    case CompactFails
    case DerRejects

  private final case class Case(
      tcId: Int,
      comment: String,
      key: Array[Byte],
      message: Array[Byte],
      signature: Array[Byte],
      outcome: Outcome
  )

  private def need[A](result: Either[io.circe.Error, A], what: String): A =
    result.fold(e => fail(s"corpus field $what: ${e.getMessage}"), identity)

  private lazy val cases: Vector[Case] =
    val text =
      val in = getClass.getResourceAsStream("/wycheproof/ecdsa_secp256k1_sha256_test.json")
      scala.io.Source.fromInputStream(in).mkString
    val corpus = need(parse(text), "root")
    val groups = need(corpus.hcursor.downField("testGroups").as[Vector[Json]], "testGroups")
    groups.flatMap { group =>
      val uncompressed =
        need(
          group.hcursor.downField("publicKey").downField("uncompressed").as[String],
          "publicKey.uncompressed"
        )
      val key = compressed(uncompressed).getOrElse(
        fail(s"corpus key is not a 65-byte on-curve point: ${uncompressed.take(16)}...")
      )
      val tests = need(group.hcursor.downField("tests").as[Vector[Json]], "tests")
      tests.map { test =>
        val tcId = need(test.hcursor.downField("tcId").as[Int], "tcId")
        val comment = need(test.hcursor.downField("comment").as[String], "comment")
        val result = need(test.hcursor.downField("result").as[String], "result")
        val msg = need(test.hcursor.downField("msg").as[String], "msg")
        val der = need(test.hcursor.downField("sig").as[String], "sig")
        val message = sha256.digest(hex(msg))
        parseSig(der) match
          case None =>
            Case(tcId, comment, key, message, Array.emptyByteArray, Outcome.DerRejects)
          case Some((r, s)) if r.bitLength > 256 || s.bitLength > 256 =>
            // No compact form exists; the builtin never receives such a signature.
            Case(tcId, comment, key, message, Array.emptyByteArray, Outcome.DerRejects)
          case Some((r, s)) if r >= n || s >= n =>
            Case(tcId, comment, key, message, be32(r) ++ be32(s), Outcome.CompactFails)
          case Some((r, s)) =>
            val verdict = result == "valid" && r != 0 && s != 0 && s <= n / 2
            Case(tcId, comment, key, message, be32(r) ++ be32(s), Outcome.Verdict(verdict))
      }
    }

  test("the corpus carries the pinned case counts (96 / 102 / 44 / 234)") {
    val all = cases
    assertEquals(all.count(_.outcome == Outcome.Verdict(true)), 96, "verdict True")
    assertEquals(all.count(_.outcome == Outcome.Verdict(false)), 102, "verdict False")
    assertEquals(all.count(_.outcome == Outcome.CompactFails), 44, "compact parse failures")
    assertEquals(all.count(_.outcome == Outcome.DerRejects), 234, "strict-DER rejections")
    assertEquals(all.size, 476)
  }

  test("every case matches the reference semantics of verifyEcdsa") {
    cases.zipWithIndex.foreach { case (c, i) =>
      val clue = s"case $i tcId=${c.tcId} ${c.comment}"
      c.outcome match
        // The builtin never receives a DER signature; `cases` already proved the parser rejects it.
        case Outcome.DerRejects => ()
        case Outcome.CompactFails =>
          assert(Secp256k1.verifyEcdsa(c.key, c.message, c.signature).isLeft, clue)
        case Outcome.Verdict(value) =>
          assertEquals(Secp256k1.verifyEcdsa(c.key, c.message, c.signature), Right(value), clue)
    }
  }
