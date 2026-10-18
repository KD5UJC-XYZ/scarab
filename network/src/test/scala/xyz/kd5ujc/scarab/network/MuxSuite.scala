// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.syntax.all.*
import fs2.Chunk
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.Mux as WireMux
import munit.{CatsEffectSuite, ScalaCheckSuite}
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll
import scala.concurrent.duration.*

import Peers.*

class MuxSuite extends FailsOnUncaught with ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(40)

  /** Decode exactly `n` bytes: the shape of a message whose length is known to both sides. */
  private def exactly(n: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= n then DecodeResult.Decoded(Bytes(bytes.value.take(n)), n)
    else DecodeResult.NeedMore

  private def payload(n: Int, seed: Int = 7): Bytes =
    Bytes(Vector.tabulate(n)(i => ((i * 31 + seed) & 0xff).toByte))

  test("messages are routed to their mini-protocol channel"):
    typed {
      linked().use { (client, server) =>
        for
          c2 <- client.channel(2)
          c3 <- client.channel(3)
          s2 <- server.channel(2)
          s3 <- server.channel(3)
          _ <- c3.send(payload(5, 3))
          _ <- c2.send(payload(7, 2))
          at2 <- s2.recv(1000)(exactly(7))
          at3 <- s3.recv(1000)(exactly(5))
          _ <- s2.send(payload(4, 9))
          back <- c2.recv(1000)(exactly(4))
        yield (at2, at3, back)
      }
    }.map(r => assertEquals(r, Right((payload(7, 2), payload(5, 3), payload(4, 9)))))

  test("a message larger than one SDU is split and reassembled"):
    typed {
      linked(
        client = Mux.Config(maxSduPayload = 100),
        server = Mux.Config(role = Mux.Role.Responder, maxSduPayload = 100)
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(1050))
          got <- s.recv(5000)(exactly(1050))
        yield got
      }
    }.map(r => assertEquals(r, Right(payload(1050))))

  test("the initiator sends with the mode bit clear and a valid 32-bit timestamp"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          for
            c <- mux.channel(2)
            _ <- c.send(payload(10))
            bytes <- raw.reads.take(18).compile.to(Chunk)
          yield WireMux.decode(Bytes(bytes.toVector)).map(sdu => (sdu, bytes(4) & 0x80))
        }
      }
    }.map {
      case Right(Right((sdu, modeBit))) =>
        assertEquals(sdu.direction, WireMux.Direction.Initiator)
        assertEquals(modeBit, 0)
        assertEquals(sdu.protocol, 2)
        assertEquals(sdu.payload, payload(10))
      case other => fail(s"unexpected $other")
    }

  test("responses arrive with the mode bit set"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          for
            c <- mux.channel(2)
            sdu = WireMux.Sdu(5L, 2, WireMux.Direction.Responder, payload(6))
            encoded <- IO.fromEither(WireMux.encode(sdu).leftMap(e => new Exception(e.message)))
            _ <- raw.write(Chunk.array(encoded.toArray))
            got <- c.recv(100)(exactly(6))
          yield (got, encoded.value(4) & 0x80)
        }
      }
    }.map(r => assertEquals(r, Right((payload(6), 0x80))))

  property("any payload segmented at any SDU size reassembles exactly"):
    forAll(Gen.choose(1, 4000), Gen.choose(1, 500), Gen.choose(0, 255)) { (size, sdu, seed) =>
      val message = payload(size, seed)
      val result = typed {
        linked(
          client = Mux.Config(maxSduPayload = sdu),
          server = Mux.Config(role = Mux.Role.Responder, maxSduPayload = sdu)
        ).use { (client, server) =>
          for
            c <- client.channel(3)
            s <- server.channel(3)
            _ <- c.send(message)
            got <- s.recv(10000)(exactly(size))
          yield got
        }
      }.unsafeRunSync()
      Prop(result == Right(message))
    }

  test("a peer with more unread bytes queued than the limit ends the connection, typed"):
    typed {
      val limit = 100L
      linked(
        client = Mux.Config(maxSduPayload = 10),
        server = Mux.Config(
          role = Mux.Role.Responder,
          maxSduPayload = 10,
          ingressLimit = _ => limit
        )
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          // 40 messages of 10 bytes and nobody reads: the queue fills to the limit and no further
          _ <- (1 to 40).toList.traverse_(_ => c.send(payload(10)).attempt)
          reason <- server.closed.timeout(5.seconds)
          // what is queued is what the limit allowed, not what was sent
          readable <- (1 to 40).toList
            .traverse(_ => s.recv(100)(exactly(10)).attempt)
            .map(_.count(_.isRight))
          afterwards <- s.recv(100)(exactly(10)).attempt
        yield (reason, readable, afterwards.isLeft)
      }
    }.map {
      case Right((NetworkError.IngressLimit(protocol, bytes), readable, ended)) =>
        assertEquals(protocol, 2)
        assert(bytes > 100L && bytes <= 110L, s"overrun at $bytes bytes")
        assertEquals(readable, 10)
        assert(ended)
      case other => fail(s"unexpected $other")
    }

  test("a slow consumer slows a producer that keeps to the protocol's window"):
    // The protocol, not the queue, holds a peer back: it may have 5 messages (50 bytes, half the
    // limit) unread at a time, a credit the consumer returns as it reads. The consumer takes 2 ms
    // per message, so the whole flow takes what the consumer takes, and the queue never overruns.
    val messages = 200
    val perMessage = 2.millis
    typed {
      linked(
        client = Mux.Config(maxSduPayload = 10),
        server = Mux.Config(
          role = Mux.Role.Responder,
          maxSduPayload = 10,
          ingressLimit = _ => 100L
        )
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          credit <- cats.effect.std.Semaphore[IO](5)
          began <- IO.monotonic
          producer <- (1 to messages).toList
            .traverse_(_ => credit.acquire *> c.send(payload(10)))
            .start
          got <- (1 to messages).toList.traverse { _ =>
            s.recv(100)(exactly(10)) <* IO.sleep(perMessage) <* credit.release
          }
          _ <- producer.joinWithNever
          took <- IO.monotonic.map(_ - began)
        yield (got.size, took)
      }
    }.map {
      case Right((n, took)) =>
        assertEquals(n, messages)
        assert(
          took >= perMessage * (messages - 5).toLong,
          s"$messages messages passed in $took, faster than the consumer reads"
        )
      case Left(e) => fail(e.message)
    }

  test("a sender waits for room in the egress queue while the bearer is not read"):
    typed {
      Bearer.inMemory[IO](1).flatMap { (ours, _) =>
        Mux
          .resource(ours, Set(2), Mux.Config(maxSduPayload = 10, egressQueueMessages = 2))
          .use { mux =>
            for
              c <- mux.channel(2)
              written <- cats.effect.Ref.of[IO, Int](0)
              sender <- (1 to 200).toList
                .traverse_(_ => c.send(payload(10)) *> written.update(_ + 1))
                .start
              _ <- IO.sleep(300.millis)
              sent <- written.get
              _ <- sender.cancel
            yield sent
          }
      }
    }.map {
      case Right(sent) => assert(sent < 200, s"all $sent sends completed with nobody reading")
      case Left(e)     => fail(e.message)
    }

  test("messages already delivered are read before a closed bearer is reported"):
    typed {
      linked().use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(5))
          _ <- IO.sleep(50.millis)
          _ <- server.abort(NetworkError.BearerClosed("test"))
          first <- s.recv(100)(exactly(5)).attempt
        yield first.isRight
      }
    }.map(r => assertEquals(r, Right(true)))

  test("pending receives raise BearerClosed when the peer closes the bearer"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          for
            c <- mux.channel(2)
            pending <- c.recv(100)(exactly(1)).start
            _ <- IO.sleep(20.millis)
            _ <- raw.close
            _ <- pending.joinWithNever
          yield ()
        }
      }
    }.map {
      case Left(NetworkError.BearerClosed(reason)) => assertEquals(reason, "closed by peer")
      case other                                   => fail(s"unexpected $other")
    }

  test("sends after the bearer closed raise BearerClosed"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          for
            c <- mux.channel(2)
            _ <- raw.close
            _ <- mux.closed
            _ <- c.send(payload(3))
          yield ()
        }
      }
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.BearerClosed]), r.toString))

  test("a truncated frame at end of input is reported"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          raw.write(
            Chunk.array(Array[Byte](0, 0, 0, 0, -128, 2, 0, 9, 1))
          ) *> raw.close *> mux.closed
        }
      }
    }.map(r => assertEquals(r, Right(NetworkError.Framing(WireMux.Error.TruncatedPayload(9, 1)))))

  test("data on an unregistered mini-protocol or with the wrong mode bit closes the bearer"):
    def sduBytes(protocol: Int, direction: WireMux.Direction) =
      IO.fromEither(
        WireMux
          .encode(WireMux.Sdu(0, protocol, direction, payload(3)))
          .leftMap(e => new Exception(e.message))
      )
    typed {
      for
        unknown <- Bearer.inMemory[IO]().flatMap { (ours, raw) =>
          Mux.resource(ours, Set(2)).use { mux =>
            sduBytes(9, WireMux.Direction.Responder)
              .flatMap(b => raw.write(Chunk.array(b.toArray))) *> mux.closed
          }
        }
        wrongMode <- Bearer.inMemory[IO]().flatMap { (ours, raw) =>
          Mux.resource(ours, Set(2)).use { mux =>
            sduBytes(2, WireMux.Direction.Initiator)
              .flatMap(b => raw.write(Chunk.array(b.toArray))) *> mux.closed
          }
        }
      yield (unknown, wrongMode)
    }.map {
      case Right((unknown, wrongMode)) =>
        assertEquals(
          unknown,
          NetworkError.IllegalTransition(9, "data on unexpected mini-protocol 9")
        )
        assertEquals(wrongMode, NetworkError.IllegalTransition(2, "SDU with the wrong mode bit"))
      case Left(e) => fail(e.message)
    }

  test("invalid local SDU limits preserve a setup cause through the caller's scope"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, _) =>
        Mux.resource(ours, Set(2), Mux.Config(maxSduPayload = 0)).use(_ => IO.unit)
      }
    }.map(r => assertEquals(r, Left(NetworkError.MuxSetup(WireMux.Error.InvalidPayloadLimit(0)))))

  test("local encoding failures close with a typed write cause"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, _) =>
        Mux.resource(ours, Set(2), Mux.Config(maxMessageBytes = 2500001)).use { mux =>
          mux.channel(2).flatMap(_.send(payload(3))) *> mux.closed
        }
      }
    }.map(r =>
      assertEquals(
        r,
        Right(NetworkError.MuxWrite(WireMux.Error.InvalidMessageLimit(2500001, 2500000)))
      )
    )

  test("a zero-length peer frame retains its rejected length and configured limit"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          raw.write(Chunk.array(Array[Byte](0, 0, 0, 0, -128, 2, 0, 0))) *> mux.closed
        }
      }
    }.map(r =>
      assertEquals(r, Right(NetworkError.Framing(WireMux.Error.PayloadLengthOutOfRange(0, 65535))))
    )

  test("an oversized ingress is a typed error"):
    typed {
      linked().use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(400))
          _ <- s.recv(100)(exactly(401))
        yield ()
      }
    }.map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IngressLimit]), r.toString))

  test("channel lookup fails for a protocol that was not registered"):
    typed(linked(protocols = Set(2)).use((client, _) => client.channel(5)).void)
      .map(r => assert(r.left.exists(_.isInstanceOf[NetworkError.IllegalTransition]), r.toString))

  for
    writing <- List(false, true)
    unexpected <- List(false, true)
  do
    test(s"mux ${
        if writing then "writer" else "reader"
      } preserves ${if unexpected then "defect" else "transport closure"}") {
      val cause =
        if unexpected then new IllegalStateException("injected")
        else new java.io.IOException("closed")
      val bearer = new Bearer[IO]:
        def reads = if writing then fs2.Stream.never[IO] else fs2.Stream.raiseError[IO](cause)
        def write(bytes: Chunk[Byte]) = IO.raiseError[Unit](cause)
        def close = IO.unit
      typed {
        Mux.resource(bearer, Set(2)).use { mux =>
          (if writing then mux.channel(2).flatMap(_.send(Bytes(Vector(0.toByte)))) else IO.unit) *>
            mux.closed
        }
      }.map {
        case Right(NetworkError.Defect(found)) if unexpected    => assert(found eq cause)
        case Right(_: NetworkError.BearerClosed) if !unexpected => ()
        case other                                              => fail(s"unexpected $other")
      }
    }
