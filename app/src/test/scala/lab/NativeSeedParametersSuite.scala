// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayStake as S

/** Hand-built transport/shape fixtures only: no native output or conformance oracle. */
class NativeSeedParametersSuite extends munit.FunSuite:
  private def get[E, A](e: Either[E, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def n(v: V) = Node(v, Bytes.empty)
  private def a(vs: V*) = V.Arr(vs.toVector.map(n))
  private def ratio(a: BigInt, b: BigInt): V = V.Tag(30, n(this.a(V.UInt(a), V.UInt(b))))
  private def full(rho: V = ratio(3, 1000), tau: V = ratio(1, 5)): Vector[V] =
    Vector(
      V.UInt(44),
      V.UInt(155381),
      V.UInt(90112),
      V.UInt(16384),
      V.UInt(1100),
      V.UInt(0),
      V.UInt(0),
      V.UInt(18),
      V.UInt(500),
      ratio(3, 10),
      rho,
      tau,
      a(V.UInt(9), V.UInt(0)),
      V.UInt(170000000),
      V.UInt(4310),
      V.Map(Vector.empty),
      a(ratio(1, 10), ratio(1, 100)),
      a(V.UInt(1000), V.UInt(2000)),
      a(V.UInt(3000), V.UInt(4000)),
      V.UInt(5000),
      V.UInt(150),
      V.UInt(3),
      a(Vector.fill(5)(ratio(1, 2))*),
      a(Vector.fill(10)(ratio(1, 2))*),
      V.UInt(0),
      V.UInt(10),
      V.UInt(6),
      V.UInt(0),
      V.UInt(0),
      V.UInt(20),
      ratio(15, 1)
    )
  private def pp(xs: Vector[V] = full()): Bytes = get(Cbor.encode(V.Arr(xs.map(n))))
  private def genesis(epoch: Int = 1000, f: String = "0.05", slot: String = "0.1"): String =
    s"""{"systemStart":"2026-10-09T00:00:00Z","networkMagic":42,"networkId":"Testnet",
       |"activeSlotsCoeff":$f,"securityParam":5,"epochLength":$epoch,
       |"slotsPerKESPeriod":100,"maxKESEvolutions":62,"slotLength":$slot,"updateQuorum":2,
       |"maxLovelaceSupply":45000000000000000,"protocolParams":{},"genDelegs":{},"initialFunds":{},
       |"staking":{"pools":{},"stake":{}}}""".stripMargin
  private def decode(
      prev: Bytes = pp(),
      cur: Bytes = pp(),
      g: String = genesis(),
      epoch: BigInt = 0,
      slot: BigInt = 36,
      magic: BigInt = 42
  ) =
    val bytes = raw(g)
    NativeSeedParameters.decode(prev, cur, bytes, sha(bytes), epoch, slot, magic)

  test("synthetic shape: exact previous/current PV9 reward roles and genesis windows") {
    val previous = pp()
    val current = pp(full(ratio(1, 100), ratio(1, 4)))
    val p = get(decode(previous, current))
    assertEquals(p.previous.original, previous)
    assertEquals(p.current.original, current)
    assertEquals(p.previous.rewards.rho, S.Ratio(3, 1000))
    assertEquals(p.current.rewards.rho, S.Ratio(1, 100))
    assertEquals(p.previous.rewards.tau, S.Ratio(1, 5))
    assertEquals(p.current.rewards.tau, S.Ratio(1, 4))
    assertEquals(p.current.rewards.pool.get.a0, S.Ratio(3, 10))
    assertEquals(p.current.rewards.pool.get.nOpt, 500)
    assertEquals(p.globals.activeSlotCoefficient, S.Ratio(1, 20))
    assertEquals(p.globals.securityParameter, Some(BigInt(5)))
    assertEquals(p.slotLength, S.Ratio(1, 10))
    assertEquals(p.stabilityWindow, BigInt(300))
    assertEquals(p.randomnessWindow, BigInt(400))
    assert(
      !p.rewardSeedAdmission && !p.runtimeImport && !p.monetaryParity &&
        !p.nativeConformance && !p.epochInfoAuthenticated
    )
  }
  test("500-slot diagnostic retains real window400 and reports incompatible timing") {
    val old = get(decode(g = genesis(500)))
    assertEquals(old.randomnessWindow, BigInt(400))
    assert(!old.timingProfileCompatible)
    assert(get(decode()).timingProfileCompatible)
    assert(!get(decode(g = genesis(800))).timingProfileCompatible)
  }
  test("window derivation uses ceiling of exact decimal rational") {
    val p = get(decode(g = genesis(f = "0.3", slot = "1e-1")))
    assertEquals(p.stabilityWindow, BigInt(50))
    assertEquals(p.randomnessWindow, BigInt(67))
    assertEquals(p.slotLength, S.Ratio(1, 10))
  }
  test("roles and complete opaque parameter content remain bound") {
    val one = pp(); val two = pp(full().updated(5, V.UInt(1)))
    val p = get(decode(one, two)); val swapped = get(decode(two, one))
    assertEquals(p.previous.rewards.rho, p.current.rewards.rho)
    assertNotEquals(p.previous.sha256, p.current.sha256)
    assertNotEquals(p.bindingId, swapped.bindingId)
    assertNotEquals(p.bindingId, get(decode(one, one)).bindingId)
    assertNotEquals(p.bindingId, get(decode(one, two, slot = 37)).bindingId)
  }
  test("both roles require complete31-field PV9.0 encoding") {
    Vector(
      full().dropRight(1),
      full() :+ V.UInt(0),
      full().updated(12, a(V.UInt(10), V.UInt(0))),
      full().updated(12, a(V.UInt(9), V.UInt(1)))
    ).foreach { xs =>
      assert(decode(prev = pp(xs)).isLeft)
      assert(decode(cur = pp(xs)).isLeft)
    }
    assert(decode(prev = Bytes.empty).isLeft)
    assert(decode(prev = Bytes(pp().value ++ Vector(0.toByte))).isLeft)
  }
  test("reward rational and integer mutations fail closed") {
    Vector(
      ratio(2, 4),
      ratio(1, 0),
      ratio(2, 1),
      a(V.UInt(1), V.UInt(2)),
      V.Tag(31, n(a(V.UInt(1), V.UInt(2))))
    ).foreach { r =>
      assert(decode(prev = pp(full(rho = r))).isLeft)
    }
    Vector(V.UInt(0), V.UInt(65536), V.NInt(-1)).foreach { v =>
      assert(decode(cur = pp(full().updated(8, v))).isLeft)
    }
  }
  test("unrelated fields still require native record shapes and widths") {
    Vector(
      2 -> V.UInt(BigInt(1) << 32),
      4 -> V.UInt(65536),
      15 -> V.Null,
      16 -> a(ratio(0, 1)),
      17 -> a(V.UInt(BigInt(1) << 63), V.UInt(0)),
      22 -> a(Vector.fill(4)(ratio(0, 1))*),
      23 -> a(Vector.fill(11)(ratio(0, 1))*)
    ).foreach { (i, v) => assert(decode(prev = pp(full().updated(i, v))).isLeft) }
    val duplicate = V.Map(Vector(n(V.UInt(0)) -> n(a(V.UInt(1))), n(V.UInt(0)) -> n(a(V.UInt(2)))))
    assert(decode(prev = pp(full().updated(15, duplicate))).isLeft)
  }
  test("effective genesis pin, point epoch and network are mandatory") {
    val bytes = raw(genesis())
    assert(NativeSeedParameters.decode(pp(), pp(), bytes, Bytes.empty, 0, 36, 42).isLeft)
    assert(NativeSeedParameters.decode(pp(), pp(), bytes, sha(raw("{}")), 0, 36, 42).isLeft)
    assert(decode(epoch = 1).isLeft)
    assert(decode(slot = -1).isLeft)
    assert(decode(magic = 43).isLeft)
    assert(decode(g = genesis().replace("Testnet", "Mainnet")).isLeft)
    assertEquals(get(decode(epoch = 1, slot = 1000)).epoch, BigInt(1))
  }
  test("missing/extra genesis fields, duplicate keys and synthetic window overrides rejected") {
    assert(decode(g = genesis().replace("\"securityParam\":5,", "")).isLeft)
    assert(decode(g = genesis().dropRight(1) + ",\"randomnessWindow\":100}").isLeft)
    assert(decode(g = genesis().dropRight(1) + ",\"networkMagic\":42}").isLeft)
    assert(decode(g = genesis().dropRight(1) + ",\"extraConfig\":{}}").isRight)
    assert(decode(g = genesis().dropRight(1) + ",\"extraConfig\":null}").isRight)
  }
  test("decimal and derived uint64 bounds fail before globals construction") {
    Vector("0", "-0.05", "1.1", "1e-1000", "1e1000").foreach { f =>
      assert(decode(g = genesis(f = f)).isLeft)
    }
    assert(decode(g = genesis(slot = "0")).isLeft)
    assert(decode(g = genesis(slot = "0.1000001")).isLeft)
    assert(decode(g = genesis(epoch = 0)).isLeft)
    assert(
      decode(g =
        genesis().replace("\"securityParam\":5", "\"securityParam\":18446744073709551615")
      ).isLeft
    )
  }

  private val injectionPoolId = "11" * 28
  private val injectionCredential = "22" * 28
  private def embeddedPool: String =
    s"""{"poolId":"$injectionPoolId","vrf":"${"33" * 32}","pledge":0,"cost":0,
      |"margin":0,"accountAddress":{"network":"Testnet","credential":{"keyHash":"$injectionCredential"}},
      |"owners":[],"relays":[],"metadata":null}""".stripMargin
  private def injections: String =
    s"""{"initialFunds":{"data":{"60$injectionCredential":100}},
      |"stakeCredentials":{"data":{"$injectionCredential":"$injectionPoolId"}},
      |"stakePools":{"data":{"$injectionPoolId":$embeddedPool}}}""".stripMargin
  private def withExtra(extra: String): String =
    genesis().dropRight(1) + ",\"extraConfig\":" + extra + "}"

  test(
    "synthetic embedded extraConfig binds original bytes without asserting injection/admission"
  ) {
    val g = withExtra(injections)
    val p = get(decode(g = g))
    assertEquals(p.genesisOriginal, raw(g))
    assertEquals(p.genesisSHA256, sha(raw(g)))
    assertEquals(p.randomnessWindow, BigInt(400))
    assert(!p.rewardSeedAdmission && !p.runtimeImport && !p.nativeConformance)
    val changed = withExtra(injections.replace(":100}", ":101}"))
    assertNotEquals(get(decode(g = changed)).bindingId, p.bindingId)
    assert(NativeSeedParameters.decode(pp(), pp(), raw(changed), sha(raw(g)), 0, 36, 42).isLeft)
    assert(decode(g = withExtra("""{"initialFunds":{},"stakePools":null}""")).isRight)
  }
  test(
    "embedded injection wrapper rejects unresolved files, unknown fields and malformed sources"
  ) {
    Vector(
      """{"initialFunds":{"file":["private","funds.json"],"hash":"00"}}""",
      """{"initialFunds":{"file":["private"],"data":{}}}""",
      """{"initialFunds":{"data":{},"extra":0}}""",
      """{"initialFunds":{"data":[]}}""",
      """{"initialFunds":{"hash":"00"}}""",
      """{"unknown":{}}"""
    ).foreach(extra => assert(decode(g = withExtra(extra)).isLeft))
  }
  test("embedded injection structural mutations reject hash, network, amount and pool mismatches") {
    Vector(
      injections.replace("60" + injectionCredential, "61" + injectionCredential),
      injections.replace(":100}", ":-1}"),
      injections.replace(":100}", ":18446744073709551616}"),
      injections.replace("\"poolId\":\"" + injectionPoolId, "\"poolId\":\"" + ("44" * 28)),
      injections.replace("\"margin\":0", "\"margin\":1.1"),
      injections.replace("\"network\":\"Testnet\"", "\"network\":\"Mainnet\""),
      injections.replace("\"keyHash\":\"" + injectionCredential, "\"keyHash\":\"22"),
      injections.replace("\"relays\":[]", "\"relays\":[{}]"),
      injections.replace("\"metadata\":null", "\"metadata\":{}"),
      injections.replace(
        "\"owners\":[]",
        s""""owners":["$injectionCredential","$injectionCredential"]"""
      ),
      injections.replace("\"cost\":0", "\"cost\":0,\"extra\":0")
    ).foreach(extra => assert(decode(g = withExtra(extra)).isLeft))
  }
  test("embedded injection map entry cap is enforced") {
    val entries = (0 to 1024)
      .map { i =>
        val key = i.toHexString.reverse.padTo(56, '0').reverse
        s""" "$key":"$injectionPoolId" """
      }
      .mkString(",")
    assert(decode(g = withExtra(s"""{"stakeCredentials":{"data":{$entries}}}""")).isLeft)
  }
  sys.env.get("NATIVE_SEED_EFFECTIVE_GENESIS").foreach { path =>
    test("opt-in exact retained effective genesis compatibility; synthetic parameter inputs only") {
      val bytes = Bytes.fromArray(java.nio.file.Files.readAllBytes(java.nio.file.Path.of(path)))
      val pin =
        get(Bytes.fromHex("c9c11cde61bdd2536c5a328395ba4f873554ba018d9675297e5329a09e9294a9"))
      assertEquals(sha(bytes), pin)
      val p = get(
        NativeSeedParameters.decode(
          NativeSeedParameterFixtures.previous,
          NativeSeedParameterFixtures.current,
          bytes,
          pin,
          0,
          36,
          1082026
        )
      )
      assertEquals(p.genesisOriginal, bytes)
      assertEquals(p.globals.epochLength, BigInt(500))
      assertEquals(p.globals.maxSupply, BigInt("100000020000000"))
      assertEquals(p.randomnessWindow, BigInt(400))
      assert(
        !p.timingProfileCompatible && !p.runtimeImport && !p.rewardSeedAdmission &&
          !p.nativeConformance
      )
    }
  }

/** Shared hand-built fixtures for diagnostic transport tests, not native output. */
private[lab] object NativeSeedParameterFixtures:
  private def get[A](e: Either[String, A]): A =
    e.fold(s => throw new IllegalArgumentException(s), identity)
  private def n(v: V) = Node(v, Bytes.empty)
  private def a(vs: V*) = V.Arr(vs.toVector.map(n))
  private def r(num: BigInt, den: BigInt): V = V.Tag(30, n(a(V.UInt(num), V.UInt(den))))
  private def parameters(rho: V, tau: V): Bytes =
    val fields = Vector(
      V.UInt(44),
      V.UInt(155381),
      V.UInt(90112),
      V.UInt(16384),
      V.UInt(1100),
      V.UInt(0),
      V.UInt(0),
      V.UInt(18),
      V.UInt(500),
      r(3, 10),
      rho,
      tau,
      a(V.UInt(9), V.UInt(0)),
      V.UInt(170000000),
      V.UInt(4310),
      V.Map(Vector.empty),
      a(r(1, 10), r(1, 100)),
      a(V.UInt(1000), V.UInt(2000)),
      a(V.UInt(3000), V.UInt(4000)),
      V.UInt(5000),
      V.UInt(150),
      V.UInt(3),
      a(Vector.fill(5)(r(1, 2))*),
      a(Vector.fill(10)(r(1, 2))*),
      V.UInt(0),
      V.UInt(10),
      V.UInt(6),
      V.UInt(0),
      V.UInt(0),
      V.UInt(20),
      r(15, 1)
    )
    get(Cbor.encode(V.Arr(fields.map(n))))
  def previous: Bytes = parameters(r(3, 1000), r(1, 5))
  def current: Bytes = parameters(r(1, 100), r(1, 4))
  def genesis: Bytes = Bytes.fromArray(
    """{"systemStart":"2026-10-09T00:00:00Z","networkMagic":1082026,"networkId":"Testnet",
      |"activeSlotsCoeff":0.05,"securityParam":5,"epochLength":1000,
      |"slotsPerKESPeriod":100,"maxKESEvolutions":62,"slotLength":0.1,"updateQuorum":2,
      |"maxLovelaceSupply":45000000000000000,"protocolParams":{},"genDelegs":{},"initialFunds":{},
      |"staking":{"pools":{},"stake":{}}}""".stripMargin.getBytes("UTF-8")
  )
