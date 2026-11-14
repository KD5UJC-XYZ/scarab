// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.protocol.{ReferenceBuild, ReferenceBuildNotYet}

/** `import-ledger-snapshot --reference-build`: a build whose layout is not implemented yet is
  * refused before anything else the import needs.
  */
class LedgerSnapshotImportBuildSuite extends FunSuite:
  private def options(build: String) =
    LedgerSnapshotImportCommand
      .parse(
        List(
          "--haskell-ledger",
          "/l",
          "--db",
          "/d",
          "--genesis-dir",
          "fixtures/preview/genesis",
          "--no-verify-ancillary",
          "--reference-build",
          build
        )
      )
      .fold(e => fail(e.message), identity)

  test("11.2 is refused as a layout not implemented yet; 11.1 gets its codec") {
    assertEquals(
      LedgerSnapshotImportCommand.inputs(options("11.2")).map(_ => ()),
      Left(
        ConfigError(
          ConfigProblem.ReferenceBuildNotYet(
            ReferenceBuildNotYet.Layout(ReferenceBuild.Release11_2)
          )
        )
      )
    )
    assertEquals(
      LedgerSnapshotImportCommand.inputs(options("11.1")).map(_._2.layout.build),
      Right(ReferenceBuild.Release11_1)
    )
  }
