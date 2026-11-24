// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.consensus

import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header, PreprodDeepTx, Unsigned}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.Point
import HeaderChain.{Entry, Known, TipView}

/** The indexed [[HeaderChain]] answers every public method as the chain it replaces does
  * ([[HeaderChainModel]], the code of #1406), over random sequences of extensions, rollbacks,
  * trims, prunes, maps and copies (issue 810). The chains hold unknown states, start at Origin or
  * after a local prefix (Origin first in it, or an Origin base after it), and pass Byron boundary
  * and main blocks that share a slot and headers at the saturated slot. A chain holds a block once,
  * so their points are distinct.
  */
class HeaderChainModelSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private def byron(boundary: Boolean, epoch: Int, inEpoch: Int, n: Int, prev: Option[Bytes]) =
    val raw = Bytes(s"byron-$boundary-$epoch-$inEpoch-$n".getBytes.toVector)
    Header.Byron(
      raw,
      Blake2b.hash256.hash(raw),
      boundary,
      epoch,
      inEpoch,
      10,
      n,
      prev,
      Unsigned.from[Unsigned.Width.W32](1).getOrElse(fail("size hint"))
    )

  /** Two Byron epochs, each a boundary block and a main block in its first slot, then Shelley-based
    * headers, the last of them beyond the largest slot a point holds.
    */
  private val main: Vector[Header] =
    val byronPart = (0 until 2).foldLeft(Vector.empty[Header]) { (acc, epoch) =>
      val ebb = byron(boundary = true, epoch, 0, acc.size, acc.lastOption.map(_.hash))
      val first = byron(boundary = false, epoch, 0, acc.size + 1, Some(ebb.hash))
      val second = byron(boundary = false, epoch, 1, acc.size + 2, Some(first.hash))
      acc ++ Vector(ebb, first, second)
    }
    val shelley = (0 until 22).foldLeft(byronPart) { (acc, i) =>
      val slot = if i >= 17 then ChainSync.UInt64.Max + i else BigInt(30 + i)
      acc :+ Fixtures.header(acc.size, slot, acc.lastOption.map(_.hash))
    }
    shelley

  /** Forks of `main` after its header at each index. */
  private val forks: Vector[(Int, Vector[Header])] =
    Vector(4, 12, 25).zipWithIndex.map((at, salt) =>
      at -> Fixtures.chain(4, salt = salt + 1, from = main.lift(at))
    )

  private val headers: Vector[Header] = main ++ forks.flatMap(_._2)
  private val universe: Vector[Point] = Point.Origin +: headers.map(_.point)

  /** Our chain as selection sees it, anchored on `main` and following `main` or a fork. */
  private val locals: Vector[ChainSelection.Local] =
    def local(anchor: Option[Header], blocks: Vector[Header]) =
      ChainSelection.Local(
        anchor.fold[Point](Point.Origin)(_.point),
        anchor.fold(SelectView(-1, 0, None, 0, None))(SelectView.of),
        blocks.map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
      )
    Vector(
      local(None, main.take(3)),
      local(None, Vector.empty),
      local(main.lift(30), Vector.empty)
    ) ++
      Vector(0, 4, 10, 20).map(at => local(main.lift(at), main.slice(at + 1, at + 9))) ++
      forks.map((at, line) => local(main.lift(at - 2), main.slice(at - 1, at + 1) ++ line))

  private val state: Gen[Option[Int]] = Gen.option(Gen.choose(0, 1000))

  private def view(h: Header): TipView =
    TipView(SelectView.of(h), ChainSelection.tiebreakerFor(h.era))

  private def known(h: Header): Gen[Known[Int]] =
    for
      s <- state
      v <- Gen.option(Gen.const(view(h)))
    yield Known(h.point, s, v)

  /** The arguments of a chain as the gate builds it, or as a test may: a prefix of `main`, Origin
    * first in it or not, a base on `main` or Origin (after the prefix, when it has one), and the
    * headers after the base.
    */
  private val start: Gen[(Point, Option[Int], Vector[Entry[Int]], Vector[Known[Int]])] =
    for
      prefixSize <- Gen.choose(0, 8)
      originFirst <- Gen.oneOf(true, false)
      originBase <- Gen.frequency(4 -> false, 1 -> true)
      entrySize <- Gen.choose(0, 10)
      prefix <- Gen.sequence[Vector[Known[Int]], Known[Int]](main.take(prefixSize).map(known))
      origin <- state
      baseState <- state
      states <- Gen.listOfN(entrySize, state)
    yield
      val local = if originFirst then Known[Int](Point.Origin, origin) +: prefix else prefix
      if originBase then
        (Point.Origin, baseState, Vector.empty, if originFirst then prefix else local)
      else
        (
          main(prefixSize).point,
          baseState,
          main.slice(prefixSize + 1, prefixSize + 1 + entrySize).zip(states).map(Entry(_, _)),
          local
        )

  enum Op:
    case Extend(header: Header, state: Option[Int])
    case Rollback(point: Point)
    case Trim(point: Point)
    case Prune(max: Int)
    case Map
    case ForgetBaseState
    case ForgetBaseView
    case KeepEntries(n: Int)
    case DropPrefix(n: Int)

  private def op(model: HeaderChainModel[Int]): Gen[Op] =
    val points = model.points
    // the headers that can follow the tip, and others that are not on the chain yet
    val fresh = headers.filterNot(h => points.contains(h.point))
    val next = fresh.filter(model.canExtend)
    val point = Gen.oneOf(Gen.oneOf(universe), Gen.oneOf(points))
    // a generator of an empty collection fails as soon as it is built, so only those that apply
    val extensions = Vector(6 -> next, 1 -> fresh).collect {
      case (weight, candidates) if candidates.nonEmpty =>
        weight -> Gen.zip(Gen.oneOf(candidates), state).map(Op.Extend(_, _))
    }
    Gen.frequency(
      (extensions ++ Vector(
        2 -> point.map(Op.Rollback(_)),
        2 -> point.map(Op.Trim(_)),
        1 -> Gen.oneOf(Gen.choose(-3, 40), Gen.oneOf(Int.MinValue, Int.MaxValue)).map(Op.Prune(_)),
        1 -> Gen.choose(0, 12).map(Op.KeepEntries(_)),
        1 -> Gen.choose(0, 9).map(Op.DropPrefix(_)),
        1 -> Gen.const(Op.Map),
        1 -> Gen.oneOf(Op.ForgetBaseState, Op.ForgetBaseView)
      ))*
    )

  /** `o` on both chains, with the answers it returns compared. */
  private def step(
      hc: HeaderChain[Int],
      model: HeaderChainModel[Int],
      o: Op
  ): (HeaderChain[Int], HeaderChainModel[Int], Prop) = o match
    case Op.Extend(h, s) =>
      (hc.extend(h, s), model.extend(h, s)) match
        case (Right(c), Right(m)) => (c, m, passed)
        case (Left(e), Left(f))   => (hc, model, e ?= f)
        case (c, m)               => (hc, model, falsified :| s"extend: $c, $m")
    case Op.Rollback(p) =>
      (hc.rollback(p), model.rollback(p)) match
        case (Some(c), Some(m)) => (c, m, passed)
        case (None, None)       => (hc, model, passed)
        case (c, m)             => (hc, model, falsified :| s"rollback: $c, $m")
    case Op.Trim(p)         => (hc.trimmedTo(p), model.trimmedTo(p), passed)
    case Op.Prune(max)      => (hc.pruned(max), model.pruned(max), passed)
    case Op.Map             => (hc.map(_ + 1), model.map(_ + 1), passed)
    case Op.ForgetBaseState => (hc.copy(baseState = None), model.copy(baseState = None), passed)
    case Op.ForgetBaseView  => (hc.copy(baseView = None), model.copy(baseView = None), passed)
    case Op.KeepEntries(n) =>
      (hc.copy(entries = hc.entries.take(n)), model.copy(entries = model.entries.take(n)), passed)
    case Op.DropPrefix(n) =>
      (hc.copy(prefix = hc.prefix.drop(n)), model.copy(prefix = model.prefix.drop(n)), passed)

  /** Every public method of the chain against the model's answer. */
  private def observe(hc: HeaderChain[Int], m: HeaderChainModel[Int]): Prop =
    val points = m.points
    (hc.base ?= m.base) &&
    (hc.baseState ?= m.baseState) &&
    (hc.entries ?= m.entries) &&
    (hc.prefix ?= m.prefix) &&
    (hc.baseView ?= m.baseView) &&
    (hc.points ?= points) &&
    (hc.nonEmptyPoints ?= m.nonEmptyPoints) &&
    (hc.pointsNewestFirst.toVector ?= points.reverse) &&
    (hc.pointsAfterFirst ?= m.nonEmptyPoints.tail) &&
    all(
      locals.map(ours =>
        (hc.intersectionPoint(ours) ?= points.reverseIterator.find(ours.contains)) :| s"$ours"
      )*
    ) &&
    (hc.densityFragment ?= GenesisDensity.Fragment(
      m.nonEmptyPoints.head,
      m.nonEmptyPoints.tail.collect { case b: Point.Block => b }
    )) &&
    (hc.tipHash ?= m.tipHash) &&
    (hc.tipState ?= m.tipState) &&
    (hc.tipPoint ?= m.tipPoint) &&
    // the same chain built from its fields: equal, and indexed alike
    (hc ?= HeaderChain(m.base, m.baseState, m.entries, m.prefix, m.baseView)) &&
    (hc.hashCode ?= HeaderChain(m.base, m.baseState, m.entries, m.prefix, m.baseView).hashCode) &&
    all(headers.map { h =>
      ((hc.contains(h.hash) ?= m.contains(h.hash)) &&
        (hc.stateAfter(h.hash) ?= m.stateAfter(h.hash)) &&
        (hc.canExtend(h) ?= m.canExtend(h))) :| s"header ${h.hash.hex.take(8)}"
    }*) &&
    all(universe.map { p =>
      val at = points.indexOf(p)
      ((hc.withinFragmentBounds(p) ?= points.contains(p)) &&
        (hc.successorPoint(p) ?= Option
          .when(at >= 0)(points.drop(at + 1).headOption)
          .flatten) &&
        (hc.rollback(p) ?= m
          .rollback(p)
          .map(r => HeaderChain(r.base, r.baseState, r.entries, r.prefix, r.baseView))) &&
        (hc.trimmedTo(p).points ?= m.trimmedTo(p).points)) :| s"point $p"
    }*)

  /** The model after `o`; an operation it refuses leaves it. */
  private def advanced(m: HeaderChainModel[Int], o: Op): HeaderChainModel[Int] = o match
    case Op.Extend(h, s)    => m.extend(h, s).getOrElse(m)
    case Op.Rollback(p)     => m.rollback(p).getOrElse(m)
    case Op.Trim(p)         => m.trimmedTo(p)
    case Op.Prune(max)      => m.pruned(max)
    case Op.Map             => m.map(_ + 1)
    case Op.ForgetBaseState => m.copy(baseState = None)
    case Op.ForgetBaseView  => m.copy(baseView = None)
    case Op.KeepEntries(n)  => m.copy(entries = m.entries.take(n))
    case Op.DropPrefix(n)   => m.copy(prefix = m.prefix.drop(n))

  private val scenario: Gen[(HeaderChainModel[Int], Vector[Op])] =
    for
      (base, baseState, entries, prefix) <- start
      initial = HeaderChainModel(base, baseState, entries, prefix)
      steps <- Gen.choose(1, 30)
      ops <- (1 to steps).foldLeft(Gen.const(Vector.empty[Op] -> initial)) { (acc, _) =>
        acc.flatMap((done, m) => op(m).map(o => (done :+ o) -> advanced(m, o)))
      }
    yield initial -> ops._1

  property("every method of the indexed chain answers as the chain it replaces") {
    // no shrinking: a shrunk sequence of operations may add a block the chain already holds
    forAllNoShrink(scenario) { (model, ops) =>
      val indexed = HeaderChain(model.base, model.baseState, model.entries, model.prefix)
      ops
        .foldLeft((indexed, model, observe(indexed, model))) { case ((hc, m, seen), o) =>
          val (c, n, answer) = step(hc, m, o)
          (c, n, (seen && answer && observe(c, n)) :| s"$o")
        }
        ._3
    }
  }

  test(
    "a long chain is built, extended, trimmed, pruned and rolled back on a small stack (STK-3)"
  ) {
    val n = 20000
    val chain = Fixtures.chain(n)
    PreprodDeepTx.onSmallStack {
      val built = HeaderChain(
        chain(99).point,
        Some(99),
        chain.slice(100, n / 2).map(h => Entry(h, Some(h.blockNo.toInt))),
        chain.take(99).map(h => Known(h.point, Some(h.blockNo.toInt)))
      )
      val grown = chain
        .drop(n / 2)
        .foldLeft(built)((c, h) =>
          c.extend(h, Some(h.blockNo.toInt)).fold(e => fail(e.message), identity)
        )
      val trimmed = grown.trimmedTo(chain(n / 4).point)
      val pruned = trimmed.pruned(1000)
      val back = grown.rollback(chain(50).point).getOrElse(fail("rollback"))
      assertEquals(grown.points, chain.map(_.point))
      assertEquals(trimmed.base, chain(n / 4).point)
      assertEquals(pruned.entries.size, 1000)
      assertEquals(back.tipPoint, chain(50).point)
      assertEquals(grown.stateAfter(chain(n - 1).hash), Some(Some(n - 1)))
      assertEquals(grown.successorPoint(chain(n / 3).point), Some(chain(n / 3 + 1).point))
      assertEquals(
        grown,
        HeaderChain(grown.base, grown.baseState, grown.entries, grown.prefix, grown.baseView)
      )
    }
  }
