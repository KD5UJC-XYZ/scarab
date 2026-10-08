// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.witness.VerificationResult
import lab.ledger.BalanceResult

class CoverageCommandSuite extends munit.FunSuite:
  private def fixture: String =
    val root = if Files.exists(Path.of("fixtures")) then Path.of(".") else Path.of("..")
    Files.readString(root.resolve("fixtures/coverage/coverage-vectors.tsv"))

  test("genuine accepted/rejected cases separate valid signatures from coverage") {
    val checks = CoverageCommand.parse(fixture).toOption.get
    assertEquals(checks.size, 2)
    assert(checks.forall(_.matched))
    assert(checks.forall(_.signatures == Vector(VerificationResult.SignatureVerified)))
    assert(checks.forall(_.balance.isInstanceOf[BalanceResult.PredicateSatisfied]))
    assertEquals(checks.map(_.coverage.covered).sorted, Vector(false, true))
    val rejection = checks.find(!_.coverage.covered).get
    assertEquals(
      rejection.coverage.missing.map(_.bytes.hex),
      Set("3c875ce0f647bdcc64b70e62814680fbd939f8faf0203b98236ede7b")
    )
  }
  test("fixture parser requires exactly two distinct opposed expectations") {
    assert(CoverageCommand.parse("").isLeft)
    assert(CoverageCommand.parse(fixture + fixture).isLeft)
    assert(CoverageCommand.parse(fixture.replace("\tfalse", "\ttrue")).isLeft)
    assert(CoverageCommand.parse("name\t00\t00\tfalse").isLeft)
  }

  test("signature-only mutation preserves real fixture coverage but rejects verification") {
    val lines = fixture.linesIterator.filterNot(_.startsWith("#")).filterNot(_.isBlank).toVector
    val accepted = lines.find(_.endsWith("\ttrue")).get.split("\t", -1)
    val raw = lab.cbor.Bytes.fromHex(accepted(1)).toOption.get
    val projection = lab.ledger.Coverage.decode(raw).toOption.get
    val originalSignature = projection.witnesses.head.signature.bytes.hex
    val mutated = fixture.replace(originalSignature, "00" * 64)
    val before = CoverageCommand.parse(fixture).toOption.get.find(_.expectedCoverage).get
    val after = CoverageCommand.parse(mutated).toOption.get.find(_.expectedCoverage).get
    assertEquals(after.coverage.required, before.coverage.required)
    assertEquals(after.coverage.provided, before.coverage.provided)
    assert(after.coverage.covered)
    assertEquals(after.signatures, Vector(VerificationResult.SignatureRejected))
    assert(!after.matched)
  }
