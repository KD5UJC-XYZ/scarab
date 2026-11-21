// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Resource, Temporal}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import java.time.{Duration, Instant}
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.VolatileChain
import xyz.kd5ujc.scarab.ledger.ClockProblem
import xyz.kd5ujc.scarab.node.forge.Forger

/** The node's blockchain time (`BlockchainTime`, `hardForkBlockchainTime`,
  * `BlockchainTime/WallClock/HardFork.hs` 67-150 at ouroboros-consensus 2416493b): the slot that a
  * fiber of its own publishes, which readers take as it stands (`getCurrentSlot = readTVar
  * slotVar`) rather than reading the wall clock themselves. Unknown until the fiber's first reading
  * after the system start.
  */
final class BlockchainTime[F[_]] private (slot: Ref[F, Option[BigInt]]):
  /** `getCurrentSlot`: the slot last published, none while it is unknown. */
  def currentSlot: F[Option[BigInt]] = slot.get

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

  /** The blockchain time over `clock` for the life of the resource. Its fiber waits for the system
    * start (`systemTimeWait`), then reads the slot ([[Forger.reading]]: a slot the era history at
    * the chain's tip does not translate is unknown, `translates`), publishes it as
    * [[checkValidClockChange]] has it, and waits until the next slot, or `backoff` while the slot
    * is unknown (`getCurrentSlot'`, `HardFork.hs` 156-185). A clock moved back too far goes to
    * `moved`, and an exception in the fiber to `defect`, each of which stops the node, as the
    * reference's thread is linked to it. `sleep` is the wait, `Temporal.sleep` but in tests.
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
    Resource.eval(Ref.of[F, Option[BigInt]](None)).flatMap { slot =>
      def go(previous: Option[(Option[BigInt], Instant)]): F[Unit] =
        (clock.now, clock.geometry, chain).tupled.flatMap { (instant, geometry, current) =>
          Forger.reading(geometry, instant, translates(current, _)) match
            case Forger.Reading.BeforeStart(remaining) => wait(remaining) *> go(previous)
            case reading @ (Forger.Reading.Unknown | Forger.Reading.Known(_)) =>
              val read = reading match
                case Forger.Reading.Known(s) => Some(s)
                case _                       => None
              previous.fold(Right(read))(checkValidClockChange(_, (read, instant))) match
                case Left(problem) => moved(problem) *> F.never[Unit]
                case Right(published) =>
                  val delay = read.fold(backoff) { s =>
                    val until = Duration.between(instant, geometry.slotStart(s + 1))
                    if until.toDays >= Forger.LongestWait.toDays then Forger.LongestWait
                    else until.toNanos.nanos.max(1.millis)
                  }
                  slot.set(published) *> wait(delay) *> go(Some(published -> instant))
        }
      go(None).handleErrorWith(defect).background.as(new BlockchainTime(slot))
    }
