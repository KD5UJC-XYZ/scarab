// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import lab.network.*
import scala.concurrent.duration.*

/** Explicit loopback-only probe. The Docker harness establishes reference identity. */
object ReferenceHandshakeCommand:
  def options(args: List[String]): Either[String, (NumericPeer, Long)] = args match
    case List(port, magic) =>
      for
        p <- port.toIntOption.toRight("invalid port")
        m <- magic.toLongOption.filter(x => x > 0 && x <= 0xffffffffL).toRight("invalid magic")
        peer <- NumericPeer.checked("127.0.0.1", p)
      yield (peer, m)
    case _ => Left("usage: reference-handshake PORT PRIVATE_NETWORK_MAGIC (loopback only)")

  private def checked[A](value: Either[String, A]): IO[A] =
    IO.fromEither(value.leftMap(new IllegalArgumentException(_)))

  def probe(peer: NumericPeer, magic: Long): IO[Handshake.Result] =
    for
      _ <- IO.raiseUnless(peer.canonicalAddress == "127.0.0.1")(
        new IllegalArgumentException("reference probe requires IPv4 loopback")
      )
      limits <- checked(TcpLimits.checked())
      result <- AsyncTcpTransport
        .resource[IO](peer, limits)
        .use { transport =>
          HandshakeSession
            .resource[IO](transport, Handshake.Suite.NodeToNode, Mux.Direction.Initiator, 5.seconds)
            .use { session =>
              for
                start <- checked(
                  Handshake
                    .clientStart(Handshake.Suite.NodeToNode, Handshake.defaultNodeToNode(magic))
                )
                (client, proposal) = start
                _ <- session.send(proposal)
                message <- session.receive(Handshake.State.Confirm)
                response <- checked(client.receive(message))
              yield response._2
            }
        }
        .timeout(15.seconds)
    yield result

  def run(args: List[String]): IO[ExitCode] =
    checked(options(args))
      .flatMap { case (peer, magic) =>
        probe(peer, magic).flatMap {
          case Handshake.Result.Negotiated(version, data) =>
            IO.println(
              s"""{"scope":"live-loopback-handshake","negotiated":true,"wireVersion":$version,"networkMagic":${data.magic},"initiatorOnly":${data.initiatorOnly},"peerSharing":${data.peerSharing},"ledgerConformance":false}"""
            ).as(ExitCode.Success)
          case other => IO.println(s"Handshake failed: $other").as(ExitCode.Error)
        }
      }
      .handleErrorWith(e => IO.println(s"ERROR: ${e.getMessage}").as(ExitCode(2)))
