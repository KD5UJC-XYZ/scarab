// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.Async
import cats.effect.std.Random
import cats.syntax.all.*
import scala.concurrent.duration.*

/** Local trust and monotonic durations, never inferred from peer data. */
final case class SessionDeadlines[F[_]](
    handshake: FiniteDuration,
    idle: FiniteDuration,
    canAwait: FiniteDuration,
    mustReply: F[Option[FiniteDuration]]
):
  private[network] def duration(state: ChainSync.State)(using
      F: Async[F]
  ): F[Option[FiniteDuration]] =
    state match
      case ChainSync.State.Idle                                     => F.pure(Some(idle))
      case ChainSync.State.Intersect | ChainSync.State.NextCanAwait => F.pure(Some(canAwait))
      case ChainSync.State.NextMustReply                            => mustReply
      case ChainSync.State.Done                                     => F.pure(None)

object SessionDeadlines:
  def default[F[_]: Async](trusted: Boolean = false): F[SessionDeadlines[F]] =
    Random.scalaUtilRandom[F].map { random =>
      SessionDeadlines(
        10.seconds,
        3373.seconds,
        10.seconds,
        if trusted then Async[F].pure(None)
        else random.betweenInt(601, 912).map(n => Some(n.seconds))
      )
    }
