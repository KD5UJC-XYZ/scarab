// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

class LoopbackSuite extends munit.FunSuite:
  private val byte = Bytes(Vector(1.toByte))
  test("bounded pair transfers bytes and rejects empty and oversized writes") {
    Loopback
      .pair[IO](Loopback.Limits(1, 2))
      .use { case (a, b) =>
        for
          _ <- a.write(byte)
          got <- b.read
          empty <- a.write(Bytes.empty).attempt
          large <- a.write(Bytes(Vector.fill(3)(0.toByte))).attempt
        yield
          assertEquals(got, Some(byte))
          assert(empty.isLeft)
          assert(large.isLeft)
      }
      .unsafeToFuture()
  }
  test("resource closes endpoints and blocked reads and writers without orphan fibers") {
    (for
      released <- Loopback.pair[IO](Loopback.Limits(1, 2)).use { case (a, b) =>
        for
          _ <- a.write(byte)
          blockedWriter <- a.write(byte).attempt.start
          blockedReader <- a.read.start
          _ <- b.close
          writer <- blockedWriter.joinWithNever
          reader <- blockedReader.joinWithNever
        yield
          assert(writer.isLeft)
          assertEquals(reader, None)
          a
      }
      closed <- released.isClosed
      _ = assert(closed)
    yield ()).timeout(2.seconds).unsafeToFuture()
  }
  test("canceling resource use releases a waiting stream") {
    (for
      ready <- Deferred[IO, ByteTransport[IO]]
      fiber <- Loopback
        .pair[IO]()
        .use { case (a, _) =>
          ready.complete(a) *> a.read.void
        }
        .start
      endpoint <- ready.get
      _ <- fiber.cancel
      closed <- endpoint.isClosed
    yield assert(closed)).timeout(2.seconds).unsafeToFuture()
  }
  test("invalid local bounds fail before allocation") {
    Loopback
      .pair[IO](Loopback.Limits(0, 1))
      .use(_ => IO.unit)
      .attempt
      .map(r => assert(r.isLeft))
      .unsafeToFuture()
  }
