// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayEpochBoundary as B
import ConwayStake as S

/** Immutable bounded monetary reward pulser; events/non-myopic state are intentionally absent. */
object ConwayRewardPulser:
  val Profile = "conway-pv9-supplied-reward-pulser-v1"
  enum Phase:
    case Pulsing, Complete
  final class State private[ConwayRewardPulser] (
      private[ConwayRewardPulser] val work: ConwayMemberRewards.Prepared,
      val traversal: Vector[S.Credential],
      val pulseSize: Int,
      private[ConwayRewardPulser] val cursor: ConwayMemberRewards.Progress,
      val completion: Option[ConwayMemberRewards.Distribution],
      val slot: BigInt,
      val revision: BigInt,
      val id: Bytes
  ):
    val processed = cursor.processed
    val members = cursor.members
    val phase = if completion.isDefined then Phase.Complete else Phase.Pulsing
    val frozenId = work.frozen.id
    val allocationId = work.allocation.id
    val securityParameter = work.frozen.rewardGlobals.get.securityParameter.get
    val remaining = traversal.size - processed
    val nativeParityValidated = false
    val eventsImplemented = false
    val nonMyopicUpdated = false
    val published = false
  private val Max = (BigInt(1) << 64) - 1
  private def checked[A](a: => A): Either[String, A] =
    try Right(a)
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hash(s: String): Bytes =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + s).getBytes("UTF-8")))
  private def timing(frozen: B.Frozen, slot: BigInt): B.Timing =
    require(
      slot != null && slot >= 0 && slot <= Max && slot / frozen.epochLength == frozen.epoch,
      "pulser signal outside frozen epoch"
    )
    get(B.rewardTiming(frozen.epoch * frozen.epochLength, frozen.window, slot))

  /** startStep does no member work in the pulse window; late RUPD start forces all remaining work.
    */
  def start(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      expectedAllocationId: Bytes,
      pools: Map[Bytes, ConwayPoolReward.Result]
  ): Either[String, State] = checked {
    val work =
      ConwayMemberRewards.prepare(frozen, expectedFrozenId, allocation, expectedAllocationId, pools)
    val k = frozen.rewardGlobals
      .flatMap(_.securityParameter)
      .getOrElse(throw new IllegalArgumentException("checked frozen security parameter required"))
    val source = work.traversal
    val chunk = ((BigInt(source.size) + 4 * k - 1) / (4 * k)).max(BigInt(1)).toInt
    val initial = new State(
      work,
      source,
      chunk,
      work.initial,
      None,
      frozen.observedSlot,
      0,
      hash(s"start:${frozen.id.hex}:${allocation.id.hex}:$k:$chunk")
    )
    timing(frozen, initial.slot) match
      case B.Timing.TooEarly        => throw new IllegalArgumentException("reward start too early")
      case B.Timing.StartOrPulse    => initial
      case B.Timing.ForceCompletion => progress(initial, initial.slot, true)
  }
  private def progress(s: State, slot: BigInt, force: Boolean): State =
    if s.phase == Phase.Complete then
      new State(
        s.work,
        s.traversal,
        s.pulseSize,
        s.cursor,
        s.completion,
        slot,
        s.revision + 1,
        hash(s"complete-signal:${s.id.hex}:$slot")
      )
    else
      val cursor = s.work.advance(s.cursor, if force then s.remaining else s.pulseSize)
      val end = cursor.processed
      // Native pulseStep checks done BEFORE processing. Last batch alone remains Pulsing.
      val completed = if force || s.remaining == 0 then Some(s.work.finish(cursor)) else None
      new State(
        s.work,
        s.traversal,
        s.pulseSize,
        cursor,
        completed,
        slot,
        s.revision + 1,
        hash(s"progress:${s.id.hex}:$slot:$force:$end:${completed.map(_.id.hex)}")
      )
  private def transition(
      s: State,
      expectedId: Bytes,
      slot: BigInt,
      force: Boolean
  ): Either[String, State] = checked {
    require(s != null && expectedId != null && s.id == expectedId, "pulser state identity mismatch")
    require(slot != null && slot > s.slot && s.revision < Max, "pulser stale/replayed signal")
    val when = timing(s.work.frozen, slot)
    require(
      if force then when == B.Timing.ForceCompletion else when == B.Timing.StartOrPulse,
      "pulser signal timing mismatch"
    )
    progress(s, slot, force)
  }
  def pulse(s: State, expectedId: Bytes, slot: BigInt): Either[String, State] =
    transition(s, expectedId, slot, false)
  def force(s: State, expectedId: Bytes, slot: BigInt): Either[String, State] =
    transition(s, expectedId, slot, true)

  /** NEWEPOCH completion subrule for an exact successor signal. This does not publish an epoch or
    * authenticate ancestry; the enclosing coordinator must own the predecessor and receipt.
    */
  def completeAtBoundary(s: State, expectedId: Bytes, slot: BigInt): Either[String, State] =
    checked {
      require(s != null && s.id == expectedId, "pulser state identity mismatch")
      require(
        slot != null && slot > s.slot && slot <= Max && s.revision < Max &&
          slot / s.work.frozen.epochLength == s.work.frozen.epoch + 1,
        "exact successor completion signal required"
      )
      progress(s, slot, true)
    }

  private[lab] def frozenForRecovery(source: State): B.Frozen = source.work.frozen

  /** Rebuild bounded work from an opaque controller-authorized source. Signal-chain identity cannot
    * be recreated from the cursor alone, so only that source may retain it.
    */
  private[lab] def reownForRecovery(source: State, reownedFrozen: B.Frozen): Either[String, State] =
    checked {
      require(source != null && reownedFrozen != null, "recovery pulser source")
      val original = source.work.frozen
      val a = B.frozenRecoveryView(original); val b = B.frozenRecoveryView(reownedFrozen)
      require(
        original.id == reownedFrozen.id && original.observedSlot == reownedFrozen.observedSlot &&
          original.window == reownedFrozen.window && original.previousParameters == reownedFrozen.previousParameters &&
          original.rewardParameters == reownedFrozen.rewardParameters && original.rewardGlobals == reownedFrozen.rewardGlobals &&
          a.kind == b.kind && a.applicationBinding == b.applicationBinding &&
          a.calculation.id == b.calculation.id && a.calculation.tupleId == b.calculation.tupleId &&
          a.calculation.stake.id == b.calculation.stake.id &&
          a.calculation.stake.revision == b.calculation.stake.revision &&
          a.calculation.stake.snapshots == b.calculation.stake.snapshots &&
          (a.calculation.application eq b.calculation.application) &&
          a.calculation.pots == b.calculation.pots &&
          a.calculation.previousBlocks == b.calculation.previousBlocks &&
          a.calculation.currentBlocks == b.calculation.currentBlocks,
        "recovery pulser frozen content differs"
      )
      val allocation = get(ConwayRewardStart.calculate(reownedFrozen, reownedFrozen.id))
      val pools = reownedFrozen.go.pools.keys.map { p =>
        p -> get(
          ConwayPoolReward.calculate(reownedFrozen, reownedFrozen.id, allocation, allocation.id, p)
        )
      }.toMap
      val work = ConwayMemberRewards.prepare(
        reownedFrozen,
        reownedFrozen.id,
        allocation,
        allocation.id,
        pools
      )
      val k = reownedFrozen.rewardGlobals.flatMap(_.securityParameter).get
      val chunk = ((BigInt(work.traversal.size) + 4 * k - 1) / (4 * k)).max(BigInt(1)).toInt
      require(
        source.traversal == work.traversal && source.pulseSize == chunk &&
          source.allocationId == allocation.id && source.processed >= 0 && source.processed <= work.traversal.size &&
          source.work.pools.map((p, result) => p -> result.id) == pools.map((p, result) =>
            p -> result.id
          ),
        "recovery pulser work differs"
      )
      val cursor = work.advance(work.initial, source.processed)
      require(cursor.members == source.members, "recovery pulser prefix differs")
      val completion = source.completion.map { expected =>
        val actual = work.finish(cursor)
        require(
          actual.id == expected.id && actual.members == expected.members &&
            actual.leaders == expected.leaders && actual.poolTotals == expected.poolTotals &&
            actual.poolIdentities == expected.poolIdentities && actual.completed.id == expected.completed.id &&
            actual.completed.rewards == expected.completed.rewards && actual.completed.deltas == expected.completed.deltas &&
            actual.completed.totalRewards == expected.completed.totalRewards && actual.completed.deltaR2 == expected.completed.deltaR2,
          "recovery pulser completion differs"
        )
        actual
      }
      new State(
        work,
        work.traversal,
        chunk,
        cursor,
        completion,
        source.slot,
        source.revision,
        source.id
      )
    }
