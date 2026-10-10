// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayEpochBoundary as B
import ConwayStake as S
import ConwayRewardStart as R
import ConwayNonMyopic as N
import ConwayEmptyGovernance as G

/** Bounded supplied-state algebra only. No coordinator, native parity or repeated-epoch authority.
  */
class RepeatedEpochInvariantSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def bytes(i: Int, size: Int = 32) = Bytes(Vector.fill(size)(i.toByte))
  private def n(v: V) = Node(v, Bytes.empty)
  private def encode(v: V) = get(Cbor.encode(v))
  private def projection(format: String, fields: BigInt*) = encode(
    V.Arr(Vector(n(V.Text(format))) ++ fields.toVector.map(x => n(V.UInt(x))))
  )
  private val pool = bytes(2, 28)
  private val credential = S.Credential(false, bytes(3, 28))
  private val parameters = get(R.decodeParameters(projection(R.ParameterFormat, 9, 0, 0, 1, 0, 1)))
  private val globals = get(R.decodeGlobals(projection(R.GlobalFormat, 500, 1, 20, 2000)))
  private case class Frame(
      ledger: ClusterTransition.State,
      stake: S.State,
      pots: B.Pots,
      previous: Map[Bytes, BigInt],
      current: Map[Bytes, BigInt]
  )
  private case class Step(
      before: Frame,
      preview: B.Preview,
      block: ClusterTransition.BlockCandidate,
      stakeCandidate: S.Candidate,
      applied: ClusterTransition.BlockApplied,
      after: Frame
  )
  private class Fixture:
    val owner = B.owner(); val stakeOwner = S.owner()
    val accounts = Map(credential -> S.Account(0, 0, Some(pool)))
    val pools = Map(
      pool -> S.Pool(
        bytes(4),
        0,
        0,
        S.Ratio(0, 1),
        credential,
        Set(credential.hash),
        Set(credential),
        0
      )
    )
    val context = get(S.context(bytes(1), 500, accounts, pools))
    val mark = get(S.snapshot(context, Map.empty))
    val env = get(
      ClusterTransition.environment(bytes(1), bytes(1), 42, 0, 9, 0, 44, 155381, 16384, 4310)
    )
    val ledger = get(
      ClusterTransition.checkpoint(env, encode(V.Map(Vector.empty)), 10, 110, bytes(1))
    )
    val stake = get(
      S.seed(
        stakeOwner,
        context,
        ledger,
        bytes(1),
        Map.empty,
        S.Snapshots(mark, S.emptySnapshot, S.emptySnapshot, 10)
      )
    )
    val initial = Frame(ledger, stake, B.Pots(0, 1000, 10, 2000), Map.empty, Map(pool -> BigInt(0)))
    def boundaryContext(f: Frame) = get(
      B.context(owner, stakeOwner, f.ledger.id, f.stake, f.pots, f.previous, f.current)
    )
    def frozen(f: Frame) =
      val c = boundaryContext(f)
      get(
        B.freezeForAllocation(
          owner,
          c,
          f.stake.slot.max(f.stake.epoch * 500 + 110),
          100,
          parameters,
          globals
        )
      )
    def step(f: Frame, credit: BigInt = 0): Step =
      val c = boundaryContext(f)
      val slot = (f.stake.epoch + 1) * 500 + 110
      val signal = get(B.signal(owner, c, bytes(20 + f.stake.epoch.toInt), slot))
      val phase =
        if credit == 0 then B.RewardPhase.Absent(get(B.suppliedAbsent(owner, c, bytes(9))))
        else
          // Explicit conserving synthetic effect, not a calculated productive-pool reward.
          val frozen = get(B.freeze(owner, c, f.stake.slot, 100, parameters.original))
          B.RewardPhase.Completed(
            get(
              B.syntheticComplete(
                owner,
                c,
                frozen,
                B.Deltas(0, -credit, 0),
                Map(credential -> Set(B.Reward(B.RewardKind.Member, pool, credit)))
              )
            )
          )
      val preview = get(B.preview(owner, c, signal, phase))
      val block = get(
        ClusterTransition.prepareSyntheticSuccessorBlock(
          f.ledger,
          preview.epoch,
          preview.pots.fees,
          preview.id,
          signal.headerHash,
          Vector.empty,
          slot
        )
      )
      val candidate = get(
        S.prepareSyntheticSuccessor(stakeOwner, f.stake, f.ledger, block, preview)
      )
      val applied = get(ClusterTransition.commitBlock(f.ledger, block))
      val selected = get(S.select(stakeOwner, f.stake, candidate))
      Step(
        f,
        preview,
        block,
        candidate,
        applied,
        Frame(applied.state, selected, preview.pots, preview.previousBlocks, preview.currentBlocks)
      )

  test("two pure rotations make go nonempty despite zero active stake, rewards and production") {
    val f = new Fixture
    assert(f.mark.active.isEmpty)
    assertEquals(f.mark.pools.keySet, Set(pool))
    val first = f.step(f.initial); val second = f.step(first.after)
    assert(first.after.stake.snapshots.go.pools.isEmpty)
    assert(second.after.stake.snapshots.go eq f.mark)
    assert(second.after.stake.snapshots.go.active.isEmpty)
    assertEquals(second.after.stake.snapshots.go.pools.keySet, Set(pool))
    assertEquals(second.after.stake.context.accounts(credential).balance, BigInt(0))
    assertEquals(
      get(N.generateForFrozen(f.frozen(first.after), f.frozen(first.after).id)),
      Map.empty
    )
    val blocked = f.frozen(second.after)
    assert(N.generateForFrozen(blocked, blocked.id).isLeft)
    assert(!second.preview.epochTransitionValidated && !second.preview.published)
    assertEquals(second.after.ledger.environment.epoch, BigInt(2))
    assertEquals(f.initial.ledger.environment.epoch, BigInt(0))
  }

  test("late second successor freezes pre-tick go and counts, not the newly rotated versions") {
    val f = new Fixture
    val first = f.step(f.initial)
    val second = f.step(first.after.copy(current = Map(pool -> BigInt(7))))
    val late = get(
      B.freezeAfterBoundary(f.owner, second.preview, second.after.stake, 100, parameters, globals)
    )
    assertEquals(late.epoch, BigInt(2)); assertEquals(late.observedSlot, BigInt(1110))
    assert(late.go eq first.after.stake.snapshots.go)
    assert(late.go.pools.isEmpty)
    assertEquals(late.previousBlocks, Map(pool -> BigInt(0)))
    assertEquals(second.after.previous, Map(pool -> BigInt(7)))
    assertEquals(second.after.current, Map.empty[Bytes, BigInt])
    assertEquals(get(N.generateForFrozen(late, late.id)), Map.empty)
    val ordinary = f.frozen(second.after)
    assert(ordinary.go eq second.after.stake.snapshots.go)
    assertEquals(ordinary.previousBlocks, Map(pool -> BigInt(7)))
    assert(N.generateForFrozen(ordinary, ordinary.id).isLeft)
    assertNotEquals(late.id, ordinary.id)
  }

  test(
    "supplied reward credits enter mark then set then go without rewriting historical snapshots"
  ) {
    val f = new Fixture
    val first = f.step(f.initial, 7)
    val second = f.step(first.after, 11)
    val third = f.step(second.after)
    assertEquals(first.after.stake.snapshots.mark.active(credential).coin, BigInt(7))
    assertEquals(second.after.stake.snapshots.mark.active(credential).coin, BigInt(18))
    assert(second.after.stake.snapshots.set eq first.after.stake.snapshots.mark)
    assert(third.after.stake.snapshots.go eq first.after.stake.snapshots.mark)
    assertEquals(third.after.stake.snapshots.go.active(credential).coin, BigInt(7))
    assert(second.preview.rotation.leadership eq first.after.stake.snapshots.mark)
    assert(third.preview.rotation.leadership eq second.after.stake.snapshots.mark)
    for frame <- Vector(f.initial, first.after, second.after, third.after) do
      assertEquals(
        frame.pots.treasury + frame.pots.reserves + frame.pots.fees +
          frame.stake.context.accounts.values.map(_.balance).sum,
        BigInt(1010)
      )
    assertEquals(f.initial.stake.context.accounts(credential).balance, BigInt(0))
    assertEquals(first.after.stake.context.accounts(credential).balance, BigInt(7))
    assert(!first.preview.rewardApplication.get.nativeEntitlementValidated)
  }

  test("second-boundary undo restores prior snapshot objects and invalidates stale capabilities") {
    val f = new Fixture
    val first = f.step(f.initial); val second = f.step(first.after)
    val restoredLedger = get(
      ClusterTransition.undo(second.after.ledger, second.after.ledger.revision, second.applied.undo)
    )
    val restoredStake = get(S.rebindAfterUndo(f.stakeOwner, first.after.stake, restoredLedger))
    assertEquals(restoredLedger.id, first.after.ledger.id)
    assertEquals(restoredStake.id, first.after.stake.id)
    assert(restoredStake.snapshots eq first.after.stake.snapshots)
    assertEquals(restoredStake.revision, first.after.stake.revision + 2)
    assert(ClusterTransition.commitBlock(restoredLedger, second.block).isLeft)
    assert(S.select(f.stakeOwner, restoredStake, second.stakeCandidate).isLeft)
    val restored = first.after.copy(ledger = restoredLedger, stake = restoredStake)
    val current = f.boundaryContext(restored)
    val absence = get(B.suppliedAbsent(f.owner, current, bytes(9)))
    assert(
      B.preview(f.owner, current, second.preview.signal, B.RewardPhase.Absent(absence))
        .left
        .toOption
        .exists(_.contains("stale/foreign boundary signal"))
    )
    val replay = f.step(restored)
    assertEquals(replay.after.stake.epoch, second.after.stake.epoch)
    assertEquals(replay.after.stake.snapshots.go.pools, second.after.stake.snapshots.go.pools)
    assertNotEquals(replay.preview.id, second.preview.id)
  }

  test("empty governance output is fresh pulsing work, not an old completion for the next epoch") {
    def payload(i: Int) = get(G.payload(encode(V.UInt(i))))
    val current = payload(1); val previous = payload(2)
    val roots = G.Purpose.values.map(_ -> Option.empty[G.ActionId]).toMap
    val constitution = G.Constitution(G.Anchor("https://example.invalid/empty", bytes(7)), None)
    val enact = G.Enact(None, constitution, current, previous, 0, Map.empty, roots)
    val before = G.Input(
      0,
      0,
      Map.empty,
      None,
      Map.empty,
      constitution,
      G.Parameters(current, previous, G.FutureParameters.NoUpdate),
      roots,
      Map.empty,
      G.OldDRep.Complete(
        G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty),
        G.Ratify(enact, Vector.empty, Set.empty, false)
      ),
      Map.empty,
      Map.empty,
      G.PoolDistribution(1, Map.empty),
      Map.empty,
      Map.empty,
      Map.empty,
      Map.empty,
      0,
      0,
      G.Deposits(Map.empty, Map.empty, Map.empty, Map.empty, 0),
      G.Globals(1, payload(3))
    )
    val first = get(G.applyBoundary(before, 1))
    assertEquals(first.fresh.index, 0); assertEquals(first.fresh.pulseSize, 1)
    assert(first.parameters.previous eq current)
    assert(first.before.parameters.previous eq previous)
    val successorInput = before.copy(
      epoch = first.epoch,
      dormant = first.dormant,
      parameters = first.parameters,
      oldDRep = G.OldDRep.Pulsing
    )
    assert(G.applyBoundary(successorInput, 2).isLeft)
    // Reusing historical completion with the obsolete previous parameter role also rejects.
    assert(G.applyBoundary(successorInput.copy(oldDRep = before.oldDRep), 2).isLeft)
    assert(!first.epochTransitionValidated && !first.published)
  }

  test(
    "unchanged Plutus costs still need an epoch-bound environment; synthetic crossing stays disabled"
  ) {
    val model = Bytes.fromArray(
      java.nio.file.Files
        .readAllBytes(java.nio.file.Path.of("vm/src/main/resources/plutus-pv9/cost-model.json"))
    )
    val costs = new String(model.toArray, "UTF-8").trim
      .stripPrefix("[")
      .stripSuffix("]")
      .split(",")
      .toVector
      .map(x => BigInt(x.trim))
    def array(v: V*) = V.Arr(v.toVector.map(n))
    def ratio(a: BigInt, b: BigInt) = V.Tag(30, n(array(V.UInt(a), V.UInt(b))))
    val fields = Vector
      .fill[V](31)(V.UInt(0))
      .updated(0, V.UInt(44))
      .updated(1, V.UInt(155381))
      .updated(3, V.UInt(16384))
      .updated(12, array(V.UInt(9), V.UInt(0)))
      .updated(14, V.UInt(4310))
      .updated(
        15,
        V.Map(
          Vector(
            n(V.UInt(2)) -> n(V.Arr(costs.map(x => n(if x < 0 then V.NInt(x) else V.UInt(x)))))
          )
        )
      )
      .updated(16, array(ratio(577, 10000), ratio(721, 10000000)))
      .updated(17, array(V.UInt(14000000), V.UInt(10000000000L)))
      .updated(18, array(V.UInt(62000000), V.UInt(20000000000L)))
      .updated(19, V.UInt(5000))
      .updated(20, V.UInt(150))
      .updated(21, V.UInt(3))
    val original = encode(V.Arr(fields.map(n)))
    val hash =
      Bytes.fromArray(java.security.MessageDigest.getInstance("SHA-256").digest(original.toArray))
    val checked = get(PlutusParameters.decode(original, hash, model))
    def base(epoch: Int) =
      get(ClusterTransition.environment(bytes(1), hash, 42, epoch, 9, 0, 44, 155381, 16384, 4310))
    val zero = base(0); val one = base(1)
    val time = PlutusContextInput.SlotTime(1700000000000L, 100, 1, bytes(1))
    val old = get(PlutusEnvironment.bind(zero, checked, time, 0))
    val next = get(PlutusEnvironment.bind(one, checked, time, 0))
    assertNotEquals(old.id, next.id)
    assert(ClusterTransition.withPlutus(one, old).isLeft)
    assert(ClusterTransition.withPlutus(one, next).isRight)
    val admittedEnvironment = get(ClusterTransition.withPlutus(zero, old))
    val state = get(
      ClusterTransition.checkpoint(
        admittedEnvironment,
        encode(V.Map(Vector.empty)),
        0,
        110,
        bytes(8)
      )
    )
    assertEquals(
      ClusterTransition
        .prepareSyntheticSuccessorBlock(state, 1, 0, bytes(9), bytes(10), Vector.empty, 500),
      Left(
        ClusterTransition.Failure.Unsupported(
          "Plutus successor parameter/state rebinding is not enabled"
        )
      )
    )
    assertEquals(state.environment.epoch, BigInt(0))
  }
