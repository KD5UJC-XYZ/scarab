// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import NetworkError.RuntimeViolation

import cats.effect.Temporal
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.KeepAlive
import scala.concurrent.duration.*

/** KeepAlive responder: echoes every cookie until the client says Done. A client silent for
  * `idleTimeout` (the upstream bound is 97 s) ends the conversation with a timeout.
  */
object KeepAliveServer:
  final case class Settings(
      idleTimeout: FiniteDuration = KeepAlive.UpstreamTimeLimits.client,
      limits: KeepAlive.Limits = KeepAlive.Limits()
  )

  def run[F[_]: Temporal](channel: MuxChannel[F], settings: Settings = Settings())(using
      Raise[F, NetworkError]
  ): F[Unit] =
    val id = Protocols.KeepAlive
    def illegal[A](detail: NetworkError.Violation): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]
    val loop: F[Boolean] =
      Timed
        .within(settings.idleTimeout, id, "client")(
          channel.recv(settings.limits.maxMessageBytes)(
            KeepAlive.decodePrefix(
              KeepAlive.State.Client,
              KeepAlive.Role.Client,
              _,
              settings.limits
            )
          )
        )
        .flatMap {
          case KeepAlive.Message.Request(cookie) =>
            KeepAlive
              .encode(
                KeepAlive.State.Server,
                KeepAlive.Role.Server,
                KeepAlive.Message.Response(cookie),
                settings.limits
              )
              .fold(illegal, channel.send)
              .as(true)
          case KeepAlive.Message.Done => Temporal[F].pure(false)
          case other                  => illegal(RuntimeViolation.KeepAliveUnexpected(other))
        }
    loop.iterateWhile(identity).void
