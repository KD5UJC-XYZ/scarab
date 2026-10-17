// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.differential

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.node.NodeSetup

/** The epoch boundary of the new rules gives the state it gave before it was made fast (#131): the
  * SHA-256 of the Haskell-format encoding of the ticked `NewEpochState` at the first block of an
  * epoch, from the snapshots of the Preview K1 run. The digests were taken with the build before
  * the change (`docs/performance.md` section 16 records the 50 of snapshots 1400 to 1449, the same
  * before and after); a change to the rules of the boundary that should change the state changes
  * them, and then the new ones are recorded with a reason.
  *
  * Runs when `SCARAB_K1_DB` names the `db` directory of that run (about 4 GB of heap per state:
  * start sbt with `-J-Xmx12g`); otherwise it is skipped.
  */
class BoundaryDigestSuite extends FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(30, "min")

  private val golden: Map[Int, String] = Map(
    1400 -> "edbf855ecc1762b400742af669746f4003491f23732c6e8bf62db16671522de4",
    1410 -> "9c8a9d5b7753645ca1049609d4f80d1ec6acd6ab5b9507d8eee1d5081e32fb15",
    1420 -> "53aa6f063a8f5f7d549fd3267c431a65fcd17271c8756a9d8cae43c4a25b6320",
    1430 -> "3b52230f04d5c5f1dfa55ba23d9d68773d108add8a1434139182b84984795f76",
    1440 -> "fa2b6468b1c5b755d8e14a6015a8a3272458152f325726bb2e040b0ce5bc58e1",
    1449 -> "fe6af09d3fa463251bed8ffbfe05f959f837a437a6076f68f93b126ed38191a5"
  )

  private def db: Option[Path] =
    sys.env
      .get("SCARAB_K1_DB")
      .map(Path.of(_))
      .filter(p => Files.isDirectory(p.resolve("snapshots")))

  test("the ticked state at the boundary is the one recorded before the change") {
    assume(db.isDefined, "SCARAB_K1_DB is not set")
    val dir = db.get
    val setup =
      NodeSetup
        .fromDirectory(Path.of("fixtures/preview/genesis"))
        .fold(e => fail(e.message), identity)
    val snapshots = BoundaryTickMain.snapshotFiles(dir)
    golden.toList.sortBy(_._1).foreach { (index, digest) =>
      val row = BoundaryTickMain.measure(dir, setup, snapshots, index, repeats = 1)
      assertEquals(row.digest, digest, s"snapshot $index")
    }
  }
