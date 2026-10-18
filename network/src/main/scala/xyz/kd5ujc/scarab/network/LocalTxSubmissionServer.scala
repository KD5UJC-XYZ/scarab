// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.LocalTxSubmission
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

/** Node-to-client transaction submission. Replies distinguish admission from a ledger rejection and
  * from service unavailability, which closes the connection without a consensus verdict.
  */
object LocalTxSubmissionServer:
  enum Unavailable:
    case ScriptEvaluation[E <: Product & Serializable](cause: E)

    def message: String = this match
      case ScriptEvaluation(cause) => s"script evaluation unavailable: $cause"

  enum Response:
    case Accepted
    case Rejected(reason: Bytes)
    case ServiceUnavailable(error: Unavailable)

  enum End:
    case ClientFinished
    case ConnectionClosed(error: NetworkError)
    case ServiceUnavailable(error: Unavailable)

    def message: String = this match
      case ClientFinished            => "client finished"
      case ConnectionClosed(error)   => error.message
      case ServiceUnavailable(error) => s"service unavailable: ${error.message}"

  final case class Settings(idleTimeout: FiniteDuration = 3373.seconds)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      submit: Bytes => F[Response],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Option[Unavailable]] =
    val id = Protocols.LocalTxSubmission
    def illegal[A](detail: NetworkError.Violation): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: LocalTxSubmission.State, message: LocalTxSubmission.Message): F[Unit] =
      LocalTxSubmission.encode(state, Role.Server, message).fold(illegal, channel.send)
    val step: F[Either[Unit, Option[Unavailable]]] =
      Timed
        .within(settings.idleTimeout, id, "idle")(
          channel.recv(LocalTxSubmission.MaxMessageBytes)(
            LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Idle, Role.Client, _)
          )
        )
        .flatMap {
          case LocalTxSubmission.Message.SubmitTx(tx) =>
            submit(tx).flatMap {
              case Response.Accepted =>
                send(LocalTxSubmission.State.Busy, LocalTxSubmission.Message.AcceptTx).as(Left(()))
              case Response.Rejected(reason) =>
                send(LocalTxSubmission.State.Busy, LocalTxSubmission.Message.RejectTx(reason))
                  .as(Left(()))
              case Response.ServiceUnavailable(error) => Temporal[F].pure(Right(Some(error)))
            }
          case LocalTxSubmission.Message.Done => Temporal[F].pure(Right(None))
          case other                          => illegal(RuntimeViolation.LocalTxSubmissionUnexpected(other))
        }
    Temporal[F].tailRecM(())(_ => step)

  /** One inbound node-to-client connection: handshake (versions 16 to 23), then submission. */
  def accept[F[_]: cats.effect.Async](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[Response],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[End] =
    Mux
      .resource(
        bearer,
        Set(Protocols.Handshake, Protocols.LocalTxSubmission),
        Mux.Config(role = Mux.Role.Responder, ingressLimit = IngressLimits.nodeToClient)
      )
      .use { mux =>
        for
          _ <- mux
            .channel(Protocols.Handshake)
            .flatMap(
              HandshakeServer.run(
                _,
                HandshakeServer.localOffers(magic),
                suite = xyz.kd5ujc.scarab.protocol.Handshake.Suite.NodeToClient
              )
            )
          channel <- mux.channel(Protocols.LocalTxSubmission)
          // the connection ends when the client finishes the protocol or closes the socket; the
          // protocol runs in a fiber of its own and its failure comes back as a value
          ended <- cats.effect
            .Async[F]
            .race(
              Fibers.scoped[F, NetworkError, Option[Unavailable]](
                NetworkError.fromThrowable("local-tx-submission", _)
              )(run(channel, submit, settings)),
              mux.closed
            )
          reason <- ended.fold(
            result =>
              Fibers
                .land[F, NetworkError, Option[Unavailable]](result)
                .map(
                  _.fold[End](End.ClientFinished)(End.ServiceUnavailable(_))
                ),
            closed =>
              closed match
                case defect: NetworkError.Defect => defect.raise[F, End]
                case other => cats.effect.Async[F].pure(End.ConnectionClosed(other))
          )
        yield reason
      }
