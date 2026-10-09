// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Node, Value as V}
import ConwayEmptyGovernance as G
import ConwayStake as S
import scala.compiletime.testing.typeCheckErrors

class ConwayEmptyGovernanceSuite extends munit.FunSuite:
  private def get[A](e: Either[?, A]): A = e.fold(e => fail(e.toString), identity)
  private def bytes(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
  private def cred(n: Int) = S.Credential(false, bytes(n, 28))
  private def payload(n: Int) = get(G.payload(get(Cbor.encode(V.UInt(n)))))
  private val max = (BigInt(1) << 64) - 1
  private val pool = bytes(60, 28)
  private val anchor = G.Anchor("https://example.invalid/governance", bytes(61))
  private val cold = cred(62); private val removed = cred(63); private val drep = cred(64)
  private val roots = G.Purpose.values.map(_ -> Option.empty[G.ActionId]).toMap
  private def fixture(count: Int = 7): G.Input =
    val accounts = (1 to count).map { n =>
      cred(n) -> G.Account(
        n,
        2,
        Some(pool),
        Some(if n == 1 then G.Vote.AlwaysAbstain else G.Vote.Credential(drep))
      )
    }.toMap
    val ds = Map(drep -> G.DRepState(3, Some(anchor), 5, accounts.keySet))
    val committee = Some(G.Committee(Map(cold -> BigInt(1)), S.Ratio(2, 3)))
    val constitution = G.Constitution(anchor, Some(bytes(65, 28)))
    val parameters = G.Parameters(payload(1), payload(2), G.FutureParameters.NoUpdate)
    val enact =
      G.Enact(committee, constitution, parameters.current, parameters.previous, 0, Map.empty, roots)
    // Historical completed registry intentionally differs from the current one.
    val old = G.CompletedSnapshot(
      Vector.empty,
      Map(G.Vote.AlwaysNoConfidence -> BigInt(99)),
      Map(cred(66) -> G.DRepState(0, None, 1, Set.empty)),
      Map(pool -> BigInt(100))
    )
    val pools = Map(pool -> G.Pool(payload(3), 7))
    val pd =
      if count == 0 then G.PoolDistribution(1, Map.empty)
      else G.PoolDistribution(100, Map(pool -> G.PoolShare(100, S.Ratio(1, 1), bytes(67))))
    G.Input(
      9,
      4,
      ds,
      committee,
      Map(cold -> G.Authorization.Hot(cred(68)), removed -> G.Authorization.Resigned(Some(anchor))),
      constitution,
      parameters,
      roots,
      Map.empty,
      G.OldDRep.Complete(old, G.Ratify(enact, Vector.empty, Set.empty, false)),
      accounts,
      accounts.keys.map(_ -> BigInt(10)).toMap,
      pd,
      pools,
      Map.empty,
      Map.empty,
      Map.empty,
      0,
      1234,
      G.Deposits(
        accounts.map((c, a) => c -> a.deposit),
        Map(pool -> BigInt(7)),
        Map(drep -> BigInt(5)),
        Map.empty,
        count * 2 + 12
      ),
      G.Globals(1, payload(4))
    )
  private def legacyGlobals(i: G.Input): G.Globals = i.globals match
    case g: G.Globals => g
    case _            => fail("legacy fixture globals required")
  private def changedRatify(i: G.Input)(f: G.Ratify => G.Ratify): G.Input = i.oldDRep match
    case G.OldDRep.Complete(s, r) => i.copy(oldDRep = G.OldDRep.Complete(s, f(r)))
    case _                        => fail("fixture phase")

  test("empty governance advances dormant/parameters and captures complete fresh DRep inputs") {
    val i = fixture(); val a = get(G.applyBoundary(i, 10))
    assertEquals(a.dormant, BigInt(5)); assertEquals(a.epoch, BigInt(10))
    assertEquals(a.committeeState.keySet, Set(cold))
    // Membership expiration is not a criterion of updateCommitteeState's intersection.
    assert(a.committee.get.members(cold) < a.epoch)
    assert(a.parameters.current eq i.parameters.current)
    assert(a.parameters.previous eq i.parameters.current)
    assert(a.before.parameters.previous eq i.parameters.previous)
    assertEquals(a.parameters.future, G.FutureParameters.PotentialNone)
    assertEquals(a.dreps, i.dreps); assertEquals(a.dreps(drep).expiry, BigInt(3))
    assertEquals(a.fresh.accounts, i.accounts); assertEquals(a.fresh.instantaneous, i.instantaneous)
    assertEquals(a.fresh.stakePoolDistribution, i.newMarkPoolDistribution)
    assertEquals(a.fresh.stakePools, i.stakePools); assertEquals(a.fresh.globals, i.globals)
    assertEquals(a.fresh.dreps, i.dreps); assertEquals(a.fresh.committeeState, a.committeeState)
    assertEquals(a.fresh.enact.treasury, BigInt(1234))
    assertEquals(a.fresh.enact.roots, roots)
    assertEquals(a.fresh.enact.current.original, i.parameters.current.original)
    assertEquals(a.fresh.enact.previous.original, i.parameters.current.original)
    assertEquals(a.deposits, i.deposits); assertEquals(a.treasury, i.treasury)
    assertEquals(a.fresh.index, 0); assertEquals(a.fresh.pulseSize, 1) // floor(7/4), not ceil.
    assert(
      a.fresh.drepDistribution.isEmpty && a.fresh.proposals.isEmpty && a.fresh.proposalDeposits.isEmpty
    )
    assertEquals(a.before, i)
    assert(
      a.syntheticOnly && !a.nativePayloadsValidated && !a.nativeEquivalent && !a.epochTransitionValidated && !a.published
    )
  }
  test("fresh pulse size floors with minimum one and dormant progresses even with no accounts") {
    for (count, chunk) <- Vector(0 -> 1, 3 -> 1, 7 -> 1, 8 -> 2, 9 -> 2, 11 -> 2) do
      val i = fixture(count); val a = get(G.applyBoundary(i, 10))
      assertEquals(a.fresh.pulseSize, chunk); assertEquals(a.dormant, BigInt(5))
    val i = fixture()
    assertEquals(
      get(
        G.applyBoundary(i.copy(globals = legacyGlobals(i).copy(securityParameter = max)), 10)
      ).fresh.pulseSize,
      1
    )
    assertEquals(
      get(
        G.applyBoundary(
          i.copy(
            committee = None,
            oldDRep =
              (changedRatify(i)(r => r.copy(enact = r.enact.copy(committee = None)))).oldDRep
          ),
          10
        )
      ).committeeState,
      Map.empty[S.Credential, G.Authorization]
    )
  }
  test("reject unknown/pulsing or pending old ratification even with empty current proposals") {
    val i = fixture(); val action = G.ActionId(bytes(70), 0)
    assert(G.applyBoundary(i.copy(oldDRep = G.OldDRep.Unknown), 10).isLeft)
    assert(G.applyBoundary(i.copy(oldDRep = G.OldDRep.Pulsing), 10).isLeft)
    Vector(
      changedRatify(i)(_.copy(delayed = true)),
      changedRatify(i)(_.copy(enacted = Vector(action))),
      changedRatify(i)(_.copy(expired = Set(action))),
      changedRatify(i)(r => r.copy(enact = r.enact.copy(treasury = 1))),
      changedRatify(i)(r => r.copy(enact = r.enact.copy(withdrawals = Map(cred(1) -> BigInt(1)))))
    ).foreach(x => assert(G.applyBoundary(x, 10).isLeft))
    val G.OldDRep.Complete(snapshot, ratify) = i.oldDRep: @unchecked
    assert(
      G.applyBoundary(
        i.copy(oldDRep = G.OldDRep.Complete(snapshot.copy(proposals = Vector(action)), ratify)),
        10
      ).isLeft
    )
    assert(G.applyBoundary(i.copy(proposals = Map(action -> payload(1))), 10).isLeft)
  }
  test(
    "reject omitted effect support: membership/constitution/parameter/root changes and donations"
  ) {
    val i = fixture()
    Vector(
      changedRatify(i)(r => r.copy(enact = r.enact.copy(committee = None))),
      changedRatify(i)(r =>
        r.copy(enact = r.enact.copy(constitution = i.constitution.copy(script = None)))
      ),
      changedRatify(i)(r => r.copy(enact = r.enact.copy(current = payload(9)))),
      changedRatify(i)(r => r.copy(enact = r.enact.copy(previous = payload(9)))),
      changedRatify(i)(r =>
        r.copy(enact =
          r.enact.copy(roots = roots.updated(G.Purpose.Parameters, Some(G.ActionId(bytes(80), 0))))
        )
      ),
      i.copy(parameters = i.parameters.copy(future = G.FutureParameters.Pending(payload(9)))),
      i.copy(donations = 1),
      i.copy(poolUpdates = i.stakePools),
      i.copy(retirements = Map(pool -> BigInt(10))),
      i.copy(proposalDeposits = Map(cred(1) -> BigInt(1)))
    ).foreach(x => assert(G.applyBoundary(x, 10).isLeft))
    assert(
      G.applyBoundary(
        i.copy(parameters = i.parameters.copy(future = G.FutureParameters.PotentialNone)),
        10
      ).isRight
    )
  }
  test("validate obligation component maps, distribution fractions and all bounded fields") {
    val i = fixture()
    Vector(
      i.copy(deposits = i.deposits.copy(total = i.deposits.total + 1)),
      i.copy(deposits = i.deposits.copy(dreps = Map.empty)),
      i.copy(deposits = i.deposits.copy(pools = Map.empty)),
      i.copy(deposits = i.deposits.copy(stake = Map.empty)),
      i.copy(dormant = max),
      i.copy(epoch = max),
      i.copy(roots = Map.empty),
      i.copy(treasury = -1),
      i.copy(globals = legacyGlobals(i).copy(securityParameter = 0)),
      i.copy(newMarkPoolDistribution = i.newMarkPoolDistribution.copy(total = 101)),
      i.copy(dreps = i.dreps.updated(drep, i.dreps(drep).copy(expiry = -1)))
    ).foreach(x => assert(G.applyBoundary(x, 10).isLeft))
    assert(G.applyBoundary(i, 9).isLeft); assert(G.applyBoundary(i, 11).isLeft)
  }
  test("payloads are exact canonical bounded bytes; identities include full historical snapshot") {
    assert(G.payload(Bytes(Vector(0x18.toByte, 0.toByte))).isLeft)
    assert(G.payload(Bytes(Vector(0xa2.toByte, 1.toByte, 0.toByte, 0.toByte, 0.toByte))).isLeft)
    assert(G.payload(Bytes(Vector(0xa2.toByte, 0.toByte, 0.toByte, 0.toByte, 1.toByte))).isLeft)
    assert(G.payload(Bytes(Vector.fill(G.MaxPayloadBytes + 1)(0.toByte))).isLeft)
    val i = fixture(); val a = get(G.applyBoundary(i, 10))
    val reordered = i.copy(
      accounts = i.accounts.toVector.reverse.toMap,
      committeeState = i.committeeState.toVector.reverse.toMap
    )
    assertEquals(get(G.applyBoundary(reordered, 10)).id, a.id)
    val G.OldDRep.Complete(snapshot, ratify) = i.oldDRep: @unchecked
    val historical = i.copy(oldDRep =
      G.OldDRep.Complete(
        snapshot.copy(drepDistribution = Map(G.Vote.AlwaysNoConfidence -> BigInt(98))),
        ratify
      )
    )
    assertNotEquals(get(G.applyBoundary(historical, 10)).id, a.id)
    assertNotEquals(get(G.applyBoundary(i.copy(dormant = 5), 10)).id, a.id)
    assert(
      typeCheckErrors("new lab.ledger.ConwayEmptyGovernance.Payload(lab.cbor.Bytes.empty)").nonEmpty
    )
  }

  test("global integer/string and aggregate byte bounds fail before identity construction") {
    val i = fixture()
    assert(
      G.applyBoundary(
        i.copy(globals = legacyGlobals(i).copy(securityParameter = BigInt(1) << 4096)),
        10
      ).isLeft
    )
    assert(
      G.applyBoundary(
        i.copy(constitution =
          i.constitution.copy(anchor = anchor.copy(url = "x" * (G.MaxPayloadBytes + 1)))
        ),
        10
      ).isLeft
    )
    val large = get(G.payload(get(Cbor.encode(V.ByteString(Bytes(Vector.fill(4096)(0.toByte)))))))
    val many = (0 until 300).map { n =>
      val key = Bytes(Vector.fill(26)(0.toByte) ++ Vector((n >>> 8).toByte, n.toByte))
      key -> G.Pool(large, 0)
    }.toMap
    assert(G.applyBoundary(i.copy(stakePools = many), 10).isLeft)
  }

  test(
    "reject malformed anchor Unicode and credential distributions absent from historical registry"
  ) {
    val i = fixture()
    val malformed = new String(Array(0xd800.toChar))
    assert(
      G.applyBoundary(
        i.copy(constitution = i.constitution.copy(anchor = anchor.copy(url = malformed))),
        10
      ).isLeft
    )
    val G.OldDRep.Complete(snapshot, ratify) = i.oldDRep: @unchecked
    val bad = snapshot.copy(drepDistribution = Map(G.Vote.Credential(drep) -> BigInt(1)))
    assert(G.applyBoundary(i.copy(oldDRep = G.OldDRep.Complete(bad, ratify)), 10).isLeft)
    val good = snapshot.copy(drepDistribution = Map(G.Vote.Credential(cred(66)) -> BigInt(1)))
    assert(G.applyBoundary(i.copy(oldDRep = G.OldDRep.Complete(good, ratify)), 10).isRight)
  }

  test("typed fixed globals preserve source identity and enforce construction bounds") {
    assert(G.suppliedFixedGlobals(0, bytes(1)).isLeft)
    assert(G.suppliedFixedGlobals(-1, bytes(1)).isLeft)
    assert(G.suppliedFixedGlobals(max + 1, bytes(1)).isLeft)
    assert(G.suppliedFixedGlobals(1, bytes(1, 31)).isLeft)
    assert(G.suppliedFixedGlobals(1, null).isLeft)
    val supplied = get(G.suppliedFixedGlobals(1, bytes(91)))
    val input = fixture().copy(globals = supplied)
    val applied = get(G.applyBoundary(input, 10))
    assertEquals(applied.fresh.globals, input.globals)
    assertEquals(applied.fresh.globals.securityParameter, BigInt(1))
    assertEquals(applied.fresh.pulseSize, 1)
    val other = get(G.suppliedFixedGlobals(1, bytes(92)))
    assertNotEquals(applied.id, get(G.applyBoundary(input.copy(globals = other), 10)).id)
    assertNotEquals(applied.id, get(G.applyBoundary(fixture(), 10)).id)
    assert(!applied.nativePayloadsValidated && !applied.nativeEquivalent)
  }
