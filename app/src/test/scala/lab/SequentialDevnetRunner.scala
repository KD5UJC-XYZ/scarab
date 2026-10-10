// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import scala.concurrent.duration.*
import lab.cbor.Bytes

/** Test-only orchestration. A checkpoint is an observation, never restore authority. */
object SequentialDevnetRunner:
  enum Requirement:
    case SameEpochService, DurableRestore, RepeatedEpochTransition, MultipleIngressNodes
  enum Scenario:
    case ObserveService, TwoSequentialTransfers, RestartAndRejoin, FollowAcrossEpochs, MultipleNodes
    def requirement: Requirement = this match
      case ObserveService | TwoSequentialTransfers => Requirement.SameEpochService
      case RestartAndRejoin                        => Requirement.DurableRestore
      case FollowAcrossEpochs                      => Requirement.RepeatedEpochTransition
      case MultipleNodes                           => Requirement.MultipleIngressNodes
  enum ObservationStage:
    case Admission1, Admission2, Duplicate1, Duplicate2, Conflict1
    case WaitFirstInclusion, WaitSecondInclusion, BetweenState, AfterSecondState,
      FirstStatusAfterSecond, Unknown
  enum ResponseCode:
    case Accepted, AlreadyPresent, InputsReserved, Pending, Included, State
    case Rejected, Unsupported, StaleState, Unavailable, Unknown
  final case class LastObservation(stage: ObservationStage, responseCode: ResponseCode)
  enum FailureCause:
    case ConnectionFailure, Deadline, InvalidObservation, TransportFailure
    case EvidenceUnavailable, UnknownClientFailure
  enum Operation:
    case HttpClient
  enum Failure:
    case ActionRejected, AdapterError, ActionDeadline, CheckpointTooLarge, EvidenceBudget
    case ClientFailure(
        cause: FailureCause,
        operation: Operation,
        lastObservation: Option[LastObservation] = None
    )
  enum Verdict:
    case Completed(checkpoint: Checkpoint)
    case Blocked(missing: Requirement)
    case Failed(reason: Failure)
  enum StopReason:
    case Exhausted, ScenarioLimit, TotalDeadline, Failed
  final case class Checkpoint(index: Int, scenario: Scenario, sha256: String, bytes: Int)
  final case class Row(scenario: Scenario, verdict: Verdict)
  final case class Report(rows: Vector[Row], stop: StopReason):
    def executedScenariosPassed: Boolean =
      stop == StopReason.Exhausted && rows.exists {
        case Row(_, Verdict.Completed(_)) => true
        case _                            => false
      } && !rows.exists {
        case Row(_, Verdict.Failed(_)) => true
        case _                         => false
      }
    def allRequestedPassed: Boolean = stop == StopReason.Exhausted && rows.forall {
      case Row(_, Verdict.Completed(_)) => true
      case _                            => false
    }
  final case class Limits(
      scenarios: Int,
      action: FiniteDuration,
      total: FiniteDuration,
      checkpointBytes: Int,
      evidenceBytes: Long
  ):
    require(scenarios > 0 && scenarios <= 128)
    require(action > Duration.Zero && action <= 60.seconds)
    require(total > Duration.Zero && total <= 10.minutes)
    require(checkpointBytes > 0 && checkpointBytes <= 65536)
    require(evidenceBytes > 0 && evidenceBytes <= 8388608)

  /** Resource owns the adapter lease. Its finalizer must settle owned work; the runner never
    * enumerates or stops arbitrary containers. Implementations must bound I/O before allocation.
    */
  trait Adapter:
    def capabilities: Set[Requirement]
    def execute(scenario: Scenario): IO[Either[Failure, Unit]]
    def observe(scenario: Scenario): IO[Bytes]

  def run(plan: Vector[Scenario], limits: Limits, lease: Resource[IO, Adapter]): IO[Report] =
    for
      rows <- Ref.of[IO, Vector[Row]](Vector.empty)
      used <- Ref.of[IO, Long](0L)
      result <- lease
        .use { adapter =>
          def one(s: Scenario, index: Int): IO[Verdict] =
            if !adapter.capabilities.contains(s.requirement) then
              IO.pure(Verdict.Blocked(s.requirement))
            else
              val action = adapter.execute(s).flatMap {
                case Left(reason) => IO.pure(Verdict.Failed(reason))
                case Right(_) =>
                  adapter.observe(s).flatMap { bytes =>
                    if bytes.size > limits.checkpointBytes then
                      IO.pure(Verdict.Failed(Failure.CheckpointTooLarge))
                    else
                      used.get.flatMap { previous =>
                        if previous + bytes.size > limits.evidenceBytes then
                          IO.pure(Verdict.Failed(Failure.EvidenceBudget))
                        else
                          used
                            .set(previous + bytes.size)
                            .as(
                              Verdict.Completed(
                                Checkpoint(
                                  index,
                                  s,
                                  ClusterHeaderObservation.sha256(bytes).hex,
                                  bytes.size
                                )
                              )
                            )
                      }
                  }
              }
              action
                .handleError(_ => Verdict.Failed(Failure.AdapterError))
                .timeoutTo(limits.action, IO.pure(Verdict.Failed(Failure.ActionDeadline)))
          def loop(index: Int): IO[StopReason] =
            if index >= plan.size then IO.pure(StopReason.Exhausted)
            else if index >= limits.scenarios then IO.pure(StopReason.ScenarioLimit)
            else
              one(plan(index), index).flatMap { verdict =>
                rows.update(_ :+ Row(plan(index), verdict)) *> (verdict match
                  case Verdict.Failed(_) => IO.pure(StopReason.Failed)
                  case _                 => IO.defer(loop(index + 1)))
              }
          loop(0)
        }
        .timeoutTo(limits.total, IO.pure(StopReason.TotalDeadline))
      completed <- rows.get
    yield Report(completed, result)
