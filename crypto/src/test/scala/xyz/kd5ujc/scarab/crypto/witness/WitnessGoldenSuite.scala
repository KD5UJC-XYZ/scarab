// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.witness

import java.nio.file.{Files, Path}
import java.nio.charset.StandardCharsets.UTF_8

class WitnessGoldenSuite extends munit.FunSuite:
  import WitnessGoldenCorpus.*

  private val root = Path.of("fixtures/witness")
  private def pinned(file: String, sha256: String): String =
    checked(file, Files.readAllBytes(root.resolve(file)), sha256)
      .fold(e => fail(e.toString), identity)

  private val vectors =
    pinned("vectors.tsv", "f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918")
  private val status =
    pinned("status.tsv", "f5b22e990b28f1bff90ef2304f3a1d19e1ba40d85e984bcf08a869cffbf44934")
  private val archive = pinned(
    "archive/pinned-source-oracle/results.json",
    "af45f40dcfe8e6bb670904c35067b3f581c0d7bdf6b7b63a8599eb4a1cfaf7d3"
  )
  private val all = read(vectors, status, archive).fold(e => fail(e.toString), identity)

  test(
    "all 2219 original IDs match native/status goldens, including 12 typed malformed signatures"
  ) {
    assertEquals(all.size, 2219)
    assertEquals(all.map(_.input.id).distinct.size, 2219)
    assertEquals(all.count(_.expected == Right(VerificationResult.SignatureVerified)), 1121)
    assertEquals(all.count(_.expected == Right(VerificationResult.SignatureRejected)), 1086)
    assertEquals(all.count(_.expected.isLeft), 12)
    all.foreach { golden =>
      val input = golden.input
      assertEquals(
        StrictEd25519.verify(input.key, input.signature, input.message),
        golden.expected,
        input.id
      )
    }
  }

  test("changed input or expectation bytes fail their independent digest pins") {
    for (file, text, pin) <- Vector(
        (
          "vectors.tsv",
          vectors,
          "f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918"
        ),
        ("status.tsv", status, "f5b22e990b28f1bff90ef2304f3a1d19e1ba40d85e984bcf08a869cffbf44934"),
        (
          "results.json",
          archive,
          "af45f40dcfe8e6bb670904c35067b3f581c0d7bdf6b7b63a8599eb4a1cfaf7d3"
        )
      )
    do
      assertEquals(
        checked(file, (text + " ").getBytes(UTF_8), pin),
        Left(Error.DigestMismatch(file))
      )

  }

  test("missing, extra and duplicate status IDs cannot reduce the replay") {
    val lines = status.linesIterator.toVector
    val first = lines(1).takeWhile(_ != '\t')
    assertEquals(
      read(vectors, lines.updated(1, lines(1).replace(first, "extra-id")).mkString("\n"), archive),
      Left(Error.IdMismatch("status.tsv", Set(first), Set("extra-id")))
    )
    assertEquals(
      read(vectors, (lines.head +: lines.drop(2)).mkString("\n"), archive),
      Left(Error.IdMismatch("status.tsv", Set(first), Set.empty))
    )
    assertEquals(
      read(vectors, status + lines(1) + "\n", archive),
      Left(Error.DuplicateId("status.tsv", first))
    )
  }

  test("duplicate inputs, missing native rows and stale native lengths or verdicts fail") {
    val first = all.head.input.id
    val originalLines = vectors.linesIterator.toVector
    assertEquals(
      read(vectors + originalLines.head + "\n", status, archive),
      Left(Error.DuplicateId("vectors.tsv", first))
    )
    val json = io.circe.parser.parse(archive).fold(e => fail(e.message), identity)
    val rows = json.asArray.fold(fail("native archive is not an array"))(identity)
    assertEquals(
      read(vectors, status, io.circe.Json.fromValues(rows :+ rows.head).noSpaces),
      Left(Error.DuplicateId("results.json", first))
    )
    assertEquals(
      read(vectors, status, io.circe.Json.fromValues(rows.tail).noSpaces),
      Left(Error.IdMismatch("results.json", Set(first), Set.empty))
    )
    for (field, value) <- Vector(
        "message_bytes" -> io.circe.Json.fromInt(1),
        "pinned_sodium" -> io.circe.Json.False,
        "native_called" -> io.circe.Json.False
      )
    do
      val stale = rows.head.mapObject(_.add(field, value))
      assertEquals(
        read(vectors, status, io.circe.Json.fromValues(rows.updated(0, stale)).noSpaces),
        Left(Error.InconsistentObservation(first))
      )
  }

  test("malformed fields and a status verdict inconsistent with the archive fail") {
    val first = all.head.input.id
    val lines = vectors.linesIterator.toVector
    val fields = lines.head.split("\t", -1).toVector
    assertEquals(
      read(
        lines.updated(0, fields.updated(1, "zz").mkString("\t")).mkString("\n"),
        status,
        archive
      ),
      Left(Error.MalformedField(first, "key"))
    )
    assertEquals(read(vectors, "bad header\n", archive), Left(Error.MalformedRow("status.tsv", 1)))
    assertEquals(
      read(vectors, status.replaceFirst("SignatureVerified", "SignatureRejected"), archive),
      Left(Error.InconsistentObservation(first))
    )
    assert(read(vectors, status, "not JSON").left.exists {
      case Error.MalformedArchive(_) => true
      case _                         => false
    })
  }
