// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Eq
import cats.effect.{Async, Clock, Deferred}
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.{Signal, SignallingRef}
import java.time.{Duration, Instant}
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.ledger.{EpochGeometry, ForgeError, VrfCert}

/** The pool's leader schedule (#1101): for each epoch it can still lead in, the slots it leads and
  * their VRF certificates, computed ahead of the slots from the epoch's inputs ([[EpochInputs]]).
  * Each slot's decision and certificate are those of [[Leadership.check]] of the slot with the same
  * inputs: the computation evaluates every slot's output with [[Leadership.decide]] and proves the
  * led slots only.
  *
  * [[computed]] walks each epoch of a signal's value in chunks of [[LeaderSchedule.ChunkSlots]]
  * slots, paced so that the work is spread over the time left, done before the epoch starts, and
  * ahead of the clock ([[LeaderSchedule.Pacing]], which states what it assumes of a chunk's
  * computation). A new value of the signal cancels the computation under way and starts again from
  * the new inputs; an epoch whose inputs did not change goes on from where its schedule got.
  *
  * Each chunk is one evaluation of the VRF key the inputs name, borrowed from the credentials in
  * force for the chunk alone (`keys`, [[Leadership.Keys]], #1418), never across a pause: a reload
  * that installs the same key under other credentials leaves the walk going with theirs, and one
  * that installs another key, or disables forging, ends the walk of inputs that name the key no
  * longer in force (the signal's next value names the key in force, or none).
  *
  * The schedules are held in memory only: a restart computes them again. Time comes only from `now`
  * and `sleep`.
  */
final class LeaderSchedule[F[_]: Async] private (
    geometry: EpochGeometry,
    now: F[Instant],
    sleep: FiniteDuration => F[Unit],
    keys: Leadership.Keys[F],
    state: SignallingRef[F, LeaderSchedule.Schedules]
):
  import LeaderSchedule.*
  private val F = Async[F]

  /** What the schedule says of `slot` under `inForce`, the inputs in force for the slot's epoch: a
    * schedule computed from other inputs gives no answer.
    */
  def answer(slot: BigInt, inForce: EpochInputs): F[Answer] =
    state.get.map(_.answer(slot, inForce))

  /** The schedule of each epoch, as it is computed. */
  def schedules: Signal[F, Schedules] = state

  /** The computation of the schedules of the epochs in `signal` 's values, until the stream is
    * cancelled. A value equal to the one before changes nothing; a new one cancels the computation
    * under way. Each element is an epoch's schedule just after a chunk was added to it.
    *
    * The values are taken in the order the signal's changes give them. A new value stops the
    * computation under way and waits for its end, its finalizers included, before its own is
    * opened: at most one is open at a time, and a replaced value's computation never begins after
    * its successor's. Begun after it, it would start the schedules again from its own inputs and
    * drop the epochs it does not name, its successor's among them, for good (#1101). A value the
    * signal held only between two of the stream's readings is never seen (`discrete` gives the
    * latest value): the value read next is computed, or, equal to the one before, leaves that one's
    * computation going.
    */
  def computed(signal: Signal[F, InForce]): Stream[F, Added] =
    Stream.eval(F.ref(Option.empty[(Deferred[F, Unit], Deferred[F, Unit])])).flatMap { running =>
      signal.discrete.changes
        .evalMap(value =>
          (F.deferred[Unit], F.deferred[Unit]).flatMapN { (replaced, ended) =>
            running
              .getAndSet(Some(replaced -> ended))
              .flatMap(_.traverse_((stop, end) => stop.complete(()) *> end.get))
              .as(
                all(value)
                  .interruptWhen(replaced.get.attempt)
                  .onFinalize(ended.complete(()).void)
              )
          }
        )
        .parJoinUnbounded
    }

  /** [[computed]], its elements discarded. */
  def run(signal: Signal[F, InForce]): F[Unit] = computed(signal).compile.drain

  /** The epochs of `value` whose slots have not all passed, each walked on its own, so that each is
    * paced to its own deadline. The schedules of the epochs that have passed are dropped, and an
    * epoch whose inputs changed starts again from nothing.
    */
  private def all(value: InForce): Stream[F, Added] =
    Stream.eval(now).flatMap { signalled =>
      val clock = geometry.slotAt(signalled)
      val live = value.epochs.filter(_.end > clock)
      Stream.exec(state.update(_.pruned(clock).started(live))) ++
        Stream
          .emits(live)
          .map(inputs => walk(inputs, Pacing.target(geometry, inputs, signalled)))
          .parJoinUnbounded
    }

  private def walk(inputs: EpochInputs, target: Pacing.Target): Stream[F, Added] =
    Stream.eval(step(inputs)).flatMap {
      case Step.Ended       => Stream.empty
      case Step.Last(added) => Stream.emit(added)
      case Step.Paced(added) =>
        Stream.emit(added) ++ Stream.exec(pause(target, added)) ++ walk(inputs, target)
    }

  /** The next chunk of the epoch of `inputs`, decided and added to its schedule, if the schedule
    * has these inputs and has slots left and the VRF key they name is in force, with the time its
    * decisions took.
    */
  private def step(inputs: EpochInputs): F[Step] =
    for
      at <- now
      clock = geometry.slotAt(at)
      current <- state.get
      result <- current.of(inputs).flatMap(_.next(clock)) match
        case None => F.pure(Step.Ended)
        case Some((from, until)) =>
          keys.evaluating(inputs.vrf)(decided(inputs, from, until, _)).flatMap {
            case None => F.pure(Step.Ended)
            case Some(chunk) =>
              for
                done <- now
                took = Pacing.elapsed(at, done)
                next <- state.modify { s =>
                  s.pruned(clock).added(chunk) match
                    case None => (s, Step.Ended)
                    case Some((updated, schedule)) =>
                      val added = Added(schedule, done, took)
                      val next = chunk.failure match
                        case Some(_) => Step.Last(added)
                        case None =>
                          if schedule.complete then Step.Last(added) else Step.Paced(added)
                      (updated, next)
                }
              yield next
          }
    yield result

  private def pause(target: Pacing.Target, added: Added): F[Unit] =
    now.flatMap(at => sleep(Pacing.pause(geometry, target, at, added.schedule, added.took)))

