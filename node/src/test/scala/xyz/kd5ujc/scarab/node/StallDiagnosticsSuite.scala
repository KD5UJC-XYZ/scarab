// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.Files
import cats.effect.{Deferred, IO, Ref}
import cats.effect.std.Supervisor
import scala.jdk.CollectionConverters.*
import cats.effect.testkit.TestControl
import io.circe.Json
import munit.CatsEffectSuite
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.node.log.{NodeEvent, Traced}

/** The state a stall leaves in the log (issue 371): a simulated stall of a watchdog reads the
  * coordinator, a session's pipeline probe and the admissions, and the `stallState` event carries
  * what they held.
  */
class StallDiagnosticsSuite extends CatsEffectSuite:
  private val stalledLine = NodeEvent.Stalled(10.minutes, BigInt(100), BigInt(5000))

  private val pipeline = Peer.PipelineState(
    peer = "127.0.0.1:3001",
    permitsFree = 1,
    permits = 3,
    queued = 2,
    candidateHeaders = 40,
    unpublishedHeaders = Some(7),
    capacityRoom = false,
    candidateBase = "origin",
    candidateTip = "900:cd",
    publishedBase = "origin",
    publishedTip = "840:ab",
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
              assertEquals(events.map(_.getClass.getSimpleName), Vector("Stalled", "StallState"))
              val f = fields(events.last)
              val sessions = f("sessions").asArray.get
              assertEquals(sessions.size, 1)
              val s = sessions.head.hcursor
              assertEquals(s.get[Long]("session"), Right(session.identity))
              assertEquals(s.get[Boolean]("busy"), Right(false))
              assertEquals(s.get[Int]("pendingOffers"), Right(0))
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
              assertEquals(s.downField("reserving").focus, Some(Json.Null))
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
        _ <- reporter
          .onStall(stalledLine)
          .timeoutTo(2.seconds, IO.raiseError(new AssertionError("blocked")))
        count <- (IO.sleep(10.millis) *> started.get).iterateUntil(_ >= 1).timeout(10.seconds)
        _ <- gate.complete(())
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
        // Began and ExitDue fall in this one check; the log never returns
        _ <- IO.sleep(200.millis) *> dog.check.timeout(5.seconds)
        _ <- watch.exit.get.timeout(5.seconds)
        _ <- (IO.sleep(10.millis) *> started.get).iterateUntil(_ >= 2).timeout(10.seconds)
        _ <- gate.complete(())
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
            _ <- Supervisor[IO](await = false)
              .use { supervisor =>
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
              .timeout(10.seconds)
            _ <- IO(release.countDown())
          yield ()
        }
        .guarantee(IO.blocking {
          Files.list(dir).forEach(f => Files.deleteIfExists(f))
          ()
        })
    }
  }

  test("a second stall while the first dump hangs skips its dump, and still reports its state") {
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
            _ <- reporter.onStall(stalledLine)
            _ <- reporter.onStall(stalledLine)
            events <- logged.get
              .iterateUntil(_.collect { case s: NodeEvent.StallState => s }.size >= 2)
              .timeout(10.seconds)
            skipped <- logged.get
              .iterateUntil(_.exists {
                case NodeEvent.StallDumped(StallDiagnostics.Dump.Skipped, _) => true
                case _                                                       => false
              })
              .timeout(10.seconds)
            files <- IO.blocking(
              Files.list(dir).iterator.asScala.toList.map(_.getFileName.toString)
            )
            _ <- IO(release.countDown())
            // the first dump ends, and the next stall dumps again
            _ <- logged.get
              .iterateUntil(_.exists {
                case NodeEvent.StallDumped(StallDiagnostics.Dump.Written(_), _) => true
                case _                                                          => false
              })
              .timeout(10.seconds)
            _ <- reporter.onStall(stalledLine)
            third <- logged.get
              .iterateUntil(_.collect { case s: NodeEvent.StallState => s }.size >= 3)
              .timeout(10.seconds)
          yield
            val states = events.collect { case s: NodeEvent.StallState => s.dump }
            assertEquals(
              states.toSet.take(2),
              Set(StallDiagnostics.Dump.Requested, StallDiagnostics.Dump.Skipped)
            )
            assert(skipped.nonEmpty)
            // one state file, from the first stall
            assertEquals(files.count(_.startsWith("stall-state-")), 1, files.toString)
            assertEquals(third.collect { case s: NodeEvent.StallState => s }.size, 3)
        }
        .guarantee(IO.blocking {
          Files.list(dir).forEach(f => Files.deleteIfExists(f))
          ()
        })
    }
  }

  test("the admission worker's work and stage are reported while it runs, and cleared after") {
    BlockAdmissions.create[IO].flatMap { admissions =>
      for
        idle <- admissions.occupancy
        inside <- admissions.processing("add", "waitingForApplication")(
          admissions.occupancy
            .flatMap(first => admissions.stage("applying") *> admissions.occupancy.map(first -> _))
        )
        after <- admissions.occupancy
      yield
        assertEquals(idle.worker, None)
        assertEquals(
          inside._1.worker.map(w => w.work -> w.stage),
          Some("add" -> "waitingForApplication")
        )
        assertEquals(inside._2.worker.map(w => w.work -> w.stage), Some("add" -> "applying"))
        assertEquals(after.worker, None)
        assertEquals(after.publishWaiting, 0)
    }
  }
