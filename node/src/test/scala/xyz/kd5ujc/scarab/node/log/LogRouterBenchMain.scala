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
  * silenced (`silenced`), where the route filter drops the line before the lock, the clock, the
  * render and the write. The difference between `writers = 1` and more writers includes the
  * contention the lock causes, the fiber dispatch, and the writers sharing cores for the render;
  * what is lost between `file` and `silenced` is the render, the write, the lock and the clock
  * together, which this harness does not separate.
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

  /** `writers` fibers, each writing its share of `lines`, started one per fiber after the clock is
    * read, so the starts fall inside the timing and the `tell` calls overlap on the lock. The sink
    * is emptied first, so the digest taken after a round is that round's bytes.
    */
  private def round(s: LogSettings, w: Int, file: Path): IO[Long] =
    IO.blocking(Files.deleteIfExists(file)) *> LogRouter.resource[IO](s).use { log =>
      val per = lines / w
      // One IO per writer, each started on its own fiber, so the tells overlap on the lock.
      val loops = List.fill(w)((0 until per).toList.traverse_(_ => log.tell(line)))
      for
        began <- IO.monotonic
        fibers <- loops.traverse(_.start)
        _ <- fibers.traverse_(_.joinWithNever)
        took <- IO.monotonic.map(_ - began)
      yield took.toNanos
    }

  private def bytes(file: Path): IO[String] =
    IO.blocking(s"${if Files.exists(file) then Files.size(file) else 0L}")

  /** One warm-up round, then two timed rounds of which the faster is reported. `round` empties the
    * sink, so each round measures an empty file, and the digest printed once for a configuration is
    * the last of its rounds — one round's bytes, not a sum over them.
    */
  private def measure(name: String, s: LogSettings, w: Int, file: Path): IO[Unit] =
    round(s, w, file) *> List.fill(2)(()).traverse(_ => round(s, w, file)).map { times =>
      val nanos = times.min
      // The lines the round actually wrote, which is `lines` whenever the writer count divides it.
      val total = lines / w * w
      val us = nanos.toDouble / total / 1000.0
      val rate = total.toDouble / (nanos.toDouble / 1e9)
      println(
        f"METRIC config=$name writers=$w lines=$total us_per_line=$us%.3f lines_per_s=$rate%.0f"
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
        // each round starts from an empty file, so a configuration's rounds are independent
        val s = settings(file, silenced = name == "silenced")
        writers.traverse_(w => measure(name, s, w, file)) *>
          bytes(file).map(n => println(s"DIGEST config=$name bytes=$n"))
      }
    }
