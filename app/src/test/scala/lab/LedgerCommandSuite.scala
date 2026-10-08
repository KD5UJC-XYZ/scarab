// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.file.{Files, Path}
import lab.ledger.BalanceResult

class LedgerCommandSuite extends munit.FunSuite:
  private def corpus: String =
    val local = Path.of("fixtures/ledger/ledger-vectors.tsv")
    Files.readString(
      if Files.exists(local) then local else Path.of("../fixtures/ledger/ledger-vectors.tsv")
    )

  test("four archived upstream cases compute from resolved inputs and raw transaction bodies") {
    val checks = LedgerCommand.parse(corpus).toOption.get
    assertEquals(checks.size, 4)
    assert(checks.forall(_.matched))
    assertEquals(checks.count(_.satisfied), 2)
    val failures = checks.collect {
      case LedgerCommand.Check(_, _, BalanceResult.ValueNotConserved(c, p, delta)) => (c, p, delta)
    }
    assertEquals(failures.map(_._3.lovelace).sorted, Vector(BigInt(-3), BigInt(0)))
    assert(failures.exists { case (c, p, d) =>
      c.assets.values.toSet == Set(BigInt(1)) && p.assets.values.toSet == Set(
        BigInt(2)
      ) && d.assets.values.toSet == Set(BigInt(-1))
    })
  }
  test("empty malformed or duplicated projection cases fail") {
    assert(LedgerCommand.parse("").isLeft)
    assert(LedgerCommand.parse("bad\tdata\tbroken\ttrue").isLeft)
    val row = corpus.linesIterator.find(s => !s.startsWith("#") && !s.isBlank).get
    assert(LedgerCommand.parse(Vector.fill(4)(row).mkString("\n")).isLeft)
  }
  test("changing recorded expectations reports mismatch rather than altering arithmetic") {
    val changed = corpus.linesIterator
      .map { line =>
        if line.endsWith("\ttrue") then line.stripSuffix("\ttrue") + "\tfalse" else line
      }
      .mkString("\n")
    val checks = LedgerCommand.parse(changed).toOption.get
    assertEquals(checks.count(_.matched), 2)
    assertEquals(checks.count(_.satisfied), 2)
  }
