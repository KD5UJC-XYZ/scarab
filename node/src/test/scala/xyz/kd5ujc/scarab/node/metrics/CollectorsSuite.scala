// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import xyz.kd5ujc.scarab.ledger.TestGeometry
import cats.effect.{IO, Ref}
import cats.mtl.Handle
import cats.syntax.all.*
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{Anchor, BlockRules, SchedulingStats, VolatileChain}
import xyz.kd5ujc.scarab.ledger.{
  BlockFailure,
  BlockRule,
  EpochGeometry,
  Fixtures,
  NodeError,
  ValidationError
}
import xyz.kd5ujc.scarab.node.RefStateful
import xyz.kd5ujc.scarab.node.forge.ForgeStats

/** The collectors read the sources that already hold the state, and the wrapped rules count what
  * passes through them.
  */
class CollectorsSuite extends CatsEffectSuite:
  private val geometry =
    EpochGeometry(
      java.time.Instant.EPOCH,
      1000,
      epochLength = 100,
      securityParam = TestGeometry.k(3),
      activeSlotsCoeff = TestGeometry.f("0.5")
    )

  private def counter[L](s: Snapshot, d: CounterDef[L], l: L): Option[Double] =
    s.families
      .get(d.name)
      .collect { case f: Family.Counter => f.series.get(d.labels.values(l)) }
      .flatten
  private def gauge[L](s: Snapshot, d: GaugeDef[L], l: L): Option[Double] =
    s.families
      .get(d.name)
      .collect { case f: Family.Gauge => f.series.get(d.labels.values(l)) }
      .flatten

  private val registry = Metrics.create[IO](Catalogue.all)

  test("the chain collector reads the tip, the epoch, the slot in it and the density") {
    // five blocks at slots 1 to 5 on a chain that keeps three: the anchor is the second block
    val headers = Fixtures.chain(5)
    val grown = headers.foldLeft(VolatileChain[Int](3, Anchor(None, 0), Vector.empty)) { (c, h) =>
      c.extend(h, 0).fold(e => fail(e.toString), _._1)
    }
    for
      metrics <- registry
      ref <- Ref.of[IO, VolatileChain[Int]](grown)
      s <- {
        given Metrics[IO] = metrics
        given cats.mtl.Stateful[IO, VolatileChain[Int]] = RefStateful(ref)
        Collectors.chain[IO, Int](_ => geometry) *> metrics.snapshot
      }
    yield
      assertEquals(gauge(s, Catalogue.blockNum, ()), Some(4.0))
      assertEquals(gauge(s, Catalogue.slotNum, ()), Some(5.0))
      assertEquals(gauge(s, Catalogue.epoch, ()), Some(0.0))
      assertEquals(gauge(s, Catalogue.slotInEpoch, ()), Some(5.0))
      assertEquals(gauge(s, Catalogue.volatileBlocks, ()), Some(3.0))
      // three blocks over the three slots from the anchor (slot 2) to the tip (slot 5)
      assertEquals(gauge(s, Catalogue.density, ()), Some(1.0))
  }

  test("an empty chain is at zero") {
    for
      metrics <- registry
      ref <- Ref.of[IO, VolatileChain[Int]](VolatileChain[Int](3, Anchor(None, 0), Vector.empty))
      s <- {
        given Metrics[IO] = metrics
        given cats.mtl.Stateful[IO, VolatileChain[Int]] = RefStateful(ref)
        Collectors.chain[IO, Int](_ => geometry) *> metrics.snapshot
      }
    yield
      assertEquals(gauge(s, Catalogue.blockNum, ()), Some(0.0))
      assertEquals(gauge(s, Catalogue.density, ()), Some(0.0))
  }

  test("the scheduling collector reports the totals of the run, and again changes nothing") {
    for
      metrics <- registry
      stats <- SchedulingStats.create[IO]
      _ <- stats.timer(10) *> stats.timer(60) *> stats.timer(120)
      _ <- stats.step("a", 300000000L) *> stats.step("b", 50000000L)
      s <- {
        given Metrics[IO] = metrics
        Collectors.scheduling(stats) *> Collectors.scheduling(stats) *> metrics.snapshot
      }
    yield
      assertEquals(counter(s, Catalogue.timerSamples, ()), Some(3.0))
      assertEquals(counter(s, Catalogue.timersLate, LatenessLabel.Over50Ms), Some(2.0))
      assertEquals(counter(s, Catalogue.timersLate, LatenessLabel.Over100Ms), Some(1.0))
      assertEquals(gauge(s, Catalogue.timerLatenessMax, ()), Some(0.12))
      assertEquals(counter(s, Catalogue.cpuSteps, ()), Some(2.0))
      assertEquals(gauge(s, Catalogue.cpuLongestStep, ()), Some(0.3))
  }

  test(
    "the forging collector gives the counters of the loop's traces under the reference's names"
  ) {
    import xyz.kd5ujc.scarab.node.forge.{CannotForge, ForgeTrace, OutsideForecastRange}
    val origin = xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin
    val hash = xyz.kd5ujc.scarab.codec.Bytes(Vector(1))
    val traces = List(
      ForgeTrace.StartLeadershipCheck(10),
      ForgeTrace.NodeNotLeader(10),
      ForgeTrace.StartLeadershipCheck(11),
      ForgeTrace.NodeIsLeader(11),
      ForgeTrace.ForgedBlock(11, origin, hash, 0, None),
      ForgeTrace.AdoptedBlock(11, hash, 100, Vector.empty),
      ForgeTrace.StartLeadershipCheck(12),
      ForgeTrace.NodeCannotForge(12, CannotForge.KeyNotUsableYet(0, 1)),
      ForgeTrace.StartLeadershipCheck(13),
      ForgeTrace.SlotIsImmutable(13, origin, 0),
      ForgeTrace.StartLeadershipCheck(14),
      ForgeTrace.BlockFromFuture(14, 15),
      ForgeTrace.StartLeadershipCheck(15),
      ForgeTrace.NoLedgerView(15, OutsideForecastRange(None, 15, 15)),
      ForgeTrace.StartLeadershipCheck(16),
      ForgeTrace.NodeIsLeader(16),
      ForgeTrace.ForgedBlock(16, origin, hash, 1, Some(hash)),
      ForgeTrace.DidntAdoptBlock(16, hash),
      ForgeTrace.StartLeadershipCheck(18),
      ForgeTrace.NodeNotLeader(18),
      ForgeTrace.StartLeadershipCheck(19)
    )
    for
      metrics <- registry
      stats <- ForgeStats.create[IO]
      _ <- traces.traverse_(stats.record(0, _))
      _ <- stats.replaced(0) *> stats.failed(0)
      s <- {
        given Metrics[IO] = metrics
        Collectors.forging(stats) *> metrics.snapshot
      }
    yield
      assertEquals(counter(s, Catalogue.forgeAboutToLead, ()), Some(9.0))
      assertEquals(counter(s, Catalogue.forgeSlotIsImmutable, ()), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeBlockFromFuture, ()), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeNodeIsLeader, ()), Some(2.0))
      assertEquals(counter(s, Catalogue.forgeNodeNotLeader, ()), Some(2.0))
      assertEquals(counter(s, Catalogue.forgeForged, ()), Some(2.0))
      assertEquals(counter(s, Catalogue.forgeAdopted, ()), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeDidntAdopt, ()), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeReplaced, ()), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeFailed, ()), Some(1.0))
      // ForgingStats as of the start of slot 19: the not-led slot 18 after the leader slot 16
      // counts 18 - 16 = 2 missed
      assertEquals(gauge(s, Catalogue.nodeCannotForge, ()), Some(1.0))
      assertEquals(gauge(s, Catalogue.nodeIsLeader, ()), Some(2.0))
      assertEquals(gauge(s, Catalogue.blocksForged, ()), Some(2.0))
      assertEquals(gauge(s, Catalogue.slotsMissed, ()), Some(2.0))
      assertEquals(counter(s, Catalogue.forgeSkipped, SkipLabel.NoLedgerView), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeSkipped, SkipLabel.SlotIsImmutable), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeSkipped, SkipLabel.BlockFromFuture), Some(1.0))
      assertEquals(counter(s, Catalogue.forgeSkipped, SkipLabel.Other), Some(0.0))
  }

  test("the JVM collector reads the memory, the threads and the process") {
    for
      metrics <- registry
      s <- {
        given Metrics[IO] = metrics
        Collectors.jvm[IO] *> metrics.snapshot
      }
    yield
      assert(gauge(s, Catalogue.jvmMemoryUsed, AreaLabel.Heap).exists(_ > 0))
      assert(gauge(s, Catalogue.jvmThreads, ()).exists(_ > 0))
      assert(gauge(s, Catalogue.processStart, ()).exists(_ > 1e9))
      assert(counter(s, Catalogue.processCpuSeconds, ()).exists(_ >= 0))
  }

  test("a scrape runs the collectors and renders text that is well formed") {
    for
      metrics <- registry
      text <- metrics.scrape(
        List(metrics.set(Catalogue.blockNum, 7.0), metrics.inc(Catalogue.rollbacks))
      )
    yield
      val parsed = TextFormat.parse(text).fold(e => fail(e.toString), identity)
      assert(
        parsed.samples.exists(s => s.name == "cardano_node_metrics_blockNum_int" && s.value == 7.0)
      )
      assert(parsed.samples.exists(s => s.name == "scarab_rollbacks_total" && s.value == 1.0))
  }

  // ---- the rules, measured

  private val header = Fixtures.header(1, 1, None)

  private def accepted: BlockRules[IO, Int] = BlockRules.from((state, _, _) => IO.pure(state + 1))
  private def refusing(failure: BlockFailure): BlockRules[IO, Int] =
    BlockRules.from((_, header, _) =>
      cats.mtl.Raise[IO, ValidationError].raise(ValidationError(header.point, failure))
    )

  private def withHandle[A](program: Handle[IO, NodeError] ?=> IO[A]): IO[Either[NodeError, A]] =
    Handle.allow[NodeError](program.map(_.asRight[NodeError])).rescue(e => IO.pure(e.asLeft[A]))

  test("measured rules pass the new state through and observe how long it took") {
    registry.flatMap { metrics =>
      given Metrics[IO] = metrics
      for
        result <- withHandle {
          val metered = MeteredRules[IO, Int](accepted)
          metered(0, header, Bytes.empty)
        }
        s <- metrics.snapshot
      yield
        assertEquals(result, Right(1))
        s.families(Catalogue.blockApplySeconds.name) match
          case h: Family.Histogram => assertEquals(h.series(Nil).count, 1L)
          case other               => fail(s"not a histogram: $other")
        assertEquals(counter(s, Catalogue.validationErrors, BlockRule.Structure), None)
    }
  }

  test("measured rules count a refusal under its rule family and raise the same error") {
    registry.flatMap { metrics =>
      given Metrics[IO] = metrics
      for
        result <- withHandle {
          val metered = MeteredRules[IO, Int](refusing(BlockFailure.BodyCommitment))
          metered(0, header, Bytes.empty)
        }
        s <- metrics.snapshot
      yield
        assertEquals(result, Left(ValidationError(header.point, BlockFailure.BodyCommitment)))
        assertEquals(counter(s, Catalogue.validationErrors, BlockRule.Structure), Some(1.0))
        assertEquals(counter(s, Catalogue.validationErrors, BlockRule.Ledger), None)
    }
  }
