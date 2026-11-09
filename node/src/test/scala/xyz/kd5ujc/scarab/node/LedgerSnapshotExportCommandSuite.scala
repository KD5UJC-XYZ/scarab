// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.ConfigProblem
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotExportCommand
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

class LedgerSnapshotExportCommandSuite extends FunSuite:
  private val required = List("--db", "/data/db", "--out", "/data/ledger")

  test("export is offline and accepts an exact slot throughout uint64") {
    assert(LedgerSnapshotExportCommand.parse(required).isRight)
    val max = LedgerSnapshotExportCommand
      .parse(required ++ List("--slot", "18446744073709551615"))
      .fold(e => fail(e.message), identity)
    assertEquals(max.config.slot.map(_.toBigInt), Some((BigInt(1) << 64) - 1))
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

  test(
    "--reference-build names the build the output is for: 11.1 without it, unknown values refused"
  ) {
    def build(args: List[String]) =
      LedgerSnapshotExportCommand.parse(required ++ args).map(_.build)
    assertEquals(build(Nil), Right(ReferenceBuild.Release11_1))
    assertEquals(build(List("--reference-build", "11.1")), Right(ReferenceBuild.Release11_1))
    assertEquals(
      build(List("--reference-build", "leios-b004fe0c")),
      Right(ReferenceBuild.LeiosB004fe0c)
    )
    LedgerSnapshotExportCommand.parse(required ++ List("--reference-build", "11.1.3")) match
      case Left(error) =>
        error.problem match
          case ConfigProblem.WithUsage(ConfigProblem.ReferenceBuildUnknown(cause), _) =>
            assertEquals(cause.text, "11.1.3")
          case other => fail(s"not an unknown build: $other")
      case Right(options) => fail(s"11.1.3 was accepted: $options")
    assert(
      LedgerSnapshotExportCommand
        .parse(required ++ List("--reference-build", "11.1", "--reference-build", "11.1"))
        .isLeft
    )
  }
