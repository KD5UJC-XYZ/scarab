// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import scala.annotation.unused
import cats.effect.{IO, Ref}
import cats.effect.testkit.TestControl
import scala.concurrent.duration.*
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.Block
import xyz.kd5ujc.scarab.node.read.ChainRead.given
import xyz.kd5ujc.scarab.ledger.ForgeError
import xyz.kd5ujc.scarab.consensus.{BlockRef, VolatileChain}
import xyz.kd5ujc.scarab.consensus.state.MirrorState
import xyz.kd5ujc.scarab.ledger.VrfCert
import xyz.kd5ujc.scarab.node.{Fatal, Follower}
import xyz.kd5ujc.scarab.consensus.{ChainSelection, SelectView}
import xyz.kd5ujc.scarab.ledger.state.PoolDistr
import xyz.kd5ujc.scarab.protocol.Era
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.node.mempool.{Source, TxKit}
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}

/** Forging against the real block rules: whatever the forger builds must pass the same `NodeRules`
  * that validate blocks received from peers, and the follower must adopt it.
  */
class ForgeSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ForgeFixture:

  test("two independently forged branches share a valid prefix within the retained window") {
    TestControl.executeEmbed(
      ForgedChain
        .competingForks(5000 until 5064, 5064 until 5128, leftSeed = 1, rightSeed = 2)
        .map { case (left, right) =>
          val common = left.headers.zip(right.headers).takeWhile { case (a, b) => a.hash == b.hash }
          assert(common.size >= ForgedChain.geometry.k.toInt)
          assert(left.headers.size > common.size)
          assert(right.headers.size > common.size)
          assertNotEquals(left.headers(common.size).hash, right.headers(common.size).hash)
          assert(left.headers.size - common.size <= ForgedChain.geometry.k.toInt)
          assert(right.headers.size - common.size <= ForgedChain.geometry.k.toInt)
        },
      seed = Some("MQ==")
    )
  }

  test("an elected slot yields a block our rules accept, built from the mempool, and adopted") {
    val p = pool(kesStart = 50)
    val (t1, id1) = TxKit.pay(1)
    val (t2, id2) = TxKit.pay(2)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        _ <- h.mempool.add(t1, Source.Local)
        _ <- h.mempool.add(t2, Source.Local)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        chain <- h.chain
      yield
        assert(forged.nonEmpty, "f = 1/2 with all the stake: some of 40 slots must be won")
        val first = forged.head
        assertEquals(first.txs, Vector(id1, id2))
        assertEquals(first.blockNo, BigInt(0))
        assertEquals(first.prev, None)
        assertEquals(first.relativeKesPeriod, 0)
        assertEquals(chain.blocks.size, forged.size.min(geometry.k.toInt))
        assertEquals(chain.tipRef.map(_.hash), Some(forged.last.hash))
        // consecutive: each block extends the previous one
        forged.sliding(2).foreach {
          case Seq(a, b) => assertEquals(b.prev, Some(a.hash))
          case _         => ()
        }
        // the ledger counted the blocks for the pool and applied the transactions
        assertEquals(blocksMade(chain, p.id), Some(BigInt(forged.size)))
        assert(!chain.state.utxo.contains(TestLedger.txIn(1)))
        assert(!chain.state.utxo.contains(TestLedger.txIn(2)))
        assert(chain.state.utxo.contains(TestLedger.txIn(3)))
    }
  }

  test("the loop attempts every slot: each slot the pool leads is forged, none is skipped") {
    val p = pool(kesStart = 50, seed = 9)
    // the slots the leadership check elects, computed apart from the forger
    val leads = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    assert(leads.size > 40 && leads.size < 160, leads.size.toString)
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true, unbounded = true)
          fiber <- h.forger.run.start
          _ <- IO.sleep(200500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
        yield
          assertEquals(forged.map(_.slot.toInt).filter(_ < 5200), leads.toVector)
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    )
  }

  test(
    "the loop follows a system start that is not on a whole second: no slot is lost or doubled"
  ) {
    val p = pool(kesStart = 50, seed = 9)
    val leads = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    // the clock stands 437 ms into a slot's tenth of a second relative to the genesis' whole second
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true, offsetMillis = 437, unbounded = true)
          fiber <- h.forger.run.start
          _ <- IO.sleep(200500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
        yield
          assertEquals(forged.map(_.slot.toInt).filter(_ < 5200), leads.toVector)
          // every slot is started once, none twice and none passed over
          assertEquals(
            events
              .collect { case ForgeEvent.Loop(ForgeTrace.StartLeadershipCheck(s)) => s.toInt }
              .filter(_ < 5200),
            (5000 until 5200).toVector
          )
      }
    )
  }

  test("per-epoch counters: checked and led slots, blocks, latency; a summary closes each epoch") {
    val p = pool(kesStart = 50, seed = 9)
    // the clock starts 10 slots before the end of epoch 5 (slots 5000 to 5999)
    TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, ledgerWith(p, 1), live = true, offsetMillis = 990000, unbounded = true)
          fiber <- h.forger.run.start
          _ <- IO.sleep(30500.millis)
          _ <- fiber.cancel
          forged <- forgedIn(h)
          events <- h.seen
          fifth <- h.forger.stats.epoch(5)
          sixth <- h.forger.stats.epoch(6)
          total <- h.forger.stats.total
        yield
          val summaries = events.collect { case s: ForgeEvent.EpochSummary => s }
          assertEquals(summaries.map(_.epoch), Vector(BigInt(5)))
          assertEquals(summaries.head.stats, fifth)
          assertEquals(fifth.slotsChecked, 10L)
          assertEquals(sixth.slotsChecked, 21L)
          assertEquals(total.leaderSlots, forged.size.toLong)
          assertEquals(total.forged, forged.size.toLong)
          assertEquals(total.adopted, forged.size.toLong)
          assertEquals(fifth.forged, forged.count(_.epoch == 5).toLong)
          assertEquals(sixth.forged, forged.count(_.epoch == 6).toLong)
          assertEquals(total.latencyCount, forged.size.toLong)
          assertEquals(total.latencyMaxMs, 0L) // the test clock does not move during a step
          assertEquals(total.notLeader + total.leaderSlots, total.slotsChecked)
          assertEquals(total.didntAdopt + total.replaced + total.failed + total.skippedTotal, 0L)
      }
    )
  }

  test("the forger needs no peer: it forges on its own chain, as the reference does") {
    val p = pool(kesStart = 50, seed = 7)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        total <- h.forger.stats.total
      yield
        assert(forged.nonEmpty)
        assertEquals(total.slotsChecked, 40L)
    }
  }

  test("slots that pass while a step is still running are passed over, and nothing is said") {
    val p = pool(kesStart = 50, seed = 10)
    // the key is written back at the period boundary of slot 5100 and that write takes 3.5 s:
    // slots 5101 and 5102 pass meanwhile (`knownSlotWatcher` notifies the slot it reads next)
    TestControl.executeEmbed(
      withHandle {
        for
          writes <- Ref.of[IO, Int](0)
          slow = new HotKeySource[IO]:
            def read = IO.pure(Left(ForgeError.KesUpdateInterrupted))
            def persist(key: xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning.SigningKey) =
              writes
                .getAndUpdate(_ + 1)
                .flatMap(n => IO.sleep(3500.millis).whenA(n == 0).as(Persisted.Written))
          h <- harness(
            p,
            ledgerWith(p, 1),
            live = true,
            offsetMillis = 99000,
            source = Some(slow),
            unbounded = true
          )
          fiber <- h.forger.run.start
          _ <- IO.sleep(6500.millis)
          _ <- fiber.cancel
          started <- tracesIn(h).map(_.collect { case ForgeTrace.StartLeadershipCheck(s) =>
            s.toInt
          })
          events <- h.seen
        yield
          assertEquals(started.take(4), Vector(5099, 5100, 5103, 5104))
          assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
      }
    )
  }

  test("a slot runs only once known: after the system start and within the tip's era history") {
    val p = pool(kesStart = 50, seed = 9)
    def started(offsetMillis: Long, millis: Long)(using Handle[IO, NodeError]) =
      for
        h <- harness(p, ledgerWith(p, 1), live = true, offsetMillis = offsetMillis)
        fiber <- h.forger.run.start
        _ <- IO.sleep(millis.millis)
        _ <- fiber.cancel
        traces <- tracesIn(h)
      yield traces.collect { case ForgeTrace.StartLeadershipCheck(s) => s.toInt }
    TestControl.executeEmbed(
      withHandle {
        for
          // (first, as the test clock starts at the system start) two seconds before the system
          // start: nothing until then, then slots 0, 1, 2, ...
          early <- started(-5002000L, 4500)
          // the chain is at genesis: its era history ends a safe zone after it, so at slot 5000 the
          // current slot is unknown and the loop runs nothing (`CurrentSlotUnknown`)
          beyond <- started(0, 200000)
        yield
          assertEquals(beyond, Vector.empty[Int])
          assertEquals(early, Vector(0, 1, 2))
      }
    )
  }

  test("a clock moved back 20 s or less is traced and the slot kept; further back the node stops") {
    val p = pool(kesStart = 50, seed = 9)
    def run(back: Long)(using Handle[IO, NodeError]) =
      for
        shift <- IO.ref(0L)
        clock = (shift.get, IO.realTimeInstant).mapN((s, t) => t.plusSeconds(5000).plusMillis(s))
        h <- harness(p, ledgerWith(p, 1), live = true, unbounded = true, clock = Some(clock))
        fiber <- h.forger.run.start
        _ <- IO.sleep(3500.millis)
        _ <- shift.set(-back)
        _ <- IO.sleep(5000.millis)
        outcome <- fiber.join
          .flatMap(
            _.fold(IO.pure(None), e => IO.raiseError(e), fa => fa.map(Some(_)))
          )
          .timeoutTo(1.second, fiber.cancel.as(None))
        events <- h.seen
      yield (outcome, events)
    TestControl.executeEmbed(
      withHandle {
        for
          nearRun <- run(10000)
          farRun <- run(30000)
        yield
          val (near, nearEvents) = nearRun
          val far = farRun._1
          // within the tolerance: no slot is attempted twice, the move is traced, the loop goes on
          val attempted = nearEvents.collect {
            case ForgeEvent.Loop(ForgeTrace.StartLeadershipCheck(s)) => s
          }
          assertEquals(attempted, attempted.distinct)
          assert(nearEvents.exists {
            case ForgeEvent.Clock(_: ClockTrace.SystemClockMovedBack) => true
            case _                                                    => false
          })
          assertEquals(near, None)
          far match
            case Some(Left(Fatal.Internal(ClockProblem.SystemClockMovedBack(m, n)))) =>
              assert(n < m)
            case other => fail(s"expected the node to stop: $other")
      }
    )
  }

  test("ForgingStats counts as the reference does, the overcount of missed slots included") {
    import ForgeTrace.*
    val origin = xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin
    val steps = Vector(
      NodeNotLeader(10), // the first: lastSlot was zero, nothing missed
      NodeNotLeader(11), // consecutive
      NodeIsLeader(12),
      ForgedBlock(12, origin, Bytes(Vector(1)), 0, None),
      NodeCannotForge(13, CannotForge.KeyNotUsableYet(1, 2)), // lastSlot stays 12
      NodeNotLeader(16), // 16 - 12 = 4 missed, for the three slots 13, 14 and 15
      NodeIsLeader(18), // a leader slot after a gap counts nothing
      NodeNotLeader(19)
    )
    assertEquals(steps.foldLeft(ForgingStats())(_.step(_)), ForgingStats(1, 2, 1, 19, 4))
  }

  test("the gauges show the ForgingStats of the start of the slot, as the reference writes them") {
    import ForgeTrace.*
    for
      stats <- ForgeStats.create[IO]
      _ <- stats.record(0, StartLeadershipCheck(1))
      _ <- stats.record(0, NodeIsLeader(1))
      before <- stats.forgingStats
      _ <- stats.record(0, StartLeadershipCheck(2))
      after <- stats.forgingStats
    yield
      assertEquals(before.nodeIsLeader, 0L)
      assertEquals(after.nodeIsLeader, 1L)
  }

  test("the mempool is synchronised after adoption: the included transactions leave it") {
    val p = pool(kesStart = 50, seed = 2)
    val (t1, _) = TxKit.pay(1)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 2))
        _ <- h.mempool.add(t1, Source.Local)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        held <- h.mempool.snapshot
      yield assertEquals(held.entries.map(_.id), Vector.empty[Bytes])
    }
  }

  test("KES evolves across period boundaries and every block still validates") {
    val p = pool(kesStart = 50, seed = 3)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5400).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        events <- h.seen
        chain <- h.chain
      yield
        assertEquals(blocksMade(chain, p.id), Some(BigInt(forged.size)))
        // slots 5000..5399 are KES periods 50..53 (slotsPerKESPeriod = 100)
        assertEquals(forged.map(_.relativeKesPeriod).distinct.sorted, Vector(0, 1, 2, 3))
        assertEquals(
          events.collect { case ForgeEvent.KesEvolved(_, r) => r }.distinct.sorted,
          Vector(1, 2, 3).filter(r => forged.exists(_.relativeKesPeriod >= r))
        )
        assert(events.collect { case _: ForgeEvent.Failed => 1 }.isEmpty, events.toString)
    }
  }

  test("a pool without stake is never elected: NodeNotLeader in every slot") {
    val p = pool(kesStart = 50, seed = 4)
    val stranger = pool(kesStart = 50, seed = 5)
    withHandle {
      for
        h <- harness(stranger, ledgerWith(p, 1))
        _ <- (5000 until 5100).toList.traverse_(s => h.forger.step(s))
        forged <- forgedIn(h)
        events <- h.seen
        total <- h.forger.stats.total
      yield
        assertEquals(forged, Vector.empty)
        assertEquals(total.notLeader, 100L)
        assertEquals(events.collect { case _: ForgeEvent.Failed => 1 }.size, 0)
    }
  }

  test("a block whose VRF key is not the registered one is forged, refused and its txs removed") {
    val p = pool(kesStart = 50, seed = 4)
    val otherVrf =
      Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)(77.toByte))).toOption.get
    val wrong =
      p.copy(creds = PoolCredentials(otherVrf, p.creds.kes, p.creds.opCert, p.creds.coldVkey))
    val (t1, _) = TxKit.pay(1)
    withHandle {
      for
        h <- harness(wrong, ledgerWith(p, 2))
        _ <- h.mempool.add(t1, Source.Local)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        traces <- tracesIn(h)
        held <- h.mempool.snapshot
        chain <- h.chain
        events <- h.seen
        total <- h.forger.stats.total
      yield
        val refused = traces.collect { case ForgeTrace.ForgedInvalidBlock(s, _, e) => (s, e) }
        assert(refused.nonEmpty, traces.toString)
        // the leader check is on the stake alone; the block's validation finds the key wrong
        // (the reference's `VRFKeyWrongVRFKey`)
        assert(
          refused.forall((_, e) => e.failure.toString.contains("VrfKeyMismatch")),
          refused.toString
        )
        assertEquals(chain.blocks.size, 0)
        // the first refused block took the transaction, which left the pool
        assertEquals(held.entries, Vector.empty)
        // one block per slot led: no second attempt in the slot
        assertEquals(
          traces.collect { case ForgeTrace.ForgedBlock(s, _, _, _, _) => s },
          refused.map(_._1)
        )
        assertEquals(total.forgedInvalid, refused.size.toLong)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
    }
  }

  test("a key whose window has not begun cannot forge in a slot the pool leads (KeyNotUsableYet)") {
    val p = pool(kesStart = 60, seed = 6) // slots 5000.. are periods 50..: before the window
    val leads = (5000 until 5030).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5030).toList.traverse_(s => h.forger.step(s))
        traces <- tracesIn(h)
        events <- h.seen
        chain <- h.chain
        total <- h.forger.stats.total
      yield
        assert(leads.nonEmpty && leads.size < 30, leads.toString)
        assertEquals(chain.blocks.size, 0)
        // the leader check comes first: a slot not led is NodeNotLeader, a slot led is
        // NodeCannotForge, never NodeIsLeader (`checkShouldForge`, `Block/Forging.hs` 218-226)
        assertEquals(
          traces.collect { case ForgeTrace.NodeCannotForge(s, r) => (s.toInt, r) },
          leads.toVector.map(_ -> CannotForge.KeyNotUsableYet(50, 60))
        )
        assertEquals(total.cannotForge, leads.size.toLong)
        assertEquals(total.leaderSlots, 0L)
        assertEquals(total.notLeader, 30L - leads.size)
        // the key is updated in every slot, and not evolved before its window
        assertEquals(events.count(_.isInstanceOf[ForgeEvent.StateInfo]), 30)
        assertEquals(events.count(_.isInstanceOf[ForgeEvent.KesEvolved]), 0)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.Failed]), events.toString)
    }
  }

  test("a key past its window is poisoned: ForgeStateUpdateError at every slot until a reload") {
    val p = pool(kesStart = 0, seed = 6) // the window is periods 0..61, slot 6200 is period 62
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (6200 until 6203).toList.traverse_(s => h.forger.step(s))
        traces <- tracesIn(h)
        events <- h.seen
        total <- h.forger.stats.total
      yield
        val info = KesInfo(0, 62, 0)
        assertEquals(
          traces.collect { case ForgeTrace.ForgeStateUpdateError(s, e) => (s.toInt, e) },
          Vector(
            6200 -> KesEvolutionError.CouldNotEvolve(info, 62),
            6201 -> KesEvolutionError.KeyAlreadyPoisoned(info, 62),
            6202 -> KesEvolutionError.KeyAlreadyPoisoned(info, 62)
          )
        )
        // no leader check after a failed update
        assertEquals(total.notLeader + total.leaderSlots + total.cannotForge, 0L)
        assertEquals(total.stateUpdateError, 3L)
        assert(!events.exists(_.isInstanceOf[ForgeEvent.StateInfo]), events.toString)
    }
  }

  test("each step is traced in the reference's order (NodeKernel.hs 575-795)") {
    val p = pool(kesStart = 50, seed = 9)
    val leads = (5000 until 5040).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    val lead = leads.head
    val idle = (lead + 1 until 5040).find(s => !leads.contains(s)).get
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- h.forger.step(lead)
        _ <- h.forger.step(idle)
        led <- namesIn(h, lead)
        notLed <- namesIn(h, idle)
        events <- h.seen
      yield
        val common =
          Vector("StartLeadershipCheck", "BlockContext", "LedgerState", "LedgerView")
        assertEquals(
          led,
          common ++ Vector(
            "NodeIsLeader",
            "ForgeTickedLedgerState",
            "ForgingMempoolSnapshot",
            "ForgedBlock",
            "AdoptedBlock"
          )
        )
        assertEquals(notLed, common :+ "NodeNotLeader")
        // the key's state is reported between the ledger view and the leader check
        val kinds = events.map {
          case ForgeEvent.Loop(t) => t.productPrefix
          case other              => other.productPrefix
        }
        assertEquals(
          kinds.take(11),
          common ++ Vector(
            "StateInfo",
            "NodeIsLeader",
            "ForgeTickedLedgerState",
            "ForgingMempoolSnapshot",
            "ForgedBlock",
            "Forged",
            "AdoptedBlock"
          )
        )
        // the first block: on genesis, block number zero; the mempool synchronised with genesis
        val origin = xyz.kd5ujc.scarab.protocol.ChainSync.Point.Origin
        assert(events.contains(ForgeEvent.Loop(ForgeTrace.BlockContext(lead, 0, origin))))
        assert(
          events.contains(
            ForgeEvent.Loop(ForgeTrace.ForgingMempoolSnapshot(lead, origin, None, 5000))
          ),
          events.toString
        )
    }
  }

  test("past the next epoch the leader check takes the tip's current distribution, as TICKF") {
    val p = pool(kesStart = 50, seed = 9)
    val ledger = ledgerWith(p, 1)
    val tipEpoch = ledger.epoch.toBigInt
    // the pool has all the stake of the current distribution and none of the mark snapshot; our own
    // rules refuse every block, so the chain stays at its origin and each slot is forecast from it
    def led(h: Harness, epoch: BigInt): IO[Int] =
      val first = geometry.firstSlot(epoch)
      (first until first + 40).toList.traverse_(s => h.forger.step(s)) *>
        tracesIn(h).map(_.count {
          case ForgeTrace.NodeIsLeader(s) => geometry.epochOf(s) == epoch
          case _                          => false
        })
    withHandle {
      for
        h <- harness(
          p,
          ledger,
          ruleFailure = Some(xyz.kd5ujc.scarab.ledger.BlockFailure.HeaderDiffers)
        )
        next <- led(h, tipEpoch + 1)
        later <- led(h, tipEpoch + 2)
        chain <- h.chain
      yield
        assertEquals(chain.tipRef, None)
        // into the next epoch the mark distribution, which does not have the pool
        assertEquals(next, 0)
        // two epochs on, the current one again (`curEpochNo /= succ (nesEL nes)`)
        assert(later > 0, later.toString)
    }
  }

  test("the ledger view is forecast to the stability window after the predecessor, exclusive") {
    val p = pool(kesStart = 50, seed = 9)
    withHandle {
      for
        // from genesis (Origin) the bound is the window itself: slot 5000 is the first not covered
        atOrigin <- harness(p, ledgerWith(p, 1), forecastWindow = 5000)
        _ <- atOrigin.forger.step(5000)
        refused <- tracesIn(atOrigin)
        within <- harness(p, ledgerWith(p, 1), forecastWindow = 5001)
        _ <- within.forger.step(5000)
        allowed <- tracesIn(within)
        stats <- atOrigin.forger.stats.total
      yield
        assertEquals(
          refused.map(_.productPrefix),
          Vector("StartLeadershipCheck", "BlockContext", "LedgerState", "NoLedgerView")
        )
        assertEquals(
          refused.last,
          ForgeTrace.NoLedgerView(5000, OutsideForecastRange(None, 5000, 5000))
        )
        assertEquals(stats.noLedgerView, 1L)
        assertEquals(stats.skipped, Map(ForgeEpochStats.Skip.NoLedgerView -> 1L))
        assert(allowed.contains(ForgeTrace.LedgerView(5000)), allowed.toString)
    }
  }

  test("from a block, the ledger view reaches the slot before its successor plus the window") {
    val p = pool(kesStart = 50, seed = 9)
    withHandle {
      for
        first <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.foldLeftM(false) { (done, s) =>
          if done then IO.pure(true) else first.forger.step(s) *> forgedIn(first).map(_.nonEmpty)
        }
        built <- first.chain
        tip <- IO.fromOption(built.tipRef)(new AssertionError("nothing forged"))
        h <- harness(p, ledgerWith(p, 1), forecastWindow = 30)
        _ <- h.ref.set(built)
        _ <- h.forger.step(tip.slot + 31)
        _ <- h.forger.step(tip.slot + 30)
        traces <- tracesIn(h)
      yield
        assertEquals(
          traces.collect { case t: ForgeTrace.NoLedgerView => t },
          Vector(
            ForgeTrace.NoLedgerView(
              tip.slot + 31,
              OutsideForecastRange(Some(tip.slot), tip.slot + 31, tip.slot + 31)
            )
          )
        )
        assert(traces.contains(ForgeTrace.LedgerView(tip.slot + 30)), traces.toString)
    }
  }

  test("a slot already holding a block is forged again on the predecessor, as the reference does") {
    val p = pool(kesStart = 50, seed = 8)
    val (t1, _) = TxKit.pay(1)
    val (t2, id2) = TxKit.pay(2)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        _ <- h.mempool.add(t1, Source.Local)
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        first <- forgedIn(h)
        before <- h.chain
        // the tip is the block of the slot we lead (and so occupied), and its predecessor is the
        // block before it
        tip <- IO.fromOption(before.tipRef)(new AssertionError("nothing was forged"))
        base = before.blocks.dropRight(1).lastOption.map(_._1)
        _ <- h.mempool.add(t2, Source.Local)
        _ <- h.forger.step(tip.slot)
        all <- forgedIn(h)
        after <- h.chain
      yield
        assertEquals(first.lastOption.map(_.slot), Some(tip.slot))
        // one more block was forged, in the occupied slot, at the occupied block's height and on
        // its predecessor, carrying the transaction that arrived since
        val alternatives = all.drop(first.size)
        assertEquals(
          alternatives.map(f => (f.slot, f.blockNo, f.prev, f.txs)),
          Vector((tip.slot, tip.blockNo, base.map(_.hash), Vector(id2)))
        )
        assert(!alternatives.exists(_.hash == tip.hash))
        // the same issuer's second block of the height is not preferred: chain selection keeps ours
        assertEquals(after.tipRef.map(_.hash), Some(tip.hash))
    }
  }

  test("with one volatile block in the slot, the alternative is forged on the immutable anchor") {
    val p = pool(kesStart = 50, seed = 8)
    val (t2, id2) = TxKit.pay(2)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        // only the oldest volatile block is left on the anchor
        _ <- h.ref.update(c => c.copy(blocks = c.blocks.take(1)))
        chain <- h.chain
        anchor <- IO.fromOption(chain.anchor.block)(new AssertionError("no block is immutable"))
        only <- IO.fromOption(chain.tipRef)(new AssertionError("no volatile block"))
        first <- forgedIn(h)
        _ <- h.mempool.add(t2, Source.Local)
        _ <- h.forger.step(only.slot)
        all <- forgedIn(h)
        after <- h.chain
      yield
        assertNotEquals(only.hash, anchor.hash)
        assertEquals(only.blockNo, anchor.blockNo + 1)
        assertEquals(
          all.drop(first.size).map(f => (f.slot, f.blockNo, f.prev, f.txs)),
          Vector((only.slot, only.blockNo, Some(anchor.hash), Vector(id2)))
        )
        assertEquals(after.tipRef.map(_.hash), Some(only.hash))
    }
  }

  test("the alternative is not forged from a predecessor too old to forge from") {
    val p = pool(kesStart = 50, seed = 8)
    // the chain `built`, whose tip holds `slot`, under a bound one slot shorter than the age of the
    // predecessor `base` and under a bound equal to it: the reference forecasts the ledger view from
    // the ledger at the predecessor (`bcPrevPoint`), so the tip's own age does not decide
    def bounded(built: VolatileChain[MirrorState], slot: BigInt, base: BlockRef)(using
        Handle[IO, NodeError]
    ): IO[Unit] =
      val gap = slot - base.slot
      for
        tooOld <- harness(p, ledgerWith(p, 3), forecastWindow = gap - 1)
        _ <- tooOld.ref.set(built)
        refusedStep <- tooOld.forger.step(slot)
        refused <- tooOld.seen
        refusedStats <- tooOld.forger.stats.total
        within <- harness(p, ledgerWith(p, 3), forecastWindow = gap)
        _ <- within.ref.set(built)
        withinStep <- within.forger.step(slot)
        forged <- forgedIn(within)
        withinStats <- within.forger.stats.total
      yield
        assert(gap > 0, s"$slot $base")
        assertEquals(refusedStep, Right(()))
        assertEquals(
          refused.filter {
            case ForgeEvent.Loop(_: ForgeTrace.NoLedgerView) | _: ForgeEvent.Forged => true
            case _                                                                  => false
          },
          Vector(
            ForgeEvent.Loop(
              ForgeTrace.NoLedgerView(slot, OutsideForecastRange(Some(base.slot), slot, slot))
            )
          )
        )
        assertEquals(refusedStats.skipped, Map(ForgeEpochStats.Skip.NoLedgerView -> 1L))
        assertEquals(refusedStats.slotsChecked, 1L)
        assertEquals(withinStep, Right(()))
        assertEquals(forged.map(f => (f.slot, f.prev)), Vector((slot, Some(base.hash))))
        assertEquals(withinStats.skipped, Map.empty[ForgeEpochStats.Skip, Long])
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        built <- h.chain
        tip <- IO.fromOption(built.tipRef)(new AssertionError("nothing was forged"))
        base <- IO.fromOption(built.blocks.dropRight(1).lastOption.map(_._1))(
          new AssertionError("a single volatile block")
        )
        _ <- bounded(built, tip.slot, base)
        // only the oldest volatile block is left: its predecessor is the immutable anchor
        single = built.copy(blocks = built.blocks.take(1))
        only <- IO.fromOption(single.tipRef)(new AssertionError("no volatile block"))
        anchor <- IO.fromOption(single.anchor.block)(new AssertionError("no block is immutable"))
        _ <- bounded(single, only.slot, anchor)
      yield ()
    }
  }

  test("with the only block on genesis in the slot, the alternative is forged on genesis") {
    val p = pool(kesStart = 50, seed = 8)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 3))
        // the slots up to the first one the pool leads: one block, on genesis
        _ <- (5000 until 5040).toList.foldLeftM(false) { (done, s) =>
          if done then IO.pure(true)
          else h.forger.step(s) *> forgedIn(h).map(_.nonEmpty)
        }
        chain <- h.chain
        only <- IO.fromOption(chain.tipRef)(new AssertionError("nothing was forged"))
        first <- forgedIn(h)
        _ <- h.forger.step(only.slot)
        all <- forgedIn(h)
      yield
        assertEquals(chain.anchor.block, None)
        assertEquals(chain.blocks.map(_._1), Vector(only))
        // "same block no and same predecessor": the genesis point
        assertEquals(
          all.drop(first.size).map(f => (f.slot, f.blockNo, f.prev)),
          Vector((only.slot, only.blockNo, None))
        )
    }
  }

  test("a boundary block in the slot is extended in that slot, as the reference does") {
    val p = pool(kesStart = 50, seed = 8)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        // the tip, a block of a slot we lead, is marked as a boundary block: only the forger's
        // choice of predecessor is under test, not the adoption of a block after a Conway one
        _ <- h.ref.update(c =>
          c.blocks.lastOption.fold(c)((r, s) =>
            c.copy(blocks = c.blocks.dropRight(1) :+ (r.copy(boundary = true) -> s))
          )
        )
        tip <- h.chain.flatMap(c => IO.fromOption(c.tipRef)(new AssertionError("no tip")))
        first <- forgedIn(h)
        _ <- h.forger.step(tip.slot)
        all <- forgedIn(h)
        stats <- h.forger.stats.total
      yield
        assert(tip.boundary)
        assertEquals(
          all.drop(first.size).map(f => (f.slot, f.blockNo, f.prev)),
          Vector((tip.slot, tip.blockNo + 1, Some(tip.hash)))
        )
        assertEquals(stats.skipped, Map.empty[ForgeEpochStats.Skip, Long])
    }
  }

  test("a slot held by a later block, or by the immutable anchor, is skipped as occupied") {
    val p = pool(kesStart = 50, seed = 8)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        first <- forgedIn(h)
        initial <- h.forger.stats.total
        chain <- h.chain
        tip <- IO.fromOption(chain.tipRef)(new AssertionError("nothing was forged"))
        anchor <- IO.fromOption(chain.anchor.block)(new AssertionError("no block is immutable"))
        _ <- h.forger.step(tip.slot - 1)
        later <- h.forger.stats.total
        // no volatile block is left: the tip is the anchor, a block of a slot we lead
        _ <- h.ref.update(_.copy(blocks = Vector.empty))
        _ <- h.forger.step(anchor.slot)
        // the anchor is in a later slot than this one: immutable as well
        _ <- h.forger.step(anchor.slot - 1)
        immutable <- h.forger.stats.total
        all <- forgedIn(h)
        traces <- tracesIn(h)
      yield
        // each gives the slot up after its block context, with the fields of the reference's trace
        assertEquals(
          traces.collect {
            case t: ForgeTrace.BlockFromFuture => t
            case t: ForgeTrace.SlotIsImmutable => t
          },
          Vector(
            ForgeTrace.BlockFromFuture(tip.slot - 1, tip.slot),
            ForgeTrace.SlotIsImmutable(anchor.slot, anchor.point, anchor.blockNo),
            ForgeTrace.SlotIsImmutable(anchor.slot - 1, anchor.point, anchor.blockNo)
          )
        )
        assertEquals(
          traces.takeRight(2).map(_.productPrefix),
          Vector("StartLeadershipCheck", "SlotIsImmutable")
        )
        assertEquals(initial.skipped, Map.empty[ForgeEpochStats.Skip, Long])
        assertEquals(later.skipped, Map(ForgeEpochStats.Skip.BlockFromFuture -> 1L))
        assertEquals(
          immutable.skipped,
          Map(
            ForgeEpochStats.Skip.BlockFromFuture -> 1L,
            ForgeEpochStats.Skip.SlotIsImmutable -> 2L
          )
        )
        assertEquals((immutable.blockFromFuture, immutable.slotIsImmutable), (1L, 2L))
        assertEquals(all.size, first.size)
    }
  }

  test("an alternative that chain selection prefers replaces another pool's block in the slot") {
    val p = pool(kesStart = 50, seed = 8)
    val q = pool(kesStart = 50, seed = 11)
    val half = BigInt(500000)
    val ledger = TxKit
      .native(1)
      .copy(pd =
        PoolDistr.of(
          ForgedChain.snapshotOf(
            Vector((p.stake.credential, p.creds, half), (q.stake.credential, q.creds, half))
          )
        )
      )
    def elected(x: Pool, s: Int): Option[SelectView] =
      Leadership
        .check(s, epochNonce, x.creds.vrf, half, 2 * half, config.activeSlotsCoeff)
        .toOption
        .map(c => SelectView(0, s, Some(x.creds.coldVkey), x.creds.opCert.sequence, Some(c.output)))
    // the first slot both pools lead in which our block (p's) is preferred to theirs (q's)
    val battle = (5000 until 5200).find(s =>
      (elected(q, s), elected(p, s))
        .mapN(ChainSelection.prefers(ChainSelection.tiebreakerFor(Era.Conway), _, _))
        .getOrElse(false)
    )
    withHandle {
      for
        slot <- IO.fromOption(battle)(new AssertionError("no slot both lead that ours wins"))
        ours <- harness(p, ledger)
        theirs <- harness(q, ledger)
        _ <- theirs.forger.step(slot)
        block <- theirs.chain.flatMap(c => IO.fromOption(c.tipRef)(new AssertionError("no block")))
        raw <- theirs.raws.get.flatMap(r =>
          IO.fromOption(r.get(block.hash))(new AssertionError("no raw"))
        )
        header <- IO.fromEither(
          Block.decodeShelley(raw).bimap(e => new AssertionError(e.toString), _.header)
        )
        received <- ours.follower.adoptForged(header, raw)
        _ <- ours.forger.step(slot)
        forged <- forgedIn(ours)
        stats <- ours.forger.stats.total
        after <- ours.chain
      yield
        assertEquals(block.slot, BigInt(slot))
        assert(received.isInstanceOf[Follower.Adoption.Adopted], received.toString)
        // ours is an alternative to their block (same slot and height, on the same predecessor),
        // adopted by switching to it
        assertEquals(
          forged.map(f => (f.slot, f.blockNo, f.prev)),
          Vector((block.slot, block.blockNo, header.prevHash))
        )
        assertEquals(after.tipRef.map(_.hash), forged.headOption.map(_.hash))
        assertEquals(after.blocks.map(_._1.hash), forged.map(_.hash))
        assertEquals((stats.adopted, stats.didntAdopt), (1L, 0L))
    }
  }

  test("a block built on a tip that has since moved is lost, not adopted") {
    val p = pool(kesStart = 50, seed = 8)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        _ <- (5000 until 5040).toList.traverse_(s => h.forger.step(s))
        _ <- h.chain
        // an identical second block on the old tip (the genesis block position)
        again <- h.follower.adoptForged(
          Block.decodeShelley(blockOnGenesis(p, h)).toOption.get.header,
          blockOnGenesis(p, h)
        )
      yield again match
        case Follower.Adoption.Lost(_) => ()
        case other                     => fail(s"expected Lost, got $other")
    }
  }

  /** A valid-looking block with no predecessor (it extends the genesis, not the tip). */
  private def blockOnGenesis(p: Pool, @unused h: Harness): Bytes =
    val body = BlockBuilder.body(Vector.empty)
    val headerBody = BlockBuilder.headerBody(
      0,
      5001,
      None,
      p.creds.coldVkey,
      p.creds.vrf.publicKey,
      VrfCert(Bytes.fromArray(new Array[Byte](64)), Bytes.fromArray(new Array[Byte](80))),
      body.size,
      body.hash,
      p.creds.opCert,
      (10, 0)
    )
    BlockBuilder.block(headerBody, Bytes.fromArray(new Array[Byte](448)), body)

  test(
    "a slot battle is decided by chain selection: of two blocks at one height exactly one wins"
  ) {
    val p = pool(kesStart = 50, seed = 10)
    val ledger = ledgerWith(p, 1)
    val elected = (5000 until 5200).filter(s =>
      Leadership
        .check(s, epochNonce, p.creds.vrf, 1000000, 1000000, config.activeSlotsCoeff)
        .isRight
    )
    val pair = elected.zip(elected.tail).find((a, b) => b - a <= 5).get
    withHandle {
      for
        a <- harness(p, ledger)
        b <- harness(
          pool(kesStart = 50, seed = 10).copy(creds = p.creds, cold = p.cold, stake = p.stake),
          ledger
        )
        _ <- a.forger.step(pair._1)
        _ <- b.forger.step(pair._2)
        blockA <- a.chain.map(_.tipRef.get)
        blockB <- b.chain.map(_.tipRef.get)
        rawA <- a.raws.get.map(_(blockA.hash))
        rawB <- b.raws.get.map(_(blockB.hash))
        headerA = Block.decodeShelley(rawA).toOption.get.header
        headerB = Block.decodeShelley(rawB).toOption.get.header
        toA <- a.follower.adoptForged(headerB, rawB)
        toB <- b.follower.adoptForged(headerA, rawA)
        tipA <- a.chain.map(_.tipRef.map(_.hash))
        tipB <- b.chain.map(_.tipRef.map(_.hash))
      yield
        val bWins = xyz.kd5ujc.scarab.consensus.ChainSelection.prefers(
          xyz.kd5ujc.scarab.consensus.ChainSelection.Tiebreaker.Restricted(5),
          xyz.kd5ujc.scarab.consensus.SelectView.of(headerA),
          xyz.kd5ujc.scarab.consensus.SelectView.of(headerB)
        )
        // exactly one of the two blocks is preferred, and each node ends on that one
        if bWins then
          assert(toA.isInstanceOf[Follower.Adoption.Adopted], toA.toString)
          assert(toB.isInstanceOf[Follower.Adoption.Lost], toB.toString)
        else
          assert(toA.isInstanceOf[Follower.Adoption.Lost], toA.toString)
          assert(toB.isInstanceOf[Follower.Adoption.Adopted], toB.toString)
        val winner = if bWins then blockB.hash else blockA.hash
        assertEquals(tipA, Some(winner))
        assertEquals(tipB, Some(winner))
    }
  }

  test("a block our rules refuse is Invalid and leaves the chain alone") {
    val p = pool(kesStart = 50, seed = 9)
    withHandle {
      for
        h <- harness(p, ledgerWith(p, 1))
        raw = blockOnGenesis(p, h)
        result <- h.follower.adoptForged(Block.decodeShelley(raw).toOption.get.header, raw)
        chain <- h.chain
      yield
        assert(result.isInstanceOf[Follower.Adoption.Invalid], result.toString)
        assertEquals(chain.blocks.size, 0)
    }
  }

  test("the block takes the longest prefix of the still-valid held transactions that fits") {
    import xyz.kd5ujc.scarab.node.mempool.Capacity
    val base = TxKit.ledger(3)
    val small1 = MempoolStateFixture.entry(TxKit.pay(1)._1)
    // spends an output the ledger does not have: dropped without counting (`computeSnapshot`)
    val invalid = MempoolStateFixture.entry(TxKit.pay(9)._1)
    // three outputs (99 ada and the fee of 1 from an output of 100): larger than the others
    val large = MempoolStateFixture.entry(
      TxKit
        .tx(
          Vector(TestLedger.txIn(2)),
          Vector.fill(3)(TxKit.bob.address() -> BigInt(33000000)),
          BigInt(1000000),
          Vector(TxKit.alice)
        )
        ._1
    )
    val small3 = MempoolStateFixture.entry(TxKit.pay(3)._1)
    val units = xyz.kd5ujc.scarab.plutus.ExUnits(14000000, 10000000000L)
    def bytes(e: xyz.kd5ujc.scarab.node.mempool.Entry) = Capacity.measure(e).bytes
    def pick(entries: Vector[xyz.kd5ujc.scarab.node.mempool.Entry], room: Long) =
      BlockBuilder
        .select(base, 1001, 1, geometry, globals, entries, BlockBuilder.Limits(room + 1024, units))
        .chosen
        .map(_._1.id)
    assert(bytes(large) > bytes(small3))
    // the capacity is the body size less 1024, and each transaction counts its size plus 4
    assertEquals(bytes(small1), small1.tx.size + 4L)
    // room for the first and the large one exactly: the invalid one in between costs nothing
    assertEquals(
      pick(Vector(small1, invalid, large), bytes(small1) + bytes(large)),
      Vector(small1.id, large.id)
    )
    // one byte short of the large one: the selection ends there, though the next would fit
    assertEquals(
      pick(Vector(small1, large, small3), bytes(small1) + bytes(large) - 1),
      Vector(small1.id)
    )
  }

  test("body encoding: the computed size equals the encoded size and the block decodes back") {
    val (t1, _) = TxKit.pay(1)
    val (t2, _) = TxKit.pay(2)
    def entry(raw: Bytes) = MempoolStateFixture.entry(raw)
    val pieces = Vector(t1, t2).map(r => BlockBuilder.piece(entry(r)).toOption.get)
    List(Vector.empty, pieces.take(1), pieces).foreach { ps =>
      val body = BlockBuilder.body(ps)
      assertEquals(BlockBuilder.bodySize(ps), body.size)
    }
    val p = pool(50)
    val body = BlockBuilder.body(pieces)
    val hb = BlockBuilder.headerBody(
      7,
      123456,
      Some(Blake2b.hash256.hash(Bytes(Vector(1)))),
      p.creds.coldVkey,
      p.creds.vrf.publicKey,
      VrfCert(Bytes.fromArray(new Array[Byte](64)), Bytes.fromArray(new Array[Byte](80))),
      body.size,
      body.hash,
      p.creds.opCert,
      (10, 3)
    )
    val raw = BlockBuilder.block(hb, Bytes.fromArray(new Array[Byte](448)), body)
    val decoded = Block.decodeShelley(raw).toOption.get.asInstanceOf[Block.Shelley]
    assertEquals(decoded.bodySize, body.size)
    assertEquals(decoded.bodyHash, body.hash)
    assertEquals(decoded.header.bodyHash, body.hash)
    assertEquals(decoded.header.blockNo, BigInt(7))
    assertEquals(decoded.header.slot, BigInt(123456))
    assertEquals(decoded.header.protocolVersion, (BigInt(10), BigInt(3)))
    assertEquals(decoded.header.bodyRaw, hb)
    assertEquals(decoded.transactions.map(_.id), body.ids)
    assertEquals(decoded.era, xyz.kd5ujc.scarab.protocol.Era.Conway)
  }

  test("leadership: the probability of winning follows the stake, and a win is checkable") {
    val key = Draft03Prover.SigningKey.fromSeed(hex(Array.fill(32)(9.toByte))).toOption.get
    val f = Leader.Fraction.checked(1, 20).toOption.get
    def wins(stake: Int, total: Int) =
      (0 until 4000).count(s => Leadership.check(s, epochNonce, key, stake, total, f).isRight)
    val full = wins(1, 1)
    val tenth = wins(1, 10)
    assert(full > 120 && full < 290, s"expected about 200 of 4000 at f = 1/20, got $full")
    assert(
      tenth > 5 && tenth < 50,
      s"expected about 20 of 4000 at a tenth of the stake, got $tenth"
    )
    assertEquals(Leadership.check(1, epochNonce, key, 0, 10, f), Left(ForgeError.NotLeader(1)))
    // with an active-slot coefficient of one every slot is led, even with no stake, as
    // `checkLeaderNatValue` decides it
    val one = Leader.Fraction.checked(1, 1).fold(e => fail(e.message), identity)
    assert(Leadership.check(1, epochNonce, key, 0, 10, one).isRight)
  }

  test("leadership preserves threshold and VRF input causes") {
    val key = Draft03Prover.SigningKey
      .fromSeed(hex(Array.fill(32)(9.toByte)))
      .fold(e => fail(e.toString), identity)
    val active = Leader.Fraction.checked(3, 4).fold(e => fail(e.message), identity)
    assertEquals(
      Leadership.check(1, epochNonce, key, 2, 1, config.activeSlotsCoeff),
      Left(ForgeError.LeaderThreshold(Leader.Error.InvalidFraction(2, 1)))
    )
    assertEquals(
      Leadership.check(1, epochNonce, key, 1, 1, active),
      Left(ForgeError.LeaderThreshold(Leader.Error.UnsupportedActiveSlotsCoefficient(3, 4)))
    )
    val expected = xyz.kd5ujc.scarab.crypto.vrf.PraosVrfCertificate.Slot
      .fromBigInt(-1)
      .left
      .toOption
      .getOrElse(fail("negative slot accepted"))
    assertEquals(
      Leadership.check(-1, epochNonce, key, 1, 1, config.activeSlotsCoeff),
      Left(ForgeError.VrfInput(expected))
    )
  }
