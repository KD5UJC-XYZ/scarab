// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{IO, Ref}
import scala.concurrent.duration.*
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

  /** Each SIGHUP of the scenario switched the credentials or disabled forging on both paths: a
    * reload refused on both would leave them equal and test nothing.
    */
  private def reloaded(c: Compared)(using munit.Location): Unit =
    def switched(events: Vector[ForgeEvent]) = events.count {
      case _: ForgeEvent.KesReloaded                                             => true
      case ForgeEvent.BlockForgingUpdate(BlockForgingState.DisabledBlockForging) => true
      case _                                                                     => false
    }
    assert(switched(c.scheduled) > 0, "the reload took effect on the scheduled path")
    assertEquals(switched(c.scheduled), switched(c.perSlot), "the reloads on both paths")
    assert(
      !c.scheduled.exists(_.isInstanceOf[ForgeEvent.KesReloadRefused]),
      c.scheduled.collect { case r: ForgeEvent.KesReloadRefused => r }.toString
    )

  private def mixed(c: Compared)(using munit.Location): Unit =
    val kinds = c.perSlotEndings.map(_._2).toSet
    assert(kinds.contains("NodeNotLeader"), s"some slot is not led: $kinds")
    assert(c.perSlotBlocks.nonEmpty, "some slot is led and forged")

  test("an epoch boundary: each slot's outcome and each block are the per-slot decision's") {
    // on the follower's committed chain, as the node runs it: the next epoch's schedule is computed
    // from it once the tip passes the freeze slot, and is complete before the epoch starts
    withHandle {
      compared(Scenario(9, alone, 5950, 6030, committed = true)).map { c =>
        same(c)
        mixed(c)
        assert(c.perSlotBlocks.exists(_._1 >= 6000), "the next epoch is led too")
        val scheduled = c.scheduled.indexWhere {
          case ForgeEvent.Scheduled(epoch, _, _, _) => epoch == 6
          case _                                    => false
        }
        val first = c.scheduled.indexWhere(e => endedIn(Vector(e)).exists(_ >= 6000))
        assert(scheduled >= 0 && scheduled < first, "epoch 6's schedule before its first slot")
        val lines = c.scheduled.collect { case s: ForgeEvent.Scheduled if s.epoch == 6 => s }
        val led = c.perSlot.collect {
          case ForgeEvent.Loop(ForgeTrace.NodeIsLeader(s)) if s >= 6000 => s
        }
        // one line: the inputs derived from a tip in epoch 5 past the freeze are those of a tip in 6
        assertEquals(lines.size, 1, lines.toString)
        assertEquals(
          lines.head.slots.filter(_ <= 6030),
          led,
          "the schedule's led slots are the per-slot check's"
        )
        // all the stake at f = 1/2: half the epoch's 1000 slots, on average
        assertEqualsDouble(lines.head.expected, 500.0, 1e-9)
        // k = 5 blocks past the freeze slot made a block at or after it immutable before the epoch
        assert(lines.head.settled, lines.head.toString)
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
          reloaded(c)
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
        reloaded(c)
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

  /** Each change, and whether it is a reload, which must take effect on both paths. */
  private def changes(b: Pool)(using Handle[IO, NodeError]): Vector[(String, Change, Boolean)] =
    Vector(
      ("a block of an earlier slot that moves the tip", fromOther(b, pair.slot - 1), false),
      (
        "a fork that changes the nonce",
        side =>
          forkedTo(
            stateWith(halves(b)(side.pool), Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(8)))))
          )(side),
        false
      ),
      ("a SIGHUP that installs another VRF key", otherVrf(33, pair.a), true),
      ("a SIGHUP that installs another KES key", otherKes(pair.a + 200, 50), true),
      ("a SIGHUP that installs another certificate", otherCertificate(pair.a, 50), true),
      ("forging switched off", forgingOff, true)
    )

  points.foreach { (where, point) =>
    test(s"the early attempt: each change $where gives the per-slot decision's outcome") {
      val b = pool(kesStart = 50, seed = pair.b)
      withHandle {
        changes(b).traverse_ { (what, change, reload) =>
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
            if reload then reloaded(c)
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

  // ---- a led slot in the first slot of a KES period: never forged before the period begins

  /** The first slot of KES period 51, for a certificate from period 50. */
  private val periodStart = BigInt(5100)

  test(
    "a led first slot of a KES period, and a reissued certificate installed in its wake margin"
  ) {
    // between the forging fiber's wake for the slot and its onset, a SIGHUP installs a certificate
    // issued again (counter 1) for the same KES key: the clock is still in period 50
    val seed = seedLeading(Vector(periodStart))
    withHandle {
      compared(
        Scenario(
          seed,
          alone,
          periodStart - 2,
          periodStart + 1,
          margin = Vector(periodStart -> otherCertificate(seed, 50))
        )
      ).map { c =>
        assertEquals(
          c.scheduled.collect { case r: ForgeEvent.KesReloadRefused => r },
          Vector.empty,
          "the scheduled forger refused the reload"
        )
        reloaded(c)
        same(c)
        def counter(events: Vector[ForgeEvent]) = events.collect {
          case f: ForgeEvent.Forged if f.slot == periodStart => f.opCertCounter
        }
        assertEquals(counter(c.perSlot), Vector(BigInt(1)), "the per-slot decision's certificate")
        assertEquals(counter(c.scheduled), Vector(BigInt(1)), "signed under the new certificate")
      }
    }
  }

  test("the certificate's expiry at a led first slot of a KES period: the key erased no earlier") {
    // a certificate from period 0: its window ends at period 62, whose first slot the pool leads and
    // whose second it does not; the key's state is read between each slot's wake and its onset on
    // both paths
    val expiry = BigInt(6200)
    val seed = seedLeading(Vector(expiry), Vector(expiry + 1))
    withHandle {
      for
        erased <- Ref.of[IO, Vector[(Way, BigInt, Boolean)]](Vector.empty)
        probe = (slot: BigInt) =>
          (side: Side) =>
            currentHot(side.h.forger.hot).flatMap(c =>
              erased.update(_ :+ (side.way, slot, c.kes.isForgotten))
            )
        c <- compared(
          Scenario(
            seed,
            p => ledgerIn(6, Vector(p -> BigInt(1000000))),
            expiry - 2,
            expiry + 2,
            kesStart = 0,
            margin = Vector(expiry -> probe(expiry), (expiry + 1) -> probe(expiry + 1))
          )
        )
        read <- erased.get
      yield
        def of(way: Way) = read.collect { case (w, slot, gone) if w == way => slot -> gone }
        assertEquals(
          of(Way.Scheduled),
          of(Way.PerSlot),
          "the key erased, by the slot whose wake margin it was read in"
        )
        assertEquals(of(Way.PerSlot), Vector(expiry -> false, (expiry + 1) -> true))
        same(c)
        def failures(events: Vector[ForgeEvent]) = events.collect {
          case ForgeEvent.Loop(ForgeTrace.ForgeStateUpdateError(slot, error)) => slot -> error
        }
        assertEquals(
          failures(c.scheduled),
          failures(c.perSlot),
          "each slot's KES failure line: its kind and the key's state"
        )
        assert(
          failures(c.perSlot).headOption.exists {
            case (slot, KesEvolutionError.CouldNotEvolve(_, _))  => slot == expiry
            case (_, KesEvolutionError.KeyAlreadyPoisoned(_, _)) => false
          },
          failures(c.perSlot).toString
        )
        assertEquals(
          c.scheduled.collect { case d: ForgeEvent.Discarded if d.slot == expiry => d },
          Vector.empty,
          "an attempt at the expiry slot made before its period and discarded"
        )
        // the expiry is a KES period change: the slot driver, which ends its first slot at the key's
        // failure, logs that slot's start first, and once in the period (#1101, Q2)
        def lines(slot: BigInt) = c.scheduled.collect {
          case ForgeEvent.Loop(t) if t.slot == slot => t.productPrefix
        }
        assertEquals(lines(expiry), Vector("StartLeadershipCheck", "ForgeStateUpdateError"))
        assertEquals(lines(expiry + 1), Vector("ForgeStateUpdateError"))
    }
  }

  test(
    "a pending early attempt made again after a reload, the clock moved back a period: no key moved"
  ) {
    // 5101 is led and built early, the clock in 5100 (period 51). In its wake margin the wall clock
    // moves back a second, into period 50, within the rewind allowance; a reload installs another
    // KES key under a certificate from period 50 (counter 0), taken at relative period 0, and the
    // early attempt is made again; a step later a certificate issued again for that key (counter
    // 1) is installed, the clock still in period 50
    val s = BigInt(5101)
    val seed = seedLeading(Vector(s))
    val other = seed + 200
    withHandle {
      compared(
        Scenario(
          seed,
          alone,
          s - 2,
          s + 1,
          behind = Some(s -> 1.second),
          margin = Vector(s -> otherKes(other, 50, counter = 0), s -> otherKes(other, 50))
        )
      ).map { c =>
        assertEquals(
          c.scheduled.collect { case r: ForgeEvent.KesReloadRefused => r },
          Vector.empty,
          "the scheduled forger refused a reload"
        )
        reloaded(c)
        same(c)
        def counter(events: Vector[ForgeEvent]) = events.collect {
          case f: ForgeEvent.Forged if f.slot == s => f.opCertCounter
        }
        assertEquals(counter(c.perSlot), Vector(BigInt(1)), "the per-slot decision's certificate")
        assertEquals(
          counter(c.scheduled),
          Vector(BigInt(1)),
          "signed under the reissued certificate"
        )
      }
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
          _ <- h
            .settled(until)
            .timeout(((until - from).toLong + 120).seconds)
            .guarantee(fiber.cancel)
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
    // the first slot, which announces KES period 50, is not led
    val first = BigInt(5000)
    val p = pool(kesStart = 50, seed = seedLeading(Nil, Vector(first)))
    ran(p, 5000, 5040).map { (events, stats) =>
      val led = traced[ForgeTrace.NodeIsLeader](events).map(_.slot)
      val notLed = traced[ForgeTrace.NodeNotLeader](events).map(_.slot)
      assert(led.nonEmpty && notLed.nonEmpty, s"$led $notLed")
      assertEquals((led ++ notLed).sorted, (BigInt(5000) to BigInt(5040)).toVector)
      // the counters as before: every slot checked, led or not, and once
      assertEquals(stats.slotsChecked, 41L)
      assertEquals(stats.leaderSlots, led.size.toLong)
      assertEquals(stats.notLeader, notLed.size.toLong)
      // the reference's Info and Debug lines of the slot's steps only for a led slot, but for
      // `startLeadershipCheck`, also logged on a slot not led that announces the KES period
      assertEquals(
        traced[ForgeTrace.StartLeadershipCheck](events).map(_.slot),
        (first +: led).sorted
      )
      assertEquals(traced[ForgeTrace.BlockContext](events).map(_.slot), led)
      assertEquals(traced[ForgeTrace.LedgerView](events).map(_.slot), led)
      // the KES key's state in each led slot's sequence, and once at the period's first slot
      val states = events.count(_.isInstanceOf[ForgeEvent.StateInfo])
      assertEquals(states, led.size + 1)
      // that slot's lines in the reference's order: its start, the key's state, its end
      val start = events.indexOf(ForgeEvent.Loop(ForgeTrace.StartLeadershipCheck(first)))
      assert(start >= 0, events.toString)
      assert(events(start + 1).isInstanceOf[ForgeEvent.StateInfo], events.toString)
      assertEquals(events(start + 2), ForgeEvent.Loop(ForgeTrace.NodeNotLeader(first)))
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

  test("a block forged ahead of its slot keeps its time: forged before the onset, adopted after") {
    val p = pool(kesStart = 50, seed = 9)
    cats.effect.testkit.TestControl.executeEmbed(
      withHandle {
        for
          h <- harness(p, alone(p), live = true, unbounded = true)
          fiber <- h.forger.run.start
          _ <- h.settled(5020).timeout(2.minutes).guarantee(fiber.cancel)
          stamped <- h.stamped.get
        yield
          val forged = stamped.collect { case (at, f: ForgeEvent.Forged) => f.slot -> at }
          val adopted = stamped.collect {
            case (at, ForgeEvent.Loop(ForgeTrace.AdoptedBlock(slot, _, _, _))) => slot -> at
          }
          // the first slot is forged at once; the later led slots ahead of their onsets
          val ahead = forged.filter((slot, at) => at.isBefore(geometry.slotStart(slot)))
          assert(ahead.size >= forged.size - 1 && ahead.nonEmpty, forged.toString)
          assert(
            adopted.forall((slot, at) => !at.isBefore(geometry.slotStart(slot))),
            adopted.toString
          )
      }
    )
  }

  test(
    "a transaction withheld by a discarded early attempt stays in the mempool for the re-forge"
  ) {
    // the early attempt of the slot, on its first parent, has no verdict on a held registration
    // (its deposit beyond the ledger's range, a ledger no chain state holds): it would leave the
    // mempool; before the onset a block of the slot before moves the tip, the attempt is
    // discarded, and the slot is forged again on the new parent, where the registration applies
    import xyz.kd5ujc.scarab.node.mempool.TxKit.*
    val keyHash = Bytes.fromArray(Array.fill(28)(9.toByte))
    val certificate =
      head(4, 1) ++ head(4, 2) ++ head(0, 0) ++ head(4, 2) ++ head(0, 0) ++ bstr(keyHash)
    // 100 ada in, 97 out, a fee of 1 and the key deposit of 2
    val (registration, id) = TxKit.tx(
      Vector(xyz.kd5ujc.scarab.ledger.TestLedger.txIn(1)),
      Vector(bob.address() -> BigInt(97000000)),
      BigInt(1000000),
      Vector(alice),
      certificates = certificate
    )
    val s = pair.slot
    val b = pool(kesStart = 50, seed = pair.b)
    val withheldEarly: (
        BigInt,
        xyz.kd5ujc.scarab.protocol.ChainSync.Point,
        xyz.kd5ujc.scarab.node.mempool.MempoolLedger
    ) => xyz.kd5ujc.scarab.node.mempool.MempoolLedger =
      (slot, prev, ledger) =>
        prev match
          case xyz.kd5ujc.scarab.protocol.ChainSync.Point.Block(at, _) if at.value == s - 1 =>
            ledger
          case _ if slot == s => ledger.withParams(_.copy(keyDeposit = BigInt(1) << 64))
          case _              => ledger
    withHandle {
      compared(
        Scenario(
          pair.a,
          halves(b),
          s - 1,
          s + 1,
          setup = side => side.h.mempool.add(registration, Source.Local).void,
          selection = Some(withheldEarly),
          at = Vector((Forger.Point.Signed(s), s, fromOther(b, s - 1)))
        )
      ).map { c =>
        same(c)
        assertEquals(c.scheduledMempool, c.perSlotMempool, "the mempool at the end")
        assert(
          c.scheduled.exists {
            case ForgeEvent.Forged(slot, _, _, _, _, txs, _, _, _, _, _, _) =>
              slot == s && txs.contains(id)
            case _ => false
          },
          "the re-forged block holds the registration"
        )
      }
    }
  }
