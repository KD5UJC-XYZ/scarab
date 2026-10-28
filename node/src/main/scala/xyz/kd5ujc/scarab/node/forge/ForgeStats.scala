// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap

/** What the forger did in one epoch (H5): the loop's traces counted by kind (the reference's
  * `Forge.*` counters, cardano-node 11.1.3 `Tracers/Consensus.hs` 1507-1538), the Scarab-only
  * refusals, the blocks replaced later, and how long a forged block took from the start of its slot
  * to adoption.
  *
  * @param slotsChecked
  *   `TraceStartLeadershipCheck`: every slot the loop ran (`Forge.about-to-lead`).
  * @param slotIsImmutable
  *   `TraceSlotIsImmutable` (`Forge.slot-is-immutable`).
  * @param blockFromFuture
  *   `TraceBlockFromFuture` (`Forge.block-from-future`).
  * @param noLedgerView
  *   `TraceNoLedgerView`.
  * @param stateUpdateError
  *   `TraceForgeStateUpdateError`.
  * @param cannotForge
  *   `TraceNodeCannotForge`.
  * @param notLeader
  *   `TraceNodeNotLeader` (`Forge.node-not-leader`).
  * @param leaderSlots
  *   `TraceNodeIsLeader` (`Forge.node-is-leader`).
  * @param forged
  *   `TraceForgedBlock` (`Forge.forged`).
  * @param adopted
  *   `TraceAdoptedBlock` (`Forge.adopted`).
  * @param didntAdopt
  *   `TraceDidntAdoptBlock` (`Forge.didnt-adopt`): a slot battle lost at adoption.
  * @param forgedInvalid
  *   `TraceForgedInvalidBlock`.
  * @param replaced
  *   Adopted blocks that another block at their height replaced later (Scarab only).
  * @param failed
  *   Slots given up for a reason the reference does not have (`forgeFailed`, Scarab only).
  */
final case class ForgeEpochStats(
    slotsChecked: Long = 0,
    slotIsImmutable: Long = 0,
    blockFromFuture: Long = 0,
    noLedgerView: Long = 0,
    stateUpdateError: Long = 0,
    cannotForge: Long = 0,
    notLeader: Long = 0,
    leaderSlots: Long = 0,
    forged: Long = 0,
    adopted: Long = 0,
    didntAdopt: Long = 0,
    forgedInvalid: Long = 0,
    replaced: Long = 0,
    failed: Long = 0,
    latencyCount: Long = 0,
    latencyTotalMs: Long = 0,
    latencyMaxMs: Long = 0
):
  /** Slots given up before the leader check, by the reference's reason. */
  def skipped: Map[ForgeEpochStats.Skip, Long] =
    Map(
      ForgeEpochStats.Skip.SlotIsImmutable -> slotIsImmutable,
      ForgeEpochStats.Skip.BlockFromFuture -> blockFromFuture,
      ForgeEpochStats.Skip.NoLedgerView -> noLedgerView
    ).filter(_._2 > 0)

  def skippedTotal: Long = slotIsImmutable + blockFromFuture + noLedgerView

  /** One more trace of the loop. */
  def record(trace: ForgeTrace): ForgeEpochStats = trace match
    case ForgeTrace.StartLeadershipCheck(_)     => copy(slotsChecked = slotsChecked + 1)
    case ForgeTrace.SlotIsImmutable(_, _, _)    => copy(slotIsImmutable = slotIsImmutable + 1)
    case ForgeTrace.BlockFromFuture(_, _)       => copy(blockFromFuture = blockFromFuture + 1)
    case ForgeTrace.NoLedgerView(_, _)          => copy(noLedgerView = noLedgerView + 1)
    case ForgeTrace.ForgeStateUpdateError(_, _) => copy(stateUpdateError = stateUpdateError + 1)
    case ForgeTrace.NodeCannotForge(_, _)       => copy(cannotForge = cannotForge + 1)
    case ForgeTrace.NodeNotLeader(_)            => copy(notLeader = notLeader + 1)
    case ForgeTrace.NodeIsLeader(_)             => copy(leaderSlots = leaderSlots + 1)
    case ForgeTrace.ForgedBlock(_, _, _, _, _)  => copy(forged = forged + 1)
    case ForgeTrace.AdoptedBlock(_, _, _, _)    => copy(adopted = adopted + 1)
    case ForgeTrace.DidntAdoptBlock(_, _)       => copy(didntAdopt = didntAdopt + 1)
    case ForgeTrace.ForgedInvalidBlock(_, _, _) => copy(forgedInvalid = forgedInvalid + 1)
    case ForgeTrace.BlockContext(_, _, _) | ForgeTrace.LedgerState(_, _) |
        ForgeTrace.LedgerView(_) | ForgeTrace.ForgeTickedLedgerState(_, _) |
        ForgeTrace.ForgingMempoolSnapshot(_, _, _, _) =>
      this

  def merge(o: ForgeEpochStats): ForgeEpochStats =
    ForgeEpochStats(
      slotsChecked + o.slotsChecked,
      slotIsImmutable + o.slotIsImmutable,
      blockFromFuture + o.blockFromFuture,
      noLedgerView + o.noLedgerView,
      stateUpdateError + o.stateUpdateError,
      cannotForge + o.cannotForge,
      notLeader + o.notLeader,
      leaderSlots + o.leaderSlots,
      forged + o.forged,
      adopted + o.adopted,
      didntAdopt + o.didntAdopt,
      forgedInvalid + o.forgedInvalid,
      replaced + o.replaced,
      failed + o.failed,
      latencyCount + o.latencyCount,
      latencyTotalMs + o.latencyTotalMs,
      latencyMaxMs.max(o.latencyMaxMs)
    )

