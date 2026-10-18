// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotExportCommand

class LedgerSnapshotExportCommandSuite extends FunSuite:
  private val required = List("--db", "/data/db", "--out", "/data/ledger")

  test("export is offline and accepts an exact slot throughout uint64") {
    assert(LedgerSnapshotExportCommand.parse(required).isRight)
    val max = LedgerSnapshotExportCommand
      .parse(required ++ List("--slot", "18446744073709551615"))
      .fold(e => fail(e.message), identity)
    assertEquals(max.slot.map(_.toBigInt), Some((BigInt(1) << 64) - 1))
  }

  test("invalid slots, duplicate arguments, missing paths and malformed paths are refused") {
    Vector("-1", "18446744073709551616", "1.0", "", "abc", "+1").foreach { slot =>
      assert(LedgerSnapshotExportCommand.parse(required ++ List("--slot", slot)).isLeft)
    }
    assert(LedgerSnapshotExportCommand.parse(List("--db", "/data/db")).isLeft)
    assert(LedgerSnapshotExportCommand.parse(required ++ List("--db", "/other")).isLeft)
    assert(LedgerSnapshotExportCommand.parse(required ++ List("--unknown", "1")).isLeft)
    assert(LedgerSnapshotExportCommand.parse(List("--db", "a\u0000b", "--out", "/out")).isLeft)
  }
