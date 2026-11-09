// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.ExitCode
import java.nio.file.Files
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.storage.StorageTestKit

/** `follow --reference-build` end to end (#826): a build not implemented yet is refused at start,
  * before the database is touched; an unknown one is refused with the usage.
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

  test("the Leios build is refused at start, before any store, lease or file of the database") {
    StorageTestKit.tempDir.use { dir =>
      val db = dir / "db"
      Main.run(follow(db.toString, "leios-b004fe0c")).map { code =>
        assertEquals(code, ExitCode(1))
        val listing = Files.list(dir.toNioPath)
        try assertEquals(listing.toArray.toVector, Vector.empty)
        finally listing.close()
      }
    }
  }

  test("an unknown build is refused as a usage error, exit status 2") {
    StorageTestKit.tempDir.use { dir =>
      Main.run(follow((dir / "db").toString, "11.1.3")).map(assertEquals(_, ExitCode(2)))
    }
  }
