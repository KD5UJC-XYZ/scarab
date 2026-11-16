// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref}
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import java.time.Instant
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

  /** What the scheduled forger did in a run: its events, and its counters of each epoch. */
  private final case class Ran(events: Vector[ForgeEvent], stats: Map[BigInt, ForgeEpochStats])

  /** The pool of `seed` alone, forging from slot `from` on the test clock, with `held` points and
    * `observe` called at every point; `body` drives the run and ends it.
    */
  private def running(
      seed: Int,
      from: BigInt,
      held: Map[Forger.Point, Hold],
      kesStart: Int = 50,
      epoch: BigInt = 5,
      source: Option[HotKeySource[IO]] = None,
      shift: Option[Ref[IO, Long]] = None,
      observe: Harness => Forger.Point => IO[Unit] = _ => _ => IO.unit
  )(body: (Harness, IO[Either[Fatal, Unit]]) => IO[Unit]): IO[Ran] =
    TestControl.executeEmbed(
      withHandle {
        for
          self <- Deferred[IO, Harness]
          p = pool(kesStart, seed)
          offset = ((from - 5000) * 1000).toLong
          h <- harness(
            p,
            ledgerIn(epoch, Vector(p -> BigInt(1000000))),
            live = true,
            offsetMillis = offset,
            unbounded = true,
            source = source,
            clock = shift.map(s =>
              (s.get, IO.realTimeInstant).mapN((back, t) =>
                t.plusSeconds(5000).plusMillis(offset + back)
              )
            ),
            pause = Some(point =>
              self.get.flatMap(observe(_)(point)) *> held.get(point).fold(IO.unit)(_.at)
            )
          )
          _ <- self.complete(h)
          fiber <- h.forger.run.start
          _ <- body(h, fiber.join.flatMap(_.embedNever))
          _ <- fiber.cancel
          events <- h.seen
          stats <- h.forger.stats.byEpoch
        yield Ran(events, stats.toMap)
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
