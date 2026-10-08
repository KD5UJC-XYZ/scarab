// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO}
import cats.syntax.all.*
import java.nio.file.Path
import lab.fetcher.*

object ChainFetchCommand:
  private val usage =
    "chain-fetch run --config FILE --output DIR [--resume] | chain-fetch inspect --output DIR [--verify-bytes]"
  private def execute(config: String, output: String, resume: Boolean): IO[ExitCode] =
    IO.blocking(LocalConfig.load(Path.of(config))).flatMap { cfg =>
      val acquisition = for
        source <- LocalBlockSource.open[IO](cfg)
        result <- NioSegmentStore
          .resource[IO](Path.of(output), cfg.spec, source.identity, resume)
          .use(store => Fetch.run(cfg.spec, source, store))
      yield Option(result)
      acquisition.timeoutTo(cfg.spec.limits.maxDuration, IO.pure(None)).flatMap {
        case Some(result) =>
          IO.println(result.json).as(if result.complete then ExitCode.Success else ExitCode(3))
        case None =>
          IO.println(
            """{"status":"incomplete","reason":"timeBudget","checkpoint":"inspect output to determine last committed block"}"""
          ).as(ExitCode(3))
      }
    }

  def run(args: List[String]): IO[ExitCode] =
    val action = args match
      case List("run", "--config", config, "--output", output) => execute(config, output, false)
      case List("run", "--config", config, "--output", output, "--resume") =>
        execute(config, output, true)
      case List("inspect", "--output", output)                   => inspect(output)
      case List("inspect", "--output", output, "--verify-bytes") => inspect(output)
      case _ => IO.println(usage).as(ExitCode(2))
    action.handleErrorWith { e =>
      val code = e match
        case f: FetchError                                    => f.code
        case _: java.nio.file.AtomicMoveNotSupportedException => 6
        case _                                                => 4
      IO.consoleForIO.errorln(s"chain-fetch error: ${e.getMessage}").as(ExitCode(code))
    }
  private def inspect(output: String): IO[ExitCode] =
    val limits = Limits
      .checked(4096, 1024 * 1024, Long.MaxValue, 1024L * 1024 * 1024, 20000, 120)
      .fold(m => throw new IllegalArgumentException(m), identity)
    NioSegmentStore.inspect[IO](Path.of(output), limits).flatMap { snapshot =>
      IO.println(FetchResult("inspected", snapshot, 0L).json).as(ExitCode.Success)
    }
