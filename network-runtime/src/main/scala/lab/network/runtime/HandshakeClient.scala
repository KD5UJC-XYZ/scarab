// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync.DecodeResult
import lab.network.Handshake
import scala.concurrent.duration.*

/** Node-to-node handshake over mini-protocol 0, driven by the pure [[Handshake]] client. */
object HandshakeClient:
  final case class Agreed(version: Int, data: Handshake.Data)

  private def decodeReply(bytes: Bytes): DecodeResult[Handshake.Message] =
    Handshake.decodePrefix(Handshake.Suite.NodeToNode, Handshake.State.Confirm, bytes) match
      case Right((message, rest))  => DecodeResult.Decoded(message, bytes.size - rest.size)
      case Left(e) if e.incomplete => DecodeResult.NeedMore
      case Left(e)                 => DecodeResult.Failed(e.message)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      offers: Vector[(Int, Handshake.Data)],
      timeout: FiniteDuration = Handshake.DefaultPhaseTimeout
  )(using Raise[F, NetworkError]): F[Agreed] =
    def refuse[A](reason: String): F[A] = NetworkError.HandshakeRefused(reason).raise[F, A]
    for
      start <- Handshake
        .clientStart(Handshake.Suite.NodeToNode, offers)
        .fold(refuse, Temporal[F].pure)
      (client, proposal) = start
      bytes <- Handshake
        .encode(Handshake.Suite.NodeToNode, proposal)
        .fold(refuse, Temporal[F].pure)
      _ <- channel.send(bytes)
      reply <- Timed.within(timeout, Protocols.Handshake, "confirm")(
        channel.recv(Handshake.MaxMessageBytes)(decodeReply)
      )
      outcome <- client.receive(reply).fold(refuse, Temporal[F].pure)
      agreed <- outcome._2 match
        case Handshake.Result.Negotiated(version, data) =>
          Temporal[F].pure(Agreed(version, data))
        case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(supported, _)) =>
          NetworkError.VersionMismatch(offers.map(_._1), supported).raise[F, Agreed]
        case Handshake.Result.Rejected(Handshake.Reason.DecodeError(v, text)) =>
          refuse(s"peer could not decode version $v data: $text")
        case Handshake.Result.Rejected(Handshake.Reason.Refused(v, text)) =>
          refuse(s"peer refused version $v: $text")
        case Handshake.Result.QueryResult(_) => refuse("unexpected query reply")
    yield agreed
