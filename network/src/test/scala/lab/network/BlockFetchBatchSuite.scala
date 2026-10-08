// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import CardanoBlockFetch.*
import BlockFetch.Message

class BlockFetchBatchSuite extends munit.FunSuite:
  private def right[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def point(slot: Int, hash: Int): SpecificPoint = right(
    SpecificPoint.from(
      ChainSync.Point.Block(right(ChainSync.UInt64.from(slot)), Bytes(Vector.fill(32)(hash.toByte)))
    )
  )
  private val p = point(0, 1)
  private val q = point(0, 2)
  private val raw = right(RawNtNBlock.from(Bytes(Vector(0x80.toByte))))
  private def plan(points: SpecificPoint*): FetchPlan = right(FetchPlan.from(points.toVector))
  private def next(b: Batch, message: BlockFetch.Message[RawNtNBlock], p: SpecificPoint): Batch =
    right(b.accept(message)(_ => Right(p)))
  test("inclusive single requires exact identity and BatchDone") {
    val start = Batch.begin(plan(p))
    assertEquals(start.plan.range.from, start.plan.range.to)
    assert(start.endOfInput.isLeft)
    val stream = next(start, Message.StartBatch, p)
    assert(stream.accept(Message.BatchDone)(_ => Right(p)).isLeft)
    val one = next(stream, Message.Block(raw), p)
    assertEquals(one.result, BatchResult.Pending)
    assert(one.endOfInput.isLeft)
    assert(one.accept(Message.Block(raw))(_ => Right(p)).isLeft)
    val complete = next(one, Message.BatchDone, p)
    assertEquals(complete.endOfInput, Right(BatchResult.Complete(Vector(raw))))
    assert(complete.accept(Message.StartBatch)(_ => Right(p)).isLeft)
  }
  test("same slot distinct points remain ordered; duplicates wrong order and short batch fail") {
    val stream = next(Batch.begin(plan(p, q)), Message.StartBatch, p)
    assert(stream.accept(Message.Block(raw))(_ => Right(q)).isLeft)
    assert(stream.accept(Message.Block(raw))(_ => Left("identity extraction failed")).isLeft)
    val one = next(stream, Message.Block(raw), p)
    assert(one.accept(Message.Block(raw))(_ => Right(p)).isLeft)
    assert(one.accept(Message.BatchDone)(_ => Right(q)).isLeft)
    val two = next(one, Message.Block(raw), q)
    assert(next(two, Message.BatchDone, q).endOfInput.isRight)
    assert(FetchPlan.from(Vector(p, p)).isLeft)
    assert(FetchPlan.from(Vector(point(2, 1), point(1, 2))).isLeft)
    assert(FetchPlan.from(Vector.empty).isLeft)
    assert(FetchPlan.from(Vector(p, q), BlockFetch.RequestLimits(maxBlocks = 1)).isLeft)
  }
  test("NoBlocks is unavailable only before streaming and terminal for this request") {
    val begin = Batch.begin(plan(p))
    assert(begin.accept(Message.Block(raw))(_ => Right(p)).isLeft)
    val unavailable = next(begin, Message.NoBlocks, p)
    assertEquals(unavailable.endOfInput, Right(BatchResult.Unavailable))
    assert(unavailable.accept(Message.StartBatch)(_ => Right(p)).isLeft)
    val stream = next(begin, Message.StartBatch, p)
    assert(stream.accept(Message.NoBlocks)(_ => Right(p)).isLeft)
  }
  test("aggregate raw-byte cap applies across a batch before completion") {
    val limited = right(FetchPlan.from(Vector(p, q), BlockFetch.RequestLimits(2, 1)))
    val stream = next(Batch.begin(limited), Message.StartBatch, p)
    val one = next(stream, Message.Block(raw), p)
    assert(one.accept(Message.Block(raw))(_ => Right(q)).isLeft)
  }
  test("shared scanner preserves ChainSync small-message and independent-field policies") {
    val limits = ChainSync.Limits(maxMessageBytes = 2, maxStringBytes = 65535, maxHashBytes = 65535)
    assert(ChainSync.encodePoint(ChainSync.Point.Origin, limits).isRight)
    assert(
      ChainSync
        .encodePoint(ChainSync.Point.Origin, ChainSync.Limits(maxMessageBytes = 65536))
        .isLeft
    )
    assert(ChainSync.validateItem(Bytes(Vector.fill(65536)(0.toByte))).isLeft)
  }

  test("impossible state-specific outer arity fails before missing tag") {
    for (state, role, hex) <- Vector(
        (BlockFetch.State.Idle, BlockFetch.Role.Client, "80"),
        (BlockFetch.State.Busy, BlockFetch.Role.Server, "82"),
        (BlockFetch.State.Streaming, BlockFetch.Role.Server, "83")
      )
    do
      assert(
        BlockFetch
          .decodePrefix(state, role, right(Bytes.fromHex(hex)), payloadCodec())
          .isInstanceOf[ChainSync.DecodeResult.Failed[?]]
      )
  }
