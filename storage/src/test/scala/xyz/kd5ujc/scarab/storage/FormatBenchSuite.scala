// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.storage.haskell.*

/** Decoding throughput of the scodec codecs against the `ByteBuffer` parsers they replaced
  * ([[LegacyFormats]]), in one JVM with interleaved rounds. Not part of `check`: it runs when
  * `SCARAB_BENCH` is set, and uses the index files of the Haskell ImmutableDB named by
  * `SCARAB_K1_DB` for the Haskell formats. Prints median nanoseconds per record.
  */
class FormatBenchSuite extends FunSuite:
  override val munitTimeout: scala.concurrent.duration.Duration =
    scala.concurrent.duration.Duration(30, "min")

  private val enabled = sys.env.contains("SCARAB_BENCH")
  private val warmups = 5
  private val runs = 10
  private val rounds = 3

  /** Keeps results alive so the JIT cannot discard the work. */
  @volatile private var sink = 0L

  override def afterAll(): Unit = if enabled then println(s"BENCH checksum of results: $sink")

  private def median(xs: Vector[Double]): Double = xs.sorted.apply(xs.size / 2)

  /** Median ns per unit over `runs` timed runs after `warmups` untimed ones. */
  private def measure(units: Long)(work: => Long): Double =
    (0 until warmups).foreach(_ => sink += work)
    median(Vector.fill(runs) {
      val t0 = System.nanoTime()
      sink += work
      (System.nanoTime() - t0).toDouble / units
    })

  private def compare(label: String, units: Long)(legacy: => Long)(scodec: => Long): Unit =
    val rs = (1 to rounds).map(_ => (measure(units)(legacy), measure(units)(scodec)))
    val l = median(rs.map(_._1).toVector)
    val s = median(rs.map(_._2).toVector)
    println(
      f"BENCH $label%-44s legacy $l%8.1f ns   scodec $s%8.1f ns   ratio ${s / l}%.2f  (rounds: ${rs
          .map((a, b) => f"${b / a}%.2f")
          .mkString(" ")})"
    )

  private def k1Chunks(limit: Int): Vector[(Array[Byte], Array[Byte])] =
    val dir = Path.of(sys.env.getOrElse("SCARAB_K1_DB", "/nonexistent")).resolve("immutable")
    val stems = Files
      .list(dir)
      .iterator
      .asScala
      .map(_.getFileName.toString)
      .filter(_.endsWith(".secondary"))
      .map(_.stripSuffix(".secondary"))
      .toVector
      .sorted
      .take(limit)
    stems.map(s =>
      (
        Files.readAllBytes(dir.resolve(s"$s.primary")),
        Files.readAllBytes(dir.resolve(s"$s.secondary"))
      )
    )

  test("bench: Haskell secondary and primary index files of the K1 Preview run") {
    assume(enabled && sys.env.contains("SCARAB_K1_DB"), "SCARAB_BENCH / SCARAB_K1_DB not set")
    val chunks = k1Chunks(3000)
    val entries = chunks.map(_._2.length / 56L).sum
    val offsets = chunks.map(c => (c._1.length - 1) / 4L).sum
    val slots = ChunkSlots.of(4320).get
    println(s"BENCH data: ${chunks.size} chunks, $entries secondary entries, $offsets offsets")
    compare("haskell secondary entries (ns/entry)", entries)(
      chunks
        .map(c => LegacyFormats.HaskellSecondary.parse(c._2, true).toOption.fold(0L)(_.size.toLong))
        .sum
    )(chunks.map(c => SecondaryIndex.parse(c._2, true).toOption.fold(0L)(_.size.toLong)).sum)
    compare("haskell primary offsets (ns/offset)", offsets)(
      chunks
        .map(c =>
          LegacyFormats.HaskellPrimary
            .parse(c._1, slots, true)
            .toOption
            .fold(0L)(_.offsets.size.toLong)
        )
        .sum
    )(
      chunks
        .map(c => PrimaryIndex.parse(c._1, slots, true).toOption.fold(0L)(_.offsets.size.toLong))
        .sum
    )
  }
