// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.Files
import cats.effect.{IO, Ref}
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
  private val pipeline = Peer.PipelineState(
    peer = "127.0.0.1:3001",
    permitsFree = 1,
    permits = 3,
    queued = 2,
    candidateHeaders = 40,
    unpublishedHeaders = 7,
    capacityRoom = false,
    candidateTipSlot = Some(900L),
    publishedTipSlot = Some(840L),
    lastReplyReceivedAgo = Some(9.minutes),
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
              reporter <- StallDiagnostics.Reporter.create[IO](None, e => logged.update(_ :+ e))
              _ <- reporter.install(StallDiagnostics.Sources(coordinator, admissions))
              dog <- StallWatchdog.create[IO](
                BigInt(100),
                10.minutes,
                None,
                {
                  case StallWatchdog.Change.Began(_, _, _) =>
                    reporter.onStall
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
              assertEquals(events.size, 1)
              val f = fields(events.head)
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
              assertEquals(p.get[Long]("lastReplyReceivedAgoMs"), Right(540000L))
              assertEquals(p.get[Long]("lastReplyHandledAgoMs"), Right(541000L))
              assertEquals(
                f("admissions"),
                Json.obj(
                  "capacity" -> Json.fromInt(10),
                  "held" -> Json.fromInt(0),
                  "queued" -> Json.fromInt(0)
                )
              )
              assertEquals(f("fiberDump"), Json.Null)
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
