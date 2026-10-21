// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.haskell

import cats.syntax.all.*
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Complete captured native index files must parse successfully and agree with the legacy Scala
  * parsers. This is a differential regression, not an independent Haskell acceptance oracle.
  * `SCARAB_K1_DB` names the pinned external database; its artifact identity belongs to the
  * candidate manifest. Closed files are strict; only the greatest numeric chunk may retain a
  * partial tail, matching HaskellImmutableReader. Tail byte counts are reported separately, never
  * erased from source files or counted as decoded entries. Other malformed-input controls remain
  * separate.
  */
class HaskellRealFilesSuite extends FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(20, "min")

  private val chunkSlots = ChunkSlots.of(4320).getOrElse(fail("invalid constant chunk geometry"))

  private def immutableDir: Option[Path] =
    sys.env.get("SCARAB_K1_DB").map(Path.of(_).resolve("immutable")).filter(Files.isDirectory(_))

  test("every chunk's primary and secondary index parse as they did before") {
    assume(immutableDir.isDefined, "SCARAB_K1_DB is not set")
    val dir = immutableDir.getOrElse(fail("required native ImmutableDB directory is absent"))
    val files = Using.resource(Files.list(dir)) { entries =>
      entries.iterator().asScala.filter(Files.isRegularFile(_)).map(_.getFileName.toString).toVector
    }
    val compared = for
      stems <- NativeIndexComparison.inventory(files)
      summary <- stems.foldLeftM(NativeIndexComparison.empty) { (total, stem) =>
        NativeIndexComparison
          .compare(
            stem,
            Files.readAllBytes(dir.resolve(s"$stem.primary")),
            Files.readAllBytes(dir.resolve(s"$stem.secondary")),
            chunkSlots,
            mostRecent = stems.lastOption.contains(stem)
          )
          .map(total.combine)
      }
      complete <- NativeIndexComparison.complete(summary)
    yield complete
    val summary = compared.fold(error => fail(error.toString), identity)
    println(
      s"HaskellRealFilesSuite: ${summary.chunks} chunk file triplets, ${summary.entries} successful secondary entries, ${summary.offsets} successful primary offsets, newest primary/secondary tail bytes=${summary.primaryTailBytes}/${summary.secondaryTailBytes}, identical to the legacy parsers"
    )
  }
