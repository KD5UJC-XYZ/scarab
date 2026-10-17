// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.legacy as flat
import xyz.kd5ujc.scarab.legacy.*


import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*

/** The reward calculation of the era-indexed rules: the formulas (`maxPool`, the operator and member
  * rewards) and the reward update of a state (RUPD).
  */
class RewardsSuite extends ScalaCheckSuite:
  private val rs = RuleSet
  private def maxPool(a0: Rational, nOpt: BigInt, r: BigInt, sigma: Rational, pledge: Rational) =
    xyz.kd5ujc.scarab.ledger.rules.Rewards.maxPool(a0, nOpt, r, sigma, pledge)

  private def leaderReward(poolR: BigInt, p: PoolParams, s: Rational, sigma: Rational) =
    xyz.kd5ujc.scarab.ledger.rules.Rewards.operatorReward(poolR, p.cost, p.margin, s, sigma)

  private def memberReward(poolR: BigInt, p: PoolParams, t: Rational, sigma: Rational) =
    xyz.kd5ujc.scarab.ledger.rules.Rewards.memberReward(poolR, p.cost, p.margin, t, sigma)

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
    assertEquals(maxPool(Rational.zero, 100, r, Rational(1, 1000), Rational.zero), r / 1000)
    // Stake above the saturation point 1/nOpt earns no more than a saturated pool.
    assertEquals(maxPool(Rational.zero, 100, r, Rational(1, 3), Rational.zero), r / 100)
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
      val leader = leaderReward(poolR, p, Rational.zero, sigma)
      val member = memberReward(poolR, p, t, sigma)
      assert(leader >= 0 && member >= 0)
      assert(leader + member <= poolR)
    }
  }

  test("a pool earning no more than its cost pays it all to the leader") {
    val p = pool(cost = 500, Rational(1, 10))
    assertEquals(leaderReward(400, p, Rational.zero, Rational(1, 10)), BigInt(400))
    assertEquals(memberReward(400, p, Rational(1, 20), Rational(1, 10)), BigInt(0))
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
      val state = FlatTestLedger.state(8, Vector.empty)
        .copy(
          // the new rules pay only the credentials that are registered when the update is made
          accounts = Map(
            ownerCredential -> Account(0, 0, None, None),
            member -> Account(0, 0, None, None)
          ),
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
      rs.rewards(state, globals)
    // Delegating to the pool: the pledge is met, the pool pays its leader and its member.
    val met = rewardsWhenOwnerDelegates(poolId)
    assert(met.get(ownerCredential).exists(_ > 0), met.toString)
    assert(met.get(member).exists(_ > 0), met.toString)
    // Delegating elsewhere: the owners' stake is not the pool's, the pledge is not met, and the
    // pool (leader and members alike) earns nothing, however much the owner holds.
    val unmet = rewardsWhenOwnerDelegates(other)
    assert(!unmet.contains(member), unmet.toString)
  }

  private val globals = LedgerGlobals(
    maxSupply = TestLedger.Supply,
    epochLength = 100,
    activeSlotsCoeff = Rational(1, 5),
    network = 0
  )

  test(
    "before Allegra an account earning a leader and a member reward keeps only the leader reward"
  ) {
    // Cross-checked against `filterRewards`: `Set.deleteFindMin` under `Ord Reward`, where a leader
    // reward sorts before a member reward and then pools compare by id.
    def cred(n: Int) = Credential.Key(Bytes(Vector.fill(28)(n.toByte)))
    val poolA = Bytes(Vector.fill(28)(1))
    val poolB = Bytes(Vector.fill(28)(2))
    // `shared` leads pool A and is a member of pool B; `both` leads pool B and is a member of it;
    // `twin` is a plain member of B with the stake of `shared` and `both`
    val (aMember, shared, both, twin) = (cred(4), cred(5), cred(6), cred(7))
    val paramsA = pool(cost = 0, Rational(1, 10))
      .copy(operator = poolA, rewardAccount = RewardAccount(0, shared))
    val paramsB = pool(cost = 0, Rational(1, 10))
      .copy(operator = poolB, rewardAccount = RewardAccount(0, both))
    val stake = BigInt(10000000000000L)
    val go = Snapshot(
      stake = Map(aMember -> stake, shared -> stake, both -> stake, twin -> stake),
      delegations = Map(aMember -> poolA, shared -> poolB, both -> poolB, twin -> poolB),
      pools = Map(poolA -> paramsA, poolB -> paramsB)
    )
    def rewards(major: Int) =
      val state = FlatTestLedger.state(major, Vector.empty)
        .copy(
          // the new rules pay only the credentials that are registered when the update is made
          accounts = Seq(aMember, shared, both, twin).map(_ -> Account(0, 0, None, None)).toMap,
          snapshots = Snapshots(Snapshot.empty, Snapshot.empty, go, 0),
          blocksPrevious = Map(poolA -> BigInt(10), poolB -> BigInt(10)),
          reserves = BigInt(10) * 1000000000000L,
          prevParams = Some(TestLedger.params(major))
        )
      rs.rewards(state, globals)
    val shelley = rewards(2)
    val allegra = rewards(3)
    val member = allegra(twin)
    assert(member > 0, allegra.toString)
    // Allegra pays leader and member rewards; Shelley only the leader reward
    assertEquals(shelley(shared), allegra(shared) - member)
    assertEquals(shelley(both), allegra(both) - member)
    assertEquals(shelley(twin), member)
    assert(shelley(shared) > 0 && shelley(both) > 0)
    assertEquals(shelley.values.sum, allegra.values.sum - 2 * member)
  }

  /** A member of a pool, registered or not when the reward calculation starts and when the update
    * is applied: the account of the member, the state after the update is applied, the one with the
    * update made and the amount it holds for the member.
    */
  private def prefiltered(
      registeredAtStart: Boolean,
      registeredAtApply: Boolean
  ): (Credential, BigInt, LedgerState) =
    val member = Credential.Key(Bytes(Vector.fill(28)(7)))
    val leader = Credential.Key(Bytes(Vector.fill(28)(3)))
    val poolId = Bytes(Vector.fill(28)(1))
    val params = pool(cost = 0, Rational(1, 10)).copy(operator = poolId)
    val stake = BigInt(10000000000000L)
    val go = Snapshot(
      stake = Map(member -> stake, leader -> stake),
      delegations = Map(member -> poolId, leader -> poolId),
      pools = Map(poolId -> params)
    )
    // each holds a lovelace, so that the snapshot taken at the boundary has both as delegators
    val acc = Account(1, 2000000, Some(poolId), None)
    val g = globals.copy(epochLength = 1000, startRewards = 40)
    val base = FlatTestLedger.state(5, Vector.empty)
      .copy(
        snapshots = Snapshots(Snapshot.empty, go, Snapshot.empty, 0),
        blocksCurrent = Map(poolId -> BigInt(10)),
        reserves = BigInt(10) * 1000000000000L,
        pools = Map(poolId -> PoolState(params, 500000000)),
        accounts = Map(member -> acc, leader -> acc)
      )
    // the boundary begins the epoch whose update this is
    val first = rs.boundary(base, g)
    val start = first.epoch * 1000
    // `tick` at the cutoff slot itself does not start the calculation, the next slot does
    val atCutoff = rs.tick(first, start + 40, start, g)
    assertEquals(atCutoff.rewardUpdate, None)
    val between =
      if registeredAtStart then atCutoff else atCutoff.copy(accounts = atCutoff.accounts - member)
    val started = rs.tick(between, start + 41, start, g)
    val amount = started.rewardUpdate.flatMap(_.rewards.get(member))
    // the update carries the member's reward only if the member was registered when it was made
    assertEquals(amount.isDefined, registeredAtStart, started.rewardUpdate.toString)
    val later =
      if registeredAtApply then started.copy(accounts = started.accounts.updated(member, acc))
      else started.copy(accounts = started.accounts - member)
    (member, amount.getOrElse(BigInt(0)), rs.boundary(later, g))

  test("before Babbage the reward prefilter uses the accounts registered after the 4k/f slot") {
    // registered when the calculation starts and still: paid
    val (member, amount, paid) = prefiltered(registeredAtStart = true, registeredAtApply = true)
    assert(amount > 0)
    assertEquals(paid.accounts(member).balance, amount + 1)
    // registered at the start, gone at the boundary: the reward goes to the treasury
    val (_, lapsedAmount, lapsed) =
      prefiltered(registeredAtStart = true, registeredAtApply = false)
    assertEquals(lapsedAmount, amount)
    assertEquals(lapsed.treasury, paid.treasury + amount)
    assertEquals(lapsed.reserves, paid.reserves)
    // not registered when the calculation started, registered by the boundary: skipped, it stays in
    // the reserves (the account is not paid)
    val (_, _, late) = prefiltered(registeredAtStart = false, registeredAtApply = true)
    assertEquals(late.accounts(member).balance, BigInt(1))
    assertEquals(late.treasury, paid.treasury)
    assertEquals(late.reserves, paid.reserves + amount)
  }

