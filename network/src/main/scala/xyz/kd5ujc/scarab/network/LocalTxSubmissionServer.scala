// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.LocalTxSubmission
import xyz.kd5ujc.scarab.protocol.ChainSync.Role
import scala.concurrent.duration.*

/** Node-to-client transaction submission: each submitted transaction is handed to `submit`, which
  * answers with `None` (accepted) or the rejection to send back (one CBOR item).
  */
object LocalTxSubmissionServer:
  final case class Settings(idleTimeout: FiniteDuration = 3373.seconds)

  def run[F[_]: Temporal](
      channel: MuxChannel[F],
      submit: Bytes => F[Option[Bytes]],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[Unit] =
    val id = Protocols.LocalTxSubmission
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]
    def send(state: LocalTxSubmission.State, message: LocalTxSubmission.Message): F[Unit] =
      LocalTxSubmission.encode(state, Role.Server, message).fold(illegal, channel.send)
    val step: F[Boolean] =
      Timed
        .within(settings.idleTimeout, id, "idle")(
          channel.recv(LocalTxSubmission.MaxMessageBytes)(
            LocalTxSubmission.decodePrefix(LocalTxSubmission.State.Idle, Role.Client, _)
          )
        )
        .flatMap {
          case LocalTxSubmission.Message.SubmitTx(tx) =>
            submit(tx).flatMap {
              case None =>
                send(LocalTxSubmission.State.Busy, LocalTxSubmission.Message.AcceptTx).as(true)
              case Some(reason) =>
                send(LocalTxSubmission.State.Busy, LocalTxSubmission.Message.RejectTx(reason))
                  .as(true)
            }
          case LocalTxSubmission.Message.Done => Temporal[F].pure(false)
          case other                          => illegal(s"unexpected client message $other")
        }
    step.iterateWhile(identity).void

  /** One inbound node-to-client connection: handshake (versions 16 to 23), then submission. */
  def accept[F[_]: cats.effect.Async](
      bearer: Bearer[F],
      magic: Long,
      submit: Bytes => F[Option[Bytes]],
      settings: Settings = Settings()
  )(using Raise[F, NetworkError]): F[String] =
    Mux
      .resource(
        bearer,
        Set(Protocols.Handshake, Protocols.LocalTxSubmission),
        Mux.Config(role = Mux.Role.Responder)
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
          // the connection ends when the client finishes the protocol or closes the socket
          ended <- cats.effect.Async[F].race(run(channel, submit, settings), mux.closed)
          reason = ended.fold(_ => "client finished", identity)
        yield reason
      }
