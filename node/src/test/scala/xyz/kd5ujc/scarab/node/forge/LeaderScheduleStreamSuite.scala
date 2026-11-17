// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Deferred, IO, Ref}
import cats.effect.kernel.Outcome
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Nonce, TestGeometry}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import LeaderSchedule.{Answer, EpochSchedule, InForce, Schedules}

/** The leader schedule's stream (#1101) on `TestControl` 's clock, which starts at the epoch of
  * `Instant`: the computation is paced to finish before its deadline and, once caught up, stays
  * ahead of the clock, also when deciding a chunk takes time; a new value of the signal cancels the
  * work under way, and a schedule of replaced inputs never answers.
  *
  * A chunk's decisions take no time on `TestControl` 's clock. Where a test gives them time, each
  * evaluation of the VRF key moves the clock the schedule reads (`now`) on by `perEvaluation`, as
  * if the evaluation had taken that long.
  */
class LeaderScheduleStreamSuite extends FailsOnUncaught:
  private def key(seed: Int): Draft03Prover.SigningKey =
    Draft03Prover.SigningKey
      .fromSeed(Bytes.fromArray(Array.fill(32)(seed.toByte)))
      .fold(e => fail(e.toString), identity)

  private val half = Leader.Fraction.checked(1, 2).fold(e => fail(e.message), identity)
  private val hashNonce: Nonce = Nonce.Hash(Blake2b.hash256.hash(Bytes(Vector(7))))

  /** Slots of `slotMillis`, epochs of `length` slots, and the clock `at` from the system start when
    * a test starts.
    */
  private def geometry(slotMillis: Long, length: Long, at: FiniteDuration): EpochGeometry =
    EpochGeometry(
      Instant.EPOCH.minusMillis(at.toMillis),
      slotMillis,
      length,
      TestGeometry.k(5),
      TestGeometry.f("0.5")
    )

  private def inputs(g: EpochGeometry, epoch: BigInt, nonce: Nonce = hashNonce): EpochInputs =
    EpochInputs.of(g, epoch, nonce, 1, 1, half, key(9))

  private def at(seconds: Double): Instant = Instant.EPOCH.plusNanos((seconds * 1e9).round)

  private def checked(slot: BigInt, inputs: EpochInputs): Answer =
    Leadership.check(slot, inputs) match
      case Right(cert)                   => Answer.Led(cert)
      case Left(ForgeError.NotLeader(_)) => Answer.NotLed
      case Left(_)                       => Answer.Uncovered

  /** The slots of the epoch of `inputs`, from `from`, whose answer is not the check's. */
  private def mismatches(state: Schedules, inputs: EpochInputs, from: BigInt = -1) =
    (inputs.first until inputs.end).toVector.flatMap { slot =>
      val expected = if slot < from then Answer.Uncovered else checked(slot, inputs)
      val got = state.answer(slot, inputs)
      Option.when(got != expected)((slot, got, expected))
    }

  /** The schedules the stream reports from `initial`, each with the time its chunk was done, until
    * `done` holds of the reports so far; and the state then. `onEach` sees each report first. Each
    * evaluation of the VRF key takes `perEvaluation`.
    */
  private def recorded(
      g: EpochGeometry,
      initial: InForce,
      done: Vector[(Instant, EpochSchedule)] => Boolean,
      onEach: (SignallingRef[IO, InForce], EpochSchedule) => IO[Unit] = (_, _) => IO.unit,
      perEvaluation: FiniteDuration = 0.nanos
  ): IO[(Vector[(Instant, EpochSchedule)], Schedules)] =
    TestControl.executeEmbed(
      for
        spent <- IO(new AtomicLong(0))
        now = IO.realTimeInstant.flatMap(t => IO(t.plusNanos(spent.get)))
        evaluator = (i: EpochInputs) =>
          new Leadership.Evaluator:
            def output(alpha: Bytes): Either[Draft03Prover.Failure, Bytes] =
              spent.addAndGet(perEvaluation.toNanos)
              i.key.output(alpha)
            def prove(alpha: Bytes): Either[Draft03Prover.Failure, Draft03Prover.Proved] =
              i.key.prove(alpha)
        schedule <- LeaderSchedule.evaluatedWith[IO](g, Some(now), None, evaluator)
        signal <- SignallingRef.of[IO, InForce](initial)
        log <- schedule
          .computed(signal)
          .evalTap(a => onEach(signal, a.schedule))
          .map(a => a.at -> a.schedule)
          .scan(Vector.empty[(Instant, EpochSchedule)])(_ :+ _)
          .takeThrough(log => !done(log))
          .compile
          .lastOrError
        state <- schedule.schedules.get
      yield (log, state)
    )

  private def complete(i: EpochInputs)(log: Vector[(Instant, EpochSchedule)]): Boolean =
    log.exists((_, s) => s.inputs == i && s.complete)

  /** The reports after which the clock reached a slot the schedule had not decided before the next
    * report: its time and slot, and how far the schedule covered.
    */
  private def overtaken(g: EpochGeometry, log: Vector[(Instant, EpochSchedule)]) =
    log
      .sliding(2)
      .collect {
        case Vector((_, before), (next, _)) if g.slotAt(next) >= before.until =>
          (next, g.slotAt(next), before.until)
      }
      .toVector

  /** The reports from the first a chunk ahead of the clock (or covering the epoch). */
  private def caughtUp(g: EpochGeometry, log: Vector[(Instant, EpochSchedule)]) =
    log.dropWhile((t, s) => !s.complete && s.until < g.slotAt(t) + LeaderSchedule.ChunkSlots)

  test(
    "a chunk that takes time is decided before its first slot begins: 2.5 s chunks of 0.1 s slots"
  ) {
    // an epoch of 300 slots of 0.1 s, signalled at its onset, each chunk taking 2.5 s; the last
    // chunk, whose slots begin at 20 s, is decided by 17.5 s, not from 18 s to 20.5 s
    val g = geometry(100, 300, 0.seconds)
    val current = inputs(g, 0)
    recorded(g, InForce.of(current), complete(current), perEvaluation = 25.millis).map {
      (log, state) =>
        assertEquals(log.map(_._2.until), Vector(BigInt(100), BigInt(200), BigInt(300)))
        assertEquals(log.map(_._1), Vector(at(2.5), at(5), at(17.5)))
        assertEquals(caughtUp(g, log).size, 2)
        assertEquals(overtaken(g, caughtUp(g, log)), Vector.empty)
        assertEquals(mismatches(state, current), Vector.empty)
    }
  }

  test(
    "a begun epoch, its chunks taking up to 80% of their slots' time: ahead of the clock once caught up"
  ) {
    // 0.1 s slots, epochs of 2000, the clock at slot 300: a chunk's slots take 10 s to pass
    val g = geometry(100, 2000, 30.seconds)
    val current = inputs(g, 0)
    val (deadline, margin) = LeaderSchedule.Pacing.target(g, current, Instant.EPOCH) match
      case LeaderSchedule.Pacing.Target.Begun(d, m) => (d, m)
      case other                                    => fail(s"not begun: $other")
    List(0, 10, 25, 50, 80)
      .traverse(ms =>
        recorded(g, InForce.of(current), complete(current), perEvaluation = ms.millis).map(ms -> _)
      )
      .map(_.foreach { (ms, run) =>
        val (log, _) = run
        assert(caughtUp(g, log).nonEmpty, s"$ms ms an evaluation: never a chunk ahead")
        assertEquals(overtaken(g, caughtUp(g, log)), Vector.empty, s"$ms ms an evaluation")
        assertEquals(log.last._2.until, current.end)
        // done by the deadline whenever the decisions fit before it, less the margin
        val left = java.time.Duration.between(Instant.EPOCH, deadline).toNanos.nanos - margin
        val fits = (ms.millis * 100) * log.size.toLong <= left
        assert(!fits || !log.last._1.isAfter(deadline), s"$ms ms: done at ${log.last._1}")
      })
  }

  test(
    "an epoch ahead is done a margin before its first slot whenever its decisions fit, and stays ahead when not"
  ) {
    // 1 s slots, epochs of 1000, the clock at slot 300: epoch 1 starts in 700 s, of which 70 s
    // are the margin; ten chunks of up to 63 s fit, and of 80 s do not
    val g = geometry(1000, 1000, 300.seconds)
    val next = inputs(g, 1)
    List(0, 100, 500, 630, 800)
      .traverse(ms =>
        recorded(g, InForce.of(next), complete(next), perEvaluation = ms.millis).map(ms -> _)
      )
      .map(_.foreach { (ms, run) =>
        val (log, state) = run
        assertEquals(log.map(_._2.until).last, next.end, s"$ms ms an evaluation")
        assertEquals(overtaken(g, log), Vector.empty, s"$ms ms an evaluation")
        if ms <= 630 then assert(!log.last._1.isAfter(at(630)), s"$ms ms: done at ${log.last._1}")
        else assert(log.last._1.isAfter(g.slotStart(next.first)), s"$ms ms: done at ${log.last._1}")
        assertEquals(mismatches(state, next), Vector.empty)
      })
  }

  test("an epoch ahead is done before its first slot, its chunks spread over the time left") {
    // 1 s slots, epochs of 1000 slots, the clock at slot 300: epoch 1 starts in 700 s, and a
    // tenth of that, 70 s, is kept, so ten chunks share the 630 s before
    val g = geometry(1000, 1000, 300.seconds)
    val next = inputs(g, 1)
    recorded(g, InForce.of(next), complete(next)).map { (log, state) =>
      assertEquals(log.map(_._2.until), (1 to 10).map(i => next.first + 100 * i).toVector)
      assertEquals(log.map(_._1), (0 until 10).map(i => at(70.0 * i)).toVector)
      assert(log.last._1.isBefore(g.slotStart(next.first)), log.last._1.toString)
      assertEquals(mismatches(state, next), Vector.empty)
      assert(state.of(next).exists(_.led.nonEmpty), "no slot led")
    }
  }

  test(
    "an epoch begun: from the clock's slot, a chunk ahead at once, and ahead of the clock after"
  ) {
    // the trial devnet's geometry: 0.1 s slots, epochs of 500; the clock 37 ms into slot 750, half
    // way through epoch 1
    val g = geometry(100, 500, 75037.millis)
    val current = inputs(g, 1)
    recorded(g, InForce.of(current), complete(current)).map { (log, state) =>
      assertEquals(log.map(_._2.from).distinct, Vector(BigInt(750)))
      assertEquals(log.map(_._2.until), Vector(BigInt(850), BigInt(950), BigInt(1000)))
      // caught up with the first chunk, then each slot covered before the clock reaches it
      assertEquals(log.head._1, Instant.EPOCH)
      assertEquals(overtaken(g, log), Vector.empty)
      // done a chunk's slots before the epoch's end
      assert(!log.last._1.isAfter(g.slotStart(current.end - 100)), log.last._1.toString)
      assertEquals(mismatches(state, current, from = 750), Vector.empty)
    }
  }

  test("a new value cancels the work under way, and a schedule of replaced inputs never answers") {
    val g = geometry(1000, 1000, 300.seconds)
    val replaced = inputs(g, 1)
    val now = inputs(g, 1, Nonce.Neutral)
    recorded(
      g,
      InForce.of(replaced),
      complete(now),
      (signal, s) => signal.set(InForce.of(now)).whenA(s.inputs == replaced)
    ).map { (log, state) =>
      // the replaced inputs' first chunk, then the new ones' ten from the epoch's first slot
      assertEquals(log.map(_._2.inputs == replaced), true +: Vector.fill(10)(false))
      assertEquals(log.drop(1).map(_._2.from).distinct, Vector(now.first))
      assertEquals(log.drop(1).map(_._2.until), (1 to 10).map(i => now.first + 100 * i).toVector)
      (replaced.first until replaced.end).foreach(slot =>
        assertEquals(state.answer(slot, replaced), Answer.Uncovered, s"slot $slot")
      )
      assertEquals(mismatches(state, now), Vector.empty)
    }
  }

  test("a value equal to the one before, its VRF key made again, does not restart the work") {
    val g = geometry(1000, 1000, 300.seconds)
    val first = inputs(g, 1)
    val again = inputs(g, 1)
    assert(!(first.key eq again.key))
    recorded(
      g,
      InForce.of(first),
      complete(first),
      (signal, _) => signal.set(InForce.of(again))
    ).map { (log, _) =>
      // a restart would decide the next chunk at once, not 70 s on
      assertEquals(log.map(_._1), (0 until 10).map(i => at(70.0 * i)).toVector)
      assertEquals(log.map(_._2.until), (1 to 10).map(i => first.first + 100 * i).toVector)
    }
  }

  test(
    "the next epoch's inputs restart the stream, and the epoch under way goes on from its schedule"
  ) {
    val g = geometry(1000, 1000, 300.seconds)
    val current = inputs(g, 0)
    val next = inputs(g, 1)
    recorded(
      g,
      InForce.of(current),
      log => complete(current)(log) && complete(next)(log),
      (signal, s) =>
        signal.set(InForce.of(current, next)).whenA(s.inputs == current && s.until == 400)
    ).map { (log, state) =>
      // each chunk of the epoch under way decided once, from the clock's slot
      val ours = log.map(_._2).filter(_.inputs == current)
      assertEquals(ours.map(_.until), (4 to 10).map(i => BigInt(100 * i)).toVector)
      assertEquals(ours.map(_.from).distinct, Vector(BigInt(300)))
      // each paced to its own deadline: the epoch under way ahead of the clock, the next done
      // before its first slot
      assertEquals(overtaken(g, log.filter(_._2.inputs == current)), Vector.empty)
      val theirs = log.filter(_._2.inputs == next)
      assertEquals(theirs.map(_._2.until).lastOption, Some(next.end))
      assert(theirs.forall(_._1.isBefore(g.slotStart(next.first))), theirs.map(_._1).toString)
      assertEquals(mismatches(state, current, from = 300), Vector.empty)
      assertEquals(mismatches(state, next), Vector.empty)
    }
  }

  test("an epoch whose slots have all passed is never computed") {
    val g = geometry(1000, 1000, 1300.seconds)
    val passed = inputs(g, 0)
    val current = inputs(g, 1)
    recorded(g, InForce.of(passed, current), complete(current)).map { (log, state) =>
      assertEquals(log.filter(_._2.inputs == passed), Vector.empty)
      assertEquals(state.of(passed), None)
      assertEquals(mismatches(state, current, from = 1300), Vector.empty)
    }
  }

  /** The seeds of `TestControl` 's scheduler the stream's orderings are tried under. */
  private val seeds: Vector[(Int, String)] =
    (1 to 64).toVector.map(n =>
      n -> java.util.Base64.getEncoder.encodeToString(n.toString.getBytes)
    )

  /** `schedule` 's computation of `signal` on `TestControl` 's clock under `seed`, run until
    * nothing is left to run, then looked at with `observe`, then cancelled, in guaranteed cleanup.
    * The result is what `observe` gave, if the computation ran until it was cancelled; a
    * computation that failed or ended before fails the test.
    */
  private def quiescent[A](
      schedule: LeaderSchedule[IO],
      signal: Signal[IO, InForce],
      seed: String
  )(observe: LeaderSchedule[IO] => IO[A]): IO[A] =
    for
      stop <- Deferred[IO, Unit]
      control <- TestControl.execute(
        schedule.computed(signal).compile.drain.background.use(ended => IO.race(ended, stop.get)),
        seed = Some(seed)
      )
      observed <- (control.tickAll *> observe(schedule))
        .guarantee(stop.complete(()).void *> control.tickAll)
      result <- control.results
      _ <- result match
        case Some(Outcome.Succeeded(Right(()))) => IO.unit
        case other =>
          IO.raiseError(new AssertionError(s"the computation did not run until cancelled: $other"))
    yield observed

  test("a value replaced before its computation began never undoes the schedule after it") {
    // the signal gives no inputs, then at once an epoch's, which is the value in force: fs2's
    // `switchMap` can start the first value's computation after the second's ends, whichever order
    // the fibers released together run in (`TestControl` 's seed); the epoch's schedule, once
    // complete, stays
    val g = geometry(100, 300, 0.seconds)
    val i = inputs(g, 1)
    val replaced = new Signal[IO, InForce]:
      def discrete = Stream(InForce.none, InForce.of(i)) ++ Stream.never[IO]
      def continuous = Stream.repeatEval(get)
      def get = IO.pure(InForce.of(i))
    seeds
      .traverse((n, seed) =>
        LeaderSchedule
          .create[IO](g)
          .flatMap(quiescent(_, replaced, seed)(_.schedules.get.map(_.of(i).exists(_.complete))))
          .tupleLeft(n)
      )
      .map(kept => assertEquals(kept.filterNot(_._2).map(_._1), Vector.empty, "seeds that lost it"))
  }

  test("a value replaced and restored before the stream saw the replacement is computed") {
    // the stream reads A, an epoch's inputs; before A's computation starts the signal becomes B,
    // none, and before the stream reads it again, A once more, so that the stream reads A again and
    // never B: A is in force throughout what the stream saw, and its schedule is computed. The
    // signal goes back to A once A's computation has started or the signal has been read since it
    // became B, whichever comes first
    val g = geometry(100, 300, 0.seconds)
    val i = inputs(g, 1)
    val a = InForce.of(i)
    seeds
      .traverse((n, seed) =>
        for
          schedule <- LeaderSchedule.create[IO](g)
          current <- Ref.of[IO, InForce](a)
          read <- Deferred[IO, Unit]
          computing = schedule.schedules.discrete.find(_.of(i).nonEmpty).compile.drain
          flickering = new Signal[IO, InForce]:
            def get = current.get.flatTap(_ => read.complete(()).void)
            def continuous = Stream.repeatEval(get)
            def discrete =
              Stream.eval(current.getAndSet(InForce.none)) ++
                Stream.exec(IO.race(read.get, computing) *> current.set(a)) ++
                Stream.eval(current.get) ++ Stream.never[IO]
          kept <- quiescent(schedule, flickering, seed)(
            _.schedules.get.map(_.of(i).exists(_.complete))
          )
        yield n -> kept
      )
      .map(kept => assertEquals(kept.filterNot(_._2).map(_._1), Vector.empty, "seeds that lost it"))
  }
