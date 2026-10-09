// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.ConwayStake as S
import scala.compiletime.testing.typeCheckErrors

class GovernanceGlobalsSuite extends munit.FunSuite:
  private val G = GovernanceGlobals
  private val F = GovernanceGlobalsFixtures
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def raw(s: String) = Bytes.fromArray(s.getBytes("UTF-8"))
  private def sha(b: Bytes) = ClusterHeaderObservation.sha256(b)
  private def prepare(genesis: Bytes = F.genesis, epoch: BigInt = 0, slot: BigInt = 36) =
    get(
      NativeSeedParameters.decode(
        F.previous,
        F.current,
        genesis,
        sha(genesis),
        epoch,
        slot,
        1082026
      )
    )
  private def bind(p: NativeSeedParameters.Prepared) = get(
    G.bind(p, p.bindingId, p.epoch, p.pointSlot, p.networkMagic)
  )
  private def changed(from: String, to: String): Bytes = raw(
    new String(F.genesis.toArray, "UTF-8").replace(from, to)
  )

  test("typed fixed-epoch projection retains every scalar source and explicit non-native claims") {
    val seed = prepare(); val g = bind(seed)
    assertEquals(g.geometry.epochLength, BigInt(1000));
    assertEquals(g.geometry.slotLength, S.Ratio(1, 10))
    assertEquals(g.slotsPerKESPeriod, BigInt(100)); assertEquals(g.maxKESEvolutions, BigInt(62))
    assertEquals(g.securityParameter, BigInt(5)); assertEquals(g.quorum, BigInt(2))
    assertEquals(g.stabilityWindow, BigInt(300));
    assertEquals(g.randomnessStabilisationWindow, BigInt(400))
    assertEquals(g.activeSlotCoefficient, S.Ratio(1, 20));
    assertEquals(g.maxLovelaceSupply, BigInt("45000000000000000"))
    assertEquals(g.systemStart.toString, "2026-10-09T00:00:00Z")
    assertEquals(g.network, G.Network.Testnet); assertEquals(g.networkMagic, BigInt(1082026))
    assertEquals(g.epoch, BigInt(0)); assertEquals(g.pointSlot, BigInt(36))
    assertEquals(g.sourceBindingId, seed.bindingId); assertEquals(g.genesisOriginal, F.genesis)
    assertEquals(g.genesisSHA256, sha(F.genesis)); assert(g.rewardGlobals eq seed.globals)
    assertEquals(
      G.checkProjection(
        g,
        seed.globals,
        seed.slotLength,
        seed.stabilityWindow,
        seed.randomnessWindow
      ),
      Right(())
    )
    assert(
      g.suppliedFixedEpochProfile && !g.nativeWireFormat && !g.nativeGlobalsDecoded &&
        !g.epochInfoAuthenticated && !g.cachedActiveSlotLogValidated && !g.nativeConformance &&
        !g.runtimeAdmission && !g.legacyGovernancePayloadAdmitted
    )
  }
  test("source identity and expected epoch point network cannot be substituted") {
    val p = prepare()
    assert(G.bind(p, Bytes(Vector.fill(32)(1.toByte)), 0, 36, 1082026).isLeft)
    assert(G.bind(p, p.bindingId, 1, 36, 1082026).isLeft)
    assert(G.bind(p, p.bindingId, 0, 37, 1082026).isLeft)
    assert(G.bind(p, p.bindingId, 0, 36, 42).isLeft)
    assert(G.bind(p, p.bindingId, -1, 36, 1082026).isLeft)
    assert(G.bind(null, p.bindingId, 0, 36, 1082026).isLeft)
    assertNotEquals(bind(prepare(slot = 37)).id, bind(p).id)
    assertNotEquals(bind(prepare(epoch = 1, slot = 1000)).id, bind(p).id)
  }
  test(
    "opaque genesis-only fields remain identity bound even when reward projection is unchanged"
  ) {
    val p = prepare(); val a = bind(p)
    Vector(
      ("\"slotsPerKESPeriod\":100", "\"slotsPerKESPeriod\":101"),
      ("\"maxKESEvolutions\":62", "\"maxKESEvolutions\":63"),
      ("\"updateQuorum\":2", "\"updateQuorum\":3"),
      ("2026-10-09T00:00:00Z", "2026-10-10T00:00:00Z"),
      ("\"slotLength\":0.1", "\"slotLength\":0.2")
    ).foreach { (from, to) =>
      val b = bind(prepare(changed(from, to)))
      assertEquals(b.rewardGlobals.id, a.rewardGlobals.id)
      assertNotEquals(b.id, a.id); assertNotEquals(b.genesisSHA256, a.genesisSHA256)
    }
  }
  test("reward and exact timing checks reject altered projection fields") {
    val p = prepare(); val a = bind(p)
    val other = prepare(changed("\"activeSlotsCoeff\":0.05", "\"activeSlotsCoeff\":0.3"))
    assertEquals(bind(other).randomnessStabilisationWindow, BigInt(67))
    assert(
      G.checkProjection(a, other.globals, p.slotLength, p.stabilityWindow, p.randomnessWindow)
        .isLeft
    )
    assert(
      G.checkProjection(a, p.globals, S.Ratio(1, 5), p.stabilityWindow, p.randomnessWindow).isLeft
    )
    assert(
      G.checkProjection(a, p.globals, p.slotLength, p.stabilityWindow + 1, p.randomnessWindow)
        .isLeft
    )
    assert(
      G.checkProjection(a, p.globals, p.slotLength, p.stabilityWindow, p.randomnessWindow + 1)
        .isLeft
    )
    assert(G.checkHeader(a, EphemeralStreamingFixture.context).isLeft)
    assert(G.checkHeader(a, null).isLeft)
    assert(typeCheckErrors("new lab.GovernanceGlobals.Checked(null)").nonEmpty)
  }

  test("exact full supplied genesis also binds every exposed generated header-context field") {
    val genesis = raw(
      new String(F.genesis.toArray, "UTF-8")
        .replace("\"epochLength\":1000", "\"epochLength\":40")
        .replace("\"activeSlotsCoeff\":0.05", "\"activeSlotsCoeff\":1")
        .replace("\"securityParam\":5", "\"securityParam\":1")
        .replace("\"slotsPerKESPeriod\":100", "\"slotsPerKESPeriod\":1000")
        .replace("\"maxKESEvolutions\":62", "\"maxKESEvolutions\":64")
    )
    val files = EphemeralStreamingFixture.context.originals.updated("transfer-genesis.md", genesis)
    val manifest = raw(
      "format\t" + SequenceInput.ProfileId + "\n" +
        SequenceInput.sources.toVector
          .sortBy(_._1)
          .map((key, file) => key + "\t" + sha(files(file)).hex)
          .mkString("\n") + "\n"
    )
    val header = get(SequenceInput.bind(manifest, files))
    val prepared = get(
      NativeSeedParameters.decode(
        F.previous,
        F.current,
        genesis,
        sha(genesis),
        header.epoch,
        header.ledger.slot,
        1082026
      )
    )
    val typed = bind(prepared)
    assertEquals(G.checkHeader(typed, header), Right(()))
    assertEquals(typed.geometry.epochLength, BigInt(40))
    assertEquals(typed.randomnessStabilisationWindow, BigInt(4))
    assert(G.checkHeader(typed, EphemeralStreamingFixture.context).isLeft)
  }

/** Supplied transport/shape data; not native conformance evidence. */
private[lab] object GovernanceGlobalsFixtures:
  private def get[A](e: Either[?, A]): A =
    e.fold(e => throw new IllegalArgumentException(e.toString), identity)
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
  private def genesisText(epoch: Int = 1000, f: String = "0.05", slot: String = "0.1"): String =
    s"""{"systemStart":"2026-10-09T00:00:00Z","networkMagic":1082026,"networkId":"Testnet",
       |"activeSlotsCoeff":$f,"securityParam":5,"epochLength":$epoch,
       |"slotsPerKESPeriod":100,"maxKESEvolutions":62,"slotLength":$slot,"updateQuorum":2,
       |"maxLovelaceSupply":45000000000000000,"protocolParams":{},"genDelegs":{},"initialFunds":{},
       |"staking":{"pools":{},"stake":{}}}""".stripMargin
  val previous: Bytes = pp()
  val current: Bytes = pp(full(ratio(1, 100), ratio(1, 4)))
  val genesis: Bytes = Bytes.fromArray(genesisText().getBytes("UTF-8"))
