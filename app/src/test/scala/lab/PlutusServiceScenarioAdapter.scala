// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{ExitCode, IO, Ref, Resource}
import lab.submission.AdmissionProfile
import java.net.Proxy
import java.nio.file.{Files, Path}
import lab.cbor.Bytes
import SequentialDevnetRunner.*

/** Borrow one already-owned local service. Cluster startup/cleanup remains the supervisor's
  * Resource responsibility. This adapter adds no JVM or cluster, and never submits to reference.
  */
object PlutusServiceScenarioAdapter:
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
  def borrowed(port: Int, exchange: Path, clientSeconds: Int): Resource[IO, Adapter] =
    Resource.eval(Ref.of[IO, Option[String]](None)).evalMap { ownerRef =>
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
                .map(code =>
                  if code == ExitCode.Success then Right(()) else Left(Failure.ActionRejected)
                )
            case _ => IO.pure(Left(Failure.ActionRejected))
          def observe(s: Scenario): IO[Bytes] = s match
            case Scenario.TwoSequentialTransfers =>
              IO.blocking {
                val in =
                  Files.newInputStream(exchange.resolve("submission/service-client-result.json"))
                val raw =
                  try in.readNBytes(65537)
                  finally in.close()
                require(raw.length <= 65536, "client evidence size")
                Bytes.fromArray(raw)
              }
            case Scenario.ObserveService => state
            case _ => IO.raiseError(new IllegalArgumentException("unsupported observation"))
      }
    }
