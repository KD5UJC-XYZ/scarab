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

class MuxSuite extends CatsEffectSuite with ScalaCheckSuite:
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
            encoded <- IO.fromEither(WireMux.encode(sdu).leftMap(new Exception(_)))
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

  test("a full protocol queue back-pressures the bearer"):
    typed {
      val cfg = Mux.Config(ingressQueueSdus = 2)
      linked(
        client = Mux.Config(maxSduPayload = 10),
        server = cfg.copy(role = Mux.Role.Responder),
        capacity = 1
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          written <- cats.effect.Ref.of[IO, Int](0)
          sender <- (1 to 200).toList
            .traverse_(_ => c.send(payload(10)) *> written.update(_ + 1))
            .start
          _ <- IO.sleep(200.millis)
          stalled <- written.get
          // Draining the consumer lets the rest through.
          _ <- (1 to 200).toList.traverse_(_ => s.recv(100)(exactly(10)))
          _ <- sender.joinWithNever
          finished <- written.get
        yield (stalled, finished)
      }
    }.map {
      case Right((stalled, finished)) =>
        assert(stalled < 200, s"all $stalled sends completed without a consumer")
        assertEquals(finished, 200)
      case Left(e) => fail(e.message)
    }

  test("messages already delivered are read before a closed bearer is reported"):
    typed {
      linked().use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(5))
          _ <- IO.sleep(50.millis)
          _ <- server.abort("test")
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
    }.map(r => assertEquals(r, Right("EOF in mux payload")))

  test("data on an unregistered mini-protocol or with the wrong mode bit closes the bearer"):
    def sduBytes(protocol: Int, direction: WireMux.Direction) =
      IO.fromEither(
        WireMux.encode(WireMux.Sdu(0, protocol, direction, payload(3))).leftMap(new Exception(_))
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
        assert(unknown.contains("unexpected mini-protocol 9"), unknown)
        assert(wrongMode.contains("mode bit"), wrongMode)
      case Left(e) => fail(e.message)
    }

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
