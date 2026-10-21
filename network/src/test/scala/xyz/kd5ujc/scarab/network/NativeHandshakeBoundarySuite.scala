// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{IO, Resource}
import cats.effect.testkit.TestControl
import fs2.Chunk
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Handshake, Mux as WireMux}

/** Native bearerAsChannel ignores incoming SDU labels; runPeerWithLimits drops the final SDU's
  * decoder trailing bytes. ConnectionHandler installs a fresh application mux afterwards.
  */
class NativeHandshakeBoundarySuite extends FailsOnUncaught:
  import Peers.*
  private val data = Handshake.Data(42L, initiatorOnly = false)
  private val application = NodeToNodeServer.applicationProtocols(false)
  private def checked[A](value: Either[?, A]): IO[A] =
    value.fold(error => IO.raiseError(new AssertionError(error.toString)), IO.pure)
  private def accepted: IO[Bytes] =
    for
      term <- checked(Handshake.dataTerm(Handshake.Suite.NodeToNode, 14, data))
      bytes <- checked(
        Handshake.encode(Handshake.Suite.NodeToNode, Handshake.Message.Accept(14, term))
      )
    yield bytes
  private def frame(payload: Bytes, protocol: Int = Protocols.Handshake): IO[Bytes] =
    checked(WireMux.encode(WireMux.Sdu(0L, protocol, WireMux.Direction.Initiator, payload)))
  private def pair(using Net): Resource[IO, (Mux[IO], Bearer[IO])] =
    Resource.eval(Bearer.inMemory[IO]()).flatMap { (a, b) =>
      Mux.handshakeResource(a, NodeToNodeServer.Protocol, Mux.Config(duplex = true)).map(_ -> b)
    }
  private def negotiate(mux: Mux[IO])(using Net): IO[Unit] =
    mux.channel(Protocols.Handshake).flatMap(HandshakeClient.run(_, Vector(14 -> data))).void
  private def install(mux: Mux[IO])(using Net): IO[Unit] =
    mux.installApplication(application, application)

  test(
    "raw handshake accepts arbitrary SDU labels and drops large trailing bytes in its final SDU"
  ) {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            reply <- accepted
            wire <- frame(reply ++ Bytes(Vector.fill(6000)(99.toByte)), Protocols.BlockFetch)
            _ <- raw.write(Chunk.array(wire.toArray))
            _ <- negotiate(mux)
            _ <- install(mux)
            closed <- mux.closed.map(Some(_)).timeoutTo(1.second, IO.pure(None))
          yield assertEquals(closed, None)
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("a following separate Handshake SDU is rejected by the negotiated application") {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            reply <- accepted.flatMap(frame(_))
            extra <- frame(Bytes(Vector(1.toByte)))
            _ <- raw.write(Chunk.array((reply ++ extra).toArray))
            _ <- negotiate(mux)
            _ <- install(mux)
            ended <- mux.closed
          yield assertEquals(
            ended,
            NetworkError.IllegalTransition(
              Protocols.Handshake,
              NetworkError.RuntimeViolation.UnexpectedProtocol(Protocols.Handshake)
            )
          )
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("a partial next SDU starts its application deadline only after installation") {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            reply <- accepted.flatMap(frame(_))
            _ <- raw.write(Chunk.array((reply ++ Bytes(Vector(0.toByte))).toArray))
            _ <- negotiate(mux)
            before <- IO.monotonic
            early <- mux.closed.map(Some(_)).timeoutTo(20.seconds, IO.pure(None))
            _ <- IO(assertEquals(early, None))
            _ <- install(mux)
            ended <- mux.closed
            after <- IO.monotonic
          yield
            assertEquals(ended, NetworkError.SduTimeout(NetworkError.SduOperation.Read))
            assertEquals(after - before, 50.seconds)
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("partial handshake SDUs use the native ten-second SDU deadline") {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            before <- IO.monotonic
            _ <- raw.write(Chunk.singleton(0.toByte))
            ended <- mux.closed
            after <- IO.monotonic
          yield
            assertEquals(ended, NetworkError.SduTimeout(NetworkError.SduOperation.Read))
            assertEquals(after - before, 10.seconds)
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("valid handshake prefix survives a malformed following application frame in the same read") {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            reply <- accepted.flatMap(frame(_))
            zero <- frame(Bytes.empty, Protocols.BlockFetch)
            _ <- raw.write(Chunk.array((reply ++ zero).toArray))
            _ <- negotiate(mux)
            _ <- install(mux)
            ended <- mux.closed
          yield assertEquals(
            ended,
            NetworkError.Framing(WireMux.Error.PayloadLengthOutOfRange(0, 65535))
          )
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("following valid application SDUs retain their original dispatch and responder activation") {
    TestControl
      .executeEmbed(typed {
        pair.use { (mux, raw) =>
          for
            reply <- accepted.flatMap(frame(_))
            body <- frame(Bytes(Vector(17.toByte)), Protocols.BlockFetch)
            _ <- raw.write(Chunk.array((reply ++ body).toArray))
            _ <- negotiate(mux)
            _ <- install(mux)
            _ <- mux.awaitResponderActivity
            channel <- mux.channel(Protocols.BlockFetch, Mux.Role.Responder)
            got <- channel.recv(10) { bytes =>
              if bytes.nonEmpty then
                xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult.Decoded(bytes, bytes.size)
              else xyz.kd5ujc.scarab.protocol.ChainSync.DecodeResult.NeedMore
            }
          yield assertEquals(got, Bytes(Vector(17.toByte)))
        }
      })
      .map(result => assertEquals(result, Right(())))
  }

  test("raw handshake acquisition requires a registered handshake protocol") {
    typed {
      Resource
        .eval(Bearer.inMemory[IO]())
        .flatMap { (a, _) =>
          Mux.handshakeResource(a, application, Mux.Config())
        }
        .use(_ => IO.unit)
    }.map(result =>
      assertEquals(
        result,
        Left(
          NetworkError.IllegalTransition(
            Protocols.Handshake,
            NetworkError.RuntimeViolation.UnexpectedProtocol(Protocols.Handshake)
          )
        )
      )
    )
  }
