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

  /** Requires exactly all frozen go-pool results, including ranking-only nonproducers. */
  def distribute(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      expectedAllocationId: Bytes,
      pools: Map[Bytes, ConwayPoolReward.Result]
  ): Either[String, Distribution] =
    try
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
      var members = Map.empty[S.Credential, B.Reward]
      var memberTotals = Map.empty[Bytes, BigInt]
      frozen.go.active.foreach { (credential, stake) =>
        val pool = pools.getOrElse(
          stake.pool,
          throw new IllegalArgumentException("active stake missing frozen go pool")
        )
        // PV9 bypasses registration prefiltering. Owners are self-delegated KEY credentials only.
        val isOwner = !credential.script && pool.snapshot.owners.contains(credential.hash)
        pool.production.foreach { production =>
          if !isOwner && production.poolReward > pool.snapshot.cost then
            require(
              pool.snapshot.coin > 0 && stake.coin <= pool.snapshot.coin,
              "member/pool stake denominator bound"
            )
            val margin = pool.snapshot.margin
            // (c / circulation) / (poolStake / circulation) cancels exactly: no extra floor.
            val amount = ((production.poolReward - pool.snapshot.cost) *
              (margin.denominator - margin.numerator) * stake.coin) /
              (margin.denominator * pool.snapshot.coin)
            require(amount >= 0 && amount <= Max, "member reward coin bound")
            if amount > 0 then
              members =
                members.updated(credential, B.Reward(B.RewardKind.Member, stake.pool, amount))
              val total = memberTotals.getOrElse(stake.pool, BigInt(0)) + amount
              require(total <= Max, "member reward aggregate overflow")
              memberTotals = memberTotals.updated(stake.pool, total)
        }
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
      Right(
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
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
