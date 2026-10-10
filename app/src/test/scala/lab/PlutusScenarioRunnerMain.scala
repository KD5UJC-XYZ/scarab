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
        J.Obj(Map("outcome" -> text("blocked"), "missingRequirement" -> text(requirement.toString)))
      case Verdict.Failed(reason) =>
        J.Obj(Map("outcome" -> text("failed"), "reason" -> text(reason.toString)))
    J.Obj(Map("scenario" -> text(value.scenario.toString), "verdict" -> verdict))
  private[lab] def encode(report: Report): lab.cbor.Bytes =
    SyntheticRewardProjection.encode(
      J.Obj(
        Map(
          "schema" -> text("sequential-devnet-runner-v1"),
          "allRequestedPassed" -> J.Lit(report.allRequestedPassed.toString),
          "executedScenariosPassed" -> J.Lit(report.executedScenariosPassed.toString),
          "stop" -> text(report.stop.toString),
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
