// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.crypto.kes.Sum6KesSigning
import xyz.kd5ujc.scarab.ledger.ClockProblem
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.node.Fatal

/** The interleavings of the scheduled forger's fibers (#1101, §7), each driven to the point where
  * it can fall by holding a fiber at one of the forger's points ([[Forger.Point]]) until the test
  * releases it, on the test clock: the KES key at a period boundary during assembly, two fibers on
  * one slot, the KES key's expiry in each order, an epoch's last slot across the boundary, forging
  * in slot order, and a clock that never waits.
  */
class ForgeInterleavingSuite extends xyz.kd5ujc.scarab.network.FailsOnUncaught with ScheduleFixture:

  /** A point a fiber waits at: `reached` once it gets there, until the test completes `release`. */
  private final case class Hold(reached: Deferred[IO, Unit], release: Deferred[IO, Unit]):
    def at: IO[Unit] = reached.complete(()).void *> release.get
    def free: IO[Unit] = release.complete(()).void

  private def holds(points: Forger.Point*): IO[Map[Forger.Point, Hold]] =
    points.toList
      .traverse(p => (Deferred[IO, Unit], Deferred[IO, Unit]).mapN((r, f) => p -> Hold(r, f)))
      .map(_.toMap)

  /** What the scheduled forger did in a run: its events, the time of each held line, and its
    * counters of each epoch.
    */
  private final case class Ran(
      events: Vector[ForgeEvent],
      stamped: Vector[(Instant, ForgeEvent)],
      stats: Map[BigInt, ForgeEpochStats]
  )

  /** A run under way: the harness, the forger's outcome once it ends, the pool, and what a reload
    * finds (the pool's own credentials at first).
    */
  private final case class Run(
      h: Harness,
      ended: IO[Either[Fatal, Unit]],
      pool: Pool,
      reading: Ref[IO, HotKeySource.Reading[IO]]
  ):
    /** A SIGHUP that finds `found`. */
    def reload(found: HotKeySource.Reading[IO]): IO[Unit] =
      reading.set(found) *> h.forger.reload.flatMap(
        _.fold(f => IO.raiseError(new AssertionError(f.message)), IO.pure)
      )

  /** [[runningIn]] with the body given the harness and the forger's outcome. */
  private def running(
      seed: Int,
      from: BigInt,
      held: Map[Forger.Point, Hold],
      kesStart: Int = 50,
      epoch: BigInt = 5,
      source: Option[HotKeySource[IO]] = None,
      shift: Option[Ref[IO, Long]] = None,
      observe: Harness => Forger.Point => IO[Unit] = _ => _ => IO.unit,
      bound: FiniteDuration = Bound
  )(body: (Harness, IO[Either[Fatal, Unit]]) => IO[Unit]): IO[Ran] =
    runningIn(seed, from, held, kesStart, epoch, source, shift, observe, bound = bound)(r =>
      body(r.h, r.ended)
    )

  /** The longest a run may take on the test clock: past it, the run fails rather than waits. */
  private val Bound: FiniteDuration = 5.minutes

  /** The pool of `seed` alone, forging from slot `from` on the test clock, with `held` points and
    * `observe` called at every point; `body` drives the run and ends it. The run is bounded on the
    * test clock (`bound`): a point or a settlement that never comes fails the test rather than
    * hangs it, and the forger is cancelled and every hold released whatever the body did.
    */
  private def runningIn(
      seed: Int,
      from: BigInt,
      held: Map[Forger.Point, Hold],
      kesStart: Int = 50,
      epoch: BigInt = 5,
      source: Option[HotKeySource[IO]] = None,
      shift: Option[Ref[IO, Long]] = None,
      observe: Harness => Forger.Point => IO[Unit] = _ => _ => IO.unit,
      enabled: Boolean = true,
      bound: FiniteDuration = Bound
  )(body: Run => IO[Unit]): IO[Ran] =
    TestControl.executeEmbed(
      withHandle {
        for
          self <- Deferred[IO, Harness]
          p = pool(kesStart, seed)
          reading <- Ref.of[IO, HotKeySource.Reading[IO]](found(p, seed))
          offset = ((from - 5000) * 1000).toLong
          h <- harness(
            p,
            ledgerIn(epoch, Vector(p -> BigInt(1000000))),
            live = true,
            offsetMillis = offset,
            unbounded = true,
            source = source.orElse(Some(keysFrom(reading))),
            clock = shift.map(s =>
              (s.get, IO.realTimeInstant).mapN((back, t) =>
                t.plusSeconds(5000).plusMillis(offset + back)
              )
            ),
            enabled = enabled,
            pause = Some(point =>
              self.get.flatMap(observe(_)(point)) *> held.get(point).fold(IO.unit)(_.at)
            )
          )
          _ <- self.complete(h)
          fiber <- h.forger.run.start
          _ <- body(Run(h, fiber.join.flatMap(_.embedNever), p, reading))
            .timeout(bound)
            .guarantee(fiber.cancel *> held.values.toList.traverse_(_.free))
          events <- h.seen
          stamped <- h.stamped.get
          stats <- h.forger.stats.byEpoch
        yield Ran(events, stamped, stats.toMap)
      }
    )

  private def forged(r: Ran, slot: BigInt): Option[ForgeEvent.Forged] =
    r.events.collectFirst { case f: ForgeEvent.Forged if f.slot == slot => f }

  private def index(r: Ran)(p: ForgeEvent => Boolean): Int = r.events.indexWhere(p)

  private def endedAt(slot: BigInt): ForgeEvent => Boolean =
    e => endedIn(Vector(e)).contains(slot)

  test(
    "a KES boundary during assembly: the block of a period's last slot is signed in its period"
  ) {
    val s = BigInt(5099)
    val seed = seedLeading(Vector(s))
    for
      written <- Ref.of[IO, Vector[Int]](Vector.empty)
      counting = new HotKeySource[IO]:
        def read = IO.pure(Left(xyz.kd5ujc.scarab.ledger.ForgeError.KesUpdateInterrupted))
        def persist(key: Sum6KesSigning.SigningKey) =
          written.update(_ :+ key.period).as(Persisted.Written(WriteProtocol.Exchanged))
      held <- holds(Forger.Point.Signing(s))
      waiting <- Ref.of[IO, Option[BigInt]](None)
      ran <- running(seed, s - 2, held, source = Some(counting)) { (h, _) =>
        for
          _ <- held(Forger.Point.Signing(s)).reached.get
          // the clock goes into the next period while the forge waits to sign
          _ <- h.clocked(s + 2)
          mark <- h.forger.settledThrough.get
          _ <- waiting.set(Some(mark))
          _ <- held(Forger.Point.Signing(s)).free
          _ <- h.settled(s + 2)
        yield ()
      }
      mark <- waiting.get
      writes <- written.get
    yield
      // the slot driver could not settle the slot before its result: it stayed before it
      assertEquals(mark, Some(s - 1))
      val block = forged(ran, s)
      assertEquals(block.map(_.kesPeriod), Some(BigInt(50)), "signed in its own period")
      assertEquals(block.map(_.relativeKesPeriod), Some(0))
      // the key moved to the next period only after the slot was settled, and was written once
      val evolved = index(ran) {
        case ForgeEvent.KesEvolved(period, _) => period == 51
        case _                                => false
      }
      assert(evolved > index(ran)(endedAt(s)), s"the key moved before $s was settled")
      assertEquals(writes.count(_ == 1), 1, s"written back once at period 51: $writes")
  }

  test(
    "two fibers, one slot: a fork that ends the lead before the onset, the forge's claim first"
  ) {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    val stranger = pool(kesStart = 50, seed = 77)
    val unstaked = mirrorState(ledgerIn(5, Vector(stranger -> BigInt(1000000))))
    for
      held <- holds(Forger.Point.Rechecked(s))
      ran <- running(seed, s - 2, held) { (h, _) =>
        for
          _ <- held(Forger.Point.Rechecked(s)).reached.get
          _ <- h.ref.set(VolatileChain.genesis(geometry.k.toInt, unstaked))
          _ <- held(Forger.Point.Rechecked(s)).free
          _ <- h.settled(s + 1)
        yield ()
      }
    yield oneNotLed(ran, s)
  }

  test("two fibers, one slot: the same fork, the slot driver reaching the slot first") {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    val stranger = pool(kesStart = 50, seed = 77)
    val unstaked = mirrorState(ledgerIn(5, Vector(stranger -> BigInt(1000000))))
    for
      held <- holds(Forger.Point.Waking(s))
      ran <- running(seed, s - 2, held) { (h, _) =>
        for
          // the forging fiber is about to claim the slot; the fork comes, and the slot driver
          // settles the slot before the forging fiber goes on, which then leaves it
          _ <- held(Forger.Point.Waking(s)).reached.get
          _ <- h.ref.set(VolatileChain.genesis(geometry.k.toInt, unstaked))
          _ <- h.settled(s)
          _ <- held(Forger.Point.Waking(s)).free
          _ <- h.settled(s + 1)
        yield ()
      }
    yield oneNotLed(ran, s)
  }

  private def oneNotLed(ran: Ran, s: BigInt)(using munit.Location): Unit =
    assertEquals(endedIn(ran.events).filter(_ == s), Vector(s), "one outcome for the slot")
    assert(ran.events.contains(ForgeEvent.Loop(ForgeTrace.NodeNotLeader(s))), ran.events.toString)
    assertEquals(forged(ran, s), None, "no block")
    val epoch = ran.stats.getOrElse(5, ForgeEpochStats())
    val ended = endedIn(ran.events).filter(geometry.epochOf(_) == 5)
    // counted once: every slot ended is checked once, and the slot is one of those not led
    assertEquals(epoch.slotsChecked, ended.size.toLong)
    assertEquals(
      epoch.notLeader,
      ran.events.count {
        case ForgeEvent.Loop(ForgeTrace.NodeNotLeader(_)) => true
        case _                                            => false
      }.toLong
    )

  test(
    "the slot driver's claim first: the forging fiber, waking for the slot, finds it and forges it"
  ) {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Waking(s), Forger.Point.Deciding(s))
      ran <- running(seed, s - 2, held) { (h, _) =>
        for
          _ <- held(Forger.Point.Waking(s)).reached.get
          _ <- held(Forger.Point.Deciding(s)).reached.get
          _ <- held(Forger.Point.Deciding(s)).free
          // the slot driver has handed the slot over by the time the forging fiber claims
          _ <- h.forger.claimed.discrete.find(_.owned.contains(s)).compile.drain
          _ <- held(Forger.Point.Waking(s)).free
          _ <- h.settled(s + 1)
        yield ()
      }
    yield
      assertEquals(endedIn(ran.events).filter(_ == s), Vector(s))
      assert(forged(ran, s).nonEmpty, "the slot handed over is forged")
  }

  // the certificate's window is periods 0 to 61; slot 6200 is the first of period 62
  private val expiry = BigInt(6200)
  private lazy val expiring = seedLeading(Vector(expiry, expiry + 1, expiry + 2))

  private def poisonedOnce(ran: Ran)(using munit.Location): Unit =
    val errors = ran.events.collect { case ForgeEvent.Loop(t: ForgeTrace.ForgeStateUpdateError) =>
      t
    }
    assert(errors.nonEmpty, ran.events.toString)
    errors.head.error match
      case KesEvolutionError.CouldNotEvolve(_, target) =>
        assertEquals(target, BigInt(62))
        assertEquals(errors.head.slot, expiry, "in the first slot whose checks reach the key")
      case other => fail(s"the first failure is not KESCouldNotEvolve: $other")
    assert(
      errors.tail.forall(_.error match
        case KesEvolutionError.KeyAlreadyPoisoned(_, _) => true
        case KesEvolutionError.CouldNotEvolve(_, _)     => false),
      errors.toString
    )
    assertEquals(errors.map(_.error.info).distinct.size, 1, "one recorded result")

  test("a KES expiry boundary, the slot driver's update first") {
    for
      held <- holds(Forger.Point.Waking(expiry))
      ran <- running(expiring, expiry - 2, held, kesStart = 0, epoch = 6) { (h, _) =>
        held(Forger.Point.Waking(expiry)).reached.get *> h.settled(expiry) *>
          held(Forger.Point.Waking(expiry)).free *> h.settled(expiry + 2)
      }
    yield poisonedOnce(ran)
  }

  test("a KES expiry boundary, a forge's update first") {
    for
      held <- holds()
      ran <- running(expiring, expiry - 2, held, kesStart = 0, epoch = 6) { (h, _) =>
        h.settled(expiry + 2)
      }
    yield
      poisonedOnce(ran)
      assert(
        ran.events.contains(ForgeEvent.Loop(ForgeTrace.StartLeadershipCheck(expiry))),
        "the forge reached the key first"
      )
  }

  test("a KES expiry boundary, the slot driver waiting on the reservation of that slot's forge") {
    for
      held <- holds(Forger.Point.Reserved(expiry))
      ran <- running(expiring, expiry - 2, held, kesStart = 0, epoch = 6) { (h, _) =>
        for
          _ <- held(Forger.Point.Reserved(expiry)).reached.get
          // the clock passes the slot's onset: the slot driver waits on the reservation
          _ <- h.clocked(expiry + 1)
          _ <- held(Forger.Point.Reserved(expiry)).free
          _ <- h.settled(expiry + 2)
        yield ()
      }
    yield poisonedOnce(ran)
  }

  test("an epoch's last slot forged across the boundary: forgeEpoch comes after it and counts it") {
    val s = BigInt(5999)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Signing(s))
      ran <- running(seed, s - 2, held) { (h, _) =>
        held(Forger.Point.Signing(s)).reached.get *> h.clocked(s + 2) *>
          held(Forger.Point.Signing(s)).free *> h.settled(s + 2)
      }
    yield
      val summary = index(ran) {
        case ForgeEvent.EpochSummary(epoch, _) => epoch == 5
        case _                                 => false
      }
      assert(summary > index(ran)(endedAt(s)), "the summary after the slot's outcome")
      val stats = ran.events.collectFirst { case ForgeEvent.EpochSummary(e, st) if e == 5 => st }
      assertEquals(
        stats.map(_.forged),
        Some(ran.events.count {
          case f: ForgeEvent.Forged => f.epoch == 5
          case _                    => false
        }.toLong)
      )
      assert(forged(ran, s).nonEmpty, "the last slot's block is counted")
  }

  /** When `t`'s forge starts, the mark it found: every slot before `t` must be settled. */
  private def markAt(
      t: BigInt,
      seen: Ref[IO, Option[BigInt]]
  ): Harness => Forger.Point => IO[Unit] =
    h => {
      case Forger.Point.Context(slot) if slot == t =>
        h.forger.settledThrough.get.flatMap(m => seen.set(Some(m)))
      case _ => IO.unit
    }

  test(
    "forging follows slot order: a slot the driver hands over late is forged before a later one"
  ) {
    // the slot driver is suspended while it decides s, the last slot of period 50, as t's wake
    // (the first slot of period 51) comes; s is then forged first, with the key of period 50
    val s = BigInt(5099)
    val t = BigInt(5100)
    val seed = seedLeading(Vector(s, t))
    for
      seen <- Ref.of[IO, Option[BigInt]](None)
      held <- holds(Forger.Point.Waking(s), Forger.Point.Deciding(s))
      ran <- running(seed, s - 2, held, observe = markAt(t, seen)) { (h, _) =>
        for
          _ <- held(Forger.Point.Waking(s)).reached.get
          _ <- held(Forger.Point.Deciding(s)).reached.get
          _ <- h.clocked(t)
          _ <- held(Forger.Point.Deciding(s)).free
          _ <- held(Forger.Point.Waking(s)).free
          _ <- h.settled(t)
        yield ()
      }
      mark <- seen.get
    yield inOrder(ran, s, t, mark)
  }

  test("two consecutive led slots across a KES boundary: each signed in its own period, in order") {
    val s = BigInt(5099)
    val t = BigInt(5100)
    val seed = seedLeading(Vector(s, t))
    for
      seen <- Ref.of[IO, Option[BigInt]](None)
      held <- holds()
      ran <- running(seed, s - 2, held, observe = markAt(t, seen))((h, _) => h.settled(t))
      mark <- seen.get
    yield inOrder(ran, s, t, mark)
  }

  private def inOrder(ran: Ran, s: BigInt, t: BigInt, mark: Option[BigInt])(using
      munit.Location
  ): Unit =
    assertEquals(forged(ran, s).map(_.kesPeriod), Some(BigInt(50)))
    assertEquals(forged(ran, t).map(_.kesPeriod), Some(BigInt(51)))
    assert(mark.exists(_ >= s), s"t's forge started with the mark at $mark, before s was settled")
    val evolved = index(ran) {
      case ForgeEvent.KesEvolved(period, _) => period == 51
      case _                                => false
    }
    assert(evolved > index(ran)(endedAt(s)), "the key reached period 51 only after s")

  test("a led slot passed over by its time is still forged when the slot driver hands it over") {
    // the forge of s holds the key past s + 1's onset; then the forging fiber's clock reads past
    // s + 1's time (a late timer) before the clock fiber has published s + 2: the forging fiber
    // passes s + 1 over, and the slot driver, taking s + 1 as the latest slot, hands it over
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s, s + 1))
    for
      shift <- Ref.of[IO, Long](0L)
      held <- holds(Forger.Point.Signing(s))
      ran <- running(seed, s - 2, held, shift = Some(shift)) { (h, _) =>
        for
          _ <- held(Forger.Point.Signing(s)).reached.get
          _ <- h.clocked(s + 1)
          _ <- shift.set(geometry.slotLengthMillis)
          _ <- held(Forger.Point.Signing(s)).free
          _ <- h.settled(s + 1)
        yield ()
      }
    yield
      assertEquals(endedIn(ran.events).filter(e => e == s || e == s + 1), Vector(s, s + 1))
      assert(forged(ran, s + 1).nonEmpty, "the slot handed over is forged")
  }

  test("the clock never waits: with the slot driver blocked it publishes every slot on time") {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      published <- fs2.concurrent.SignallingRef.of[IO, Vector[(BigInt, Instant)]](Vector.empty)
      held <- holds(Forger.Point.Signing(s))
      ran <- running(seed, s - 2, held) { (h, _) =>
        for
          watcher <- h.forger.clockSlot.discrete
            .collect { case Some(slot) => slot }
            .evalMap(slot => IO.realTimeInstant.flatMap(at => published.update(_ :+ (slot -> at))))
            .compile
            .drain
            .start
          _ <- held(Forger.Point.Signing(s)).reached.get
          _ <- published.discrete.find(_.exists(_._1 >= s + 3)).compile.drain
          _ <- watcher.cancel
          _ <- held(Forger.Point.Signing(s)).free
          _ <- h.settled(s + 3)
        yield ()
      }
      times <- published.get
    yield
      // one publication per slot, each a slot's length after the one before
      val slots = times.map(_._1)
      assertEquals(slots, (slots.head to s + 3).toVector)
      times.sliding(2).foreach {
        case Vector((_, a), (_, b)) =>
          assertEquals(java.time.Duration.between(a, b).toMillis, geometry.slotLengthMillis)
        case _ => ()
      }
      // the slots that passed while the driver waited get no line and no count
      val ended = endedIn(ran.events)
      assert(!ended.contains(s + 1) && !ended.contains(s + 2), ended.toString)
      assertEquals(ran.stats.getOrElse(5, ForgeEpochStats()).slotsChecked, ended.size.toLong)
  }

  test(
    "the clock never waits: a clock moved back past 20 s stops the node with the driver blocked"
  ) {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      shift <- Ref.of[IO, Long](0L)
      outcome <- Ref.of[IO, Option[Either[Fatal, Unit]]](None)
      held <- holds(Forger.Point.Signing(s))
      _ <- running(seed, s - 2, held, shift = Some(shift)) { (h, ended) =>
        for
          _ <- held(Forger.Point.Signing(s)).reached.get
          _ <- h.clocked(s + 1)
          _ <- shift.set(-30000L)
          result <- ended
          _ <- outcome.set(Some(result))
        yield ()
      }
      result <- outcome.get
    yield result match
      case Some(Left(Fatal.Internal(ClockProblem.SystemClockMovedBack(last, now)))) =>
        assert(now < last, s"$now is not before $last")
      case other => fail(s"expected the node to stop on the clock: $other")
  }

  // ---- the review's interleavings (#1101)

  test(
    "a forging claim made while the slot driver read forging disabled is awaited and published"
  ) {
    // forging is off; the slot driver reads it so for s and is suspended; a reload turns it on and
    // the forging fiber claims s; the slot driver, resumed, must wait for that forge, not pass s
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Disabled(s))
      ran <- runningIn(seed, s - 2, held, enabled = false) { r =>
        for
          _ <- held(Forger.Point.Disabled(s)).reached.get
          _ <- r.reload(found(r.pool, seed))
          _ <- r.h.forger.claimed.discrete.find(_.owned.contains(s)).compile.drain
          _ <- held(Forger.Point.Disabled(s)).free
          _ <- r.h.settled(s + 1)
        yield ()
      }
    yield
      assertEquals(endedIn(ran.events).filter(_ == s), Vector(s), "the forge's outcome, once")
      assert(forged(ran, s).nonEmpty, "its block, published")
  }

  test("the same across a KES boundary: the key does not move on before the claimed slot signs") {
    // s is the last slot of period 50; its forge waits before it reserves the key until the clock
    // is in period 51, and must still sign with the key of period 50
    val s = BigInt(5099)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Disabled(s), Forger.Point.Context(s))
      ran <- runningIn(seed, s - 2, held, enabled = false) { r =>
        for
          _ <- held(Forger.Point.Disabled(s)).reached.get
          _ <- r.reload(found(r.pool, seed))
          _ <- held(Forger.Point.Context(s)).reached.get
          _ <- held(Forger.Point.Disabled(s)).free
          _ <- r.h.clocked(s + 1)
          _ <- held(Forger.Point.Context(s)).free
          _ <- r.h.settled(s + 1)
        yield ()
      }
    yield
      assertEquals(forged(ran, s).map(_.kesPeriod), Some(BigInt(50)), "signed in its period")
      assert(!ran.events.exists(_.isInstanceOf[ForgeEvent.Failed]), ran.events.toString)
  }

  test("a clock moved back during the early wait: the block is handed over at the onset by it") {
    // the block is built ahead of its slot; the wall clock then moves back ten seconds, within the
    // twenty the clock allows: the handover waits until the wall clock reaches the slot's onset
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      shift <- Ref.of[IO, Long](0L)
      held <- holds(Forger.Point.Waiting(s))
      ran <- running(seed, s - 2, held, shift = Some(shift)) { (h, _) =>
        // the forge has read the clock and set its timer for the onset, 50 ms on
        held(Forger.Point.Waiting(s)).reached.get *> shift.set(-10000L) *>
          held(Forger.Point.Waiting(s)).free *> h.settled(s)
      }
    yield
      val adopted = ran.stamped.collect {
        case (at, ForgeEvent.Loop(ForgeTrace.AdoptedBlock(slot, _, _, _))) if slot == s => at
      }
      assertEquals(adopted.size, 1, ran.stamped.toString)
      assert(!adopted.head.isBefore(geometry.slotStart(s)), s"handed over at ${adopted.head}")
  }

  private def credentialsReplaced(ran: Ran, s: BigInt)(using munit.Location): Unit =
    assertEquals(forged(ran, s), None, "no block of replaced credentials is handed over")
    assert(
      ran.events.exists {
        case ForgeEvent.Discarded(slot, DiscardReason.Credentials, Some(_)) => slot == s
        case _                                                              => false
      },
      ran.events.toString
    )

  test("credentials replaced after an early block is signed and checked, before its handover") {
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Handing(s))
      ran <- runningIn(seed, s - 2, held) { r =>
        for
          _ <- held(Forger.Point.Handing(s)).reached.get
          _ <- r.reload(HotKeySource.Reading.Absent(java.nio.file.Path.of("kes.skey")))
          _ <- held(Forger.Point.Handing(s)).free
          _ <- r.h.settled(s)
        yield ()
      }
    yield credentialsReplaced(ran, s)
  }

  test("credentials replaced after a block forged at once is signed, before its handover") {
    // the forging fiber wakes for s only once the clock is in s: the slot is forged at once
    val s = BigInt(5003)
    val seed = seedLeading(Vector(s))
    for
      held <- holds(Forger.Point.Waking(s), Forger.Point.Handing(s))
      ran <- runningIn(seed, s - 2, held) { r =>
        for
          _ <- held(Forger.Point.Waking(s)).reached.get
          _ <- r.h.clocked(s)
          _ <- held(Forger.Point.Waking(s)).free
          _ <- held(Forger.Point.Handing(s)).reached.get
          _ <- r.reload(HotKeySource.Reading.Found(certifiedFor(r.pool, seed + 100)))
          _ <- held(Forger.Point.Handing(s)).free
          _ <- r.h.settled(s)
        yield ()
      }
    yield credentialsReplaced(ran, s)
  }

  test("a run whose settlement never comes fails within its bound on the test clock") {
    val seed = seedLeading(Vector(BigInt(5003)))
    for
      held <- holds()
      result <- running(seed, 5001, held, bound = 30.seconds)((h, _) =>
        h.settled(BigInt(10000000))
      ).attempt
    yield assert(
      result.left.exists(_.isInstanceOf[java.util.concurrent.TimeoutException]),
      result.toString
    )
  }
