// SPDX-License-Identifier: Apache-2.0
package lab.network

import ChainSync.*
import lab.cbor.Bytes

class FixtureChainModelSuite extends munit.FunSuite:
  private def right[A](e: Either[String, A]): A = e.fold(fail(_), identity)
  private def p(slot: Int, hash: Int): Point =
    Point.Block(right(UInt64.from(slot)), Bytes(Vector.fill(32)(hash.toByte)))
  private val a = p(1, 1)
  private val b = p(2, 2)
  private val c = p(3, 3)
  private val fork = p(2, 4)
  private val history = Vector(Point.Origin, a, b, c)
  private val tip = Tip(c, right(UInt64.from(3)))
  test("first matching intersection uses client order, includes slot AND hash") {
    assertEquals(right(FixtureChainModel.intersect(Vector(a, c), history)), Some(a))
    assertEquals(right(FixtureChainModel.intersect(Vector(c, a), history)), Some(c))
    assertEquals(
      right(FixtureChainModel.intersect(Vector(fork, b, Point.Origin), history)),
      Some(b)
    )
    assertEquals(right(FixtureChainModel.intersect(Vector(fork), history)), None)
    assertEquals(right(FixtureChainModel.intersect(Vector.empty, history)), None)
    assertEquals(
      right(FixtureChainModel.intersect(Vector(Point.Origin), history)),
      Some(Point.Origin)
    )
  }
  test("failed intersection preserves cursor/history; explicit Origin retry succeeds") {
    val initial = right(FixtureChainModel.start(history))
    val offered = right(initial.step(Role.Client, Message.FindIntersect(Vector(fork))))
    val failed = right(offered.step(Role.Server, Message.IntersectNotFound(tip)))
    assertEquals(failed.history, history)
    assertEquals(failed.cursor, c)
    val retry = right(failed.step(Role.Client, Message.FindIntersect(Vector(Point.Origin))))
    val found = right(retry.step(Role.Server, Message.IntersectFound(Point.Origin, tip)))
    assertEquals(found.history, Vector(Point.Origin))
    assertEquals(found.state, State.Idle)
  }
  test("unoffered intersection rejected without changing immutable cursor") {
    val initial = right(FixtureChainModel.start(history))
    val offered = right(initial.step(Role.Client, Message.FindIntersect(Vector(b))))
    assert(offered.step(Role.Server, Message.IntersectFound(a, tip)).isLeft)
    assertEquals(offered.cursor, c)
    assertEquals(offered.state, State.Intersect)
  }
  test("unknown/pruned rollback requires reintersection and never resets Origin") {
    val initial = right(FixtureChainModel.start(Vector(b, c)))
    val next = right(initial.step(Role.Client, Message.RequestNext))
    assert(
      next
        .step(Role.Server, Message.RollBackward(a, tip))
        .swap
        .toOption
        .get
        .contains("reintersection")
    )
    assertEquals(next.history, Vector(b, c))
    assert(next.step(Role.Server, Message.RollBackward(Point.Origin, tip)).isLeft)
  }
  test("new offered intersection establishes anchor; rollback to anchor is legal") {
    val initial = right(FixtureChainModel.start(Vector(Point.Origin, a)))
    val waiting = right(initial.step(Role.Client, Message.FindIntersect(Vector(b))))
    val found = right(waiting.step(Role.Server, Message.IntersectFound(b, tip)))
    assertEquals(found.history, Vector(b))
    val requested = right(found.step(Role.Client, Message.RequestNext))
    assertEquals(
      right(requested.step(Role.Server, Message.RollBackward(b, tip))).history,
      Vector(b)
    )
  }
  test("await permits exactly one delayed forward or backward, and no new request") {
    val initial = right(FixtureChainModel.start(Vector(Point.Origin, a)))
    val awaiting = right(
      right(initial.step(Role.Client, Message.RequestNext)).step(Role.Server, Message.AwaitReply)
    )
    assert(awaiting.step(Role.Server, Message.AwaitReply).isLeft)
    assert(awaiting.step(Role.Client, Message.RequestNext).isLeft)
    assert(awaiting.step(Role.Client, Message.Done).isLeft)
    assertEquals(
      right(awaiting.step(Role.Server, Message.RollForward("B", tip), Some(b))).cursor,
      b
    )
    assertEquals(
      right(awaiting.step(Role.Server, Message.RollBackward(Point.Origin, tip))).cursor,
      Point.Origin
    )
  }
  test("finite fork script has exact trace, final cursor/history and Done state") {
    val result = right(FixtureChainModel.demo())
    assertEquals(result.state, State.Done)
    assertEquals(result.history, Vector(Point.Origin, a, fork, p(3, 5)))
    assertEquals(
      result.trace,
      Vector(
        "find-intersect",
        "intersection-found",
        "request-next",
        "roll-backward",
        "request-next",
        "roll-forward",
        "request-next",
        "await-reply",
        "roll-backward",
        "request-next",
        "roll-forward",
        "request-next",
        "roll-forward",
        "done"
      )
    )
    assert(result.step(Role.Client, Message.Done).isLeft)
    assert(result.step(Role.Client, Message.RequestNext).isLeft)
  }
  test("history/candidates/trace/hash allocations have explicit local bounds") {
    assert(FixtureChainModel.start(Vector.empty).isLeft)
    assert(FixtureChainModel.start(Vector.fill(257)(a)).isLeft)
    assert(FixtureChainModel.start(Vector(a, a)).isLeft)
    assert(FixtureChainModel.start(Vector(Point.Block(UInt64.Zero, Bytes(Vector(1))))).isLeft)
    assert(FixtureChainModel.intersect(Vector.fill(65)(a), history).isLeft)
    val full = right(FixtureChainModel.start((0 until 256).map(i => p(i, 1)).toVector))
    val requested = right(full.step(Role.Client, Message.RequestNext))
    assert(
      requested.step(Role.Server, Message.RollForward("overflow", tip), Some(p(256, 1))).isLeft
    )
    var model = right(FixtureChainModel.start())
    (0 until 512).foreach { _ =>
      model = right(
        right(model.step(Role.Client, Message.RequestNext))
          .step(Role.Server, Message.RollBackward(Point.Origin, Tip.Origin))
      )
    }
    assertEquals(model.trace.size, 1024)
    assert(model.step(Role.Client, Message.Done).isLeft)
  }
