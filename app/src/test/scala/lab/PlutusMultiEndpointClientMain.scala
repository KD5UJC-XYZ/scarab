// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp, Ref, Resource}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import scala.concurrent.duration.*
import lab.submission.SignedTransaction
import ReferenceJson.Json as J
import SequentialDevnetRunner.*

object PlutusMultiEndpointClientMain extends IOApp:
  private enum Phase:
    case ReadOriginals, Connect, SubmitAndInclude, CrossObserve
  private def phaseName(value: Phase): String = value match
    case Phase.ReadOriginals    => "ReadOriginals"
    case Phase.Connect          => "Connect"
    case Phase.SubmitAndInclude => "SubmitAndInclude"
    case Phase.CrossObserve     => "CrossObserve"
  private def failure(error: Throwable): String = error match
    case _: java.net.ConnectException             => "ConnectionFailure"
    case _: java.util.concurrent.TimeoutException => "Deadline"
    case _: java.net.http.HttpTimeoutException    => "Deadline"
    case _: IllegalArgumentException              => "InvalidObservation"
    case _: java.io.IOException                   => "TransportFailure"
    case _                                        => "ClientFailure"
  private[lab] def boundedResult(value: J): (lab.cbor.Bytes, Boolean) =
    val raw = SyntheticRewardProjection.encode(value)
    if raw.size <= 1048576 then (raw, ReferenceJson.field(value, "passed") == J.Lit("true"))
    else
      val fallback = J.Obj(
        Map(
          "schema" -> J.Str("multi-endpoint-client-result-v1"),
          "passed" -> J.Lit("false"),
          "resourcesFinalized" -> ReferenceJson.field(value, "resourcesFinalized"),
          "diagnosticOnly" -> J.Lit("true"),
          "fullLedgerValidated" -> J.Lit("false"),
          "submittedEnvelopeByteEqualityVerified" -> J.Lit("false"),
          "failureCause" -> J.Str("EvidenceLimit"),
          "phase" -> J.Str("ResultPublication"),
          "originalSHA256" -> J.Str(PlutusMultiEndpointHttp.hash(raw).hex),
          "endpoints" -> J.Arr(Vector.empty)
        )
      )
      (SyntheticRewardProjection.encode(fallback), false)

  def run(args: List[String]): IO[ExitCode] = args match
    case p1 :: p2 :: duration :: e1 :: e2 :: output :: Nil =>
      for
        ports <- IO(Vector(p1.toInt, p2.toInt))
        seconds <- IO(duration.toInt)
        roots <- IO(Vector(Path.of(e1), Path.of(e2)))
        out <- IO(Path.of(output))
        _ <- IO(
          require(
            ports.distinct.size == 2 && ports.forall(p =>
              p > 0 && p <= 65535
            ) && seconds >= 3 && seconds <= 60 && roots.forall(
              _.isAbsolute
            ) && roots.distinct.size == 2 && out.isAbsolute
          )
        )
        phase <- Ref.of[IO, Phase](Phase.ReadOriginals)
        partial <- Ref.of[IO, Vector[J]](Vector.empty)
        started <- IO.monotonic
        attempt <- (for
          txs <- roots.traverse(root =>
            PlutusMultiEndpointHttp
              .read(root.resolve("submission/transaction-1.cbor"), 65536)
              .flatMap(raw =>
                IO.fromEither(
                  SignedTransaction
                    .checked(raw)
                    .left
                    .map(e => new IllegalArgumentException(e.toString))
                )
              )
          )
          _ <- IO(PlutusMultiEndpointHttp.disjoint(txs))
          _ <- phase.set(Phase.Connect)
          rows <- roots.indices.toVector
            .traverse { i =>
              PlutusMultiEndpointHttp.resource(s"service-${i + 1}", ports(i), roots(i), txs(i))
            }
            .use { clients =>
              (for
                _ <- phase.set(Phase.SubmitAndInclude)
                report <- SequentialDevnetRunner.run(
                  Vector(Scenario.MultipleNodes),
                  Limits(1, seconds.seconds, seconds.seconds, 4096, 4096),
                  PlutusMultiEndpointScenarioAdapter.owned(
                    clients.map(_.endpoint),
                    txs.map(_.original),
                    seconds.seconds,
                    endpoint =>
                      Resource.pure[IO, PlutusMultiEndpointScenarioAdapter.Client](
                        clients.find(_.endpoint == endpoint).get
                      )
                  )
                )
                _ <- IO(require(report.allRequestedPassed, "two designated inclusions required"))
                _ <- phase.set(Phase.CrossObserve)
                evidence <- clients.traverse(_.finish(txs))
              yield evidence).guarantee(clients.traverse(_.audit).flatMap(partial.set))
            }
        yield rows).timeout(seconds.seconds).attempt
        elapsed <- IO.monotonic.map(_ - started)
        last <- phase.get
        partialRows <- partial.get
        result = attempt match
          case Right(rows) =>
            J.Obj(
              Map(
                "passed" -> J.Lit("true"),
                "resourcesFinalized" -> J.Lit("true"),
                "endpoints" -> J.Arr(rows)
              )
            )
          case Left(error) =>
            J.Obj(
              Map(
                "passed" -> J.Lit("false"),
                "resourcesFinalized" -> J.Lit("false"),
                "failureCause" -> J.Str(failure(error)),
                "phase" -> J.Str(phaseName(last)),
                "partialEndpoints" -> J.Arr(partialRows),
                "endpoints" -> J.Arr(Vector.empty)
              )
            )
        passed <- IO.blocking {
          val fields = result match
            case J.Obj(values) => values
            case _             => Map.empty[String, J]
          val value = J.Obj(
            fields ++ Map(
              "schema" -> J.Str("multi-endpoint-client-result-v1"),
              "diagnosticOnly" -> J.Lit("true"),
              "fullLedgerValidated" -> J.Lit("false"),
              "submittedEnvelopeByteEqualityVerified" -> J.Lit("false"),
              "elapsedMillis" -> J.Num(elapsed.toMillis.toString)
            )
          )
          val (bytes, passed) = boundedResult(value)
          val path = out.resolve("multi-endpoint-client-result.json")
          val part = path.resolveSibling(path.getFileName.toString + ".part")
          Files.write(part, bytes.toArray, Open.CREATE_NEW, Open.WRITE)
          Files.createLink(path, part)
          passed
        }
      yield if passed then ExitCode.Success else ExitCode.Error
    case _ =>
      IO.raiseError(
        new IllegalArgumentException(
          "expected PORT1 PORT2 DURATION_SECONDS EXCHANGE1 EXCHANGE2 OUTPUT"
        )
      )
