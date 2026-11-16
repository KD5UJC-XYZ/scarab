// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.syntax.all.*
import fs2.concurrent.SignallingRef
import java.time.Instant
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.crypto.vrf.{Draft03Prover, PraosLeaderThreshold as Leader}
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, Nonce, TestGeometry}
import xyz.kd5ujc.scarab.network.FailsOnUncaught

import LeaderSchedule.{Answer, EpochSchedule, InForce, Schedules}

/** The leader schedule's stream (#1101) on `TestControl` 's clock, which starts at the epoch of
  * `Instant`: the computation is paced to finish before its deadline and, once caught up, stays
  * ahead of the clock; a new value of the signal cancels the work under way, and a schedule of
  * replaced inputs never answers.
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

  /** The schedules the stream reports from `initial`, each with the time it is reported at, until
    * `done` holds of the state after one; and the state then. `onEach` sees each report first.
    */
  private def recorded(
      g: EpochGeometry,
      initial: InForce,
      done: Schedules => Boolean,
      onEach: (SignallingRef[IO, InForce], EpochSchedule) => IO[Unit] = (_, _) => IO.unit
  ): IO[(Vector[(Instant, EpochSchedule)], Schedules)] =
    TestControl.executeEmbed(
      for
        schedule <- LeaderSchedule.create[IO](g)
        signal <- SignallingRef.of[IO, InForce](initial)
        log <- schedule
          .computed(signal)
          .evalTap(s => onEach(signal, s))
          .evalMap(s =>
            (IO.realTimeInstant, schedule.schedules.get).tupled.map((t, all) => (t, s, all))
          )
          .takeThrough((_, _, all) => !done(all))
          .map((t, s, _) => t -> s)
          .compile
          .toVector
        state <- schedule.schedules.get
      yield (log, state)
    )

  private def complete(i: EpochInputs)(state: Schedules): Boolean = state.of(i).exists(_.complete)

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
      state => complete(current)(state) && complete(next)(state),
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
