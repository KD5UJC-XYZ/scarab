// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.selection

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.*
import scala.concurrent.duration.*

/** The peer metrics board against the properties of the reference
  * (`ouroboros-network/tests/lib/Test/Ouroboros/Network/PeerSelection/PeerMetric.hs`:
  * `prop_insert_peer` 238, `prop_metrics_are_bounded` 314, `prop_bounded_size` 388), the examples
  * of the rules in `PeerMetric.hs` itself, and the hot-demotion pick that reads the scores
  * (`Policies.hs` 104-143).
  */
class PeerMetricsSuite extends ScalaCheckSuite:

  /** A report of the script of the reference: a header or a block of a peer at a slot. */
  private enum Report(val peer: Int, val slot: Long):
    case HeaderOf(who: Int, at: Long) extends Report(who, at)
    case BlockOf(who: Int, at: Long, size: Long) extends Report(who, at)

  /** One slot takes one second, as in `mkTimedScript` (151). */
  private def at(slot: Long): Time = slot.seconds

  private def step(m: PeerMetrics[Int], r: Report): PeerMetrics[Int] = r match
    case Report.HeaderOf(p, s)      => m.reportHeader(p, s, at(s))
    case Report.BlockOf(p, s, size) => m.reportFetch(p, s, size, at(s))

  /** `Arbitrary Event` (80-105) and `mkFixedScript` (116): ordered by slot, over at most 20 peers.
    */
  private val script: Gen[List[Report]] =
    val one = for
      peer <- Gen.choose(1, 20)
      slot <- Gen.choose(1L, 360L)
      report <- Gen.oneOf(
        Gen.const(Report.HeaderOf(peer, slot)),
        Gen.choose(1L, 2000000L).map(Report.BlockOf(peer, slot, _))
      )
    yield report
    Gen.choose(101, 360).flatMap(Gen.listOfN(_, one)).map(_.sortBy(_.slot))

  private def run(reports: List[Report], limit: Int = PeerMetrics.MaxEntries) =
    reports.scanLeft(PeerMetrics.empty[Int](limit))(step)

  // ------------------------------------------------------------------ the properties of the reference

  property("prop_insert_peer: a newly added peer is never in the 20% worst (with five results)") {
    forAll(script) { reports =>
      val states = run(reports)
      val firstSlot = reports.head.slot
      reports.indices.filter(i => reports(i).slot > firstSlot + 90).forall { i =>
        val (before, after, peer) = (states(i), states(i + 1), reports(i).peer)
        val known = before.upstreamness.contains(peer) || before.fetchynessBlocks.contains(peer) ||
          before.fetchynessBytes.contains(peer)
        // the reference sorts `(score, joined slot)` and looks at the worst fifth
        def fine(scores: Map[Int, Long]): Boolean =
          val ranked = scores.toList
            .map((p, v) => (p, (v, after.joinedAt.get(p))))
            .sortBy(_._2)(using
              Ordering.Tuple2(using Ordering.Long, Ordering.Option(using Ordering.Long))
            )
          val worst = ranked.take(scores.size / 5).map(_._1).toSet
          worst.size < 5 || !worst.contains(peer)
        known || List(
          after.upstreamness.view.mapValues(_.toLong).toMap,
          after.fetchynessBlocks.view.mapValues(_.toLong).toMap,
          after.fetchynessBytes
        ).forall(fine)
      }
    }
  }

  property("prop_metrics_are_bounded: counts are never negative and bytes never exceed the total") {
    forAll(script) { reports =>
      val totalBytes = reports
        .collect { case Report.BlockOf(p, _, size) => p -> size }
        .groupMapReduce(_._1)(_._2)(_ + _)
      val bound = 2 * (if totalBytes.isEmpty then 0L else totalBytes.values.max)
      run(reports).forall { m =>
        m.upstreamness.values.forall(_ >= 0) && m.fetchynessBlocks.values.forall(_ >= 0) &&
        m.fetchynessBytes.values.forall(b => b >= 0 && b <= bound)
      }
    }
  }

  property("prop_bounded_size: the results have at most limit times the peers entries") {
    forAll(Gen.choose(1, 400), script) { (limit, reports) =>
      val peers = reports.map(_.peer).distinct.size
      run(reports, limit).forall { m =>
        m.upstreamness.size <= limit * peers && m.fetchynessBlocks.size <= limit * peers &&
        m.fetchynessBytes.size <= limit * peers
      }
    }
  }

  // ------------------------------------------------------------------ the rules of metricsTracer

  property("the boards never hold more than 180 slots, and the counts add up to the board") {
    forAll(script) { reports =>
      run(reports).forall { m =>
        val (headers, blocks) = m.sizes
        headers <= 180 && blocks <= 180 &&
        m.upstreamness.values.sum == headers && m.fetchynessBlocks.values.sum == blocks
      }
    }
  }

  test("the earliest arrival of a slot wins, a later one changes nothing (367-396)") {
    val m = PeerMetrics
      .empty[String]()
      .reportHeader("a", 10, 5.seconds)
      .reportHeader("b", 10, 3.seconds)
      .reportHeader("c", 10, 4.seconds)
    assertEquals(m.upstreamness.filter(_._2 > 0), Map("b" -> 1))
  }

  test("a slot below all 180 of a full board is dropped, and one above evicts the smallest") {
    val full = (1L to 180L).foldLeft(PeerMetrics.empty[String]())((m, s) =>
      m.reportHeader("a", s + 100, s.seconds)
    )
    assertEquals(full.sizes._1, 180)
    val older = full.reportHeader("b", 5, 999.seconds)
    assertEquals(older.upstreamness("a"), 180)
    assertEquals(older.upstreamness.getOrElse("b", 0), 0)
    val newer = full.reportHeader("b", 1000, 999.seconds)
    assertEquals(newer.sizes._1, 180)
    assertEquals(newer.upstreamness("b"), 1)
    assertEquals(newer.upstreamness("a"), 179)
  }

  test("blocks have a board of their own and count separately") {
    val m = PeerMetrics
      .empty[String]()
      .reportHeader("a", 1, 1.second)
      .reportFetch("b", 1, 70000, 2.seconds)
      .reportFetch("a", 2, 30000, 3.seconds)
    assertEquals(m.upstreamness.filter(_._2 > 0), Map("a" -> 1))
    assertEquals(m.fetchynessBlocks.filter(_._2 > 0), Map("b" -> 1, "a" -> 1))
    assertEquals(m.fetchynessBytes, Map("a" -> 30000L, "b" -> 70000L))
  }

  test(
    "the join-time correction is zero (adjustAvg _ _ _ _ _ = 0, 599): a late peer is not credited"
  ) {
    val early = (1L to 100L).foldLeft(PeerMetrics.empty[String]())((m, s) =>
      m.reportHeader("old", s, s.seconds)
    )
    val late = early.reportHeader("new", 101, 101.seconds)
    // one slot to its name, not an average of the board
    assertEquals(late.upstreamness("new"), 1)
    assertEquals(late.upstreamness("old"), 100)
  }

  test(
    "a peer is registered at the slot of its first report, and again after a whole board without it"
  ) {
    // the board's oldest slot is 1: the reference drops registry entries at or below it
    val a = PeerMetrics
      .empty[String]()
      .reportHeader("q", 1, 1.second)
      .reportFetch("q", 1, 1, 1.second)
      .reportHeader("p", 7, 7.seconds)
      .reportFetch("p", 7, 1, 7.seconds)
    assertEquals(a.joinedAt("p"), 7L)
    val b = a.reportHeader("p", 9, 9.seconds)
    assertEquals(b.joinedAt("p"), 7L)
    // 200 slots by another peer push both boards past p's slots: p is new when it returns
    val c = (10L to 210L).foldLeft(b)((m, s) =>
      m.reportHeader("q", s, s.seconds).reportFetch("q", s, 1, s.seconds)
    )
    assertEquals(c.joinedAt.get("p"), None)
    assertEquals(c.reportHeader("p", 300, 300.seconds).joinedAt("p"), 300L)
  }

  // ------------------------------------------------------------------ the hot-demotion pick

  test("hot demotion takes the lowest score first, then the earliest joined, then the draw") {
    val m = PeerMetrics
      .empty[Int]()
      .reportHeader(1, 10, 1.second)
      .reportHeader(1, 11, 2.seconds)
      .reportFetch(2, 10, 1, 1.second)
      .reportHeader(3, 12, 3.seconds)
      .reportHeader(4, 13, 4.seconds)
    val scores = m.hotScores
    // 1 scores 2, 2 scores 1, 3 and 4 score 1 and joined later than 2
    assertEquals(scores(1).value, 2)
    assertEquals(scores(2).value, 1)
    val candidates = scala.collection.immutable.SortedSet(1, 2, 3, 4, 5)
    val (picked, _) =
      Picks.hotDemotion(candidates, 5, Rng(1), p => scores.getOrElse(p, HotScore.Unknown))
    // 5 is unknown to the metrics and goes first; then by score, ties by the slot of joining
    assertEquals(picked, List(5, 2, 3, 4, 1))
  }

  property("a lower score is never demoted after a higher one") {
    forAll(script, Gen.choose(0L, 1000L)) { (reports, seed) =>
      val m = run(reports).last
      val scores = m.hotScores
      val peers = scala.collection.immutable.SortedSet.from(scores.keys)
      val (picked, _) = Picks.hotDemotion(peers, peers.size, Rng(seed), scores)
      picked.map(scores).sliding(2).forall {
        case List(a, b) => Ordering[HotScore].lteq(a, b)
        case _          => true
      }
    }
  }

  test(
    "the board, the scores and the pick need no deep stack for a long run of reports (ADR 0011)"
  ) {
    val result = new java.util.concurrent.atomic.AtomicReference[Either[Throwable, (Int, Int)]]
    val worker = new Thread(
      null,
      () =>
        result.set(
          scala.util.Try {
            // 200,000 headers and blocks of 1,000 peers in one batch each
            val slots = (1L to 200000L)
            val m = PeerMetrics
              .empty[Int]()
              .reportHeaders(1, slots, 1.second)
              .reportFetches(2, slots.map(_ -> 1000L), 1.second)
            val scores = m.hotScores
            val peers = scala.collection.immutable.SortedSet.from(1 to 1000)
            val (picked, _) =
              Picks.hotDemotion(peers, 500, Rng(3L), p => scores.getOrElse(p, HotScore.Unknown))
            (m.sizes._1 + m.sizes._2, picked.size)
          }.toEither
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    result.get match
      case Right((size, picked)) =>
        assertEquals(size, 360)
        assertEquals(picked, 500)
      case Left(e) => fail(s"the metrics need a deep stack: $e")
  }