object ForgeEpochStats:
  /** A reason a slot was given up before the leader check, with its stable key in `/forging` and
    * the epoch summary.
    */
  enum Skip(val key: String):
    case SlotIsImmutable extends Skip("slotIsImmutable")
    case BlockFromFuture extends Skip("blockFromFuture")
    case NoLedgerView extends Skip("noLedgerView")

/** cardano-node's `ForgingStats` (11.1.3 `Tracers/ForgingStats.hs` 26-33): the counts behind the
  * gauges `nodeCannotForge`, `nodeIsLeader`, `blocksForged` and `slotsMissed`, folded over the
  * loop's traces as `calculateForgingStats` (94-121) folds them, the reference's arithmetic
  * included: a slot not led after a gap counts `slot - lastSlot` missed slots (one more than the
  * slots in the gap), and a `lastSlot` of zero means none yet.
  */
final case class ForgingStats(
    nodeCannotForge: Long = 0,
    nodeIsLeader: Long = 0,
    blocksForged: Long = 0,
    lastSlot: BigInt = 0,
    slotsMissed: BigInt = 0
):
  def step(trace: ForgeTrace): ForgingStats = trace match
    case ForgeTrace.NodeCannotForge(_, _) => copy(nodeCannotForge = nodeCannotForge + 1)
    case ForgeTrace.NodeIsLeader(slot)    => copy(nodeIsLeader = nodeIsLeader + 1, lastSlot = slot)
    case ForgeTrace.ForgedBlock(_, _, _, _, _) => copy(blocksForged = blocksForged + 1)
    case ForgeTrace.NodeNotLeader(slot) =>
      if lastSlot == 0 || lastSlot + 1 == slot then copy(lastSlot = slot)
      else copy(lastSlot = slot, slotsMissed = slotsMissed + (slot - lastSlot))
    case _ => this

/** The forger's counters per epoch, and the reference's `ForgingStats`. The gauges show the
  * `ForgingStats` as they were at the last `TraceStartLeadershipCheck`: the reference writes them
  * only when it traces that (`calcForgeStats`, `ForgingStats.hs` 83-92, a `foldCondTraceM` that
  * emits on that trace alone), so what a slot changes shows at the start of the next one.
  */
final class ForgeStats[F[_]: Sync] private (ref: Ref[F, ForgeStats.State]):
  private def update(epoch: BigInt)(f: ForgeEpochStats => ForgeEpochStats): F[Unit] =
    ref.update(s =>
      s.copy(byEpoch = s.byEpoch.updated(epoch, f(s.byEpoch.getOrElse(epoch, ForgeEpochStats()))))
    )

  /** A trace of the loop in a slot of `epoch`. */
  def record(epoch: BigInt, trace: ForgeTrace): F[Unit] =
    ref.update { s =>
      val live = s.live.step(trace)
      ForgeStats.State(
        s.byEpoch.updated(epoch, s.byEpoch.getOrElse(epoch, ForgeEpochStats()).record(trace)),
        live,
        trace match
          case ForgeTrace.StartLeadershipCheck(_) => live
          case _                                  => s.published
      )
    }

  def failed(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(failed = s.failed + 1))
  def replaced(epoch: BigInt): F[Unit] = update(epoch)(s => s.copy(replaced = s.replaced + 1))

  /** An adopted block became our tip `latencyMs` after the start of its slot. */
  def latency(epoch: BigInt, latencyMs: Long): F[Unit] =
    update(epoch)(s =>
      s.copy(
        latencyCount = s.latencyCount + 1,
        latencyTotalMs = s.latencyTotalMs + latencyMs,
        latencyMaxMs = s.latencyMaxMs.max(latencyMs)
      )
    )

  def epoch(epoch: BigInt): F[ForgeEpochStats] =
    ref.get.map(_.byEpoch.getOrElse(epoch, ForgeEpochStats()))
  def byEpoch: F[SortedMap[BigInt, ForgeEpochStats]] = ref.get.map(_.byEpoch)
  def total: F[ForgeEpochStats] =
    ref.get.map(_.byEpoch.values.foldLeft(ForgeEpochStats())(_.merge(_)))

  /** The reference's `ForgingStats` as its gauges show them now. */
  def forgingStats: F[ForgingStats] = ref.get.map(_.published)

object ForgeStats:
  private final case class State(
      byEpoch: SortedMap[BigInt, ForgeEpochStats],
      live: ForgingStats,
      published: ForgingStats
  )

  def create[F[_]: Sync]: F[ForgeStats[F]] =
    Ref
      .of[F, State](State(SortedMap.empty, ForgingStats(), ForgingStats()))
      .map(new ForgeStats(_))
