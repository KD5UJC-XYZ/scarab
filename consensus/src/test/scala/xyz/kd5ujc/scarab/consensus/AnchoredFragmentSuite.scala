// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{Fixtures, PreprodDeepTx}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The indexed [[AnchoredFragment]] against a plain model, its anchor and its blocks in a `Vector`,
  * over random sequences of the operations the reference names (issue 810).
  */
class AnchoredFragmentSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private def hash(tag: Int): Bytes = Bytes(Vector.tabulate(32)(i => (tag >> (8 * (i % 4))).toByte))

  private def block(slot: BigInt, tag: Int): Point.Block =
    new Point.Block(ChainSync.UInt64.saturating(slot), hash(tag))

  /** A chain whose slots never decrease: pairs share a slot (a Byron boundary block and the main
    * block after it), and the last blocks are at the saturated slot.
    */
  private val main: Vector[Point.Block] =
    Vector.tabulate(40) { i =>
      val slot =
        if i >= 34 then ChainSync.UInt64.Max + i
        else BigInt(i - (if i % 7 == 1 then 1 else 0))
      block(slot, 1000 + i)
    }

  /** A fork of `main` after its block `at`. */
  private def fork(at: Int, size: Int, salt: Int): Vector[Point.Block] =
    Vector.tabulate(size)(i =>
      block(if at >= 34 then ChainSync.UInt64.Max else BigInt(at + 1 + i), 10000 * salt + i)
    )

  private val forks: Vector[Vector[Point.Block]] =
    Vector(fork(5, 6, 1), fork(20, 4, 2), fork(36, 3, 3))

  private val universe: Vector[Point] =
    Point.Origin +: (main ++ forks.flatten)

  /** The model: the anchor and the blocks, oldest first. */
  final case class Model(anchor: Point, blocks: Vector[Point.Block]):
    def points: Vector[Point] = anchor +: blocks
    def indexOf(p: Point): Int = points.indexOf(p)
    def rollback(p: Point): Option[Model] =
      Option.when(indexOf(p) >= 0)(Model(anchor, blocks.take(indexOf(p))))
    def after(p: Point): Option[Model] =
      Option.when(indexOf(p) >= 0)(Model(p, blocks.drop(indexOf(p))))
    def anchorNewest(n: Int): Model =
      val kept = n.max(0).min(blocks.size)
      Model(points(blocks.size - kept), blocks.takeRight(kept))
    def fragment: AnchoredFragment[Point.Block] = AnchoredFragment.fromOldestFirst(anchor, blocks)

  enum Op:
    case Add(block: Point.Block)
    case Rollback(point: Point)
    case After(point: Point)
    case Split(point: Point)
    case Newest(n: Int)

  private def start: Gen[Model] =
    for
      from <- Gen.choose(-1, 30)
      size <- Gen.choose(0, 10)
    yield
      val anchor = main.lift(from).fold[Point](Point.Origin)(identity)
      Model(anchor, main.slice(from + 1, from + 1 + size))

  /** Where each fork leaves `main`. */
  private val forkedAt: Vector[(Int, Vector[Point.Block])] = Vector(5, 20, 36).zip(forks)

  /** The blocks that can follow `head` on `main` or a fork. */
  private def successors(head: Point): Vector[Point.Block] =
    if head == Point.Origin then main.take(1)
    else
      main.indexWhere(b => (b: Point) == head) match
        case -1 =>
          forks.flatMap(line =>
            line.indexWhere(b => (b: Point) == head) match
              case -1 => Vector.empty
              case at => line.lift(at + 1).toVector
          )
        case at => main.lift(at + 1).toVector ++ forkedAt.collect { case (`at`, line) => line(0) }

  private def op(model: Model): Gen[Op] =
    val point = Gen.oneOf(Gen.oneOf(universe), Gen.oneOf(model.points))
    // any block not on the fragment yet: a fragment's points are distinct
    val fresh = (main ++ forks.flatten).filterNot(b => model.points.contains(b))
    val next = successors(model.points.last).filter(fresh.contains)
    // a generator of an empty collection fails as soon as it is built, so only those that apply
    val adds = Vector(5 -> next, 1 -> fresh).collect {
      case (weight, blocks) if blocks.nonEmpty => weight -> Gen.oneOf(blocks).map(Op.Add(_))
    }
    Gen.frequency(
      (adds ++ Vector(
        2 -> point.map(Op.Rollback(_)),
        2 -> point.map(Op.After(_)),
        1 -> point.map(Op.Split(_)),
        1 -> Gen.choose(-1, 12).map(Op.Newest(_))
      ))*
    )

  /** The model after `o`; an operation on a point off the fragment leaves it. */
  private def applied(m: Model, o: Op): Model = o match
    case Op.Add(b)      => Model(m.anchor, m.blocks :+ b)
    case Op.Rollback(p) => m.rollback(p).getOrElse(m)
    case Op.After(p)    => m.after(p).getOrElse(m)
    case Op.Split(p)    => m.after(p).getOrElse(m)
    case Op.Newest(n)   => m.anchorNewest(n)

  private val scenario: Gen[(Model, Vector[Op])] =
    for
      initial <- start
      steps <- Gen.choose(1, 25)
      ops <- (1 to steps).foldLeft(Gen.const(Vector.empty[Op] -> initial)) { (acc, _) =>
        acc.flatMap((done, m) => op(m).map(o => (done :+ o) -> applied(m, o)))
      }
    yield initial -> ops._1

  private def observe(f: AnchoredFragment[Point.Block], m: Model): Prop =
    (f.anchorPoint ?= m.anchor) &&
      (f.toOldestFirst ?= m.blocks) &&
      (f.length ?= m.blocks.size) &&
      (f.isEmpty ?= m.blocks.isEmpty) &&
      (f.headPoint ?= m.points.last) &&
      (f.pointsNewestFirst.toVector ?= m.points.reverse) &&
      (f ?= m.fragment) &&
      // no key outlives its block: one for each block and the anchor's
      (f.indexSize ?= m.blocks.size + (if m.anchor == Point.Origin then 0 else 1)) &&
      (f.hashCode ?= m.fragment.hashCode) &&
      all(universe.map { p =>
        val i = m.indexOf(p)
        ((f.withinFragmentBounds(p) ?= (i >= 0)) &&
          (f.pointOnFragment(p) ?= (i > 0)) &&
          (f.successorBlock(p) ?= (if i >= 0 then m.blocks.lift(i) else None))) :| s"$p"
      }*) &&
      all((main +: forks).map { line =>
        val other = Model(Point.Origin, line)
        (f.intersectionPoint(other.fragment) ?= other.points.reverse.find(m.points.contains)) &&
        (other.fragment.intersectionPoint(f) ?= m.points.reverse.find(other.points.contains))
      }*)

  /** `o` on the fragment, with its answer checked against the model's. */
  private def step(
      f: AnchoredFragment[Point.Block],
      m: Model,
      o: Op
  ): (AnchoredFragment[Point.Block], Prop) =
    o match
      case Op.Add(b)    => f.addBlock(b) -> passed
      case Op.Newest(n) => f.anchorNewest(n) -> passed
      case Op.Rollback(p) =>
        f.rollback(p).getOrElse(f) -> (f.rollback(p).isDefined ?= m.rollback(p).isDefined)
      case Op.After(p) =>
        f.afterPoint(p).getOrElse(f) -> (f.afterPoint(p).isDefined ?= m.after(p).isDefined)
      case Op.Split(p) =>
        (f.splitAfterPoint(p), m.rollback(p).zip(m.after(p))) match
          case (Some((before, after)), Some((b, _))) => after -> observe(before, b)
          case (None, None)                          => f -> passed
          case (split, model)                        => f -> (falsified :| s"$split, $model")

  property("every operation answers as the model does, and every lookup after it") {
    // no shrinking: a shrunk sequence of operations may add a block the chain already holds
    forAllNoShrink(scenario) { (initial, ops) =>
      ops
        .foldLeft((initial.fragment, initial, observe(initial.fragment, initial))) {
          case ((f, m, seen), o) =>
            val (g, answer) = step(f, m, o)
            val n = applied(m, o)
            (g, n, seen && (answer && observe(g, n)) :| s"$o")
        }
        ._3
    }
  }

  test("a fragment anchored at Origin finds Origin; one anchored at a block does not") {
    val atOrigin = AnchoredFragment.fromOldestFirst[Point.Block](Point.Origin, main.take(3))
    assert(atOrigin.withinFragmentBounds(Point.Origin) && !atOrigin.pointOnFragment(Point.Origin))
    assertEquals(atOrigin.successorBlock(Point.Origin), main.headOption)
    val atBlock = AnchoredFragment.fromOldestFirst[Point.Block](main(0), main.slice(1, 3))
    assert(!atBlock.withinFragmentBounds(Point.Origin))
    assertEquals(atBlock.rollback(Point.Origin), None)
  }

  test("a point is found by its slot and hash: the same hash at another slot is not on it") {
    val f = AnchoredFragment.fromOldestFirst[Point.Block](Point.Origin, main.take(5))
    val moved = new Point.Block(ChainSync.UInt64.saturating(99), main(2).hash)
    assert(!f.withinFragmentBounds(moved))
    assertEquals(f.rollback(moved), None)
  }

  test("a fragment of headers names each header's point") {
    val chain = Fixtures.chain(6)
    val f = AnchoredFragment.fromOldestFirst(Point.Origin, chain)
    assertEquals(f.headPoint, chain.last.point)
    assertEquals(f.rollback(chain(2).point).map(_.toOldestFirst), Some(chain.take(3)))
    assertEquals(f.afterPoint(chain(2).point).map(_.anchorPoint), Some(chain(2).point))
  }

  test("a long fragment is built, grown, trimmed and rolled back on a small stack (STK-3)") {
    val n = 200000
    val blocks = Vector.tabulate(n)(i => block(BigInt(i), i))
    PreprodDeepTx.onSmallStack {
      val built = AnchoredFragment.fromOldestFirst[Point.Block](Point.Origin, blocks.take(n / 2))
      val grown = blocks.drop(n / 2).foldLeft(built)(_.addBlock(_))
      val trimmed = grown.afterPoint(blocks(n / 4)).getOrElse(fail("anchor"))
      val back = trimmed.rollback(blocks(n / 2)).getOrElse(fail("rollback"))
      val split = grown.splitAfterPoint(blocks(n / 3)).getOrElse(fail("split"))
      assertEquals(grown, AnchoredFragment.fromOldestFirst(Point.Origin, blocks))
      assertEquals(back.length, n / 4)
      assertEquals(split._1.length + split._2.length, n)
      assertEquals(trimmed.intersectionPoint(grown), Some(blocks.last))
      assertEquals(grown.anchorNewest(10).anchorPoint, blocks(n - 11))
      assert(grown.hashCode == AnchoredFragment.fromOldestFirst(Point.Origin, blocks).hashCode)
    }
  }
