// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.ledger.{BalanceResult, FeePredicate, SizePredicate}

class FeeSizeCommandSuite extends munit.FunSuite:
  private def fixture: String =
    val root = if Files.exists(Path.of("fixtures")) then Path.of(".") else Path.of("..")
    Files.readString(root.resolve("fixtures/fee-size/fee-size-vectors.tsv"))

  test("demo keeps archived ledger result separate from fee size and balance") {
    val checks = FeeSizeCommand.parse(fixture).toOption.get
    assert(checks.forall(_.matched))
    assert(checks.forall(_.predicates.fee.isInstanceOf[FeePredicate.Satisfied]))
    assert(checks.forall(_.predicates.size.isInstanceOf[SizePredicate.Satisfied]))
    val rejected = checks.find(!_.archivedLedgerSuccess).get
    assert(rejected.balance.isInstanceOf[BalanceResult.ValueNotConserved])
  }

  test("malformed, duplicate, incomplete and nonnumeric fixture rows reject") {
    for source <- Seq(
        "",
        fixture + fixture,
        "x\t00",
        fixture.replace("\t44\t", "\t-1\t"),
        fixture.replace("\t44\t", "\t18446744073709551616\t"),
        fixture.replace("\tfalse", "\ttrue")
      )
    do assert(FeeSizeCommand.parse(source).isLeft)
  }

  test("altered size expectation yields an explicit unmatched result") {
    val checks = FeeSizeCommand.parse(fixture.replace("\t265\t", "\t264\t")).toOption.get
    assert(!checks.head.matched)
  }
