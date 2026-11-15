// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.{Deferred, IO, Resource}
import cats.syntax.all.*
import com.comcast.ip4s.{Host, Port, SocketAddress}
import java.io.InputStream
import java.net.{Socket, SocketException, SocketTimeoutException, URI}
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import scala.annotation.tailrec
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.FailsOnUncaught
import xyz.kd5ujc.scarab.node.MetricsServer
import xyz.kd5ujc.scarab.storage.StorageEvent

/** The endpoint answers a scrape with the text format and nothing else. */
class MetricsServerSuite extends FailsOnUncaught:
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

  /** The server on an ephemeral port, until the second value is run, which stops it and returns
    * when it has. Released, it stops the server if the test did not, and fails rather than wait
    * more than 10 s for it; a server that ends before it listens fails the test at once.
    */
  private def running: Resource[IO, (Int, IO[Unit])] =
    for
      ready <- Resource.eval(Deferred[IO, Int])
      stop <- Resource.eval(Deferred[IO, Unit])
      serving <- Resource.make(server.use(port => ready.complete(port) *> stop.get).start)(
        serving => (stop.complete(()) *> serving.join).void.timeout(10.seconds)
      )
      port <- Resource.eval(
        IO.race(serving.joinWithNever, ready.get)
          .flatMap(_.fold(_ => IO.raiseError(new IllegalStateException("no listener")), IO.pure))
      )
    yield (port, stop.complete(()) *> serving.joinWithNever)

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

  /** One response: its status line, the body length its headers state, and the length read. */
  private final case class Answer(status: String, stated: Int, read: Int):
    def whole: Boolean = stated > 0 && read == stated

  private def response(socket: Socket): IO[Answer] =
    IO.blocking {
      val in = socket.getInputStream
      val lines = head(in, Vector.empty).linesIterator.toVector
      val stated = lines
        .collectFirst {
          case line if line.toLowerCase.startsWith("content-length:") =>
            line.drop("content-length:".length).trim.toIntOption.getOrElse(0)
        }
        .getOrElse(0)
      Answer(lines.headOption.getOrElse(""), stated, in.readNBytes(stated).length)
    }

  /** How a connection the server should end turned out. */
  private enum End:
    case Ended
    case MoreBytes(count: Int)
    case StillOpen
    case Failed(cause: Throwable)

  /** What follows until the server ends the connection: an end of stream or a reset ends it; a read
    * that times out does not.
    */
  private def ended(socket: Socket): IO[End] =
    IO.blocking(socket.getInputStream.readAllBytes()).attempt.map {
      case Right(rest) if rest.isEmpty     => End.Ended
      case Right(rest)                     => End.MoreBytes(rest.length)
      case Left(_: SocketTimeoutException) => End.StillOpen
      case Left(_: SocketException)        => End.Ended
      case Left(other)                     => End.Failed(other)
    }

  test(
    "the server stops within its shutdown timeout with an idle keep-alive connection, and ends it"
  ) {
    running.use { (port, stopping) =>
      connection(port).use { socket =>
        for
          _ <- send(socket, Request)
          first <- response(socket)
          stopped <- (stopping *> ended(socket)).timed
        yield
          assertEquals(first.status, "HTTP/1.1 200 OK", "the request was answered before the stop")
          assert(first.whole, s"with its whole body: $first")
          assert(stopped._1 < ShutdownBound, s"stopping and ending took ${stopped._1}")
          assertEquals(stopped._2, End.Ended, "the idle connection was ended")
      }
    }
  }

  test("the server stops within its shutdown timeout with a request half sent, and ends it") {
    running.use { (port, stopping) =>
      connection(port).use { socket =>
        // The partial request goes to a connection the server accepted and served; nothing shows
        // whether its bytes were read before the stop, only that the connection was open.
        for
          _ <- send(socket, Request)
          first <- response(socket)
          _ <- send(socket, "GET /metrics HTTP/1.1\r\nHost: loc")
          stopped <- (stopping *> ended(socket)).timed
        yield
          assertEquals(first.status, "HTTP/1.1 200 OK", "the connection was accepted and served")
          assert(first.whole, s"with its whole body: $first")
          assert(stopped._1 < ShutdownBound, s"stopping and ending took ${stopped._1}")
          assertEquals(stopped._2, End.Ended, "the connection with a half-sent request was ended")
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
