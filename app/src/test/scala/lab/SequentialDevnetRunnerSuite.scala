// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.unsafe.implicits.global
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.Bytes
import SequentialDevnetRunner.*

class SequentialDevnetRunnerSuite extends munit.FunSuite:
  private val limits = Limits(8, 5.seconds, 20.seconds, 128, 512)
  private val observe = Scenario.ObserveService
  private def lease(
      log: Ref[IO, Vector[String]],
      action: IO[Either[Failure, Unit]],
      size: Int = 2
  ) =
    Resource.make(
      log
        .update(_ :+ "acquire")
        .as(new Adapter:
          val capabilities = Set(Requirement.SameEpochService)
          def execute(s: Scenario) = log.update(_ :+ "execute") *> action
          def observe(s: Scenario) =
            log.update(_ :+ "checkpoint").as(Bytes.fromArray(new Array[Byte](size))))
    )(_ => log.update(_ :+ "release"))

  test("serial completion precedes checkpoint and one resource release") {
    (for
      log <- Ref.of[IO, Vector[String]](Vector.empty)
      report <- run(Vector(observe, observe), limits, lease(log, IO.pure(Right(()))))
      events <- log.get
      _ = assertEquals(
        events,
        Vector("acquire", "execute", "checkpoint", "execute", "checkpoint", "release")
      )
      _ = assert(report.allRequestedPassed)
      _ = assertEquals(report.rows.size, 2)
    yield ()).unsafeToFuture()
  }
  test("unsupported requirements never execute and cannot report all requested passed") {
    (for
      log <- Ref.of[IO, Vector[String]](Vector.empty)
      report <- run(
        Vector(Scenario.RestartAndRejoin, Scenario.FollowAcrossEpochs, Scenario.MultipleNodes),
        limits,
        lease(log, IO.pure(Right(())))
      )
      events <- log.get
      _ = assertEquals(events, Vector("acquire", "release"))
      _ = assertEquals(report.stop, StopReason.Exhausted)
      _ = assertEquals(
        report.rows.map(_.verdict),
        Vector(
          Verdict.Blocked(Requirement.DurableRestore),
          Verdict.Blocked(Requirement.RepeatedEpochTransition),
          Verdict.Blocked(Requirement.MultipleIngressNodes)
        )
      )
      _ = assert(!report.allRequestedPassed)
    yield ()).unsafeToFuture()
  }
  test("failure has no checkpoint and prevents later actions") {
    (for
      log <- Ref.of[IO, Vector[String]](Vector.empty)
      report <- run(
        Vector(observe, observe),
        limits,
        lease(log, IO.pure(Left(Failure.ActionRejected)))
      )
      events <- log.get
      _ = assertEquals(events, Vector("acquire", "execute", "release"))
      _ = assertEquals(report.stop, StopReason.Failed)
      _ = assertEquals(report.rows.size, 1)
    yield ()).unsafeToFuture()
  }
  test("scenario and evidence caps stop without claiming success") {
    (for
      log <- Ref.of[IO, Vector[String]](Vector.empty)
      capped <- run(
        Vector(observe, observe),
        limits.copy(scenarios = 1),
        lease(log, IO.pure(Right(())))
      )
      oversized <- run(
        Vector(observe),
        limits.copy(checkpointBytes = 1),
        lease(log, IO.pure(Right(())))
      )
      total <- run(
        Vector(observe, observe),
        limits.copy(evidenceBytes = 3),
        lease(log, IO.pure(Right(())))
      )
      _ = assertEquals(capped.stop, StopReason.ScenarioLimit)
      _ = assert(!capped.allRequestedPassed)
      _ = assertEquals(oversized.rows.head.verdict, Verdict.Failed(Failure.CheckpointTooLarge))
      _ = assertEquals(total.rows.last.verdict, Verdict.Failed(Failure.EvidenceBudget))
    yield ()).unsafeToFuture()
  }
  test("action deadline and total deadline settle resource with distinct outcomes") {
    TestControl
      .executeEmbed(for
        log <- Ref.of[IO, Vector[String]](Vector.empty)
        action <- run(Vector(observe), limits, lease(log, IO.never))
        total <- run(Vector(observe), limits.copy(total = 1.second), lease(log, IO.never))
        events <- log.get
        _ = assertEquals(action.rows.head.verdict, Verdict.Failed(Failure.ActionDeadline))
        _ = assertEquals(total.stop, StopReason.TotalDeadline)
        _ = assertEquals(events.count(_ == "release"), 2)
      yield ())
      .unsafeToFuture()
  }
  test("external cancellation joins owned action and releases lease without checkpoint") {
    (for
      log <- Ref.of[IO, Vector[String]](Vector.empty)
      started <- Deferred[IO, Unit]
      fiber <- run(
        Vector(observe),
        limits,
        lease(log, (started.complete(()) *> IO.never).onCancel(log.update(_ :+ "cancel")))
      ).start
      _ <- started.get
      _ <- fiber.cancel
      events <- log.get
      _ = assertEquals(events, Vector("acquire", "execute", "cancel", "release"))
    yield ()).unsafeToFuture()
  }
  test("limits reject zero and excessive budgets") {
    intercept[IllegalArgumentException](limits.copy(scenarios = 0))
    intercept[IllegalArgumentException](limits.copy(action = Duration.Zero))
    intercept[IllegalArgumentException](limits.copy(total = 11.minutes))
    intercept[IllegalArgumentException](limits.copy(checkpointBytes = 65537))
    intercept[IllegalArgumentException](limits.copy(evidenceBytes = 0))
  }

  test("concrete observer requires fixed profile, volatile scope and owner identity") {
    import ReferenceJson.Json as J
    import lab.submission.AdmissionProfile
    val profile = J.Str(AdmissionProfile.PlutusV3.id)
    val fields = Map(
      "profileId" -> profile,
      "fullLedgerValidated" -> J.Lit("false"),
      "volatile" -> J.Lit("true"),
      "pin" -> J.Obj(Map("profileId" -> profile, "ownerId" -> J.Str("ab" * 32)))
    )
    def encoded(values: Map[String, J]) = SyntheticRewardProjection.encode(J.Obj(values))
    assertEquals(PlutusServiceScenarioAdapter.stateOwner(encoded(fields)), "ab" * 32)
    intercept[IllegalArgumentException](
      PlutusServiceScenarioAdapter.stateOwner(
        encoded(fields.updated("fullLedgerValidated", J.Lit("true")))
      )
    )
    intercept[IllegalArgumentException](
      PlutusServiceScenarioAdapter.stateOwner(encoded(fields.updated("profileId", J.Str("other"))))
    )
  }

  test("supported acceptance is distinct from all requested coverage and an all-blocked run") {
    val checkpoint = Checkpoint(0, observe, "ab" * 32, 2)
    val mixed = Report(
      Vector(
        Row(observe, Verdict.Completed(checkpoint)),
        Row(Scenario.RestartAndRejoin, Verdict.Blocked(Requirement.DurableRestore))
      ),
      StopReason.Exhausted
    )
    assert(mixed.executedScenariosPassed)
    assert(!mixed.allRequestedPassed)
    assert(!Report(mixed.rows.tail, StopReason.Exhausted).executedScenariosPassed)
    val json = ReferenceJson.parse(PlutusScenarioRunnerMain.encode(mixed))
    assertEquals(ReferenceJson.field(json, "allRequestedPassed"), ReferenceJson.Json.Lit("false"))
    assertEquals(
      ReferenceJson.field(json, "executedScenariosPassed"),
      ReferenceJson.Json.Lit("true")
    )
  }
