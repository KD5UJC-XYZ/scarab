// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.ledger.TestLedger
import xyz.kd5ujc.scarab.ledger.state.*

/** Native-state ports of the five pending-sensitive legacy regressions. The reference is
  * shelley-1.19.0.1: Rupd.hs:101-152, PulsingReward.hs:89-283 and Rewards.hs:159-186.
  */
class NativeRewardSemanticsSuite extends FunSuite:
  import NativeRewardFixture.*

  private def amount(rs: Map[Credential[Staking], BigInt], c: Credential[Staking]): BigInt =
    rs.getOrElse(c, fail(s"missing expected fixture reward: $c"))

  test("an epoch pays rewards only if a block arrives after the start of rewards") {
    // k=1, f=1/20 gives 4k/f=80; pending work completes at NEWEPOCH, not at start.
    val g = globals(1000, Rational(1, 20))
    val initial = state[Conway](
      10,
      SnapShot.empty,
      Vector.empty,
      Vector.empty,
      reserves = Supply - 1000,
      fee = 1000,
      epoch = 5
    )
    val first = tick(initial, 6000, g)
    assertEquals(first.ru, None)
    assertEquals(tick(first, 6080, g).ru, None)
    val created = tick(first, 6081, g)
    assert(created.ru.exists { case PulsingRewUpdate.Pulsing(_, _) => true; case _ => false })
    val paid = tick(created, 7000, g)
    assertEquals(paid.es.ls.utxo.fees.value, BigInt(0))
    assertEquals(total(paid), Supply)
    val skipped = tick(first, 7000, g)
    assertEquals(skipped.es.ls.utxo.fees.value, BigInt(1000))
    assertEquals(total(skipped), Supply)
  }

  test("the pledge counts the owners' stake only where they delegate to the pool") {
    val p = pool(1); val other = pool(9)
    val owner = cred(5); val member = cred(6)
    val params = stakePool(owner).copy(pledge = coin(1000000), owners = Sorted.set(Vector(key(5))))
    val pools = Sorted.map(Vector(p -> params, other -> params.copy(owners = Sorted.emptySet)))
    def rewardsWhenOwnerDelegates(to: KeyHash[StakePool]) =
      val go = snapshot(
        Vector(owner -> BigInt(5000000), member -> BigInt(5000000)),
        Map(owner -> to, member -> p),
        pools
      )
      rewards(
        state[Babbage](8, go, Vector(p -> BigInt(10), other -> BigInt(10)), Vector(owner, member)),
        globals(100, Rational(1, 5))
      )
    val met = rewardsWhenOwnerDelegates(p)
    assert(amount(met, owner) > 0, met.toString)
    assert(amount(met, member) > 0, met.toString)
    val unmet = rewardsWhenOwnerDelegates(other)
    assert(!unmet.contains(member), unmet.toString)
  }

  test(
    "before Allegra an account earning a leader and a member reward keeps only the leader reward"
  ) {
    // filterRewards selects Set.deleteFindMin under Ord Reward: leader before member, then pool.
    val a = pool(1); val b = pool(2)
    val aMember = cred(4); val shared = cred(5); val both = cred(6); val twin = cred(7)
    val cs = Vector(aMember, shared, both, twin)
    val pools = Sorted.map(
      Vector(
        a -> stakePool(shared, margin = Rational(1, 10)),
        b -> stakePool(both, margin = Rational(1, 10))
      )
    )
    val go = snapshot(
      cs.map(_ -> BigInt(10000000000000L)),
      Map(aMember -> a, shared -> b, both -> b, twin -> b),
      pools
    )
    val blocks = Vector(a -> BigInt(10), b -> BigInt(10))
    val g = globals(100, Rational(1, 5))
    val shelley = rewards(state[Shelley](2, go, blocks, cs), g)
    val allegra = rewards(state[Allegra](3, go, blocks, cs), g)
    val member = amount(allegra, twin)
    assert(member > 0, allegra.toString)
    assertEquals(amount(shelley, shared), amount(allegra, shared) - member)
    assertEquals(amount(shelley, both), amount(allegra, both) - member)
    assertEquals(amount(shelley, twin), member)
    assert(amount(shelley, shared) > 0 && amount(shelley, both) > 0)
    assertEquals(shelley.values.sum, allegra.values.sum - 2 * member)
  }

  private def prefiltered(
      registeredAtStart: Boolean,
      registeredAtApply: Boolean
  ): (Credential[Staking], BigInt, NewEpochState[Alonzo]) =
    val member = cred(7); val leader = cred(3); val p = pool(1)
    val pools = Sorted.map(Vector(p -> stakePool(leader, margin = Rational(1, 10))))
    val go = snapshot(
      Vector(member -> BigInt(10000000000000L), leader -> BigInt(10000000000000L)),
      Map(member -> p, leader -> p),
      pools
    )
    // f=1/5, k=2 gives the original 40-slot reward window.
    val g = globals(1000, Rational(1, 5), security = 2)
    val account = EraLedger.AccountView(compact(1), compact(2000000), Some(p))
    val initial = state[Alonzo](5, SnapShot.empty, Vector.empty, Vector.empty, epoch = 5)
    val withPools = initial.copy(
      bcur = BlocksMade(Sorted.map(Vector(p -> Word64.fromUnsigned(10L)))),
      es = initial.es.copy(
        snapshots = SnapShots(SnapShot.empty, go, SnapShot.empty, coin(0)),
        ls = EraLedger.withPState(initial.es.ls, Empty.pState.copy(stakePools = pools))
      )
    )
    val base = accounts(withPools, Vector(member -> account, leader -> account))
    val first = boundary(base, g)
    val atCutoff = rupd(first, 40, g)
    assertEquals(atCutoff.ru, None)
    val between =
      if registeredAtStart then atCutoff else accounts(atCutoff, Vector(leader -> account))
    val started = rupd(between, 41, g)
    val pending = started.ru.getOrElse(fail("start must retain native pending reward work"))
    val update = ok(Rewards.complete(pending))
    val memberReward = Rewards.aggregate(5, update.rs).find(_._1 == member).map(_._2.value)
    assertEquals(memberReward.isDefined, registeredAtStart, update.toString)
    val later = accounts(
      started,
      if registeredAtApply then Vector(member -> account, leader -> account)
      else Vector(leader -> account)
    )
    (member, memberReward.getOrElse(BigInt(0)), boundary(later, g))

  test("before Babbage the reward prefilter uses the accounts registered after the 4k/f slot") {
    val (member, reward, paid) = prefiltered(true, true)
    assert(reward > 0)
    assertEquals(balance(paid, member), reward + 1)
    val (_, lapsedReward, lapsed) = prefiltered(true, false)
    assertEquals(lapsedReward, reward)
    assertEquals(
      lapsed.es.chainAccount.treasury.value,
      paid.es.chainAccount.treasury.value + reward
    )
    assertEquals(lapsed.es.chainAccount.reserves, paid.es.chainAccount.reserves)
    val (_, _, late) = prefiltered(false, true)
    assertEquals(balance(late, member), BigInt(1))
    assertEquals(late.es.chainAccount.treasury, paid.es.chainAccount.treasury)
    assertEquals(late.es.chainAccount.reserves.value, paid.es.chainAccount.reserves.value + reward)
  }

  /** Mainnet stop 5: at slot 17,020,941 (epoch 237), withdrawal 48f567e5 requested 351635274, while
    * the node had 351050635. The account received only pool ffdf1b8f's epoch 235 leader reward,
    * calculated in epoch 236 and paid on entering 237. These public epoch 235 inputs reproduce both
    * values; only the reserves differ by the Shelley-to-Allegra returned AVVM pot. Koios
    * account_rewards/pool_history/ pool_delegators_history/epoch_params/totals supplied the
    * original regression's inputs.
    */
  private def mainnetLeaderReward(reserves: BigInt): BigInt =
    val ownerHash = ok(
      KeyHash.fromBytes[Staking](hex("e1f6cb9f65d11425be3aefc5ff5ac2f2d33f885ddfde3c258c4b23f5"))
    )
    val owner = Credential.KeyHashObj(ownerHash)
    val p = ok(
      KeyHash.fromBytes[StakePool](hex("ffdf1b8fe56f419eaf95f20df8ffa8e72c799e9571ef9ce1c9a997f3"))
    )
    def credential(value: String) =
      Credential.KeyHashObj(ok(KeyHash.fromBytes[Staking](hex(value))))
    val members = Vector(
      owner -> BigInt("1008447705"),
      credential("437d2a3a1a661e88594e92f62c98d2a1abcad00fe10d9b8720f86d34") -> BigInt(
        "9281947006"
      ),
      credential("6203cb82d7d0eb75721b78bea8a5d27af654ddd1bc1c7ae76910158e") -> BigInt(
        "142357969763"
      ),
      credential("eb526044328462293d1d153d32fcc9fbcc4e9378546a974089a32b9a") -> BigInt("976728906")
    )
    assertEquals(members.map(_._2).sum, BigInt("153625093380"))
    val activeStake = BigInt("20218109006931643")
    val other = pool(0); val filler = cred(17)
    val params = stakePool(owner, 340000000, Rational(1, 100)).copy(
      vrf = ok(
        VRFVerKeyHash.fromBytes(
          hex("8447cb5ef7b2045afad0527c0978fdc67686c6de5d86e9c7fa1d1a2f4b9a8b22")
        )
      ),
      pledge = coin(1000000000),
      owners = Sorted.set(Vector(ownerHash))
    )
    val pools = Sorted.map(
      Vector(
        p -> params,
        other -> params.copy(pledge = coin(0), accountId = filler, owners = Sorted.emptySet)
      )
    )
    val go = snapshot(
      members :+ (filler -> (activeStake - members.map(_._2).sum)),
      members.map((c, _) => c -> p).toMap + (filler -> other),
      pools
    )
    val initial = state[Allegra](
      3,
      go,
      Vector(p -> BigInt(1), other -> BigInt(14391)),
      members.map(_._1) :+ filler,
      reserves,
      BigInt("8115520823"),
      epoch = 236
    )
    // The first Allegra epoch computes rewards with epoch 235's previous Shelley parameters.
    val epoch235 =
      TestLedger.params(2).copy(nOpt = 500, d = Rational(32, 100), minPoolCost = 340000000)
    val gov = initial.es.ls.utxo.gov.copy(prevPParams = epoch235)
    val native = initial.copy(es =
      initial.es.copy(ls = initial.es.ls.copy[Allegra](utxo = initial.es.ls.utxo.copy(gov = gov)))
    )
    amount(rewards(native, globals(432000, Rational(1, 20), network = 1)), owner)

  test("the epoch 235 leader reward matches the reference only with the AVVM balances returned") {
    val nodeReserves = BigInt("12794406996777589")
    val avvmReturned = BigInt("318200635000000")
    assertEquals(mainnetLeaderReward(nodeReserves), BigInt(351050635))
    assertEquals(mainnetLeaderReward(nodeReserves + avvmReturned), BigInt(351635274))
  }
