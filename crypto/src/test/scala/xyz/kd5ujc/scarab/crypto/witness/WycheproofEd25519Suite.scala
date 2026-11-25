// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import io.circe.Decoder
import io.circe.parser.decode
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.security.MessageDigest

import Ed25519Verifiers.*
import VerificationResult.*

/** Every case of the Wycheproof Ed25519 vectors (`fixtures/wycheproof-ed25519`, C2SP/wycheproof
  * `a3d5d142`), run whole through Scarab, the JDK and Bouncy Castle, against the verdicts of the
  * reference's libsodium on the same cases (`sodium-results.tsv`; the fixture's README says how
  * they were produced).
  *
  * Wycheproof leaves 29 cases `acceptable`; the reference's verdicts decide them: it rejects the 8
  * keys of small order and accepts the 21 keys of mixed order. Scarab and Bouncy Castle give the
  * reference's verdict on all 210 cases. The JDK does too, except on the 8 keys of small order,
  * whose signatures satisfy the equation and which it accepts (it has no small-order test,
  * `Ed25519Verifiers`).
  */
class WycheproofEd25519Suite extends munit.FunSuite:
  private val root = Path.of("fixtures/wycheproof-ed25519")

  private def pinned(file: String, sha256: String): String =
    val bytes = Files.readAllBytes(root.resolve(file))
    val found =
      MessageDigest.getInstance("SHA-256").digest(bytes).map(b => f"${b & 0xff}%02x").mkString
    assertEquals(found, sha256, s"$file is not the pinned file")
    new String(bytes, UTF_8)

  private enum Expected:
    case Valid, Invalid, Acceptable

  private given Decoder[Expected] = Decoder.decodeString.emap {
    case "valid"      => Right(Expected.Valid)
    case "invalid"    => Right(Expected.Invalid)
    case "acceptable" => Right(Expected.Acceptable)
    case other        => Left(s"unknown result $other")
  }

  private final case class Test(
      tcId: Int,
      comment: String,
      flags: List[String],
      msg: String,
      sig: String,
      result: Expected
  ) derives Decoder
  private final case class Key(pk: String) derives Decoder
  private final case class Group(publicKey: Key, tests: List[Test]) derives Decoder
  private final case class Vectors(numberOfTests: Int, testGroups: List[Group]) derives Decoder

  private final case class Case(
      test: Test,
      key: Array[Byte],
      signature: Array[Byte],
      message: Array[Byte]
  )

  private enum Native:
    case SignatureVerified, SignatureRejected, NotRunMalformedLength

  private def bytes(hex: String): Array[Byte] =
    hex.grouped(2).map(Integer.parseInt(_, 16).toByte).toArray

  private val vectors =
    decode[Vectors](
      pinned(
        "ed25519_test.json",
        "ea80ffebac085e21cb9d3e903e7fa2b448d96702da5b31f061d0b83b430b74b3"
      )
    ).fold(e => fail(e.toString), identity)

  private val cases: Vector[Case] =
    vectors.testGroups.toVector.flatMap { g =>
      g.tests.map(t => Case(t, bytes(g.publicKey.pk), bytes(t.sig), bytes(t.msg)))
    }

  private val native: Map[Int, Native] =
    pinned(
      "sodium-results.tsv",
      "775a9a58de2b0bc94c7e2e0cf4c8015aa1a9728718fbc36b67b7ae199c2fc82a"
    ).linesIterator
      .map(_.split("\t", -1).toVector)
      .map {
        case Vector(id, verdict) => id.toInt -> Native.valueOf(verdict)
        case other               => fail(s"malformed row $other")
      }
      .toMap

  private val smallOrderKeys = (152 to 159).toSet
  private val mixedOrderKeys = (160 to 180).toSet

  test("the pinned file: 210 cases numbered 1 to 210, 117 valid, 64 invalid, 29 acceptable") {
    assertEquals(vectors.numberOfTests, 210, "numberOfTests")
    assertEquals(cases.map(_.test.tcId), (1 to 210).toVector, "case numbers")
    assertEquals(cases.count(_.test.result == Expected.Valid), 117, "valid")
    assertEquals(cases.count(_.test.result == Expected.Invalid), 64, "invalid")
    assertEquals(
      cases.filter(_.test.result == Expected.Acceptable).map(_.test.tcId).toSet,
      smallOrderKeys ++ mixedOrderKeys,
      "acceptable"
    )
    assertEquals(native.keySet, (1 to 210).toSet, "a native verdict for every case")
  }

  test("the reference agrees with every valid and invalid case, and decides the acceptable ones") {
    cases.foreach { c =>
      val id = c.test.tcId
      val expected = c.test.result match
        case Expected.Valid   => Set(Native.SignatureVerified)
        case Expected.Invalid => Set(Native.SignatureRejected, Native.NotRunMalformedLength)
        case Expected.Acceptable =>
          if smallOrderKeys(id) then Set(Native.SignatureRejected)
          else Set(Native.SignatureVerified)
      assert(
        expected(native(id)),
        s"case $id (${c.test.comment}): the reference says ${native(id)}"
      )
      assertEquals(
        native(id) == Native.NotRunMalformedLength,
        c.signature.length != 64,
        s"case $id: not run exactly when the signature is not 64 bytes"
      )
    }
    assertEquals(
      cases.count(c => native(c.test.tcId) == Native.NotRunMalformedLength),
      12,
      "not run"
    )
  }

  test("Scarab gives the reference's verdict on all 210 cases") {
    cases.foreach { c =>
      val expected = native(c.test.tcId) match
        case Native.SignatureVerified => Right(SignatureVerified)
        case Native.SignatureRejected => Right(SignatureRejected)
        case Native.NotRunMalformedLength =>
          Left(WitnessInputError.MalformedSignatureLength(c.signature.length))
      assertEquals(
        scarab(c.key, c.signature, c.message),
        expected,
        s"case ${c.test.tcId} (${c.test.comment}, ${c.test.flags.mkString(",")})"
      )
    }
  }

  test("Bouncy Castle gives the reference's verdict on all 210 cases") {
    cases.foreach { c =>
      assertEquals(
        bc(c.key, c.signature, c.message),
        native(c.test.tcId) == Native.SignatureVerified,
        s"case ${c.test.tcId} (${c.test.comment})"
      )
    }
  }

  test(
    "the JDK gives the reference's verdict except on the 8 keys of small order, which it accepts"
  ) {
    cases.foreach { c =>
      val id = c.test.tcId
      val outcome = jdk(c.key, c.signature, c.message)
      if smallOrderKeys(id) then
        assertEquals(
          outcome,
          Jdk.Verified,
          s"case $id (${c.test.comment}): no small-order test in the JDK"
        )
      else if native(id) == Native.NotRunMalformedLength then
        assertEquals(outcome, Jdk.SignatureRefused, s"case $id: a signature of the wrong length")
      else
        assertEquals(
          outcome == Jdk.Verified,
          native(id) == Native.SignatureVerified,
          s"case $id (${c.test.comment}): the JDK says $outcome, the reference ${native(id)}"
        )
    }
  }
