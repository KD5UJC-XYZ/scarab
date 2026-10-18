// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import cats.effect.IO
import scala.concurrent.duration.*
import cats.syntax.all.*
import io.circe.parser.parse
import xyz.kd5ujc.scarab.ledger.ConfigProblem
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.consensus.FollowerEvent
import xyz.kd5ujc.scarab.ledger.PeerError
import xyz.kd5ujc.scarab.protocol.Era

/** The settings the flags give, and what the router lets through to which sink. */
class LogRouterSuite extends CatsEffectSuite:
  import LogSamples.{block, tip}

  private def settings(flags: (String, String)*): LogSettings =
    val many = flags.groupMap(_._1)(_._2).view.mapValues(_.toList).toMap
    LogSettings.parse(many.view.mapValues(_.last).toMap, many).fold(e => fail(e.message), identity)

  private def refused(flags: (String, String)*): ConfigProblem =
    val many = flags.groupMap(_._1)(_._2).view.mapValues(_.toList).toMap
    LogSettings
      .parse(many.view.mapValues(_.last).toMap, many)
      .fold(_.problem, s => fail(s"accepted: $s"))

  test("no flag: every line as JSON on stdout, nothing else") {
    val s = settings()
    assertEquals(s.sinks, List(Sink.Stdout -> Format.Json))
    assertEquals(
      EventKind.values.toList.map(s.threshold(Sink.Stdout, _)).distinct,
      List(Threshold.AtLeast(Severity.Debug))
    )
    assertEquals(EventKind.values.toList.flatMap(s.sampling(Sink.Stdout, _)), Nil)
  }

  test("the most specific namespace that sets a threshold or a sampling wins") {
    val s = settings(
      "log-stderr" -> "human",
      "log-severity" -> "Chain=Warning",
      "log-severity" -> "stdout:Chain.Extended=Info",
      "log-severity" -> "stderr:*=Error",
      "log-every" -> "stdout:Chain.Extended=1000"
    )
    assertEquals(s.threshold(Sink.Stdout, EventKind.Extended), Threshold.AtLeast(Severity.Info))
    assertEquals(s.threshold(Sink.Stdout, EventKind.Epoch), Threshold.AtLeast(Severity.Warning))
    assertEquals(s.threshold(Sink.Stdout, EventKind.Started), Threshold.AtLeast(Severity.Debug))
    // the area beats the root on stderr too
    assertEquals(s.threshold(Sink.Stderr, EventKind.Epoch), Threshold.AtLeast(Severity.Warning))
    assertEquals(s.threshold(Sink.Stderr, EventKind.Started), Threshold.AtLeast(Severity.Error))
    assertEquals(
      s.sampling(Sink.Stdout, EventKind.Extended),
      Some(Scope.Of(EventKind.Extended) -> 1000L)
    )
    assertEquals(s.sampling(Sink.Stderr, EventKind.Extended), None)
  }

  test("a namespace, a sink, a severity or a size that does not exist is refused") {
    assertEquals(refused("log-severity" -> "Chain.Extnded=Info"), ConfigProblem.LogNamespace("Chain.Extnded"))
    assertEquals(refused("log-severity" -> "disk:Chain=Info"), ConfigProblem.LogSink("disk"))
    assertEquals(refused("log-severity" -> "Chain=Loud"), ConfigProblem.FlagValue("log-severity", "Loud"))
    assertEquals(refused("log-every" -> "Chain=0"), ConfigProblem.FlagValue("log-every", "0"))
    assertEquals(refused("log-file-keep" -> "3"), ConfigProblem.FlagDependency(Set("log-file-format", "log-file-size", "log-file-keep"), "log-file"))
    assertEquals(refused("log-file" -> "x", "log-file-size" -> "12"), ConfigProblem.FlagValue("log-file-size", "12"))
    assertEquals(refused("log-stdout" -> "xml"), ConfigProblem.FlagValue("log-stdout", "xml"))
  }

  test("sizes in bytes, K, M and G") {
    assertEquals(LogSettings.bytes("2048"), Some(2048L))
    assertEquals(LogSettings.bytes("64K"), Some(65536L))
    assertEquals(LogSettings.bytes("10M"), Some(10485760L))
    assertEquals(LogSettings.bytes("1G"), Some(1073741824L))
    assertEquals(LogSettings.bytes("1T"), None)
  }

  private def tempDir: IO[Path] = IO.blocking(Files.createTempDirectory("scarab-log"))
  private def lines(p: Path): List[String] =
    if Files.exists(p) then Files.readAllLines(p, UTF_8).asScala.toList else Nil
  private def events(p: Path): List[String] =
    lines(p).flatMap(l => parse(l).toOption.flatMap(_.hcursor.get[String]("event").toOption))

  private def extended(n: Int) =
    FollowerEvent.Extended(block.copy(slot = n), BigInt(0), tip, Era.Conway)

  test("a sampled namespace keeps the first line of every N, the others all of theirs") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val s = settings(
        "log-stdout" -> "off",
        "log-file" -> file.toString,
        "log-every" -> "file:Chain.Extended=3"
      )
      LogRouter
        .resource[IO](s)
        .use { log =>
          given Log[IO] = log
          val follower = Log.follower[IO](None)
          (0 until 10).toList.traverse_(n => follower.emit(extended(n))) *>
            follower.emit(FollowerEvent.Reconnecting(PeerError.NoIntersection, 1))
        }
        .map { _ =>
          val slots = lines(file).flatMap(l =>
            parse(l).toOption.flatMap(_.hcursor.downField("block").get[Int]("slot").toOption)
          )
          assertEquals(slots, List(0, 3, 6, 9))
          assertEquals(events(file).last, "reconnecting")
        }
    }
  }

  test("a threshold drops the lines below it and Silence drops all of them") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val s = settings(
        "log-stdout" -> "off",
        "log-file" -> file.toString,
        "log-severity" -> "Chain=Warning",
        "log-severity" -> "Node=Silence"
      )
      LogRouter
        .resource[IO](s)
        .use { log =>
          given Log[IO] = log
          Log.follower[IO](None).emit(extended(1)) *>
            Log.follower[IO](None).emit(FollowerEvent.Reconnecting(PeerError.NoIntersection, 1)) *>
            log.emit[NodeEvent](NodeEvent.Listening("x")) *>
            log.emit[NodeEvent](NodeEvent.SlowFetch(1))
        }
        .map(_ => assertEquals(events(file), List("reconnecting")))
    }
  }

  test("the file turns over at its size and keeps the newest files") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val s = settings(
        "log-stdout" -> "off",
        "log-file" -> file.toString,
        "log-file-size" -> "1K",
        "log-file-keep" -> "2"
      )
      LogRouter
        .resource[IO](s)
        .use { log =>
          given Log[IO] = log
          (0 until 100).toList.traverse_(n => Log.follower[IO](None).emit(extended(n)))
        }
        .map { _ =>
          val files = List(file, dir.resolve("node.jsonl.1"), dir.resolve("node.jsonl.2"))
          files.foreach(f => assert(Files.size(f) <= 1024, s"$f: ${Files.size(f)} bytes"))
          assert(!Files.exists(dir.resolve("node.jsonl.3")))
          val slots = files.reverse
            .flatMap(lines)
            .flatMap(l =>
              parse(l).toOption.flatMap(_.hcursor.downField("block").get[Int]("slot").toOption)
            )
          // the newest lines, in order, with none missing
          assertEquals(slots, (100 - slots.size until 100).toList)
        }
    }
  }

  test("a restart appends to the file") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val s = settings("log-stdout" -> "off", "log-file" -> file.toString)
      val once = LogRouter.resource[IO](s).use { log =>
        log.emit[NodeEvent](NodeEvent.Listening("x"))
      }
      (once *> once).map(_ => assertEquals(events(file), List("listening", "listening")))
    }
  }

  test("the human format: time, namespace, severity, event and fields") {
    val line = summon[Traced[NodeEvent]].line(
      NodeEvent.Inbound("10.0.0.1:3001", xyz.kd5ujc.scarab.node.InboundEvent.Refused("limit"))
    )
    assertEquals(
      Render.human(line, java.time.Instant.parse("2026-10-16T12:00:00Z")),
      "[2026-10-16T12:00:00Z][Net.Inbound](Warning) inbound remote=10.0.0.1:3001 state=refused reason=limit"
    )
  }

  test("SLF4J finds the node's backend, and a library's line goes to the sinks as Library.Slf4j") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val s = settings(
        "log-stdout" -> "off",
        "log-file" -> file.toString,
        "log-severity" -> "Library=Info"
      )
      LogRouter
        .resource[IO](s)
        .use { _ =>
          IO.blocking {
            val logger = org.slf4j.LoggerFactory.getLogger("org.http4s.test")
            logger.debug("not kept")
            logger.warn("hello {}", "world")
          }
        }
        .map { _ =>
          assert(org.slf4j.LoggerFactory.getLogger("any").isInstanceOf[LibraryLogger])
          val kept = lines(file).flatMap(parse(_).toOption).map(_.hcursor)
          assertEquals(kept.flatMap(_.get[String]("message").toOption), List("hello world"))
          assertEquals(kept.flatMap(_.get[String]("level").toOption), List("Warning"))
          assertEquals(kept.flatMap(_.get[String]("logger").toOption), List("org.http4s.test"))
        }
    }
  }

  test("the runtime's starvation warning goes to the sinks as Node.CpuStarvation") {
    tempDir.flatMap { dir =>
      val file = dir.resolve("node.jsonl")
      val event = NodeEvent.CpuStarvation(250.millis, 0.1, 1.second)
      LogRouter
        .resource[IO](settings("log-stdout" -> "off", "log-file" -> file.toString))
        .use(_ => IO.blocking(LibraryLogs.report(event, Severity.Warning, "not text")))
        .map { _ =>
          val kept = lines(file).flatMap(parse(_).toOption).map(_.hcursor)
          assertEquals(kept.flatMap(_.get[String]("event").toOption), List("cpuStarvation"))
          assertEquals(kept.flatMap(_.get[Long]("clockDriftMs").toOption), List(250L))
        }
    }
  }
