// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, Path}
import scala.jdk.CollectionConverters.*
import cats.effect.{IO, Ref}
import cats.syntax.all.*
import io.circe.parser.parse
import xyz.kd5ujc.scarab.consensus.FollowerEvent
import xyz.kd5ujc.scarab.protocol.Era

/** A sink that cannot take a line drops it, without failing what logged it: the other sinks still
  * take the line, the failure is reported once per streak, and the sink takes lines again when the
  * fault clears.
  */
class LogSinkFailureSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught:
  import LogSamples.{block, tip}

  private val settings = LogSettings()

  private def extended(n: Int) =
    FollowerEvent.Extended(block.copy(slot = n), BigInt(0), Some(tip), Era.Conway)

  private def emit(log: Log[IO], slots: Range): IO[Unit] =
    given Log[IO] = log
    slots.toList.traverse_(n => Log.follower[IO](None).emit(extended(n)))

  private def slots(texts: List[String]): List[Int] =
    texts.flatMap(l =>
      parse(l).toOption.flatMap(_.hcursor.downField("block").get[Int]("slot").toOption)
    )

  private def lines(p: Path): List[String] =
    if Files.exists(p) then Files.readAllLines(p, UTF_8).asScala.toList else Nil

  private val full = new IOException("No space left on device")

  /** A write that fails as a sink returns its failure, or as a write raises it. */
  private val failures: List[(String, IO[Either[SinkError, Unit]])] = List(
    "returned" -> IO.pure(SinkError.Write(full).asLeft[Unit]),
    "raised" -> IO.raiseError(full)
  )

  failures.foreach((how, failure) =>
    test(
      s"a sink that fails ($how) drops the line, the others take it, and the streak is reported once"
    ) {
      streak(failure)
    }
  )

  private def streak(failure: IO[Either[SinkError, Unit]]): IO[Unit] =
    for
      fault <- Ref.of[IO, Boolean](true)
      failingTook <- Ref.of[IO, Vector[String]](Vector.empty)
      otherTook <- Ref.of[IO, Vector[String]](Vector.empty)
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      // the failing sink first, so that its failure would stop the others if it escaped
      failing = (text: String) =>
        fault.get.ifM(
          failure,
          failingTook.update(_ :+ text).as(().asRight[SinkError])
        )
      other = (text: String) => otherTook.update(_ :+ text).as(().asRight[SinkError])
      outputs = List((Sink.File, Format.Json, failing), (Sink.Stdout, Format.Json, other))
      _ <- LogRouter.routed[IO](settings, outputs, r => reports.update(_ :+ r)).use { log =>
        for
          _ <- emit(log, 0 until 3)
          during <- reports.get
          _ = assertEquals(during, Vector(SinkReport.Failed(Sink.File, SinkError.Write(full))))
          _ <- fault.set(false)
          _ <- emit(log, 3 until 5)
          _ <- fault.set(true)
          _ <- emit(log, 5 until 7)
        yield ()
      }
      took <- failingTook.get
      others <- otherTook.get
      reported <- reports.get
    yield
      assertEquals(slots(others.toList), (0 until 7).toList)
      assertEquals(slots(took.toList), List(3, 4))
      assertEquals(
        reported,
        Vector(
          SinkReport.Failed(Sink.File, SinkError.Write(full)),
          SinkReport.Recovered(Sink.File, 3),
          SinkReport.Failed(Sink.File, SinkError.Write(full)),
          SinkReport.Closed(Sink.File, 2)
        )
      )

  test("a write that fails (a full disk) leaves a sink that opens its file again") {
    val devFull = java.nio.file.Paths.get("/dev/full")
    for
      _ <- IO.blocking(Files.isWritable(devFull)).map(assume(_, "no /dev/full"))
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      _ <- IO.blocking(Files.createSymbolicLink(file, devFull))
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      _ <- LogRouter.reporting[IO](fileSettings(file), r => reports.update(_ :+ r)).use { log =>
        emit(log, 0 until 3) *>
          IO.blocking {
            Files.delete(file)
            Files.createSymbolicLink(file, Files.createFile(dir.resolve("disk")))
          } *>
          emit(log, 3 until 5)
      }
      reported <- reports.get
      written <- IO.blocking(slots(lines(dir.resolve("disk"))))
    yield
      reported match
        case Vector(
              SinkReport.Failed(Sink.File, SinkError.Write(_)),
              SinkReport.Recovered(Sink.File, 3)
            ) =>
          ()
        case other => fail(s"reports: $other")
      assertEquals(written, List(3, 4))
  }

  test("the reports on stderr") {
    assertEquals(
      SinkReport.Failed(Sink.File, SinkError.Write(full)).text,
      "scarab: the log's file sink could not write a line (java.io.IOException: No space left on device); its lines are dropped until it takes one again"
    )
    assertEquals(
      SinkReport.Recovered(Sink.File, 3).text,
      "scarab: the log's file sink takes lines again, after dropping 3"
    )
    assertEquals(
      SinkReport.Closed(Sink.File, 2).text,
      "scarab: the log closed with its file sink failing, after it dropped 2 lines"
    )
  }

  /** `node.jsonl.1` a directory with a file in it: turning the file over cannot replace it. */
  private def blocked(dir: Path): IO[Path] = IO.blocking {
    val in = Files.createDirectories(dir.resolve("node.jsonl.1"))
    Files.writeString(in.resolve("held"), "held")
    in
  }

  private def cleared(in: Path): IO[Unit] = IO.blocking {
    Files.delete(in.resolve("held"))
    Files.delete(in)
  }

  private def fileSettings(file: Path) = settings.copy(
    stdout = None,
    file = Some(FileSink(file, Format.Json, maxBytes = 1024, keep = 1))
  )

  test("a rotation that cannot move the file leaves a sink that takes lines and turns over again") {
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      in <- blocked(dir)
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      before <- LogRouter.reporting[IO](fileSettings(file), r => reports.update(_ :+ r)).use {
        log =>
          for
            _ <- emit(log, 0 until 20)
            before <- IO.blocking(slots(lines(file)))
            during <- reports.get
            _ = during match
              case Vector(
                    SinkReport.Failed(Sink.File, SinkError.Rotate(_: DirectoryNotEmptyException))
                  ) =>
                ()
              case other => fail(s"reports while the rotation fails: $other")
            _ <- cleared(in)
            _ <- emit(log, 20 until 60)
          yield before
      }
      reported <- reports.get
      files = List(dir.resolve("node.jsonl.1"), file)
      kept <- IO.blocking(files.map(f => f -> Files.size(f)))
      after <- IO.blocking(slots(files.flatMap(lines)))
    yield
      // the lines before the fault are in the file, the ones while it held are dropped
      assert(before.nonEmpty)
      assertEquals(before, (0 until before.size).toList)
      assertEquals(
        reported,
        Vector(reported.head, SinkReport.Recovered(Sink.File, (20 - before.size).toLong))
      )
      // after it, the file turns over again and keeps the newest lines, with none missing
      kept.foreach((f, size) => assert(size <= 1024, s"$f: $size bytes"))
      assert(!Files.exists(dir.resolve("node.jsonl.2")))
      assert(after.nonEmpty)
      assertEquals(after, (60 - after.size until 60).toList)
  }

  test("a library's line on a failing sink is dropped and reported, and nothing escapes") {
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      _ <- blocked(dir)
      // a full file, so the first line turns it over
      _ <- IO.blocking(Files.writeString(file, "x" * 2048))
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      _ <- LogRouter.reporting[IO](fileSettings(file), r => reports.update(_ :+ r)).use { _ =>
        IO.blocking(org.slf4j.LoggerFactory.getLogger("org.http4s.test").warn("dropped"))
      }
      reported <- reports.get
      size <- IO.blocking(Files.size(file))
    yield
      reported match
        case Vector(
              SinkReport.Failed(Sink.File, SinkError.Rotate(_: DirectoryNotEmptyException)),
              SinkReport.Closed(Sink.File, 1)
            ) =>
          ()
        case other => fail(s"reports: $other")
      assertEquals(size, 2048L)
  }
