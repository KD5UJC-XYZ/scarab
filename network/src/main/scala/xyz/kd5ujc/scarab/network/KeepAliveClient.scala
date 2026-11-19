// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Clock, Ref, Resource, Sync, Temporal}
import cats.effect.std.SecureRandom
import cats.mtl.Raise
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import xyz.kd5ujc.scarab.network.selection.Rng
import xyz.kd5ujc.scarab.protocol.KeepAlive
import scala.concurrent.duration.*

/** KeepAlive client on mini-protocol 8. Use from a single fiber. */
trait KeepAliveClient[F[_]]:
  /** One request/response round trip; returns its duration. */
  def ping(using Raise[F, NetworkError]): F[FiniteDuration]

  /** End at the client-agency boundary. */
  def done(using Raise[F, NetworkError]): F[Unit]

object KeepAliveClient:
  /** Where the cookies of a client come from. The reference's client draws each cookie from a
    * `StdGen` and keeps the advanced generator for the next (ouroboros-network 4646039f,
    * `KeepAlive.hs` 55-59 and 100-102, as at c45735a5), and the node gives the client of every
    * connection the same generator, `keepAliveRng`, split from its own at start-up
    * (ouroboros-consensus 808e4005, `Node.hs` 661-664 and 961-989, `Network/NodeToNode.hs` 409).
    */
  enum Cookies:
    /** Every client made with these settings starts from `start`, as each connection's client
      * starts from the node's `keepAliveRng` in the reference: for tests and tools.
      */
    case Shared(start: Rng)

    /** Each client seeds a generator of its own from the platform's secure generator when it is
      * made, so connections are seeded independently: an approved deviation from the reference's
      * one `keepAliveRng` for every connection (docs/decisions.md, networking).
      */
    case Fresh

  /** The upstream server drops a client that is silent for 97 seconds; the client pings every 10
    * seconds, as the reference's does (`KeepAliveInterval 10`, ouroboros-consensus 808e4005,
    * `Network/NodeToNode.hs` 1108).
    */
  final case class Settings(
      interval: FiniteDuration = 10.seconds,
      responseTimeout: FiniteDuration = 60.seconds,
      limits: KeepAlive.Limits = KeepAlive.Limits(),
      cookies: Cookies = Cookies.Fresh
  )

  /** Each ping draws its cookie from the client's generator, the low 16 bits of the generator's
    * next 64 as `random` draws a `Word16` from a `StdGen`, and keeps the advanced generator for the
    * next (`KeepAlive.hs` 55-59 and 100-102). A response with another cookie is a protocol
    * violation (`Protocol/KeepAlive/Client.hs` 49-53, `KeepAliveCookieMissmatch`).
    */
  def apply[F[_]: Async](
      channel: MuxChannel[F],
      settings: Settings = Settings()
  ): F[KeepAliveClient[F]] =
    val F = Async[F]
    val id = Protocols.KeepAlive
    def illegal[A](detail: NetworkError.Violation)(using Raise[F, NetworkError]): F[A] =
      NetworkError.IllegalTransition(id, detail).raise[F, A]

    start[F](settings.cookies).flatMap(Ref.of[F, Rng]).map { generator =>
      new KeepAliveClient[F]:
        def done(using Raise[F, NetworkError]): F[Unit] =
          KeepAlive
            .encode(
              KeepAlive.State.Client,
              KeepAlive.Role.Client,
              KeepAlive.Message.Done,
              settings.limits
            )
            .fold(illegal[Unit], bytes => channel.send(bytes) *> channel.flush)
        def ping(using Raise[F, NetworkError]): F[FiniteDuration] =
          for
            n <- generator.modify(word16)
            cookie <- KeepAlive.Cookie.from(n).fold(illegal(_), F.pure)
            request = KeepAlive.Message.Request(cookie)
            bytes <- KeepAlive
              .encode(KeepAlive.State.Client, KeepAlive.Role.Client, request, settings.limits)
              .fold(illegal(_), F.pure)
            started <- Clock[F].monotonic
            _ <- channel.send(bytes)
            response <- Timed.within(settings.responseTimeout, id, "server")(
              channel.recv(settings.limits.maxMessageBytes)(
                KeepAlive.incremental(
                  KeepAlive.State.Server,
                  KeepAlive.Role.Server,
                  settings.limits
                )
              )
            )
            _ <- KeepAlive.matchResponse(Some(cookie), response).fold(illegal(_), F.pure)
            finished <- Clock[F].monotonic
          yield finished - started
    }

  /** The generator a new client starts from. */
  private def start[F[_]: Sync](cookies: Cookies): F[Rng] = cookies match
    case Cookies.Shared(rng) => Sync[F].pure(rng)
    case Cookies.Fresh => SecureRandom.javaSecuritySecureRandom[F].flatMap(_.nextLong).map(Rng(_))

  /** The next `Word16` and the advanced generator, as a new state and its value for `modify`. */
  private def word16(rng: Rng): (Rng, Int) =
    val (bits, next) = rng.nextLong
    (next, (bits & 0xffffL).toInt)

  /** Ping every `interval` for the life of the resource. A failed ping aborts the whole
    * multiplexer, so pending operations on the other protocols raise the reason.
    */
  def background[F[_]: Async](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings = Settings(),
      sample: Option[FiniteDuration => F[Unit]] = None
  ): Resource[F, Unit] =
    Temporal[F].background(pinger(channel, mux, settings, sample)).void

  /** The established application accepts Terminate, sends MsgDone after its current ping, and
    * publishes its result before mux failure can cancel its resource. PeerStateActions owns the
    * result handle (c45735a5 PeerSelection/PeerStateActions.hs:1055–1077).
    */
  def controlled[F[_]: Async](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings,
      sample: Option[FiniteDuration => F[Unit]],
      completed: Either[NetworkError, Unit] => F[Unit],
      control: F[ProtocolResults.Control],
      terminated: F[Unit]
  ): Resource[F, Unit] =
    Temporal[F]
      .background(
        Fibers
          .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("keep-alive", t))(
            apply[F](channel, settings).flatMap { client =>
              Temporal[F].tailRecM(()) { _ =>
                control.flatMap {
                  case ProtocolResults.Control.Terminate => client.done.as(Right(()))
                  case _ =>
                    client.ping.flatMap(duration => sample.traverse_(_(duration))) *>
                      Temporal[F]
                        .race(terminated, Temporal[F].sleep(settings.interval))
                        .as(Left(()))
                }
              }
            }
          )
          .flatTap(completed)
          .flatMap(_.fold(mux.abort, _ => Temporal[F].unit))
      )
      .void

  /** The pinger fiber. Whatever ends it ends the connection. It runs in an error scope of its own
    * ([[Fibers.scoped]]) and ends with the reason as a value, which it hands to the multiplexer:
    * the channel raises through the scope of the fiber that calls it, so a connection lost under a
    * waiting ping is a value here and not a failure of the fiber (issue 130).
    */
  private[network] def pinger[F[_]: Async](
      channel: MuxChannel[F],
      mux: Mux[F],
      settings: Settings,
      sample: Option[FiniteDuration => F[Unit]] = None
  ): F[Unit] =
    Fibers
      .scoped[F, NetworkError, Unit](t => NetworkError.fromThrowable("keep-alive", t)) {
        apply[F](channel, settings)
          .flatMap(client =>
            (client.ping.flatMap(duration => sample.traverse_(_(duration))) *> Temporal[F]
              .sleep(settings.interval)).foreverM[Unit]
          )
      }
      .flatMap(_.fold(mux.abort, _ => Temporal[F].unit))
