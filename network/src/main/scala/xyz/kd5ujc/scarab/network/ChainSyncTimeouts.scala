// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.Applicative
import cats.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.protocol.ChainSync.State

/** Network pin 2fb69829: Cardano/Network/Protocol/ChainSync/Codec/TimeLimits.hs:41–89 and
  * Ouroboros/Network/Protocol/ChainSync/Codec.hs:53–63. Draw once per MustReply entry, after its
  * AwaitReply receiver hook; partial message reads share that state's deadline.
  */
object ChainSyncTimeouts:
  enum PeerTrust:
    case Trustable, Untrustable

  enum Policy:
    case Reference(peer: PeerTrust)
    case Fixed(deadline: ProtocolDeadline)

    def withPeerTrust(peer: PeerTrust): Policy = this match
      case Reference(_) => Reference(peer)
      case fixed        => fixed

  val MinSeconds: Double = 601.0
  val MaxSeconds: Double = 911.0

  /** random-1.3.1's scaleFloating splits a uniform Word64 around the interval midpoint. Both
    * endpoints are possible; clearing the high bit keeps the unsigned conversion exact before
    * Double rounding. Random streams are interpreter inputs, not cross-language seeds.
    */
  def sampledSeconds(word: Long): Double =
    val fraction = (word & Long.MaxValue).toDouble / 18446744073709551616.0
    val half = (MaxSeconds - MinSeconds) * fraction
    if word < 0 then MinSeconds + half else MaxSeconds - half

  /** Native realToFrac floors the exact binary Double rational at picosecond resolution. */
  def sampledPicoseconds(seconds: Double): BigInt =
    val raw = java.lang.Double.doubleToRawLongBits(seconds)
    val mantissa = raw & 0x000fffffffffffffL
    val exponent = ((raw >>> 52) & 0x7ff).toInt
    val significand =
      if exponent == 0 then BigInt(mantissa) else BigInt(mantissa) + (BigInt(1) << 52)
    val shift = if exponent == 0 then -1074 else exponent - 1075
    val scaled = significand * BigInt(10).pow(12)
    if shift >= 0 then scaled << shift else scaled >> -shift

  /** Preserve the exact policy value above; round only the host timer up to nanoseconds. */
  def sampledDeadline(seconds: Double): ProtocolDeadline =
    ProtocolDeadline.After(((sampledPicoseconds(seconds) + 999) / 1000).toLong.nanos)

  private[network] def next[F[_]: Applicative](
      state: State,
      settings: ChainSyncRuntime.ClientSettings
  )(drawSeconds: F[Double]): F[ProtocolDeadline] =
    settings.timeouts match
      case Some(explicit) =>
        (state match
          case State.Intersect     => explicit.intersect
          case State.NextCanAwait  => explicit.canAwait
          case State.NextMustReply => explicit.mustReply
          case _                   => ProtocolDeadline.Unbounded
        ).pure[F]
      case None =>
        state match
          case State.Intersect    => ProtocolDeadline.After(settings.intersectTimeout).pure[F]
          case State.NextCanAwait => ProtocolDeadline.After(settings.canAwaitTimeout).pure[F]
          case State.NextMustReply =>
            settings.mustReply match
              case Policy.Fixed(deadline)                  => deadline.pure[F]
              case Policy.Reference(PeerTrust.Trustable)   => ProtocolDeadline.Unbounded.pure[F]
              case Policy.Reference(PeerTrust.Untrustable) => drawSeconds.map(sampledDeadline)
          case _ => ProtocolDeadline.Unbounded.pure[F]
