// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.syntax.all.*
import scala.concurrent.duration.*

/** The policy that governs how many connections are accepted, mirroring the reference's
  * `AcceptedConnectionsLimit` (ouroboros-network 2fb69829, `Server/RateLimiting.hs`).
  *
  * @param acceptedConnectionsHardLimit
  *   Hard limit of accepted connections: at it, the accept loop waits, and the connection manager
  *   refuses a connection that would exceed it (`Core.hs`, `includeInboundConnectionImpl`).
  * @param acceptedConnectionsSoftLimit
  *   Soft limit: above it, accepting is rate limited.
  * @param acceptedConnectionsDelay
  *   Max delay for limiting accepted connections: linear from 0 at the soft limit up to this at the
  *   hard limit, and the least time the loop pauses once it reached the hard limit.
  */
final case class AcceptedConnectionsLimit(
    acceptedConnectionsHardLimit: Int,
    acceptedConnectionsSoftLimit: Int,
    acceptedConnectionsDelay: FiniteDuration
)

object AcceptedConnectionsLimit:
  /** The reference's defaults: hard 512, soft 384, delay 5 s (`Diffusion/Configuration.hs`,
    * `defaultAcceptedConnectionsLimit`).
    */
  val default: AcceptedConnectionsLimit = AcceptedConnectionsLimit(512, 384, 5.seconds)

  /** What the accept loop does before accepting the next connection (the reference's
    * `RateLimitDelay`).
    */
  enum RateLimitDelay:
    case NoRateLimiting
    case SoftDelay(delay: FiniteDuration)
    case HardLimit(limit: Int)

  /** The reference's `getRateLimitDecision`: below the soft limit nothing; at or above the hard
    * limit wait for a drop below it; between the two a delay linear in the excess over the soft
    * limit.
    */
  def decision(numberOfConnections: Int, limit: AcceptedConnectionsLimit): RateLimitDelay =
    val hard = limit.acceptedConnectionsHardLimit
    val soft = limit.acceptedConnectionsSoftLimit
    if numberOfConnections < soft then RateLimitDelay.NoRateLimiting
    else if numberOfConnections >= hard then RateLimitDelay.HardLimit(hard)
    else
      RateLimitDelay.SoftDelay(
        (limit.acceptedConnectionsDelay.toNanos * (numberOfConnections - soft) /
          (hard - soft).max(1)).nanos
      )

  /** The reference's `runConnectionRateLimits`: read the number of inbound connections of
    * `connections` and carry out the decision. At the hard limit it waits until the number drops
    * below it, and at least `acceptedConnectionsDelay`, so that the last slot is not taken again
    * and again by a connection that fails at once. `events` receives the reference's three traces
    * (`AcceptConnectionsPolicyTrace`).
    */
  def rateLimit[F[_]: Temporal](
      connections: ConnectionManager[F],
      limit: AcceptedConnectionsLimit,
      events: ConnectionEvents[F]
  ): F[Unit] =
    val F = Temporal[F]
    connections.inboundCount.flatMap(count =>
      decision(count, limit) match
        case RateLimitDelay.NoRateLimiting => F.unit
        case RateLimitDelay.SoftDelay(delay) =>
          events.emit(ConnectionEvent.AcceptRateLimited(delay, count)) *> F.sleep(delay)
        case RateLimitDelay.HardLimit(hard) =>
          for
            _ <- events.emit(ConnectionEvent.AcceptHardLimit(hard))
            start <- F.monotonic
            _ <- connections.awaitInboundBelow(hard)
            end <- F.monotonic
            remaining = limit.acceptedConnectionsDelay - (end - start)
            _ <- F.sleep(remaining).whenA(remaining > Duration.Zero)
            resumed <- connections.inboundCount
            _ <- events.emit(ConnectionEvent.AcceptResumed(resumed))
          yield ()
    )
