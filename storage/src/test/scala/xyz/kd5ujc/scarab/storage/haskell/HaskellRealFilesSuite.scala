// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.storage.LegacyFormats

/** The index files of a real Haskell ImmutableDB, read by the scodec codecs and by the legacy
  * parsers: every chunk must give the same entries, offsets and faults. Runs when `SCARAB_K1_DB`
  * names a Haskell node's `db` directory (the K1 Preview run: 29,031 chunks of 4320 slots).
  */
class HaskellRealFilesSuite extends FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(20, "min")

  private val chunkSlots = ChunkSlots.of(4320).get

  private def immutableDir: Option[Path] =
    sys.env.get("SCARAB_K1_DB").map(Path.of(_).resolve("immutable")).filter(Files.isDirectory(_))

  test("every chunk's primary and secondary index parse as they did before") {
    assume(immutableDir.isDefined, "SCARAB_K1_DB is not set")
    val dir = immutableDir.get
    val stems = Files
      .list(dir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".secondary"))
      .map(_.stripSuffix(".secondary"))
      .toVector
      .sorted
    var entries = 0L
    var offsets = 0L
    stems.foreach { stem =>
      val secondary = Files.readAllBytes(dir.resolve(s"$stem.secondary"))
      val primary = Files.readAllBytes(dir.resolve(s"$stem.primary"))
      val newSecondary = SecondaryIndex.parse(secondary, tolerateTornTail = true)
      assertEquals(
        newSecondary,
        LegacyFormats.HaskellSecondary.parse(secondary, tolerateTornTail = true),
        stem
      )
      assertEquals(
        PrimaryIndex.parse(primary, chunkSlots, tolerateTornTail = true),
        LegacyFormats.HaskellPrimary.parse(primary, chunkSlots, tolerateTornTail = true),
        stem
      )
      entries += newSecondary.toOption.fold(0)(_.size)
      offsets += PrimaryIndex
        .parse(primary, chunkSlots, tolerateTornTail = true)
        .toOption
        .fold(0)(_.offsets.size)
    }
    println(
      s"HaskellRealFilesSuite: ${stems.size} chunks, $entries secondary entries, $offsets primary offsets, identical to the legacy parsers"
    )
    assert(stems.nonEmpty)
  }
