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

/** Node-to-node handshake, responder side: reads the client's proposal, answers through the pure
  * [[Handshake.responder]] and reports what was agreed. `Agreed.data.initiatorOnly` is false
  * exactly when both sides allow a duplex connection (we then also run our clients on it).
  */
object HandshakeServer:
  /** The versions this node accepts, all with the same magic and no peer sharing, no query. */
  def offers(magic: Long, duplex: Boolean): Vector[(Int, Handshake.Data)] =
    Vector(14, 15).map(v => v -> Handshake.Data(magic, initiatorOnly = !duplex))

  private def decodeProposal(bytes: Bytes): DecodeResult[Handshake.Message] =
    Handshake.decodePrefix(Handshake.Suite.NodeToNode, Handshake.State.Propose, bytes) match
      case Right((message, rest))  => DecodeResult.Decoded(message, bytes.size - rest.size)
      case Left(e) if e.incomplete => DecodeResult.NeedMore
      case Left(e)                 => DecodeResult.Failed(e.message)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      supported: Vector[(Int, Handshake.Data)],
      timeout: FiniteDuration = Handshake.DefaultPhaseTimeout
  )(using Raise[F, NetworkError]): F[HandshakeClient.Agreed] =
    def refuse[A](reason: String): F[A] = NetworkError.HandshakeRefused(reason).raise[F, A]
    for
      proposal <- Timed.within(timeout, Protocols.Handshake, "propose")(
        channel.recv(Handshake.MaxMessageBytes)(decodeProposal)
      )
      answer <- Handshake
        .responder(Handshake.Suite.NodeToNode, supported, proposal)
        .fold(refuse, Temporal[F].pure)
      bytes <- Handshake
        .encode(Handshake.Suite.NodeToNode, answer._1)
        .fold(refuse, Temporal[F].pure)
      _ <- channel.send(bytes) *> channel.flush
      agreed <- answer._2 match
        case Handshake.Result.Negotiated(version, data) =>
          Temporal[F].pure(HandshakeClient.Agreed(version, data))
        case Handshake.Result.Rejected(Handshake.Reason.VersionMismatch(_, _)) =>
          proposal match
            case Handshake.Message.Propose(vs) =>
              NetworkError
                .VersionMismatch(vs.map(_._1), supported.map(_._1))
                .raise[F, HandshakeClient.Agreed]
            case _ => refuse("version mismatch")
        case Handshake.Result.Rejected(Handshake.Reason.DecodeError(v, text)) =>
          refuse(s"could not decode version $v data: $text")
        case Handshake.Result.Rejected(Handshake.Reason.Refused(v, text)) =>
          refuse(s"refused version $v: $text")
        case Handshake.Result.QueryResult(_) => refuse("version query answered")
    yield agreed
