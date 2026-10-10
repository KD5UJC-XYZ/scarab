// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, IOApp, Ref}
import cats.syntax.all.*
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import scala.concurrent.duration.*
import lab.submission.SignedTransaction
import ReferenceJson.Json as J
import lab.submission.StatePin

object PlutusSoakClientMain extends IOApp:
  private[lab] def boundaryReached(initial: Vector[StatePin], current: Vector[StatePin]): Boolean =
    require(initial.size == 2 && current.size == 2 && initial.map(_.ownerId).distinct.size == 2)
    require(initial.forall(_.point.slot < 1000), "initial observation must precede first boundary")
    require(
      current.zip(initial).forall { (p, before) =>
        p.ownerId == before.ownerId && PlutusMultiEndpointHttp.follows(p, before)
      },
      "same owners and checked state progression"
    )
    val crossed = current.forall(_.point.slot >= 1000)
    if crossed then
      require(
        current.zip(initial).forall { (p, before) =>
          p.generation > before.generation && p.environmentId != before.environmentId
        },
        "actual changed epoch environment"
      )
    crossed

  private[lab] def postBoundary(boundary: StatePin, accepted: StatePin): Unit =
    require(
      boundary.point.slot >= 1000 && accepted.point.slot >= 1000 &&
        accepted.ownerId == boundary.ownerId && accepted.environmentId == boundary.environmentId &&
        PlutusMultiEndpointHttp.follows(accepted, boundary),
      "post-boundary checked admission pin"
    )

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
            ) && seconds == 120 && roots.forall(
              _.isAbsolute
            ) && roots.distinct.size == 2 && out.isAbsolute
          )
        )
        phase <- Ref.of[IO, Phase](Phase.ReadOriginals)
        partial <- Ref.of[IO, Vector[J]](Vector.empty)
        boundary <- Ref.of[IO, Vector[lab.submission.StatePin]](Vector.empty)
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
                initial <- clients.traverse(_.state)
                _ <- clients(0).submit(txs(0).original)
                _ <- clients(0).awaitIncluded(txs(0).transactionId)
                crossed <- {
                  def waitBoundary: IO[Vector[lab.submission.StatePin]] =
                    clients.traverse(_.state).flatMap { states =>
                      val pins = states.map(_.pin)
                      IO(boundaryReached(initial.map(_.pin), pins)).flatMap { reached =>
                        if reached then IO.pure(pins)
                        else IO.sleep(100.millis) *> IO.defer(waitBoundary)
                      }
                    }
                  waitBoundary
                }
                _ <- boundary.set(crossed)
                second <- clients(1).submit(txs(1).original)
                _ <- IO(postBoundary(crossed(1), second.pin))
                _ <- clients(1).awaitIncluded(txs(1).transactionId)
                _ <- phase.set(Phase.CrossObserve)
                evidence <- clients.traverse(_.finish(txs))
              yield evidence).guarantee(clients.traverse(_.audit).flatMap(partial.set))
            }
        yield rows).timeout(seconds.seconds).attempt
        elapsed <- IO.monotonic.map(_ - started)
        last <- phase.get
        partialRows <- partial.get
        boundaryPins <- boundary.get
        result = attempt match
          case Right(rows) =>
            J.Obj(
              Map(
                "passed" -> J.Lit("true"),
                "resourcesFinalized" -> J.Lit("true"),
                "endpoints" -> J.Arr(rows),
                "soakProfile" -> J.Str("early-restart-two-service-soak-v1"),
                "secondSubmittedAfterEpochOne" -> J.Lit("true"),
                "boundaryPins" -> J.Arr(boundaryPins.map(PlutusServiceRuntime.pin))
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
          val path = out.resolve("soak-client-result.json")
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
