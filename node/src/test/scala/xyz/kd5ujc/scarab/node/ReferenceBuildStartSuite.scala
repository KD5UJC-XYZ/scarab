// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.ExitCode
import java.nio.file.Files
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotExportCommand
import xyz.kd5ujc.scarab.storage.StorageTestKit

/** `follow --reference-build` end to end (#826): a build not implemented yet is refused at start,
  * before the database is touched; an unknown one is refused with the usage. `11.2`, whose layout
  * is not implemented yet, is refused by `export-ledger-snapshot` too.
  */
class ReferenceBuildStartSuite extends CatsEffectSuite:
  private def follow(db: String, build: String): List[String] =
    List(
      "follow",
      "--genesis-dir",
      "fixtures/eras/genesis",
      "--db",
      db,
      "--peer",
      "127.0.0.1:1",
      "--seconds",
      "5",
      "--reference-build",
      build
    )

  private def untouched(dir: fs2.io.file.Path): Unit =
    val listing = Files.list(dir.toNioPath)
    try assertEquals(listing.toArray.toVector, Vector.empty)
    finally listing.close()

  // the Leios build runs (#54); 11.2 is the one build not implemented yet
  List("11.2").foreach { build =>
    test(s"$build is refused at start, before any store, lease or file of the database") {
      StorageTestKit.tempDir.use { dir =>
        Main.run(follow((dir / "db").toString, build)).map { code =>
          assertEquals(code, ExitCode(1))
          untouched(dir)
        }
      }
    }
  }

  // without a database directory the Leios build has no endorser-block store, so a certifying
  // block could never be applied: refused at start, before any store opens (#54)
  test(
    "leios-rc-2026w47 without --db or DatabasePath is refused at start, before any store opens"
  ) {
    Main
      .run(
        List(
          "follow",
          "--genesis-dir",
          "fixtures/dijkstra-devnet/genesis",
          "--peer",
          "127.0.0.1:1",
          "--seconds",
          "5",
          "--reference-build",
          "leios-rc-2026w47"
        )
      )
      .map(assertEquals(_, ExitCode(1)))
  }

  // the database directory the configuration names is the run's (`DatabasePath`, completed into
  // the options before the store is admitted and opened there)
  test("leios-rc-2026w47 with DatabasePath from --config and no --db opens its store there") {
    StorageTestKit.tempDir.use { dir =>
      val genesis = java.nio.file.Path.of("fixtures", "dijkstra-devnet", "genesis").toAbsolutePath
      val source = Files.readString(genesis.resolve("config.json"))
      val config = io.circe.parser
        .parse(source)
        .flatMap(_.asObject.toRight(new IllegalArgumentException("not an object")))
        .fold(e => fail(e.toString), identity)
      val rewritten = io.circe.Json
        .fromJsonObject(
          config.toList
            .foldLeft(config) { case (obj, (key, value)) =>
              if key.endsWith("GenesisFile") then
                value.asString.fold(obj)(file =>
                  obj.add(key, io.circe.Json.fromString(genesis.resolve(file).toString))
                )
              else obj
            }
            .add("DatabasePath", io.circe.Json.fromString((dir / "db").toString))
        )
        .noSpaces
      val file = (dir / "config.json").toNioPath
      for
        _ <- cats.effect.IO.blocking(Files.writeString(file, rewritten))
        code <- Main.run(
          List(
            "follow",
            "--config",
            file.toString,
            "--peer",
            "127.0.0.1:1",
            "--seconds",
            "2",
            "--reference-build",
            "leios-rc-2026w47"
          )
        )
        opened <- cats.effect.IO.blocking(Files.isDirectory((dir / "db" / "leios").toNioPath))
      yield
        assertEquals(code, ExitCode.Success)
        assert(opened, "no endorser-block store in the configured database directory")
    }
  }

  test("11.2 is refused by export-ledger-snapshot before any file; reading it would exit 1") {
    StorageTestKit.tempDir.use { dir =>
      LedgerSnapshotExportCommand
        .run(
          List("--db", (dir / "db").toString, "--out", (dir / "out").toString) ++
            List("--reference-build", "11.2")
        )
        .map { code =>
          assertEquals(code, ExitCode(2))
          untouched(dir)
        }
    }
  }

  test("an unknown build is refused as a usage error, exit status 2") {
    StorageTestKit.tempDir.use { dir =>
      Main.run(follow((dir / "db").toString, "11.1.3")).map(assertEquals(_, ExitCode(2)))
    }
  }
