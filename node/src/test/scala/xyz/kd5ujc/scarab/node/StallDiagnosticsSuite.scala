// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.Files
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Supervisor
import cats.syntax.all.*
import scala.jdk.CollectionConverters.*
import cats.effect.testkit.TestControl
import com.comcast.ip4s.*
import io.circe.Json
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.node.BlockAdmissions.{AdmissionStage, WorkKind}
import xyz.kd5ujc.scarab.node.log.{NodeEvent, Traced}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The state a stall leaves in the log (issue 371): a simulated stall of a watchdog reads the
  * coordinator, a session's pipeline probe and the admissions, and the `stallState` event carries
  * what they held.
  */
class StallDiagnosticsSuite extends CatsEffectSuite:
  private val stalledLine = NodeEvent.Stalled(10.minutes, BigInt(100), BigInt(5000))

  private def at(slot: Long, hash: Int): ChainSync.Point =
    ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), Bytes.wrap(Array(hash.toByte)))

  private val pipeline = Peer.PipelineState(
    peer = SocketAddress(host"127.0.0.1", port"3001"),
    permitsFree = 1,
    permits = 3,
    queued = 2,
    candidateHeaders = 40,
    unpublishedHeaders = Some(7),
    capacityRoom = false,
    candidateBase = ChainSync.Point.Origin,
    candidateTip = at(900, 0xcd),
    publishedBase = ChainSync.Point.Origin,
    publishedTip = at(840, 0xab),
    candidateTipSlot = Some(900L),
    publishedTipSlot = Some(840L),
    lastReplyCollectedAgo = Some(9.minutes + 2.seconds),
    lastReplyAcceptedAgo = Some(9.minutes),
    lastReplyHandledAgo = Some(9.minutes + 1.second)
  )

  private def fields(event: NodeEvent): Map[String, Json] =
    summon[Traced[NodeEvent]].line(event).fields.toMap

  test("a stall reports each session's pipeline, the coordinator's entries and the admissions") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      BlockAdmissions.create[IO].flatMap { admissions =>
        coordinator.session(7L).use { session =>
          TestControl.executeEmbed(
            for
              _ <- session.observed(IO.pure(pipeline))
              logged <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
              reporter <- StallDiagnostics.Reporter
                .create[IO](None, e => logged.update(_ :+ e), identity)
              _ <- reporter.install(StallDiagnostics.Sources(coordinator, admissions))
              dog <- StallWatchdog.create[IO](
                BigInt(100),
                10.minutes,
                None,
                {
                  case StallWatchdog.Change.Began(_, _, _) =>
                    reporter.onStall(stalledLine)
                  case _ => IO.unit
                }
              )
              _ <- dog.announced(BigInt(5000))
              _ <- IO.sleep(9.minutes) *> dog.check
              early <- logged.get
              _ <- IO.sleep(2.minutes) *> dog.check *> dog.check
              events <- logged.get
            yield
              assertEquals(early, Vector.empty)
              assert(
                events match
                  case Vector(_: NodeEvent.Stalled, _: NodeEvent.StallState) => true
                  case _                                                     => false
              )
              val f = fields(events.last)
              val sessions = f("sessions").asArray.get
              assertEquals(sessions.size, 1)
              val s = sessions.head.hcursor
              assertEquals(s.get[Long]("session"), Right(session.identity))
              assertEquals(s.get[Boolean]("busy"), Right(false))
              assertEquals(s.get[Int]("pendingRequests"), Right(0))
              assertEquals(s.get[Int]("pendingHeaders"), Right(0))
              assertEquals(s.downField("active").focus, Some(Json.arr()))
              val p = s.downField("pipeline")
              assertEquals(p.get[String]("peer"), Right("127.0.0.1:3001"))
              assertEquals(p.get[Long]("permitsFree"), Right(1L))
              assertEquals(p.get[Int]("permits"), Right(3))
              assertEquals(p.get[Int]("queued"), Right(2))
              assertEquals(p.get[Int]("candidateHeaders"), Right(40))
              assertEquals(p.get[Int]("unpublishedHeaders"), Right(7))
              assertEquals(p.get[Boolean]("capacityRoom"), Right(false))
              assertEquals(p.get[Long]("candidateTipSlot"), Right(900L))
              assertEquals(p.get[Long]("publishedTipSlot"), Right(840L))
              assertEquals(p.get[Long]("lastReplyCollectedAgoMs"), Right(542000L))
              assertEquals(p.get[Long]("lastReplyAcceptedAgoMs"), Right(540000L))
              assertEquals(p.get[String]("candidateTip"), Right("900:cd"))
              assertEquals(p.get[String]("publishedTip"), Right("840:ab"))
              assertEquals(p.get[Long]("lastReplyHandledAgoMs"), Right(541000L))
              assertEquals(
                f("admissions"),
                Json.obj(
                  "capacity" -> Json.fromInt(10),
                  "held" -> Json.fromInt(0),
                  "queued" -> Json.fromInt(0),
                  "worker" -> Json.Null,
                  "publishWaiting" -> Json.fromInt(0),
                  "oldestPublishWaitMs" -> Json.Null
                )
              )
              assertEquals(f("fiberDump"), Json.Null)
              assertEquals(f("decisionLock"), Json.Null)
          )
        }
      }
    }
  }

  test("a session without a pipeline probe reports none") {
    FetchCoordinator.create[IO]().flatMap { coordinator =>
      coordinator
        .session(1L)
        .use(_ =>
          coordinator.diagnostics
            .map(sessions => assertEquals(sessions.map(_.pipeline), Vector(None)))
        )
    }
  }

  test("the fiber dump is written to the directory named, one file per stall") {
    IO.blocking(Files.createTempDirectory("stall-dump")).flatMap { dir =>
      StallDiagnostics
        .fiberDump[IO](dir.resolve("nested"), 1)
        .flatMap {
          case StallDiagnostics.Dump.Written(path) =>
            IO.blocking(assert(Files.isRegularFile(path), path.toString))
              .as(path.getFileName.toString)
          case other => IO.raiseError(new AssertionError(s"not written: $other"))
        }
        .map(name => assert(name.matches("fiber-dump-\\d+-1\\.txt"), name))
        .guarantee(IO.blocking {
          val nested = dir.resolve("nested")
          Files.list(nested).forEach(f => Files.deleteIfExists(f))
          Files.deleteIfExists(nested)
          Files.deleteIfExists(dir)
          ()
        })
    }
  }

  private def inTempDir[A](use: java.nio.file.Path => IO[A]): IO[A] =
    IO.blocking(Files.createTempDirectory("stall-dump")).flatMap { dir =>
      use(dir).guarantee(IO.blocking {
        Files.deleteIfExists(dir.resolve("blocker"))
        Files.deleteIfExists(dir)
        ()
      })
    }

  private val noSessions = StallDiagnostics.Report(
    Vector.empty,
    BlockAdmissions.Occupancy(10, 0, 0)
  )

  test("a runtime with no live-fiber trigger reports NoMonitor and writes nothing") {
    inTempDir { dir =>
      StallDiagnostics.fiberDump[IO](dir, 1, () => None).flatMap { dump =>
        IO.blocking(Files.list(dir).count()).map { files =>
          assertEquals(files, 0L)
          assertEquals(dump, StallDiagnostics.Dump.Failed(StallDiagnostics.DumpError.NoMonitor))
          assertEquals(
            fields(NodeEvent.StallState(noSessions, dump))("fiberDump"),
            Json.obj("failed" -> Json.fromString("noMonitor"))
          )
        }
      }
    }
  }

  test("a directory that cannot be created reports Io with the cause") {
    inTempDir { dir =>
      // a regular file where the dump directory should be
      val blocker = dir.resolve("blocker")
      IO.blocking(Files.createFile(blocker)) *>
        StallDiagnostics
          .fiberDump[IO](blocker.resolve("nested"), 1, () => Some(Array("fiber")))
          .map {
            case failed @ StallDiagnostics.Dump.Failed(StallDiagnostics.DumpError.Io(_)) =>
              val rendered = fields(NodeEvent.StallState(noSessions, failed))("fiberDump").hcursor
              assertEquals(rendered.get[String]("failed"), Right("io"))
              assert(rendered.get[String]("message").exists(_.nonEmpty))
            case other => fail(s"not an I/O failure: $other")
          }
    }
  }

  test("a failing snapshot reports Defect, which the watchdog survives") {
    inTempDir { dir =>
      StallDiagnostics
        .fiberDump[IO](dir, 1, () => throw new IllegalStateException("monitor failed"))
        .map {
          case failed @ StallDiagnostics.Dump.Failed(StallDiagnostics.DumpError.Defect(cause)) =>
            assertEquals(cause.getMessage, "monitor failed")
            val rendered = fields(NodeEvent.StallState(noSessions, failed))("fiberDump").hcursor
            assertEquals(rendered.get[String]("failed"), Right("defect"))
          case other => fail(s"not a defect: $other")
        }
    }
  }

  private val oneSession = FetchCoordinator.create[IO]().flatMap { coordinator =>
    BlockAdmissions.create[IO].map(StallDiagnostics.Sources(coordinator, _))
  }

  test("the report is read and the state emitted while the log is wedged") {
    // a log whose lock is held: every write waits for a gate that is never opened
    oneSession.flatMap { sources =>
      for
        gate <- Deferred[IO, Unit]
        started <- Ref.of[IO, Int](0)
        wedged = (_: NodeEvent) => started.update(_ + 1) *> gate.get
        reporter <- StallDiagnostics.Reporter.create[IO](None, wedged, _.start.void)
        _ <- reporter.install(sources)
        // onStall returns although the log never does
        count <- (
          reporter
            .onStall(stalledLine)
            .timeoutTo(2.seconds, IO.raiseError(new AssertionError("blocked"))) *>
            (IO.sleep(10.millis) *> started.get).iterateUntil(_ >= 1).timeout(10.seconds)
        ).guarantee(gate.complete(()).void)
      yield assertEquals(count, 1)
    }
  }

  test(
    "a hung dump neither delays the state nor blocks the watchdog, and the state file is written"
  ) {
    inTempDir { dir =>
      oneSession
        .flatMap { sources =>
          for
            release <- IO(new java.util.concurrent.CountDownLatch(1))
            logged <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
            stateLogged <- Deferred[IO, Unit]
            log = (e: NodeEvent) =>
              logged.update(_ :+ e) *> (e match
                case _: NodeEvent.StallState => stateLogged.complete(()).void
                case _                       => IO.unit)
            hung = () =>
              release.await()
              None
            reporter <- StallDiagnostics.Reporter.create[IO](Some(dir), log, _.start.void, hung)
            _ <- reporter.install(sources)
            dog <- StallWatchdog.create[IO](
              BigInt(100),
              100.millis,
              Some(200.millis),
              {
                case StallWatchdog.Change.Began(_, _, _) => reporter.onStall(stalledLine)
                case StallWatchdog.Change.ExitDue(_, _, _) =>
                  logged.update(_ :+ NodeEvent.StallExit(200.millis, BigInt(0), BigInt(100)))
                case _ => IO.unit
              }
            )
            _ <- dog.announced(BigInt(5000))
            _ <- IO.sleep(150.millis) *> dog.check
            _ <- stateLogged.get.timeout(10.seconds)
            // the dump is still hanging; a later check still reaches the exit
            _ <- IO.sleep(150.millis) *> dog.check
            events <- logged.get
            files <- IO.blocking(
              Files.list(dir).iterator.asScala.toList.map(_.getFileName.toString)
            )
            _ <- IO(release.countDown())
            _ <- logged.get
              .iterateUntil(_.exists(_.isInstanceOf[NodeEvent.StallDumped]))
              .timeout(10.seconds)
          yield
            assert(events.exists(_.isInstanceOf[NodeEvent.StallState]), events.toString)
            assert(events.exists(_.isInstanceOf[NodeEvent.StallExit]), events.toString)
            assert(!events.exists(_.isInstanceOf[NodeEvent.StallDumped]), events.toString)
            assert(files.exists(_.matches("stall-state-\\d+-1\\.json")), files.toString)
        }
        .guarantee(IO.blocking {
          Files.list(dir).forEach(f => Files.deleteIfExists(f))
          ()
        })
    }
  }

  test("the real watchdog callback awaits no log, and a later or simultaneous exit still lands") {
    oneSession.flatMap { sources =>
      for
        gate <- Deferred[IO, Unit]
        started <- Ref.of[IO, Int](0)
        wedged = (_: NodeEvent) => started.update(_ + 1) *> gate.get
        watch <- StallDiagnostics.Watch.create[IO](None, wedged, _.start.void)
        _ <- watch.reporter.install(sources)
        dog <- StallWatchdog.create[IO](
          BigInt(100),
          50.millis,
          Some(100.millis),
          watch.react(_ => IO.unit)
        )
        _ <- dog.announced(BigInt(5000))
        _ <- (
          // Began and ExitDue fall in this one check; the log never returns
          IO.sleep(200.millis) *> dog.check.timeout(5.seconds) *>
            watch.exit.get.timeout(5.seconds) *>
            (IO.sleep(10.millis) *> started.get).iterateUntil(_ >= 2).timeout(10.seconds)
        ).guarantee(gate.complete(()).void)
      yield ()
    }
  }

  test("closing the scope while a dump hangs finalizes, and the hung dump is abandoned") {
    inTempDir { dir =>
      oneSession
        .flatMap { sources =>
          for
            release <- IO(new java.util.concurrent.CountDownLatch(1))
            entered <- Deferred[IO, Unit]
            hung = () =>
              // the dump is inside the uncancellable snapshot when the scope closes
              entered.complete(()).unsafeRunAndForget()(using munitIORuntime)
              release.await()
              None
            scope = Supervisor[IO](await = false).use { supervisor =>
              for
                reporter <- StallDiagnostics.Reporter.create[IO](
                  Some(dir),
                  _ => IO.unit,
                  StallDiagnostics.abandoning(supervisor),
                  hung
                )
                _ <- reporter.install(sources)
                _ <- reporter.onStall(stalledLine)
                _ <- entered.get.timeout(10.seconds)
              yield ()
            }
            outcome <- scope.start
              .flatMap(fiber => IO.race(fiber.joinWithNever, IO.sleep(10.seconds)))
              .guarantee(IO(release.countDown()))
          yield assert(outcome.isLeft, "the scope did not finalize while the dump hung")
        }
        .guarantee(IO.blocking {
          Files.list(dir).forEach(f => Files.deleteIfExists(f))
          ()
        })
    }
  }

  test("a second stall while the first dump hangs skips its dump, and a later one dumps again") {
    inTempDir { dir =>
      oneSession
        .flatMap { sources =>
          for
            release <- IO(new java.util.concurrent.CountDownLatch(1))
            logged <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
            hung = () =>
              release.await()
              None
            reporter <- StallDiagnostics.Reporter
              .create[IO](Some(dir), e => logged.update(_ :+ e), _.start.void, hung)
            _ <- reporter.install(sources)
            dumps = logged.get.map(_.collect { case d: NodeEvent.StallDumped => d })
            states = logged.get.map(_.collect { case s: NodeEvent.StallState => s.dump })
            result <- (for
              _ <- reporter.onStall(stalledLine)
              _ <- reporter.onStall(stalledLine)
              firstStates <- states
                .flatMap(s => IO.sleep(10.millis).as(s))
                .iterateUntil(_.size >= 2)
                .timeout(10.seconds)
              skipped <- dumps
                .flatMap(d => IO.sleep(10.millis).as(d))
                .iterateUntil(_.nonEmpty)
                .timeout(10.seconds)
              files <- IO.blocking(
                Files.list(dir).iterator.asScala.toList.map(_.getFileName.toString)
              )
              _ <- IO(release.countDown())
              // the first dump ends and frees the slot before its line is logged
              _ <- dumps
                .flatMap(d => IO.sleep(10.millis).as(d))
                .iterateUntil(_.size >= 2)
                .timeout(10.seconds)
              _ <- reporter.onStall(stalledLine)
              all <- dumps
                .flatMap(d => IO.sleep(10.millis).as(d))
                .iterateUntil(_.size >= 3)
                .timeout(10.seconds)
            yield (firstStates, skipped, files, all)).guarantee(IO(release.countDown()))
          yield
            val (firstStates, skipped, files, all) = result
            assertEquals(
              firstStates.toSet,
              Set(StallDiagnostics.Dump.Requested, StallDiagnostics.Dump.Skipped)
            )
            assertEquals(
              skipped.map(d => d.state -> d.fibers),
              Vector(StallDiagnostics.Dump.Skipped -> StallDiagnostics.Dump.Skipped)
            )
            // one state file, from the first stall
            assertEquals(files.count(_.startsWith("stall-state-")), 1, files.toString)
            // the first and the third stall dumped; only the second was skipped
            val outcomes = all.map(_.state)
            assertEquals(outcomes.count(_ == StallDiagnostics.Dump.Skipped), 1)
            assertEquals(
              outcomes.count {
                case StallDiagnostics.Dump.Written(_) => true
                case _                                => false
              },
              2
            )
        }
        .guarantee(IO.blocking {
          Files.list(dir).forEach(f => Files.deleteIfExists(f))
          ()
        })
    }
  }

  test("repeated stalls with a stuck log hold one fiber and one waiting report") {
    oneSession.flatMap { sources =>
      for
        gate <- Deferred[IO, Unit]
        started <- Ref.of[IO, Int](0)
        fibers <- Ref.of[IO, Int](0)
        stuck = (_: NodeEvent) => started.update(_ + 1) *> gate.get
        reporter <- StallDiagnostics.Reporter
          .create[IO](None, stuck, body => fibers.update(_ + 1) *> body.start.void)
        _ <- reporter.install(sources)
        result <- (for
          _ <- (1 to 50).toList.traverse_(_ => reporter.onStall(stalledLine))
          _ <- (IO.sleep(10.millis) *> started.get).iterateUntil(_ >= 1).timeout(10.seconds)
          fibersStarted <- fibers.get
          calls <- started.get
          dropped <- reporter.dropped
        yield (fibersStarted, calls, dropped)).guarantee(gate.complete(()).void)
      yield assertEquals(result, (1, 1, 48L))
    }
  }

  test("the next stallState says how many reports were replaced while the log was stuck") {
    oneSession.flatMap { sources =>
      for
        gate <- Deferred[IO, Unit]
        logged <- Ref.of[IO, Vector[NodeEvent]](Vector.empty)
        wedged = (e: NodeEvent) => gate.get *> logged.update(_ :+ e)
        reporter <- StallDiagnostics.Reporter.create[IO](None, wedged, _.start.void)
        _ <- reporter.install(sources)
        counts <- (
          (1 to 50).toList.traverse_(_ => reporter.onStall(stalledLine)) *>
            gate.complete(()) *>
            (IO.sleep(10.millis) *> logged.get)
              .iterateUntil(_.exists(_.isInstanceOf[NodeEvent.StallState]))
              .timeout(10.seconds)
              .map(_.collect { case s: NodeEvent.StallState => s.report.droppedReports })
        ).guarantee(gate.complete(()).void)
      yield assertEquals(counts.head, 48L)
    }
  }

  test("the last line of a stalled run is bounded when the log is stuck") {
    StallDiagnostics.bounded[IO](100.millis)(IO.never[Unit]).timeout(5.seconds)
  }

  test("the worker's mark is cleared when its work is cancelled or fails") {
    BlockAdmissions.create[IO].flatMap { admissions =>
      for
        fiber <- admissions.processing(WorkKind.Add, AdmissionStage.Applying)(IO.never[Unit]).start
        during <- (IO.sleep(10.millis) *> admissions.occupancy)
          .iterateUntil(_.worker.nonEmpty)
          .timeout(10.seconds)
        _ <- fiber.cancel
        cancelled <- admissions.occupancy
        failed <- admissions
          .processing(WorkKind.Add, AdmissionStage.Applying)(
            IO.raiseError[Unit](new Exception("x"))
          )
          .attempt
        after <- admissions.occupancy
      yield
        assert(during.worker.nonEmpty)
        assertEquals(cancelled.worker, None)
        assert(failed.isLeft)
        assertEquals(after.worker, None)
    }
  }

  test("the admission worker's work and stage are reported while it runs, and cleared after") {
    BlockAdmissions.create[IO].flatMap { admissions =>
      for
        idle <- admissions.occupancy
        inside <- admissions.processing(WorkKind.Add, AdmissionStage.WaitingForApplication)(
          admissions.occupancy
            .flatMap(first =>
              admissions.stage(AdmissionStage.Applying) *> admissions.occupancy.map(first -> _)
            )
        )
        after <- admissions.occupancy
      yield
        assertEquals(idle.worker, None)
        assertEquals(
          inside._1.worker.map(w => w.work -> w.stage),
          Some(WorkKind.Add -> AdmissionStage.WaitingForApplication)
        )
        assertEquals(
          inside._2.worker.map(w => w.work -> w.stage),
          Some(WorkKind.Add -> AdmissionStage.Applying)
        )
        assertEquals(after.worker, None)
        assertEquals(after.publishWaiting, 0)
    }
  }
