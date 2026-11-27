// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity
import xyz.kd5ujc.scarab.consensus.genesis.GenesisDensity.Fragment
import xyz.kd5ujc.scarab.ledger.{Block, Fixtures, Header}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ReferenceBuild}
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The Genesis projection the block-selection worker runs on each admission (#1499) gives what the
  * whole-structure computation before it gave ([[GenesisProjectionOracle]]): the same projected
  * candidates, rebasing and trimming, the same LoE trims and refusals, and a selection window
  * carried through extensions and rollbacks that equals and answers as the window built whole.
  *
  * The chains are lawful, as the node's are: the recorded first 70 preprod blocks (the epoch 0
  * boundary block, the main block that shares its slot, the rest of Byron and the first Shelley
  * blocks), continued and forked by synthetic headers, including a fork from the boundary block and
  * one with another genesis.
  */
class GenesisProjectionSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(400)

  private val recorded: Vector[Header] =
    val setup = NodeSetup
      .fromDirectory(ReferenceBuild.Default)(Path.of("fixtures/preprod/genesis"))
      .fold(e => fail(e.message), identity)
    Files
      .readAllLines(Path.of("fixtures/preprod/blocks.hex"))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(l => Bytes.fromHex(l).fold(e => fail(e.message), identity))
      .map(Block.decode(_, setup.geometry.byron.epochSlots).fold(e => fail(e.message), _.header))

  /** `path` up to the header at `at` (none for -1), then `n` new headers. */
  private def fork(path: Vector[Header], at: Int, n: Int, salt: Int): Vector[Header] =
    path.take(at + 1) ++ Fixtures.chain(n, salt, path.lift(at))

  private val trunk = recorded ++ Fixtures.chain(40, 0, recorded.lastOption)
  private val paths: Vector[Vector[Header]] =
    val second = fork(trunk, 60, 40, 2)
    Vector(
      trunk,
      fork(trunk, 0, 12, 5),
      fork(trunk, 5, 30, 1),
      second,
      fork(second, 80, 20, 3),
      fork(trunk, -1, 30, 4)
    )

  private def pointAt(path: Vector[Header], i: Int): Point =
    path.lift(i).fold(Point.Origin)(_.point)
  private def viewAt(path: Vector[Header], i: Int): SelectView =
    path.lift(i).fold(SelectView(-1, 0, None, 0, None))(SelectView.of)
  private def block(h: Header): ChainSelection.LocalBlock =
    ChainSelection.LocalBlock(h.point, SelectView.of(h))

  private val allPoints: Vector[Point] =
    (Point.Origin +: paths.flatten.map(_.point)).distinct

  private val window: Gen[ChainSelection.Local] =
    for
      path <- Gen.oneOf(paths)
      anchor <- Gen.choose(-1, path.size - 1)
      tip <- Gen.choose(anchor, (anchor + 30).min(path.size - 1))
    yield ChainSelection.Local(
      pointAt(path, anchor),
      viewAt(path, anchor),
      path.slice(anchor + 1, tip + 1).map(block)
    )

  /** A candidate: mostly from a point of `local`, otherwise from anywhere on the paths. */
  private def candidate(local: ChainSelection.Local): Gen[ChainSelection.Candidate] =
    val onWindow =
      for
        p <- Gen.oneOf(local.anchor +: local.blocks.map(_.point))
        path <- Gen.oneOf(paths.filter(path => p == Point.Origin || path.exists(_.point == p)))
        base = if p == Point.Origin then -1 else path.indexWhere(_.point == p)
        end <- Gen.choose(base, (base + 40).min(path.size - 1))
      yield ChainSelection.Candidate(p, path.slice(base + 1, end + 1))
    val anywhere =
      for
        path <- Gen.oneOf(paths)
        base <- Gen.choose(-1, path.size - 1)
        end <- Gen.choose(base, (base + 40).min(path.size - 1))
      yield ChainSelection.Candidate(pointAt(path, base), path.slice(base + 1, end + 1))
    Gen.frequency(3 -> onWindow, 2 -> anywhere)

  private val fragment: Gen[Fragment] =
    for
      path <- Gen.oneOf(paths)
      anchor <- Gen.choose(-1, path.size - 1)
      end <- Gen.choose(anchor, path.size - 1)
    yield Fragment(pointAt(path, anchor), path.slice(anchor + 1, end + 1).map(_.blockPoint))

  private val limit: Gen[Option[Fragment]] =
    Gen.frequency(1 -> Gen.const(None), 6 -> fragment.map(Some(_)))
  private val k: Gen[Int] = Gen.oneOf(0, 1, 2, 3, 5, 8, 2160)

  private def selected(local: ChainSelection.Local): Fragment =
    Fragment(local.anchor, local.blocks.map(_.point).collect { case b: Point.Block => b })

  property("a projected candidate is the one the whole-window computation gives") {
    forAll(window.flatMap(l => candidate(l).map(l -> _)), limit, k) { case ((local, c), loe, k) =>
      assertEquals(
        Follower.limitCandidate(k, local, c, loe),
        GenesisProjectionOracle.limitCandidate(k, local, c, loe)
      )
    }
  }

  property("the candidates chosen, and their order, are those the whole-window projection gives") {
    forAll(
      window.flatMap(l => Gen.choose(1, 4).flatMap(Gen.listOfN(_, candidate(l))).map(l -> _)),
      limit,
      k
    ) { case ((local, cs), loe, k) =>
      assertEquals(
        CandidateSelection.project(k, local, cs.toVector, loe),
        GenesisProjectionOracle.project(k, local, cs.toVector, loe)
      )
    }
  }

  /** A candidate whose blocks are linked otherwise than the window's and the limit's: a block of
    * its own before blocks it shares with them, as the forest holds a Byron boundary block naming
    * the genesis under the block its raw predecessor names, while the header gate puts it after
    * Origin (`Origin -> P -> E -> X` against `Origin -> E -> X`). The newest shared point is found
    * as before, wherever the candidate's own blocks are.
    */
  property("a candidate with a block of its own before shared ones projects as before") {
    val foreign = Fixtures.chain(3, 9)
    val crossed =
      for
        local <- window
        c <- candidate(local)
        at <- Gen.choose(0, c.headers.size)
        extra <- Gen.oneOf(foreign)
      yield (local, c.copy(headers = c.headers.patch(at, Vector(extra), 0)))
    forAll(crossed, limit, k) { case ((local, c), loe, k) =>
      assertEquals(
        Follower.limitCandidate(k, local, c, loe),
        GenesisProjectionOracle.limitCandidate(k, local, c, loe)
      )
      assertEquals(
        CandidateSelection.project(k, local, Vector(c), loe),
        GenesisProjectionOracle.project(k, local, Vector(c), loe)
      )
      val proposed = Fragment(local.anchor, c.headers.map(_.blockPoint))
      assertEquals(
        GenesisDensity.trim(k, selected(local), proposed, loe),
        GenesisProjectionOracle.trim(k, selected(local), proposed, loe)
      )
    }
  }

  test("a boundary block after Origin in the limit and after a held block in the forest") {
    val p = Fixtures.header(0, 1, None, 11)
    val e = Fixtures.header(0, 1, Some(p.hash), 12)
    val x = Fixtures.chain(4, 13, Some(e))
    val c = ChainSelection.Candidate(Point.Origin, Vector(p, e) ++ x)
    val loe = Some(Fragment(Point.Origin, (e +: x).map(_.blockPoint)))
    val empty = ChainSelection.Local(Point.Origin, viewAt(trunk, -1), Vector.empty)
    for
      local <- Vector(empty, empty.extended(block(e), 0))
      k <- Vector(0, 2, 2160)
    do
      assertEquals(
        Follower.limitCandidate(k, local, c, loe),
        GenesisProjectionOracle.limitCandidate(k, local, c, loe)
      )
  }

  property("an LoE trim, and its refusal, are those of the whole-limit search") {
    forAll(window, fragment, limit, k) { (local, c, loe, k) =>
      assertEquals(
        GenesisDensity.trim(k, selected(local), c, loe),
        GenesisProjectionOracle.trim(k, selected(local), c, loe)
      )
    }
  }

  /** A limit along a path, and a candidate that follows it to a chosen point and then leaves it, by
    * a fork of its own or by stopping there, so every boundary of the limit's tip is met.
    */
  property("a candidate leaving the limit at, before or after its tip is trimmed as before") {
    val leaving =
      for
        path <- Gen.oneOf(paths)
        anchor <- Gen.choose(-1, path.size - 1)
        end <- Gen.choose(anchor, (anchor + 12).min(path.size - 1))
        leaves <- Gen.choose(anchor, (end + 2).min(path.size - 1))
        fresh <- Gen.choose(0, 4)
        from <- Gen.choose(-1, leaves)
      yield
        val candidate = fork(path, leaves, fresh, 7)
        (
          Fragment(pointAt(path, anchor), path.slice(anchor + 1, end + 1).map(_.blockPoint)),
          Fragment(pointAt(candidate, from), candidate.drop(from + 1).map(_.blockPoint))
        )
    forAll(leaving, window, k) { case ((loe, c), local, k) =>
      val current = Fragment(loe.anchor, Vector.empty)
      assertEquals(
        GenesisDensity.trim(k, current, c, Some(loe)),
        GenesisProjectionOracle.trim(k, current, c, Some(loe))
      )
      assertEquals(
        GenesisDensity.trim(k, selected(local), c, Some(loe)),
        GenesisProjectionOracle.trim(k, selected(local), c, Some(loe))
      )
    }
  }

  property("a point's offset found by slot is its index on the fragment, the anchor included") {
    forAll(fragment, Gen.oneOf(allPoints)) { (f, p) =>
      assertEquals(f.offsetOf(p), Option(f.points.indexOf(p)).filter(_ >= 0))
      assertEquals(f.splitAfter(p), GenesisProjectionOracle.splitAfter(f, p))
      assertEquals(
        GenesisDensity.sanitize(Fragment(p, Vector.empty), f),
        GenesisProjectionOracle.sanitize(Fragment(p, Vector.empty), f)
      )
    }
  }

  /** One change of the window: the next block of a path after the tip, with this many blocks made
    * immutable; a rollback to one of its points; or, rarely, a block it holds already or Origin.
    */
  private enum Op:
    case Extend(path: Int, immutable: Int)
    case Rollback(at: Int)
    case Repeat(at: Int)
    case AtOrigin

  private val op: Gen[Op] = Gen.frequency(
    8 -> Gen
      .zip(Gen.choose(0, paths.size - 1), Gen.frequency(4 -> Gen.const(0), 2 -> Gen.choose(1, 3)))
      .map(Op.Extend(_, _)),
    3 -> Gen.choose(0, 1000).map(Op.Rollback(_)),
    1 -> Gen.choose(0, 1000).map(Op.Repeat(_)),
    1 -> Gen.const(Op.AtOrigin)
  )

  private def step(
      local: ChainSelection.Local,
      o: Op
  ): Option[(ChainSelection.Local, ChainSelection.Local)] =
    o match
      case Op.Extend(p, immutable) =>
        val path = paths(p)
        val tip = local.blocks.lastOption.fold(local.anchor)(_.point)
        val at = if tip == Point.Origin then -1 else path.indexWhere(_.point == tip)
        Option
          .when(tip == Point.Origin || at >= 0)(path.lift(at + 1))
          .flatten
          .map(h =>
            (
              local.extended(block(h), immutable),
              GenesisProjectionOracle.extended(local, block(h), immutable)
            )
          )
      case Op.Rollback(at) =>
        val points = local.anchor +: local.blocks.map(_.point)
        val point = points(at % points.size)
        local
          .rolledBackTo(point)
          .map(
            _ -> GenesisProjectionOracle
              .rolledBackTo(local, point)
              .getOrElse(fail(s"no rollback to $point"))
          )
      case Op.Repeat(at) =>
        local.blocks
          .lift(at % (local.blocks.size + 1))
          .map(b => (local.extended(b, 0), GenesisProjectionOracle.extended(local, b, 0)))
      case Op.AtOrigin =>
        val b = ChainSelection.LocalBlock(Point.Origin, SelectView(-1, 0, None, 0, None))
        Some((local.extended(b, 0), GenesisProjectionOracle.extended(local, b, 0)))

  private def answersAsScans(local: ChainSelection.Local): Prop =
    val hashes = None +: local.blocks.map(_.point).collect { case Point.Block(_, h) => Some(h) }
    val probes = (local.anchor +: local.blocks.map(_.point)) ++ allPoints.take(40)
    probes.foreach { p =>
      assertEquals(local.contains(p), GenesisProjectionOracle.contains(local, p), p)
      assertEquals(local.depthTo(p), GenesisProjectionOracle.depthTo(local, p), p)
      assertEquals(local.rolledBackTo(p), GenesisProjectionOracle.rolledBackTo(local, p), p)
    }
    hashes.foreach(h => assertEquals(local.placesOf(h), GenesisProjectionOracle.placesOf(local, h)))
    assertEquals(local.blockPoints, selected(local).headers)
    Prop.passed

  property(
    "a window carried through extensions, anchor moves and rollbacks is the one built whole"
  ) {
    forAll(window, Gen.listOf(op)) { (start, ops) =>
      ops.foldLeft(start) { (local, o) =>
        step(local, o).fold(local) { (carried, whole) =>
          assertEquals(carried, whole)
          answersAsScans(carried)
          carried
        }
      }
      Prop.passed
    }
  }

  property("a carried window projects as the whole-window computation does") {
    forAll(window, Gen.listOf(op), limit, k) { (start, ops, loe, k) =>
      // only lawful windows: no block held twice, none at Origin
      val local = ops
        .filter { case Op.Extend(_, _) | Op.Rollback(_) => true; case _ => false }
        .foldLeft(start)((l, o) => step(l, o).fold(l)(_._1))
      forAll(candidate(local)) { c =>
        assertEquals(
          Follower.limitCandidate(k, local, c, loe),
          GenesisProjectionOracle.limitCandidate(k, local, c, loe)
        )
      }
    }
  }

  test("an irregular window, a block held twice or at Origin, is searched as before") {
    val path = paths(0)
    val base = ChainSelection.Local(Point.Origin, viewAt(path, -1), path.take(6).map(block))
    val twice = base.extended(block(path(2)), 0)
    val origin = base.extended(ChainSelection.LocalBlock(Point.Origin, viewAt(path, -1)), 0)
    Vector(twice, origin, twice.extended(block(path(3)), 2)).foreach { local =>
      answersAsScans(local)
      paths.foreach { p =>
        val c = ChainSelection.Candidate(Point.Origin, p.take(10))
        assertEquals(
          local.intersection(c).map(_._1),
          Option((c.base +: c.headers.map(_.point)).lastIndexWhere(local.contains)).filter(_ >= 0)
        )
      }
    }
  }

  /** Blocks of the paths arrive in a random order, each after its predecessor; after each arrival
    * the selection projects every path's candidate and adopts the best, keeping `k` blocks, while
    * the limit moves along the trunk, jumps to a fork, or is lifted. Both projections choose the
    * same candidates at every step, so the selected chain is the same throughout.
    */
  property("the same arrivals select the same chain with and without the incremental projection") {
    val arrival = Gen.zip(Gen.choose(0, paths.size - 1), Gen.choose(0, 9))
    forAll(Gen.choose(1, 8), Gen.listOfN(150, arrival)) { (k, arrivals) =>
      val start = ChainSelection.Local(Point.Origin, viewAt(trunk, -1), Vector.empty)
      def adopt(
          local: ChainSelection.Local,
          chosen: Vector[ChainSelection.Candidate],
          back: (ChainSelection.Local, Point) => Option[ChainSelection.Local],
          extend: (ChainSelection.Local, ChainSelection.LocalBlock, Int) => ChainSelection.Local
      ): ChainSelection.Local =
        chosen.headOption.fold(local) { c =>
          c.headers.foldLeft(back(local, c.base).getOrElse(fail(s"no rollback to ${c.base}")))(
            (w, h) => extend(w, block(h), (w.blocks.size + 1 - k).max(0))
          )
        }
      arrivals.zipWithIndex.foldLeft((start, start, Map.empty[Int, Int])) {
        case ((carried, whole, admitted), ((p, l), i)) =>
          val got = admitted.updated(p, (admitted.getOrElse(p, 0) + 1).min(paths(p).size))
          // each path's candidate from its newest point on our chain, as the whole window finds it
          def candidates(local: ChainSelection.Local) = paths.indices.toVector.flatMap { q =>
            val have = paths(q).take(got.getOrElse(q, 0))
            val common = (Point.Origin +: have.map(_.point))
              .lastIndexWhere(GenesisProjectionOracle.contains(local, _))
            Option.when(common >= 0)(
              ChainSelection.Candidate(pointAt(have, common - 1), have.drop(common))
            )
          }
          val loe = l match
            case 0 => None
            case 1 => Some(Fragment(Point.Origin, paths(3).take(i / 2).map(_.blockPoint)))
            case _ => Some(Fragment(Point.Origin, trunk.take(i / 2 + l).map(_.blockPoint)))
          val chosen = CandidateSelection.project(k, carried, candidates(carried), loe)
          val before = GenesisProjectionOracle.project(k, whole, candidates(whole), loe)
          assertEquals(chosen, before)
          val nextCarried = adopt(carried, chosen, _.rolledBackTo(_), _.extended(_, _))
          val nextWhole =
            adopt(
              whole,
              before,
              GenesisProjectionOracle.rolledBackTo,
              GenesisProjectionOracle.extended
            )
          assertEquals(nextCarried, nextWhole)
          (nextCarried, nextWhole, got)
      }
      Prop.passed
    }
  }

  /** The candidates of the processed forest as the worker builds them, a Byron boundary block that
    * names the genesis among them: the forest keeps it under the genesis hash, which the walks take
    * for Origin (`originPredecessor`), and the limit holds it after Origin, as the header gate
    * reads it. Every window of the recorded chain from Origin, every limit along each path, and the
    * candidates through each block or from every selected point, project as the oracle does.
    */
  test("the forest's candidates through a boundary block naming the genesis project as before") {
    val genesis = recorded.headOption.flatMap(_.prevHash)
    assert(genesis.isDefined)
    val forest = ProcessedForest.of(paths.flatten.distinct).fold(e => fail(e.toString), identity)
    val windows = Vector(0, 1, 2, 5, 40).map(n =>
      ChainSelection.Local(Point.Origin, viewAt(trunk, -1), trunk.take(n).map(block))
    )
    // the boundary block is reached from Origin through its alias, not left out
    assert(
      AvailableBlocks
        .candidates(windows(0), forest, _ => false, None, genesis)
        .exists(c => c.base == Point.Origin && c.headers.headOption.contains(recorded(0)))
    )
    val limits = None +: paths.flatMap(path =>
      Vector(0, 1, 2, 30, path.size).map(m =>
        Some(Fragment(Point.Origin, path.take(m).map(_.blockPoint)))
      )
    )
    for
      local <- windows
      containing <- None +: Vector(0, 1, 3, 50).map(i => Some(Set(trunk(i).hash)))
      candidates = AvailableBlocks.candidates(local, forest, _ => false, containing, genesis)
      loe <- limits
      k <- Vector(1, 3, 2160)
    do
      assertEquals(
        CandidateSelection.project(k, local, candidates, loe),
        GenesisProjectionOracle.project(k, local, candidates, loe)
      )
      candidates.foreach(c =>
        assertEquals(
          Follower.limitCandidate(k, local, c, loe),
          GenesisProjectionOracle.limitCandidate(k, local, c, loe)
        )
      )
  }

  test("a long window, limit and candidate project as the whole-window computation does") {
    val chain = Fixtures.chain(7000)
    val local = ChainSelection.Local(
      chain(999).point,
      SelectView.of(chain(999)),
      chain.slice(1000, 3160).map(block)
    )
    val forkAt = 2000
    val forked = fork(chain, forkAt, 3000, 1)
    val candidates = Vector(
      ChainSelection.Candidate(chain(3159).point, chain.slice(3160, 6160)),
      ChainSelection.Candidate(forked(forkAt).point, forked.drop(forkAt + 1)),
      ChainSelection.Candidate(chain(10).point, chain.slice(11, 4000)),
      ChainSelection.Candidate(chain(3159).point, Vector.empty)
    )
    val limits = Vector(
      None,
      Some(Fragment(chain(500).point, chain.slice(501, 5000).map(_.blockPoint))),
      Some(Fragment(chain(500).point, chain.slice(501, 2500).map(_.blockPoint))),
      Some(Fragment(chain(999).point, forked.slice(1000, 4500).map(_.blockPoint))),
      Some(Fragment(chain(1500).point, Vector.empty))
    )
    for
      loe <- limits
      c <- candidates
      k <- Vector(0, 2160)
    do
      assertEquals(
        Follower.limitCandidate(k, local, c, loe),
        GenesisProjectionOracle.limitCandidate(k, local, c, loe)
      )
      assertEquals(
        CandidateSelection.project(k, local, candidates, loe),
        GenesisProjectionOracle.project(k, local, candidates, loe)
      )
  }
