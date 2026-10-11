// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.cbor.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

class RewardsSuite extends ScalaCheckSuite:
  private def pool(cost: BigInt, margin: Rational) = PoolParams(
    Bytes(Vector.fill(28)(1)),
    Bytes(Vector.fill(32)(2)),
    pledge = 0,
    cost = cost,
    margin = margin,
    RewardAccount(0, Credential.Key(Bytes(Vector.fill(28)(3)))),
    Set.empty,
    Bytes.empty,
    None
  )

  test("with no pledge influence the optimal reward is R scaled by capped relative stake") {
    val r = BigInt(1000000000)
    assertEquals(Rewards.maxPool(Rational.zero, 100, r, Rational(1, 1000), Rational.zero), r / 1000)
    // Stake above the saturation point 1/nOpt earns no more than a saturated pool.
    assertEquals(Rewards.maxPool(Rational.zero, 100, r, Rational(1, 3), Rational.zero), r / 100)
  }

  property("leader and member shares never exceed the pool reward") {
    forAll(
      Gen.choose(0L, 10000000000L),
      Gen.choose(0L, 1000000L),
      Gen.choose(0, 100),
      Gen.choose(1, 1000)
    ) { (poolR, cost, marginPct, memberShare) =>
      val p = pool(cost, Rational(marginPct, 100))
      val sigma = Rational(1000, 100000)
      val t = Rational(memberShare.min(1000), 100000)
      val leader = Rewards.leaderReward(poolR, p, Rational.zero, sigma)
      val member = Rewards.memberReward(poolR, p, t, sigma)
      assert(leader >= 0 && member >= 0)
      assert(leader + member <= poolR)
    }
  }

  test("a pool earning no more than its cost pays it all to the leader") {
    val p = pool(cost = 500, Rational(1, 10))
    assertEquals(Rewards.leaderReward(400, p, Rational.zero, Rational(1, 10)), BigInt(400))
    assertEquals(Rewards.memberReward(400, p, Rational(1, 20), Rational(1, 10)), BigInt(0))
  }

  test("the pledge counts the owners' stake only where they delegate to the pool") {
    val poolId = Bytes(Vector.fill(28)(1))
    val other = Bytes(Vector.fill(28)(9))
    val owner = Bytes(Vector.fill(28)(5))
    val ownerCredential = Credential.Key(owner)
    val member = Credential.Key(Bytes(Vector.fill(28)(6)))
    val params = pool(cost = 0, Rational.zero).copy(
      pledge = 1000000,
      rewardAccount = RewardAccount(0, ownerCredential),
      owners = Set(owner)
    )
    val otherParams = params.copy(operator = other, owners = Set.empty)
    def rewardsWhenOwnerDelegates(to: Bytes): Map[Credential, BigInt] =
      val go = Snapshot(
        stake = Map(ownerCredential -> BigInt(5000000), member -> BigInt(5000000)),
        delegations = Map(ownerCredential -> to, member -> poolId),
        pools = Map(poolId -> params, other -> otherParams)
      )
      val state = TestLedger
        .state(8, Vector.empty)
        .copy(
          snapshots = Snapshots(Snapshot.empty, Snapshot.empty, go, 0),
          blocksPrevious = Map(poolId -> BigInt(10), other -> BigInt(10)),
          reserves = BigInt(10) * 1000000000000L
        )
      val globals = LedgerGlobals(
        maxSupply = TestLedger.Supply,
        epochLength = 100,
        activeSlotsCoeff = Rational(1, 5),
        network = 0
      )
      Rewards.update(state, globals).rewards
    // Delegating to the pool: the pledge is met, the pool pays its leader and its member.
    val met = rewardsWhenOwnerDelegates(poolId)
    assert(met.get(ownerCredential).exists(_ > 0), met.toString)
    assert(met.get(member).exists(_ > 0), met.toString)
    // Delegating elsewhere: the owners' stake is not the pool's, the pledge is not met, and the
    // pool (leader and members alike) earns nothing, however much the owner holds.
    val unmet = rewardsWhenOwnerDelegates(other)
    assert(!unmet.contains(member), unmet.toString)
  }
