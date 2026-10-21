// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Chunk
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult
import xyz.kd5ujc.scarab.protocol.Mux as WireMux

/** ConnectionHandler.hs:335–381 replaces handshake dispatch with the negotiated application; native
  * Ingress.hs:110–119 rejects missing protocols and unavailable directions.
  */
class NegotiatedDispatchSuite extends FailsOnUncaught:
  import Peers.*
  private val rawByte = Bytes(Vector(1.toByte))
  private val noSharing = NodeToNodeServer.applicationProtocols(false)
  private val sharing = NodeToNodeServer.applicationProtocols(true)
  private def exactly(n: Int)(bytes: Bytes): DecodeResult[Bytes] =
    if bytes.size >= n then DecodeResult.Decoded(bytes.take(n), n) else DecodeResult.NeedMore
  private def duplex(using Net): Resource[IO, (Mux[IO], Mux[IO])] =
    linked(
      protocols = NodeToNodeServer.Protocol,
      client = Mux.Config(duplex = true),
      server = Mux.Config(role = Mux.Role.Responder, duplex = true)
    )
  private def unknown(protocol: Int) = NetworkError.IllegalTransition(
    protocol,
    NetworkError.RuntimeViolation.UnexpectedProtocol(protocol)
  )
  private def notActive(mux: Mux[IO]): IO[Unit] =
    typed(mux.awaitResponderActivity)
      .map(Option(_))
      .timeoutTo(1.millis, IO.pure(None))
      .map(result => assertEquals(result, None))

  for enabled <- Vector(false, true) do
    test(s"early PeerSharing is validated before activation, negotiated=$enabled") {
      TestControl
        .executeEmbed(typed {
          duplex.use { (ours, remote) =>
            for
              input <- ours.channel(Protocols.PeerSharing, Mux.Role.Responder)
              output <- remote.channel(Protocols.PeerSharing, Mux.Role.Initiator)
              _ <- output.send(rawByte)
              _ <- input.recv(10)(
                exactly(1)
              ) // Prove pre-install routing, even with a fast consumer.
              _ <- notActive(ours)
              configured <- typed(
                ours.installApplication(
                  if enabled then sharing else noSharing,
                  if enabled then sharing else noSharing
                )
              )
              active <- typed(ours.awaitResponderActivity)
            yield
              if enabled then
                assertEquals(configured, Right(()))
                assertEquals(active, Right(()))
              else
                assertEquals(configured, Left(unknown(Protocols.PeerSharing)))
                assertEquals(active, Left(unknown(Protocols.PeerSharing)))
          }
        })
        .map(result => assertEquals(result, Right(())))
    }

  test("early valid responder bytes remain available and activate after application installation") {
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, remote) =>
          for
            input <- ours.channel(Protocols.BlockFetch, Mux.Role.Responder)
            output <- remote.channel(Protocols.BlockFetch, Mux.Role.Initiator)
            _ <- output.send(rawByte)
            _ <- output.flush
            _ <- IO.sleep(1.millis)
            _ <- notActive(ours)
            _ <- ours.installApplication(noSharing, noSharing)
            _ <- ours.awaitResponderActivity
            got <- input.recv(10)(exactly(1))
          yield assertEquals(got, rawByte)
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("early initiator responses do not activate responders") {
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, remote) =>
          for
            input <- ours.channel(Protocols.ChainSync, Mux.Role.Initiator)
            output <- remote.channel(Protocols.ChainSync, Mux.Role.Responder)
            _ <- output.send(rawByte)
            _ <- input.recv(10)(exactly(1))
            _ <- ours.installApplication(noSharing, noSharing)
            _ <- notActive(ours)
          yield ()
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("installed dispatch rejects disabled PeerSharing and late Handshake on ingress") {
    Vector(Protocols.PeerSharing, Protocols.Handshake).traverse_ { protocol =>
      TestControl
        .executeEmbed(typed {
          duplex.use { (ours, remote) =>
            for
              _ <- ours.installApplication(noSharing, noSharing)
              output <- remote.channel(protocol, Mux.Role.Initiator)
              _ <- output.send(rawByte)
              ended <- ours.closed
              active <- typed(ours.awaitResponderActivity)
            yield
              assertEquals(ended, unknown(protocol))
              assertEquals(active, Left(unknown(protocol)))
          }
        })
        .map(result => assertEquals(result, Right(())))
    }
  }

  test("previously acquired channels and new lookups obey negotiated absence and direction") {
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, _) =>
          for
            sharingChannel <- ours.channel(Protocols.PeerSharing, Mux.Role.Initiator)
            responder <- ours.channel(Protocols.ChainSync, Mux.Role.Responder)
            _ <- ours.installApplication(noSharing, Set.empty)
            send <- typed(sharingChannel.send(rawByte))
            receive <- typed(sharingChannel.recv(10)(exactly(1)))
            lookup <- typed(ours.channel(Protocols.PeerSharing))
            wrong <- typed(responder.send(rawByte))
          yield
            assertEquals(send, Left(unknown(Protocols.PeerSharing)))
            assertEquals(receive, Left(unknown(Protocols.PeerSharing)))
            assertEquals(lookup.left.toOption, Some(unknown(Protocols.PeerSharing)))
            assertEquals(
              wrong,
              Left(
                NetworkError.IllegalTransition(
                  Protocols.ChainSync,
                  NetworkError.RuntimeViolation.WrongModeBit
                )
              )
            )
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("a receive begun before installation wakes with its caller's typed rejection") {
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, _) =>
          for
            channel <- ours.channel(Protocols.PeerSharing, Mux.Role.Responder)
            waiting <- typed(channel.recv(10)(exactly(1))).start
            _ <- IO.sleep(1.millis)
            _ <- ours.installApplication(noSharing, noSharing)
            result <- waiting.joinWithNever
          yield assertEquals(result, Left(unknown(Protocols.PeerSharing)))
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("responder activity survives cancellation and idempotent installation") {
    TestControl
      .executeEmbed(typed {
        duplex.use { (ours, remote) =>
          for
            waiting <- typed(ours.awaitResponderActivity).start
            _ <- IO.sleep(1.millis)
            _ <- waiting.cancel
            output <- remote.channel(Protocols.ChainSync, Mux.Role.Initiator)
            input <- ours.channel(Protocols.ChainSync, Mux.Role.Responder)
            _ <- output.send(rawByte)
            _ <- input.recv(10)(exactly(1))
            _ <- ours.installApplication(noSharing, noSharing)
            _ <- ours.installApplication(noSharing, noSharing)
            _ <- ours.awaitResponderActivity
            _ <- ours.awaitResponderActivity
          yield ()
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("zero-payload SDUs retain native framing rejection before application dispatch") {
    TestControl
      .executeEmbed(typed {
        Resource
          .eval(Bearer.inMemory[IO]())
          .flatMap { (ours, raw) =>
            Mux.resource(ours, NodeToNodeServer.Protocol, Mux.Config(duplex = true)).map(_ -> raw)
          }
          .use { (ours, raw) =>
            for
              frame <- IO.fromEither(
                WireMux
                  .encode(
                    WireMux.Sdu(0L, Protocols.PeerSharing, WireMux.Direction.Initiator, Bytes.empty)
                  )
                  .leftMap(e => new AssertionError(e.message))
              )
              _ <- raw.write(Chunk.array(frame.toArray))
              ended <- ours.closed
              active <- typed(ours.awaitResponderActivity)
            yield
              val expected = NetworkError.Framing(WireMux.Error.PayloadLengthOutOfRange(0, 65535))
              assertEquals(ended, expected)
              assertEquals(active, Left(expected))
          }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("a fragmented lawful handshake is fully consumed before final application installation") {
    TestControl
      .executeEmbed(typed {
        Resource
          .eval(Bearer.inMemory[IO]())
          .flatMap { (a, b) =>
            (
              Mux.handshakeResource(
                a,
                NodeToNodeServer.Protocol,
                Mux.Config(duplex = true, maxSduPayload = 1)
              ),
              Mux.handshakeResource(
                b,
                NodeToNodeServer.Protocol,
                Mux.Config(role = Mux.Role.Responder, duplex = true, maxSduPayload = 1)
              )
            ).tupled
          }
          .use { (ours, remote) =>
            for
              incoming <- remote.channel(Protocols.Handshake)
              serving <- typed(
                HandshakeServer.run(incoming, HandshakeServer.offers(42L, true))
              ).start
              outgoing <- ours.channel(Protocols.Handshake)
              agreed <- HandshakeClient.run(
                outgoing,
                Vector(14 -> xyz.kd5ujc.scarab.protocol.Handshake.Data(42L, initiatorOnly = false))
              )
              theirs <- serving.joinWithNever
              _ <- IO(assert(theirs.isRight && !agreed.data.initiatorOnly))
              _ <- ours.installApplication(noSharing, noSharing)
              _ <- remote.installApplication(noSharing, noSharing)
              _ <- notActive(ours)
            yield ()
          }
      })
      .map(result => assertEquals(result, Right(())))
  }

  for trailing <- Vector(false) do
    test(s"unconsumed late Handshake data rejects installation, buffered=$trailing") {
      TestControl
        .executeEmbed(typed {
          duplex.use { (ours, remote) =>
            for
              input <- ours.channel(Protocols.Handshake)
              output <- remote.channel(Protocols.Handshake)
              _ <- output.send(if trailing then Bytes(Vector(1.toByte, 2.toByte)) else rawByte)
              _ <- input.recv(10)(exactly(1))
              _ <-
                if trailing then IO.unit
                else output.send(rawByte) *> output.flush *> IO.sleep(1.millis)
              configured <- typed(ours.installApplication(noSharing, noSharing))
            yield assertEquals(configured, Left(unknown(Protocols.Handshake)))
          }
        })
        .map(result => assertEquals(result, Right(())))
    }
