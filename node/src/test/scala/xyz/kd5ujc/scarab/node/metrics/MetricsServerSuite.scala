// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import java.io.InputStream
import java.net.{Socket, SocketException, SocketTimeoutException, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import munit.CatsEffectSuite
import scala.annotation.tailrec
import scala.concurrent.duration.*
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
        val parsed = TextFormat.parse(response.body).fold(e => fail(e.toString), identity)
        assert(
          parsed.samples.exists(s =>
            s.name == "cardano_node_metrics_blockNum_int" && s.value == 12.0
          )
        )
      }
    }
  }

  /** The metrics server's shutdown timeout is 1 s; Ember's request header timeout, 5 s, must not be
    * what ends a connection when the server stops.
    */
  private val ShutdownBound = 4.seconds
  private val Request = "GET /metrics HTTP/1.1\r\nHost: localhost\r\n\r\n"

  /** The server on an ephemeral port, until the second value is run: it stops the server and gives
    * the time stopping took. Released, it stops the server if the test did not.
    */
  private def running: Resource[IO, (Int, IO[FiniteDuration])] =
    Resource
      .make(
        for
          ready <- Deferred[IO, Int]
          stop <- Deferred[IO, Unit]
          serving <- server.use(port => ready.complete(port) *> stop.get).start
          port <- ready.get
        yield (port, stop, serving)
      )((_, stop, serving) => stop.complete(()).void *> serving.join.void)
      .map((port, stop, serving) =>
        (port, (stop.complete(()) *> serving.joinWithNever).timed.map(_._1))
      )

  /** A raw connection to the listener, closed whatever happens; its reads give up after 10 s. */
  private def connection(port: Int): Resource[IO, Socket] =
    Resource.make(IO.blocking {
      val socket = new Socket("127.0.0.1", port)
      socket.setSoTimeout(10000)
      socket
    })(socket => IO.blocking(socket.close()))

  private def send(socket: Socket, text: String): IO[Unit] =
    IO.blocking {
      socket.getOutputStream.write(text.getBytes(StandardCharsets.US_ASCII))
      socket.getOutputStream.flush()
    }

  @tailrec
  private def head(in: InputStream, read: Vector[Byte]): String =
    if read.takeRight(4) == Vector[Byte](13, 10, 13, 10) then
      new String(read.toArray, StandardCharsets.US_ASCII)
    else
      in.read() match
        case -1   => new String(read.toArray, StandardCharsets.US_ASCII)
        case byte => head(in, read :+ byte.toByte)

  /** One whole response: its status line and the length of the body its headers state, read. */
  private def response(socket: Socket): IO[(String, Int)] =
    IO.blocking {
      val in = socket.getInputStream
      val lines = head(in, Vector.empty).linesIterator.toVector
      val length = lines.collectFirst {
        case line if line.toLowerCase.startsWith("content-length:") =>
          line.drop("content-length:".length).trim.toIntOption.getOrElse(0)
      }
      (lines.headOption.getOrElse(""), in.readNBytes(length.getOrElse(0)).length)
    }

  /** What follows until the server ends the connection: nothing (an end of stream) or a reset. A
    * read that times out is not an end.
    */
  private def ended(socket: Socket): IO[Either[String, Unit]] =
    IO.blocking(socket.getInputStream.readAllBytes()).attempt.map {
      case Right(rest) if rest.isEmpty     => Right(())
      case Right(rest)                     => Left(s"${rest.length} more bytes")
      case Left(_: SocketTimeoutException) => Left("still open after 10 s")
      case Left(_: SocketException)        => Right(())
      case Left(other)                     => Left(other.toString)
    }

  test(
    "the server stops within its shutdown timeout with an idle keep-alive connection, and ends it"
  ) {
    running.use { (port, stopping) =>
      connection(port).use { socket =>
        for
          _ <- send(socket, Request)
          first <- response(socket)
          took <- stopping
          end <- ended(socket)
        yield
          assertEquals(first._1, "HTTP/1.1 200 OK", "the request was answered before the stop")
          assert(first._2 > 0, "with its whole body")
          assert(took < ShutdownBound, s"the server took $took to stop")
          assertEquals(end, Right(()), "the idle connection was ended")
      }
    }
  }

  test("the server stops within its shutdown timeout with a request half sent, and ends it") {
    running.use { (port, stopping) =>
      connection(port).use { socket =>
        for
          _ <- send(socket, Request)
          first <- response(socket)
          _ <- send(socket, "GET /metrics HTTP/1.1\r\nHost: loc")
          took <- stopping
          end <- ended(socket)
        yield
          assertEquals(first._1, "HTTP/1.1 200 OK", "the connection was accepted and served")
          assert(took < ShutdownBound, s"the server took $took to stop")
          assertEquals(end, Right(()), "the connection with a half-sent request was ended")
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
        TextFormat.parse(response.body).fold(e => fail(e.toString), _.samples)
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
