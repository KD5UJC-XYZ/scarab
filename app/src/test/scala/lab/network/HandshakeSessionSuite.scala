// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{IO, Ref, Deferred}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

class HandshakeSessionSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private val accept = bytes("000000008000000883010e8402f500f4")
  private def receiveScript(chunks: Vector[Bytes]): IO[Handshake.Message] =
    Loopback.pair[IO]().use { case (a, b) =>
      HandshakeSession
        .resource[IO](a, Handshake.Suite.NodeToNode, Mux.Direction.Initiator, 1.second)
        .use { session =>
          (chunks.traverse_(b.write), session.receive(Handshake.State.Confirm)).parTupled.map(_._2)
        }
    }
  test("every split of source-derived mux accept is reassembled") {
    (1 until accept.size).toVector
      .traverse_ { split =>
        receiveScript(Vector(Bytes(accept.value.take(split)), Bytes(accept.value.drop(split))))
          .map(message => assert(message.isInstanceOf[Handshake.Message.Accept]))
      }
      .unsafeToFuture()
  }
  test("CBOR is reassembled across two SDUs, not just TCP chunks") {
    receiveScript(Vector(bytes("000000008000000483010e84"), bytes("000000008000000402f500f4")))
      .map(message => assert(message.isInstanceOf[Handshake.Message.Accept]))
      .unsafeToFuture()
  }
  test("wrong mux direction, protocol, zero SDU, bad CBOR, huge SDU fail") {
    Vector(
      "000000000000000883010e8402f500f4",
      "000000008001000883010e8402f500f4",
      "0000000080000000",
      "0000000080000001ff",
      "000000008000ffff"
    ).traverse_ { hex =>
      receiveScript(Vector(bytes(hex))).attempt.map(r => assert(r.isLeft))
    }.unsafeToFuture()
  }
  test("phase timeout closes owned stream") {
    Loopback
      .pair[IO]()
      .use { case (a, _) =>
        HandshakeSession
          .resource[IO](a, Handshake.Suite.NodeToNode, Mux.Direction.Initiator, 20.millis)
          .use { session =>
            for
              result <- session.receive(Handshake.State.Confirm).attempt
              closed <- a.isClosed
            yield
              assert(
                result.left.toOption.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])
              )
              assert(closed)
          }
      }
      .unsafeToFuture()
  }
  test("EOF and cancellation terminate phase and close resources") {
    (for
      entered <- Deferred[IO, Unit]
      closed <- Ref.of[IO, Boolean](false)
      transport = new ByteTransport[IO]:
        def read: IO[Option[Bytes]] = entered.complete(()) *> IO.never
        def write(bytes: Bytes): IO[Unit] = IO.unit
        def close: IO[Unit] = closed.set(true)
        def isClosed: IO[Boolean] = closed.get
      _ <- HandshakeSession
        .resource[IO](transport, Handshake.Suite.NodeToNode, Mux.Direction.Initiator)
        .use { session =>
          for
            fiber <- session.receive(Handshake.State.Confirm).start
            _ <- entered.get
            _ <- fiber.cancel
            value <- closed.get
          yield assert(value)
        }
    yield ()).timeout(2.seconds).unsafeToFuture()
  }
  private def scripted(chunks: Vector[Bytes]): IO[ByteTransport[IO]] =
    Ref.of[IO, Vector[Bytes]](chunks).map { pending =>
      new ByteTransport[IO]:
        def read: IO[Option[Bytes]] = pending.modify(xs => (xs.drop(1), xs.headOption))
        def write(bytes: Bytes): IO[Unit] = IO.unit
        def close: IO[Unit] = IO.unit
        def isClosed: IO[Boolean] = IO.pure(false)
    }
  test("EOF distinguishes truncated mux header, payload and CBOR") {
    Vector("000000", "00000000800000088301", "00000000800000028301")
      .traverse_ { hex =>
        scripted(Vector(bytes(hex))).flatMap { transport =>
          HandshakeSession
            .resource[IO](transport, Handshake.Suite.NodeToNode, Mux.Direction.Initiator)
            .use {
              _.receive(Handshake.State.Confirm).attempt.map(r => assert(r.isLeft))
            }
        }
      }
      .unsafeToFuture()
  }
  test("coalesced CBOR messages and SDUs preserve leftovers for next receive") {
    val twoInSdu = bytes("000000008000001083010e8402f500f483010e8402f500f4")
    Vector(twoInSdu, Bytes(accept.value ++ accept.value))
      .traverse_ { input =>
        scripted(Vector(input)).flatMap { transport =>
          HandshakeSession
            .resource[IO](transport, Handshake.Suite.NodeToNode, Mux.Direction.Initiator)
            .use { session =>
              for
                first <- session.receive(Handshake.State.Confirm)
                second <- session.receive(Handshake.State.Confirm)
              yield assertEquals(first, second)
            }
        }
      }
      .unsafeToFuture()
  }

  test("a trickling peer cannot reset the whole-phase deadline") {
    (for
      pending <- Ref.of[IO, Vector[Byte]](accept.value)
      closed <- Ref.of[IO, Boolean](false)
      transport = new ByteTransport[IO]:
        def read: IO[Option[Bytes]] = IO.sleep(10.millis) *> pending.modify(xs =>
          (xs.drop(1), xs.headOption.map(x => Bytes(Vector(x))))
        )
        def write(bytes: Bytes): IO[Unit] = IO.unit
        def close: IO[Unit] = closed.set(true)
        def isClosed: IO[Boolean] = closed.get
      _ <- HandshakeSession
        .resource[IO](transport, Handshake.Suite.NodeToNode, Mux.Direction.Initiator, 30.millis)
        .use { session =>
          for
            result <- session.receive(Handshake.State.Confirm).attempt
            value <- closed.get
          yield
            assert(
              result.left.toOption.exists(_.isInstanceOf[java.util.concurrent.TimeoutException])
            )
            assert(value)
        }
    yield ()).unsafeToFuture()
  }
