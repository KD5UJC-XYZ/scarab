// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.net.{InetSocketAddress, Socket}
import lab.cbor.Bytes
import scala.concurrent.duration.*

class TcpLoopbackSuite extends munit.FunSuite:
  private val payload = Bytes.fromHex("0001020304050607").toOption.get

  private def collect(transport: ByteTransport[IO], size: Int): IO[Bytes] =
    def loop(acc: Vector[Byte]): IO[Bytes] =
      if acc.size >= size then IO.pure(Bytes(acc))
      else
        transport.read.flatMap {
          case Some(bytes) => loop(acc ++ bytes.value)
          case None        => IO.raiseError(new AssertionError("unexpected EOF"))
        }
    loop(Vector.empty)

  test("real IPv4 localhost connection carries bytes in both directions") {
    TcpLoopback
      .withAddress[IO]()
      .use { connection =>
        for
          _ <- IO {
            assertEquals(connection.address.getAddress.getHostAddress, "127.0.0.1")
            assert(connection.address.getPort > 0)
          }
          _ <- connection.left.write(payload)
          received <- collect(connection.right, payload.size)
          _ <- connection.right.write(payload)
          echoed <- collect(connection.left, payload.size)
        yield
          assertEquals(received, payload)
          assertEquals(echoed, payload)
      }
      .timeout(5.seconds)
      .unsafeToFuture()
  }

  test("reads are bounded and preserve a stream split across small writes") {
    TcpLoopback
      .pair[IO](TcpLoopback.Limits(2))
      .use { case (left, right) =>
        for
          _ <- payload.value.grouped(2).toList.traverse_(chunk => left.write(Bytes(chunk)))
          received <- {
            def readAll(acc: Vector[Byte]): IO[Bytes] =
              if acc.size == payload.size then IO.pure(Bytes(acc))
              else
                right.read.flatMap {
                  case Some(bytes) =>
                    IO(assert(bytes.size > 0 && bytes.size <= 2)) *> readAll(acc ++ bytes.value)
                  case None => IO.raiseError(new AssertionError("unexpected EOF"))
                }
            readAll(Vector.empty)
          }
        yield assertEquals(received, payload)
      }
      .timeout(5.seconds)
      .unsafeToFuture()
  }

  test("partial reads are normal: one byte can arrive before the next write") {
    TcpLoopback
      .pair[IO]()
      .use { case (left, right) =>
        for
          _ <- left.write(Bytes(Vector(1.toByte)))
          first <- right.read
          _ <- left.write(payload)
          rest <- collect(right, payload.size)
        yield
          assertEquals(first, Some(Bytes(Vector(1.toByte))))
          assertEquals(rest, payload)
      }
      .timeout(5.seconds)
      .unsafeToFuture()
  }

  test("peer close yields EOF after buffered bytes") {
    TcpLoopback
      .pair[IO]()
      .use { case (left, right) =>
        for
          _ <- left.write(payload)
          _ <- left.close
          bytes <- collect(right, payload.size)
          eof <- right.read
        yield
          assertEquals(bytes, payload)
          assertEquals(eof, None)
      }
      .timeout(5.seconds)
      .unsafeToFuture()
  }

  test("canceling a blocked read closes its channel and joins promptly") {
    TcpLoopback
      .pair[IO]()
      .use { case (left, right) =>
        for
          started <- Deferred[IO, Unit]
          reader <- (started.complete(()) *> left.read).start
          _ <- started.get *> IO.sleep(50.millis)
          _ <- reader.cancel
          outcome <- reader.join
          closed <- left.isClosed
          eof <- right.read
        yield
          assert(outcome.isCanceled)
          assert(closed)
          assertEquals(eof, None)
      }
      .timeout(5.seconds)
      .unsafeToFuture()
  }

  test("resource release closes both endpoints and its ephemeral listener") {
    (for
      allocated <- TcpLoopback.withAddress[IO]().allocated
      (connection, release) = allocated
      _ <- release
      leftClosed <- connection.left.isClosed
      rightClosed <- connection.right.isClosed
      connectFailed <- IO.blocking {
        val socket = new Socket()
        try
          socket.connect(new InetSocketAddress("127.0.0.1", connection.address.getPort), 500)
          false
        catch case _: java.io.IOException => true
        finally socket.close()
      }
    yield
      assert(leftClosed && rightClosed)
      assert(connectFailed)
    ).timeout(5.seconds).unsafeToFuture()
  }

  test("invalid limits and empty or oversized writes are rejected") {
    (for
      invalid <- List(0, -1, 65544).traverse { size =>
        TcpLoopback.pair[IO](TcpLoopback.Limits(size)).use(_ => IO.unit).attempt
      }
      _ <- IO(assert(invalid.forall(_.left.exists(_.isInstanceOf[IllegalArgumentException]))))
      _ <- TcpLoopback.pair[IO](TcpLoopback.Limits(2)).use { case (left, _) =>
        List(Bytes.empty, payload).traverse_(bytes =>
          left.write(bytes).attempt.flatMap(result => IO(assert(result.isLeft)))
        )
      }
    yield ()).timeout(5.seconds).unsafeToFuture()
  }
