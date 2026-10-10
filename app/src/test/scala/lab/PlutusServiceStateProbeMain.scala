// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, IOApp, ExitCode}
import java.nio.file.{Files, Path, StandardOpenOption as Open}
import scala.concurrent.duration.*
import ReferenceJson.Json as J

/** One bounded read-only HTTP observation; no transaction endpoint is used. */
object PlutusServiceStateProbeMain extends IOApp:
  def run(args: List[String]): IO[ExitCode] = args match
    case portText :: output :: Nil =>
      for
        port <- IO(portText.toInt)
        path <- IO(Path.of(output))
        _ <- IO(require(port > 0 && port <= 65535 && path.isAbsolute))
        observed <- PlutusMultiEndpointHttp.http
          .use { client =>
            for
              started <- IO.realTime.map(_.toMillis)
              response <- PlutusMultiEndpointHttp.request(client, port, "/v1/state", None)
              _ <- IO(PlutusMultiEndpointHttp.stateReply(response._1, response._2))
              ended <- IO.realTime.map(_.toMillis)
            yield (started, ended, response._2)
          }
          .timeout(15.seconds)
        _ <- IO.blocking {
          val raw = SyntheticRewardProjection.encode(
            J.Obj(
              Map(
                "schema" -> J.Str("plutus-service-state-probe-v1"),
                "startedUnixMillis" -> J.Num(observed._1.toString),
                "endedUnixMillis" -> J.Num(observed._2.toString),
                "state" -> observed._3,
                "resourcesFinalized" -> J.Lit("true"),
                "diagnosticOnly" -> J.Lit("true"),
                "fullLedgerValidated" -> J.Lit("false")
              )
            )
          )
          require(raw.size <= 65536)
          val part = path.resolveSibling(path.getFileName.toString + ".part")
          Files.write(part, raw.toArray, Open.CREATE_NEW, Open.WRITE)
          Files.createLink(path, part)
        }
      yield ExitCode.Success
    case _ => IO.raiseError(new IllegalArgumentException("expected PORT OUTPUT"))
