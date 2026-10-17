// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.metrics

import cats.effect.IO
import munit.CatsEffectSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{BlockRef, FollowerEvent}
import xyz.kd5ujc.scarab.network.ConnectionEvent
import xyz.kd5ujc.scarab.node.{HeaderGate, InboundEvent, Peer}
import xyz.kd5ujc.scarab.node.forge.ForgeEvent
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

  test("the key evolution gives the periods left until the certificate expires") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.forge.emit(ForgeEvent.Started(hash, hash, 10, 62, 2, 12))
        _ <- em.forge.emit(ForgeEvent.KesEvolved(15, 5))
        s <- metrics.snapshot
      yield
        assertEquals(gauge(s, Catalogue.forgingEnabled, ()), Some(1.0))
        assertEquals(gauge(s, Catalogue.opCertStartKesPeriod, ()), Some(10.0))
        assertEquals(gauge(s, Catalogue.opCertExpiryKesPeriod, ()), Some(72.0))
        assertEquals(gauge(s, Catalogue.currentKesPeriod, ()), Some(15.0))
        assertEquals(gauge(s, Catalogue.remainingKesPeriods, ()), Some(57.0))
    }
  }

  test("mempool rejections are counted by reason, additions as processed transactions") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.mempool.emit(MempoolEvent.Added(hash, 100, 1, 100L))
        _ <- em.mempool.emit(MempoolEvent.Rejected(None, Rejection.Malformed("x")))
        _ <- em.mempool.emit(MempoolEvent.Rejected(Some(hash), Rejection.Full(10, 5)))
        _ <- em.mempool.emit(MempoolEvent.Rejected(Some(hash), Rejection.Full(10, 5)))
        s <- metrics.snapshot
      yield
        assertEquals(counter(s, Catalogue.txsProcessed, ()), Some(1.0))
        assertEquals(counter(s, Catalogue.mempoolRejected, RejectionLabel.Malformed), Some(1.0))
        assertEquals(counter(s, Catalogue.mempoolRejected, RejectionLabel.Full), Some(2.0))
    }
  }

  test("a snapshot written is counted with its duration and size") {
    fixture.flatMap { (metrics, em) =>
      for
        _ <- em.storage.emit(StorageEvent.SnapshotWritten("a", Some(block(1)), 2048L, 1500L))
        _ <- em.storage.emit(StorageEvent.SnapshotFailed("disk"))
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
          ConnectionEvent.Closed("a", ConnectionState.Provenance.Outbound, "x")
        )
        _ <- em.inbound.emit("b", InboundEvent.SessionEnded("x", dropped = true))
        _ <- em.inbound.emit("b", InboundEvent.SessionEnded("x", dropped = false))
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
