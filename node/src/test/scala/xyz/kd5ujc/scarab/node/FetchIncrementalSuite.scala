// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, Fetched, FetchMode}
import xyz.kd5ujc.scarab.protocol.ChainSync
import xyz.kd5ujc.scarab.protocol.ChainSync.Point

/** The candidate index, the heights and the decision stages that issue 810 carries from one batch
  * or decision to the next are those found from scratch ([[FetchDecisionModel]]), over random
  * sequences of extensions, trims, rollbacks, forks, replaced candidates, changing fetched sets and
  * selections, across several peers.
  */
class FetchIncrementalSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(300)

  private val main = Fixtures.chain(60)

  /** One ChainSync event, drawn from codes against the candidate it applies to. */
  private final case class Op(forward: Boolean, kind: Int, seed: Int)

  private val ops: Gen[Op] =
    for
      forward <- Gen.frequency(4 -> true, 1 -> false)
      kind <- Gen.choose(0, 99)
      seed <- Gen.choose(0, 1 << 20)
    yield Op(forward, kind, seed)

  /** The header `op` rolls forward onto `candidate`: mostly the next main header or a fork's next,
    * sometimes one with an earlier slot, one that extends nothing, or one seen before.
    */
  private def headerFor(op: Op, candidate: ChainSelection.Candidate, seen: Vector[Header]): Header =
    val tip = candidate.headers.lastOption
    val prev = tip.map(_.hash).orElse(pointHash(candidate.base))
    val blockNo = tip.fold(BigInt(1))(_.blockNo + 1)
    val slot = tip.fold(BigInt(1))(_.slot)
    val onMain = tip.fold(Some(-1))(t => Some(main.indexWhere(_.hash == t.hash)).filter(_ >= -1))
    op.kind % 20 match
      case 0 if seen.nonEmpty => seen(op.seed % seen.size)
      case 1 => Fixtures.header(blockNo, (slot - 1 - op.seed % 3).max(0), prev, op.seed)
      case 2 => Fixtures.header(blockNo, slot + op.seed % 3, Some(main(op.seed % 60).hash), op.seed)
      case 3 | 4 =>
        // the next header with the same slot as the tip
        Fixtures.header(blockNo, slot, prev, op.seed)
      case _ =>
        onMain
          .filter(i => i + 1 < main.size && op.kind % 20 < 16)
          .fold(Fixtures.header(blockNo, slot + 1 + op.seed % 2, prev, op.seed % 3))(i =>
            main(i + 1)
          )

  private def pointHash(point: Point): Option[Bytes] = point match
    case Point.Block(_, hash) => Some(hash)
    case Point.Origin         => None

  /** The point `op` rolls back to: the base, one of the headers, one it does not hold, or origin.
    */
  private def pointFor(op: Op, candidate: ChainSelection.Candidate, seen: Vector[Header]): Point =
    op.kind % 8 match
      case 0                  => candidate.base
      case 1 if seen.nonEmpty => seen(op.seed % seen.size).point
      case 2                  => Point.Origin
      case _ if candidate.headers.nonEmpty =>
        candidate.headers(op.seed % candidate.headers.size).point
      case _ => main(op.seed % 60).point

  private def event(op: Op, candidate: ChainSelection.Candidate, seen: Vector[Header]): SyncEvent =
    if op.forward then SyncEvent.RollForward(headerFor(op, candidate, seen), ChainSync.Tip.Origin)
    else SyncEvent.RollBackward(pointFor(op, candidate, seen), ChainSync.Tip.Origin)

  /** Spans over `size` headers drawn from bits: which headers are taken and where a run is cut. */
  private def spansOf(size: Int, taken: Long, cut: Long): Vector[FetchCandidates.Span] =
    (0 until size)
      .foldLeft(Vector.empty[FetchCandidates.Span]) { (spans, i) =>
        val take = (taken >>> (i % 64) & 1) == 1
        val joins = (cut >>> (i % 64) & 1) == 0
        if !take then spans
        else
          spans.lastOption match
            case Some(last) if last.until == i && joins =>
              spans.dropRight(1) :+ last.copy(until = i + 1)
            case _ => spans :+ FetchCandidates.Span(i, i + 1)
      }

  /** What a decision reads of an index is what the model finds of its candidate. */
  private def agrees(announced: FetchCandidates.Announced, taken: Long, cut: Long): Prop =
    val candidate = announced.candidate
    val headers = candidate.headers
    val spans = spansOf(headers.size, taken, cut) :+ FetchCandidates.Span(0, headers.size)
    val probes = (candidate.base +: Point.Origin +: headers.map(_.point)) ++ main.map(_.point)
    val retained = headers.map(_.point).toSet + candidate.base
    (announced.order == (if FetchDecisionModel.ordered(candidate) then
                           FetchCandidates.SlotOrder.NonDecreasing
                         else FetchCandidates.SlotOrder.Unchecked)) :| "order" &&
    probes.forall(p => announced.retains(p) == retained(p)) :| "retained points" &&
    spans.indices.forall { n =>
      val chosen = spans.take(n + 1)
      announced.fragments(chosen) ==
        FetchDecisionModel.fragmentsOf(chosen.flatMap(s => headers.slice(s.from, s.until)))
    } :| "fragments"

  property("an index carried through any steps is the one a scan of its candidate finds") {
    forAll(Gen.listOfN(40, ops), Gen.choose(0, 7), Gen.long, Gen.long) {
      (steps, retained, taken, cut) =>
        val start =
          FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, Vector.empty))
        steps
          .foldLeft((start, Vector.empty[Header], Prop.passed)) {
            case ((announced, seen, prop), op) =>
              val before = announced.candidate
              val step = event(op, before, seen) match
                case e @ SyncEvent.RollForward(h, _)  => (announced.forward(h, retained), e)
                case e @ SyncEvent.RollBackward(p, _) => (announced.backward(p), e)
              val (moved, applied) = step
              val next = moved.announced
              val expected = FetchDecisionModel.candidateAfter(before, Vector(applied), retained)
              val leftExactly = moved match
                case FetchCandidates.Announced.Step.Moved(n, left) =>
                  val was = before.headers.map(_.point).toSet + before.base
                  val is = n.candidate.headers.map(_.point).toSet + n.candidate.base
                  (n.derivation == FetchCandidates.Derivation.Carried) &&
                  (was -- is).subsetOf(left.toSet) && left.forall(was)
                case FetchCandidates.Announced.Step.Reset(n) =>
                  n.candidate.headers.isEmpty && !before.retains(n.candidate.base)
              val seenNext = applied match
                case SyncEvent.RollForward(h, _) => seen :+ h
                case _                           => seen
              (
                next,
                seenNext,
                prop && (next.candidate == expected) :| s"candidate after $op" &&
                  leftExactly :| s"left points after $op" && agrees(next, taken, cut) &&
                  agrees(FetchCandidates.Announced.of(next.candidate), taken, cut)
              )
          }
          ._3
    }
  }

  extension (candidate: ChainSelection.Candidate)
    private def retains(point: Point): Boolean =
      candidate.base == point || candidate.headers.exists(_.point == point)

  /** One round of a session: a batch, the heights a FindIntersect offered, or a candidate put in
    * place from outside (a restart or a Genesis graft).
    */
  private enum Round:
    case Batch(ops: List[Op])
    case Offered(points: List[Int])
    case Replaced(base: Int, length: Int)

  private val rounds: Gen[Round] = Gen.frequency(
    8 -> Gen.choose(0, 6).flatMap(Gen.listOfN(_, ops)).map(Round.Batch(_)),
    1 -> Gen.listOf(Gen.choose(0, 59)).map(Round.Offered(_)),
    1 -> (for b <- Gen.choose(-1, 50); n <- Gen.choose(0, 9) yield Round.Replaced(b, n))
  )

  private final case class Session(
      candidate: ChainSelection.Candidate,
      track: CandidateTrack,
      heights: Map[Point, Option[BigInt]],
      seen: Vector[Header]
  )

  property("the heights and candidate a batch leaves are those of the whole-candidate filter") {
    forAll(Gen.listOfN(30, rounds), Gen.choose(0, 7)) { (script, retained) =>
      val start = Session(
        ChainSelection.Candidate(Point.Origin, Vector.empty),
        CandidateTrack.initial,
        CandidateTrack.initial.heights,
        Vector.empty
      )
      script
        .foldLeft((start, Prop.passed)) { case ((session, prop), round) =>
          round match
            case Round.Batch(codes) =>
              val (batch, seen) = codes.foldLeft((Vector.empty[SyncEvent], session.seen)) {
                case ((events, seen), op) =>
                  val now = FetchDecisionModel.candidateAfter(session.candidate, events, retained)
                  val e = event(op, now, seen)
                  e match
                    case SyncEvent.RollForward(h, _) => (events :+ e, seen :+ h)
                    case _                           => (events :+ e, seen)
              }
              val moved =
                CandidateTrack.move(session.track.announced, session.candidate, batch, retained)
              val received = batch.collect { case SyncEvent.RollForward(h, _) =>
                h.point -> Some(h.blockNo)
              }.toMap
              val track = session.track.recorded(moved, received)
              val candidate = FetchDecisionModel.candidateAfter(session.candidate, batch, retained)
              val heights = FetchDecisionModel.heightsAfter(session.heights, batch, candidate)
              (
                Session(moved.announced.candidate, track, heights, seen),
                prop && (moved.announced.candidate == candidate) :| "candidate" &&
                  (track.heights == heights) :| s"heights after $batch" &&
                  agrees(moved.announced, 0L, 0L)
              )
            case Round.Offered(indices) =>
              val offered =
                indices.map(i => main(i).point -> Some(main(i).blockNo)).toMap +
                  (Point.Origin -> None)
              (
                session.copy(track = session.track.offered(offered), heights = offered),
                prop
              )
            case Round.Replaced(base, length) =>
              val candidate = ChainSelection.Candidate(
                main.lift(base).fold(Point.Origin)(_.point),
                main.slice(base + 1, base + 1 + length)
              )
              (session.copy(candidate = candidate), prop)
        }
        ._2
    }
  }

  test("a session that only extends its candidate never scans it or filters every height again") {
    val retained = 8
    val batches = main.grouped(3).toVector
    val (_, _, scans, filters) =
      batches.foldLeft(
        (ChainSelection.Candidate(Point.Origin, Vector.empty), CandidateTrack.initial, 0, 0)
      ) { case ((candidate, track, scans, filters), headers) =>
        val batch = headers.map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
        val moved = CandidateTrack.move(track.announced, candidate, batch, retained)
        val scanned = if moved.start == FetchCandidates.Derivation.Scanned then 1 else 0
        val filtered = if track.carries(moved) then 0 else 1
        (
          moved.announced.candidate,
          track.recorded(moved, Map.empty),
          scans + scanned,
          filters + filtered
        )
      }
    // only the first batch, from the session's initial candidate, scans and filters
    assertEquals((scans, filters), (1, 1))
  }

  test("a long candidate is carried, indexed and cut into fragments on a small stack") {
    val long = Fixtures.chain(20000, salt = 1)
    val carried = xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(
      long.foldLeft(
        FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, Vector.empty))
      ) { (announced, h) =>
        announced.forward(h, 30000).announced
      }
    )
    val rolled =
      xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(carried.backward(long(10).point))
    val spans = Vector.tabulate(5000)(i => FetchCandidates.Span(i * 4, i * 4 + 3))
    assertEquals(carried.order, FetchCandidates.SlotOrder.NonDecreasing)
    assertEquals(rolled.announced.candidate.headers.size, 11)
    assertEquals(
      xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack(carried.fragments(spans)),
      FetchDecisionModel.fragmentsOf(spans.flatMap(s => long.slice(s.from, s.until)))
    )
    assertEquals(
      xyz.kd5ujc.scarab.ledger.PreprodDeepTx
        .onSmallStack(
          FetchCandidates.missing(carried, 0, h => h.blockNo % 3 == 0)
        )
        .size,
      6667
    )
  }

  /** One round of decisions: what changes before it. */
  private enum Change:
    case Steps(peer: Int, ops: List[Op])
    case Replace(peer: Int, base: Int, length: Int, fork: Int)
    case Leave(peer: Int)
    case Select(anchor: Int, length: Int, fork: Int)
    case Fetch(added: List[Int], removed: List[Int])
    case Mode(genesis: Boolean)
    case Nothing

  private val changes: Gen[Change] = Gen.frequency(
    6 -> (for p <- Gen.choose(0, 3); n <- Gen.choose(1, 4); o <- Gen.listOfN(n, ops)
    yield Change.Steps(p, o)),
    1 -> (for
      p <- Gen.choose(0, 3); b <- Gen.choose(-1, 50); n <- Gen.choose(0, 12);
      f <- Gen.choose(0, 4)
    yield Change.Replace(p, b, n, f)),
    1 -> Gen.choose(0, 3).map(Change.Leave(_)),
    3 -> (for a <- Gen.choose(-1, 40); n <- Gen.choose(0, 15); f <- Gen.choose(0, 5)
    yield Change.Select(a, n, f)),
    3 -> (for a <- Gen.listOf(Gen.choose(0, 59)); r <- Gen.listOf(Gen.choose(0, 59))
    yield Change.Fetch(a, r)),
    1 -> Gen.oneOf(true, false).map(Change.Mode(_)),
    2 -> Gen.const(Change.Nothing)
  )

  private final case class World(
      peers: Map[Int, FetchCandidates.Announced],
      weighings: Map[Int, FetchCoordinator.Weighing],
      local: Option[ChainSelection.Local],
      view: Option[FetchCandidates.Selected],
      fetched: Set[Bytes],
      genesis: Boolean,
      seen: Vector[Header]
  )

  private def localOf(anchor: Int, length: Int, fork: Int): ChainSelection.Local =
    val kept = main.slice(anchor + 1, anchor + 1 + length)
    // a selection that left the main chain at its tip for a fork of `fork` blocks
    val forked = kept.lastOption
      .orElse(main.lift(anchor))
      .fold(Vector.empty[Header])(last => Fixtures.chain(fork % 3, salt = 2, from = Some(last)))
    ChainSelection.Local(
      main.lift(anchor).fold(Point.Origin)(_.point),
      main.lift(anchor).fold(SelectView(-1, 0, None, 0, None))(SelectView.of),
      (kept ++ forked).map(h => ChainSelection.LocalBlock(h.point, SelectView.of(h)))
    )

  private def changed(world: World, change: Change): World = change match
    case Change.Steps(peer, codes) =>
      val start = world.peers.getOrElse(
        peer,
        FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, Vector.empty))
      )
      val (announced, seen) = codes.foldLeft((start, world.seen)) { case ((a, seen), op) =>
        event(op, a.candidate, seen) match
          case SyncEvent.RollForward(h, _)  => (a.forward(h, 40).announced, seen :+ h)
          case SyncEvent.RollBackward(p, _) => (a.backward(p).announced, seen)
      }
      world.copy(peers = world.peers.updated(peer, announced), seen = seen)
    case Change.Replace(peer, base, length, fork) =>
      val kept = main.slice(base + 1, base + 1 + length)
      val forked = kept.lastOption.fold(Vector.empty[Header])(last =>
        Fixtures.chain(fork, salt = 1, from = Some(last))
      )
      val candidate =
        ChainSelection.Candidate(main.lift(base).fold(Point.Origin)(_.point), kept ++ forked)
      world.copy(peers = world.peers.updated(peer, FetchCandidates.Announced.of(candidate)))
    case Change.Leave(peer) =>
      world.copy(peers = world.peers - peer, weighings = world.weighings - peer)
    case Change.Select(anchor, length, fork) =>
      world.copy(local = world.local.map(_ => localOf(anchor, length, fork)))
    case Change.Fetch(added, removed) =>
      world.copy(fetched = world.fetched ++ added.map(main(_).hash) -- removed.map(main(_).hash))
    case Change.Mode(genesis) => world.copy(genesis = genesis)
    case Change.Nothing       => world

  property("every round's stages and decisions are those found from scratch, for every peer") {
    forAll(
      Gen.listOfN(40, changes),
      Gen.oneOf(true, false),
      Gen.oneOf(FetchMode.BulkSync, FetchMode.Deadline)
    ) { (script, selecting, mode) =>
      val start = World(
        Map.empty,
        Map.empty,
        Option.when(selecting)(localOf(-1, 0, 0)),
        None,
        Set.empty,
        false,
        Vector.empty
      )
      script
        .foldLeft((start, Prop.passed)) { case ((before, prop), change) =>
          val world = changed(before, change)
          // a fresh membership test for every decision, as the block pool gives one
          val fetched = Fetched.of(world.fetched)
          val view = FetchCoordinator.viewOf(world.view, world.local)
          val peers = world.peers.toVector.sortBy(_._1)
          val decided = peers.map { (id, announced) =>
            val restriction = ChainSelection.tiebreakerFor(
              announced.candidate.headers.lastOption
                .fold(xyz.kd5ujc.scarab.protocol.Era.Byron)(_.era)
            )
            val (weighing, chosen) = FetchCoordinator.stages(
              world.weighings.get(id),
              view,
              announced,
              restriction,
              world.genesis,
              fetched
            )
            val expected = FetchDecisionModel.chosen(
              world.local,
              world.genesis,
              restriction,
              announced.candidate,
              fetched
            )
            (id, weighing, (chosen.left.toOption, chosen.getOrElse(Vector.empty)), expected)
          }
          def policy(
              stages: Vector[
                (Int, (Option[BlockFetchPolicy.Refusal], Vector[BlockFetchPolicy.Fragment]))
              ]
          ) =
            BlockFetchPolicy.decide(
              BlockFetchPolicy.Config(2, 1, 3),
              mode,
              stages.map { case (id, (refusal, fragments)) =>
                BlockFetchPolicy.Candidate(
                  peer = id,
                  chainPreference = id % 2,
                  chainHead = Bytes.empty,
                  fragments = fragments,
                  tieBreak = id.toLong,
                  refusal = refusal
                )
              }
            )
          val next = world.copy(
            weighings = decided.flatMap((id, w, _, _) => w.map(id -> _)).toMap,
            view = view
          )
          (
            next,
            prop && decided
              .forall((_, _, got, expected) => got == expected) :| s"stages after $change" &&
              (policy(decided.map((id, _, got, _) => id -> got)) ==
                policy(decided.map((id, _, _, expected) => id -> expected))) :| "decisions"
          )
        }
        ._2
    }
  }

  test("a peer weighed against the same candidate index and window keeps its weighing") {
    val local = localOf(5, 10, 0)
    val view = FetchCoordinator.viewOf(None, Some(local))
    val announced =
      FetchCandidates.Announced.of(ChainSelection.Candidate(main(3).point, main.slice(4, 30)))
    val restriction = ChainSelection.tiebreakerFor(main(29).era)
    val (first, _) =
      FetchCoordinator.stages(None, view, announced, restriction, false, Fetched.none)
    val (again, _) = FetchCoordinator.stages(
      first,
      FetchCoordinator.viewOf(view, Some(local)),
      announced,
      restriction,
      false,
      Fetched.none
    )
    val (moved, _) = FetchCoordinator.stages(
      first,
      FetchCoordinator.viewOf(view, Some(localOf(5, 11, 0))),
      announced,
      restriction,
      false,
      Fetched.none
    )
    assert(first.zip(again).exists(_ eq _))
    assert(!first.zip(moved).exists(_ eq _))
    assert(FetchCoordinator.viewOf(view, Some(local)).zip(view).exists(_ eq _))
  }
