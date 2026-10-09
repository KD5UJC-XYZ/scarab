// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.ChainSync
import scala.concurrent.duration.*

class EphemeralStreamingSuite extends munit.FunSuite:
  private val F = EphemeralStreamingFixture
  private val D = EphemeralStreaming
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private case class Trace(epoch: BigInt, compacted: BigInt, frozen: Option[(Bytes, BigInt)])
  private val publication = Bytes(Vector.fill(32)(91.toByte))
  private def point(b: SequenceInput.Block): ChainSync.Point =
    ChainSync.Point.Block(get(ChainSync.UInt64.from(b.header.slot)), b.header.hash)
  private def byteCost(b: SequenceInput.Block): Long =
    b.original.envelope.size.toLong + b.original.block.size + b.transactionMemos
      .map(_.size.toLong)
      .sum
  private def events(bs: Vector[SequenceInput.Block]) = bs.map(D.Event.Block.apply)
  private def run(
      r: CoherentSequence.Runtime[IO],
      es: Vector[D.Event],
      limits: D.Limits = D.Limits()
  ) =
    Ref.of[IO, Vector[D.Event]](es).flatMap { queue =>
      D.run(r, limits)(queue.modify(xs => (xs.drop(1), xs.headOption)))
    }
  private def sameContent(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.ledger.id, b.ledger.id)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.environment.id, b.ledger.environment.id)
    assertEquals(a.stake.get.id, b.stake.get.id)
    assertEquals(a.syntheticRewards.get.id, b.syntheticRewards.get.id)
    assertEquals(a.nonces.id, b.nonces.id)
    assertEquals(a.certificates.state.id, b.certificates.state.id)
    assertEquals(a.revision, b.revision)
  private def sameSemantics(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
    assertEquals(a.acquisition.tip, b.acquisition.tip)
    assertEquals(a.ledger.outputMap, b.ledger.outputMap)
    assertEquals(a.ledger.fees, b.ledger.fees)
    assertEquals(a.ledger.environment.epoch, b.ledger.environment.epoch)
    assertEquals(a.ledger.environment.parameterDigest, b.ledger.environment.parameterDigest)
    assertEquals(a.nonces.fields, b.nonces.fields)
    assertEquals(a.certificates.state.tip, b.certificates.state.tip)
    val x = a.stake.get; val y = b.stake.get
    assertEquals(x.context.accounts, y.context.accounts)
    assertEquals(x.context.pools, y.context.pools)
    assertEquals(x.instantaneous, y.instantaneous)
    assertEquals(x.utxo, y.utxo)
    assertEquals(x.epoch, y.epoch)
    Vector(
      (x.snapshots.mark, y.snapshots.mark),
      (x.snapshots.set, y.snapshots.set),
      (x.snapshots.go, y.snapshots.go)
    ).foreach { (a, b) =>
      assertEquals(a.active, b.active); assertEquals(a.pools, b.pools);
      assertEquals(a.total, b.total)
    }
    assertEquals(x.snapshots.fees, y.snapshots.fees)
    val ra = a.syntheticRewards.get; val rb = b.syntheticRewards.get
    assertEquals(ra.pots, rb.pots)
    assertEquals(ra.previousBlocks, rb.previousBlocks)
    assertEquals(ra.currentBlocks, rb.currentBlocks)
    assertEquals(ra.pulser.map(_.phase), rb.pulser.map(_.phase))
    assertEquals(ra.pulser.map(_.members), rb.pulser.map(_.members))

  test("25 linked signed blocks cross reward freeze and epoch with bounded retained receipts") {
    (for
      r <- F.runtime
      queue <- Ref.of[IO, Vector[D.Event]](events(F.blocks))
      trace <- Ref.of[IO, Vector[Trace]](Vector.empty)
      report <- D.run(r, D.Limits()) {
        for
          s <- r.snapshot
          capsule <- r.exportSyntheticRecovery(publication).map(get(_))
          _ = assert(capsule.image.records.size <= 8)
          _ = assert(capsule.image.states.size <= 9)
          _ = assertEquals(capsule.image.records.size, s.state.acquisition.size)
          _ = assert(s.state.acquisition.originals.size <= 8)
          _ <- trace.update(
            _ :+ Trace(
              s.state.stake.get.epoch,
              s.state.compactedBlocks,
              s.state.syntheticRewards.get.frozen.map(f => (f.id, f.observedSlot))
            )
          )
          event <- queue.modify(xs => (xs.drop(1), xs.headOption))
        yield event
      }
      history <- trace.get
      _ = assert(F.blocks.size >= 17)
      _ = assertEquals(report.stop, D.Stop.End)
      _ = assertEquals(report.counters.acceptedBlocks, F.blocks.size.toLong)
      _ = assertEquals(report.counters.blocks, F.blocks.size.toLong)
      _ = assertEquals(report.counters.events, F.blocks.size.toLong)
      _ = assertEquals(report.counters.bytes, F.blocks.map(byteCost).sum)
      _ = assert(report.counters.compactions >= 2)
      _ = assertEquals(report.snapshot.state.depth, BigInt(F.blocks.size))
      _ = assertEquals(report.snapshot.state.stake.get.epoch, BigInt(1))
      _ = assertEquals(report.snapshot.state.acquisition.tip, point(F.blocks.last))
      _ = {
        val frozen = history.filter(_.epoch == 0).flatMap(s => s.frozen.map(s -> _))
        assert(frozen.nonEmpty)
        assertEquals(frozen.map(_._2._1).distinct.size, 1)
        assert(frozen.exists((s, _) => s.compacted > 8))
        assertEquals(frozen.head._2._2, BigInt(5))
        assertEquals(
          report.snapshot.state.syntheticRewards.get.previousBlocks.values.sum,
          BigInt(20)
        )
        assertEquals(report.snapshot.state.syntheticRewards.get.currentBlocks.values.sum, BigInt(5))
        assert(
          !report.snapshot.state.fullLedgerValidated && !report.snapshot.state.consensusValidated
        )
      }
    yield ()).unsafeToFuture()
  }

  test(
    "retained boundary rollback and replay restore all semantics without resetting lifetime counts"
  ) {
    (for
      r <- F.runtime
      successor = F.blocks.filter(_.header.slot >= 40)
      predecessor = F.blocks.lastIndexWhere(_.header.slot < 40)
      baseline <- Ref.of[IO, Option[CoherentSequence.Snapshot]](None)
      queue <- Ref.of[IO, Vector[D.Event]](
        events(F.blocks) ++ Vector(D.Event.Rollback(point(F.blocks(predecessor)))) ++ events(
          successor
        )
      )
      pulled <- Ref.of[IO, Int](0)
      report <- D.run(r, D.Limits()) {
        for
          n <- pulled.getAndUpdate(_ + 1)
          _ <-
            if n == F.blocks.size then r.snapshot.flatMap(s => baseline.set(Some(s))) else IO.unit
          event <- queue.modify(xs => (xs.drop(1), xs.headOption))
        yield event
      }
      before <- baseline.get.map(_.get)
      _ = assertEquals(report.stop, D.Stop.End)
      _ = sameSemantics(report.snapshot.state, before.state)
      _ = assert(report.snapshot.state.revision > before.state.revision)
      _ = assertEquals(report.counters.rollbacks, 1L)
      _ = assertEquals(report.counters.acceptedBlocks, (F.blocks.size + successor.size).toLong)
      _ = assertEquals(report.counters.events, (F.blocks.size + successor.size + 1).toLong)
      _ = assertEquals(report.counters.bytes, (F.blocks ++ successor).map(byteCost).sum + 64L)
      stale <- r.rollbackTo(before.fence, before.state.acquisition.tip)
      _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleFence))
    yield ()).unsafeToFuture()
  }

  test("beyond-window rollback rejects without change and compaction invalidates old fences") {
    (for
      r <- F.runtime
      captured <- Ref.of[IO, Option[CoherentSequence.Snapshot]](None)
      pull <- Ref.of[IO, Int](0)
      queue <- Ref.of[IO, Vector[D.Event]](events(F.blocks))
      completed <- D.run(r, D.Limits()) {
        for
          n <- pull.getAndUpdate(_ + 1)
          _ <- if n == 8 then r.snapshot.flatMap(s => captured.set(Some(s))) else IO.unit
          next <- queue.modify(xs => (xs.drop(1), xs.headOption))
        yield next
      }
      old <- captured.get.map(_.get)
      stale <- r.advanceAnchor(old.fence, old.state.acquisition.tip)
      _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleFence))
      rejected <- run(r, Vector(D.Event.Rollback(point(F.blocks.head))))
      _ = assertEquals(
        rejected.stop,
        D.Stop.Rejected(CoherentSequence.Failure.OutsideRetainedWindow)
      )
      _ = assertEquals(rejected.snapshot.state.id, completed.snapshot.state.id)
      _ = sameContent(rejected.snapshot.state, completed.snapshot.state)
      _ = assertEquals(rejected.counters.events, 1L)
      _ = assertEquals(rejected.counters.bytes, 64L)
      _ = assertEquals(rejected.counters.rollbacks, 0L)
    yield ()).unsafeToFuture()
  }

  test("invalid successor changes only disclosed pre-validation compaction availability") {
    (for
      r <- F.runtime
      twin <- F.runtime
      oldBlocks = F.blocks.takeWhile(_.header.slot < 40)
      successor = F.blocks.find(_.header.slot >= 40).get
      twinReport <- run(twin, events(oldBlocks))
      twinBefore = twinReport.snapshot
      preview <- twin
        .prepareSyntheticSuccessor(twinBefore.fence, successor.header.hash, successor.header.slot)
        .map(get(_))
      expected <- twin
        .advanceAnchor(twinBefore.fence, twinBefore.state.acquisition.candidates(7))
        .map(get(_))
      _ = assertEquals(expected.state.revision, twinBefore.state.revision)
      staleFence <- twin.rollbackTo(twinBefore.fence, expected.state.acquisition.tip)
      _ = assertEquals(staleFence, Left(CoherentSequence.Failure.StaleFence))
      stalePreview <- twin.checkSyntheticSuccessor(preview)
      _ = assertEquals(stalePreview, Left(CoherentSequence.Failure.StaleCandidate))
      rejected <- run(r, events(oldBlocks) :+ D.Event.Block(F.invalidSignature(successor)))
      _ = assert(rejected.stop.isInstanceOf[D.Stop.Rejected])
      _ = assertEquals(rejected.snapshot.state.id, expected.state.id)
      _ = sameContent(rejected.snapshot.state, expected.state)
      _ = assertEquals(rejected.counters.acceptedBlocks, oldBlocks.size.toLong)
      _ = assertEquals(rejected.counters.blocks, oldBlocks.size.toLong + 1)
      _ = assertEquals(rejected.counters.compactions, twinReport.counters.compactions + 1)
      _ = assert(rejected.snapshot.state.acquisition.anchor != twinBefore.state.acquisition.anchor)
      _ = sameContent(rejected.snapshot.state, twinBefore.state)
      before <- r.snapshot
      again <- run(r, Vector(D.Event.Block(F.invalidSignature(successor))))
      _ = assert(again.stop.isInstanceOf[D.Stop.Rejected])
      _ = assertEquals(again.snapshot.state.id, before.state.id)
      _ = sameContent(again.snapshot.state, before.state)
      _ = assertEquals(again.counters.compactions, 0L)
    yield ()).unsafeToFuture()
  }

  test("event block and byte budgets include rollback and replay attempts across compaction") {
    val bs = F.blocks.take(17)
    val replay = bs.takeRight(2)
    val es = events(bs) ++ Vector(D.Event.Rollback(point(bs(bs.size - 3)))) ++ events(replay)
    val cost = bs.map(byteCost).sum + 64L + byteCost(replay.head)
    Vector(
      (D.Limits(maxEvents = 19, maxBlocks = 19), D.Stop.EventLimit),
      (D.Limits(maxBlocks = 18), D.Stop.BlockLimit),
      (D.Limits(maxBytes = cost), D.Stop.ByteLimit)
    ).traverse_ { (limits, stop) =>
      for
        r <- F.runtime
        report <- run(r, es, limits)
        _ = assertEquals(report.stop, stop)
        _ = assertEquals(report.counters.acceptedBlocks, 18L)
        _ = assertEquals(report.counters.rollbacks, 1L)
        _ = assertEquals(report.snapshot.state.acquisition.tip, point(replay.head))
        _ = assert(report.counters.compactions > 0)
        _ = assertEquals(report.counters.events, if stop == D.Stop.EventLimit then 19L else 20L)
        _ = assertEquals(report.counters.blocks, if stop == D.Stop.EventLimit then 18L else 19L)
        _ = assertEquals(
          report.counters.bytes,
          if stop == D.Stop.EventLimit then cost else cost + byteCost(replay.last)
        )
      yield ()
    }.unsafeToFuture()
  }

  test("one deadline spans repeated rollbacks and cancellation interrupts a waiting source") {
    TestControl
      .executeEmbed(for
        r <- F.runtime
        initial <- r.snapshot
        canceled <- Ref.of[IO, Boolean](false)
        timed <- D.run(r, D.Limits(duration = 150.millis))(
          IO.sleep(50.millis).as(Some(D.Event.Rollback(initial.state.acquisition.tip)))
        )
        _ = assertEquals(timed.stop, D.Stop.Deadline)
        _ = assert(timed.counters.events <= 3)
        _ = assert(
          timed.counters.rollbacks <= timed.counters.events && timed.counters.events - timed.counters.rollbacks <= 1
        )
        _ = assert(timed.elapsed >= 100.millis && timed.elapsed < 2.seconds)
        _ = assertEquals(timed.snapshot.state.id, initial.state.id)
        waiting <- D.run(r, D.Limits(duration = 50.millis))(
          IO.never[Option[D.Event]].onCancel(canceled.set(true))
        )
        _ = assertEquals(waiting.stop, D.Stop.Deadline)
        didCancel <- canceled.get
        _ = assert(didCancel)
        _ = assertEquals(waiting.counters.events, 0L)
      yield ())
      .unsafeToFuture()
  }

  test("retention limit cannot exceed coordinator capacity and invalid limits never pull input") {
    (for
      r <- CoherentSequence
        .createWithSyntheticRewards[IO](
          F.context,
          F.stakeSeed,
          F.profile,
          F.initialPots,
          Map.empty,
          Map.empty,
          F.pin,
          maxBlocks = 4
        )
        .map(get(_))
      pulled <- Ref.of[IO, Boolean](false)
      before <- r.snapshot
      result <- D.run(r, D.Limits(retained = 8))(pulled.set(true).as(None)).attempt
      _ = assert(result.isLeft)
      didPull <- pulled.get
      _ = assert(!didPull)
      after <- r.snapshot
      _ = assertEquals(after.state.id, before.state.id)
    yield ()).unsafeToFuture()
  }

  test("external cancellation releases the pull resource and leaves the runtime untouched") {
    (for
      r <- F.runtime
      before <- r.snapshot
      entered <- Deferred[IO, Unit]
      closed <- Ref.of[IO, Boolean](false)
      source = Resource
        .make(IO.unit)(_ => closed.set(true))
        .use(_ => entered.complete(()).void *> IO.never[Option[D.Event]])
      fiber <- D.run(r, D.Limits())(source).start
      _ <- entered.get
      _ <- fiber.cancel
      result <- fiber.join
      _ = assert(result.isCanceled)
      released <- closed.get
      _ = assert(released)
      after <- r.snapshot
      _ = assertEquals(after.state.id, before.state.id)
      _ = assertEquals(after.state.revision, before.state.revision)
    yield ()).unsafeToFuture()
  }
