// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.mtl.Handle
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{NodeError, Nonce}
import xyz.kd5ujc.scarab.node.mempool.{Source, TxKit}

/** The differential test of the scheduled forger (#1101, §7): the same scenario run through the
  * scheduled forger on the test clock and through the per-slot decision ([[Forger.step]]) at each
  * slot's onset, the same changes made to both, and every slot's outcome and every block handed
  * over compared. The schedule's decisions and certificates against [[Leadership.check]] on every
  * slot of generated epochs are `LeaderScheduleSuite` 's property; this suite drives the wired
  * forger.
  */
class ScheduledForgerSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScheduleFixture:

  /** One pool with all the stake in every snapshot, its ledger in epoch 5. */
  private def alone(p: Pool) = ledgerIn(5, Vector(p -> BigInt(1000000)))

  private def same(c: Compared)(using munit.Location): Unit =
    assertEquals(c.scheduledEndings, c.perSlotEndings, "each slot's outcome")
    assertEquals(c.scheduledBlocks, c.perSlotBlocks, "each block handed over, by its hash")

  private def mixed(c: Compared)(using munit.Location): Unit =
    val kinds = c.perSlotEndings.map(_._2).toSet
    assert(kinds.contains("NodeNotLeader"), s"some slot is not led: $kinds")
    assert(c.perSlotBlocks.nonEmpty, "some slot is led and forged")

  test("an epoch boundary: each slot's outcome and each block are the per-slot decision's") {
    withHandle {
      compared(Scenario(9, alone, 5950, 6030)).map { c =>
        same(c)
        mixed(c)
        assert(c.perSlotBlocks.exists(_._1 >= 6000), "the next epoch is led too")
      }
    }
  }

  test("a fork that replaces the blocks past the next epoch's freeze, its schedule under way") {
    // the next epoch's inputs are known once the tip is at or past 5960 (4k/f = 40 slots before
    // 6000); the fork takes the chain back before 5958, and the slots after it forge anew
    withHandle {
      compared(
        Scenario(9, alone, 5950, 6020, before = Vector(BigInt(5968) -> rolledBackBefore(5958)))
      )
        .map { c =>
          same(c)
          mixed(c)
        }
    }
  }

  test("a fork across the previous boundary that changes the mark distribution") {
    val stranger = pool(kesStart = 50, seed = 77)
    def marked(p: Pool) =
      ledgerIn(
        5,
        Vector(p -> BigInt(1000000)),
        mark = Some(Vector(p -> BigInt(250000), stranger -> BigInt(750000)))
      )
    withHandle {
      compared(
        Scenario(
          9,
          alone,
          5975,
          6025,
          before = Vector(BigInt(5985) -> (side => forkedTo(mirrorState(marked(side.pool)))(side)))
        )
      ).map { c =>
        same(c)
        mixed(c)
      }
    }
  }

  test("a led slot in an epoch's first slot whose tip already holds a block of that slot") {
    // every slot is led (f = 1); a block of slot 6000 with a transaction arrives just before it,
    // so 6000 is forged again on its predecessor in epoch 5, its inputs forecast from that state
    val (t1, _) = TxKit.pay(1)
    def arrived(seed: Int)(using Handle[IO, NodeError]): Change = side =>
      for
        elsewhere <- harness(
          side.pool.copy(creds = side.pool.creds.copy(kes = kesOf(seed))),
          ledgerIn(5, Vector(side.pool -> BigInt(1000000)), funds = 2),
          clock = Some(IO.pure(geometry.slotStart(6000))),
          praos = everySlot
        )
        current <- side.h.ref.get
        _ <- elsewhere.ref.set(current)
        _ <- elsewhere.mempool.add(t1, Source.Local)
        _ <- elsewhere.forger.step(6000)
        moved <- elsewhere.ref.get
        _ <- side.h.ref.set(moved)
      yield ()
    withHandle {
      compared(
        Scenario(
          3,
          p => ledgerIn(5, Vector(p -> BigInt(1000000)), funds = 2),
          5996,
          6003,
          praos = everySlot,
          before = Vector(BigInt(6000) -> arrived(3))
        )
      ).map { c =>
        same(c)
        assert(c.perSlotBlocks.exists(_._1 == 6000), "slot 6000 is forged on the predecessor")
        assert(
          c.perSlotEndings.exists((s, kind) => s == 6000 && kind != "NodeNotLeader"),
          c.perSlotEndings.toString
        )
      }
    }
  }

  test("slots the schedule does not cover are decided at the slot, as the per-slot decision does") {
    withHandle {
      compared(Scenario(9, alone, 5000, 5040, scheduler = false)).map { c =>
        same(c)
        mixed(c)
      }
    }
  }

  test("a SIGHUP that installs another VRF key mid-epoch") {
    withHandle {
      compared(Scenario(9, alone, 5000, 5030, before = Vector(BigInt(5015) -> otherVrf(31, 9))))
        .map { c =>
          same(c)
          // blocks of the other key are refused by the rules, which find it is not the registered one
          assert(
            c.perSlotEndings.exists((s, kind) => s >= 5015 && kind == "ForgedInvalidBlock"),
            c.perSlotEndings.toString
          )
        }
    }
  }

  test("forging switched off and on again") {
    withHandle {
      compared(
        Scenario(
          9,
          alone,
          5000,
          5030,
          before = Vector(BigInt(5010) -> forgingOff, BigInt(5020) -> forgingOn(9))
        )
      ).map { c =>
        same(c)
        assert(!c.perSlotEndings.exists((s, _) => s >= 5010 && s < 5020), "nothing while off")
        assert(c.perSlotEndings.exists(_._1 >= 5020), "forging again once on")
      }
    }
  }

  test("a start in the middle of an epoch") {
    withHandle {
      compared(Scenario(9, alone, 5500, 5540)).map { c =>
        same(c)
        mixed(c)
      }
    }
  }

  // ---- the early attempt (§5.3): one change at each point where it can fall

  /** Two pools with half the stake each, `a` ours, and the slots: `a` leads `slot` and not the slot
    * before it, which `b` leads, and `b` leads the slot after it.
    */
  private final case class Pair(a: Int, b: Int, slot: BigInt)

  private lazy val pair: Pair =
    def leads(seed: Int, slot: BigInt) =
      Leadership.check(slot, epochNonce, vrfOf(seed), 1, 2, config.activeSlotsCoeff).isRight
    (for
      a <- (1 to 60).iterator
      b <- (61 to 120).iterator
      s <- (5004 until 5040).iterator.map(BigInt(_))
      if leads(a, s) && !leads(a, s - 1) && leads(b, s - 1) && leads(b, s + 1)
    yield Pair(a, b, s)).next()

  private def halves(b: Pool)(a: Pool) =
    ledgerIn(5, Vector(a -> BigInt(500000), b -> BigInt(500000)))

  /** A block `b` forges in `slot` on the chain of the side, put on it. */
  private def fromOther(b: Pool, slot: BigInt)(using Handle[IO, NodeError]): Change = side =>
    for
      other <- harness(
        b.copy(creds = b.creds.copy(kes = kesOf(pair.b))),
        halves(b)(side.pool),
        clock = Some(IO.pure(geometry.slotStart(slot)))
      )
      current <- side.h.ref.get
      _ <- other.ref.set(current)
      _ <- other.forger.step(slot)
      moved <- other.ref.get
      _ <- side.h.ref.set(moved)
    yield ()

  private val points: Vector[(String, BigInt => Forger.Point)] = Vector(
    "between the wake and the re-check" -> (s => Forger.Point.Context(s)),
    "between the re-check and the signing" -> (s => Forger.Point.Rechecked(s)),
    "between the signing and the onset" -> (s => Forger.Point.Signed(s))
  )

  private def changes(b: Pool)(using Handle[IO, NodeError]): Vector[(String, Change)] = Vector(
    "a block of an earlier slot that moves the tip" -> fromOther(b, pair.slot - 1),
    "a fork that changes the nonce" -> (side =>
      forkedTo(
        stateWith(halves(b)(side.pool), Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(8)))))
      )(side)
    ),
    "a SIGHUP that installs another VRF key" -> otherVrf(33, pair.a),
    "a SIGHUP that installs another KES key" -> otherKes(pair.a + 200, 50),
    "a SIGHUP that installs another certificate" -> otherCertificate(pair.a, 50),
    "forging switched off" -> forgingOff
  )

  points.foreach { (where, point) =>
    test(s"the early attempt: each change $where gives the per-slot decision's outcome") {
      val b = pool(kesStart = 50, seed = pair.b)
      withHandle {
        changes(b).traverse_ { (what, change) =>
          compared(
            Scenario(
              pair.a,
              halves(b),
              pair.slot - 2,
              pair.slot + 2,
              at = Vector((point(pair.slot), pair.slot, change))
            )
          ).map { c =>
            assertEquals(c.scheduledEndings, c.perSlotEndings, what)
            assertEquals(c.scheduledBlocks, c.perSlotBlocks, what)
            // a block built before a change of credentials is never handed over
            val discarded = c.scheduled.collect {
              case ForgeEvent.Discarded(s, DiscardReason.Credentials, Some(hash)) => (s, hash)
            }
            assert(
              discarded.forall(d => !c.scheduledBlocks.contains(d)),
              s"$what: a block discarded for its credentials was handed over"
            )
          }
        }
      }
    }
  }

  test("an early refusal that clears before the onset is forged; one that stands is logged once") {
    val b = pool(kesStart = 50, seed = pair.b)
    // a block of the slot after it puts the tip in the future of the slot: refused early
    // (`blockFromFuture`); taken away before the onset, the slot is forged
    withHandle {
      for
        cleared <- compared(
          Scenario(
            pair.a,
            halves(b),
            pair.slot - 2,
            pair.slot + 1,
            at = Vector(
              (Forger.Point.Waking(pair.slot), pair.slot, fromOther(b, pair.slot + 1)),
              (Forger.Point.Refused(pair.slot), pair.slot, rolledBackBefore(pair.slot + 1))
            )
          )
        )
        stands <- compared(
          Scenario(
            pair.a,
            halves(b),
            pair.slot - 2,
            pair.slot,
            at = Vector((Forger.Point.Waking(pair.slot), pair.slot, fromOther(b, pair.slot + 1)))
          )
        )
      yield
        same(cleared)
        assert(cleared.scheduledBlocks.exists(_._1 == pair.slot), "the slot is forged")
        assert(
          cleared.scheduled.contains(ForgeEvent.Discarded(pair.slot, DiscardReason.Refused, None)),
          "the early refusal was discarded"
        )
        same(stands)
        assertEquals(
          stands.scheduledEndings.filter(_._1 == pair.slot),
          Vector(pair.slot -> "BlockFromFuture")
        )
    }
  }

  // ---- what an operator sees of the slots not led (#1101, Q1 and Q2)

  /** The scheduled forger's events from `from` to `until`, and its counters of epoch 5. */
  private def ran(
      p: Pool,
      from: BigInt,
      until: BigInt,
      forecastWindow: BigInt = 1000000
  ): IO[(Vector[ForgeEvent], ForgeEpochStats)] =
    cats.effect.testkit.TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(
            p,
            alone(p),
            live = true,
            offsetMillis = ((from - 5000) * 1000).toLong,
            unbounded = true,
            forecastWindow = forecastWindow
          )
          fiber <- h.forger.run.start
          _ <- h.settled(until)
          _ <- fiber.cancel
          events <- h.seen
          stats <- h.forger.stats.epoch(5)
        yield (events, stats)
      }
    )

  private def traced[T <: ForgeTrace](events: Vector[ForgeEvent])(using
      t: scala.reflect.ClassTag[T]
  ): Vector[T] =
    events.collect { case ForgeEvent.Loop(t(trace)) => trace }

  test(
    "a slot not led logs nodeNotLeader alone and is counted; a led slot logs its whole sequence"
  ) {
    val p = pool(kesStart = 50, seed = 9)
    ran(p, 5000, 5040).map { (events, stats) =>
      val led = traced[ForgeTrace.NodeIsLeader](events).map(_.slot)
      val notLed = traced[ForgeTrace.NodeNotLeader](events).map(_.slot)
      assert(led.nonEmpty && notLed.nonEmpty, s"$led $notLed")
      assertEquals((led ++ notLed).sorted, (BigInt(5000) to BigInt(5040)).toVector)
      // the counters as before: every slot checked, led or not
      assertEquals(stats.slotsChecked, 41L)
      assertEquals(stats.leaderSlots, led.size.toLong)
      assertEquals(stats.notLeader, notLed.size.toLong)
      // the reference's Info and Debug lines of the slot's steps only for a led slot
      assertEquals(traced[ForgeTrace.StartLeadershipCheck](events).map(_.slot), led)
      assertEquals(traced[ForgeTrace.BlockContext](events).map(_.slot), led)
      assertEquals(traced[ForgeTrace.LedgerView](events).map(_.slot), led)
      // the KES key's state in each led slot's sequence, and once at the period's first slot
      val states = events.count(_.isInstanceOf[ForgeEvent.StateInfo])
      assertEquals(states, led.size + (if led.contains(BigInt(5000)) then 0 else 1))
    }
  }

  test("near the KES key's expiry stateInfo is logged in every slot whose checks reach the key") {
    // a certificate from period 0: period 56 has six periods left, a Warning
    val p = pool(kesStart = 0, seed = 9)
    ran(p, 5600, 5620).map { (events, _) =>
      val states = events.collect { case ForgeEvent.StateInfo(info) => info }
      assertEquals(states.size, 21, "one per slot")
      assert(states.forall(_.remaining <= Forger.KesWarning), states.toString)
    }
  }

  test("a slot not led that a check before the leader check refuses logs that check's line alone") {
    // from the origin the ledger view reaches the window itself: every slot from 5000 is beyond it
    val p = pool(kesStart = 50, seed = 9)
    ran(p, 5000, 5020, forecastWindow = 5000).map { (events, stats) =>
      val refused = traced[ForgeTrace.NoLedgerView](events).map(_.slot)
      assertEquals(refused, (BigInt(5000) to BigInt(5020)).toVector)
      assertEquals(traced[ForgeTrace.NodeNotLeader](events), Vector.empty)
      assertEquals(stats.noLedgerView, 21L)
      assertEquals(stats.slotsChecked, 21L)
      // a slot the schedule leads is attempted, with its whole sequence; one it does not, not
      val started = traced[ForgeTrace.StartLeadershipCheck](events).map(_.slot)
      assert(started.size < refused.size, started.toString)
    }
  }
