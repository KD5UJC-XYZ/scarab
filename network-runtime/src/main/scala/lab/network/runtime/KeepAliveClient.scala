// SPDX-License-Identifier: Apache-2.0
package lab.network.runtime

import cats.effect.{Clock, Ref, Resource, Temporal}
import cats.mtl.{Handle, Raise}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.network.KeepAlive
import scala.concurrent.duration.*

/** KeepAlive client on mini-protocol 8. Use from a single fiber. */
trait KeepAliveClient[F[_]]:
  /** One request/response round trip; returns its duration. */
  def ping: F[FiniteDuration]

object KeepAliveClient:
  /** The upstream server drops a client that is silent for 97 seconds, so ping well inside that. */
  final case class Settings(
      interval: FiniteDuration = 30.seconds,
      responseTimeout: FiniteDuration = 60.seconds,
      limits: KeepAlive.Limits = KeepAlive.Limits()
  )

  def apply[F[_]: Temporal](channel: MuxChannel[F], settings: Settings = Settings())(using
      Raise[F, NetworkError]
  ): F[KeepAliveClient[F]] =
    val F = Temporal[F]
    val id = Protocols.KeepAlive
    def illegal[A](detail: String): F[A] = NetworkError.IllegalTransition(id, detail).raise[F, A]

    Ref.of[F, Int](0).map { counter =>
      new KeepAliveClient[F]:
        def ping: F[FiniteDuration] =
          for
            n <- counter.getAndUpdate(c => (c + 1) % (KeepAlive.Cookie.Max + 1))
            cookie <- KeepAlive.Cookie.from(n).fold(illegal, F.pure)
            request = KeepAlive.Message.Request(cookie)
            bytes <- KeepAlive
              .encode(KeepAlive.State.Client, KeepAlive.Role.Client, request, settings.limits)
              .fold(illegal, F.pure)
            started <- Clock[F].monotonic
            _ <- channel.send(bytes)
            response <- Timed.within(settings.responseTimeout, id, "server")(
              channel.recv(settings.limits.maxMessageBytes)(
                KeepAlive.decodePrefix(
                  KeepAlive.State.Server,
                  KeepAlive.Role.Server,
                  _,
                  settings.limits
                )
              )
            )
            _ <- KeepAlive.matchResponse(Some(cookie), response).fold(illegal, F.pure)
            finished <- Clock[F].monotonic
          yield finished - started
    }

  /** Ping every `interval` for the life of the resource. A failed ping aborts the whole
    * multiplexer, so pending operations on the other protocols raise `BearerClosed`.
    */
  def background[F[_]: Temporal](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings = Settings()
  ): Resource[F, Unit] =
    val pinger: F[Unit] =
      Handle
        .allow[NetworkError] {
          apply[F](channel, settings)
            .flatMap(client => (client.ping *> Temporal[F].sleep(settings.interval)).foreverM[Unit])
        }
        .rescue(error => mux.abort(error.message))
    Temporal[F].background(pinger).void
