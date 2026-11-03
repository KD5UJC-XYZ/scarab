// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.mtl.Handle
import fs2.Chunk
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.{Handshake, Mux as WireMux}

/** The node-to-client entry points read the handshake on a bearer phase of their own, as the
  * reference's local server (ouroboros-network 2fb69829, `ConnectionHandler.hs:94-102,412,451`):
  * silence before the first byte is not bounded, a partial handshake SDU has 10 s and an
  * application SDU after the handshake 30 s. The handshake itself has no state limit
  * (`noTimeLimitsHandshake`).
  */
class NodeToClientSduDeadlinesSuite extends FailsOnUncaught:
  private val magic = 42L

  private def checked[A](value: Either[?, A]): IO[A] =
    value.fold(error => IO.raiseError(new AssertionError(error.toString)), IO.pure)

  private def frame(payload: Bytes, protocol: Int): IO[Chunk[Byte]] =
    checked(WireMux.encode(WireMux.Sdu(0L, protocol, WireMux.Direction.Initiator, payload)))
      .map(bytes => Chunk.array(bytes.toArray))

  /** A complete node-to-client proposal of version 16, as one SDU. */
  private val proposal: IO[Chunk[Byte]] =
    for
      message <- checked(
        Handshake.proposal(Handshake.Suite.NodeToClient, Vector(16 -> Handshake.Data(magic)))
      )
      bytes <- checked(Handshake.encode(Handshake.Suite.NodeToClient, message))
      sdu <- frame(bytes, Protocols.Handshake)
    yield sdu

  /** The two entry points, each serving one connection. */
  private val entries: List[(String, Bearer[IO] => Handle[IO, NetworkError] ?=> IO[Any])] = List(
    "NodeToClientServer" -> (bearer =>
      NodeToClientServer.accept[IO](
        bearer,
        magic,
        _ => IO.pure(LocalTxSubmissionServer.Response.Accepted)
      )
    ),
    "LocalTxSubmissionServer" -> (bearer =>
      LocalTxSubmissionServer.accept[IO](
        bearer,
        magic,
        _ => IO.pure(LocalTxSubmissionServer.Response.Accepted)
      )
    )
  )

  /** Serve one connection whose client writes `client` to the raw bearer; the result is how long
    * the server ran (at most `within`) and why it ended, if it did.
    */
  private def served(
      serve: Bearer[IO] => Handle[IO, NetworkError] ?=> IO[Any],
      within: FiniteDuration
  )(client: Bearer[IO] => IO[Unit]): IO[(FiniteDuration, Option[NetworkError])] =
    TestControl.executeEmbed(
      Bearer.inMemory[IO]().flatMap { (server, raw) =>
        val ended: IO[Option[NetworkError]] = Handle
          .allow[NetworkError](serve(server).map {
            case LocalTxSubmissionServer.End.ConnectionClosed(error) => Some(error)
            case _                                                   => None
          })
          .rescue(error => IO.pure(Some(error)))
        for
          start <- IO.monotonic
          result <- IO.both(ended.timeoutTo(within, IO.pure(None)), client(raw)).map(_._1)
          end <- IO.monotonic
        yield (end - start, result)
      }
    )

  private val readTimeout = NetworkError.SduTimeout(NetworkError.SduOperation.Read)

  entries.foreach { (name, serve) =>
    test(s"$name: silence before the first byte is not bounded") {
      served(serve, 1.day)(_ => IO.unit).map(assertEquals(_, (1.day, None)))
    }

    test(s"$name: a partial handshake SDU has the reference's 10 s") {
      served(serve, 1.day)(_.write(Chunk.singleton(0.toByte)))
        .map(assertEquals(_, (10.seconds, Some(readTimeout))))
    }

    test(s"$name: an application SDU after the handshake has 30 s") {
      served(serve, 1.day) { raw =>
        for
          sdu <- proposal
          _ <- raw.write(sdu)
          // the reply arrives, then one byte of the next SDU's header and nothing more
          _ <- raw.reads.take(1).compile.drain
          _ <- raw.write(Chunk.singleton(0.toByte))
        yield ()
      }.map(assertEquals(_, (30.seconds, Some(readTimeout))))
    }
  }
