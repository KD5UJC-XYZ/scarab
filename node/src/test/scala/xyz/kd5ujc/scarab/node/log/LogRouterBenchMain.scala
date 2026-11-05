// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.file.{Files, Path}
import cats.effect.{IO, IOApp}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent}
import xyz.kd5ujc.scarab.protocol.{ChainSync, Era}

/** What the router's one sink lock costs: `tell` throughput with several fibers writing at once,
  * against a file sink that admits every line (`file`), and against the same sink with every line
  * silenced (`silenced`), which leaves the lock and the clock with no render and no write. What is
  * lost between `writers = 1` and more writers is the contention the lock causes; what is lost
  * between `file` and `silenced` is the render and the write.
  *
  * Not part of `check`: run it with `bash scripts/sbtw "node/Test/runMain
  * xyz.kd5ujc.scarab.node.log.LogRouterBenchMain"`. It prints a `METRIC` line per configuration and
  * writer count, and a `DIGEST` line per configuration with the bytes that reached the file, so a
  * reader can see the work happened rather than trust the timing. The figures are recorded in
  * `docs/performance/logging-and-the-router.md`.
  */
object LogRouterBenchMain extends IOApp.Simple:
  /** The lines one round writes, all writers together. */
  private val lines = 240000

  /** The writer counts a round uses. */
  private val writers = List(1, 4, 16)

  /** A real line: the per-block `Extended` event, through the router's own renderer. The block and
    * the tip are a plain thirty-two-byte hash and a slot, so the harness reads no fixture.
    */
  private val hash: Bytes = Bytes.fromHex("ab" * 32).fold(e => sys.error(e.toString), identity)

  private val block: BlockRef = BlockRef(BigInt(4200), hash, BigInt(17))

  private val extended: Traced.FollowerLine =
    FollowerEvent.Extended(
      block,
      BigInt(0),
      ChainSync.Tip(block.point, ChainSync.UInt64.saturating(17)),
      Era.Conway
    )

  private val line: Line = Traced[Traced.FollowerLine].line(extended)

  private def settings(file: Path, silenced: Boolean): LogSettings =
    LogSettings(
      stdout = None,
      stderr = None,
      file = Some(FileSink(file, Format.Json, maxBytes = 1L << 30, keep = 1)),
      severity = if silenced then Map((Sink.File, Scope.All) -> Threshold.Silence) else Map.empty
    )

  /** `writers` fibers, each writing its share of `lines`, all started before the clock is read. */
  private def round(s: LogSettings, w: Int): IO[Long] =
    LogRouter.resource[IO](s).use { log =>
      val per = lines / w
      val work =
        (0 until w).toList.traverse(_ => (0 until per).toList.traverse_(_ => log.tell(line)))
      for
        began <- IO.monotonic
        fibers <- work.start
        _ <- fibers.joinWithNever
        took <- IO.monotonic.map(_ - began)
      yield took.toNanos
    }

  private def bytes(file: Path): IO[String] =
    IO.blocking(if Files.exists(file) then s"${Files.size(file)}" else "none")

  /** One warm-up round, then two timed rounds of which the faster is reported. */
  private def measure(name: String, s: LogSettings, w: Int): IO[Unit] =
    round(s, w) *> List.fill(2)(()).traverse(_ => round(s, w)).map { times =>
      val nanos = times.min
      val us = nanos.toDouble / lines / 1000.0
      val rate = lines.toDouble / (nanos.toDouble / 1e9)
      println(
        f"METRIC config=$name writers=$w lines=$lines us_per_line=$us%.3f lines_per_s=$rate%.0f"
      )
      ()
    }

  def run: IO[Unit] =
    IO.blocking(Files.createTempDirectory("scarab-logrouter-bench")).flatMap { dir =>
      val configs = List(
        "file" -> dir.resolve("file.jsonl"),
        "silenced" -> dir.resolve("silenced.jsonl")
      )
      configs.traverse_ { (name, file) =>
        // each round opens the file afresh, so a configuration's rounds are independent
        val s = settings(file, silenced = name == "silenced")
        writers
          .traverse_(w => IO.blocking(Files.deleteIfExists(file)) *> measure(name, s, w)) *>
          bytes(file).map(n => println(s"DIGEST config=$name bytes=$n"))
      }
    }
