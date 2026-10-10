// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Ref, Resource}
import lab.submission.AdmissionProfile
import java.net.Proxy
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import cats.syntax.all.*
import lab.cbor.Bytes
import SequentialDevnetRunner.*

/** Borrow one already-owned local service. Cluster startup/cleanup remains the supervisor's
  * Resource responsibility. This adapter adds no JVM or cluster, and never submits to reference.
  */
object PlutusServiceScenarioAdapter:
  /** Failure metadata only: every result here remains a failed action. Unknown input is never
    * copied into the public-shaped diagnostic or used to select successful control flow.
    */
  private[lab] def clientFailure(bytes: Bytes): Failure =
    import ReferenceJson.{Json as J, field, string}
    val cause = scala.util
      .Try {
        require(bytes.size <= 65536)
        val json = ReferenceJson.parse(bytes)
        require(field(json, "schema") == J.Str("plutus-service-client-result-v1"))
        require(field(json, "passed") == J.Lit("false"))
        require(field(json, "fullLedgerValidated") == J.Lit("false"))
        val failureType = string(field(json, "failureType"))
        require(failureType.length <= 96)
        failureType match
          case "ConnectException" => FailureCause.ConnectionFailure
          case "TimeoutException" | "HttpTimeoutException" | "HttpConnectTimeoutException" =>
            FailureCause.Deadline
          case "IllegalArgumentException" | "AssertionError" | "ClientEvidenceLimit" =>
            FailureCause.InvalidObservation
          case "IOException" | "SocketException" => FailureCause.TransportFailure
          case _                                 => FailureCause.UnknownClientFailure
      }
      .getOrElse(FailureCause.InvalidObservation)
    val last = scala.util
      .Try {
        require(bytes.size <= 65536)
        val json = ReferenceJson.parse(bytes)
        field(json, "observations") match
          case J.Arr(rows) if rows.size <= 32 =>
            rows.lastOption.map { row =>
              val stage = string(field(row, "stage")) match
                case "admission1"             => ObservationStage.Admission1
                case "admission2"             => ObservationStage.Admission2
                case "duplicate1"             => ObservationStage.Duplicate1
                case "duplicate2"             => ObservationStage.Duplicate2
                case "conflict1"              => ObservationStage.Conflict1
                case "included1"              => ObservationStage.WaitFirstInclusion
                case "included2"              => ObservationStage.WaitSecondInclusion
                case "betweenState"           => ObservationStage.BetweenState
                case "afterSecondState"       => ObservationStage.AfterSecondState
                case "firstStatusAfterSecond" => ObservationStage.FirstStatusAfterSecond
                case _                        => ObservationStage.Unknown
              val rawCode = row match
                case J.Obj(fields) if fields.contains("response") =>
                  string(field(row, "response", "code"))
                case _ => string(field(row, "responseCode"))
              val code = rawCode match
                case "Accepted"       => ResponseCode.Accepted
                case "AlreadyPresent" => ResponseCode.AlreadyPresent
                case "InputsReserved" => ResponseCode.InputsReserved
                case "Pending"        => ResponseCode.Pending
                case "Included"       => ResponseCode.Included
                case "State"          => ResponseCode.State
                case "Rejected"       => ResponseCode.Rejected
                case "Unsupported"    => ResponseCode.Unsupported
                case "StaleState"     => ResponseCode.StaleState
                case "Unavailable"    => ResponseCode.Unavailable
                case _                => ResponseCode.Unknown
              LastObservation(stage, code)
            }
          case _ => None
      }
      .toOption
      .flatten
    Failure.ClientFailure(cause, Operation.HttpClient, last)

  private def readClient(exchange: Path): IO[Bytes] = IO.blocking {
    val in = Files.newInputStream(exchange.resolve("submission/service-client-result.json"))
    val raw =
      try in.readNBytes(65537)
      finally in.close()
    require(raw.length <= 65536, "client evidence size")
    Bytes.fromArray(raw)
  }

  private[lab] def stateOwner(bytes: Bytes): String =
    import ReferenceJson.{Json as J, field, string}
    val json = ReferenceJson.parse(bytes)
    require(field(json, "profileId") == J.Str(AdmissionProfile.PlutusV3.id))
    require(field(json, "fullLedgerValidated") == J.Lit("false"))
    require(field(json, "volatile") == J.Lit("true"))
    val pin = field(json, "pin")
    require(field(pin, "profileId") == J.Str(AdmissionProfile.PlutusV3.id))
    val owner = string(field(pin, "ownerId"))
    require(owner.matches("[0-9a-f]{64}"), "owner identity")
    owner
  private[lab] def validateClientOwner(bytes: Bytes, expected: String): Unit =
    import ReferenceJson.{Json as J, field}
    val json = ReferenceJson.parse(bytes)
    require(field(json, "schema") == J.Str("plutus-service-client-result-v1"))
    require(field(json, "passed") == J.Lit("true"))
    require(field(json, "sameOwner") == J.Lit("true"))
    require(field(json, "profileId") == J.Str(AdmissionProfile.PlutusV3.id))
    require(field(json, "fullLedgerValidated") == J.Lit("false"))
    require(expected.matches("[0-9a-f]{64}"))
    def pinOwner(pin: J): Unit =
      require(field(pin, "ownerId") == J.Str(expected), "client changed borrowed owner")
      require(field(pin, "profileId") == J.Str(AdmissionProfile.PlutusV3.id))
    field(json, "transactions") match
      case J.Arr(rows) =>
        require(rows.size == 2, "two client transactions")
        rows.foreach { row =>
          pinOwner(field(row, "acceptedResponse", "receipt", "pin"))
          pinOwner(field(row, "includedResponse", "pin"))
        }
      case _ => throw new IllegalArgumentException("client transactions array")
    Vector("betweenStateResponse", "afterSecondStateResponse", "firstStatusAfterSecondResponse")
      .foreach(name => pinOwner(field(json, name, "pin")))

  private[lab] def saveCheckpoint(exchange: Path, index: Int, bytes: Bytes): IO[Unit] =
    IO.blocking {
      require(index >= 0 && index < 128 && bytes.size > 0 && bytes.size <= 65536)
      val target = exchange.resolve(s"submission/scenario-checkpoint-$index.json")
      val temporary = target.resolveSibling(target.getFileName.toString + ".part")
      Files.write(temporary, bytes.toArray, Open.CREATE_NEW, Open.WRITE)
      Files.createLink(target, temporary)
      ()
    }

  def borrowed(port: Int, exchange: Path, clientSeconds: Int): Resource[IO, Adapter] =
    Resource
      .eval((Ref.of[IO, Option[String]](None), Ref.of[IO, (Int, Long)]((0, 0L))).tupled)
      .evalMap { (ownerRef, checkpoints) =>
        IO {
          require(port > 0 && port <= 65535 && exchange.isAbsolute)
          require(clientSeconds >= 1 && clientSeconds <= 60)
          new Adapter:
            val capabilities = Set(Requirement.SameEpochService)
            private def state: IO[Bytes] = IO
              .blocking {
                val connection = new java.net.URI(s"http://127.0.0.1:$port/v1/state").toURL
                  .openConnection(Proxy.NO_PROXY)
                  .asInstanceOf[java.net.HttpURLConnection]
                connection.setConnectTimeout(2000)
                connection.setReadTimeout(2000)
                connection.setInstanceFollowRedirects(false)
                try
                  require(connection.getResponseCode == 200, "state HTTP status")
                  val in = connection.getInputStream
                  val raw =
                    try in.readNBytes(65537)
                    finally in.close()
                  require(raw.length <= 65536, "state response size")
                  val bytes = Bytes.fromArray(raw)
                  stateOwner(bytes)
                  bytes
                finally connection.disconnect()
              }
              .flatMap { bytes =>
                val observed = stateOwner(bytes)
                ownerRef
                  .modify {
                    case None                   => (Some(observed), true)
                    case previous @ Some(owner) => (previous, owner == observed)
                  }
                  .flatMap(ok => IO(require(ok, "service owner changed")).as(bytes))
              }
            def execute(s: Scenario): IO[Either[Failure, Unit]] = s match
              case Scenario.ObserveService => state.as(Right(()))
              case Scenario.TwoSequentialTransfers =>
                state *> PlutusServiceClientMain
                  .run(List(port.toString, clientSeconds.toString, exchange.toString))
                  .flatMap { code =>
                    if code == ExitCode.Success then IO.pure(Right(()))
                    else
                      readClient(exchange)
                        .map(bytes => Left(clientFailure(bytes)))
                        .handleError(_ =>
                          Left(
                            Failure
                              .ClientFailure(FailureCause.EvidenceUnavailable, Operation.HttpClient)
                          )
                        )
                  }
              case _ => IO.pure(Left(Failure.ActionRejected))
            def observe(s: Scenario): IO[Bytes] =
              val observation = s match
                case Scenario.TwoSequentialTransfers =>
                  IO.blocking {
                    val in =
                      Files.newInputStream(
                        exchange.resolve("submission/service-client-result.json")
                      )
                    val raw =
                      try in.readNBytes(65537)
                      finally in.close()
                    require(raw.length <= 65536, "client evidence size")
                    Bytes.fromArray(raw)
                  }.flatMap { bytes =>
                    ownerRef.get.flatMap {
                      case Some(expected) => IO(validateClientOwner(bytes, expected)).as(bytes)
                      case None =>
                        IO.raiseError(new IllegalStateException("missing borrowed owner"))
                    }
                  }
                case Scenario.ObserveService => state
                case _ => IO.raiseError(new IllegalArgumentException("unsupported observation"))
              observation.flatTap { bytes =>
                checkpoints
                  .modify { case (index, used) =>
                    if index >= 128 || bytes.size <= 0 || used + bytes.size > 65536 then
                      ((index, used), None)
                    else ((index + 1, used + bytes.size), Some(index))
                  }
                  .flatMap {
                    case None => IO.raiseError(new IllegalStateException("checkpoint evidence cap"))
                    case Some(index) => saveCheckpoint(exchange, index, bytes)
                  }
              }
        }
      }
