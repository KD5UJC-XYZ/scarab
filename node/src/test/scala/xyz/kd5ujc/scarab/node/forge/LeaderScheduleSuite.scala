// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.syntax.all.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAllNoShrink
import scala.annotation.tailrec
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Nonce, TestGeometry}

import LeaderSchedule.{Answer, Decided, EpochSchedule, Pacing, Schedules}

/** The leader schedule's pure parts (#1101): its decisions are those of [[Leadership.check]], slot
  * for slot; its state answers only under the inputs it was computed from; and its pacing finishes
  * each epoch before its deadline and, once caught up, stays ahead of the clock.
  */
class LeaderScheduleSuite extends ScalaCheckSuite:
  override def scalaCheckTestParameters =
    super.scalaCheckTestParameters.withMinSuccessfulTests(25)

  private def key(seed: Seq[Int]): Draft03Prover.SigningKey =
    Draft03Prover.SigningKey
      .fromSeed(Bytes.fromArray(seed.map(_.toByte).toArray))
      .fold(e => fail(e.toString), identity)

  private def fraction(n: BigInt, d: BigInt): Leader.Fraction =
    Leader.Fraction.checked(n, d).fold(e => fail(e.message), identity)

  private def geometry(slotMillis: Long, epochLength: Long, start: Instant = Instant.EPOCH) =
    EpochGeometry(start, slotMillis, epochLength, TestGeometry.k(5), TestGeometry.f("0.5"))

  private val hashNonce: Nonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))
  private val half = fraction(1, 2)

  /** The answer [[Leadership.check]] of `slot` gives: led with its certificate, not led, or a
    * failure, which the schedule never covers.
    */
  private def checked(slot: BigInt, inputs: EpochInputs, key: Draft03Prover.SigningKey): Answer =
    Leadership.check(slot, inputs, Leadership.Evaluator.of(key)) match
      case Right(cert)                   => Answer.Led(cert)
      case Left(ForgeError.NotLeader(_)) => Answer.NotLed
      case Left(_)                       => Answer.Uncovered

  /** The schedule of `inputs` computed chunk by chunk with the clock in slot `clock`, as the walk
    * computes it, until it covers the epoch or a slot's check fails.
    */
  private def computed(
      inputs: EpochInputs,
      clock: BigInt,
      key: Draft03Prover.SigningKey
  ): EpochSchedule =
    @tailrec def go(schedule: EpochSchedule): EpochSchedule =
      schedule.next(clock) match
        case None => schedule
        case Some((from, until)) =>
          val chunk = LeaderSchedule.decided(inputs, from, until, Leadership.Evaluator.of(key))
          schedule.added(chunk) match
            case None                                  => fail(s"chunk $from-$until refused")
            case Some(added) if chunk.failure.nonEmpty => added
            case Some(added)                           => go(added)
    go(EpochSchedule.empty(inputs))

  /** The slots of the epoch where the schedule's answer is not `expected`'s. */
  private def mismatches(
      schedule: EpochSchedule,
      inputs: EpochInputs,
      expected: BigInt => Answer
  ): Vector[(BigInt, Answer, Answer)] =
    (inputs.first until inputs.end).toVector.flatMap { slot =>
      val got = schedule.answer(slot, inputs)
      Option.when(got != expected(slot))((slot, got, expected(slot)))
    }

  private final case class Case(
      seed: List[Int],
      nonce: Nonce,
      coefficient: (Int, Int),
      stake: Long,
      total: Long,
      epochLength: Int,
      epoch: Int,
      into: Int
  )

  private val cases: Gen[Case] =
    for
      seed <- Gen.listOfN(32, Gen.choose(0, 255))
      nonce <- Gen.oneOf(
        Gen.const(Nonce.Neutral),
        Gen
          .listOfN(32, Gen.choose(0, 255))
          .map(b => Nonce.Hash(Bytes.fromArray(b.map(_.toByte).toArray)))
      )
      d <- Gen.choose(1, 20)
      n <- Gen.oneOf(Gen.const(d), Gen.choose(1, d))
      total <- Gen.choose(1L, 1000000L)
      stake <- Gen.oneOf(Gen.const(0L), Gen.const(total), Gen.choose(0L, total))
      length <- Gen.choose(1, 230)
      epoch <- Gen.choose(0, 3)
      into <- Gen.oneOf(Gen.const(0), Gen.choose(0, length - 1))
    yield Case(seed, nonce, (n, d), stake, total, length, epoch, into)

  // the properties do not shrink: a shrunk case leaves its generator's range (a seed of fewer than
  // 32 bytes, slots of no length) and reports a case that cannot occur
  property("every slot's decision and every led slot's certificate are Leadership.check's") {
    forAllNoShrink(cases) { c =>
      val k = key(c.seed)
      val inputs = EpochInputs.of(
        geometry(1000, c.epochLength.toLong),
        c.epoch,
        c.nonce,
        c.stake,
        c.total,
        fraction(c.coefficient._1, c.coefficient._2),
        k.publicKey
      )
      // started with the clock `into` the epoch: the slots before it have passed
      val clock = inputs.first + c.into
      val schedule = computed(inputs, clock, k)
      val wrong =
        mismatches(
          schedule,
          inputs,
          s => if s < clock then Answer.Uncovered else checked(s, inputs, k)
        )
      Prop(wrong.isEmpty) :| s"slots whose answer is not the check's: ${wrong.take(5)}" &&
      Prop(
        schedule.complete
      ) :| s"the schedule covers until ${schedule.until}, not ${inputs.end}" &&
      Prop(schedule.from == clock) :| s"the schedule starts at ${schedule.from}, not $clock"
    }
  }

  test(
    "an epoch with led and not led slots: the schedule has each one's decision and certificate"
  ) {
    val k = key(Seq.fill(32)(9))
    val inputs = EpochInputs.of(geometry(1000, 300), 2, hashNonce, 1, 1, half, k.publicKey)
    val schedule = computed(inputs, inputs.first, k)
    assertEquals(mismatches(schedule, inputs, checked(_, inputs, k)), Vector.empty)
    assert(schedule.led.nonEmpty, "no slot led: the comparison saw no certificate")
    assert(schedule.led.size < 300, "every slot led: the comparison saw no slot not led")
  }

  test("at f = 1 every slot is led, and without stake below f = 1 none is") {
    val k = key(Seq.fill(32)(3))
    val all = EpochInputs.of(geometry(1000, 150), 1, hashNonce, 0, 10, fraction(1, 1), k.publicKey)
    val none = EpochInputs.of(geometry(1000, 150), 1, hashNonce, 0, 10, half, k.publicKey)
    assertEquals(computed(all, 0, k).led.keySet, (all.first until all.end).toSet)
    assertEquals(computed(none, 0, k).led.size, 0)
    assertEquals(computed(none, 0, k).complete, true)
  }

  test("a check that fails otherwise than by not leading stops the schedule at that slot") {
    // a stake above the total is no fraction: every slot's check fails
    val k = key(Seq.fill(32)(3))
    val invalid = EpochInputs.of(geometry(1000, 150), 1, hashNonce, 11, 10, half, k.publicKey)
    val schedule = computed(invalid, 0, k)
    assertEquals(schedule.until, invalid.first)
    assert(Leadership.check(invalid.first, invalid, Leadership.Evaluator.of(k)) match {
      case Left(
            ForgeError.LeaderThreshold(
              xyz.kd5ujc.scarab.crypto.vrf.PraosLeaderThreshold.Error.InvalidFraction(_, _)
            )
          ) =>
        true
      case _ => false
    })
    (invalid.first until invalid.end).foreach(slot =>
      assertEquals(schedule.answer(slot, invalid), Answer.Uncovered, s"slot $slot")
    )
  }

  test("the slots decided before a failure in a chunk keep their decisions; the failure is kept") {
    val k = key(Seq.fill(32)(9))
    val inputs = EpochInputs.of(geometry(1000, 300), 1, hashNonce, 1, 1, half, k.publicKey)
    val boom = Draft03Prover.Failure.InternalFailure(new IllegalStateException("evaluation"))
    val calls = new AtomicInteger(0)
    // the fifth output fails
    val failing = new Leadership.Evaluator:
      def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] =
        if calls.incrementAndGet() == 5 then Left(boom) else k.output(alpha)
      def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
        k.prove(alpha)
    val chunk = LeaderSchedule.decided(inputs, inputs.first, inputs.first + 100, failing)
    assertEquals(chunk.until, inputs.first + 4)
    assertEquals(chunk.failure, Some(ForgeError.VrfProof(boom)))
    val schedule = EpochSchedule.empty(inputs).added(chunk).getOrElse(fail("chunk refused"))
    (inputs.first until inputs.first + 4).foreach(slot =>
      assertEquals(schedule.answer(slot, inputs), checked(slot, inputs, k), s"slot $slot")
    )
    assertEquals(schedule.answer(inputs.first + 4, inputs), Answer.Uncovered)
  }

  test("a chunk past where a schedule ends drops the slots before it: they answer uncovered") {
    val k = key(Seq.fill(32)(9))
    val inputs = EpochInputs.of(geometry(1000, 500), 1, hashNonce, 1, 1, half, k.publicKey)
    def chunk(from: BigInt) =
      LeaderSchedule.decided(inputs, from, from + 100, Leadership.Evaluator.of(k))
    val once = EpochSchedule.empty(inputs).added(chunk(inputs.first)).getOrElse(fail("refused"))
    assert(once.led.nonEmpty, "the first chunk leads no slot: the test would see no dropped slot")
    // the clock passed the schedule's end: the next chunk starts at the clock's slot
    val jumped = once.added(chunk(inputs.first + 250)).getOrElse(fail("refused"))
    assertEquals((jumped.from, jumped.until), (inputs.first + 250, inputs.first + 350))
    (inputs.first until inputs.first + 250).foreach(slot =>
      assertEquals(jumped.answer(slot, inputs), Answer.Uncovered, s"slot $slot")
    )
    (inputs.first + 250 until inputs.first + 350).foreach(slot =>
      assertEquals(jumped.answer(slot, inputs), checked(slot, inputs, k), s"slot $slot")
    )
  }

  test(
    "inputs are compared by value: the VRF key by its public key, the coefficient as a fraction"
  ) {
    val g = geometry(1000, 100)
    def inputs(seed: Int, nonce: Nonce = hashNonce, stake: BigInt = 1, f: Leader.Fraction = half) =
      EpochInputs.of(g, 3, nonce, stake, 2, f, key(Seq.fill(32)(seed)).publicKey)
    // two keys made apart from one seed are two objects with one public key
    assertEquals(inputs(4), inputs(4))
    assertEquals(inputs(4).hashCode, inputs(4).hashCode)
    assert(inputs(4) === inputs(4))
    assertEquals(inputs(4, f = fraction(2, 4)), inputs(4))
    assertNotEquals(inputs(4), inputs(5))
    assertNotEquals(inputs(4), inputs(4, nonce = Nonce.Neutral))
    assertNotEquals(inputs(4), inputs(4, stake = 2))
    assertNotEquals(inputs(4), inputs(4, f = fraction(1, 3)))
    assertEquals((inputs(4).first, inputs(4).end), (BigInt(300), BigInt(400)))
  }

  test("a schedule answers only under the inputs it was computed from") {
    val g = geometry(1000, 150)
    val k = key(Seq.fill(32)(9))
    val a = EpochInputs.of(g, 1, hashNonce, 1, 1, half, k.publicKey)
    val b = EpochInputs.of(g, 1, Nonce.Neutral, 1, 1, half, k.publicKey)
    val schedule = computed(a, 0, k)
    assertEquals(mismatches(schedule, a, checked(_, a, k)), Vector.empty)
    (a.first until a.end).foreach(slot =>
      assertEquals(schedule.answer(slot, b), Answer.Uncovered, s"slot $slot under other inputs")
    )
  }

  test(
    "the state keeps a schedule whose inputs did not change, restarts one whose did, and refuses stale chunks"
  ) {
    val g = geometry(1000, 300)
    val k = key(Seq.fill(32)(9))
    val a = EpochInputs.of(g, 1, hashNonce, 1, 1, half, k.publicKey)
    val b = EpochInputs.of(g, 1, Nonce.Neutral, 1, 1, half, k.publicKey)
    val firstOf = (i: EpochInputs) =>
      LeaderSchedule.decided(i, i.first, i.first + 100, Leadership.Evaluator.of(k))
    val withA = Schedules.empty.started(Vector(a)).added(firstOf(a)).getOrElse(fail("refused"))._1
    // the same inputs again: the schedule goes on from where it got
    assertEquals(withA.started(Vector(a)).of(a).map(_.until), Some(a.first + 100))
    // other inputs: started again from nothing, and a chunk decided under the old ones never lands
    val withB = withA.started(Vector(b))
    assertEquals(withB.of(b).map(s => (s.from, s.until)), Some((b.first, b.first)))
    assertEquals(withB.of(a), None)
    assertEquals(withB.added(firstOf(a)), None)
    // a chunk that overlaps what is covered is refused too
    val bOnce = withB.added(firstOf(b)).getOrElse(fail("refused"))._1
    assertEquals(bOnce.added(firstOf(b)), None)
    // an epoch whose slots have all passed is dropped
    assertEquals(bOnce.pruned(b.end).of(b), None)
    assertEquals(bOnce.pruned(b.end - 1).of(b).nonEmpty, true)
  }

  test("pacing ahead of an epoch: a tenth of the time to its first slot is kept, the rest shared") {
    val g = geometry(1000, 1000)
    val inputs = EpochInputs.of(g, 1, hashNonce, 1, 1, half, key(Seq.fill(32)(9)).publicKey)
    val signalled = Instant.EPOCH.plusSeconds(300)
    val target = Pacing.target(g, inputs, signalled)
    assertEquals(target, Pacing.Target.Ahead(Instant.EPOCH.plusSeconds(1000), 70.seconds))
    val oneChunk = EpochSchedule
      .empty(inputs)
      .added(Decided(inputs, 1000, 1100, Vector.empty, None))
      .getOrElse(fail("refused"))
    // nine chunks left share 1000 - 300 - 70 seconds
    assertEquals(Pacing.pause(g, target, signalled, oneChunk, 0.nanos), 70.seconds)
    // a chunk that took 10 s: nine chunks of 10 s are kept back from the 630 s
    assertEquals(
      Pacing.pause(g, target, signalled.plusSeconds(10), oneChunk, 10.seconds),
      (530.seconds.toNanos / 9).nanos
    )
    // past the deadline less the margin, no pause
    assertEquals(
      Pacing.pause(g, target, Instant.EPOCH.plusSeconds(935), oneChunk, 0.nanos),
      0.nanos
    )
  }

  test("pacing a begun epoch: no pause until a chunk ahead, then done a chunk before its end") {
    val g = geometry(1000, 1000)
    val inputs = EpochInputs.of(g, 0, hashNonce, 1, 1, half, key(Seq.fill(32)(9)).publicKey)
    val signalled = Instant.EPOCH.plusSeconds(300)
    val target = Pacing.target(g, inputs, signalled)
    assertEquals(target, Pacing.Target.Begun(Instant.EPOCH.plusSeconds(900), 60.seconds))
    def covering(until: BigInt) =
      EpochSchedule
        .empty(inputs)
        .added(Decided(inputs, 300, until, Vector.empty, None))
        .getOrElse(fail("refused"))
    // 99 slots ahead of the clock: no pause
    assertEquals(
      Pacing.pause(g, target, signalled.plusSeconds(1), covering(400), 0.nanos),
      0.nanos
    )
    // a chunk ahead: six chunks left share 900 - 300 - 60 seconds
    assertEquals(Pacing.pause(g, target, signalled, covering(400), 0.nanos), 90.seconds)
  }

  test(
    "a pause leaves the next chunk time to be decided, and as long again, before its first slot"
  ) {
    // slots of 0.1 s, an epoch of 300 signalled at its onset, each chunk taking 2.5 s: after two
    // chunks, at 5 s, the epoch's last chunk must be decided before slot 200 begins at 20 s
    val g = geometry(100, 300)
    val inputs = EpochInputs.of(g, 0, hashNonce, 1, 1, half, key(Seq.fill(32)(9)).publicKey)
    val target = Pacing.target(g, inputs, Instant.EPOCH)
    assertEquals(target, Pacing.Target.Begun(Instant.EPOCH.plusSeconds(20), 2.seconds))
    val two = EpochSchedule
      .empty(inputs)
      .added(Decided(inputs, 0, 200, Vector.empty, None))
      .getOrElse(fail("refused"))
    // the share of the time left would be 20 - 5 - 2 - 2.5 = 10.5 s; the next chunk and as long
    // again before 20 s leave 10 s
    assertEquals(
      Pacing.pause(g, target, Instant.EPOCH.plusSeconds(5), two, 2500.millis),
      10.seconds
    )
  }

  /** The time each chunk of a walk takes to decide. */
  private enum Took:
    /** Every chunk takes `each`. */
    case Steady(each: FiniteDuration)

    /** The first chunk takes `first`; each next one the one before times the next of `factors` (in
      * turn), at most `most`.
      */
    case Varying(first: FiniteDuration, factors: Vector[Double], most: FiniteDuration)

    def apply(chunk: Int): FiniteDuration = this match
      case Steady(each) => each
      case Varying(first, factors, most) =>
        (0 until chunk).foldLeft(first)((d, i) =>
          (d.toNanos * factors(i % factors.size)).toLong.nanos.min(most)
        )

  /** A chunk of the walk: when its decisions were done, and the slots it decided. */
  private final case class Walked(done: Instant, from: BigInt, until: BigInt)

  /** The walk of `inputs` with the pure steps, each chunk taking the time `took` gives it. */
  private def paced(
      g: EpochGeometry,
      inputs: EpochInputs,
      signalled: Instant,
      took: Took
  ): Vector[Walked] =
    val target = Pacing.target(g, inputs, signalled)
    @tailrec def go(at: Instant, schedule: EpochSchedule, log: Vector[Walked]): Vector[Walked] =
      schedule.next(g.slotAt(at)) match
        case None => log
        case Some((from, until)) =>
          val added = schedule
            .added(Decided(inputs, from, until, Vector.empty, None))
            .getOrElse(fail(s"chunk $from-$until refused"))
          val spent = took(log.size)
          val done = at.plusNanos(spent.toNanos)
          val pause = Pacing.pause(g, target, done, added, spent)
          go(done.plusNanos(pause.toNanos), added, log :+ Walked(done, from, until))
    go(signalled, EpochSchedule.empty(inputs), Vector.empty)

  property(
    "paced, an epoch is done before its deadline when its decisions fit, and once caught up stays ahead of the clock"
  ) {
    val walks =
      for
        slotMillis <- Gen.oneOf(100L, 200L, 1000L, 2000L)
        length <- Gen.choose(1L, 5000L)
        // the signal: before the epoch, or into it
        offset <- Gen.choose(-length * slotMillis, length * slotMillis - 1)
        // the time a chunk's slots take to pass
        chunkTime = (slotMillis * 100).millis
        took <- Gen.oneOf(
          Gen.const(Took.Steady(0.nanos)),
          Gen.choose(0.0, 0.9).map(f => Took.Steady((chunkTime.toNanos * f).toLong.nanos)),
          for
            first <- Gen.choose(0.001, 0.45)
            factors <- Gen.listOfN(17, Gen.choose(0.5, 1.95))
          yield Took.Varying(
            (chunkTime.toNanos * first).toLong.nanos,
            factors.toVector,
            (chunkTime.toNanos * 0.9).toLong.nanos
          )
        )
      yield (slotMillis, length, offset, took)
    // twenty walks a case: each is cheap, and the cases are few
    forAllNoShrink(Gen.listOfN(20, walks))(
      _.map((s, l, o, t) => walked(s, l, o, t)).reduce(_ && _)
    )
  }

  private def walked(slotMillis: Long, length: Long, offset: Long, took: Took): Prop =
    val g = geometry(slotMillis, length)
    val inputs = EpochInputs.of(g, 2, hashNonce, 1, 1, half, key(Seq.fill(32)(9)).publicKey)
    val signalled = g.slotStart(inputs.first).plusMillis(offset)
    val log = paced(g, inputs, signalled, took)
    val last = log.last
    // with every chunk taking as long, done by the deadline whenever the decisions fit before it
    val deadline = Pacing.target(g, inputs, signalled) match
      case Pacing.Target.Ahead(at, margin) => (at, margin)
      case Pacing.Target.Begun(at, margin) => (at, margin)
    val fits = took match
      case Took.Steady(each) =>
        BigInt(each.toNanos) * log.size <=
          BigInt(java.time.Duration.between(signalled, deadline._1).toNanos) - deadline._2.toNanos
      case Took.Varying(_, _, _) => false
    val done = Prop(last.until == inputs.end) :| s"covered until ${last.until}" &&
      Prop(
        !fits || !last.done.isAfter(deadline._1)
      ) :| s"done at ${last.done}, after ${deadline._1}"
    // from the first chunk a chunk ahead of the clock, each chunk is decided before its first slot
    // begins, and none passes slots over
    val caught = log.indexWhere(w =>
      w.until >= g.slotAt(w.done) + LeaderSchedule.ChunkSlots || w.until == inputs.end
    )
    val behind = log
      .drop(caught.max(0) + 1)
      .zip(log.drop(caught.max(0)))
      .collect {
        case (w, before) if caught >= 0 && (g.slotAt(w.done) >= w.from || w.from != before.until) =>
          w
      }
    val steadyCaught = took match
      case Took.Steady(each)     => each == 0.nanos
      case Took.Varying(_, _, _) => false
    (done && Prop(caught >= 0 || !steadyCaught) :| "never caught up" &&
      Prop(behind.isEmpty) :| s"the clock reached slots not decided: ${behind.take(3)}") :|
      s"slots of $slotMillis ms, epochs of $length, signalled $offset ms from the epoch's start, $took"
