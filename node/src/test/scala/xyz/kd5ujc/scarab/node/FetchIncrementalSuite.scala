// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.{Fixtures, Header}
import xyz.kd5ujc.scarab.network.selection.{BlockFetchPolicy, Fetched, FetchMode, GenesisBlockFetch}
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
    case Replaced(base: Int, length: Int, grafted: Boolean)

  private val rounds: Gen[Round] = Gen.frequency(
    8 -> Gen.choose(0, 6).flatMap(Gen.listOfN(_, ops)).map(Round.Batch(_)),
    1 -> Gen.listOf(Gen.choose(0, 59)).map(Round.Offered(_)),
    1 -> (for b <- Gen.choose(-1, 50); n <- Gen.choose(0, 9); g <- Gen.oneOf(true, false)
    yield Round.Replaced(b, n, g))
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
            case Round.Replaced(base, length, grafted) =>
              val candidate = ChainSelection.Candidate(
                main.lift(base).fold(Point.Origin)(_.point),
                main.slice(base + 1, base + 1 + length)
              )
              // a restart leaves the track; a graft installs the index it announced
              val track =
                if grafted then session.track.replaced(FetchCandidates.Announced.of(candidate))
                else session.track
              (session.copy(candidate = candidate, track = track), prop)
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
      val genesisStart = GenesisBlockFetch.State[Int]()
      script.zipWithIndex
        .foldLeft((start, genesisStart, genesisStart, Prop.passed)) {
          case ((before, genesisGot, genesisExpected, prop), (change, round)) =>
            val world = changed(before, change)
            // a fresh membership test for every decision, as the block pool gives one
            val fetched = Fetched.of(world.fetched)
            val view = FetchCoordinator.viewOf(world.view, world.local)
            // sessions 4 and 5 are registered and have announced nothing: the coordinator weighs a
            // fresh index of the empty candidate for them at every decision
            val unannounced = Vector(4, 5).map(id =>
              id -> FetchCandidates.Announced
                .of(ChainSelection.Candidate(Point.Origin, Vector.empty))
            )
            val peers = world.peers.toVector.sortBy(_._1) ++ unannounced
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
            val config = BlockFetchPolicy.Config(2, 1, 3)
            def candidates(
                stages: Vector[
                  (Int, (Option[BlockFetchPolicy.Refusal], Vector[BlockFetchPolicy.Fragment]))
                ]
            ) =
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
            // the Genesis decision keeps its state from round to round, on each side its own
            def genesis(
                state: GenesisBlockFetch.State[Int],
                stages: Vector[
                  (Int, (Option[BlockFetchPolicy.Refusal], Vector[BlockFetchPolicy.Fragment]))
                ]
            ) =
              GenesisBlockFetch.decide(
                config,
                state,
                BigInt(round) * 1000000000000L,
                GenesisBlockFetch.Starvation.EndedAt(BigInt(round / 3) * 3000000000000L),
                candidates(stages),
                GenesisBlockFetch.DefaultGracePicos,
                fetched
              )
            val got = decided.map((id, _, got, _) => id -> got)
            val expected = decided.map((id, _, _, expected) => id -> expected)
            val (decisions, genesisNext) =
              if world.genesis then
                val (a, b) = (genesis(genesisGot, got), genesis(genesisExpected, expected))
                (
                  (a.decisions, a.demoteDynamo, a.state) == (b.decisions, b.demoteDynamo, b.state),
                  (a.state, b.state)
                )
              else
                (
                  BlockFetchPolicy.decide(config, mode, candidates(got)) ==
                    BlockFetchPolicy.decide(config, mode, candidates(expected)),
                  (genesisGot, genesisExpected)
                )
            val next = world.copy(
              weighings = decided.flatMap((id, w, _, _) => w.map(id -> _)).toMap,
              view = view
            )
            (
              next,
              genesisNext._1,
              genesisNext._2,
              prop && decided
                .forall((_, _, got, expected) => got == expected) :| s"stages after $change" &&
                decisions :| "decisions"
            )
        }
        ._4
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

  test("heights a FindIntersect installs between a batch's move and its record are all filtered") {
    val retained = 8
    val first = main.take(5).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
    val start = ChainSelection.Candidate(Point.Origin, Vector.empty)
    val once = CandidateTrack.move(None, start, first, retained)
    val track = CandidateTrack.initial.recorded(once, Map.empty)
    val batch = main.slice(5, 7).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
    val moved = CandidateTrack.move(track.announced, once.announced.candidate, batch, retained)
    // the FindIntersect lands after the candidate moved and before its heights were recorded
    val offered = main.slice(20, 30).map(h => h.point -> Some(h.blockNo)).toMap +
      (Point.Origin -> None)
    val interleaved = track.offered(offered)
    val received = batch.collect { case SyncEvent.RollForward(h, _) =>
      h.point -> Some(h.blockNo)
    }.toMap
    assert(!interleaved.carries(moved))
    assertEquals(
      interleaved.recorded(moved, received).heights,
      FetchDecisionModel.heightsAfter(offered, batch, moved.announced.candidate)
    )
  }

  test("a candidate replaced between reading the index and moving it is scanned, not carried") {
    val retained = 8
    val start = ChainSelection.Candidate(Point.Origin, Vector.empty)
    val once = CandidateTrack.move(
      None,
      start,
      main.take(5).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin)),
      retained
    )
    val track = CandidateTrack.initial.recorded(once, Map.empty)
    // a restart put an equal but distinct candidate in place after the index was read
    val replaced = ChainSelection.Candidate(
      once.announced.candidate.base,
      once.announced.candidate.headers.map(identity)
    )
    val batch = main.slice(5, 7).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
    val moved = CandidateTrack.move(track.announced, replaced, batch, retained)
    assertEquals(moved.start, FetchCandidates.Derivation.Scanned)
    assert(!track.carries(moved))
    assertEquals(
      moved.announced.candidate,
      FetchDecisionModel.candidateAfter(replaced, batch, retained)
    )
    // a graft installs the index it announced, so the batch after it carries that index
    val grafted = track.replaced(FetchCandidates.Announced.of(replaced))
    val next = CandidateTrack.move(grafted.announced, replaced, batch, retained)
    assertEquals(next.start, FetchCandidates.Derivation.Carried)
    assert(!grafted.carries(next))
  }

  test("positions are counted again from the first header long before they could overflow") {
    // two headers that never extend each other, alternating, in a window of two: every pair is cut
    val a = Fixtures.header(1, 1, None, 1)
    val b = Fixtures.header(2, 2, None, 2)
    val start = FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, Vector.empty))
    def appended(announced: FetchCandidates.Announced, i: Long) =
      announced.forward(if i % 2 == 0 then a else b, 2).announced
    val near = (0L until FetchCandidates.Announced.Rebase - 2).foldLeft(start)(appended)
    // the rebase falls on append Rebase + 2, where the only retained break crosses it: every
    // append around it keeps the pair cut in two
    (FetchCandidates.Announced.Rebase - 2 until FetchCandidates.Announced.Rebase + 6)
      .foldLeft(near) { (announced, i) =>
        val next = appended(announced, i)
        val headers = next.candidate.headers
        assertEquals(headers.size, 2)
        assertEquals(
          next.fragments(Vector(FetchCandidates.Span(0, 2))),
          FetchDecisionModel.fragmentsOf(headers),
          s"after append ${i + 1}"
        )
        assertEquals(next.fragments(Vector(FetchCandidates.Span(0, 2))).size, 2)
        next
      }
  }

  test("spans are cut to the candidate") {
    val announced =
      FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, main.take(3)))
    assertEquals(
      announced.fragments(Vector(FetchCandidates.Span(-4, 1), FetchCandidates.Span(2, 9))),
      FetchDecisionModel.fragmentsOf(Vector(main(0), main(2)))
    )
  }

  test("a graft between a batch's move and its record keeps every height the whole filter keeps") {
    val retained = 8
    val start = ChainSelection.Candidate(Point.Origin, Vector.empty)
    val first = main.take(5).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
    val once = CandidateTrack.move(None, start, first, retained)
    // heights of retained points from the first batch, and one FindIntersect offered earlier
    val seeded = CandidateTrack.initial
      .offered(Map(main(40).point -> Some(main(40).blockNo), Point.Origin -> None))
      .recorded(
        once,
        first.collect { case SyncEvent.RollForward(h, _) => h.point -> Some(h.blockNo) }.toMap
      )
    val batch = main.slice(5, 7).map(SyncEvent.RollForward(_, ChainSync.Tip.Origin))
    val moved = CandidateTrack.move(seeded.announced, once.announced.candidate, batch, retained)
    // a graft of another candidate lands after the move and before the record
    val graft = ChainSelection.Candidate(main(1).point, main.slice(2, 4))
    val grafted = seeded.replaced(FetchCandidates.Announced.of(graft))
    val received = batch.collect { case SyncEvent.RollForward(h, _) =>
      h.point -> Some(h.blockNo)
    }.toMap
    val recorded = grafted.recorded(moved, received)
    assert(!grafted.carries(moved))
    assertEquals(
      recorded.heights,
      FetchDecisionModel.heightsAfter(seeded.heights, batch, moved.announced.candidate)
    )
    assert(recorded.heights.size > 2)
    // the batch after it starts from the graft, which is not the candidate the record left
    val after = CandidateTrack.move(recorded.announced, graft, batch, retained)
    assertEquals(after.start, FetchCandidates.Derivation.Scanned)
    assertEquals(
      after.announced.candidate,
      FetchDecisionModel.candidateAfter(graft, batch, retained)
    )
  }

  property("an index carried through steps and trims to our anchor is the one a scan finds") {
    forAll(
      Gen.listOfN(40, Gen.zip(ops, Gen.option(ops))),
      Gen.choose(0, 7)
    ) { (steps, retained) =>
      val start = FetchCandidates.Announced.of(ChainSelection.Candidate(Point.Origin, Vector.empty))
      steps
        .foldLeft((start, Vector.empty[Header], Prop.passed)) {
          case ((announced, seen, prop), (op, trim)) =>
            val before = announced.candidate
            trim match
              case Some(other) =>
                // trim to an anchor, never past a published point
                val anchor = pointFor(op, before, seen)
                val published = pointFor(other, before, seen)
                val step = announced.trimmedTo(anchor, published)
                val next = step.announced
                // exactly the old base and the dropped headers before the new base, in order
                val dropped = before.headers.size - next.candidate.headers.size
                val expectedLeft =
                  if dropped == 0 then Vector.empty
                  else before.base +: before.headers.take(dropped - 1).map(_.point)
                val left = step match
                  case FetchCandidates.Announced.Step.Moved(_, l) => l == expectedLeft
                  case FetchCandidates.Announced.Step.Reset(_)    => false
                (
                  next,
                  seen,
                  prop && (next.candidate ==
                    FetchDecisionModel.trimmedTo(before, anchor, published)) :|
                    s"trim to $anchor before $published" &&
                    (!before.retains(published) || next.candidate.retains(published)) :|
                    "the published point is kept" &&
                    left :| "left points of a trim" &&
                    agrees(next, op.seed.toLong, op.kind.toLong)
                )
              case None =>
                val e = event(op, before, seen)
                val next = e match
                  case SyncEvent.RollForward(h, _)  => announced.forward(h, retained).announced
                  case SyncEvent.RollBackward(p, _) => announced.backward(p).announced
                val seenNext = e match
                  case SyncEvent.RollForward(h, _) => seen :+ h
                  case _                           => seen
                (
                  next,
                  seenNext,
                  prop && (next.candidate ==
                    FetchDecisionModel.candidateAfter(before, Vector(e), retained)) :| "step" &&
                    agrees(next, op.seed.toLong, op.kind.toLong)
                )
        }
        ._3
    }
  }

  /** One round on a streaming session's [[CandidateState]]: a ChainSync reply, our chain's anchor
    * moving on, the header gate's base moving back (a rollback into the local chain it captured),
    * the capacity wait's trim, a publisher's step of at most `limit` headers and the trim after it,
    * or a new intersection. Each trim reads the gate's base as it is.
    */
  private enum Streaming:
    case Reply(op: Op)
    case Anchor(seed: Int)
    case Back(seed: Int)
    case Trim
    case Publish(limit: Int)
    case Restart(seed: Int)

  private val streamings: Gen[Streaming] = Gen.frequency(
    8 -> ops.map(Streaming.Reply(_)),
    2 -> Gen.choose(0, 1 << 20).map(Streaming.Anchor(_)),
    1 -> Gen.choose(0, 1 << 20).map(Streaming.Back(_)),
    1 -> Gen.const(Streaming.Trim),
    4 -> Gen.choose(1, 4).map(Streaming.Publish(_)),
    1 -> Gen.choose(0, 1 << 20).map(Streaming.Restart(_))
  )

  /** What [[Streaming]] rounds leave: the session's state, the header gate's base, and the chain
    * the consumer built from every event published to it, from the origin.
    */
  private final case class Streamed(
      state: CandidateState,
      anchor: Point,
      consumer: Vector[Header],
      prop: Prop
  )

  /** The points of `candidate`, its base first. */
  private def pointsOf(candidate: ChainSelection.Candidate): Vector[Point] =
    candidate.base +: candidate.headers.map(_.point)

  /** The consumer's chain after `events`, or the point of a rollback it cannot follow. */
  private def followed(
      consumer: Vector[Header],
      events: Vector[SyncEvent]
  ): Either[Point, Vector[Header]] =
    events.foldLeft[Either[Point, Vector[Header]]](Right(consumer)) {
      case (chain, SyncEvent.RollForward(h, _)) => chain.map(_ :+ h)
      case (chain, SyncEvent.RollBackward(point, _)) =>
        chain.flatMap { headers =>
          if point == Point.Origin then Right(Vector.empty)
          else
            headers.lastIndexWhere(_.point == point) match
              case -1 => Left(point)
              case at => Right(headers.take(at + 1))
        }
    }

  property(
    "batches, trims and publisher steps on one candidate state carry its index and publish it whole"
  ) {
    forAll(Gen.listOfN(60, streamings), Gen.choose(1, 7)) { (script, retained) =>
      val tip = ChainSync.Tip.Origin
      val start =
        Streamed(
          CandidateState.initial.streaming,
          Point.Origin,
          Vector.empty,
          Prop.passed
        )
      def cursorOf(state: CandidateState) =
        state.publication.fold(state.candidate)(_.cursor)
      // a step, then the trim after it
      def publish(seen: Streamed, limit: Int): Streamed =
        seen.state.published(limit).map((next, events) => (next.trimmed(seen.anchor), events)) match
          case Left(divergence) => seen.copy(prop = seen.prop && (false :| s"$divergence"))
          case Right((next, events)) =>
            followed(seen.consumer, events) match
              case Left(point) =>
                seen.copy(prop = seen.prop && (false :| s"the consumer cannot roll back to $point"))
              case Right(consumer) =>
                val cursor = cursorOf(next)
                Streamed(
                  next,
                  seen.anchor,
                  consumer,
                  seen.prop &&
                    (consumer.lastOption.fold[Point](Point.Origin)(_.point) == cursor.tip) :|
                    "the consumer is at the cursor's tip" &&
                    (next.candidate == FetchDecisionModel.trimmedTo(
                      seen.state.candidate,
                      seen.anchor,
                      cursor.tip
                    )) :| "a step trims to our anchor, never past its cursor"
                )
      val end = script.foldLeft(start) { (seen, round) =>
        val state = seen.state
        val candidate = state.candidate
        round match
          case Streaming.Reply(op) if op.forward =>
            // the peer's next header: the main chain's while the candidate follows it, a fork's
            // otherwise, taken only while the window has room for it (the capacity wait)
            val last = candidate.headers.lastOption
            // the position on the main chain of the candidate's tip, -1 for the origin
            val onMain = last.fold(
              if candidate.base == Point.Origin then Some(-1)
              else Some(main.indexWhere(_.point == candidate.base)).filter(_ >= 0)
            )(h => Some(main.indexWhere(_.hash == h.hash)).filter(_ >= 0))
            val header = onMain
              .filter(i => op.kind % 4 != 0 && i + 1 < main.size)
              .fold(
                Fixtures.header(
                  last.fold(BigInt(1))(_.blockNo + 1),
                  last.fold(BigInt(1))(_.slot + 1),
                  last.map(_.hash).orElse(pointHash(candidate.base)),
                  op.seed
                )
              )(i => main(i + 1))
            if !Peer.publicationRoom(cursorOf(state), candidate, retained) then seen
            else
              val batch = Vector(SyncEvent.RollForward(header, tip))
              val (next, moved) =
                state.recorded(
                  batch,
                  retained,
                  Map(header.point -> Some(header.blockNo)),
                  Some(seen.anchor)
                )
              val expected = FetchDecisionModel.trimmedTo(
                FetchDecisionModel.candidateAfter(candidate, batch, retained),
                seen.anchor,
                cursorOf(state).tip
              )
              seen.copy(
                state = next,
                prop = seen.prop && (next.candidate == expected) :| "a batch, then a trim" &&
                  (moved.start == FetchCandidates.Derivation.Carried ||
                    state.track.announced.isEmpty) :| "the batch carried its index" &&
                  next.track.announced.exists(_.candidate eq next.candidate) :|
                  "the index is the candidate's" &&
                  next.track.announced.fold(Prop.falsified)(agrees(_, op.seed.toLong, 0L))
              )
          case Streaming.Reply(op) =>
            // a rollback the header gate takes: to a point at or after our anchor
            val points = pointsOf(candidate)
            val from = points.indexOf(seen.anchor).max(0)
            val point = points(from + op.seed % (points.size - from))
            val batch = Vector(SyncEvent.RollBackward(point, tip))
            val (next, moved) = state.recorded(batch, retained, Map.empty, Some(seen.anchor))
            seen.copy(
              state = next,
              prop = seen.prop && (moved.start == FetchCandidates.Derivation.Carried ||
                state.track.announced.isEmpty) :| "the rollback carried its index"
            )
          case Streaming.Anchor(seed) =>
            // our chain adopts a header of the main chain the candidate holds, past our anchor
            val ours = main.indexWhere(_.point == seen.anchor)
            val held =
              main.indices.filter(i => i > ours && pointsOf(candidate).contains(main(i).point))
            if held.isEmpty then seen else seen.copy(anchor = main(held(seed % held.size)).point)
          case Streaming.Back(seed) =>
            // the gate's base moves back along our chain, to the origin or a header before it
            val ours = main.indexWhere(_.point == seen.anchor)
            if ours < 0 then seen
            else
              seen.copy(anchor =
                main.lift(seed % (ours + 1) - 1).fold[Point](Point.Origin)(_.point)
              )
          case Streaming.Trim =>
            val next = state.trimmed(seen.anchor)
            seen.copy(
              state = next,
              prop = seen.prop && (next.candidate == FetchDecisionModel.trimmedTo(
                candidate,
                seen.anchor,
                cursorOf(state).tip
              )) :| "the capacity wait's trim"
            )
          case Streaming.Publish(limit) => publish(seen, limit)
          case Streaming.Restart(seed)  =>
            // a new intersection at a point of our chain the consumer has, at or after our anchor
            val known = Point.Origin +: seen.consumer.map(_.point)
            val from = known.indexOf(seen.anchor).max(0)
            val point = known(from + seed % (known.size - from))
            seen.copy(state =
              state.replaced(
                FetchCandidates.Announced.of(ChainSelection.Candidate(point, Vector.empty)),
                tip
              )
            )
      }
      // the publisher publishes all there is: the consumer ends at the candidate
      val drained = (0 until 100).foldLeft(end)((seen, _) => publish(seen, 4))
      drained.prop && (drained.consumer
        .map(_.point)
        .takeRight(drained.state.candidate.headers.size) ==
        drained.state.candidate.headers.map(_.point)) :| "the consumer holds the candidate" &&
      (drained.consumer.lastOption.fold[Point](Point.Origin)(_.point) ==
        drained.state.candidate.tip) :| "the consumer is at the candidate's tip"
    }
  }

  test("a rollback before the candidate's base is published as one, not a detached candidate") {
    val tip = ChainSync.Tip.Origin
    def forward(state: CandidateState, i: Int) =
      state
        .recorded(
          Vector(SyncEvent.RollForward(main(i), tip)),
          8,
          Map(main(i).point -> Some(main(i).blockNo)),
          None
        )
        ._1
    def published(state: CandidateState) =
      state.published(4).fold(e => fail(s"publication failed: $e"), identity)
    // the session intersected at the fifth header and published two more
    val intersected = CandidateState.initial.streaming.replaced(
      FetchCandidates.Announced.of(ChainSelection.Candidate(main(4).point, Vector.empty)),
      tip
    )
    val (sent, first) = published(forward(forward(intersected, 5), 6))
    // the first publication of the new epoch rolls back to the intersection
    assertEquals(
      first,
      SyncEvent.RollBackward(main(4).point, tip) +:
        Vector(main(5), main(6)).map(SyncEvent.RollForward(_, tip))
    )
    // the header gate holds the third header, of the local chain it captured before the
    // intersection, and takes the peer's rollback to it
    val (rolled, moved) = sent.recorded(
      Vector(SyncEvent.RollBackward(main(2).point, tip)),
      8,
      Map.empty,
      None
    )
    assertEquals(moved.left, None)
    val (again, back) = published(forward(rolled, 3))
    assertEquals(
      back,
      Vector(SyncEvent.RollBackward(main(2).point, tip), SyncEvent.RollForward(main(3), tip))
    )
    assertEquals(again.publication.map(_.cursor), Some(again.candidate))
  }

  /** One round of a session whose candidate is trimmed between batches. */
  private enum Trimming:
    case Batch(ops: List[Op])
    case Trim(anchor: Op, published: Op)
    case Offered(points: List[Int])
    case Replaced(base: Int, length: Int)

  private val trimmings: Gen[Trimming] = Gen.frequency(
    6 -> Gen.choose(0, 5).flatMap(Gen.listOfN(_, ops)).map(Trimming.Batch(_)),
    3 -> Gen.zip(ops, ops).map(Trimming.Trim(_, _)),
    1 -> Gen.listOf(Gen.choose(0, 59)).map(Trimming.Offered(_)),
    1 -> (for b <- Gen.choose(-1, 50); n <- Gen.choose(0, 9) yield Trimming.Replaced(b, n))
  )

  property("trims between batches leave the candidate and heights of the whole-candidate filter") {
    forAll(Gen.listOfN(30, trimmings), Gen.choose(0, 7)) { (script, retained) =>
      val start = Session(
        ChainSelection.Candidate(Point.Origin, Vector.empty),
        CandidateTrack.initial,
        CandidateTrack.initial.heights,
        Vector.empty
      )
      script
        .foldLeft((start, Prop.passed)) { case ((session, prop), round) =>
          round match
            case Trimming.Batch(codes) =>
              val (batch, seen) = codes.foldLeft((Vector.empty[SyncEvent], session.seen)) {
                case ((events, seen), op) =>
                  val now = FetchDecisionModel.candidateAfter(session.candidate, events, retained)
                  event(op, now, seen) match
                    case e @ SyncEvent.RollForward(h, _) => (events :+ e, seen :+ h)
                    case e                               => (events :+ e, seen)
              }
              val moved =
                CandidateTrack.move(session.track.announced, session.candidate, batch, retained)
              val received = batch.collect { case SyncEvent.RollForward(h, _) =>
                h.point -> Some(h.blockNo)
              }.toMap
              val candidate = FetchDecisionModel.candidateAfter(session.candidate, batch, retained)
              val heights = FetchDecisionModel.heightsAfter(session.heights, batch, candidate)
              val track = session.track.recorded(moved, received)
              (
                Session(moved.announced.candidate, track, heights, seen),
                prop && (moved.announced.candidate == candidate) :| "candidate" &&
                  (track.heights == heights) :| s"heights after $batch"
              )
            case Trimming.Trim(anchorCode, publishedCode) =>
              val before = session.candidate
              val anchor = pointFor(anchorCode, before, session.seen)
              val published = pointFor(publishedCode, before, session.seen)
              val expected = FetchDecisionModel.trimmedTo(before, anchor, published)
              // the heights of the points the trim dropped leave; the next batch filters the rest
              val was = before.headers.map(_.point).toSet + before.base
              val is = expected.headers.map(_.point).toSet + expected.base
              val heights =
                session.heights.filterNot((p, _) => was(p) && !is(p) && p != Point.Origin)
              session.track.trimmed(before, anchor, published) match
                case Some(trimmed) =>
                  (
                    Session(trimmed.candidate, trimmed.track, heights, session.seen),
                    prop && (trimmed.candidate == expected) :| s"trim to $anchor" &&
                      (trimmed.track.announced.map(_.candidate) == Some(trimmed.candidate)) :|
                      "the track is the trimmed candidate's" &&
                      (trimmed.track.heights == heights) :| "heights after a trim" &&
                      trimmed.track.announced.fold(Prop.falsified)(agrees(_, 0L, 0L))
                  )
                case None =>
                  // nothing to drop, or a track that is not the candidate's: the candidate stays
                  (
                    session,
                    prop && (session.track.announced.forall(_.candidate ne before) ||
                      expected == before) :| s"a trim to $anchor was not taken"
                  )
            case Trimming.Offered(indices) =>
              val offered =
                indices.map(i => main(i).point -> Some(main(i).blockNo)).toMap +
                  (Point.Origin -> None)
              (session.copy(track = session.track.offered(offered), heights = offered), prop)
            case Trimming.Replaced(base, length) =>
              val candidate = ChainSelection.Candidate(
                main.lift(base).fold(Point.Origin)(_.point),
                main.slice(base + 1, base + 1 + length)
              )
              (session.copy(candidate = candidate), prop)
        }
        ._2
    }
  }

  property(
    "publication from a candidate trimmed to our anchor, or moved any other way, is the model's"
  ) {
    forAll(
      Gen.choose(0, 40),
      Gen.choose(0, 40),
      Gen.choose(0, 12),
      Gen.choose(0, 12),
      Gen.choose(0, 6),
      Gen.choose(1, 4),
      Gen.choose(0, 3)
    ) { (publishedFrom, publishedLength, trimAt, grow, forkAt, limit, shape) =>
      // the published cursor: a stretch of the main chain; the latest candidate: the same chain
      // trimmed, grown, rolled back onto a fork, or unrelated
      val previous = ChainSelection.Candidate(
        main.lift(publishedFrom - 1).fold(Point.Origin)(_.point),
        main.slice(publishedFrom, publishedFrom + publishedLength)
      )
      val end = (publishedFrom + publishedLength + grow).min(main.size)
      val latest = shape match
        case 0 =>
          ChainSelection.Candidate(
            main(publishedFrom + trimAt).point,
            main.slice(publishedFrom + trimAt + 1, end)
          )
        case 1 => ChainSelection.Candidate(previous.base, main.slice(publishedFrom, end))
        case 2 =>
          val kept = main.slice(publishedFrom, publishedFrom + forkAt.min(publishedLength))
          val fork = kept.lastOption.fold(Vector.empty[Header])(last =>
            Fixtures.chain(grow, salt = 2, from = Some(last))
          )
          val point = main(publishedFrom + trimAt.min(forkAt)).point
          FetchDecisionModel.trimmedTo(
            ChainSelection.Candidate(previous.base, kept ++ fork),
            point,
            point
          )
        case _ => ChainSelection.Candidate(main(55).point, main.slice(56, 58))
      val got = Peer.publication(previous, latest, ChainSync.Tip.Origin, limit)
      val expected = FetchDecisionModel.publication(previous, latest, ChainSync.Tip.Origin, limit)
      (got == expected) :| s"$previous -> $latest"
    }
  }

  test("an older graft after a trim is published as a deeper rollback and a replay to its tip") {
    // published to D, then trimmed to C: the cursor no longer holds A or B
    val chain = main.take(4)
    val (a, b, c, d) = (chain(0), chain(1), chain(2), chain(3))
    val untrimmed = ChainSelection.Candidate(Point.Origin, Vector(a, b, c, d))
    val trimmed = FetchDecisionModel.trimmedTo(untrimmed, c.point, d.point)
    assertEquals(trimmed, ChainSelection.Candidate(c.point, Vector(d)))
    // an accepted, older checkpoint is grafted: Origin, A, B
    val graft = ChainSelection.Candidate(Point.Origin, Vector(a, b))
    val tip = ChainSync.Tip.Origin
    // from the untrimmed cursor the publication rolls back to B
    assertEquals(
      Peer.publication(untrimmed, graft, tip, 8).map(_._2),
      Right(Vector(SyncEvent.RollBackward(b.point, tip)))
    )
    // from the trimmed one it finds no common point; the new epoch's fallback publishes from the
    // graft's base: a rollback to Origin and A, B again, to the same tip
    assertEquals(
      Peer.publication(trimmed, graft, tip, 8).left.map(_ => ()),
      Left(())
    )
    assertEquals(
      Peer
        .publication(ChainSelection.Candidate(graft.base, Vector.empty), graft, tip, 8)
        .map((after, events) => (after.tip, SyncEvent.RollBackward(graft.base, tip) +: events)),
      Right(
        (
          b.point,
          Vector(
            SyncEvent.RollBackward(Point.Origin, tip),
            SyncEvent.RollForward(a, tip),
            SyncEvent.RollForward(b, tip)
          )
        )
      )
    )
  }
