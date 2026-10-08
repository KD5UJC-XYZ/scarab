// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, ExitCode}
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.*
import scala.concurrent.duration.*

/** Offline fixture scripts over ephemeral IPv4 localhost sockets, never a node probe. */
object NetworkCommand:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).toOption.get
  private def checked[A](result: Either[String, A]): IO[A] =
    IO.fromEither(result.leftMap(new IllegalArgumentException(_)))

  private def script(peer: ByteTransport[IO], request: Bytes, reply: Bytes): IO[Unit] =
    def collect(pending: Bytes): IO[Unit] =
      if pending.size == request.size then
        IO.raiseUnless(pending == request)(
          new IllegalStateException("request differs from literal fixture")
        )
      else if pending.size > request.size then
        IO.raiseError(new IllegalStateException("oversized request"))
      else
        peer.read.flatMap {
          case None => IO.raiseError(new IllegalStateException("EOF awaiting fixture request"))
          case Some(chunk) => collect(Bytes(pending.value ++ chunk.value))
        }
    collect(Bytes.empty) *> reply.value
      .grouped(2)
      .toVector
      .traverse_(chunk => peer.write(Bytes(chunk)))

  private def exchange(
      suite: Handshake.Suite,
      version: Int,
      data: Handshake.Data,
      request: String,
      response: String
  ): IO[Handshake.Result] =
    TcpLoopback
      .pair[IO]()
      .use { case (clientTransport, peer) =>
        HandshakeSession
          .resource[IO](clientTransport, suite, Mux.Direction.Initiator, 2.seconds)
          .use { session =>
            for
              started <- checked(Handshake.clientStart(suite, Vector(version -> data)))
              (client, proposal) = started
              result <- (
                script(peer, bytes(request), bytes(response)),
                session.send(proposal) *> session
                  .receive(Handshake.State.Confirm)
                  .flatMap(message => checked(client.receive(message)))
                  .map(_._2)
              ).parTupled
            yield result._2
          }
      }
      .timeout(3.seconds)

  def checks: IO[Vector[Handshake.Result]] =
    Vector(
      exchange(
        Handshake.Suite.NodeToNode,
        14,
        Handshake.Data(2),
        "00000000000000098200a10e8402f500f4",
        "000000008000000883010e8402f500f4"
      ),
      exchange(
        Handshake.Suite.NodeToClient,
        16,
        Handshake.Data(2),
        "00000000000000098200a11980108202f4",
        "000000008000000883011980108202f4"
      ),
      exchange(
        Handshake.Suite.NodeToClient,
        16,
        Handshake.Data(764824073L),
        "000000000000000d8200a1198010821a2d964a09f4",
        "000000008000000c8301198010821a2d964a09f4"
      ),
      exchange(
        Handshake.Suite.NodeToClient,
        23,
        Handshake.Data(2, query = true),
        "00000000000000098200a11980178202f5",
        "00000000800000098203a11980178202f4"
      )
    ).sequence

  def run: IO[ExitCode] = checks
    .flatMap { results =>
      val negotiated = results.count {
        case Handshake.Result.Negotiated(_, _) => true
        case _                                 => false
      }
      val queried = results.count {
        case Handshake.Result.QueryResult(_) => true
        case _                               => false
      }
      val ok = negotiated == 3 && queried == 1
      IO.println(
        s"""{"scope":"handshake/mux source conformance and local transport simulation","transport":"TCP 127.0.0.1 ephemeral","reference":"c45735a56c567fa977969173d18943bac6bb3821","checks":4,"negotiated":$negotiated,"queryResults":$queried,"passed":$ok,"referenceRuntimeChecked":false,"chainSync":false}"""
      ).as(if ok then ExitCode.Success else ExitCode.Error)
    }
    .handleErrorWith(error => IO.println(s"ERROR: ${error.getMessage}").as(ExitCode(2)))
