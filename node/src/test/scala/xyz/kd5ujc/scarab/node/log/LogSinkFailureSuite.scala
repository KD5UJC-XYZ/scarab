// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.log

import java.io.IOException
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{DirectoryNotEmptyException, Files, Path}
import scala.jdk.CollectionConverters.*
import scala.concurrent.duration.*
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Queue
import cats.effect.testkit.TestControl
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

  private def extended(n: Int): FollowerEvent.Extended =
    new FollowerEvent.Extended(block.copy(slot = n), BigInt(0), Some(tip), Era.Conway)

  private def emit(log: Log[IO], slots: Range): IO[Unit] =
    given Log[IO] = log
    slots.toList.traverse_(n => Log.follower[IO](None).emit(extended(n)))

  /** The slot of each line; a line that is not an `extended` line fails the test. */
  private def slots(texts: List[String]): List[Int] =
    texts.map(l =>
      parse(l)
        .flatMap(_.hcursor.downField("block").get[Int]("slot"))
        .fold(e => fail(s"not an extended line: $l ($e)"), identity)
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
      seen <- Queue.unbounded[IO, SinkReport]
      // the failing sink first, so that its failure would stop the others if it escaped
      failing = (text: String) =>
        fault.get.ifM(
          failure,
          failingTook.update(_ :+ text).as(().asRight[SinkError])
        )
      other = (text: String) => otherTook.update(_ :+ text).as(().asRight[SinkError])
      outputs = List((Sink.File, Format.Json, failing), (Sink.Stdout, Format.Json, other))
      _ <- LogRouter.routed[IO](settings, outputs, seen.offer, 64, 1.minute).use { log =>
        for
          _ <- emit(log, 0 until 3)
          first <- seen.take
          _ = assertEquals(first, SinkReport.Failed(Sink.File, SinkError.Write(full)))
          _ <- fault.set(false)
          _ <- emit(log, 3 until 5)
          _ <- fault.set(true)
          _ <- emit(log, 5 until 7)
        yield ()
      }
      took <- failingTook.get
      others <- otherTook.get
      reported <- seen.tryTakeN(None)
    yield
      assertEquals(slots(others.toList), (0 until 7).toList)
      assertEquals(slots(took.toList), List(3, 4))
      assertEquals(
        reported,
        List(
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
      _ <- LogRouter.reporting[IO](fileSettings(file), r => reports.update(_ :+ r), 1.minute).use {
        log =>
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
    assertEquals(
      SinkReport.Lost(4).text,
      "scarab: 4 reports of the log's failing sinks were lost while earlier ones waited to be written"
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

  private def fileSettings(file: Path, keep: Int = 1) = settings.copy(
    stdout = None,
    file = Some(FileSink(file, Format.Json, maxBytes = 1024, keep = keep))
  )

  private val at = java.time.Instant.parse("2026-11-24T12:00:00Z")

  /** The lines of `slots`, all made at one time, so that two runs of the same slots write the same
    * bytes and turn their files over at the same lines.
    */
  private def tellAll(log: Log[IO], slots: Seq[Int]): IO[Unit] =
    slots.toList.traverse_(n =>
      log.tell(Traced[Traced.FollowerLine].line(extended(n)).copy(time = Some(at)))
    )

  /** The lines of `node.jsonl` and of each rotated file up to one past `keep`, when it exists. */
  private def contents(dir: Path, keep: Int): IO[List[Option[List[String]]]] = IO.blocking(
    (dir.resolve("node.jsonl") :: (1 to keep + 1).toList.map(i => dir.resolve(s"node.jsonl.$i")))
      .map(f => Option.when(Files.exists(f))(lines(f)))
  )

  /** What a run without a fault writes from `slots`. */
  private def healthy(slots: Seq[Int], keep: Int): IO[List[Option[List[String]]]] =
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      _ <- LogRouter
        .reporting[IO](fileSettings(dir.resolve("node.jsonl"), keep), _ => IO.unit, 1.minute)
        .use(tellAll(_, slots))
      written <- contents(dir, keep)
    yield written

  test("a rotation that failed after some moves takes up where it stopped, and discards no file") {
    val fault = new java.util.concurrent.atomic.AtomicBoolean(false)
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      // the rotated files move up; the file itself cannot move while the fault holds
      calls = new FileCalls:
        def move(from: Path, to: Path): Unit =
          if fault.get && from == file then throw new IOException("the file cannot move")
          else FileCalls.real.move(from, to)
        def open(sink: FileSink): RotatingFile.Open = FileCalls.real.open(sink)
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      filled <- LogRouter
        .reporting[IO](fileSettings(file, keep = 3), r => reports.update(_ :+ r), 1.minute, calls)
        .use { log =>
          for
            _ <- tellAll(log, 0 until 30)
            filled <- IO.blocking(Files.exists(dir.resolve("node.jsonl.3")))
            _ <- IO(fault.set(true)) *> tellAll(log, 30 until 40)
            // one line: the rotation it takes up, and no later one that would push its files out
            _ <- IO(fault.set(false)) *> tellAll(log, 40 until 41)
          yield filled
        }
      reported <- reports.get
      dropped = reported match
        case Vector(
              SinkReport.Failed(Sink.File, SinkError.Rotate(_)),
              SinkReport.Recovered(Sink.File, n)
            ) =>
          n.toInt
        case other => fail(s"reports: $other")
      written <- contents(dir, 3)
      // the lines taken: all but the ones dropped while the fault held
      expected <- healthy((0 until 40 - dropped) :+ 40, 3)
    yield
      assert(filled, "every rotated file was there before the fault")
      assert(dropped > 0)
      assertEquals(written, expected)
  }

  test("a rotation that cannot move the file leaves a sink that takes lines and turns over again") {
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      in <- blocked(dir)
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      seen <- LogRouter
        .reporting[IO](fileSettings(file), r => reports.update(_ :+ r), 1.minute)
        .use { log =>
          for
            _ <- tellAll(log, 0 until 20)
            before <- IO.blocking(slots(lines(file)))
            _ <- cleared(in)
            // the rotation the fault held, then more of them
            _ <- tellAll(log, 20 until 21)
            recovered <- contents(dir, 1)
            _ <- tellAll(log, 21 until 60)
          yield (before, recovered)
        }
      (before, recovered) = seen
      reported <- reports.get
      dropped = reported match
        case Vector(
              SinkReport.Failed(Sink.File, SinkError.Rotate(_: DirectoryNotEmptyException)),
              SinkReport.Recovered(Sink.File, n)
            ) =>
          n.toInt
        case other => fail(s"reports: $other")
      written <- contents(dir, 1)
      // what a run without the fault writes from the lines taken: all but the ones dropped
      taken = 0 until 20 - dropped
      expectedRecovered <- healthy(taken :+ 20, 1)
      expected <- healthy(taken ++ (20 until 60), 1)
    yield
      assert(dropped > 0 && dropped < 20, s"dropped $dropped")
      assertEquals(before, taken.toList)
      assertEquals(recovered, expectedRecovered)
      assertEquals(written, expected)
      written.flatten.foreach(f => slots(f))
  }

  test("a library's line on a failing sink is dropped and reported, and nothing escapes") {
    for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      _ <- blocked(dir)
      // a full file, so the first line turns it over
      _ <- IO.blocking(Files.writeString(file, "x" * 2048))
      reports <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      _ <- LogRouter.reporting[IO](fileSettings(file), r => reports.update(_ :+ r), 1.minute).use {
        _ =>
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

  /** A route that fails while `fault` holds. */
  private def faulty(fault: Ref[IO, Boolean]) =
    (
      Sink.File,
      Format.Json,
      (_: String) => fault.get.map(on => Either.cond(!on, (), SinkError.Write(full)))
    )

  test("a report writer that never returns holds neither what logs nor the log's release") {
    // under TestControl a release that waited for the writer would never end, and fail the test
    TestControl.executeEmbed(
      for
        fault <- Ref.of[IO, Boolean](false)
        stuck = (_: SinkReport) => IO.uncancelable(_ => IO.never[Unit])
        told <- LogRouter.routed[IO](settings, List(faulty(fault)), stuck, 2, 1.second).use { log =>
          (0 until 10).toList
            .traverse_(n => fault.set(n % 2 == 0) *> emit(log, n until n + 1))
            .as(10)
        }
      yield assertEquals(told, 10)
    )
  }

  test("reports that cannot wait are lost and counted, and the count is written") {
    for
      fault <- Ref.of[IO, Boolean](true)
      entered <- Deferred[IO, Unit]
      gate <- Deferred[IO, Unit]
      written <- Ref.of[IO, Vector[SinkReport]](Vector.empty)
      // the first report holds the writer until the gate opens
      write = (r: SinkReport) =>
        written.update(_ :+ r) *> entered.complete(()) *> IO.uncancelable(_ => gate.get)
      _ <- LogRouter.routed[IO](settings, List(faulty(fault)), write, 1, 1.minute).use { log =>
        for
          _ <- emit(log, 0 until 1)
          _ <- entered.get
          // queued: the recovery; lost: the next failure and recovery
          _ <- fault.set(false) *> emit(log, 1 until 2)
          _ <- fault.set(true) *> emit(log, 2 until 3)
          _ <- fault.set(false) *> emit(log, 3 until 4)
          _ <- gate.complete(())
        yield ()
      }
      reported <- written.get
    yield assertEquals(
      reported,
      Vector(
        SinkReport.Failed(Sink.File, SinkError.Write(full)),
        SinkReport.Lost(2),
        SinkReport.Recovered(Sink.File, 1)
      )
    )
  }

  test("a write under way at release closes the file it opened, and no later write opens it") {
    val armed = new java.util.concurrent.atomic.AtomicBoolean(false)
    val entered = new java.util.concurrent.CountDownLatch(1)
    val gate = new java.util.concurrent.CountDownLatch(1)
    val opened = new java.util.concurrent.atomic.AtomicReference[Option[RotatingFile.Open]](None)
    // the file the rotation opens is held open until the gate opens
    val calls = new FileCalls:
      def move(from: Path, to: Path): Unit = FileCalls.real.move(from, to)
      def open(file: FileSink): RotatingFile.Open =
        val open = FileCalls.real.open(file)
        if armed.get then
          opened.set(Some(open))
          entered.countDown()
          gate.await()
        open
    // the gate opens whatever the outcome, so that a failure does not hold a thread
    (for
      dir <- IO.blocking(Files.createTempDirectory("scarab-log"))
      file = dir.resolve("node.jsonl")
      allocated <- RotatingFile
        .resource[IO](FileSink(file, Format.Json, maxBytes = 16, keep = 1), calls)
        .allocated
      (rotating, release) = allocated
      first <- rotating.write("a line longer than the file")
      _ <- IO(armed.set(true))
      underWay <- rotating.write("the line that turns it over").start
      _ <- IO.blocking(entered.await())
      _ <- release
      _ <- IO.blocking(gate.countDown())
      second <- underWay.joinWithNever
      _ <- IO.blocking(Files.delete(file))
      late <- rotating.write("a line after the release")
      reopened <- IO.blocking(Files.exists(file))
    yield
      assertEquals(first, Right(()))
      assertEquals(second, Right(()))
      assert(opened.get.exists(!_.stream.getFD.valid), "the file opened under way is closed")
      assertEquals(late, Left(SinkError.Released))
      assert(!reopened, "a write after the release opened the file")
    ).guarantee(IO(gate.countDown()))
  }
