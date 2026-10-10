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
