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
  * Runs when `SCARAB_K1_DB` names its verified current-format V5 conversion corpus, with the
  * recorded immutable chain available read-only (about 4 GB per state; allow a 12 GB heap for this
  * optional proof); otherwise it is skipped.
  */
class BoundaryDigestSuite extends FunSuite:
  override def munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(30, "min")

  private val golden: Map[Int, String] = Map(
    1400 -> "a3719403dbd2ab611d10eb8268ec1d68a810a8772a210ea515c21f5fbee23a51",
    1410 -> "ba21584259b1c29dfaf63121463154722964a5a9ebee7727e02692177545612d",
    1420 -> "96a4059abacc000f0dac4e2b5fe4b0ec72285fc43730ea81c0302e2ed5887660",
    1430 -> "b83b3239f3ce5f588b980a4e4eb5e4faf9512a77ffba8e68c5de5e0d8f8589b4",
    1440 -> "42dd4eaf8291e9db5c7339504cc6b4cd305a81b48e08ee0bcc56942d0f694096",
    1449 -> "6f5b91ad4a64a95a5ba8be3ecafecef753a50146e2ac029f0a8deff26468f033"
  )

  private def db: Option[Path] =
    sys.env
      .get("SCARAB_K1_DB")
      .map(Path.of(_))
      .filter(p => Files.isDirectory(p.resolve("snapshots")))

  test("the ticked state at the boundary is the one recorded before the change") {
    assume(db.isDefined, "SCARAB_K1_DB is not set")
    if sys.env.contains("SCARAB_CANDIDATE_RUNTIME") then
      val heap = Runtime.getRuntime.maxMemory()
      val processors = Runtime.getRuntime.availableProcessors()
      println(s"candidate-runtime project=node maxHeapBytes=$heap processors=$processors")
      assert(heap >= 16L * 1024 * 1024 * 1024, s"candidate fork heap is only $heap bytes")
      assertEquals(processors, 4)
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
