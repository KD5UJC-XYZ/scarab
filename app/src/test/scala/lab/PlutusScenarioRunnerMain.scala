// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp}
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import scala.concurrent.duration.*
import ReferenceJson.Json as J
import SequentialDevnetRunner.*

/** Drop-in Test-only client process for an already running, supervisor-owned service. */
object PlutusScenarioRunnerMain extends IOApp:
  private def text(s: String): J = J.Str(s)
  private def number(n: Long): J = J.Num(n.toString)
  private def wire(value: Scenario): String = value match
    case Scenario.ObserveService         => "ObserveService"
    case Scenario.TwoSequentialTransfers => "TwoSequentialTransfers"
    case Scenario.RestartAndRejoin       => "RestartAndRejoin"
    case Scenario.FollowAcrossEpochs     => "FollowAcrossEpochs"
    case Scenario.MultipleNodes          => "MultipleNodes"
  private def wire(value: Requirement): String = value match
    case Requirement.SameEpochService        => "SameEpochService"
    case Requirement.DurableRestore          => "DurableRestore"
    case Requirement.RepeatedEpochTransition => "RepeatedEpochTransition"
    case Requirement.MultipleIngressNodes    => "MultipleIngressNodes"
  private def wire(value: StopReason): String = value match
    case StopReason.Exhausted     => "Exhausted"
    case StopReason.ScenarioLimit => "ScenarioLimit"
    case StopReason.TotalDeadline => "TotalDeadline"
    case StopReason.Failed        => "Failed"
  private def wire(value: FailureCause): String = value match
    case FailureCause.ConnectionFailure    => "ConnectionFailure"
    case FailureCause.Deadline             => "Deadline"
    case FailureCause.InvalidObservation   => "InvalidObservation"
    case FailureCause.TransportFailure     => "TransportFailure"
    case FailureCause.EvidenceUnavailable  => "EvidenceUnavailable"
    case FailureCause.UnknownClientFailure => "UnknownClientFailure"
  private def wire(value: Operation): String = value match
    case Operation.HttpClient => "HttpClient"
  private def wire(value: ObservationStage): String = value match
    case ObservationStage.Admission1             => "Admission1"
    case ObservationStage.Admission2             => "Admission2"
    case ObservationStage.Duplicate1             => "Duplicate1"
    case ObservationStage.Duplicate2             => "Duplicate2"
    case ObservationStage.Conflict1              => "Conflict1"
    case ObservationStage.WaitFirstInclusion     => "WaitFirstInclusion"
    case ObservationStage.WaitSecondInclusion    => "WaitSecondInclusion"
    case ObservationStage.BetweenState           => "BetweenState"
    case ObservationStage.AfterSecondState       => "AfterSecondState"
    case ObservationStage.FirstStatusAfterSecond => "FirstStatusAfterSecond"
    case ObservationStage.Unknown                => "Unknown"
  private def wire(value: ResponseCode): String = value match
    case ResponseCode.Accepted       => "Accepted"
    case ResponseCode.AlreadyPresent => "AlreadyPresent"
    case ResponseCode.InputsReserved => "InputsReserved"
    case ResponseCode.Pending        => "Pending"
    case ResponseCode.Included       => "Included"
    case ResponseCode.State          => "State"
    case ResponseCode.Rejected       => "Rejected"
    case ResponseCode.Unsupported    => "Unsupported"
    case ResponseCode.StaleState     => "StaleState"
    case ResponseCode.Unavailable    => "Unavailable"
    case ResponseCode.Unknown        => "Unknown"
  private def wire(value: Failure): String = value match
    case Failure.ActionRejected         => "ActionRejected"
    case Failure.AdapterError           => "AdapterError"
    case Failure.ActionDeadline         => "ActionDeadline"
    case Failure.CheckpointTooLarge     => "CheckpointTooLarge"
    case Failure.EvidenceBudget         => "EvidenceBudget"
    case Failure.ClientFailure(_, _, _) => "ClientFailure"
  private def row(value: Row): J =
    val verdict = value.verdict match
      case Verdict.Completed(checkpoint) =>
        J.Obj(
          Map(
            "outcome" -> text("completed"),
            "checkpointSHA256" -> text(checkpoint.sha256),
            "checkpointBytes" -> number(checkpoint.bytes),
            "index" -> number(checkpoint.index)
          )
        )
      case Verdict.Blocked(requirement) =>
        J.Obj(Map("outcome" -> text("blocked"), "missingRequirement" -> text(wire(requirement))))
      case Verdict.Failed(Failure.ClientFailure(cause, operation, last)) =>
        val observation = last
          .map(value =>
            "lastObservation" -> J.Obj(
              Map(
                "stage" -> text(wire(value.stage)),
                "responseCode" -> text(wire(value.responseCode))
              )
            )
          )
          .toMap
        J.Obj(
          Map(
            "outcome" -> text("failed"),
            "reason" -> text("ClientFailure"),
            "diagnostic" -> J.Obj(
              Map("operation" -> text(wire(operation)), "cause" -> text(wire(cause))) ++ observation
            )
          )
        )
      case Verdict.Failed(reason) =>
        J.Obj(Map("outcome" -> text("failed"), "reason" -> text(wire(reason))))
    J.Obj(Map("scenario" -> text(wire(value.scenario)), "verdict" -> verdict))
  private[lab] def encode(report: Report): lab.cbor.Bytes =
    SyntheticRewardProjection.encode(
      J.Obj(
        Map(
          "schema" -> text("sequential-devnet-runner-v1"),
          "allRequestedPassed" -> J.Lit(report.allRequestedPassed.toString),
          "executedScenariosPassed" -> J.Lit(report.executedScenariosPassed.toString),
          "stop" -> text(wire(report.stop)),
          "observationalOnly" -> J.Lit("true"),
          "restoreAuthority" -> J.Lit("false"),
          "rows" -> J.Arr(report.rows.map(row))
        )
      )
    )
  def run(args: List[String]): IO[ExitCode] = args match
    case portText :: durationText :: rootText :: Nil =>
      for
        port <- IO(portText.toInt)
        seconds <- IO(durationText.toInt)
        root <- IO(Path.of(rootText))
        _ <- IO(require(seconds >= 3 && seconds <= 60 && root.isAbsolute))
        report <- SequentialDevnetRunner.run(
          Vector(
            Scenario.ObserveService,
            Scenario.TwoSequentialTransfers,
            Scenario.ObserveService,
            Scenario.RestartAndRejoin,
            Scenario.FollowAcrossEpochs,
            Scenario.MultipleNodes
          ),
          Limits(8, seconds.seconds, seconds.seconds, 65536, 65536),
          PlutusServiceScenarioAdapter.borrowed(port, root, seconds - 2)
        )
        _ <- IO.blocking {
          val bytes = encode(report)
          require(bytes.size <= 65536, "runner report bound")
          val target = root.resolve("submission/scenario-runner-result.json")
          val temporary = target.resolveSibling(target.getFileName.toString + ".part")
          Files.write(temporary, bytes.toArray, Open.CREATE_NEW, Open.WRITE)
          Files.createLink(target, temporary)
        }
      yield if report.executedScenariosPassed then ExitCode.Success else ExitCode.Error
    case _ => IO.raiseError(new IllegalArgumentException("expected PORT DURATION_SECONDS EXCHANGE"))
