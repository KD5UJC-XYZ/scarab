// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network


import cats.effect.{Clock, Ref, Resource, Temporal}
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.protocol.KeepAlive
import scala.concurrent.duration.*

/** KeepAlive client on mini-protocol 8. Use from a single fiber. */
trait KeepAliveClient[F[_]]:
  /** One request/response round trip; returns its duration. */
  def ping(using Raise[F, NetworkError]): F[FiniteDuration]

object KeepAliveClient:
  /** The upstream server drops a client that is silent for 97 seconds, so ping well inside that. */
  final case class Settings(
      interval: FiniteDuration = 30.seconds,
      responseTimeout: FiniteDuration = 60.seconds,
      limits: KeepAlive.Limits = KeepAlive.Limits()
  )

  def apply[F[_]: Temporal](
      channel: MuxChannel[F],
      settings: Settings = Settings()
  ): F[KeepAliveClient[F]] =
    val F = Temporal[F]
    val id = Protocols.KeepAlive
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    Ref.of[F, Int](0).map { counter =>
      new KeepAliveClient[F]:
        def ping(using Raise[F, NetworkError]): F[FiniteDuration] =
          for
            n <- counter.getAndUpdate(c => (c + 1) % (KeepAlive.Cookie.Max + 1))
            cookie <- KeepAlive.Cookie.from(n).fold(illegal(_), F.pure)
            request = KeepAlive.Message.Request(cookie)
            bytes <- KeepAlive
              .encode(KeepAlive.State.Client, KeepAlive.Role.Client, request, settings.limits)
              .fold(illegal(_), F.pure)
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
            _ <- KeepAlive.matchResponse(Some(cookie), response).fold(illegal(_), F.pure)
            finished <- Clock[F].monotonic
          yield finished - started
    }

  /** Ping every `interval` for the life of the resource. A failed ping aborts the whole
    * multiplexer, so pending operations on the other protocols raise the reason.
    */
  def background[F[_]: Temporal](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings = Settings()
  ): Resource[F, Unit] =
    Temporal[F].background(pinger(channel, mux, settings)).void

  /** The pinger fiber. Whatever ends it ends the connection. It runs in an error scope of its own
    * ([[Fibers.scoped]]) and ends with the reason as a value, which it hands to the multiplexer:
    * the channel raises through the scope of the fiber that calls it, so a connection lost under a
    * waiting ping is a value here and not a failure of the fiber (issue 130).
    */
  private[network] def pinger[F[_]: Temporal](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings
  ): F[Unit] =
    Fibers
      .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("keep-alive", t)) {
        apply[F](channel, settings)
          .flatMap(client => (client.ping *> Temporal[F].sleep(settings.interval)).foreverM[Unit])
      }
      .flatMap(_.fold(mux.abort, _ => Temporal[F].unit))
