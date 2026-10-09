// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.std.Semaphore
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import java.net.Socket
import java.nio.charset.StandardCharsets
import scala.concurrent.duration.*

class AdaHttpSuite extends munit.FunSuite:
  private def client(port: Int): Resource[IO, Socket] =
    Resource.make(IO.blocking {
      val socket = new Socket("127.0.0.1", port)
      socket.setSoTimeout(7000)
      socket
    })(s => IO.blocking(s.close()))

  private def exchange(port: Int, request: String, body: Bytes = Bytes.empty): IO[String] =
    client(port).use { socket =>
      IO.blocking {
        socket.getOutputStream.write(request.getBytes(StandardCharsets.US_ASCII))
        socket.getOutputStream.write(body.toArray)
        socket.getOutputStream.flush()
        new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
      }
    }

  private def handler(seen: Ref[IO, Vector[Bytes]]): AdaHttp.Handler[IO] =
    new AdaHttp.Handler[IO]:
      def request =
        Resource.pure[IO, Option[AdaHttp.RequestHandler[IO]]](Some(new AdaHttp.RequestHandler[IO]:
          def submit(original: Bytes) =
            seen.update(_ :+ original).as(AdaHttp.Response(202, "{\"code\":\"Accepted\"}"))
          def transaction(id: Bytes) = IO.pure(AdaHttp.Response(200, s"""{"id":"${id.hex}"}"""))
          def state = IO.pure(AdaHttp.Response(200, "{\"code\":\"State\"}"))))

  test("POST delivers unchanged CBOR bytes and closes the connection") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      original = Bytes.fromHex("84a0a0f5f6").toOption.get
      _ <- AdaHttp.server(handler(seen)).use { bound =>
        exchange(
          bound.port,
          "POST /v1/transactions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/cbor\r\nContent-Length: 5\r\n\r\n",
          original
        ).map { response =>
          assert(response.startsWith("HTTP/1.1 202"))
          assert(response.contains("Connection: close"))
        }
      }
      values <- seen.get
      _ = assertEquals(values, Vector(original))
    yield ()).unsafeToFuture()
  }

  test("GET state and exact transaction IDs use their handlers") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      _ <- AdaHttp.server(handler(seen)).use { bound =>
        for
          state <- exchange(bound.port, "GET /v1/state HTTP/1.1\r\nHost: localhost\r\n\r\n")
          id = "ab" * 32
          status <- exchange(
            bound.port,
            s"GET /v1/transactions/$id HTTP/1.1\r\nHost: localhost\r\n\r\n"
          )
          invalid <- exchange(
            bound.port,
            "GET /v1/transactions/xx HTTP/1.1\r\nHost: localhost\r\n\r\n"
          )
          _ = assert(state.contains("State"))
          _ = assert(status.contains(id))
          _ = assert(invalid.startsWith("HTTP/1.1 400"))
        yield ()
      }
    yield ()).unsafeToFuture()
  }

  test("oversized, ambiguous and unsupported framing fails before submission") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      _ <- AdaHttp.server(handler(seen)).use { bound =>
        val prefix =
          "POST /v1/transactions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/cbor\r\n"
        Vector(
          "Content-Length: 65537\r\n" -> 413,
          "Content-Length: 0\r\nContent-Length: 0\r\n" -> 400,
          "Transfer-Encoding: chunked\r\n" -> 400,
          "Content-Length: -1\r\n" -> 400,
          "Content-Length: 99999999999\r\n" -> 400,
          "" -> 411
        ).traverse_ { case (headers, status) =>
          exchange(bound.port, prefix + headers + "\r\n")
            .map(response => assert(response.startsWith(s"HTTP/1.1 $status")))
        }
      }
      values <- seen.get
      _ = assertEquals(values, Vector.empty)
    yield ()).unsafeToFuture()
  }

  test("request resource is acquired before bytes arrive and shutdown releases it") {
    (for
      acquired <- Deferred[IO, Unit]
      released <- Deferred[IO, Unit]
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      base = handler(seen)
      managed = new AdaHttp.Handler[IO]:
        def request =
          Resource.make(acquired.complete(()).void)(_ => released.complete(()).void) *> base.request
      _ <- AdaHttp.server(managed).use { bound =>
        client(bound.port).use(_ => acquired.get.timeout(2.seconds))
      }
      _ <- released.get.timeout(2.seconds)
      values <- seen.get
      _ = assertEquals(values, Vector.empty)
    yield ()).unsafeToFuture()
  }

  test("unavailable request resource returns503 without reading a body") {
    val unavailable = new AdaHttp.Handler[IO]:
      def request = Resource.pure[IO, Option[AdaHttp.RequestHandler[IO]]](None)
    AdaHttp
      .server(unavailable)
      .use { bound =>
        client(bound.port).use { socket =>
          IO.blocking(new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8))
            .map(response => assert(response.startsWith("HTTP/1.1 503")))
        }
      }
      .unsafeToFuture()
  }

  test("eight sockets bound admitted resources and a ninth closes without admission") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      active <- Ref.of[IO, Int](0)
      ready <- Deferred[IO, Unit]
      base = handler(seen)
      managed = new AdaHttp.Handler[IO]:
        def request = Resource.make(
          active
            .updateAndGet(_ + 1)
            .flatMap(n => if n == 8 then ready.complete(()).void else IO.unit)
        )(_ => active.update(_ - 1)) *> base.request
      _ <- AdaHttp.server(managed).use { bound =>
        List.fill(8)(client(bound.port)).sequence.use { _ =>
          ready.get.timeout(2.seconds) *> client(bound.port).use { extra =>
            IO.blocking(extra.getInputStream.read()).map(value => assertEquals(value, -1))
          } *> active.get.map(count => assertEquals(count, 8))
        }
      }
      count <- active.get
      _ = assertEquals(count, 0)
    yield ()).unsafeToFuture()
  }

  test("the complete header/body read has a five second deadline") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      _ <- AdaHttp.server(handler(seen)).use { bound =>
        client(bound.port).use { socket =>
          IO.blocking {
            socket.getOutputStream.write(
              "POST /v1/transactions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/cbor\r\nContent-Length: 5\r\n\r\n"
                .getBytes(StandardCharsets.US_ASCII)
            )
            socket.getOutputStream.flush()
            new String(socket.getInputStream.readAllBytes(), StandardCharsets.UTF_8)
          }.map(response => assert(response.startsWith("HTTP/1.1 408")))
        }
      }
      values <- seen.get
      _ = assertEquals(values, Vector.empty)
    yield ()).unsafeToFuture()
  }

  test("canceling server after accepted publication cannot remove the admitted entry") {
    (for
      stored <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      accepted <- Deferred[IO, Unit]
      disposed <- Deferred[IO, Unit]
      original = Bytes.fromHex("84a0a0f5f6").toOption.get
      managed = new AdaHttp.Handler[IO]:
        def request = Resource
          .make(IO.unit)(_ => disposed.complete(()).void)
          .as(Some(new AdaHttp.RequestHandler[IO]:
            def submit(bytes: Bytes) =
              stored.update(_ :+ bytes) *> accepted.complete(()).void *> IO.never[AdaHttp.Response]
            def transaction(id: Bytes) = IO.pure(AdaHttp.Response(200, "{}"))
            def state = IO.pure(AdaHttp.Response(200, "{}"))): Option[AdaHttp.RequestHandler[IO]])
      _ <- AdaHttp
        .server(managed)
        .use { bound =>
          client(bound.port).use { socket =>
            IO.blocking {
              socket.getOutputStream.write(
                "POST /v1/transactions HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/cbor\r\nContent-Length: 5\r\n\r\n"
                  .getBytes(StandardCharsets.US_ASCII)
              )
              socket.getOutputStream.write(original.toArray)
              socket.getOutputStream.flush()
            } *> accepted.get.timeout(2.seconds)
          }
        }
        .timeout(3.seconds)
      _ <- disposed.get.timeout(1.second)
      values <- stored.get
      _ = assertEquals(values, Vector(original))
    yield ()).unsafeToFuture()
  }

  test("parent registry drains sockets whose child never started and returns each permit once") {
    (for
      permits <- Semaphore[IO](8)
      channels <- Resource
        .make(IO(Vector.fill(8)(java.nio.channels.SocketChannel.open())))(values =>
          IO(values.foreach(_.close()))
        )
        .use { sockets =>
          AdaHttp
            .socketOwners(permits)
            .allocated
            .flatMap { (owners, shutdown) =>
              (for
                _ <- sockets.traverse_(socket => permits.acquire *> owners.adopt(socket))
                _ <- owners.release(sockets.head)
                _ <- owners.release(sockets.head)
                remaining <- owners.size
                available <- permits.available
                _ = assertEquals(remaining, 7)
                _ = assertEquals(available, 1L)
                // None of the remaining sockets ever had a child effect run.
                _ <- shutdown
                _ <- sockets.traverse_(owners.release)
                empty <- owners.size
                restored <- permits.available
                _ = assertEquals(empty, 0)
                _ = assertEquals(restored, 8L)
                _ = assert(sockets.forall(socket => !socket.isOpen))
              yield ()).guarantee(shutdown)
            }
            .as(sockets.size)
        }
      _ = assertEquals(channels, 8)
    yield ()).unsafeToFuture()
  }

  test("repeated connection handoffs racing shutdown close sockets and release request resources") {
    (for
      seen <- Ref.of[IO, Vector[Bytes]](Vector.empty)
      active <- Ref.of[IO, Int](0)
      base = handler(seen)
      managed = new AdaHttp.Handler[IO]:
        def request = Resource.make(active.update(_ + 1))(_ => active.update(_ - 1)) *> base.request
      _ <- (0 until 12).toList.traverse_ { _ =>
        AdaHttp.server(managed).allocated.flatMap { (bound, shutdown) =>
          List
            .fill(8)(client(bound.port))
            .sequence
            .use { sockets =>
              shutdown.timeout(2.seconds) *> sockets.traverse_ { socket =>
                IO.blocking {
                  socket.setSoTimeout(1000)
                  try assertEquals(socket.getInputStream.read(), -1)
                  catch
                    case _: java.net.SocketException => () // Listener-close may reset queued peers.
                }
              } *> active.get.map(value => assertEquals(value, 0))
            }
            .guarantee(shutdown)
        }
      }
    yield ()).timeout(15.seconds).unsafeToFuture()
  }
