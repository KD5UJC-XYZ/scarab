// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayStake as S
import ConwayEpochBoundary as B

/** Whole frozen-go PV9 member/leader distribution, not native admission or a reward pulser. */
object ConwayMemberRewards:
  val Profile = "conway-pv9-frozen-member-distribution-v1"
  private val Max = (BigInt(1) << 64) - 1
  final case class PoolTotals(members: BigInt, leader: BigInt, remainder: BigInt)
  final class Distribution private[ConwayMemberRewards] (
      val frozenId: Bytes,
      val allocationId: Bytes,
      val poolIdentities: Map[Bytes, Bytes],
      val members: Map[S.Credential, B.Reward],
      val leaders: Map[S.Credential, Set[B.Reward]],
      val poolTotals: Map[Bytes, PoolTotals],
      val completed: ConwayRewardCompletion.Completed,
      val id: Bytes
  ):
    val memberDistributionCalculated = true
    val nativeEntitlementValidated = false
    val pulserExecuted = false
    val nonMyopicUpdated = false
    val published = false
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def hash(s: String): Bytes =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + s).getBytes("UTF-8")))

  private[ledger] final class Progress private[ConwayMemberRewards] (
      private[ConwayMemberRewards] val work: Prepared,
      val processed: Int,
      val members: Map[S.Credential, B.Reward]
  )

  /** Checked shared inputs used by the whole-map and bounded pulser paths. */
  private[ledger] final class Prepared private[ConwayMemberRewards] (
      val frozen: B.Frozen,
      val allocation: ConwayRewardStart.Allocation,
      val pools: Map[Bytes, ConwayPoolReward.Result]
  ):
    val traversal =
      frozen.go.active.keys.toVector.sortBy(c => (if c.script then 0 else 1, c.hash.hex))
    val initial = new Progress(this, 0, Map.empty)
    def advance(progress: Progress, count: Int): Progress =
      require(
        progress != null && (progress.work eq this) && count >= 0 && count <= 4096,
        "foreign progress or batch bound"
      )
      val end = (progress.processed + count).min(traversal.size)
      val added = traversal.slice(progress.processed, end).flatMap(c => member(c).map(c -> _)).toMap
      new Progress(this, end, progress.members ++ added)
    private def member(credential: S.Credential): Option[B.Reward] =
      val stake = frozen.go.active(credential)
      val pool = pools(stake.pool)
      val isOwner = !credential.script && pool.snapshot.owners.contains(credential.hash)
      pool.production.flatMap { production =>
        if isOwner || production.poolReward <= pool.snapshot.cost then None
        else
          require(
            pool.snapshot.coin > 0 && stake.coin <= pool.snapshot.coin,
            "member/pool stake denominator bound"
          )
          val margin = pool.snapshot.margin
          val amount = ((production.poolReward - pool.snapshot.cost) *
            (margin.denominator - margin.numerator) * stake.coin) /
            (margin.denominator * pool.snapshot.coin)
          require(amount >= 0 && amount <= Max, "member reward coin bound")
          Option.when(amount > 0)(B.Reward(B.RewardKind.Member, stake.pool, amount))
      }
    def finish(progress: Progress): Distribution =
      require(
        progress != null && (progress.work eq this) && progress.processed == traversal.size,
        "foreign or incomplete member progress"
      )
      val members = progress.members
      var memberTotals = Map.empty[Bytes, BigInt]
      members.foreach { (_, r) =>
        val total = memberTotals.getOrElse(r.pool, BigInt(0)) + r.amount
        require(total <= Max, "member reward aggregate overflow")
        memberTotals = memberTotals.updated(r.pool, total)
      }
      var leaders = Map.empty[S.Credential, Set[B.Reward]]
      var totals = Map.empty[Bytes, PoolTotals]
      pools.foreach { (id, pool) =>
        pool.production.foreach { production =>
          // Native leader collection retains zero rewards and unions pools sharing a recipient.
          val account = pool.snapshot.rewardAccount
          leaders = leaders.updated(
            account,
            leaders.getOrElse(account, Set.empty) + production.leaderReward
          )
          val memberTotal = memberTotals.getOrElse(id, BigInt(0))
          val remainder = production.poolReward - memberTotal - production.leaderReward.amount
          require(remainder >= 0 && remainder <= Max, "distributed rewards exceed pool pot")
          totals =
            totals.updated(id, PoolTotals(memberTotal, production.leaderReward.amount, remainder))
        }
      }
      val completed = get(
        ConwayRewardCompletion.complete(
          allocation.completionInputs,
          allocation.completionInputs.id,
          members,
          leaders
        )
      )
      val identities = pools.map((id, p) => id -> p.id)
      val poolText =
        identities.toVector.sortBy(_._1.hex).map((k, v) => s"${k.hex}:${v.hex}").mkString("|")
      new Distribution(
        frozen.id,
        allocation.id,
        identities,
        members,
        leaders,
        totals,
        completed,
        hash(s"${frozen.id.hex}:${allocation.id.hex}:$poolText:${completed.id.hex}")
      )

  private[ledger] def prepare(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      expectedAllocationId: Bytes,
      pools: Map[Bytes, ConwayPoolReward.Result]
  ): Prepared =
    require(
      frozen != null && allocation != null && expectedFrozenId != null &&
        expectedAllocationId != null && frozen.id == expectedFrozenId &&
        allocation.id == expectedAllocationId && allocation.frozenId == frozen.id,
      "member frozen/allocation identity mismatch"
    )
    require(
      frozen.rewardParameters.flatMap(_.pool).nonEmpty,
      "checked frozen pool parameters required"
    )
    require(
      pools != null && pools.size <= 4096 && pools.keySet == frozen.go.pools.keySet,
      "complete frozen go-pool result domain required"
    )
    pools.foreach { (id, p) =>
      require(
        p != null && p.poolId == id && p.frozenId == frozen.id &&
          p.allocationId == allocation.id && p.snapshot == frozen.go.pools(id),
        "pool result identity mismatch"
      )
    }
    new Prepared(frozen, allocation, pools)

  /** Requires exactly all frozen go-pool results, including ranking-only nonproducers. */
  def distribute(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      expectedAllocationId: Bytes,
      pools: Map[Bytes, ConwayPoolReward.Result]
  ): Either[String, Distribution] =
    try
      val work = prepare(frozen, expectedFrozenId, allocation, expectedAllocationId, pools)
      Right(work.finish(work.advance(work.initial, work.traversal.size)))
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
