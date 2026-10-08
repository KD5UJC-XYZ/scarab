// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.cbor.Bytes

/** Independent harness tests. Archived native observations are differential evidence, not an exact
  * Cardano-fork binary oracle, signing capability, or production audit.
  */
class WitnessCommandSuite extends munit.FunSuite:
  private val expectedPins = Map(
    "vectors.tsv" -> "f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918",
    "status.tsv" -> "f5b22e990b28f1bff90ef2304f3a1d19e1ba40d85e984bcf08a869cffbf44934",
    "ledger-witnesses.tsv" -> "eb8a8ae0c0731ef24e03789dcc26c314cdada4298a6555aa69f484ff544c2d3f"
  )
  private lazy val corpus: Map[String, Bytes] = expectedPins.keys.map { name =>
    val local = Path.of("fixtures/witness", name)
    val path = if Files.exists(local) then local else Path.of("../fixtures/witness", name)
    name -> Bytes.fromArray(Files.readAllBytes(path))
  }.toMap

  private def changed(bytes: Bytes): Bytes =
    Bytes(bytes.value.updated(0, (bytes.value.head ^ 1).toByte))

  test("projection admission uses three independently fixed SHA256 values") {
    assertEquals(WitnessCommand.pins, expectedPins)
    corpus.foreach { case (name, bytes) =>
      val admitted = WitnessCommand.admit(name, bytes).fold(fail(_), identity)
      assertEquals(
        Bytes.fromArray(admitted.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        bytes
      )
    }
  }

  test("all 2219 public cases and eight genuine individual ledger witnesses match") {
    val report = WitnessCommand.check(corpus).fold(fail(_), identity)
    assertEquals(report.checks.size, 2219)
    assertEquals(report.ledgerChecks.size, 8)
    assertEquals(report.checks.map(_.name).distinct.size, 2219)
    assertEquals(report.ledgerChecks.map(_.name).distinct.size, 8)
    assert(report.matched)
    assert((report.checks ++ report.ledgerChecks).forall(_.matched))
    assertEquals(report.checks.count(_.actual == "MalformedSignatureLength"), 12)
    assertEquals(report.checks.count(_.name.startsWith("sodium-valid-")), 1024)
    assertEquals(report.checks.count(_.name.startsWith("sodium-S-plus-L-")), 1024)
    assertEquals(report.checks.count(_.name.startsWith("wycheproof-")), 151)
    assertEquals(report.checks.count(_.name.startsWith("speccheck-")), 12)
    assertEquals(report.checks.count(_.name.startsWith("amaru-")), 8)
    val byName = report.checks.map(c => c.name -> c.actual).toMap
    List(2, 4, 5).foreach { index =>
      assertEquals(byName(s"speccheck-$index"), "SignatureRejected")
    }
    assertEquals(byName("speccheck-3"), "SignatureVerified")
    assert(report.ledgerChecks.forall(_.actual == "SignatureVerified"))
  }

  test("each modified projection is rejected before parsing or cryptographic evaluation") {
    corpus.foreach { case (name, bytes) =>
      val altered = changed(bytes)
      assertEquals(
        WitnessCommand.admit(name, altered),
        Left(s"$name immutable projection digest mismatch")
      )
      assertEquals(
        WitnessCommand.check(corpus.updated(name, altered)),
        Left(s"$name immutable projection digest mismatch")
      )
      assertEquals(
        WitnessCommand.check(corpus.updated(name, Bytes.empty)),
        Left(s"$name immutable projection digest mismatch")
      )
    }
  }

  test("each missing projection fails closed rather than silently skipping its checks") {
    corpus.keys.foreach { name =>
      assertEquals(WitnessCommand.check(corpus - name), Left(s"missing $name"))
    }
    assert(WitnessCommand.check(Map.empty).isLeft)
  }

  test("unknown or path-like pin names cannot admit otherwise genuine fixture data") {
    List("unknown.tsv", "../vectors.tsv", "VECTORS.TSV", "").foreach { name =>
      assertEquals(
        WitnessCommand.admit(name, corpus("vectors.tsv")),
        Left(s"$name immutable projection digest mismatch")
      )
    }
    assert(WitnessCommand.admit("status.tsv", corpus("vectors.tsv")).isLeft)
  }

  test("eight MiB is bounded admission and limit plus one fails before digest matching") {
    val atLimit = Bytes(Vector.fill(8 * 1024 * 1024)(0.toByte))
    val overLimit = Bytes(atLimit.value :+ 0.toByte)
    assertEquals(
      WitnessCommand.admit("vectors.tsv", atLimit),
      Left("vectors.tsv immutable projection digest mismatch")
    )
    expectedPins.keys.foreach { name =>
      assertEquals(WitnessCommand.admit(name, overLimit), Left(s"$name exceeds 8 MiB"))
      assertEquals(
        WitnessCommand.check(corpus.updated(name, overLimit)),
        Left(s"$name exceeds 8 MiB")
      )
    }
  }

  test("report cannot hide either a primitive mismatch or an individual witness mismatch") {
    val good = WitnessCommand.Check("public", "SignatureVerified", "SignatureVerified")
    val bad = WitnessCommand.Check("public", "SignatureVerified", "SignatureRejected")
    assert(good.matched)
    assert(!bad.matched)
    assert(!WitnessCommand.Report(Vector(bad), Vector(good)).matched)
    assert(!WitnessCommand.Report(Vector(good), Vector(bad)).matched)
    assert(WitnessCommand.Report(Vector(good), Vector(good)).matched)
  }
