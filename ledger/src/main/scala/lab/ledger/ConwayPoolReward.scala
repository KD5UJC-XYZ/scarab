// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.Blake2b
import lab.cbor.Bytes
import scala.util.control.NonFatal
import ConwayStake as S
import ConwayEpochBoundary as B

/** Pure pool entitlement from supplied frozen go data; no member distribution or publication. */
object ConwayPoolReward:
  val Profile = "conway-pv9-frozen-pool-reward-v1"
  private val Max = (BigInt(1) << 64) - 1
  final class Fraction private[ConwayPoolReward] (val numerator: BigInt, val denominator: BigInt)
  final class Production private[ConwayPoolReward] (
      val blocks: BigInt,
      val maximumReward: BigInt,
      val apparentPerformance: Fraction,
      val poolReward: BigInt,
      val leaderReward: B.Reward
  )
  final class Result private[ConwayPoolReward] (
      val frozenId: Bytes,
      val allocationId: Bytes,
      val poolId: Bytes,
      val snapshot: S.PoolSnapshot,
      val circulation: BigInt,
      val activeStake: BigInt,
      val relativeStake: Fraction,
      val activeShare: Fraction,
      val ownerShare: Fraction,
      val relativePledge: Fraction,
      val pledgeEligible: Boolean,
      val production: Option[Production],
      val id: Bytes
  ):
    val nativeSeedAdmitted = false
    val memberDistributionCalculated = false
    val pulserExecuted = false
    val published = false

  private final case class Q(n: BigInt, d: BigInt):
    def +(b: Q): Q = q(n * b.d + b.n * d, d * b.d)
    def -(b: Q): Q = q(n * b.d - b.n * d, d * b.d)
    def *(b: Q): Q = q(n * b.n, d * b.d)
    def /(b: Q): Q = q(n * b.d, d * b.n)
    def min(b: Q): Q = if n * b.d <= b.n * d then this else b
    def floor: BigInt =
      require(n >= 0, "negative pool reward intermediate")
      n / d
    def view: Fraction = new Fraction(n, d)
  private def q(n: BigInt, d: BigInt = 1): Q =
    require(d > 0, "positive pool reward denominator required")
    val g = n.gcd(d); Q(n / g, d / g)
  private def bounded(n: BigInt): Boolean = n >= 0 && n <= Max
  private def hash(s: String): Bytes =
    Blake2b.hash256.hash(Bytes.fromArray((Profile + "\n" + s).getBytes("UTF-8")))

  /** Pool parameters, circulation, go data and block counts all come from the bound Frozen. */
  def calculate(
      frozen: B.Frozen,
      expectedFrozenId: Bytes,
      allocation: ConwayRewardStart.Allocation,
      expectedAllocationId: Bytes,
      poolId: Bytes
  ): Either[String, Result] =
    try
      require(
        frozen != null && allocation != null && expectedFrozenId != null &&
          expectedAllocationId != null && frozen.id == expectedFrozenId &&
          allocation.id == expectedAllocationId && allocation.frozenId == frozen.id,
        "frozen pool allocation identity mismatch"
      )
      require(poolId != null && poolId.value != null && poolId.size == 28, "pool identity width")
      val parameters = frozen.rewardParameters
        .flatMap(_.pool)
        .getOrElse(throw new IllegalArgumentException("checked frozen a0/nOpt parameters required"))
      val pool = frozen.go.pools.getOrElse(
        poolId,
        throw new IllegalArgumentException("pool absent from frozen go snapshot")
      )
      val circulation = frozen.maxSupply - frozen.reserves
      require(bounded(circulation) && circulation > 0, "positive bounded circulation required")
      val active = frozen.go.total // Snapshot represents empty active stake with denominator one.
      require(
        active > 0 && bounded(active) && frozen.go.active.values.map(_.coin).sum <= circulation,
        "frozen active stake exceeds circulation"
      )
      require(pool.ownerCoin <= pool.coin && pool.coin <= circulation, "pool/owner stake bound")
      val sigma = q(pool.coin, circulation)
      val sigmaA = q(pool.coin, active)
      val owner = q(pool.ownerCoin, circulation)
      val pledge = q(pool.pledge, circulation)
      val eligible = pool.pledge <= pool.ownerCoin
      val produced = frozen.previousBlocks.get(poolId).map { blocks =>
        val a0 = q(parameters.a0.numerator, parameters.a0.denominator)
        val z0 = q(1, parameters.nOpt)
        val s = sigma.min(z0); val p = pledge.min(z0)
        val maximum =
          if !eligible then BigInt(0)
          else
            ((q(allocation.rewardPot) / (q(1) + a0)) *
              (s + p * a0 * ((s - p * ((z0 - s) / z0)) / z0))).floor
        val beta = q(blocks, allocation.blocksMade.max(BigInt(1)))
        // Native apparent performance is beta/sigmaA, NOT capped at one.
        val performance = if pool.coin == 0 then q(0) else beta / sigmaA
        val reward = (performance * q(maximum)).floor
        require(bounded(maximum) && bounded(reward), "pool reward coin overflow")
        val leader =
          if reward <= pool.cost then reward
          else
            val m = q(pool.margin.numerator, pool.margin.denominator)
            pool.cost + (q(reward - pool.cost) * (m + (q(1) - m) * owner / sigma)).floor
        require(bounded(leader) && leader <= reward, "leader reward bound")
        new Production(
          blocks,
          maximum,
          performance.view,
          reward,
          B.Reward(B.RewardKind.Leader, poolId, leader)
        )
      }
      Right(
        new Result(
          frozen.id,
          allocation.id,
          poolId,
          pool,
          circulation,
          active,
          sigma.view,
          sigmaA.view,
          owner.view,
          pledge.view,
          eligible,
          produced,
          hash(s"${frozen.id.hex}:${allocation.id.hex}:${poolId.hex}")
        )
      )
    catch case NonFatal(e) => Left(Option(e.getMessage).getOrElse(e.getClass.getName))
