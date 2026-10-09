// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayStake as S
import ConwayEpochBoundary as B
import ConwayRewardStart as R
import ConwayPoolReward as P

class ConwayPoolRewardSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def bytes(i: Int, size: Int = 32) = Bytes(Vector.fill(size)(i.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private val pool = bytes(1, 28)
  private val other = bytes(2, 28)
  private val owner = S.Credential(false, bytes(3, 28))
  private val member = S.Credential(true, owner.hash)
  private val second = S.Credential(false, bytes(4, 28))
  private val max = (BigInt(1) << 64) - 1
  private def parameters(a: BigInt = 1, d: BigInt = 2, k: BigInt = 2): Bytes = get(
    Cbor.encode(
      V.Arr(
        Vector(n(V.Text(R.PoolParameterFormat))) ++ Vector[BigInt](9, 0, 0, 1, 0, 1, a, d, k).map(
          x => n(V.UInt(x))
        )
      )
    )
  )
  private class Fixture(
      stake: BigInt = 100,
      ownerStake: BigInt = 30,
      otherStake: BigInt = 300,
      pledge: BigInt = 20,
      cost: BigInt = 7,
      margin: S.Ratio = S.Ratio(1, 3),
      blocks: Map[Bytes, BigInt] = Map(pool -> BigInt(3), other -> BigInt(1)),
      a: BigInt = 1,
      d: BigInt = 2,
      k: BigInt = 2,
      reserves: BigInt = 1000,
      supply: BigInt = 2000,
      fees: BigInt = 1000,
      scoped: Boolean = true
  ):
    val bo = B.owner(); val so = S.owner()
    val accounts = Map(
      owner -> S.Account(0, 0, Some(pool)),
      member -> S.Account(0, 0, Some(pool)),
      second -> S.Account(0, 0, Some(other))
    )
    val pools = Map(
      pool -> S.Pool(bytes(5), pledge, cost, margin, owner, Set(owner.hash), Set(owner, member), 0),
      other -> S.Pool(bytes(6), 0, 0, S.Ratio(0, 1), second, Set(second.hash), Set(second), 0)
    )
    val context = get(S.context(bytes(7), 500, accounts, pools))
    val go = get(
      S.fromActive(
        context,
        Map(
          owner -> S.Active(ownerStake, pool),
          member -> S.Active(stake - ownerStake, pool),
          second -> S.Active(otherStake, other)
        ).filter(_._2.coin > 0)
      )
    )
    val empty = get(Cbor.encode(V.Map(Vector.empty)))
    val env = get(
      ClusterTransition.environment(bytes(7), bytes(7), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(ClusterTransition.checkpoint(env, empty, 50, 110, bytes(7)))
    val state = get(S.seed(so, context, ledger, bytes(7), Map.empty, S.Snapshots(go, go, go, fees)))
    val start = get(
      B.context(
        bo,
        so,
        bytes(8),
        state,
        B.Pots(0, reserves, 0, supply),
        blocks,
        Map(pool -> BigInt(999))
      )
    )
    val params =
      if scoped then get(R.decodePoolParameters(parameters(a, d, k)))
      else
        get(
          R.decodeParameters(
            get(
              Cbor.encode(
                V.Arr(
                  Vector(n(V.Text(R.ParameterFormat))) ++
                    Vector[BigInt](9, 0, 0, 1, 0, 1).map(x => n(V.UInt(x)))
                )
              )
            )
          )
        )
    val globals = get(
      R.decodeGlobals(
        get(
          Cbor.encode(
            V.Arr(
              Vector(n(V.Text(R.GlobalFormat))) ++
                Vector[BigInt](500, 1, 20, supply).map(x => n(V.UInt(x)))
            )
          )
        )
      )
    )
    val frozen = get(B.freezeForAllocation(bo, start, 110, 100, params, globals))
    val allocation = get(R.calculate(frozen, frozen.id))
    def result(id: Bytes = pool) = P.calculate(frozen, frozen.id, allocation, allocation.id, id)
  private def ratio(r: P.Fraction, n: BigInt, d: BigInt): Unit =
    assertEquals((r.numerator, r.denominator), (n, d))

  test(
    "frozen circulation and active shares differ; apparent performance exceeds one with staged floors"
  ) {
    val f = new Fixture; val r = get(f.result()); val p = r.production.get
    assertEquals(r.circulation, BigInt(1000)); assertEquals(r.activeStake, BigInt(400))
    ratio(r.relativeStake, 1, 10); ratio(r.activeShare, 1, 4); ratio(r.ownerShare, 3, 100)
    ratio(r.relativePledge, 1, 50); assert(r.pledgeEligible)
    assertEquals(p.maximumReward, BigInt(67)); ratio(p.apparentPerformance, 3, 1)
    assertEquals(p.poolReward, BigInt(201)); assertEquals(p.leaderReward.amount, BigInt(110))
    assertEquals(p.leaderReward, B.Reward(B.RewardKind.Leader, pool, 110))
    assertEquals(r.snapshot.owners, Set(owner.hash)) // Same-hash script stake is not owner stake.
    assert(
      !r.nativeSeedAdmitted && !r.memberDistributionCalculated && !r.pulserExecuted && !r.published
    )
  }
  test(
    "pledge equality qualifies, unmet pledge zeros rewards, and saturation caps stake and pledge"
  ) {
    assert(get(new Fixture(pledge = 30).result()).pledgeEligible)
    val unmet = get(new Fixture(pledge = 31).result())
    assert(!unmet.pledgeEligible); assertEquals(unmet.production.get.maximumReward, BigInt(0))
    assertEquals(unmet.production.get.leaderReward.amount, BigInt(0))
    val sat = get(
      new Fixture(
        stake = 800,
        ownerStake = 600,
        otherStake = 0,
        pledge = 600,
        cost = 2,
        margin = S.Ratio(0, 1),
        blocks = Map(pool -> BigInt(1)),
        a = 2,
        d = 1
      ).result()
    ).production.get
    assertEquals(sat.maximumReward, BigInt(500)); assertEquals(sat.poolReward, BigInt(500))
    assertEquals(sat.leaderReward.amount, BigInt(375))
  }
  test("missing block entry returns ranking only; explicit zero retains produced-pool info") {
    val missing = get(new Fixture(blocks = Map.empty).result())
    assertEquals(missing.production, None); ratio(missing.relativeStake, 1, 10)
    val zeroResult = get(new Fixture(blocks = Map(pool -> BigInt(0))).result())
    assertNotEquals(missing.frozenId, zeroResult.frozenId)
    val zero = zeroResult.production.get
    assertEquals(zero.blocks, BigInt(0)); assertEquals(zero.maximumReward, BigInt(67))
    ratio(zero.apparentPerformance, 0, 1); assertEquals(zero.poolReward, BigInt(0))
    assertEquals(zero.leaderReward.amount, BigInt(0))
  }
  test("zero pool stake, empty active sentinel, zero pot and cost/margin endpoints") {
    val empty = get(
      new Fixture(
        stake = 0,
        ownerStake = 0,
        otherStake = 0,
        pledge = 0,
        blocks = Map(pool -> BigInt(1))
      ).result()
    )
    assertEquals(empty.activeStake, BigInt(1)); ratio(empty.activeShare, 0, 1)
    assertEquals(empty.production.get.maximumReward, BigInt(0))
    assertEquals(empty.production.get.poolReward, BigInt(0))
    assertEquals(get(new Fixture(fees = 0).result()).production.get.leaderReward.amount, BigInt(0))
    assertEquals(
      get(new Fixture(cost = 201).result()).production.get.leaderReward.amount,
      BigInt(201)
    )
    assertEquals(
      get(new Fixture(cost = 202).result()).production.get.leaderReward.amount,
      BigInt(201)
    )
    assertEquals(
      get(new Fixture(margin = S.Ratio(1, 1)).result()).production.get.leaderReward.amount,
      BigInt(201)
    )
    assertEquals(
      get(new Fixture(margin = S.Ratio(0, 1)).result()).production.get.leaderReward.amount,
      BigInt(65)
    )
  }
  test("pool parameter projection rejects invalid denominator, nonreduced a0 and Word16 nOpt") {
    Vector(parameters(1, 0), parameters(2, 4), parameters(k = 0), parameters(k = 65536)).foreach {
      raw =>
        assert(R.decodePoolParameters(raw).isLeft)
    }
    assert(R.decodePoolParameters(parameters(2, 1)).isRight) // a0 is not a unit interval.
    assert(R.decodePoolParameters(parameters(max, 1, 65535)).isRight)
    assert(R.decodePoolParameters(parameters(0, 1, 1)).isRight)
    val raw = parameters()
    assert(R.decodePoolParameters(Bytes(Vector(0x98.toByte, 10.toByte) ++ raw.value.tail)).isLeft)
    assert(R.decodePoolParameters(Bytes(raw.value ++ Vector(0.toByte))).isLeft)
    assert(R.decodePoolParameters(Bytes(Vector.fill(1025)(0.toByte))).isLeft)
    assert(R.decodeParameters(raw).isLeft)
  }
  test(
    "stale allocation, missing parameters/pool, zero circulation and excessive go stake reject"
  ) {
    val f = new Fixture; val otherF = new Fixture(a = 2, d = 1)
    assert(P.calculate(f.frozen, bytes(99), f.allocation, f.allocation.id, pool).isLeft)
    assert(P.calculate(f.frozen, f.frozen.id, f.allocation, bytes(99), pool).isLeft)
    assert(P.calculate(f.frozen, f.frozen.id, otherF.allocation, otherF.allocation.id, pool).isLeft)
    assert(new Fixture(scoped = false).result().isLeft)
    assert(f.result(bytes(9, 28)).isLeft); assert(f.result(bytes(9)).isLeft)
    assert(new Fixture(stake = 0, ownerStake = 0, otherStake = 0, supply = 1000).result().isLeft)
    assert(new Fixture(stake = 900, ownerStake = 30, otherStake = 300).result().isLeft)
    assertNotEquals(f.frozen.id, otherF.frozen.id)
    assertNotEquals(get(f.result()).id, get(otherF.result()).id)
  }
  test(
    "large exact products and minimum saturation denominator stay bounded without numeric narrowing"
  ) {
    val r = get(
      new Fixture(
        stake = max - 1,
        ownerStake = max - 1,
        otherStake = 0,
        pledge = max - 1,
        cost = 0,
        margin = S.Ratio(0, 1),
        blocks = Map(pool -> max),
        a = max,
        d = 1,
        k = 1,
        reserves = 0,
        supply = max,
        fees = max
      ).result()
    ).production.get
    // sigma=p=(Max-1)/Max; maxPool floor remains exact despite products far beyond uint64.
    assertEquals(r.maximumReward, BigInt("18446744073709551612"))
    assertEquals(r.poolReward, r.maximumReward); assertEquals(r.leaderReward.amount, r.poolReward)
    val fine = get(new Fixture(a = max, d = max - 1, k = 65535).result()).production.get
    assert(fine.maximumReward >= 0 && fine.leaderReward.amount <= fine.poolReward)
  }
  test("leader output composes with the bound completion inputs without member distribution") {
    val f = new Fixture; val r = get(f.result()); val inputs = f.allocation.completionInputs
    val completed = get(
      ConwayRewardCompletion.complete(
        inputs,
        inputs.id,
        Map.empty,
        Map(r.snapshot.rewardAccount -> Set(r.production.get.leaderReward))
      )
    )
    assertEquals(completed.deltas, B.Deltas(0, 890, -1000))
  }

  test("bounded a0-zero cases match the independently simplified unsaturated formula") {
    for b <- 1 to 5; rest <- 0 to 4 do
      val r = get(
        new Fixture(
          ownerStake = 0,
          pledge = 0,
          cost = 0,
          margin = S.Ratio(0, 1),
          a = 0,
          d = 1,
          k = 1,
          blocks = Map(pool -> BigInt(b), other -> BigInt(rest))
        ).result()
      ).production.get
      assertEquals(r.maximumReward, BigInt(100))
      assertEquals(r.poolReward, BigInt(400 * b) / (b + rest))
      assertEquals(r.leaderReward.amount, BigInt(0))
  }
