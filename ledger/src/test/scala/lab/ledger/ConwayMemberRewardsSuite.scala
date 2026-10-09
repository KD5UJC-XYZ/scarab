// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayStake as S
import ConwayEpochBoundary as B
import ConwayRewardStart as R
import ConwayMemberRewards as M

class ConwayMemberRewardsSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def bytes(i: Int, size: Int = 32) = Bytes(Vector.fill(size)(i.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private val pool = bytes(1, 28); private val other = bytes(2, 28)
  private val owner = S.Credential(false, bytes(3, 28))
  private val script = S.Credential(true, owner.hash)
  private val member = S.Credential(false, bytes(4, 28))
  private val ownerB = S.Credential(false, bytes(5, 28))
  private val memberB = S.Credential(false, bytes(6, 28))
  private val recipient = S.Credential(false, bytes(7, 28))
  private class Fixture(
      fees: BigInt = 1000,
      margin: S.Ratio = S.Ratio(1, 3),
      blocks: Map[Bytes, BigInt] = Map(pool -> BigInt(1), other -> BigInt(1)),
      leaderAccount: S.Credential = recipient,
      omitScriptAtFreeze: Boolean = false,
      empty: Boolean = false
  ):
    val bo = B.owner(); val so = S.owner()
    val accounts = Map(
      owner -> S.Account(0, 0, Some(pool)),
      script -> S.Account(0, 0, Some(pool)),
      member -> S.Account(0, 0, Some(pool)),
      ownerB -> S.Account(0, 0, Some(other)),
      memberB -> S.Account(0, 0, Some(other))
    )
    val pools = Map(
      pool -> S.Pool(
        bytes(8),
        0,
        7,
        margin,
        leaderAccount,
        Set(owner.hash, memberB.hash),
        Set(owner, script, member),
        0
      ),
      other -> S.Pool(
        bytes(9),
        0,
        0,
        S.Ratio(0, 1),
        leaderAccount,
        Set(ownerB.hash),
        Set(ownerB, memberB),
        0
      )
    )
    val context = get(S.context(bytes(10), 500, accounts, pools))
    val go =
      if empty then S.emptySnapshot
      else
        get(
          S.fromActive(
            context,
            Map(
              owner -> S.Active(20, pool),
              script -> S.Active(40, pool),
              member -> S.Active(40, pool),
              ownerB -> S.Active(25, other),
              memberB -> S.Active(75, other)
            )
          )
        )
    val raw = get(Cbor.encode(V.Map(Vector.empty)))
    val env = get(
      ClusterTransition.environment(bytes(10), bytes(10), 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(ClusterTransition.checkpoint(env, raw, 50, 110, bytes(10)))
    val state = get(
      S.seed(so, context, ledger, bytes(10), Map.empty, S.Snapshots(go, go, go, fees))
    )
    val pots = B.Pots(0, 1000, fees, 2000)
    def application(as: Map[S.Credential, S.Account]): S.Context =
      get(
        S.context(
          bytes(11),
          500,
          as,
          pools.map((id, p) =>
            id -> p.copy(delegators = as.collect {
              case (c, a) if a.delegation.contains(id) => c
            }.toSet)
          )
        )
      )
    val frozenAccounts = if omitScriptAtFreeze then accounts - script else accounts
    val start = get(
      B.contextAtApplication(
        bo,
        so,
        bytes(12),
        state,
        application(frozenAccounts),
        pots,
        blocks,
        Map.empty
      )
    )
    val params = get(
      R.decodePoolParameters(
        get(
          Cbor.encode(
            V.Arr(
              Vector(n(V.Text(R.PoolParameterFormat))) ++
                Vector[BigInt](9, 0, 0, 1, 0, 1, 0, 1, 1).map(x => n(V.UInt(x)))
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
                Vector[BigInt](500, 1, 20, 2000).map(x => n(V.UInt(x)))
            )
          )
        )
      )
    )
    val frozen = get(B.freezeForAllocation(bo, start, 110, 100, params, globals))
    val allocation = get(R.calculate(frozen, frozen.id))
    val results = go.pools.keys
      .map(id =>
        id -> get(ConwayPoolReward.calculate(frozen, frozen.id, allocation, allocation.id, id))
      )
      .toMap
    def distribution(rs: Map[Bytes, ConwayPoolReward.Result] = results) =
      M.distribute(frozen, frozen.id, allocation, allocation.id, rs)

  test("whole-go distribution excludes self-delegated key owners and preserves same-hash scripts") {
    val f = new Fixture; val d = get(f.distribution())
    assertEquals(
      d.members,
      Map(
        script -> B.Reward(B.RewardKind.Member, pool, 24),
        member -> B.Reward(B.RewardKind.Member, pool, 24),
        memberB -> B.Reward(B.RewardKind.Member, other, 75)
      )
    )
    assert(!d.members.contains(owner) && !d.members.contains(ownerB))
    assertEquals(f.go.pools(pool).owners, Set(owner.hash)) // memberB owns A but delegates to B.
    assertEquals(
      d.leaders(recipient),
      Set(B.Reward(B.RewardKind.Leader, pool, 50), B.Reward(B.RewardKind.Leader, other, 25))
    )
    assertEquals(d.poolTotals(pool), M.PoolTotals(48, 50, 2))
    assertEquals(d.poolTotals(other), M.PoolTotals(75, 25, 0))
    assertEquals(d.completed.totalRewards, BigInt(198));
    assertEquals(d.completed.deltaR2, BigInt(802))
    assertEquals(d.completed.deltas, B.Deltas(0, 802, -1000))
    assert(
      d.memberDistributionCalculated && !d.nativeEntitlementValidated && !d.pulserExecuted &&
        !d.nonMyopicUpdated && !d.published
    )
  }
  test(
    "each tiny member reward is floored independently and zeros are omitted, unlike zero leaders"
  ) {
    val d = get(new Fixture(fees = 100).distribution())
    assert(!d.members.contains(script) && !d.members.contains(member))
    assertEquals(d.members(memberB).amount, BigInt(7))
    assertEquals(d.poolTotals(pool), M.PoolTotals(0, 8, 2))
    // Two separate rewards of 0.8 are zero, not a combined reward of one.
    assertEquals(d.completed.totalRewards, BigInt(17));
    assertEquals(d.completed.deltaR2, BigInt(83))
    val tiny = get(new Fixture(fees = 10).distribution())
    assertEquals(tiny.members, Map.empty[S.Credential, B.Reward])
    assertEquals(tiny.leaders(recipient).find(_.pool == other).get.amount, BigInt(0))
    assertEquals(tiny.completed.totalRewards, BigInt(1))
    val marginOne = get(new Fixture(margin = S.Ratio(1, 1)).distribution())
    assert(!marginOne.members.contains(script) && !marginOne.members.contains(member))
    val zero = get(new Fixture(fees = 0).distribution())
    assert(zero.members.isEmpty); assertEquals(zero.completed.totalRewards, BigInt(0))
  }
  test("PV9 ignores frozen registration prefilter for members and leaders") {
    val f = new Fixture(omitScriptAtFreeze = true)
    assert(
      !f.frozen.registeredAccounts.contains(script) && !f.frozen.registeredAccounts.contains(
        recipient
      )
    )
    val d = get(f.distribution())
    assertEquals(d.members(script).amount, BigInt(24)); assertEquals(d.leaders(recipient).size, 2)
    assertEquals(d.completed.totalRewards, BigInt(198))
  }
  test(
    "application-time deregistration and new leader registration drive credits and unpublished SNAP"
  ) {
    val f = new Fixture; val d = get(f.distribution())
    val currentAccounts = (f.accounts - script - member).updated(recipient, S.Account(0, 0, None))
    val current = get(
      B.contextAtApplication(
        f.bo,
        f.so,
        bytes(13),
        f.state,
        f.application(currentAccounts),
        f.pots,
        f.frozen.previousBlocks,
        Map.empty
      )
    )
    val effect = get(B.completeFromFrozen(f.bo, current, d.completed))
    assertEquals(effect.applied.totalUnregistered, BigInt(48))
    assertEquals(effect.applied.credited(recipient), BigInt(75))
    assertEquals(effect.applied.credited(memberB), BigInt(75))
    assertEquals(effect.pots, B.Pots(48, 1802, 0, 2000))
    val signal = get(B.signal(f.bo, current, bytes(14), 500))
    val preview = get(B.preview(f.bo, current, signal, B.RewardPhase.Completed(effect)))
    assertEquals(preview.balances(recipient), BigInt(75));
    assertEquals(preview.balances(memberB), BigInt(75))
    assert(!preview.published && !preview.epochTransitionValidated)
    assertEquals(
      d.members(script).amount,
      BigInt(24)
    ) // Calculation does not mutate on application.
  }
  test("incomplete, extraneous, miskeyed, stale and cross-allocation pool results reject") {
    val f = new Fixture; val g = new Fixture(fees = 999)
    assert(f.distribution(f.results - pool).isLeft)
    assert(f.distribution(f.results.updated(bytes(99, 28), f.results(pool))).isLeft)
    assert(f.distribution(f.results.updated(pool, f.results(other))).isLeft)
    assert(f.distribution(f.results.updated(pool, g.results(pool))).isLeft)
    assert(f.distribution(f.results.updated(pool, null)).isLeft)
    assert(M.distribute(f.frozen, bytes(99), f.allocation, f.allocation.id, f.results).isLeft)
    assert(M.distribute(f.frozen, f.frozen.id, f.allocation, bytes(99), f.results).isLeft)
    assert(M.distribute(f.frozen, f.frozen.id, g.allocation, g.allocation.id, f.results).isLeft)
  }
  test("shared leader recipient can also be a member without losing reward type or pool identity") {
    val f = new Fixture(leaderAccount = script); val d = get(f.distribution())
    assertEquals(d.completed.rewards(script).size, 3)
    val applied = get(
      ConwayRewardApplication.applyPv9(f.accounts, f.pots, d.completed.deltas, d.completed.rewards)
    )
    assertEquals(applied.credited(script), BigInt(99));
    assertEquals(applied.totalUnregistered, BigInt(0))
    assertEquals(applied.credited.values.sum, BigInt(198))
  }
  test(
    "empty and nonproducing pools produce complete empty maps; explicit zero keeps leader entries"
  ) {
    val empty = get(new Fixture(empty = true, blocks = Map.empty).distribution())
    assert(empty.members.isEmpty && empty.leaders.isEmpty && empty.poolTotals.isEmpty)
    assertEquals(empty.completed.deltaR2, BigInt(1000))
    val missing = get(new Fixture(blocks = Map.empty).distribution())
    assert(missing.members.isEmpty && missing.leaders.isEmpty && missing.poolTotals.isEmpty)
    val zero = get(new Fixture(blocks = Map(pool -> BigInt(0))).distribution())
    assert(zero.members.isEmpty);
    assertEquals(zero.leaders(recipient), Set(B.Reward(B.RewardKind.Leader, pool, 0)))
    assertEquals(zero.poolTotals, Map(pool -> M.PoolTotals(0, 0, 0)))
    assertNotEquals(missing.id, zero.id)
  }
  test(
    "result identity is independent of pool traversal order and sensitive to frozen reward inputs"
  ) {
    val f = new Fixture; val a = get(f.distribution())
    val reversed = get(f.distribution(f.results.toVector.reverse.toMap))
    assertEquals(a.id, reversed.id); assertEquals(a.completed.id, reversed.completed.id)
    val changed = get(new Fixture(margin = S.Ratio(1, 2)).distribution())
    assertNotEquals(a.id, changed.id); assertNotEquals(a.poolIdentities, changed.poolIdentities)
  }
