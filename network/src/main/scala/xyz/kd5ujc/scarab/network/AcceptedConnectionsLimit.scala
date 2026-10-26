// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.Temporal
import cats.syntax.all.*
import scala.concurrent.duration.*

/** The policy that governs how many connections are accepted, mirroring the reference's
  * `AcceptedConnectionsLimit` (ouroboros-network 2fb69829, `Server/RateLimiting.hs:26-42`): two
  * `Word32` limits and a `DiffTime` delay.
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
    acceptedConnectionsHardLimit: Word32,
    acceptedConnectionsSoftLimit: Word32,
    acceptedConnectionsDelay: DiffTime
)

object AcceptedConnectionsLimit:
  /** The reference's defaults: hard 512, soft 384, delay 5 s (`Diffusion/Configuration.hs`,
    * `defaultAcceptedConnectionsLimit`).
    */
  val default: AcceptedConnectionsLimit =
    AcceptedConnectionsLimit(
      Word32.fromUnsigned(512),
      Word32.fromUnsigned(384),
      DiffTime.of(5.seconds)
    )

  /** What the accept loop does before accepting the next connection (the reference's
    * `RateLimitDelay`).
    */
  enum RateLimitDelay:
    case NoRateLimiting
    case SoftDelay(delay: DiffTime)
    case HardLimit(limit: Word32)

  /** The reference's `getRateLimitDecision` (`RateLimiting.hs:67-93`): below the soft limit
    * nothing; at or above the hard limit wait for a drop below it; between the two a delay linear
    * in the excess over the soft limit, in [[DiffTime]] arithmetic, so no intermediate overflows.
    */
  def decision(numberOfConnections: Int, limit: AcceptedConnectionsLimit): RateLimitDelay =
    val hard = limit.acceptedConnectionsHardLimit.toLong
    val soft = limit.acceptedConnectionsSoftLimit.toLong
    if numberOfConnections < soft then RateLimitDelay.NoRateLimiting
    else if numberOfConnections >= hard then
      RateLimitDelay.HardLimit(limit.acceptedConnectionsHardLimit)
    else
      RateLimitDelay.SoftDelay(
        limit.acceptedConnectionsDelay
          .times(numberOfConnections - soft)
          .dividedBy((hard - soft).max(1L))
      )

  /** The reference's `threadDelay` on a `DiffTime` (io-classes 1.8 si-timers,
    * `Control/Monad/Class/MonadTimer/SI.hs:72-101`): nothing for a delay that is not positive, else
    * the delay floored to a microsecond (`diffTimeToMicrosecondsAsInt`), however long it is.
    */
  def threadDelay[F[_]: Temporal](delay: DiffTime): F[Unit] =
    val F = Temporal[F]
    F.tailRecM((delay.toPicoseconds / 1000000).max(0) * 1000) { nanos =>
      if nanos == 0 then F.pure(Right(()))
      else
        val chunk = nanos.min(BigInt(Long.MaxValue)).toLong
        F.sleep(chunk.nanos).as(Left(nanos - chunk))
    }

  /** The reference's `runConnectionRateLimits` (`RateLimiting.hs:99-134`): read the number of
    * inbound connections of `connections` and carry out the decision. At the hard limit it waits
    * until the number drops below it, and at least `acceptedConnectionsDelay`, so that the last
    * slot is not taken again and again by a connection that fails at once. `events` receives the
    * reference's three traces (`AcceptConnectionsPolicyTrace`).
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
          events.emit(ConnectionEvent.AcceptRateLimited(delay, count)) *> threadDelay(delay)
        case RateLimitDelay.HardLimit(hard) =>
          for
            _ <- events.emit(ConnectionEvent.AcceptHardLimit(hard))
            start <- F.monotonic
            _ <- connections.awaitInboundBelow(hard.toLong)
            end <- F.monotonic
            _ <- threadDelay(limit.acceptedConnectionsDelay - DiffTime.of(end - start))
            resumed <- connections.inboundCount
            _ <- events.emit(ConnectionEvent.AcceptResumed(resumed))
          yield ()
    )
