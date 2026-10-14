// SPDX-License-Identifier: Apache-2.0
package lab.node.forge

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap

/** What the forger did in one epoch (H5): the slots it checked for leadership, the ones it led,
  * what became of the blocks, the slots it could not even check (by reason) and how long a forged
  * block took from the start of its slot to adoption.
  *
  * `lost` counts blocks that did not stay on the chain: lost slot battles at adoption and blocks
  * replaced by another one later (a block may be both `adopted` and later `lost`).
  */
final case class ForgeEpochStats(
    slotsChecked: Long = 0,
    leaderSlots: Long = 0,
    forged: Long = 0,
    adopted: Long = 0,
    lost: Long = 0,
    failed: Long = 0,
    /** Slots not checked, by reason: `busy` (the loop was still on an earlier slot), `tipTooOld`,
      * `slotOccupied` (a block already held the slot or a later one), `notConnected`.
      */
    skipped: Map[String, Long] = Map.empty,
    latencyCount: Long = 0,
    latencyTotalMs: Long = 0,
    latencyMaxMs: Long = 0
):
  def skippedTotal: Long = skipped.values.sum

  def merge(o: ForgeEpochStats): ForgeEpochStats =
    ForgeEpochStats(
      slotsChecked + o.slotsChecked,
      leaderSlots + o.leaderSlots,
      forged + o.forged,
      adopted + o.adopted,
      lost + o.lost,
      failed + o.failed,
      (skipped.keySet ++ o.skipped.keySet).iterator
        .map(k => k -> (skipped.getOrElse(k, 0L) + o.skipped.getOrElse(k, 0L)))
        .toMap,
      latencyCount + o.latencyCount,
      latencyTotalMs + o.latencyTotalMs,
      latencyMaxMs.max(o.latencyMaxMs)
    )

object ForgeEpochStats:
  /** Skip reasons that are counted but not logged one by one (they happen every slot). */
  val Busy = "busy"
  val TipTooOld = "tipTooOld"
  val SlotOccupied = "slotOccupied"
  val NotConnected = "notConnected"

/** The forger's counters per epoch. */
final class ForgeStats[F[_]: Sync] private (ref: Ref[F, SortedMap[BigInt, ForgeEpochStats]]):
  private def update(epoch: BigInt)(f: ForgeEpochStats => ForgeEpochStats): F[Unit] =
    ref.update(m => m.updated(epoch, f(m.getOrElse(epoch, ForgeEpochStats()))))

  def checked(epoch: BigInt): F[Unit] =
    update(epoch)(s => s.copy(slotsChecked = s.slotsChecked + 1))
  def leader(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(leaderSlots = s.leaderSlots + 1))
  def forged(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(forged = s.forged + 1))
  def failed(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(failed = s.failed + 1))
  def lost(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(lost = s.lost + 1))
  def skipped(epoch: BigInt, reason: String): F[Unit] =
    update(epoch)(s =>
      s.copy(skipped = s.skipped.updated(reason, s.skipped.getOrElse(reason, 0L) + 1))
    )

  /** A block became our tip `latencyMs` after the start of its slot. */
  def adopted(epoch: BigInt, latencyMs: Long): F[Unit] =
    update(epoch)(s =>
      s.copy(
        adopted = s.adopted + 1,
        latencyCount = s.latencyCount + 1,
        latencyTotalMs = s.latencyTotalMs + latencyMs,
        latencyMaxMs = s.latencyMaxMs.max(latencyMs)
      )
    )

  def epoch(epoch: BigInt): F[ForgeEpochStats] = ref.get.map(_.getOrElse(epoch, ForgeEpochStats()))
  def byEpoch: F[SortedMap[BigInt, ForgeEpochStats]] = ref.get
  def total: F[ForgeEpochStats] = ref.get.map(_.values.foldLeft(ForgeEpochStats())(_.merge(_)))

object ForgeStats:
  def create[F[_]: Sync]: F[ForgeStats[F]] =
    Ref.of[F, SortedMap[BigInt, ForgeEpochStats]](SortedMap.empty).map(new ForgeStats(_))
