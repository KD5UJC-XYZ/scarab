// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.node.MetricsServer

/** The endpoint answers a scrape with the text format and nothing else. */
class MetricsServerSuite extends CatsEffectSuite:
  private def get(port: Int, path: String): IO[HttpResponse[String]] =
    IO.blocking(
      HttpClient
        .newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .build()
        .send(
          HttpRequest.newBuilder(URI.create(s"http://127.0.0.1:$port$path")).GET().build(),
          HttpResponse.BodyHandlers.ofString()
        )
    )

  private def server: Resource[IO, Int] =
    for
      metrics <- Resource.eval(Metrics.create[IO](Catalogue.all))
      address <- Resource.eval(
        IO.fromOption(
          (Host.fromString("127.0.0.1"), Port.fromInt(0)).mapN(SocketAddress(_, _))
        )(new IllegalStateException("address"))
      )
      running <- MetricsServer.resource[IO](
        address,
        metrics.set(Catalogue.blockNum, 12.0) *> metrics.scrape(Nil)
      )
    yield running.address.getPort

  test("GET /metrics answers the text format 0.0.4 and runs the scrape each time") {
    server.use { port =>
      get(port, "/metrics").map { response =>
        assertEquals(response.statusCode, 200)
        assertEquals(
          response.headers.firstValue("Content-Type").orElse(""),
          "text/plain; version=0.0.4; charset=utf-8"
        )
        val parsed = TextFormat.parse(response.body).fold(fail(_), identity)
        assert(
          parsed.samples.exists(s =>
            s.name == "cardano_node_metrics_blockNum_int" && s.value == 12.0
          )
        )
      }
    }
  }

  test("nothing else is served on the metrics listener") {
    server.use(port => get(port, "/state").map(r => assertEquals(r.statusCode, 404)))
  }
