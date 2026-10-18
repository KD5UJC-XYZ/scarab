// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{Point, UInt64}
import ProtocolWire.{bad, get, attempt}

/** Cardano NtN serialized-block adapter. No NtC profile, inner CBOR inspection, block identity
  * derivation, consensus checks or ledger validation.
  */
object CardanoBlockFetch:
  val DefaultNtNVersion: Int = 14
  val CardanoCodecVersion: Int = 2

  final class SpecificPoint private (val slot: UInt64, val hash: Bytes):
    def point: Point = Point.Block(slot, hash)
    override def equals(other: Any): Boolean = other match
      case p: SpecificPoint => slot == p.slot && hash == p.hash
      case _                => false
    override def hashCode: Int = (slot, hash).hashCode
  object SpecificPoint:
    def from(point: Point): Either[ProtocolError, SpecificPoint] = point match
      case Point.Origin => Left(ProtocolError.OriginIsNotAnAddressableCardanoBlock)
      case Point.Block(slot, hash) =>
        if hash.size != 32 then Left(ProtocolError.CardanoPointHashMustContain32Bytes(hash.size))
        else Right(new SpecificPoint(slot, hash))

  /** Endpoints are inclusive. Equal endpoints request one block. Slot ordering alone cannot prove
    * chain order: Byron EBB and regular blocks can share a slot.
    */
  final case class InclusiveRange(from: SpecificPoint, to: SpecificPoint):
    def request: BlockFetch.Message[Nothing] = BlockFetch.Message.RequestRange(from.point, to.point)
  object InclusiveRange:
    def fromPoints(from: Point, to: Point): Either[ProtocolError, InclusiveRange] =
      for
        first <- SpecificPoint.from(from)
        last <- SpecificPoint.from(to)
      yield InclusiveRange(first, last)
    def single(point: SpecificPoint): InclusiveRange = InclusiveRange(point, point)

  /** Owned immutable original byte-string contents. The inner bytes may even be invalid CBOR. This
    * type deliberately does not expose a validated-block conversion.
    */
  final class RawNtNBlock private (val bytes: Bytes):
    override def equals(other: Any): Boolean = other match
      case block: RawNtNBlock => bytes == block.bytes
      case _                  => false
    override def hashCode: Int = bytes.hashCode
  object RawNtNBlock:
    def from(
        bytes: Bytes,
        maxRawBytes: Int = RawLimits().maxRawBytes
    ): Either[ProtocolError, RawNtNBlock] =
      if maxRawBytes < 0 || maxRawBytes > 2499991 then Left(ProtocolError.InvalidRawBlockLimit(maxRawBytes))
      else if bytes.size > maxRawBytes then Left(ProtocolError.RawBlockByteLimitExceeded(bytes.size, maxRawBytes))
      else Right(new RawNtNBlock(bytes))

  /** Default leaves room for the nine-byte canonical Block message wrapper. */
  final case class RawLimits(maxRawBytes: Int = 2499991):
    def valid: Boolean = maxRawBytes >= 0 && maxRawBytes <= 2499991

  def payloadCodec(limits: RawLimits = RawLimits()): BlockFetch.PayloadCodec[RawNtNBlock] =
    new BlockFetch.PayloadCodec[RawNtNBlock]:
      private def bounds(using ProtocolWire.Scope): ProtocolWire.Limits =
        if !limits.valid then bad(ProtocolError.InvalidRawBlockLimit(limits.maxRawBytes))
        ProtocolWire.Limits(2500000, 2500000, 64)
      def encode(block: RawNtNBlock): Either[ProtocolError, Bytes] = attempt {
        val writer = new ProtocolWire.Writer(bounds)
        get(RawNtNBlock.from(block.bytes, limits.maxRawBytes))
        writer.head(6, 24)
        writer.head(2, block.bytes.size)
        writer.raw(block.bytes)
        writer.result
      }
      def decode(bytes: Bytes): Either[ProtocolError, RawNtNBlock] = attempt {
        val reader = new ProtocolWire.Reader(bytes, bounds)
        val result = readFrom(reader)
        if reader.position != bytes.size then bad(ProtocolError.TrailingSerializedBlockBytes)
        result
      }
      override private[protocol] def readFrom(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): RawNtNBlock =
        if !limits.valid then bad(ProtocolError.InvalidRawBlockLimit(limits.maxRawBytes))
        if reader.tag() != 24 then bad(ProtocolError.ExpectedSerializedBlockTag24)
        // Reader checks the declared length before checking body availability or slicing.
        // Thus a huge incomplete advertised bstr is Failed, not NeedMore.
        get(RawNtNBlock.from(reader.bytes(limits.maxRawBytes), limits.maxRawBytes))

  /** Decode requests through this boundary before a Cardano server/API accepts them. Generic
    * BlockFetch intentionally retains polymorphic point compatibility.
    */
  def checkedRange(message: BlockFetch.Message[?]): Either[ProtocolError, InclusiveRange] = message match
    case BlockFetch.Message.RequestRange(from, to) => InclusiveRange.fromPoints(from, to)
    case _                                         => Left(ProtocolError.ExpectedRequestRange)

  /** Selected ordered points, supplied by an independent selection/identity layer. Slot
    * monotonicity is necessary but does not establish parent linkage.
    */
  final class FetchPlan private (
      val expected: Vector[SpecificPoint],
      val limits: BlockFetch.RequestLimits
  ):
    def range: InclusiveRange = InclusiveRange(expected.head, expected.last)
  object FetchPlan:
    def from(
        expected: Vector[SpecificPoint],
        limits: BlockFetch.RequestLimits = BlockFetch.RequestLimits()
    ): Either[ProtocolError, FetchPlan] =
      if !limits.valid then Left(ProtocolError.InvalidRequestLimits)
      else if expected.isEmpty || expected.size > limits.maxBlocks then
        Left(ProtocolError.ExpectedPointCountOutsideRequestLimits(expected.size, limits.maxBlocks))
      else if expected.distinct.size != expected.size then Left(ProtocolError.DuplicateExpectedPoints)
      else if expected.zip(expected.drop(1)).exists((a, b) => a.slot.value > b.slot.value) then
        Left(ProtocolError.ReversedExpectedSlotOrder)
      else Right(new FetchPlan(expected, limits))

  enum BatchResult:
    case Pending

    /** Exact count/order relative to the supplied identity function only. This is not a ledger,
      * header, consensus or parent-link validation certificate.
      */
    case Complete(blocks: Vector[RawNtNBlock])
    case Unavailable

  /** Pure bounded one-request model. No socket, timer or mux ownership. A caller must treat Left as
    * terminal for that request; incomplete prefixes never yield Complete. The identity function
    * must derive points independently from original block bytes.
    */
  final class Batch private (
      val plan: FetchPlan,
      val state: BlockFetch.State,
      val result: BatchResult,
      private val received: BlockFetch.Received,
      private val blocks: Vector[RawNtNBlock]
  ):
    def accept(message: BlockFetch.Message[RawNtNBlock])(
        identify: RawNtNBlock => Either[ProtocolError, SpecificPoint]
    ): Either[ProtocolError, Batch] =
      import BlockFetch.{Message, Role}
      if result != BatchResult.Pending then Left(ProtocolError.RequestAlreadyCompleted)
      else
        BlockFetch.transition(state, Role.Server, message).flatMap { next =>
          message match
            case Message.StartBatch => Right(new Batch(plan, next, result, received, blocks))
            case Message.NoBlocks =>
              Right(new Batch(plan, next, BatchResult.Unavailable, received, blocks))
            case Message.Block(block) =>
              if blocks.size >= plan.expected.size then Left(ProtocolError.ExtraBlockInRequestedBatch)
              else
                for
                  count <- BlockFetch.Received.add(received, block.bytes.size, plan.limits)
                  point <- identify(block)
                  _ <- Either.cond(
                    point == plan.expected(blocks.size),
                    (),
                    ProtocolError.BlockPointOrderMismatch
                  )
                yield new Batch(plan, next, result, count, blocks :+ block)
            case Message.BatchDone =>
              if blocks.size != plan.expected.size then
                Left(ProtocolError.EarlyBatchDoneIncompleteRequestedRange)
              else Right(new Batch(plan, next, BatchResult.Complete(blocks), received, blocks))
            case _ => Left(ProtocolError.UnexpectedClientMessageInServerBatch)
        }
    def endOfInput: Either[ProtocolError, BatchResult] = result match
      case BatchResult.Pending => Left(ProtocolError.IncompleteBlockFetchRequestAtEndOfInput)
      case value               => Right(value)
  object Batch:
    /** The returned Busy state models a RequestRange already accepted by the pure model; it does
      * not assert that any transport sent it.
      */
    def begin(plan: FetchPlan): Batch =
      new Batch(
        plan,
        BlockFetch.State.Busy,
        BatchResult.Pending,
        BlockFetch.Received.Empty,
        Vector.empty
      )
