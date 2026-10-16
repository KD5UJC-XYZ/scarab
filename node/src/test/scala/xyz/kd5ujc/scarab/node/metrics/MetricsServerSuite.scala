// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.node.MetricsServer
import xyz.kd5ujc.scarab.storage.StorageEvent

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

  test(
    "a scrape during a replay shows the phase and the progress, and leaves the gauges of a collector not yet ready absent"
  ) {
    val serving =
      for
        metrics <- Resource.eval(Metrics.create[IO](Catalogue.all))
        given Metrics[IO] = metrics
        measured <- Resource.eval(EventMetrics.create[IO])
        collectors <- Resource.eval(Collectors.Registry.create[IO])
        address <- Resource.eval(
          IO.fromOption(
            (Host.fromString("127.0.0.1"), Port.fromInt(0)).mapN(SocketAddress(_, _))
          )(new IllegalStateException("address"))
        )
        running <- MetricsServer.resource[IO](address, collectors.scrape)
      yield (running.address.getPort, metrics, measured, collectors)

    def samples(port: Int): IO[Vector[TextFormat.Sample]] =
      get(port, "/metrics").map { response =>
        assertEquals(response.statusCode, 200)
        TextFormat.parse(response.body).fold(fail(_), _.samples)
      }
    def value(all: Vector[TextFormat.Sample], name: String): Option[Double] =
      all.find(_.name == name).map(_.value)
    def phase(all: Vector[TextFormat.Sample], label: String): Option[Double] =
      all
        .find(s => s.name == "scarab_node_phase" && s.labels.contains("phase" -> label))
        .map(_.value)

    serving.use { (port, metrics, measured, collectors) =>
      val block = xyz.kd5ujc.scarab.consensus.BlockRef(
        BigInt(3000),
        xyz.kd5ujc.scarab.codec.Bytes.fromHex("ab" * 32).fold(e => fail(e.toString), identity),
        BigInt(300)
      )
      for
        restoring <- samples(port)
        _ <- measured.storage.emit(StorageEvent.ReplayStarted(None, Some(block)))
        _ <- measured.storage.emit(StorageEvent.ReplayProgress(block, 100L, 1500L))
        replaying <- samples(port)
        // the chain collector is added once its source exists
        _ <- collectors.add(metrics.set(Catalogue.blockNum, 300.0))
        _ <- measured.phase(PhaseLabel.Following)
        following <- samples(port)
      yield
        assertEquals(phase(restoring, "Restoring"), Some(1.0))
        assertEquals(value(restoring, "scarab_replay_block"), None)
        assertEquals(phase(replaying, "Replaying"), Some(1.0))
        assertEquals(value(replaying, "scarab_replay_target_block"), Some(300.0))
        assertEquals(value(replaying, "scarab_replay_block"), Some(300.0))
        assertEquals(value(replaying, "scarab_replay_blocks"), Some(100.0))
        assertEquals(value(replaying, "cardano_node_metrics_blockNum_int"), None)
        assertEquals(phase(following, "Following"), Some(1.0))
        assertEquals(value(following, "cardano_node_metrics_blockNum_int"), Some(300.0))
    }
  }