object LeaderSchedule:
  /** The slots decided between two pauses. */
  val ChunkSlots: BigInt = 100

  /** What the schedule says of a slot. */
  enum Answer:
    /** The pool leads the slot, with this certificate. */
    case Led(cert: VrfCert)

    /** The pool does not lead the slot. */
    case NotLed

    /** The schedule does not cover the slot under the inputs asked about: not yet computed, passed
      * over, computed from other inputs, or a slot whose check fails otherwise than by not leading
      * it. The slot is decided at the slot.
      */
    case Uncovered

  /** The inputs of the epochs the pool can still lead in, by epoch: the epoch the clock is in and,
    * once its inputs are known, the next. None while forging is disabled.
    */
  final case class InForce private (byEpoch: SortedMap[BigInt, EpochInputs]):
    def epochs: Vector[EpochInputs] = byEpoch.values.toVector

  object InForce:
    val none: InForce = InForce(SortedMap.empty)

    def of(inputs: EpochInputs*): InForce = InForce(SortedMap.from(inputs.map(i => i.epoch -> i)))

    given Eq[InForce] = Eq.fromUniversalEquals

  /** The decisions of a chunk of slots under `inputs`: those from `from` until `until`, with the
    * certificates of the led ones; `failure` when the check of slot `until` failed otherwise than
    * by not leading it, so that the chunk ends there.
    */
  final case class Decided(
      inputs: EpochInputs,
      from: BigInt,
      until: BigInt,
      led: Vector[(BigInt, VrfCert)],
      failure: Option[ForgeError]
  )

  /** The decisions of the slots from `from` until `until` under `inputs`, each as
    * [[Leadership.decide]] makes it with the evaluations of `key`: the output of every slot, and a
    * proof for a led slot only.
    */
  private[forge] def decided(
      inputs: EpochInputs,
      from: BigInt,
      until: BigInt,
      key: Leadership.Evaluator
  ): Decided =
    @tailrec def go(slot: BigInt, led: Vector[(BigInt, VrfCert)]): Decided =
      if slot >= until then Decided(inputs, from, until, led, None)
      else
        Leadership.decide(
          slot,
          inputs.nonce,
          key,
          inputs.stake,
          inputs.total,
          inputs.activeSlotsCoeff
        ) match
          case Right(cert)                   => go(slot + 1, led :+ (slot -> cert))
          case Left(ForgeError.NotLeader(_)) => go(slot + 1, led)
          case Left(failure)                 => Decided(inputs, from, slot, led, Some(failure))
    go(from, Vector.empty)

  /** An epoch's schedule: computed from `inputs`, it covers the slots from `from` until `until` and
    * holds the certificate of each led slot among them.
    */
  final case class EpochSchedule private (
      inputs: EpochInputs,
      from: BigInt,
      until: BigInt,
      led: SortedMap[BigInt, VrfCert]
  ):
    /** Whether it covers the epoch to its last slot. */
    def complete: Boolean = until >= inputs.end

    def answer(slot: BigInt, inForce: EpochInputs): Answer =
      if inForce == inputs && from <= slot && slot < until then
        led.get(slot).fold(Answer.NotLed)(Answer.Led(_))
      else Answer.Uncovered

    /** The slots of the next chunk: from where the schedule ends or, once the clock is past that,
      * from the clock's slot, since the slots before it have passed.
      */
    private[forge] def next(clock: BigInt): Option[(BigInt, BigInt)] =
      val start = until.max(clock)
      Option.when(start < inputs.end)(start -> (start + ChunkSlots).min(inputs.end))

    /** The schedule with `chunk` added, if the chunk was decided under its inputs and starts where
      * it ends, or later, past slots it drops.
      */
    private[forge] def added(chunk: Decided): Option[EpochSchedule] =
      Option.when(chunk.inputs == inputs && chunk.from >= until)(
        if chunk.from == until then copy(until = chunk.until, led = led ++ chunk.led)
        else EpochSchedule(inputs, chunk.from, chunk.until, SortedMap.from(chunk.led))
      )

  /** An epoch's schedule just after a chunk was added to it, at `at`, the chunk's decisions having
    * taken `took`.
    */
  final case class Added(schedule: EpochSchedule, at: Instant, took: FiniteDuration)

  object EpochSchedule:
    /** The schedule of `inputs` before any chunk. */
    private[forge] def empty(inputs: EpochInputs): EpochSchedule =
      EpochSchedule(inputs, inputs.first, inputs.first, SortedMap.empty)

  /** The schedule of each epoch, by epoch. */
  final case class Schedules private (byEpoch: Map[BigInt, EpochSchedule]):
    def answer(slot: BigInt, inForce: EpochInputs): Answer =
      byEpoch.get(inForce.epoch).fold(Answer.Uncovered)(_.answer(slot, inForce))

    /** The schedule of the epoch of `inputs`, if it was computed from them. */
    def of(inputs: EpochInputs): Option[EpochSchedule] =
      byEpoch.get(inputs.epoch).filter(_.inputs == inputs)

    /** The schedule of `epoch`, whatever its inputs. */
    def epoch(epoch: BigInt): Option[EpochSchedule] = byEpoch.get(epoch)

    /** The first slot after `after` that a schedule leads, whatever its inputs. */
    def nextLed(after: BigInt): Option[BigInt] =
      byEpoch.values.flatMap(_.led.keysIteratorFrom(after + 1).nextOption()).minOption

    /** A schedule for each of `inputs`: kept if it was computed from them, else started again; the
      * schedules of the epochs `inputs` does not name are dropped, so that forging switched off
      * leaves none.
      */
    private[forge] def started(inputs: Seq[EpochInputs]): Schedules =
      Schedules(
        inputs.foldLeft(byEpoch.filter((e, _) => inputs.exists(_.epoch == e))) { (schedules, i) =>
          if schedules.get(i.epoch).exists(_.inputs == i) then schedules
          else schedules.updated(i.epoch, EpochSchedule.empty(i))
        }
      )

    /** With `chunk` added to its epoch's schedule, and that schedule, if it takes the chunk. */
    private[forge] def added(chunk: Decided): Option[(Schedules, EpochSchedule)] =
      of(chunk.inputs)
        .flatMap(_.added(chunk))
        .map(s => (Schedules(byEpoch.updated(s.inputs.epoch, s)), s))

    /** Without the epochs whose slots have all passed by the clock's slot `clock`. */
    private[forge] def pruned(clock: BigInt): Schedules =
      Schedules(byEpoch.filter((_, s) => s.inputs.end > clock))

  object Schedules:
    val empty: Schedules = Schedules(Map.empty)

  /** What a step of an epoch's walk did. */
  private enum Step:
    /** Nothing: the epoch is covered or passed, its inputs were replaced, or the VRF key they name
      * is no longer in force.
      */
    case Ended

    /** A chunk was added, and the walk ends: the epoch is covered, or a slot's check failed. */
    case Last(added: Added)

    /** A chunk was added, and slots are left: the walk pauses, then goes on. */
    case Paced(added: Added)

  /** The pacing of an epoch's walk (#1101). The pause after a chunk is the time left until the
    * deadline, less a margin and less the time the chunks left take to decide, shared among those
    * chunks; each chunk left is taken to need as long as the last one took. So the work is spread
    * and done a margin before the deadline when each chunk takes no longer than the one before it
    * and the chunks left fit in the time left; chunks that grow can end after the deadline, the
    * schedule still ahead of the clock. The margin is a tenth of the time from the signal to the
    * deadline.
    *
    * No pause is so long that the next chunk, taking as long as the last, would end less than that
    * time again before the onset of the first slot the schedule has not decided. Once a chunk ahead
    * of the clock, the schedule so stays ahead of it, each chunk decided before its first slot
    * begins, as long as deciding a chunk takes less than the time its slots take to pass, less one
    * slot (9.9 s of 0.1 s slots, 99 s of 1 s slots), and less than twice as long as the chunk
    * before it. A chunk that takes longer can end after the clock has reached its first slots: the
    * walk then goes on from the clock's slot, and each slot the clock reaches before the schedule
    * covers it is decided at the slot.
    */
  object Pacing:
    /** What an epoch's walk is paced to. */
    enum Target:
      /** The epoch had not begun at the signal: done before its first slot. */
      case Ahead(deadline: Instant, margin: FiniteDuration)

      /** The epoch had begun: from the clock's slot, with no pause until it is a chunk ahead of the
        * clock, then done a chunk's slots before the epoch's end, so that each chunk is decided
        * before the clock reaches it.
        */
      case Begun(deadline: Instant, margin: FiniteDuration)

    /** The target of the walk of `inputs` from a signal at `signalled`. */
    def target(geometry: EpochGeometry, inputs: EpochInputs, signalled: Instant): Target =
      val first = geometry.slotStart(inputs.first)
      if signalled.isBefore(first) then Target.Ahead(first, tenth(signalled, first))
      else
        val deadline = geometry.slotStart((inputs.end - ChunkSlots).max(inputs.first))
        Target.Begun(deadline, tenth(signalled, deadline))

    /** The pause at `now` after a chunk added to `schedule` whose decisions took `took`. */
    def pause(
        geometry: EpochGeometry,
        target: Target,
        now: Instant,
        schedule: EpochSchedule,
        took: FiniteDuration
    ): FiniteDuration =
      val clock = geometry.slotAt(now)
      val left = chunksLeft(schedule.until.max(clock), schedule.inputs.end)
      // the next chunk, and as long again, before the first slot not decided
      val cap = nanos(now, geometry.slotStart(schedule.until)) - 2 * BigInt(took.toNanos)
      target match
        case Target.Begun(_, _) if schedule.until < clock + ChunkSlots => 0.nanos
        case Target.Begun(deadline, margin) => spread(deadline, margin, now, left, took, cap)
        case Target.Ahead(deadline, margin) => spread(deadline, margin, now, left, took, cap)

    /** The time from `from` to `to`, none if `to` is earlier. */
    def elapsed(from: Instant, to: Instant): FiniteDuration = bounded(nanos(from, to).max(0))

    /** The chunks that cover the slots from `from` until `end`. */
    def chunksLeft(from: BigInt, end: BigInt): BigInt =
      ((end - from).max(0) + ChunkSlots - 1) / ChunkSlots

    private def spread(
        deadline: Instant,
        margin: FiniteDuration,
        now: Instant,
        left: BigInt,
        took: FiniteDuration,
        cap: BigInt
    ): FiniteDuration =
      if left <= 0 then 0.nanos
      else
        val reserved = left * took.toNanos
        bounded(((nanos(now, deadline) - margin.toNanos - reserved) / left).min(cap).max(0))

    private def tenth(from: Instant, to: Instant): FiniteDuration =
      bounded(nanos(from, to).max(0) / 10)

    private def nanos(from: Instant, to: Instant): BigInt =
      val d = Duration.between(from, to)
      BigInt(d.getSeconds) * 1000000000 + d.getNano

    private def bounded(n: BigInt): FiniteDuration =
      n.min(BigInt(Forger.LongestWait.toNanos)).toLong.nanos

  /** A schedule over `geometry` 's slots, empty, whose chunks evaluate the VRF key `keys` lends.
    * `now` and `sleep` default to `Clock` and `sleep` of `F`.
    */
  private[forge] def create[F[_]: Async](
      geometry: EpochGeometry,
      keys: Leadership.Keys[F],
      now: Option[F[Instant]] = None,
      sleep: Option[FiniteDuration => F[Unit]] = None
  ): F[LeaderSchedule[F]] =
    SignallingRef
      .of[F, Schedules](Schedules.empty)
      .map(state =>
        new LeaderSchedule[F](
          geometry,
          now.getOrElse(Clock[F].realTimeInstant),
          sleep.getOrElse(d => Async[F].sleep(d)),
          keys,
          state
        )
      )
