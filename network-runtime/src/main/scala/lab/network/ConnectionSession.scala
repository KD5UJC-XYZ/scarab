// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Compatibility facade over the shared single-owner network runtime. */
final class ConnectionSession[F[_]: Async, P] private (
    owner: SingleProtocolConnection[F, ChainSync.State, ChainSync.Message[P]],
    val profile: ConnectionSession.Profile[P],
    val role: ChainSync.Role
):
  def status: F[ConnectionSession.Status] = owner.status.map(s =>
    ConnectionSession.Status(
      s.active,
      s.state,
      s.closed,
      s.events,
      s.epoch,
      s.ingressBytes,
      s.peakIngressBytes,
      s.frames,
      s.peakFrames
    )
  )
  def negotiate(offers: Vector[(Int, Handshake.Data)]): F[Handshake.Result] =
    owner.negotiate(offers)
  def send(message: ChainSync.Message[P]): F[Unit] = owner.send(message)
  def receive: F[ChainSync.Message[P]] = owner.receive
  def done: F[Unit] = send(ChainSync.Message.Done)
  def close: F[Unit] = owner.close

object ConnectionSession:
  sealed abstract class Profile[P](
      val suite: Handshake.Suite,
      val version: Int,
      val protocol: Int,
      val codec: ChainSync.PayloadCodec[P]
  )
  case object NtN14
      extends Profile[ChainSyncFixtures.OpaqueNtNHeaderFixture](
        Handshake.Suite.NodeToNode,
        14,
        2,
        ChainSyncFixtures.ntnHeader
      )
  case object NtC16
      extends Profile[ChainSyncFixtures.OpaqueNtCBlockFixture](
        Handshake.Suite.NodeToClient,
        16,
        5,
        ChainSyncFixtures.ntcBlock
      )
  final case class Config(
      maxSduPayload: Int = 256,
      maxChunkBytes: Int = 1024,
      maxIngressBytes: Int = 131086,
      maxFrames: Int = 1024,
      maxEvents: Int = 256,
      chainLimits: ChainSync.Limits = ChainSync.Limits()
  )
  final case class Status(
      active: Boolean,
      state: ChainSync.State,
      closed: Option[String],
      events: Vector[String],
      epoch: Long,
      ingressBytes: Int,
      peakIngressBytes: Int,
      frames: Int,
      peakFrames: Int
  )
  def resource[F[_]: Async, P](
      transport: ByteTransport[F],
      profile: Profile[P],
      role: ChainSync.Role,
      deadlines: SessionDeadlines[F],
      config: Config = Config()
  ): Resource[F, ConnectionSession[F, P]] =
    val driver = new SingleProtocolConnection.Driver[F, ChainSync.State, ChainSync.Message[P]]:
      def suite = profile.suite
      def version = profile.version
      def protocol = profile.protocol
      def initial = ChainSync.State.Idle
      def handshake = deadlines.handshake
      def valid = config.chainLimits.valid && config.maxIngressBytes <= 1048576 &&
        deadlines.idle > Duration.Zero && deadlines.canAwait > Duration.Zero
      def duration(state: ChainSync.State) = deadlines.duration(state)
      def messageLimit(state: ChainSync.State) = config.chainLimits.maxMessageBytes
      def transition(
          state: ChainSync.State,
          sender: ChainSync.Role,
          message: ChainSync.Message[P]
      ) =
        ChainSync.transition(state, sender, message)
      def encode(state: ChainSync.State, sender: ChainSync.Role, message: ChainSync.Message[P]) =
        ChainSync.encode(state, sender, message, profile.codec, config.chainLimits)
      def decode(state: ChainSync.State, sender: ChainSync.Role, bytes: Bytes) =
        ChainSync.decodePrefix(state, sender, bytes, profile.codec, config.chainLimits)
      override def semantics(offered: Vector[ChainSync.Point], message: ChainSync.Message[P]) =
        message match
          case ChainSync.Message.FindIntersect(points) => Right(points)
          case ChainSync.Message.IntersectFound(point, _) =>
            Either.cond(offered.contains(point), Vector.empty, "intersection was not offered")
          case ChainSync.Message.IntersectNotFound(_) => Right(Vector.empty)
          case _                                      => Right(offered)
    SingleProtocolConnection
      .resource(
        transport,
        driver,
        role,
        SingleProtocolConnection.Config(
          config.maxSduPayload,
          config.maxChunkBytes,
          config.maxIngressBytes,
          config.maxFrames,
          config.maxEvents
        )
      )
      .map(new ConnectionSession(_, profile, role))
