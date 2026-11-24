// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.tools

import java.nio.file.Path
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild
import xyz.kd5ujc.scarab.storage.haskell.ChunkSlots

/** `import-haskell-db --reference-build` (#54): the build that wrote the database names the network
  * setup and whether its Dijkstra blocks are stored.
  */
class HaskellImportCommandSuite extends FunSuite:
  private val base = List("--haskell-db", "h", "--db", "d", "--config", "c.json")

  test("without the flag the build is 11.1; with it, the build named") {
    assertEquals(HaskellImportCommand.parse(base).map(_.build), Right(ReferenceBuild.Release11_1))
    assertEquals(
      HaskellImportCommand
        .parse(base ++ List("--reference-build", "leios-rc-2026w47"))
        .map(_.build),
      Right(ReferenceBuild.Leios)
    )
  }

  test("an unknown build is refused with the typed problem") {
    HaskellImportCommand.parse(base ++ List("--reference-build", "12.0")) match
      case Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.ReferenceBuildUnknown(_), _))) =>
        ()
      case other => fail(s"not refused as an unknown build: $other")
  }

  test("the public Leios testnet under its build: its setup, magic 164 and chunks of 1,080 slots") {
    val options = HaskellImportCommand
      .parse(
        List(
          "--haskell-db",
          "h",
          "--db",
          "d",
          "--config",
          "fixtures/leios/testnet/config/config.json",
          "--stop-slot",
          "1943999",
          "--reference-build",
          "leios-rc-2026w47"
        )
      )
      .fold(e => fail(e.message), identity)
    val (setup, chunk) = HaskellImportCommand.setupOf(options).fold(e => fail(e.message), identity)
    val config = HaskellImportCommand.config(options, setup, chunk)
    assertEquals(config.build, ReferenceBuild.Leios)
    assertEquals(config.stopSlot, Some(1943999L))
    assertEquals(config.identity.magic, 164L)
    // k = 108 (the testnet's `securityParam`): the reference's chunks of 1,080 slots
    assertEquals(Some(chunk), ChunkSlots.of(1080))
    assertEquals(config.haskellDb.toNioPath, Path.of("h"))
  }
