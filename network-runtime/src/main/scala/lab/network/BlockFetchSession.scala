// SPDX-License-Identifier: Apache-2.0
package lab.network

import cats.effect.{Async, Resource}
import cats.syntax.all.*
import lab.cbor.Bytes
import scala.concurrent.duration.*

/** Strict single-range NtN14 client; transport capability does not establish relay
  * interoperability. Shares the connection owner with ChainSync, never its reader. One application
  * protocol is fixed at construction; negotiation grants access to it.
  */
final class BlockFetchSession[F[_]: Async] private (
    owner: SingleProtocolConnection[F, BlockFetch.State, BlockFetch.Message[
      CardanoBlockFetch.RawNtNBlock
    ]],
    config: BlockFetchSession.Config
):
  def negotiate(data: Handshake.Data): F[Unit] =
    Async[F].raiseWhen(data.query)(
      new IllegalArgumentException("query is not an application negotiation")
    ) *>
      owner.negotiate(Vector(14 -> data)).flatMap {
        case Handshake.Result.Negotiated(14, actual) if actual == data => Async[F].unit
        case _ =>
          owner.close *> Async[F].raiseError(
            new IllegalStateException("strict NtN14 negotiation did not match descriptor")
          )
      }
  def request(range: CardanoBlockFetch.InclusiveRange): F[Unit] = owner.sendOnce(range.request)
  def receive: F[BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]] = owner.receive
  def finish: F[Unit] = owner.requireDrained *> owner.send(BlockFetch.Message.ClientDone)
  def status: F[SingleProtocolConnection.Status[BlockFetch.State]] = owner.status
  def close: F[Unit] = owner.close

  /** Encompasses negotiation and the entire batch, never reset by individual blocks. */
  def bounded[A](acquire: F[A]): F[A] =
    Async[F].timeoutTo(
      acquire,
      config.times.wholeRequest,
      owner.close *> Async[F].raiseError(
        new SingleProtocolConnection.WholeDeadlineExceeded
      )
    )

object BlockFetchSession:
  final case class Config(
      maxRawBlockBytes: Int = 1048576,
      maxSduPayload: Int = 65535,
      maxChunkBytes: Int = 65543,
      maxFrames: Int = 1024,
      maxEvents: Int = 32,
      maxWireBytes: Long = 5L * 1024 * 1024,
      maxTotalFrames: Long = 16384,
      handshake: FiniteDuration = 10.seconds,
      times: BlockFetch.TimeLimits = BlockFetch.TimeLimits()
  )
  def resource[F[_]: Async](
      transport: ByteTransport[F],
      config: Config = Config()
  ): Resource[F, BlockFetchSession[F]] =
    val F = Async[F]
    val limits = BlockFetch.Limits(
      streamingMessageBytes = config.maxRawBlockBytes + 9,
      maxStringBytes = config.maxRawBlockBytes
    )
    val codec = CardanoBlockFetch.payloadCodec(CardanoBlockFetch.RawLimits(config.maxRawBlockBytes))
    val driver = new SingleProtocolConnection.Driver[F, BlockFetch.State, BlockFetch.Message[
      CardanoBlockFetch.RawNtNBlock
    ]]:
      def suite = Handshake.Suite.NodeToNode
      def version = 14
      def protocol = 3
      def initial = BlockFetch.State.Idle
      def handshake = config.handshake
      def valid = config.maxRawBlockBytes > 0 && config.maxRawBlockBytes <= 1048576 &&
        config.times.valid && config.maxTotalFrames <= 65536 && config.maxWireBytes <= 5L * 1024 * 1024 && limits.valid
      def duration(state: BlockFetch.State): F[Option[FiniteDuration]] = F.pure(state match
        case BlockFetch.State.Idle      => config.times.idle
        case BlockFetch.State.Busy      => Some(config.times.busy)
        case BlockFetch.State.Streaming => Some(config.times.streaming)
        case BlockFetch.State.Done      => None)
      def messageLimit(state: BlockFetch.State) = limits.messageBytes(state)
      private def role(sender: ChainSync.Role): BlockFetch.Role = sender match
        case ChainSync.Role.Client => BlockFetch.Role.Client
        case ChainSync.Role.Server => BlockFetch.Role.Server
      def transition(
          state: BlockFetch.State,
          sender: ChainSync.Role,
          message: BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]
      ) =
        BlockFetch.transition(state, role(sender), message)
      def encode(
          state: BlockFetch.State,
          sender: ChainSync.Role,
          message: BlockFetch.Message[CardanoBlockFetch.RawNtNBlock]
      ) =
        BlockFetch.encode(state, role(sender), message, codec, limits)
      def decode(state: BlockFetch.State, sender: ChainSync.Role, bytes: Bytes) =
        BlockFetch.decodePrefix(state, role(sender), bytes, codec, limits)
    // One raw block + nine CBOR bytes + one bounded transport chunk. Not a heap-size guarantee.
    SingleProtocolConnection
      .resource(
        transport,
        driver,
        ChainSync.Role.Client,
        SingleProtocolConnection.Config(
          config.maxSduPayload,
          config.maxChunkBytes,
          config.maxRawBlockBytes + 9 + config.maxChunkBytes,
          config.maxFrames,
          config.maxEvents,
          config.maxWireBytes,
          config.maxTotalFrames,
          Some(config.times.wholeRequest)
        )
      )
      .map(new BlockFetchSession(_, config))
