// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.{Files, Path}
import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEpochBoundary as B, ConwayRewardStart as R, ConwayRewardPulser as P}

class CoherentSyntheticEpochSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private val pin = Bytes(Vector.fill(32)(7.toByte))
  private def array(v: V*) = get(Cbor.encode(V.Arr(v.toVector.map(Node(_, Bytes.empty)))))
  private val params = get(
    R.decodePoolParameters(
      array(
        V.Text(R.PoolParameterFormat),
        V.UInt(9),
        V.UInt(0),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(0),
        V.UInt(1),
        V.UInt(1)
      )
    )
  )
  private val globals = get(
    R.decodePulserGlobals(
      array(
        V.Text(R.PulserGlobalFormat),
        V.UInt(500),
        V.UInt(1),
        V.UInt(20),
        V.UInt(BigInt("45000000000000000")),
        V.UInt(5)
      )
    )
  )
  private def profile(window: Int = 50) = get(
    CoherentSequence.syntheticRewardProfile(
      params,
      globals,
      window,
      Some(false),
      Some(0),
      Some(false),
      Some(false),
      Some(0),
      Some(false),
      Some(false)
    )
  )

  test("synthetic omitted effects must each be explicitly absent, never unknown or nonzero") {
    for i <- 0 until 7 do
      val missing = CoherentSequence.syntheticRewardProfile(
        params,
        globals,
        50,
        if i == 0 then None else Some(false),
        if i == 1 then None else Some(0),
        if i == 2 then None else Some(false),
        if i == 3 then None else Some(false),
        if i == 4 then None else Some(0),
        if i == 5 then None else Some(false),
        if i == 6 then None else Some(false)
      )
      assert(missing.isLeft)
      val active = CoherentSequence.syntheticRewardProfile(
        params,
        globals,
        50,
        Some(i == 0),
        Some(if i == 1 then BigInt(1) else BigInt(0)),
        Some(i == 2),
        Some(i == 3),
        Some(if i == 4 then BigInt(1) else BigInt(0)),
        Some(i == 5),
        Some(i == 6)
      )
      assert(active.isLeft)
    assert(
      CoherentSequence
        .syntheticRewardProfile(
          params,
          globals,
          250,
          Some(false),
          Some(0),
          Some(false),
          Some(false),
          Some(0),
          Some(false),
          Some(false)
        )
        .isLeft
    )
    assert(
      compileErrors(
        "new lab.CoherentSequence.SyntheticSuccessor(null,null,null,null,null)"
      ).nonEmpty
    )
    assert(compileErrors("val s: lab.CoherentSequence.SyntheticRewards = null; s.copy()").nonEmpty)
  }

  // Previously captured exact-byte blocks are read only. Reward pots/absence/no-effect profile
  // are explicit synthetic assertions, not claimed to be the native state behind these blocks.
  sys.env.get("STAKE_SEQUENCE_EVIDENCE").foreach { location =>
    val dir = Path.of(location)
    def read(n: String): Bytes =
      val stream = Files.newInputStream(dir.resolve(n))
      try
        val bytes = stream.readNBytes(4194305)
        assert(bytes.nonEmpty && bytes.length <= 4194304)
        Bytes.fromArray(bytes)
      finally stream.close()
    def context = get(SequenceInput.load(dir))
    def prepared =
      val c = context
      val utxo = get(Bytes.fromHex(new String(read("pre-utxo-cbor.md").toArray, "UTF-8").trim))
      get(
        ConwayStakeSeed.decode(
          read("pre-ledger-state.md"),
          c.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          c.nonces.context.epochLength
        )
      )
    def blocks = get(
      ClusterHeaderObservation.capturesBounded(dir.resolve("scala-sequence-capture.md"), 16)
    )
      .map(c => get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block))))
    def runtime(window: Int = 50) = CoherentSequence
      .createWithSyntheticRewards[IO](
        context,
        prepared,
        profile(window),
        B.Pots(0, 0, context.ledger.fees, globals.maxSupply),
        Map.empty,
        Map.empty,
        pin
      )
      .map(get(_))
    def publish(r: CoherentSequence.Runtime[IO], b: SequenceInput.Block) =
      r.prepare(b).map(get(_)).flatMap(r.publish).map(get(_))
    def successor(r: CoherentSequence.Runtime[IO], snap: CoherentSequence.Snapshot) =
      r.prepareSyntheticSuccessor(snap.fence, pin, (context.epoch + 1) * 500).map(get(_))
    def same(a: CoherentSequence.State, b: CoherentSequence.State): Unit =
      assertEquals(a.id, b.id)
      assertEquals(a.syntheticRewards.get.id, b.syntheticRewards.get.id)
      assertEquals(a.syntheticRewards.get.pulser.map(_.id), b.syntheticRewards.get.pulser.map(_.id))
      assertEquals(a.stake.get.id, b.stake.get.id)
      assertEquals(a.stake.get.context.accounts, b.stake.get.context.accounts)
      assertEquals(a.stake.get.snapshots, b.stake.get.snapshots)
      assertEquals(a.ledger.id, b.ledger.id)
      assertEquals(a.nonces.id, b.nonces.id)
      assertEquals(a.certificates.state.id, b.certificates.state.id)

    test(
      "preparation/cancellation do not progress; duplicate publication counts actual issuer once"
    ) {
      (for
        r <- runtime()
        seed <- r.snapshot
        candidate <- r.prepare(blocks.head).map(get(_))
        unchanged <- r.snapshot
        _ = same(seed.state, unchanged.state)
        entered <- Deferred[IO, Unit]
        release <- Deferred[IO, Unit]
        fiber <- (entered.complete(()) *> release.get *> r.publish(candidate)).start
        _ <- entered.get
        _ <- fiber.cancel
        canceled <- r.snapshot
        _ = same(seed.state, canceled.state)
        results <- (r.publish(candidate), r.publish(candidate)).parTupled
        _ = assertEquals(Vector(results._1, results._2).count(_.isRight), 1)
        next <- r.snapshot
        accepted = get(Vector(results._1, results._2).find(_.isRight).get)
        reward = next.state.syntheticRewards.get
        _ = assertEquals(
          reward.currentBlocks,
          Map(accepted.certificateObservation.issuer -> BigInt(1))
        )
        _ = assertEquals(reward.pots.fees, next.state.ledger.fees)
        _ = assertEquals(next.state.revision, seed.state.revision + 1)
        _ = assert(reward.frozen.isDefined)
        _ = assertEquals(reward.frozen.get.preTickTupleId, seed.state.id)
        _ = assertEquals(reward.frozen.get.snapshotFees, seed.state.stake.get.snapshots.fees)
        failed <- r.prepare(blocks.head)
        _ = assert(failed.isLeft)
        afterFailure <- r.snapshot
        _ = same(next.state, afterFailure.state)
      yield ()).unsafeToFuture()
    }

    test(
      "owned pulsing successor completes purely, applies before SNAP and rotates old mark/counts"
    ) {
      (for
        r <- runtime()
        _ <- publish(r, blocks.head)
        before <- r.snapshot
        _ = assertEquals(before.state.syntheticRewards.get.pulser.get.phase, P.Phase.Pulsing)
        pending <- successor(r, before)
        _ = assertEquals(pending.completedPulser.get.phase, P.Phase.Complete)
        _ = assertEquals(pending.preview.rotation.leadership, before.state.stake.get.snapshots.mark)
        _ = assertEquals(
          pending.preview.previousBlocks,
          before.state.syntheticRewards.get.currentBlocks
        )
        _ = assertEquals(pending.preview.currentBlocks, Map.empty)
        _ = assertEquals(pending.preview.preTickRewardEnvironment.tupleId, before.state.id)
        _ = assertEquals(pending.preview.rotation.snapshots.fees, pending.preview.pots.fees)
        _ = assert(pending.preview.rewardApplication.isDefined)
        _ = assert(
          !pending.published && !pending.headerAndBlockChecked && !pending.epochTransitionValidated
        )
        fresh <- r.checkSyntheticSuccessor(pending)
        _ = assert(fresh.isRight)
        unchanged <- r.snapshot
        _ = same(before.state, unchanged.state)
        skip <- r.prepareSyntheticSuccessor(before.fence, pin, (context.epoch + 2) * 500)
        _ = assert(skip.isLeft)
        sameEpoch <- r.prepareSyntheticSuccessor(before.fence, pin, before.state.ledger.slot + 1)
        _ = assert(sameEpoch.isLeft)
      yield ()).unsafeToFuture()
    }

    test("foreign/stale/sibling successor capabilities fail even when amounts are equal") {
      (for
        a <- runtime()
        b <- runtime()
        initial <- a.snapshot
        empty <- successor(a, initial)
        _ = assertEquals(empty.preview.rewardApplication, None)
        _ <- publish(a, blocks.head)
        _ <- publish(b, blocks.head)
        before <- a.snapshot
        pending <- successor(a, before)
        foreign <- b.checkSyntheticSuccessor(pending)
        _ = assertEquals(foreign, Left(CoherentSequence.Failure.ForeignCandidate))
        foreignFence <- b.prepareSyntheticSuccessor(before.fence, pin, (context.epoch + 1) * 500)
        _ = assertEquals(foreignFence, Left(CoherentSequence.Failure.ForeignFence))
        stale <- a.checkSyntheticSuccessor(empty)
        _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleCandidate))
        undone <- a.rollbackTo(before.fence, initial.state.acquisition.tip).map(get(_))
        _ = same(initial.state, undone.state)
        _ = assert(undone.state.revision > before.state.revision)
        _ <- publish(a, blocks.head)
        sibling <- a.checkSyntheticSuccessor(pending)
        _ = assertEquals(sibling, Left(CoherentSequence.Failure.StaleCandidate))
        replay <- a.snapshot
        _ = assertEquals(
          replay.state.syntheticRewards.get.pots,
          before.state.syntheticRewards.get.pots
        )
        _ = assert(
          replay.state.syntheticRewards.get.frozen.get.id != before.state.syntheticRewards.get.frozen.get.id
        )
      yield ()).unsafeToFuture()
    }

    test(
      "whole tuple rollback restores pulser; compaction preserves owned origin and codecs reject"
    ) {
      (for
        r <- runtime()
        seed <- r.snapshot
        first <- publish(r, blocks.head)
        v1 = ValidatedCheckpoint.encode(context, seed, pin, 0, 8)
        _ = assert(v1.swap.toOption.get.contains("stake"))
        _ <- publish(r, blocks(1))
        before <- r.snapshot
        pending <- successor(r, before)
        restored <- r.rollbackTo(before.fence, first.state.acquisition.tip).map(get(_))
        _ = same(restored.state, first.state)
        _ <- publish(r, blocks(1))
        repeated <- r.snapshot
        _ = same(repeated.state, before.state)
        compact <- r.advanceAnchor(repeated.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(
          compact.state.syntheticRewards.get.id,
          before.state.syntheticRewards.get.id
        )
        stale <- r.checkSyntheticSuccessor(pending)
        _ = assertEquals(stale, Left(CoherentSequence.Failure.StaleCandidate))
        v2 <- r.exportLocalCheckpoint(pin, pin, 0)
        _ = assert(v2.swap.toOption.get.contains("stake"))
        undo <- r.rollbackTo(compact.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(undo.state.syntheticRewards.get.id, first.state.syntheticRewards.get.id)
        _ = assertEquals(undo.state.stake.get.id, first.state.stake.get.id)
        _ = assert(undo.state.revision > compact.state.revision)
      yield ()).unsafeToFuture()
    }

    test("late first accepted signal forces completion and empty accepted blocks count") {
      (for
        r <- runtime(1)
        seed <- r.snapshot
        first <- publish(r, blocks.head)
        _ = assertEquals(first.state.syntheticRewards.get.pulser.get.phase, P.Phase.Complete)
        _ <- publish(r, blocks(1))
        next <- r.snapshot
        _ = assert(blocks.exists(_.transactionMemos.isEmpty))
        _ = assertEquals(next.state.syntheticRewards.get.currentBlocks.values.sum, BigInt(2))
        undone <- r.rollbackTo(next.fence, seed.state.acquisition.tip).map(get(_))
        _ = same(undone.state, seed.state)
      yield ()).unsafeToFuture()
    }

    test("reward compaction retains two suffix receipts and exact bounded frozen origin") {
      val directory = Path.of(sys.env("STAKE_WINDOW_EVIDENCE"))
      val input = get(SequenceInput.load(directory))
      val ledgerBytes = input.originals("pre-ledger-state.md")
      val utxo =
        get(Bytes.fromHex(new String(input.originals("pre-utxo-cbor.md").toArray, "UTF-8").trim))
      val stakeSeed = get(
        ConwayStakeSeed.decode(
          ledgerBytes,
          input.sourcePins("preLedgerSha256"),
          utxo,
          ClusterHeaderObservation.sha256(utxo),
          500
        )
      )
      val bs = get(
        ClusterHeaderObservation.capturesBounded(directory.resolve("scala-sequence-capture.md"), 16)
      )
        .take(3)
        .map(c =>
          get(SequenceInput.block(BoundedChainFollower.Original(c.headerEnvelope, c.block)))
        )
      assertEquals(bs.size, 3)
      (for
        r <- CoherentSequence
          .createWithSyntheticRewards[IO](
            input,
            stakeSeed,
            profile(),
            B.Pots(0, 0, input.ledger.fees, globals.maxSupply),
            Map.empty,
            Map.empty,
            pin
          )
          .map(get(_))
        nullCheck <- r.checkSyntheticSuccessor(null)
        _ = assert(nullCheck.isLeft)
        first <- publish(r, bs.head)
        second <- publish(r, bs(1))
        third <- publish(r, bs(2))
        before <- r.snapshot
        compact <- r.advanceAnchor(before.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(compact.state.acquisition.size, 2)
        _ = assertEquals(compact.state.syntheticRewards.get.id, third.state.syntheticRewards.get.id)
        backSecond <- r.rollbackTo(compact.fence, second.state.acquisition.tip).map(get(_))
        _ = assertEquals(
          backSecond.state.syntheticRewards.get.id,
          second.state.syntheticRewards.get.id
        )
        backFirst <- r.rollbackTo(backSecond.fence, first.state.acquisition.tip).map(get(_))
        _ = assertEquals(
          backFirst.state.syntheticRewards.get.id,
          first.state.syntheticRewards.get.id
        )
        _ <- publish(r, bs(1))
        again <- publish(r, bs(2))
        _ = same(again.state, compact.state)
      yield ()).unsafeToFuture()
    }
  }
