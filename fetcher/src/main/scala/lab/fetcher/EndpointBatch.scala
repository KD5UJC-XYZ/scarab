// SPDX-License-Identifier: Apache-2.0
package lab.fetcher

import lab.chain.CardanoBlockIndex
import lab.network.{BlockFetch, CardanoBlockFetch, ChainSync}
import CardanoBlockFetch.{InclusiveRange, RawNtNBlock, SpecificPoint}

/** A bounded, pure, structural verifier for one inclusive post-Byron (Shelley–Conway) range. It
  * hashes original header bytes, checks parent links and waits for BatchDone before exposing a
  * result. Neither the asserted anchor slot, ledger/header validity, nor chain authenticity is
  * proved.
  */
object EndpointBatch:
  final case class Error(code: Int, message: String)

  final class Limits private (
      val maxBlocks: Int,
      val maxRawBytes: Long,
      val maxBlockBytes: Int
  ):
    def requestLimits: BlockFetch.RequestLimits =
      BlockFetch.RequestLimits(maxBlocks, maxRawBytes)
  object Limits:
    val Default: Limits = new Limits(4, 4194304L, 1048576)
    def checked(
        maxBlocks: Int = 4,
        maxRawBytes: Long = 4194304L,
        maxBlockBytes: Int = 1048576
    ): Either[Error, Limits] =
      Either.cond(
        maxBlocks > 0 && maxBlocks <= 4 && maxRawBytes > 0 && maxRawBytes <= 4194304L &&
          maxBlockBytes > 0 && maxBlockBytes <= 1048576,
        new Limits(maxBlocks, maxRawBytes, maxBlockBytes),
        Error(2, "invalid endpoint batch limits")
      )

  final class Spec private (
      val anchor: Point,
      val range: InclusiveRange,
      val expectedCount: Option[Int],
      val expectedPoints: Option[Vector[SpecificPoint]],
      val limits: Limits
  ):
    def first: SpecificPoint = range.from
    def last: SpecificPoint = range.to
  object Spec:
    def checked(
        anchor: Point,
        range: InclusiveRange,
        expectedCount: Option[Int] = None,
        expectedPoints: Option[Vector[SpecificPoint]] = None,
        limits: Limits = Limits.Default
    ): Either[Error, Spec] =
      val first = range.from
      val last = range.to
      val single = first == last
      if first.slot.value <= anchor.slot || last.slot.value < first.slot.value ||
        (first.slot == last.slot && !single)
      then Left(Error(2, "range must follow concrete anchor and have increasing endpoint slots"))
      else if expectedCount.exists(n =>
          n <= 0 || n > limits.maxBlocks ||
            (single && n != 1) || (!single && n < 2)
        )
      then Left(Error(2, "exact expected count is incompatible with range or budget"))
      else if !single && limits.maxBlocks < 2 then
        Left(Error(2, "distinct endpoints require at least two blocks"))
      else if expectedPoints.exists { points =>
          points.isEmpty || points.size > limits.maxBlocks || points.head != first ||
          points.last != last || expectedCount.exists(_ != points.size) ||
          points.zip(points.drop(1)).exists((a, b) => a.slot.value >= b.slot.value) ||
          points.map(_.hash).distinct.size != points.size
        }
      then Left(Error(2, "invalid independent expected point list"))
      else Right(new Spec(anchor, range, expectedCount, expectedPoints, limits))

  enum Result:
    case Pending
    case Complete(blocks: Vector[RawNtNBlock])
    case Unavailable

  /** Left is terminal for this acquisition. No caller-supplied identity function can bless bytes.
    */
  final class Batch private[EndpointBatch] (
      val spec: Spec,
      val state: BlockFetch.State,
      val result: Result,
      val received: BlockFetch.Received,
      private val blocks: Vector[RawNtNBlock],
      private val points: Vector[SpecificPoint]
  ):
    private def reachedLast: Boolean = points.lastOption.contains(spec.last)
    def accept(message: BlockFetch.Message[RawNtNBlock]): Either[Error, Batch] =
      import BlockFetch.{Message, Role}
      if result != Result.Pending then Left(Error(4, "request already completed"))
      else
        BlockFetch.transition(state, Role.Server, message).left.map(Error(4, _)).flatMap { next =>
          message match
            case Message.StartBatch =>
              Right(new Batch(spec, next, result, received, blocks, points))
            case Message.NoBlocks =>
              Right(new Batch(spec, next, Result.Unavailable, received, blocks, points))
            case Message.Block(block) =>
              if reachedLast then Left(Error(5, "extra block after requested endpoint"))
              else if spec.expectedCount.exists(received.blocks >= _) ||
                spec.expectedPoints.exists(received.blocks >= _.size)
              then Left(Error(5, "extra block beyond exact expected count"))
              else if block.bytes.size > spec.limits.maxBlockBytes then
                Left(Error(3, "raw block byte limit exceeded"))
              else
                for
                  count <- BlockFetch.Received
                    .add(received, block.bytes.size, spec.limits.requestLimits)
                    .left
                    .map(Error(3, _))
                  index <- CardanoBlockIndex
                    .inspect(block.bytes, spec.limits.maxBlockBytes)
                    .left
                    .map(Error(5, _))
                  slot <- ChainSync.UInt64.from(index.slot).left.map(Error(5, _))
                  point <- SpecificPoint
                    .from(ChainSync.Point.Block(slot, index.headerHash))
                    .left
                    .map(Error(5, _))
                  _ <- verifyPoint(point, index.parentHash.hex)
                yield new Batch(spec, next, result, count, blocks :+ block, points :+ point)
            case Message.BatchDone =>
              if !reachedLast || spec.expectedCount.exists(_ != received.blocks) ||
                spec.expectedPoints.exists(_.size != received.blocks)
              then Left(Error(3, "early BatchDone: incomplete requested range"))
              else Right(new Batch(spec, next, Result.Complete(blocks), received, blocks, points))
            case _ => Left(Error(4, "unexpected client message in server batch"))
        }

    private def verifyPoint(point: SpecificPoint, parent: String): Either[Error, Unit] =
      val previous = points.lastOption
      if points.isEmpty && point != spec.first then Left(Error(5, "first point mismatch"))
      else if parent != previous.fold(spec.anchor.hash)(_.hash.hex) then
        Left(Error(5, "parent hash mismatch"))
      else if point.slot.value <= previous.fold(spec.anchor.slot)(_.slot.value) then
        Left(Error(5, "slots must strictly increase in supported range"))
      else if points.exists(_.hash == point.hash) then Left(Error(5, "duplicate block hash"))
      else if point.slot.value > spec.last.slot.value ||
        (point.slot == spec.last.slot && point != spec.last)
      then Left(Error(5, "requested endpoint mismatch or overshoot"))
      else if spec.expectedPoints.exists(_(points.size) != point) then
        Left(Error(5, "independent expected point/order mismatch"))
      else Right(())

    def endOfInput: Either[Error, Result] = result match
      case Result.Pending => Left(Error(3, "EOF before BatchDone: incomplete requested range"))
      case value          => Right(value)

  /** Models an already accepted RequestRange, without asserting transport delivery. */
  def begin(spec: Spec): Batch =
    new Batch(
      spec,
      BlockFetch.State.Busy,
      Result.Pending,
      BlockFetch.Received.Empty,
      Vector.empty,
      Vector.empty
    )
