// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
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
        client = Mux.Config(sduSize = 100),
        server = Mux.Config(role = Mux.Role.Responder, sduSize = 100)
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(1050))
          got <- s.recv(5000)(exactly(1050))
        yield got
      }
    }.map(r => assertEquals(r, Right(payload(1050))))

  test("the default send size is the reference socket bearer's 12288 bytes (Bearer.hs:86)"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, raw) =>
        Mux.resource(ours, Set(2)).use { mux =>
          for
            c <- mux.channel(2)
            _ <- c.send(payload(30000))
            bytes <- raw.reads.take(30000 + 3 * WireMux.HeaderBytes).compile.to(Chunk)
          yield
            def frames(rest: Bytes, sizes: Vector[Int]): Vector[Int] =
              rest.bigEndian(6, 2).map(_.toInt) match
                case None => sizes
                case Some(length) =>
                  frames(Bytes(rest.value.drop(WireMux.HeaderBytes + length)), sizes :+ length)
            frames(Bytes(bytes.toVector), Vector.empty)
        }
      }
    }.map(r => assertEquals(r, Right(Vector(12288, 12288, 5424))))

  test("an SDU of the full 65535 bytes is accepted on read although the send size is smaller"):
    typed {
      linked(
        client = Mux.Config(sduSize = 65535),
        server = Mux.Config(role = Mux.Role.Responder)
      ).use { (client, server) =>
        for
          c <- client.channel(2)
          s <- server.channel(2)
          _ <- c.send(payload(65535))
          got <- s.recv(65535)(exactly(65535))
        yield got
      }
    }.map(r => assertEquals(r, Right(payload(65535))))

  test("a send size above the accepted SDU size is a setup error"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, _) =>
        Mux
          .resource(ours, Set(2), Mux.Config(sduSize = 65536))
          .use(_ => IO.unit)
      }
    }.map(r =>
      assertEquals(r, Left(NetworkError.MuxSetup(WireMux.Error.InvalidPayloadLimit(65536))))
    )

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
          client = Mux.Config(sduSize = sdu),
          server = Mux.Config(role = Mux.Role.Responder, sduSize = sdu)
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
        client = Mux.Config(sduSize = 10),
        server = Mux.Config(
          role = Mux.Role.Responder,
          sduSize = 10,
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
        client = Mux.Config(sduSize = 10),
        server = Mux.Config(
          role = Mux.Role.Responder,
          sduSize = 10,
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

  test("a sender waits for room in the lane budget while the bearer is not read"):
    typed {
      Bearer.inMemory[IO](1).flatMap { (ours, _) =>
        Mux
          .resource(ours, Set(2), Mux.Config(sduSize = 10, egressBufferBytes = 20))
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
          _ <- server.abort(NetworkError.BearerClosed(NetworkError.Closure.Aborted))
          first <- s.recv(100)(exactly(5)).attempt
        yield first.isRight
      }
    }.map(r => assertEquals(r, Right(true)))

  test(
    "a queued SDU that would overflow the receive buffer waits until the complete message is delivered"
  ):
    val config = Mux.Config.nodeToClient.copy(
      role = Mux.Role.Initiator,
      sduSize = 1000,
      receiveBufferLimit = 6500
    )
    typed {
      Bearer.inMemory[IO]().flatMap { (a, b) =>
        (
          Mux.resource(a, Set(2), config),
          Mux.resource(b, Set(2), config.copy(role = Mux.Role.Responder))
        ).tupled.use { (client, server) =>
          for
            c <- client.channel(2)
            s <- server.channel(2)
            // Both messages are queued before the receiver reads: it holds the first complete
            // (5000 bytes) and the next SDU (1000 bytes) would still fit, the one after would not.
            _ <- c.send(payload(5000, 1))
            _ <- c.send(payload(3000, 2))
            _ <- IO.sleep(300.millis)
            first <- s.recv(10000)(exactly(5000))
            second <- s.recv(10000)(exactly(3000))
          yield (first, second)
        }
      }
    }.map {
      case Right((first, second)) =>
        assertEquals(first, payload(5000, 1))
        assertEquals(second, payload(3000, 2))
      case Left(e) => fail(s"unexpected $e")
    }

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
      case Left(NetworkError.BearerClosed(reason)) =>
        assertEquals(reason, NetworkError.Closure.PeerClosed)
      case other => fail(s"unexpected $other")
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
          NetworkError.IllegalTransition(9, NetworkError.RuntimeViolation.UnexpectedProtocol(9))
        )
        assertEquals(
          wrongMode,
          NetworkError.IllegalTransition(2, NetworkError.RuntimeViolation.WrongModeBit)
        )
      case Left(e) => fail(e.message)
    }

  test("invalid local SDU limits preserve a setup cause through the caller's scope"):
    typed {
      Bearer.inMemory[IO]().flatMap { (ours, _) =>
        Mux.resource(ours, Set(2), Mux.Config(maxSduPayload = 0)).use(_ => IO.unit)
      }
    }.map(r => assertEquals(r, Left(NetworkError.MuxSetup(WireMux.Error.InvalidPayloadLimit(0)))))

  test("empty and oversized messages are refused before any egress bytes are written"):
    val profiles = Vector(Mux.Config(), Mux.Config(maxMessageBytes = 3000000))
    profiles.traverse_ { config =>
      Vector(0, config.maxMessageBytes + 1).traverse_ { size =>
        for
          writes <- cats.effect.Ref.of[IO, Int](0)
          result <- typed {
            Bearer.inMemory[IO]().flatMap { (ours, _) =>
              val observing = new Bearer[IO]:
                def reads = ours.reads
                def close = ours.close
                def write(bytes: Chunk[Byte]) = writes.update(_ + 1) *> ours.write(bytes)
              Mux.resource(observing, Set(2), config).use { mux =>
                mux.channel(2).flatMap(_.send(payload(size)))
              }
            }
          }
          count <- writes.get
        yield
          assertEquals(
            result,
            Left(
              NetworkError.IllegalTransition(
                2,
                NetworkError.RuntimeViolation.OutgoingMessageSize(size, config.maxMessageBytes)
              )
            )
          )
          assertEquals(count, 0, s"rejected $size-byte message must not reach the bearer")
      }
    }

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
      Deferred[IO, Unit]
        .flatMap { failWrite =>
          val bearer = new Bearer[IO]:
            def reads = if writing then fs2.Stream.never[IO] else fs2.Stream.raiseError[IO](cause)
            def write(bytes: Chunk[Byte]) = failWrite.get *> IO.raiseError[Unit](cause)
            def close = IO.unit
          typed {
            Mux.resource(bearer, Set(2)).use { mux =>
              for
                channel <- mux.channel(2)
                // Let send finish queue admission before the writer reports the injected failure.
                _ <- channel.send(Bytes(Vector(0.toByte))).whenA(writing)
                _ <- failWrite.complete(())
                reason <- mux.closed
                // once the connection is closed, a caller's send fails with the same reason (#264)
                after <- typed(channel.send(Bytes(Vector(1.toByte))))
              yield (reason, after)
            }
          }
        }
        .map {
          case Right((reason, after)) =>
            assertEquals(after, Left(reason))
            reason match
              // the reader's exception is the connection's (issue 463); the writer's stays a defect
              case NetworkError.BearerDefect(found) if unexpected && !writing =>
                assert(found eq cause)
              case NetworkError.Defect(found) if unexpected && writing => assert(found eq cause)
              case NetworkError.BearerClosed(NetworkError.Closure.Transport(_, found))
                  if !unexpected =>
                assert(found eq cause)
              case other => fail(s"unexpected $other")
          case other => fail(s"unexpected $other")
        }
    }

  test("a payload queued as the connection closes is delivered before the closure (#389)"):
    val last = Bytes(Vector(0x81.toByte, 0x02.toByte))
    val ended = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)
    (
      Ref.of[IO, Vector[Bytes]](Vector.empty),
      Ref.of[IO, Option[NetworkError]](None),
      Ref.of[IO, Int](0)
    ).tupled.flatMap { (queue, closure, looks) =>
      // The reader runs between the channel's first look at its queue and its look at the
      // closure: it queues the last SDU of a clean shutdown, then records the end of the bearer.
      val reader = queue.update(_ :+ last) *> closure.set(Some(ended))
      val tryTake = looks.getAndUpdate(_ + 1).flatMap { look =>
        queue.modify(q => (q.drop(1), q.headOption)) <* reader.whenA(look == 0)
      }
      for
        first <- Mux.queuedOrClosed(tryTake, closure.get)
        second <- Mux.queuedOrClosed(tryTake, closure.get)
      yield
        assertEquals(first, Some(Right(last)))
        assertEquals(second, Some(Left(ended)))
    }

  test("a receive already waiting gets the payload the peer sent just before closing (#389)"):
    val last = Bytes(Vector(0x81.toByte, 0x02.toByte))
    val frame = WireMux
      .encode(WireMux.Sdu(0, 2, WireMux.Direction.Responder, last))
      .fold(error => fail(error.message), identity)
    // Under TestControl the sleep ends only once every fiber is blocked, so the receive is waiting
    // on its empty queue before the SDU arrives. The reader then queues the SDU and records the end
    // of the bearer at once. Each seed orders the reader, the closure and the waiting receive
    // differently, and replays that order on every run.
    (1 to 200).toList.traverse_ { n =>
      val seed = java.util.Base64.getEncoder.encodeToString(n.toString.getBytes)
      TestControl
        .executeEmbed(
          typed {
            Bearer.inMemory[IO]().flatMap { (ours, raw) =>
              Mux.resource(ours, Set(2)).use { mux =>
                for
                  channel <- mux.channel(2)
                  waiting <- typed(channel.recv(10)(exactly(2))).start
                  _ <- IO.sleep(1.second)
                  _ <- raw.write(Chunk.array(frame.toArray)) *> raw.close
                  received <- waiting.joinWithNever
                yield received
              }
            }
          },
          seed = Some(seed)
        )
        .map(result => assertEquals(result, Right(Right(last)), s"seed $n"))
    }

  for
    protocol <- Vector(Protocols.Handshake, 99)
    direction <- Vector(WireMux.Direction.Initiator, WireMux.Direction.Responder)
  do
    test(
      s"raw handshake input ignores incoming SDU protocol=$protocol and direction=$direction only before activation"
    ) {
      typed {
        Bearer.inMemory[IO]().flatMap { (ours, raw) =>
          Mux.handshakeResource(ours, Set(Protocols.Handshake, 2), Mux.Config()).use { mux =>
            val input = payload(3)
            def sdu(id: Int, dir: WireMux.Direction) = WireMux
              .encode(WireMux.Sdu(0, id, dir, input))
              .fold(error => fail(error.message), identity)
            for
              channel <- mux.channel(Protocols.Handshake)
              _ <- raw.write(Chunk.array(sdu(protocol, direction).toArray))
              decoded <- channel.recv(3)(exactly(3))
              _ <- mux.installApplication(Set(2), Set.empty)
              _ <- raw.write(Chunk.array(sdu(2, WireMux.Direction.Initiator).toArray))
              closed <- mux.closed
            yield
              assertEquals(decoded, input)
              assertEquals(
                closed,
                NetworkError.IllegalTransition(2, NetworkError.RuntimeViolation.WrongModeBit)
              )
          }
        }
      }.map(result => assertEquals(result, Right(())))
    }
