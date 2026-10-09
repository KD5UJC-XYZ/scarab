// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayStake as S
import ConwayEpochBoundary as B
import ConwayRewardStart as R
import ConwayMemberRewards as M

class ConwayRewardPulserSuite extends munit.FunSuite:
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
      empty: Boolean = false,
      k: Option[BigInt] = Some(BigInt(1)),
      observedSlot: BigInt = 110
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
    val ledger = get(ClusterTransition.checkpoint(env, raw, 50, 50, bytes(10)))
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
    val globalBytes = get(
      Cbor.encode(
        V.Arr(
          Vector(n(V.Text(if k.isDefined then R.PulserGlobalFormat else R.GlobalFormat))) ++
            (Vector[BigInt](500, 1, 20, 2000) ++ k.toVector).map(x => n(V.UInt(x)))
        )
      )
    )
    val globals = get(
      if k.isDefined then R.decodePulserGlobals(globalBytes) else R.decodeGlobals(globalBytes)
    )
    val frozen = get(B.freezeForAllocation(bo, start, observedSlot, 100, params, globals))
    val allocation = get(R.calculate(frozen, frozen.id))
    val results = go.pools.keys
      .map(id =>
        id -> get(ConwayPoolReward.calculate(frozen, frozen.id, allocation, allocation.id, id))
      )
      .toMap
    def distribution(rs: Map[Bytes, ConwayPoolReward.Result] = results) =
      M.distribute(frozen, frozen.id, allocation, allocation.id, rs)

  private def start(f: Fixture) =
    ConwayRewardPulser.start(f.frozen, f.frozen.id, f.allocation, f.allocation.id, f.results)
  private val P = ConwayRewardPulser
  test(
    "native credential order, ceiling pulse size and delayed completion match whole-map rewards"
  ) {
    val f = new Fixture; val s = get(start(f)); val whole = get(f.distribution())
    assertEquals(s.traversal, Vector(script, owner, member, ownerB, memberB))
    assertEquals(s.pulseSize, 2); assertEquals(s.processed, 0);
    assertEquals(s.phase, P.Phase.Pulsing)
    val a = get(P.pulse(s, s.id, 111)); assertEquals(a.processed, 2)
    assertEquals(a.members.keySet, Set(script));
    assertEquals(s.members, Map.empty[S.Credential, B.Reward])
    val b = get(P.pulse(a, a.id, 112)); assertEquals(b.processed, 4)
    val c = get(P.pulse(b, b.id, 113)); assertEquals(c.remaining, 0);
    assertEquals(c.phase, P.Phase.Pulsing)
    assertEquals(c.completion, None)
    val done = get(P.pulse(c, c.id, 114)); assertEquals(done.phase, P.Phase.Complete)
    assertEquals(done.completion.get.id, whole.id)
    assertEquals(done.completion.get.completed.rewards, whole.completed.rewards)
    val again = get(P.pulse(done, done.id, 115)); assertEquals(again.completion.get.id, whole.id)
    assertEquals(again.processed, 5)
  }
  test("force from initial or partial work equals whole result; late start forces immediately") {
    val f = new Fixture; val s = get(start(f)); val partial = get(P.pulse(s, s.id, 111))
    val forced = get(P.force(s, s.id, 201)); val resumed = get(P.force(partial, partial.id, 201))
    assertEquals(forced.completion.get.id, get(f.distribution()).id)
    assertEquals(resumed.completion.get.id, forced.completion.get.id)
    val late = new Fixture(observedSlot = 201); val lateState = get(start(late))
    assertEquals(lateState.phase, P.Phase.Complete)
    assertEquals(lateState.completion.get.id, get(late.distribution()).id)
    assert(
      !forced.nativeParityValidated && !forced.eventsImplemented && !forced.nonMyopicUpdated && !forced.published
    )
  }
  test("empty input starts pulsing and completes on next pulse; positive k has minimum chunk one") {
    val f = new Fixture(empty = true, blocks = Map.empty); val s = get(start(f))
    assertEquals(s.pulseSize, 1); assertEquals(s.remaining, 0);
    assertEquals(s.phase, P.Phase.Pulsing)
    assertEquals(get(P.pulse(s, s.id, 111)).completion.get.id, get(f.distribution()).id)
    val big = new Fixture(k = Some((BigInt(1) << 64) - 1)); val b = get(start(big))
    assertEquals(b.pulseSize, 1); assertEquals(b.securityParameter, (BigInt(1) << 64) - 1)
    assertNotEquals(b.frozenId, get(start(new Fixture)).frozenId)
  }
  test("strict start/force boundaries and same-epoch monotonically increasing signals") {
    val f = new Fixture(observedSlot = 101); val s = get(start(f))
    assert(B.freezeForAllocation(f.bo, f.start, 100, 100, f.params, f.globals).isLeft)
    assert(P.pulse(s, s.id, 100).isLeft); assert(P.force(s, s.id, 200).isLeft)
    assert(P.pulse(s, s.id, 200).isRight); assert(P.pulse(s, s.id, 201).isLeft)
    assert(P.force(s, s.id, 201).isRight); assert(P.force(s, s.id, 500).isLeft)
  }
  test("missing k, invalid globals, stale IDs, replay and pool substitution fail closed") {
    assert(start(new Fixture(k = None)).isLeft)
    def raw(k: BigInt) = get(
      Cbor.encode(
        V.Arr(
          Vector(n(V.Text(R.PulserGlobalFormat))) ++
            Vector[BigInt](500, 1, 20, 2000, k).map(x => n(V.UInt(x)))
        )
      )
    )
    assert(R.decodePulserGlobals(raw(0)).isLeft)
    assert(R.decodeGlobals(raw(1)).isLeft)
    val f = new Fixture; val s = get(start(f)); val next = get(P.pulse(s, s.id, 111))
    assert(P.pulse(next, s.id, 112).isLeft); assert(P.pulse(next, next.id, 111).isLeft)
    assert(P.force(next, s.id, 201).isLeft)
    assert(P.start(f.frozen, bytes(99), f.allocation, f.allocation.id, f.results).isLeft)
    val g = new Fixture(k = Some(BigInt(2)))
    assert(P.start(f.frozen, f.frozen.id, f.allocation, f.allocation.id, g.results).isLeft)
    assert(P.start(f.frozen, f.frozen.id, g.allocation, g.allocation.id, f.results).isLeft)
    assert(P.start(f.frozen, f.frozen.id, f.allocation, f.allocation.id, f.results - pool).isLeft)
    // Pure replay with its own original input is deterministic; no global consumed-token store exists.
    assertEquals(get(P.pulse(s, s.id, 111)).id, next.id)
  }
  test("registration changes between pulses and application do not change calculated rewards") {
    val f = new Fixture(omitScriptAtFreeze = true); val s = get(start(f));
    val a = get(P.pulse(s, s.id, 111))
    val done = get(P.force(a, a.id, 201)); val d = done.completion.get
    assertEquals(d.members(script).amount, BigInt(24))
    val accounts = (f.accounts - script - member).updated(recipient, S.Account(0, 0, None))
    val applied = get(
      ConwayRewardApplication.applyPv9(accounts, f.pots, d.completed.deltas, d.completed.rewards)
    )
    assertEquals(applied.totalUnregistered, BigInt(48));
    assertEquals(applied.credited(recipient), BigInt(75))
    assertEquals(applied.pots, B.Pots(48, 1802, 0, 2000))
  }

  test("opaque member progress rejects incomplete and foreign accumulation before distribution") {
    val f = new Fixture
    val work = M.prepare(f.frozen, f.frozen.id, f.allocation, f.allocation.id, f.results)
    val other = M.prepare(f.frozen, f.frozen.id, f.allocation, f.allocation.id, f.results)
    intercept[IllegalArgumentException](work.finish(work.initial))
    intercept[IllegalArgumentException](work.advance(other.initial, 1))
    intercept[IllegalArgumentException](work.advance(work.initial, 4097))
    val finished = work.advance(work.initial, work.traversal.size)
    intercept[IllegalArgumentException](other.finish(finished))
    assertEquals(work.finish(finished).id, get(f.distribution()).id)
  }
