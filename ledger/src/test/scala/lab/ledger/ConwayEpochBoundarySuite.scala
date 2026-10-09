// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayEpochBoundary as B
import ConwayStake as S

class ConwayEpochBoundarySuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def bytes(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
  private val pin = bytes(1)
  private val pool = bytes(2, 28)
  private val key = S.Credential(false, bytes(3, 28))
  private val script = S.Credential(true, key.hash)
  private def n(v: V) = Node(v, Bytes.empty)
  private val max = (BigInt(1) << 64) - 1
  private class Fixture(empty: Boolean = false):
    val owner = B.owner()
    val stakeOwner = S.owner()
    val accounts = Map(
      key -> S.Account(if empty then 0 else 5, 2, Some(pool)),
      script -> S.Account(if empty then 0 else 7, 2, Some(pool))
    )
    val pools = Map(
      pool -> S.Pool(bytes(4), 20, 2, S.Ratio(1, 10), key, Set(key.hash), Set(key, script), 0)
    )
    val context = get(S.context(pin, 500, accounts, pools))
    val raw = get(
      Cbor.encode(
        V.Map(
          if empty then Vector.empty
          else
            Vector(
              n(V.Arr(Vector(n(V.ByteString(bytes(5))), n(V.UInt(0))))) ->
                n(
                  V.Arr(
                    Vector(
                      n(
                        V.ByteString(
                          Bytes(Vector(0.toByte) ++ bytes(6, 28).value ++ key.hash.value)
                        )
                      ),
                      n(V.UInt(1000))
                    )
                  )
                )
            )
        )
      )
    )
    val env = get(
      ClusterTransition.environment(pin, pin, 1082026, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(ClusterTransition.checkpoint(env, raw, 50, 110, pin))
    val snapshots = S.Snapshots(
      get(S.fromActive(context, Map(key -> S.Active(40, pool)))),
      get(S.fromActive(context, Map(key -> S.Active(20, pool)))),
      get(S.fromActive(context, Map(key -> S.Active(10, pool)))),
      8
    )
    val state = get(S.seed(stakeOwner, context, ledger, pin, get(S.recompute(raw)), snapshots))
    val pots = B.Pots(100, 1000, 50, 5000)
    val start = get(
      B.context(
        owner,
        stakeOwner,
        bytes(10),
        state,
        pots,
        Map(pool -> BigInt(2)),
        Map(pool -> BigInt(3))
      )
    )
    val parameters = Bytes.fromArray("synthetic previous PV9 parameters".getBytes("UTF-8"))
    val frozen = get(B.freeze(owner, start, 110, 100, parameters))
    val block = get(ClusterTransition.prepareBlock(ledger, bytes(11), Vector.empty, 151))
    val accepted = get(ClusterTransition.commitBlock(ledger, block))
    val nextStake = get(
      S.select(stakeOwner, state, get(S.prepare(stakeOwner, state, ledger, block)))
    )
    val current = get(
      B.context(
        owner,
        stakeOwner,
        bytes(12),
        nextStake,
        pots,
        Map(pool -> BigInt(2)),
        Map(pool -> BigInt(4))
      )
    )
    val signal = get(B.signal(owner, current, bytes(13), 500))
    val rewards = Map(
      key -> Set(B.Reward(B.RewardKind.Member, pool, 8)),
      script -> Set(B.Reward(B.RewardKind.Leader, pool, 4))
    )
    val delta = B.Deltas(3, -10, -5)
    def complete = get(B.syntheticComplete(owner, current, frozen, delta, rewards))
    def preview = get(B.preview(owner, current, signal, B.RewardPhase.Completed(complete)))

  test("post-reward balances and fees feed fresh mark; old mark supplies leadership") {
    val f = new Fixture; val p = f.preview
    assertEquals(p.balances(key), BigInt(13)); assertEquals(p.balances(script), BigInt(11))
    assertEquals(p.rotation.snapshots.mark.active(key).coin, BigInt(1013))
    assertEquals(p.rotation.snapshots.mark.active(script).coin, BigInt(11))
    assertEquals(p.rotation.snapshots.mark.total, BigInt(1024))
    assertEquals(p.rotation.snapshots.fees, BigInt(45))
    assert(p.rotation.snapshots.set eq f.snapshots.mark)
    assert(p.rotation.snapshots.go eq f.snapshots.set)
    assert(p.rotation.leadership eq f.snapshots.mark)
    assertEquals(f.state.context.accounts(key).balance, BigInt(5))
    assertEquals(f.state.snapshots.fees, BigInt(8))
    assert(!p.published && !p.epochTransitionValidated && !f.complete.nativeMonetaryValidated)
    assertEquals(p.unresolved, B.Unresolved.values.toSet)
  }
  test("exact signed conservation, pot bounds, reward domains and duplicate identities reject") {
    val f = new Fixture
    def effect(d: B.Deltas, rs: Map[S.Credential, Set[B.Reward]] = f.rewards) =
      B.syntheticComplete(f.owner, f.current, f.frozen, d, rs)
    assert(effect(B.Deltas(3, -9, -5)).isLeft)
    assert(effect(B.Deltas(1040, -1000, -52)).isLeft) // Conserves, but fees become negative.
    assert(effect(B.Deltas(max + 1, -max - 1, 0), Map.empty).isLeft)
    assert(
      effect(
        B.Deltas(0, -1, 0),
        Map(S.Credential(false, bytes(99, 28)) -> Set(B.Reward(B.RewardKind.Member, pool, 1)))
      ).isRight
    )
    assert(
      effect(
        B.Deltas(0, -3, 0),
        Map(
          key -> Set(B.Reward(B.RewardKind.Member, pool, 1), B.Reward(B.RewardKind.Member, pool, 2))
        )
      ).isLeft
    )
    assert(effect(B.Deltas(0, 0, 0), Map(key -> Set.empty)).isRight)
    assert(
      effect(B.Deltas(0, 0, 0), Map(key -> Set(B.Reward(B.RewardKind.Member, pool, -1)))).isLeft
    )
    val p = f.preview
    def sum(p: B.Pots, balances: Iterable[BigInt]) = p.treasury + p.reserves + p.fees + balances.sum
    assertEquals(sum(p.pots, p.balances.values), sum(f.pots, f.accounts.values.map(_.balance)))
  }
  test("Unknown and Pulsing are never converted into absent or zero rewards") {
    val f = new Fixture
    assert(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Unknown).isLeft)
    assert(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Pulsing(f.frozen)).isLeft)
    assert(B.suppliedAbsent(f.owner, f.current, Bytes.empty).isLeft)
    val absence = get(B.suppliedAbsent(f.owner, f.current, bytes(21)))
    val p = get(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Absent(absence)))
    assertEquals(p.pots, f.pots)
    assertEquals(p.balances, f.accounts.map((c, a) => c -> a.balance))
    assert(p.id != f.preview.id)
  }
  test("reward timing uses strict start and force inequalities and bounded arithmetic") {
    assertEquals(get(B.rewardTiming(0, 100, 100)), B.Timing.TooEarly)
    assertEquals(get(B.rewardTiming(0, 100, 101)), B.Timing.StartOrPulse)
    assertEquals(get(B.rewardTiming(0, 100, 200)), B.Timing.StartOrPulse)
    assertEquals(get(B.rewardTiming(0, 100, 201)), B.Timing.ForceCompletion)
    assert(B.rewardTiming(max - 1, 1, max).isLeft)
    assert(B.rewardTiming(0, 0, 1).isLeft)
    val f = new Fixture
    assert(B.freeze(f.owner, f.start, 110, 110, f.parameters).isLeft)
    assert(B.freeze(f.owner, f.start, 500, 100, f.parameters).isLeft)
    assert(B.freeze(f.owner, f.start, 110, 100, Bytes.empty).isLeft)
  }
  test(
    "frozen inputs retain pre-tick go, fees, accounts, parameters, reserves and previous blocks"
  ) {
    val f = new Fixture; val inputs = f.frozen; val p = f.preview
    assert(inputs.go eq f.snapshots.go)
    assertEquals(inputs.snapshotFees, BigInt(8))
    assertEquals(inputs.registeredAccounts, f.accounts)
    assertEquals(inputs.previousBlocks, Map(pool -> BigInt(2)))
    assertEquals(inputs.previousParameters, f.parameters)
    assertEquals(inputs.reserves, BigInt(1000)); assertEquals(inputs.maxSupply, BigInt(5000))
    assertEquals(inputs.preTickTupleId, f.start.tupleId)
    assert(p.preTickRewardEnvironment eq f.current)
    assertEquals(p.previousBlocks, Map(pool -> BigInt(4)))
    assertEquals(p.currentBlocks, Map.empty[Bytes, BigInt])
    assertEquals(
      f.current.currentBlocks,
      Map(pool -> BigInt(4))
    ) // No incoming block counted or accepted.
  }
  test("foreign or stale contexts, signals, absences and completion effects fail closed") {
    val f = new Fixture; val other = new Fixture
    assert(B.preview(other.owner, f.current, f.signal, B.RewardPhase.Completed(f.complete)).isLeft)
    assert(B.preview(f.owner, f.start, f.signal, B.RewardPhase.Completed(f.complete)).isLeft)
    val absence = get(B.suppliedAbsent(f.owner, f.start, bytes(20)))
    assert(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Absent(absence)).isLeft)
    assert(B.syntheticComplete(f.owner, f.current, other.frozen, f.delta, f.rewards).isLeft)
    assert(
      B.context(f.owner, other.stakeOwner, bytes(12), f.nextStake, f.pots, Map.empty, Map.empty)
        .isLeft
    )
    val differentSupply = get(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots.copy(maxSupply = 6000),
        f.current.previousBlocks,
        f.current.currentBlocks
      )
    )
    assert(B.syntheticComplete(f.owner, differentSupply, f.frozen, f.delta, f.rewards).isLeft)
    val differentPrevious = get(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots,
        Map(pool -> BigInt(3)),
        f.current.currentBlocks
      )
    )
    assert(B.syntheticComplete(f.owner, differentPrevious, f.frozen, f.delta, f.rewards).isLeft)
    val changed = get(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots,
        Map.empty,
        Map(pool -> BigInt(5))
      )
    )
    val signal = get(B.signal(f.owner, changed, bytes(13), 500))
    assert(B.preview(f.owner, changed, signal, B.RewardPhase.Completed(f.complete)).isLeft)
    assert(B.signal(f.owner, f.current, bytes(13), 499).isLeft)
    assert(B.signal(f.owner, f.current, bytes(13), 1000).isLeft)
    assert(B.signal(f.owner, f.current, bytes(13), max + 1).isLeft)
  }
  test("nonzero old mark remains leadership when post-boundary fresh stake is empty") {
    val f = new Fixture(empty = true)
    val absence = get(B.suppliedAbsent(f.owner, f.current, bytes(20)))
    val p = get(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Absent(absence)))
    assert(p.rotation.snapshots.mark.active.isEmpty)
    assertEquals(p.rotation.snapshots.mark.total, BigInt(1))
    assertEquals(p.rotation.leadership.active(key).coin, BigInt(40))
    assert(p.rotation.leadership ne p.rotation.snapshots.mark)
  }
  test("preview identity is deterministic, snapshot-sensitive and rejects count/supply overflow") {
    val f = new Fixture
    assertEquals(f.preview.id, f.preview.id)
    assert(S.snapshotIdentity(f.snapshots.mark) != S.snapshotIdentity(f.snapshots.set))
    assert(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots,
        Map(pool -> BigInt(0)),
        Map.empty
      ).isLeft
    )
    assert(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots,
        Map(pool -> max, bytes(22, 28) -> BigInt(1)),
        Map.empty
      ).isLeft
    )
    assert(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(12),
        f.nextStake,
        f.pots.copy(maxSupply = 100),
        Map.empty,
        Map.empty
      ).isLeft
    )
    val signal = get(B.signal(f.owner, f.current, bytes(14), 500))
    val p = get(B.preview(f.owner, f.current, signal, B.RewardPhase.Completed(f.complete)))
    assert(p.id != f.preview.id)
    assertEquals(p.rotation.beforeId, f.nextStake.id)
    assertEquals(p.rotation.beforeRevision, f.nextStake.revision)
  }

  test("application-time registration after freeze drives filtering and post-reward SNAP") {
    val f = new Fixture
    val newlyRegistered = S.Credential(false, bytes(50, 28))
    val accounts = Map(script -> f.accounts(script), newlyRegistered -> S.Account(0, 2, Some(pool)))
    val pools = f.pools.updated(pool, f.pools(pool).copy(delegators = Set(script, newlyRegistered)))
    val application = get(S.context(bytes(51), 500, accounts, pools))
    val c = get(
      B.contextAtApplication(
        f.owner,
        f.stakeOwner,
        bytes(52),
        f.nextStake,
        application,
        f.pots,
        f.current.previousBlocks,
        f.current.currentBlocks
      )
    )
    val signal = get(B.signal(f.owner, c, bytes(13), 500))
    val rewards = f.rewards.updated(newlyRegistered, Set(B.Reward(B.RewardKind.Member, pool, 2)))
    val complete = get(B.syntheticComplete(f.owner, c, f.frozen, B.Deltas(3, -12, -5), rewards))
    val p = get(B.preview(f.owner, c, signal, B.RewardPhase.Completed(complete)))
    assert(f.frozen.registeredAccounts.contains(key))
    assert(!f.frozen.registeredAccounts.contains(newlyRegistered))
    assertEquals(complete.applied.unregisteredCredentials, Set(key))
    assertEquals(complete.applied.totalUnregistered, BigInt(8))
    assertEquals(complete.applied.credited, Map(script -> BigInt(4), newlyRegistered -> BigInt(2)))
    assertEquals(p.pots.treasury, BigInt(111)); assertEquals(p.pots.fees, BigInt(45))
    assert(!p.balances.contains(key))
    assertEquals(p.rotation.snapshots.mark.active(script).coin, BigInt(11))
    assertEquals(p.rotation.snapshots.mark.active(newlyRegistered).coin, BigInt(2))
    assert(!p.rotation.snapshots.mark.active.contains(key))
    assert(p.rotation.leadership eq f.snapshots.mark)
    assert(p.rewardApplication.contains(complete.applied))
    assert(f.state.context.accounts.contains(key))
    assert(B.preview(f.owner, c, f.signal, B.RewardPhase.Completed(complete)).isLeft)
    assert(B.preview(f.owner, c, signal, B.RewardPhase.Completed(f.complete)).isLeft)
    assert(c.id != f.current.id)
    assert(!p.published && !p.epochTransitionValidated)
  }

  test(
    "completion derives fee removal and unused reward remainder before account/pot application"
  ) {
    val f = new Fixture
    val inputs = get(ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, 8, 10, 15, 3))
    val members = Map(key -> B.Reward(B.RewardKind.Member, pool, 8))
    val leaders = Map(key -> Set(B.Reward(B.RewardKind.Leader, pool, 2)))
    val completed = get(ConwayRewardCompletion.complete(inputs, inputs.id, members, leaders))
    assertEquals(completed.totalRewards, BigInt(10)); assertEquals(completed.deltaR2, BigInt(5))
    assertEquals(completed.deltas, B.Deltas(3, -5, -8))
    assertEquals(completed.rewards(key), Set(members(key)) ++ leaders(key))
    val effect = get(B.completeFromFrozen(f.owner, f.current, completed))
    val p = get(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Completed(effect)))
    assertEquals(p.pots, B.Pots(103, 995, 42, 5000))
    assertEquals(p.balances(key), BigInt(15))
    assertEquals(p.rotation.snapshots.fees, BigInt(42))
    assert(p.rotation.leadership eq f.snapshots.mark)
    assert(effect.completionIdentity.contains(completed.id))
    assert(
      !completed.nativeEntitlementValidated && !completed.pulserCompleted && !completed.nonMyopicUpdated
    )
    assert(!p.published && !p.epochTransitionValidated)
  }
  test("completion handles all-unused and fully allocated reward pots without arbitrary deltas") {
    val f = new Fixture
    val inputs = get(ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, 8, 10, 15, 3))
    val empty = get(ConwayRewardCompletion.complete(inputs, inputs.id, Map.empty, Map.empty))
    assertEquals(empty.deltaR2, BigInt(15)); assertEquals(empty.deltas, B.Deltas(3, 5, -8))
    val used = get(
      ConwayRewardCompletion.complete(
        inputs,
        inputs.id,
        Map(key -> B.Reward(B.RewardKind.Member, pool, 12)),
        Map(script -> Set(B.Reward(B.RewardKind.Leader, pool, 3)))
      )
    )
    assertEquals(used.deltaR2, BigInt(0)); assertEquals(used.deltas, B.Deltas(3, -10, -8))
    assertEquals(
      used.deltas.treasury + used.deltas.reserves + used.deltas.fees + used.totalRewards,
      BigInt(0)
    )
  }
  test("completion rejects stale frozen/input identities, fee mismatch and allocation bounds") {
    val f = new Fixture
    def input(fees: BigInt, r: BigInt, pot: BigInt, t: BigInt) =
      ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, fees, r, pot, t)
    assert(input(7, 10, 14, 3).isLeft)
    assert(input(8, 10, 14, 3).isLeft)
    assert(input(8, 1001, 1006, 3).isLeft)
    assert(input(8, -1, 4, 3).isLeft)
    assert(input(8, 10, max + 1, 3).isLeft)
    assert(ConwayRewardCompletion.checkedInputs(f.frozen, bytes(90), 8, 10, 15, 3).isLeft)
    val inputs = get(input(8, 10, 15, 3))
    assert(ConwayRewardCompletion.complete(inputs, bytes(91), Map.empty, Map.empty).isLeft)
    val result = get(ConwayRewardCompletion.complete(inputs, inputs.id, Map.empty, Map.empty))
    val lowFees = get(
      B.context(
        f.owner,
        f.stakeOwner,
        bytes(92),
        f.nextStake,
        f.pots.copy(fees = 7),
        f.current.previousBlocks,
        f.current.currentBlocks
      )
    )
    assert(B.completeFromFrozen(f.owner, lowFees, result).isLeft)
    val other = new Fixture
    assert(B.completeFromFrozen(other.owner, other.current, result).isLeft)
  }
  test(
    "completion checks member/leader roles, duplicate set identities, aggregate limits and overspend"
  ) {
    val f = new Fixture
    val inputs = get(ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, 8, 10, 15, 3))
    def complete(m: Map[S.Credential, B.Reward], l: Map[S.Credential, Set[B.Reward]]) =
      ConwayRewardCompletion.complete(inputs, inputs.id, m, l)
    assert(complete(Map(key -> B.Reward(B.RewardKind.Leader, pool, 1)), Map.empty).isLeft)
    assert(complete(Map.empty, Map(key -> Set(B.Reward(B.RewardKind.Member, pool, 1)))).isLeft)
    assert(
      complete(
        Map.empty,
        Map(
          key -> Set(B.Reward(B.RewardKind.Leader, pool, 1), B.Reward(B.RewardKind.Leader, pool, 2))
        )
      ).isLeft
    )
    assert(complete(Map(key -> B.Reward(B.RewardKind.Member, pool, 16)), Map.empty).isLeft)
    assert(
      complete(
        Map(key -> B.Reward(B.RewardKind.Member, pool, max)),
        Map(script -> Set(B.Reward(B.RewardKind.Leader, pool, 1)))
      ).isLeft
    )
    assert(
      complete(
        Map(S.Credential(false, Bytes.empty) -> B.Reward(B.RewardKind.Member, pool, 1)),
        Map.empty
      ).isLeft
    )
  }
  test("completion identity commits to frozen allocation even when final deltas coincide") {
    val f = new Fixture
    val a = get(ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, 8, 10, 15, 3))
    val b = get(ConwayRewardCompletion.checkedInputs(f.frozen, f.frozen.id, 8, 11, 16, 3))
    val members = Map(
      key -> B.Reward(B.RewardKind.Member, pool, 7),
      script -> B.Reward(B.RewardKind.Member, pool, 3)
    )
    val x = get(ConwayRewardCompletion.complete(a, a.id, members, Map.empty))
    val y = get(ConwayRewardCompletion.complete(b, b.id, members, Map.empty))
    assertEquals(x.deltas, y.deltas); assert(x.id != y.id)
    val reordered =
      get(ConwayRewardCompletion.complete(a, a.id, members.toVector.reverse.toMap, Map.empty))
    assertEquals(x.id, reordered.id)
    val ex = get(B.completeFromFrozen(f.owner, f.current, x))
    val ey = get(B.completeFromFrozen(f.owner, f.current, y))
    assertEquals(ex.pots, ey.pots); assert(ex.id != ey.id)
    val px = get(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Completed(ex)))
    val py = get(B.preview(f.owner, f.current, f.signal, B.RewardPhase.Completed(ey)))
    assert(px.id != py.id)
  }
