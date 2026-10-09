// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import lab.ledger.{ConwayEmptyGovernance as G, ConwayStake as S}

/** Generated semantic mutations; no retained endpoint data or native execution. */
class NativeEndpointGovernanceSuite extends NativeLedgerSeedFixtures:
  private lazy val applied = get(G.applyBoundary(get(bundle().join).governanceInput, 1))
  private lazy val components = get(NativeEndpointGovernance.expectedComponents(applied))
  private def accepted(values: Map[String, Bytes]) =
    NativeEndpointGovernance.compareComponents(values, applied)
  private def mutate(name: String, path: Vector[Int], replacement: V) =
    components.updated(
      name,
      get(Cbor.encode(change(get(Cbor.decode(components(name))).value, path, replacement)))
    )

  test("normalized fresh empty-proposal serialization preserves four parameter originals") {
    assertEquals(accepted(components), Right(()))
    assertEquals(components("futureParameters").hex, "820280")
    assertEquals(components("currentParameters"), applied.parameters.current.original)
    assertEquals(components("previousParameters"), applied.parameters.previous.original)
    assertEquals(applied.fresh.index, 0)
    assert(applied.fresh.drepDistribution.isEmpty)
  }
  test(
    "completed distribution accumulates rewards and instantaneous stake without expiry filtering"
  ) {
    val c1 = S.Credential(false, bytes(28, 81))
    val c2 = S.Credential(false, bytes(28, 82))
    val c3 = S.Credential(false, bytes(28, 83))
    val c4 = S.Credential(false, bytes(28, 84))
    val c5 = S.Credential(false, bytes(28, 85))
    val registered = S.Credential(false, bytes(28, 91))
    val unknown = S.Credential(false, bytes(28, 92))
    def account(reward: Int, vote: G.Vote) = G.Account(reward, 0, None, Some(vote))
    val fresh = applied.fresh.copy(
      accounts = Map(
        c1 -> account(2, G.Vote.Credential(registered)),
        c2 -> account(3, G.Vote.Credential(registered)),
        c3 -> account(5, G.Vote.AlwaysAbstain),
        c4 -> account(7, G.Vote.AlwaysNoConfidence),
        c5 -> account(100, G.Vote.Credential(unknown))
      ),
      instantaneous = Map(c1 -> BigInt(11), c2 -> BigInt(13), c3 -> BigInt(17), c4 -> BigInt(19)),
      dreps = Map(registered -> G.DRepState(0, None, 0, Set(c1, c2)))
    )
    assertEquals(
      get(NativeEndpointGovernance.completedDistribution(fresh)),
      Map(
        G.Vote.Credential(registered) -> BigInt(29),
        G.Vote.AlwaysAbstain -> BigInt(22),
        G.Vote.AlwaysNoConfidence -> BigInt(26)
      )
    )
    assert(NativeEndpointGovernance.completedDistribution(fresh.copy(index = 1)).isLeft)
    assert(
      NativeEndpointGovernance
        .completedDistribution(fresh.copy(proposalDeposits = Map(c1 -> BigInt(1))))
        .isLeft
    )
  }
  test("outer votes dreps dormant committee constitution and proposal differences are rejected") {
    for name <- Vector("accounts", "dreps", "committeeState") do
      assert(accepted(components.updated(name, raw(m(b(28, 44) -> V.UInt(1))))).isLeft, name)
    assert(accepted(mutate("dormantEpochs", Vector.empty, V.UInt(applied.dormant + 1))).isLeft)
    assert(accepted(mutate("committee", Vector.empty, a())).isLeft)
    assert(accepted(mutate("constitution", Vector(0, 0), V.Text("changed"))).isLeft)
    assert(accepted(mutate("proposals", Vector(1), a(V.UInt(0)))).isLeft)
    assert(accepted(mutate("futureParameters", Vector.empty, a(V.UInt(0)))).isLeft)
  }
  test("completed snapshot distribution registrations and pool stake must match") {
    for index <- Vector(1, 2, 3) do
      assert(accepted(mutate("drepPulsingState", Vector(0, index), m())).isLeft, index.toString)
    assert(accepted(mutate("drepPulsingState", Vector(0, 0), a(V.UInt(0)))).isLeft)
  }
  test("ratification enactment parameters treasury actions expired and delay are checked") {
    assert(
      accepted(
        mutate("drepPulsingState", Vector(1, 0, 4), V.UInt(applied.fresh.enact.treasury + 1))
      ).isLeft
    )
    assert(accepted(mutate("drepPulsingState", Vector(1, 1), a(V.UInt(0)))).isLeft)
    assert(accepted(mutate("drepPulsingState", Vector(1, 3), V.Bool(true))).isLeft)
    assert(accepted(mutate("drepPulsingState", Vector(1, 2), a())).isLeft)
    assert(
      accepted(mutate("currentParameters", Vector(0), V.UInt(999))).isLeft
    )
  }
  test("duplicate semantic map keys and set entries cannot disappear during normalization") {
    val accounts = get(Cbor.decode(components("accounts"))).value.asInstanceOf[V.Map].value
    val duplicate = raw(V.Map(accounts :+ accounts.head))
    assert(accepted(components.updated("accounts", duplicate)).left.exists(_.contains("duplicate")))
    val ds = get(Cbor.decode(components("dreps"))).value.asInstanceOf[V.Map].value
    val record = ds.head._2.value.asInstanceOf[V.Arr].value
    val inner = record(3).value.asInstanceOf[V.Tag].value.value.asInstanceOf[V.Arr].value
    val doubled = V.Tag(258, Node(V.Arr(inner :+ inner.head), Bytes.empty))
    val badRecord = Node(V.Arr(record.updated(3, Node(doubled, Bytes.empty))), Bytes.empty)
    val bad = raw(V.Map(ds.updated(0, ds.head._1 -> badRecord)))
    assert(accepted(components.updated("dreps", bad)).left.exists(_.contains("duplicate")))
  }
  test("missing oversized and malformed components fail before equality claims") {
    assert(accepted(components - "accounts").isLeft)
    assert(accepted(components.updated("accounts", Bytes(Vector.fill(65537)(0.toByte)))).isLeft)
    assert(
      accepted(components.updated("dreps", Bytes(components("dreps").value :+ 0.toByte))).isLeft
    )
    assert(NativeEndpointGovernance.compare(null, null).isLeft)
  }

  test("semantically equal re-encoding cannot replace exact parameter original spans") {
    val current = components("currentParameters")
    assertEquals(current.value.take(2), Vector(0x98.toByte, 0x1f.toByte))
    val wider = Bytes(Vector(0x99.toByte, 0.toByte, 0x1f.toByte) ++ current.value.drop(2))
    assert(
      accepted(components.updated("currentParameters", wider)).left
        .exists(_.contains("outer parameter original"))
    )
  }
