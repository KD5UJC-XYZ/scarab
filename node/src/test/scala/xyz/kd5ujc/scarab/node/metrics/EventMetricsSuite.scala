// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent}
import xyz.kd5ujc.scarab.network.{ConnectionEnd, ConnectionEvent}
import xyz.kd5ujc.scarab.ledger.PeerError
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.network.selection.{Churn, FetchMode}
import xyz.kd5ujc.scarab.node.{HeaderGate, InboundEvent, InboundGovernor, Output, Peer}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.forge.{ForgeEvent, ForgeTrace, KesEvolutionError, KesInfo}
import xyz.kd5ujc.scarab.node.mempool.{MempoolEvent, MempoolEvents, Rejection}
import xyz.kd5ujc.scarab.protocol.{ChainSync, ConnectionState, Era}
import xyz.kd5ujc.scarab.storage.{ImmutableStore, StorageEvent}

/** Each event becomes the measurements the documentation lists for it. */
class EventMetricsSuite extends CatsEffectSuite:
  private val hash = Bytes.fromHex("ab" * 32).fold(e => fail(e.toString), identity)
  private def block(no: Int): BlockRef = BlockRef(BigInt(no) * 10, hash, BigInt(no))
  private def tip(slot: Int, no: Int): ChainSync.Tip =
    ChainSync.Tip(
      ChainSync.Point.Block(ChainSync.UInt64.saturating(slot), hash),
      ChainSync.UInt64.saturating(no)
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
  private def observations[L](s: Snapshot, d: HistogramDef[L], l: L): Option[Long] =
    s.families
      .get(d.name)
      .collect { case f: Family.Histogram => f.series.get(d.labels.values(l)).map(_.count) }
      .flatten

  private val fixture =
    for
      metrics <- Metrics.create[IO](Catalogue.all)
      measured <- EventMetrics.create[IO](using summon, metrics)
    yield (metrics, measured)

  test("an applied block counts under its era, sets the era gauge and the peer's tip") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.follower.emit(FollowerEvent.Extended(block(1), 0, tip(5000, 400), Era.Babbage))
        _ <- em.follower.emit(FollowerEvent.Extended(block(2), 0, tip(5010, 401), Era.Conway))
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.blocksApplied, Era.Babbage), Some(1.0))
        assertEquals(counter(s, Catalogue.blocksApplied, Era.Conway), Some(1.0))
        assertEquals(gauge(s, Catalogue.currentEra, Era.Conway), Some(1.0))
        assertEquals(gauge(s, Catalogue.currentEra, Era.Babbage), Some(0.0))
        assertEquals(gauge(s, Catalogue.peerTipSlot, ()), Some(5010.0))
        assertEquals(gauge(s, Catalogue.peerTipBlock, ()), Some(401.0))
    }
  }

  test(
    "the start moves restoring, replaying, following; the replay gauges follow the storage events"
  ) {
    fixture.flatMap { (metrics, em) =>
      def phases(s: Snapshot) =
        PhaseLabel.values.toList.map(p => p -> gauge(s, Catalogue.nodePhase, p)).toMap
      for
        s0 <- metrics.snapshot
        _ <- em.storage.emit(StorageEvent.ReplayStarted(None, Some(block(900))))
        s1 <- metrics.snapshot
        _ <- em.storage.emit(StorageEvent.ReplayProgress(block(300), 300L, 4000L))
        s2 <- metrics.snapshot
        _ <- em.storage.emit(StorageEvent.ReplayFinished(Some(block(900)), 900L, 9000L))
        _ <- em.phase(PhaseLabel.Following)
        s3 <- metrics.snapshot
      yield
        // before the first event: restoring, and no replay gauge (absent, not zero)
        assertEquals(phases(s0)(PhaseLabel.Restoring), Some(1.0))
        assertEquals(gauge(s0, Catalogue.replayTargetBlock, ()), None)
        assertEquals(gauge(s0, Catalogue.replayBlock, ()), None)
        assertEquals(phases(s1)(PhaseLabel.Replaying), Some(1.0))
        assertEquals(phases(s1)(PhaseLabel.Restoring), Some(0.0))
        assertEquals(gauge(s1, Catalogue.replayTargetBlock, ()), Some(900.0))
        assertEquals(gauge(s1, Catalogue.replayTargetSlot, ()), Some(9000.0))
        assertEquals(gauge(s1, Catalogue.replayBlock, ()), None)
        assertEquals(gauge(s2, Catalogue.replayBlock, ()), Some(300.0))
        assertEquals(gauge(s2, Catalogue.replaySlot, ()), Some(3000.0))
        assertEquals(gauge(s2, Catalogue.replayedBlocks, ()), Some(300.0))
        assertEquals(gauge(s3, Catalogue.replayBlock, ()), Some(900.0))
        assertEquals(phases(s3)(PhaseLabel.Following), Some(1.0))
        assertEquals(phases(s3)(PhaseLabel.Replaying), Some(0.0))
    }
  }

  test("a rollback counts as a fork and its depth is observed") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.follower.emit(
          FollowerEvent.RolledBack(ChainSync.Point.Origin, ChainSync.Point.Origin, 3)
        )
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.forks, ()), Some(1.0))
        assertEquals(counter(s, Catalogue.rollbacks, ()), Some(1.0))
        assertEquals(observations(s, Catalogue.rollbackDepth, ()), Some(1L))
    }
  }

  test("the immutable blocks start from what the store holds and grow with each block moved") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.storage.emit(
          StorageEvent.ImmutableOpened(ImmutableStore.OpenReport(None, 100L, 1, 0, 0L))
        )
        _ <- em.follower.emit(FollowerEvent.Immutable(block(1)))
        _ <- em.follower.emit(FollowerEvent.Immutable(block(2)))
        s <- metrics.snapshot
      yield assertEquals(gauge(s, Catalogue.immutableBlocks, ()), Some(102.0))
    }
  }

  test("the forge state gives the KES gauges as the reference's StateInfo does") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.forge.emit(ForgeEvent.Started(hash, hash, 10, 62, 2, 12))
        started <- metrics.snapshot
        _ <- em.forge.emit(ForgeEvent.StateInfo(KesInfo(10, 72, 5)))
        s <- metrics.snapshot
      yield
        assertEquals(gauge(started, Catalogue.forgingEnabled, ()), Some(1.0))
        assertEquals(gauge(started, Catalogue.opCertCounter, ()), Some(2.0))
        // nothing of the key until the forge state is first updated in a slot
        assertEquals(gauge(started, Catalogue.currentKesPeriod, ()), None)
        assertEquals(gauge(s, Catalogue.opCertStartKesPeriod, ()), Some(10.0))
        assertEquals(gauge(s, Catalogue.opCertExpiryKesPeriod, ()), Some(72.0))
        assertEquals(gauge(s, Catalogue.currentKesPeriod, ()), Some(15.0))
        assertEquals(gauge(s, Catalogue.remainingKesPeriods, ()), Some(57.0))
    }
  }

  test("a failed forge state update zeroes the current and remaining KES periods") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.forge.emit(ForgeEvent.StateInfo(KesInfo(10, 72, 61)))
        _ <- em.forge.emit(
          ForgeEvent.Loop(
            ForgeTrace.ForgeStateUpdateError(
              7200,
              KesEvolutionError.CouldNotEvolve(KesInfo(10, 72, 61), 72)
            )
          )
        )
        s <- metrics.snapshot
      yield
        assertEquals(gauge(s, Catalogue.opCertStartKesPeriod, ()), Some(10.0))
        assertEquals(gauge(s, Catalogue.opCertExpiryKesPeriod, ()), Some(72.0))
        assertEquals(gauge(s, Catalogue.currentKesPeriod, ()), Some(0.0))
        assertEquals(gauge(s, Catalogue.remainingKesPeriods, ()), Some(0.0))
    }
  }

  test("mempool rejections are counted by reason, additions as processed transactions") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.mempool.emit(MempoolEvent.Added(hash, 100, 1, 100L))
        _ <- em.mempool.emit(
          MempoolEvent.Rejected(
            None,
            Rejection.Malformed(
              xyz.kd5ujc.scarab.ledger.TransactionDecodeProblem
                .Syntax(xyz.kd5ujc.scarab.codec.Cbor.Error.UnexpectedEnd)
            )
          )
        )
        _ <- em.mempool.emit(
          MempoolEvent.Rejected(
            Some(hash),
            Rejection.Full(TxMeasure(10, 0, 0, 0), TxMeasure(5, 0, 0, 0))
          )
        )
        _ <- em.mempool.emit(
          MempoolEvent.Rejected(
            Some(hash),
            Rejection.Full(TxMeasure(10, 0, 0, 0), TxMeasure(5, 0, 0, 0))
          )
        )
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.txsProcessed, ()), Some(1.0))
        assertEquals(counter(s, Catalogue.mempoolRejected, RejectionLabel.Malformed), Some(1.0))
        assertEquals(counter(s, Catalogue.mempoolRejected, RejectionLabel.Full), Some(2.0))
    }
  }

  test("a transaction removed on request is counted once") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.mempool.emit(MempoolEvent.Removed(hash))
        s <- metrics.snapshot
      yield assertEquals(counter(s, Catalogue.mempoolRemoved, ()), Some(1.0))
    }
  }

  test("a snapshot written is counted with its duration and size") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.storage.emit(StorageEvent.SnapshotWritten("a", Some(block(1)), 2048L, 1500L))
        _ <- em.storage.emit(
          StorageEvent.SnapshotFailed(
            xyz.kd5ujc.scarab.storage.SnapshotFailure.Defect(new java.io.IOException("disk"))
          )
        )
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.snapshotsWritten, ()), Some(1.0))
        assertEquals(gauge(s, Catalogue.snapshotWriteSeconds, ()), Some(1.5))
        assertEquals(gauge(s, Catalogue.snapshotBytes, ()), Some(2048.0))
        assertEquals(counter(s, Catalogue.snapshotProblems, SnapshotProblemLabel.Failed), Some(1.0))
    }
  }

  test("connection and inbound events are counted by kind") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.connection.emit(ConnectionEvent.Opened("a", ConnectionState.Provenance.Outbound))
        _ <- em.connection.emit(
          ConnectionEvent.Closed(
            "a",
            ConnectionState.Provenance.Outbound,
            ConnectionEnd.Released
          )
        )
        _ <- em.inbound.emit(
          "b",
          InboundEvent.SessionEnded(
            InboundGovernor.SessionFailure
              .Peer(PeerError.NoIntersection),
            dropped = true
          )
        )
        _ <- em.inbound.emit(
          "b",
          InboundEvent.SessionEnded(
            InboundGovernor.SessionFailure
              .Peer(PeerError.NoIntersection),
            dropped = false
          )
        )
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.connectionEvents, ConnectionEventLabel.Opened), Some(1.0))
        assertEquals(counter(s, Catalogue.connectionEvents, ConnectionEventLabel.Closed), Some(1.0))
        assertEquals(
          counter(s, Catalogue.inboundEvents, InboundEventLabel.SessionDropped),
          Some(1.0)
        )
        assertEquals(counter(s, Catalogue.inboundEvents, InboundEventLabel.SessionEnded), Some(1.0))
    }
  }

  test("what a peer session measured, and the header counts added by the batches of any session") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.peer(Peer.Observation.Round(128, 120, 250L))
        _ <- em.peer(Peer.Observation.Fetched(40L))
        _ <- em.headers(HeaderGate.Counts(100, 5, 1))
        _ <- em.headers(HeaderGate.Counts(20, 0, 0))
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.chainSyncRounds, ()), Some(1.0))
        assertEquals(counter(s, Catalogue.chainSyncHeaders, ()), Some(120.0))
        assertEquals(observations(s, Catalogue.blockFetchSeconds, ()), Some(1L))
        assertEquals(counter(s, Catalogue.headersValidated, ()), Some(120.0))
        assertEquals(counter(s, Catalogue.headersUnchecked, ()), Some(5.0))
        assertEquals(counter(s, Catalogue.headersWaited, ()), Some(1.0))
    }
  }

  test("rolling replies count headers without inventing batch rounds or latencies") {
    fixture.flatMap { (metrics, em) =>
      for
        before <- metrics.snapshot
        _ <- em.peer(Peer.Observation.HeaderReceived)
        _ <- em.peer(Peer.Observation.HeaderReceived)
        rolling <- metrics.snapshot
        _ <- em.peer(Peer.Observation.Round(8, 7, 250L))
        combined <- metrics.snapshot
      yield
        assertEquals(counter(rolling, Catalogue.chainSyncHeaders, ()), Some(2.0))
        assertEquals(
          counter(rolling, Catalogue.chainSyncRounds, ()),
          counter(before, Catalogue.chainSyncRounds, ())
        )
        assertEquals(
          observations(rolling, Catalogue.chainSyncRoundSeconds, ()),
          observations(before, Catalogue.chainSyncRoundSeconds, ())
        )
        assertEquals(counter(combined, Catalogue.chainSyncHeaders, ()), Some(9.0))
        assertEquals(counter(combined, Catalogue.chainSyncRounds, ()), Some(1.0))
        assertEquals(observations(combined, Catalogue.chainSyncRoundSeconds, ()), Some(1L))
    }
  }

  test(
    "the churn governor's rounds and steps are counted, the sleep is a gauge, observations are free"
  ) {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.peerSelection(Output.Churned(Churn.Trace.Mode(FetchMode.Deadline)))
        _ <- em.peerSelection(
          Output.Churned(Churn.Trace.Action(Churn.Step.DecreaseActive, 2.seconds, -4))
        )
        _ <- em.peerSelection(
          Output.Churned(Churn.Trace.Timeout(Churn.Step.IncreaseKnown, 80.seconds, 0))
        )
        _ <- em.peerSelection(Output.Churned(Churn.Trace.Wait(3400.seconds)))
        _ <- em.peer(Peer.Observation.HeadersArrived(Vector(1L, 2L), 1.second))
        _ <- em.peer(Peer.Observation.BlocksArrived(Vector(1L -> 100L), 1.second))
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.churnRounds, ()), Some(1.0))
        assertEquals(
          counter(s, Catalogue.churnSteps, (Churn.Step.DecreaseActive, ChurnResultLabel.Reached)),
          Some(1.0)
        )
        assertEquals(
          counter(s, Catalogue.churnSteps, (Churn.Step.IncreaseKnown, ChurnResultLabel.TimedOut)),
          Some(1.0)
        )
        assertEquals(gauge(s, Catalogue.churnWaitSeconds, ()), Some(3400.0))
    }
  }

  test("two sinks of one trait both receive every event, the first before the second") {
    import EventSink.alongside
    for
      log <- cats.effect.Ref.of[IO, List[String]](Nil)
      sink = summon[EventSink[MempoolEvents, MempoolEvent]]
      one = sink.of[IO](_ => log.update(_ :+ "first"))
      two = sink.of[IO](_ => log.update(_ :+ "second"))
      _ <- one.alongside(two).emit(MempoolEvent.Adopted(hash))
      seen <- log.get
    yield assertEquals(seen, List("first", "second"))
  }
