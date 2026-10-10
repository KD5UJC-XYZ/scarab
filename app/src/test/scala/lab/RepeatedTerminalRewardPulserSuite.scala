// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayStake as S
import lab.ledger.ConwayEpochBoundary as B
import lab.ledger.ConwayRewardStart as R
import lab.ledger.ConwayMemberRewards as M
import lab.ledger.{ClusterTransition, ConwayPoolReward, ConwayRewardPulser, ConwayNonMyopic as NM}
import RepeatedTerminalCbor.{Node as WNode, Value as WV}

/** Independent source-shaped active reward encodings; no native capture or execution claim. */
class RepeatedTerminalRewardPulserSuite extends munit.FunSuite:
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
  private val H = RepeatedTerminalRewardPulser
  private val oldPool = bytes(30, 28)
  private val oldWords = Vector.fill(100)(0xbf800000)
  private val firstWords = Vector.fill(100)(0x80000000)
  private val secondWords = Vector.fill(100)(0x3f800000)
  private val history = get(NM.state(Map(oldPool -> get(NM.likelihood(oldWords))), 17))
  private def likelihoods(f: Fixture): Map[Bytes, NM.Likelihood] =
    f.go.pools.keys.map { id =>
      id -> get(NM.likelihood(if id == pool then firstWords else secondWords))
    }.toMap
  private def project(f: Fixture, p: P.State): H.Projection =
    get(H.fromState(p, history, likelihoods(f)))
  private val traversal = Vector(script, owner, member, ownerB, memberB)
  private val active = Vector(
    script -> S.Active(40, pool),
    owner -> S.Active(20, pool),
    member -> S.Active(40, pool),
    ownerB -> S.Active(25, other),
    memberB -> S.Active(75, other)
  )
  private val payments = Map(
    script -> B.Reward(B.RewardKind.Member, pool, 24),
    member -> B.Reward(B.RewardKind.Member, pool, 24),
    memberB -> B.Reward(B.RewardKind.Member, other, 75)
  )
  private def w(v: WV): WNode = WNode(v, Bytes.empty)
  private def u(v: BigInt): WNode = w(WV.UInt(v))
  private def wa(values: WNode*): WNode = w(WV.Arr(values.toVector))
  private def wm(values: (WNode, WNode)*): WNode = w(WV.Map(values.toVector))
  private def wb(value: Bytes): WNode = w(WV.ByteString(value))
  private def wc(value: S.Credential): WNode = wa(u(if value.script then 1 else 0), wb(value.hash))
  private def ws(values: WNode*): WNode = w(WV.Tag(258, wa(values*)))
  private def wratio(n: BigInt, d: BigInt): WNode = w(WV.Tag(30, wa(u(n), u(d))))
  private def rw(value: B.Reward): WNode =
    wa(u(value.kind.ordinal), wb(value.pool), u(value.amount))
  private def weights(values: Vector[Int]): WNode = wa(values.map(x => w(WV.Float32(x)))*)
  private def rewardMap(values: Map[S.Credential, B.Reward]): WNode =
    wm(values.toVector.map((c, r) => wc(c) -> rw(r))*)
  private def eventMap(values: Map[S.Credential, B.Reward]): WNode =
    wm(values.toVector.map((c, r) => wc(c) -> ws(rw(r)))*)
  private def nativePool(first: Boolean): WNode = wa(
    u(100),
    wratio(1, 2),
    ws(wb(if first then owner.hash else ownerB.hash)),
    u(if first then 20 else 25),
    wb(bytes(if first then 8 else 9)),
    u(0),
    u(if first then 7 else 0),
    if first then wratio(1, 3) else wratio(0, 1),
    u(if first then 3 else 2),
    wc(recipient)
  )
  private def nativeInfo(first: Boolean): WNode =
    wa(
      wratio(1, 10),
      u(100),
      nativePool(first),
      u(1),
      wa(wb(if first then pool else other), u(if first then 50 else 25))
    )
  private val nativeHistory = wa(wm(wb(oldPool) -> weights(oldWords)), u(17))
  private val nativeSnapshot = wa(
    u(1000),
    wa(u(9), u(0)),
    nativeHistory,
    u(0),
    u(1000),
    u(0),
    wm(wb(pool) -> weights(firstWords), wb(other) -> weights(secondWords)),
    wm(
      wc(recipient) -> ws(
        rw(B.Reward(B.RewardKind.Leader, pool, 50)),
        rw(B.Reward(B.RewardKind.Leader, other, 25))
      )
    )
  )
  private val nativeFree = wa(
    ws(traversal.map(wc)*),
    u(1000),
    wa(u(9), u(0)),
    wm(wb(pool) -> nativeInfo(true), wb(other) -> nativeInfo(false))
  )
  private def nativePulser(processed: Int): WNode =
    val accumulated = payments.filter((c, _) => traversal.take(processed).contains(c))
    val first = if processed == 0 then 0 else ((processed - 1) / 2) * 2
    val recent = payments.filter((c, _) => traversal.slice(first, processed).contains(c))
    wa(
      u(2),
      nativeFree,
      wm(active.drop(processed).map((c, a) => wc(c) -> wa(u(a.coin), wb(a.pool)))*),
      wa(rewardMap(accumulated), eventMap(recent))
    )
  private def replace(root: WNode, path: List[Int], value: WNode): WNode = path match
    case Nil => value
    case head :: tail =>
      root.value match
        case WV.Arr(xs) => w(WV.Arr(xs.updated(head, replace(xs(head), tail, value))))
        case _          => fail("array mutation path")
  private def parts(root: WNode): Vector[WNode] = get(RepeatedTerminalCbor.rows(root))

  test("independent full native-shaped initial and each active prefix compare without completion") {
    val f = new Fixture
    val initial = get(start(f))
    val states = (1 to 3).scanLeft(initial)((p, i) => get(P.pulse(p, p.id, 110 + i)))
    assertEquals(states.map(_.processed), Vector(0, 2, 4, 5))
    states.foreach { p =>
      val expected = get(H.decode(nativeSnapshot, nativePulser(p.processed)))
      val actual = project(f, p)
      assertEquals(H.json(actual), H.json(expected))
      assertEquals(p.phase, P.Phase.Pulsing)
      assert(p.completion.isEmpty)
      assertEquals(actual.remaining, active.drop(p.processed))
      assertEquals(
        actual.accumulated,
        payments.filter((c, _) => traversal.take(p.processed).contains(c))
      )
      assertEquals(actual.snapshot.nonMyopic.id, history.id)
      assertEquals(actual.snapshot.likelihoods, Map(pool -> firstWords, other -> secondWords))
    }
    assertEquals(states.last.remaining, 0)
    assertEquals(project(f, states.last).recent.keySet, Set(memberB))
    assertEquals(project(f, states(1)).recent.keySet, Set(script))
    assertEquals(project(f, states(2)).recent.keySet, Set(member))
    assertEquals(initial.processed, 0)
    assert(initial.members.isEmpty)
  }

  test(
    "active cursor counts owners, zero rewards and nonproducing pools in native credential order"
  ) {
    for f <- Vector(new Fixture(fees = 0), new Fixture(blocks = Map(pool -> BigInt(1)))) do
      val initial = get(start(f))
      assertEquals(initial.traversal, traversal)
      val states = (1 to 3).scanLeft(initial)((p, i) => get(P.pulse(p, p.id, 110 + i)))
      states.foreach(p => assertEquals(project(f, p).remaining, active.drop(p.processed)))
      val last = project(f, states.last)
      assert(last.remaining.isEmpty)
      assert(!last.accumulated.contains(owner) && !last.accumulated.contains(ownerB))
      if f.allocation.rewardPot == 0 then
        assert(last.accumulated.isEmpty && last.recent.isEmpty)
        assertEquals(last.snapshot.leaders(recipient).map(_.amount), Set(BigInt(0)))
        assertEquals(last.snapshot.leaders(recipient).map(_.pool), Set(pool, other))
      else
        assertEquals(last.freeVars.pools.keySet, Set(pool))
        assertEquals(last.snapshot.likelihoods.keySet, Set(pool, other))
        assertEquals(last.accumulated.keySet, Set(script, member))
        assert(last.recent.isEmpty)
  }

  test("frozen registration set and zero shared-recipient leaders are preserved") {
    val f = new Fixture(omitScriptAtFreeze = true)
    val p = get(start(f))
    val first = get(P.pulse(p, p.id, 111))
    val projection = project(f, first)
    assert(!projection.freeVars.registered.contains(script))
    assertEquals(projection.accumulated(script).amount, BigInt(24))
    assertEquals(projection.snapshot.leaders(recipient).map(_.amount), Set(BigInt(50), BigInt(25)))
    val changedFree = replace(nativeFree, List(0), ws((traversal.filterNot(_ == script)).map(wc)*))
    val expected = get(H.decode(nativeSnapshot, replace(nativePulser(2), List(1), changedFree)))
    assertEquals(H.json(projection), H.json(expected))
  }

  test(
    "empty source remains initially Pulsing while Complete is rejected by the active projector"
  ) {
    val f = new Fixture(empty = true, blocks = Map.empty)
    val initial = get(start(f))
    val observed = project(f, initial)
    assertEquals(observed.pulseSize, 1)
    assert(observed.remaining.isEmpty && observed.accumulated.isEmpty && observed.recent.isEmpty)
    assert(observed.freeVars.pools.isEmpty && observed.snapshot.leaders.isEmpty)
    assert(observed.snapshot.likelihoods.isEmpty)
    assertEquals(initial.phase, P.Phase.Pulsing)
    val complete = get(P.pulse(initial, initial.id, 111))
    assert(H.fromState(complete, history, likelihoods(f)).isLeft)
    assert(H.fromState(null, history, likelihoods(f)).isLeft)
    assert(H.fromState(initial, null, likelihoods(f)).isLeft)
    val otherFixture = new Fixture
    val otherInitial = get(start(otherFixture))
    assert(H.fromState(otherInitial, history, Map.empty).isLeft)
  }

  test("every active snapshot field and every RSLP or free-variable field changes comparison") {
    val f = new Fixture
    val initial = get(start(f)); val partial = get(P.pulse(initial, initial.id, 111))
    val wanted = H.json(project(f, partial))
    def differs(snapshot: WNode, pulser: WNode): Unit =
      H.decode(snapshot, pulser).foreach(value => assertNotEquals(H.json(value), wanted))
    val s = nativeSnapshot; val p = nativePulser(2)
    Vector(
      List(0) -> u(1001),
      List(1) -> wa(u(10), u(0)),
      List(2) -> replace(nativeHistory, List(1), u(18)),
      List(3) -> u(1),
      List(4) -> u(1001),
      List(5) -> u(1),
      List(6) -> wm(wb(pool) -> weights(secondWords), wb(other) -> weights(secondWords)),
      List(7) -> wm()
    ).foreach((path, value) => differs(replace(s, path, value), p))
    Vector(
      List(0) -> u(3),
      List(1, 0) -> ws(traversal.filterNot(_ == owner).map(wc)*),
      List(1, 1) -> u(999),
      List(1, 2) -> wa(u(8), u(0)),
      List(1, 3) -> wm(),
      List(2) -> wm(),
      List(3, 0) -> wm(),
      List(3, 1) -> wm()
    ).foreach((path, value) => differs(s, replace(p, path, value)))
    val info = nativeInfo(true)
    Vector(
      List(0) -> wratio(1, 9),
      List(1) -> u(101),
      List(2, 0) -> u(101),
      List(2, 1) -> wratio(1, 3),
      List(2, 2) -> ws(),
      List(2, 3) -> u(21),
      List(2, 4) -> wb(bytes(80)),
      List(2, 5) -> u(1),
      List(2, 6) -> u(8),
      List(2, 7) -> wratio(1, 2),
      List(2, 8) -> u(4),
      List(2, 9) -> wc(member),
      List(3) -> u(2),
      List(4) -> wa(wb(pool), u(51))
    ).foreach { (path, value) =>
      val changedPools = wm(wb(pool) -> replace(info, path, value), wb(other) -> nativeInfo(false))
      differs(s, replace(p, List(1, 3), changedPools))
    }
    val wrongMember = rewardMap(Map(script -> payments(script).copy(amount = 25)))
    differs(s, replace(p, List(3, 0), wrongMember))
    val wrongRecent = eventMap(Map(member -> payments(member)))
    differs(s, replace(p, List(3, 1), wrongRecent))
  }

  test("malformed widths, duplicate semantic keys and inconsistent recent members fail closed") {
    val s = nativeSnapshot; val p = nativePulser(2)
    assert(H.decode(wa(parts(s).dropRight(1)*), p).isLeft)
    assert(H.decode(s, wa(parts(p).dropRight(1)*)).isLeft)
    assert(H.decode(s, replace(p, List(0), u(0))).isLeft)
    assert(H.decode(s, replace(p, List(0), u(BigInt(1) << 63))).isLeft)
    assert(H.decode(s, replace(p, List(1, 0), ws(wc(owner), wc(owner)))).isLeft)
    val duplicate = wm(wc(member) -> wa(u(40), wb(pool)), wc(member) -> wa(u(41), wb(pool)))
    assert(H.decode(s, replace(p, List(2), duplicate)).isLeft)
    val repeatedMember = wm(wc(script) -> rw(payments(script)), wc(script) -> rw(payments(script)))
    assert(H.decode(s, replace(p, List(3, 0), repeatedMember)).isLeft)
    val duplicateRewardKey =
      wm(wc(script) -> ws(rw(payments(script)), rw(payments(script).copy(amount = 25))))
    assert(H.decode(s, replace(p, List(3, 1), duplicateRewardKey)).isLeft)
    assert(H.decode(s, replace(p, List(2), wm(wc(member) -> wa(u(0), wb(pool))))).isLeft)
    val malformedRecent =
      wm(wc(script) -> ws(rw(payments(script)), rw(B.Reward(B.RewardKind.Member, other, 1))))
    assert(H.decode(s, replace(p, List(3, 1), malformedRecent)).isLeft)
  }

  test(
    "raw likelihood semantics retain signed zero and reject binary64, nonfinite and wrong length"
  ) {
    val p = nativePulser(0)
    val snapshot = nativeSnapshot
    def withFirst(words: WNode) =
      replace(snapshot, List(6), wm(wb(pool) -> words, wb(other) -> weights(secondWords)))
    val halfNegativeZeros = wa(Vector.fill(100)(w(WV.Float16(0x8000)))*)
    assertEquals(
      H.json(get(H.decode(withFirst(halfNegativeZeros), p))),
      H.json(get(H.decode(snapshot, p)))
    )
    val positiveZeros = wa(Vector.fill(100)(w(WV.Float32(0)))*)
    assertNotEquals(
      H.json(get(H.decode(withFirst(positiveZeros), p))),
      H.json(get(H.decode(snapshot, p)))
    )
    for bad <- Vector(
        wa(Vector.fill(100)(w(WV.Float64(0L)))*),
        wa(Vector.fill(100)(w(WV.Float32(0x7f800000)))*),
        wa(Vector.fill(100)(w(WV.Float32(0x7fc00000)))*),
        wa(Vector.fill(99)(w(WV.Float32(0)))*)
      )
    do assert(H.decode(withFirst(bad), p).isLeft)
    val badHistory = replace(
      nativeHistory,
      List(0),
      wm(wb(oldPool) -> wa(Vector.fill(100)(w(WV.Float64(-4616189618054758400L)))*))
    )
    assert(H.decode(replace(snapshot, List(2), badHistory), p).isLeft)
  }

  test("full active sum decodes original CBOR bytes without losing phase or traversal semantics") {
    def word(n: BigInt, width: Int): Vector[Byte] =
      Vector.tabulate(width)(i => ((n >> (8 * (width - i - 1))) & 255).toByte)
    def head(major: Int, n: BigInt): Vector[Byte] =
      val (additional, width) =
        if n < 24 then (n.toInt, 0)
        else if n <= 255 then (24, 1)
        else if n <= 65535 then (25, 2)
        else if n <= BigInt("ffffffff", 16) then (26, 4)
        else (27, 8)
      Vector(((major << 5) | additional).toByte) ++ word(n, width)
    def encode(n: WNode): Vector[Byte] = n.value match
      case WV.UInt(value)       => head(0, value)
      case WV.ByteString(value) => head(2, value.size) ++ value.value
      case WV.Arr(values)       => head(4, values.size) ++ values.flatMap(encode)
      case WV.Map(values) =>
        head(5, values.size) ++ values.flatMap((k, v) => encode(k) ++ encode(v))
      case WV.Tag(tag, inner) => head(6, tag) ++ encode(inner)
      case WV.Float16(raw)    => Vector(0xf9.toByte) ++ word(raw, 2)
      case WV.Float32(raw)    => Vector(0xfa.toByte) ++ word(BigInt(raw.toLong & 0xffffffffL), 4)
      case WV.Float64(raw) => Vector(0xfb.toByte) ++ word(BigInt(raw) & ((BigInt(1) << 64) - 1), 8)
      case _               => fail("unsupported generated native fixture node")
    val f = new Fixture
    val initial = get(start(f))
    val p = nativePulser(0)
    val fields = parts(p)
    val reversed = fields(2).value match
      case WV.Map(values) => w(WV.Map(values.reverse))
      case _              => fail("remaining map")
    val sum = wa(wa(u(0), nativeSnapshot, replace(p, List(2), reversed)))
    val original = Bytes(encode(sum))
    val parsed = get(RepeatedTerminalCbor.decode(original))
    assertEquals(parsed.original, original)
    get(RepeatedPlutusTerminal.decodeReward(parsed)) match
      case RepeatedPlutusTerminal.Reward.Pulsing(projection) =>
        assertEquals(H.json(projection), H.json(project(f, initial)))
        assertEquals(projection.remaining.map(_._1), traversal)
      case other => fail(s"active sum changed phase: $other")
    assert(RepeatedPlutusTerminal.decodeReward(wa(wa(u(0), nativeSnapshot))).isLeft)
    assert(RepeatedPlutusTerminal.decodeReward(wa(wa(u(1), nativeSnapshot, p))).isLeft)
    assert(RepeatedPlutusTerminal.decodeReward(wa(wa(u(2), nativeSnapshot, p))).isLeft)
  }
