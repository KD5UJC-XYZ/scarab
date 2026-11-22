// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Resource, Temporal}
import fs2.Stream
import fs2.concurrent.SignallingRef
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.ClockProblem
import xyz.kd5ujc.scarab.node.forge.Forger
import xyz.kd5ujc.scarab.node.read.TxClock

/** The node's blockchain time (`BlockchainTime`, `hardForkBlockchainTime`,
  * `BlockchainTime/WallClock/HardFork.hs` 67-150 at ouroboros-consensus 2416493b): the slot that a
  * fiber of its own publishes, which readers take as it stands (`getCurrentSlot = readTVar
  * slotVar`) rather than reading the wall clock themselves. The first reading is taken when the
  * resource is acquired, after the system start.
  */
final class BlockchainTime[F[_]] private (slot: SignallingRef[F, Option[BigInt]]):
  /** `getCurrentSlot`: the slot last published, none while it is unknown. */
  def currentSlot: F[Option[BigInt]] = slot.get

  /** The slot as it is published, each change once: what a transaction that reads `slotVar` retries
    * on.
    */
  def published: Stream[F, Option[BigInt]] = slot.discrete

object BlockchainTime:
  /** `checkValidClockChange` (`HardFork.hs` 118-150 at 2416493b): the slot to publish after
    * `previous` for the reading `next`, each with the time it was read at. A later slot, or a slot
    * known or unknown after the other, is published; the same or an earlier slot within
    * `maxClockRewind` of the previous reading keeps the previous slot, which never decreases;
    * further back, the node stops (`SystemClockMovedBack`).
    */
  def checkValidClockChange(
      previous: (Option[BigInt], Instant),
      next: (Option[BigInt], Instant),
      maxClockRewind: Duration = Forger.MaxClockRewind
  ): Either[ClockProblem, Option[BigInt]] =
    (previous, next) match
      case ((Some(m), prevTime), (Some(n), newTime)) if m >= n =>
        if !Duration.between(newTime, prevTime).minus(maxClockRewind).isPositive then Right(Some(m))
        else Left(ClockProblem.SystemClockMovedBack(m, n))
      case (_, (slot, _)) => Right(slot)

  /** The blockchain time over `clock` for the life of the resource, acquired as
    * `hardForkBlockchainTime` is (`HardFork.hs` 68-90 at 2416493b): it waits for the system start
    * (`systemTimeWait`, `waitForSystemStart`, `WallClock/Default.hs` 23-34), takes the first
    * reading, and publishes it before the resource is given; a fiber of its own then waits the
    * first reading's delay and reads the slot again, published as [[checkValidClockChange]] has it,
    * each reading followed by its own delay (`loop`, `HardFork.hs` 104-119). A reading is the slot
    * ([[Forger.reading]]: a slot the era history at the chain's tip does not translate is unknown,
    * `translates`) and the wait until the next slot, or `backoff` while the slot is unknown
    * (`getCurrentSlot'`, `HardFork.hs` 156-176). A clock moved back too far goes to `moved`, and an
    * exception in the fiber to `defect`, each of which stops the node, as the reference's thread is
    * linked to it. `sleep` is the wait, `Temporal.sleep` but in tests.
    */
  def resource[F[_], S](
      clock: SlotClock[F],
      chain: F[VolatileChain[S]],
      translates: (VolatileChain[S], BigInt) => Boolean,
      moved: ClockProblem => F[Unit],
      defect: Throwable => F[Unit],
      backoff: FiniteDuration = Forger.UnknownSlotBackoff,
      sleep: Option[FiniteDuration => F[Unit]] = None
  )(using F: Temporal[F]): Resource[F, BlockchainTime[F]] =
    val wait = sleep.getOrElse(F.sleep(_))
    // `systemTimeWait`: once, for exactly as long as the system start is ahead
    val systemTimeWait: F[Unit] =
      (clock.now, clock.geometry).tupled.flatMap { (instant, geometry) =>
        wait(Duration.between(instant, geometry.systemStart).toNanos.nanos)
          .whenA(instant.isBefore(geometry.systemStart))
      }
    // `getCurrentSlot'`: the slot, the time it was read at and the wait until the next reading; a
    // time before the system start, which only a clock moved back gives here, translates no slot
    val current: F[(Option[BigInt], Instant, FiniteDuration)] =
      (clock.now, clock.geometry, chain).tupled.map { (instant, geometry, tip) =>
        val read = Forger.reading(geometry, instant, translates(tip, _)) match
          case Forger.Reading.Known(s)                                => Some(s)
          case Forger.Reading.Unknown | Forger.Reading.BeforeStart(_) => None
        val delay = read.fold(backoff) { s =>
          val until = Duration.between(instant, geometry.slotStart(s + 1))
          if until.toDays >= Forger.LongestWait.toDays then Forger.LongestWait
          else until.toNanos.nanos.max(1.millis)
        }
        (read, instant, delay)
      }
    def loop(
        slot: Ref[F, Option[BigInt]],
        previous: (Option[BigInt], Instant),
        delay: FiniteDuration
    ): F[Unit] =
      wait(delay) *> current.flatMap { (read, instant, next) =>
        checkValidClockChange(previous, (read, instant)) match
          case Left(problem) => moved(problem) *> F.never[Unit]
          case Right(published) =>
            slot.set(published) *> loop(slot, (published, instant), next)
      }
    Resource
      .eval(systemTimeWait *> current)
      .flatMap { (first, instant, delay) =>
        Resource.eval(SignallingRef.of[F, Option[BigInt]](first)).flatMap { slot =>
          loop(slot, (first, instant), delay)
            .handleErrorWith(defect)
            .background
            .as(new BlockchainTime(slot))
        }
      }

  /** The blockchain time of the node's selected chain, `chain`: [[resource]] with the era history
    * at the chain's tip translating the slot ([[Forger.withinHorizon]] of `clock` 's
    * [[TxClock.translates]], `hfbtGetLedgerState` with `hardForkSummary`), as the node runs it for
    * the Leios fetch decision.
    */
  def ofChain[F[_]: Temporal, S](
      slotClock: SlotClock[F],
      chain: F[VolatileChain[S]],
      clock: TxClock[S],
      moved: ClockProblem => F[Unit],
      defect: Throwable => F[Unit]
  ): Resource[F, BlockchainTime[F]] =
    resource(slotClock, chain, Forger.withinHorizon[S](using clock), moved, defect)
