// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.io.{ByteArrayOutputStream, PrintStream}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import cats.effect.IO
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.SchedulingStats
import xyz.kd5ujc.scarab.node.JsonLog

/** The log lines of today's `JsonLog`, one per sample, with the time stamp fixed. */
class LogGoldenSuite extends CatsEffectSuite:
  import LogSamples.*

  private val golden = Path.of("fixtures/log/golden.jsonl")

  private def capture(run: IO[Unit]): IO[List[String]] =
    IO.blocking {
      val out = new ByteArrayOutputStream()
      val saved = System.out
      System.setOut(new PrintStream(out, true, UTF_8))
      (out, saved)
    }.flatMap { (out, saved) =>
      run.guarantee(IO.blocking(System.setOut(saved))) *>
        IO(new String(out.toByteArray, UTF_8).linesIterator.toList)
    }

  private val stamp = "\"at\":\"[^\"]*\"".r
  private val build = "\"build\":\\{[^}]*\\}".r
  private def fixed(line: String) =
    build.replaceFirstIn(
      stamp.replaceFirstIn(line, "\"at\":\"2026-10-16T12:00:00Z\""),
      "\"build\":{\"version\":\"0.1.0\",\"scalaVersion\":\"3.3.8\",\"sbtVersion\":\"1.10.7\",\"gitCommit\":\"0123abcd\"}"
    )

  private val old: IO[Unit] =
    for
      sched <- SchedulingStats.create[IO]
      _ <- sched.timer(70L) *> sched.step("rules", 40000000L)
      logged = JsonLog.events(Some(sched))
      _ <- JsonLog.started(peers, setup.genesis, setup.config, Some(topology))
      _ <- JsonLog.started(peers, setup.genesis)
      _ <- blstReports.traverse_(JsonLog.blst)
      _ <- errors.traverse_(JsonLog.error)
      _ <- JsonLog.memory(memory)
      _ <- mirrorOutcomes.traverse_(o => JsonLog.mirrorChecked(header, BigInt(3), o, 25L))
      _ <- divergences.traverse_(d => JsonLog.rulesDiverged(d, None))
      _ <- divergences.traverse_(JsonLog.rulesHalted)
      _ <- differentialEvents.traverse_(JsonLog.differential)
      _ <- JsonLog.differentialSummary(stats)
      _ <- JsonLog.listening("0.0.0.0:3001")
      _ <- JsonLog.headersChecked(50000L, 12L, 3L)
      _ <- JsonLog.inbound("/tmp/node.socket", "accepted")
      _ <- JsonLog.inbound("10.0.0.1:3001", "reused", Some("outbound served by inbound"))
      _ <- JsonLog.chainSyncRounds(200L, 25600L, 120L, 900L, 128)
      _ <- JsonLog.slowFetch(300L)
      _ <- inbound.traverse_(JsonLog.inboundEvent("10.0.0.1:3001", _))
      _ <- connection.traverse_(JsonLog.connection)
      _ <- mempool.traverse_(JsonLog.mempool.emit)
      _ <- forge.traverse_(JsonLog.forge.emit)
      _ <- follower.traverse_(logged.emit)
      _ <- follower.traverse_(JsonLog.events().emit)
      _ <- storage.traverse_(JsonLog.storage.emit)
      _ <- JsonLog.finished(
        xyz.kd5ujc.scarab.consensus.VolatileChain.genesis(2160, setup.start, setup.origin)
      )
    yield ()

  test("every event renders as the golden says") {
    capture(old).map(_.map(fixed)).map { lines =>
      if !Files.exists(golden) then
        Files.createDirectories(golden.getParent)
        Files.writeString(golden, lines.mkString("", "\n", "\n"), UTF_8)
      assertEquals(lines, Files.readString(golden, UTF_8).linesIterator.toList)
    }
  }
