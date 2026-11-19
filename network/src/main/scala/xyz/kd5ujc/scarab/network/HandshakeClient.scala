// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import xyz.kd5ujc.scarab.protocol.ProtocolError
import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.{Handshake, NtnVersion}
import scala.concurrent.duration.*

/** Node-to-node handshake over mini-protocol 0, driven by the pure [[Handshake]] client. */
object HandshakeClient:
  final case class Agreed(version: Int, data: Handshake.Data):
    /** The agreed node-to-node version as the typed value (`None` for a node-to-client one). */
    def ntnVersion: Option[NtnVersion] = NtnVersion.fromNumber(version)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      offers: Vector[(Int, Handshake.Data)],
      timeout: FiniteDuration = Handshake.DefaultPhaseTimeout,
      suite: Handshake.Suite = Handshake.Suite.NodeToNode
  )(using Raise[F, NetworkError]): F[Agreed] =
    def refuse[A](reason: ProtocolError | NetworkError.HandshakeFailure): F[A] = NetworkError
      .HandshakeRefused(reason match
        case error: ProtocolError                 => NetworkError.HandshakeFailure.Local(error)
        case error: NetworkError.HandshakeFailure => error)
      .raise[F, A]
    for
      start <- Handshake
        .clientStart(suite, offers)
        .fold(refuse, Temporal[F].pure)
      (client, proposal) = start
      bytes <- Handshake
        .encode(suite, proposal)
        .fold(refuse, Temporal[F].pure)
      _ <- channel.send(bytes)
      reply <- Timed.within(timeout, Protocols.Handshake, "confirm")(
        channel.recv(Handshake.MaxMessageBytes)(
          Handshake.incremental(suite, Handshake.State.Confirm)
        )
      )
      outcome <- client.receive(reply).fold(refuse, Temporal[F].pure)
      agreed <- outcome._2 match
        case Handshake.Result.Negotiated(version, data) =>
          Temporal[F].pure(Agreed(version, data))
        case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(supported, _)) =>
          NetworkError.VersionMismatch(offers.map(_._1), supported).raise[F, Agreed]
        case Handshake.Result.Rejected(Handshake.Reason.DecodeError(v, text)) =>
          refuse(NetworkError.HandshakeFailure.Remote(Handshake.Reason.DecodeError(v, text)))
        case Handshake.Result.Rejected(Handshake.Reason.Refused(v, text)) =>
          refuse(NetworkError.HandshakeFailure.Remote(Handshake.Reason.Refused(v, text)))
        case Handshake.Result.QueryResult(_) =>
          refuse(NetworkError.HandshakeFailure.UnexpectedQueryReply)
    yield agreed
