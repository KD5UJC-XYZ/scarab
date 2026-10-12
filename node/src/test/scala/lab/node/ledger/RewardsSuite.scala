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
      val state = TestLedger
        .state(major, Vector.empty)
        .copy(
          snapshots = Snapshots(Snapshot.empty, Snapshot.empty, go, 0),
          blocksPrevious = Map(poolA -> BigInt(10), poolB -> BigInt(10)),
          reserves = BigInt(10) * 1000000000000L,
          prevParams = Some(TestLedger.params(major))
        )
      Rewards.update(state, globals).rewards
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

  private def prefiltered(
      registeredAtStart: Boolean,
      registeredAtApply: Boolean
  ): (LedgerState, LedgerState) =
    val account = Credential.Key(Bytes(Vector.fill(28)(7)))
    val acc = Account(0, 2000000, None, None)
    val update = RewardUpdate(
      deltaTreasury = 10,
      deltaReserves = -110,
      rewards = Map(account -> BigInt(100)),
      deltaFees = 0
    )
    val g = globals.copy(startRewards = 40)
    val base = TestLedger
      .state(5, Vector.empty)
      .copy(
        treasury = 0,
        reserves = 1000,
        rewardDraft = Some(update),
        accounts = Map(account -> acc)
      )
    // `tick` at the cutoff slot itself does not start the calculation, the next slot does
    val start = BigInt(500)
    val atCutoff = Epoch.tick(base, start + 40, start, g)
    assertEquals(atCutoff.rewardRegistered, None)
    val between =
      if registeredAtStart then atCutoff else atCutoff.copy(accounts = Map.empty)
    val started = Epoch.tick(between, start + 41, start, g)
    assertEquals(started.rewardRegistered.map(_.contains(account)), Some(registeredAtStart))
    // the update carries the filtered rewards, the skipped ones went back to the reserves
    assertEquals(started.rewardUpdate.map(_.rewards.contains(account)), Some(registeredAtStart))
    assertEquals(
      started.rewardUpdate.map(_.deltaReserves),
      Some(BigInt(if registeredAtStart then -110 else -10))
    )
    val later =
      if registeredAtApply then started.copy(accounts = Map(account -> acc))
      else started.copy(accounts = Map.empty)
    (started, Epoch.boundary(later, later.epoch + 1, g))

  test("before Babbage the reward prefilter uses the accounts registered after the 4k/f slot") {
    val account = Credential.Key(Bytes(Vector.fill(28)(7)))
    // registered when the calculation starts and still: paid
    val (_, paid) = prefiltered(registeredAtStart = true, registeredAtApply = true)
    assertEquals(paid.accounts(account).balance, BigInt(100))
    assertEquals(paid.treasury, BigInt(10))
    // registered at the start, gone at the boundary: the reward goes to the treasury
    val (_, lapsed) = prefiltered(registeredAtStart = true, registeredAtApply = false)
    assertEquals(lapsed.treasury, BigInt(110))
    assertEquals(lapsed.reserves, BigInt(890))
    // not registered when the calculation started, registered by the boundary: skipped, it stays in
    // the reserves (the account is not paid)
    val (_, late) = prefiltered(registeredAtStart = false, registeredAtApply = true)
    assertEquals(late.accounts(account).balance, BigInt(0))
    assertEquals(late.treasury, BigInt(10))
    assertEquals(late.reserves, BigInt(990))
  }
