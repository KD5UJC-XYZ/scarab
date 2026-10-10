// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Value as V}
import ConwayEmptyGovernance as G
import ConwayEmptyDRepCompletion as C
import ConwayStake as S

class ConwayEmptyDRepCompletionSuite extends munit.FunSuite:
  private def get[A](value: Either[?, A]): A = value.fold(e => fail(e.toString), identity)
  private def bytes(n: Int, size: Int = 32) = Bytes(Vector.fill(size)(n.toByte))
  private def credential(n: Int) = S.Credential(false, bytes(n, 28))
  private def payload(n: Int) = get(G.payload(get(Cbor.encode(V.UInt(n)))))
  private val pool = bytes(1, 28)
  private val zeroPool = bytes(2, 28)
  private val account = credential(3)
  private val roots = G.Purpose.values.map(_ -> Option.empty[G.ActionId]).toMap
  private def fixture: G.Input =
    val parameters = G.Parameters(payload(1), payload(2), G.FutureParameters.NoUpdate)
    val committee = Some(G.Committee(Map(credential(4) -> BigInt(20)), S.Ratio(1, 2)))
    val constitution =
      G.Constitution(G.Anchor("https://example.invalid", bytes(5)), Some(bytes(6, 28)))
    val enact =
      G.Enact(committee, constitution, parameters.current, parameters.previous, 0, Map.empty, roots)
    G.Input(
      9,
      2,
      Map.empty,
      committee,
      Map.empty,
      constitution,
      parameters,
      roots,
      Map.empty,
      G.OldDRep.Complete(
        G.CompletedSnapshot(Vector.empty, Map.empty, Map.empty, Map.empty),
        G.Ratify(enact, Vector.empty, Set.empty, false)
      ),
      Map(account -> G.Account(5, 2, Some(pool), None)),
      Map(account -> BigInt(95)),
      G.PoolDistribution(
        100,
        Map(
          pool -> G.PoolShare(100, S.Ratio(1, 1), bytes(7)),
          zeroPool -> G.PoolShare(0, S.Ratio(0, 1), bytes(8))
        )
      ),
      Map(pool -> G.Pool(payload(3), 7), zeroPool -> G.Pool(payload(4), 7)),
      Map.empty,
      Map.empty,
      Map.empty,
      0,
      1234,
      G.Deposits(
        Map(account -> BigInt(2)),
        Map(pool -> BigInt(7), zeroPool -> BigInt(7)),
        Map.empty,
        Map.empty,
        16
      ),
      G.Globals(1, payload(5))
    )
  private def applied(input: G.Input = fixture): G.Applied = get(G.applyBoundary(input, 10))
  private def complete(source: G.Applied): C.Completed = get(
    C.complete(source, source.id, source.epoch)
  )

  test(
    "completion preserves explicit zero pool and exact captured governance while clearing only RATIFY treasury"
  ) {
    val source = applied()
    val result = complete(source)
    assert(result.source eq source)
    assertEquals(result.snapshot.poolDistribution, Map(pool -> BigInt(100), zeroPool -> BigInt(0)))
    assert(
      result.snapshot.dreps.isEmpty && result.snapshot.drepDistribution.isEmpty && result.snapshot.proposals.isEmpty
    )
    assertEquals(result.ratify.enact, source.fresh.enact.copy(treasury = 0))
    assert(result.ratify.enact.current eq source.parameters.current)
    assert(result.ratify.enact.previous eq source.parameters.previous)
    assertEquals(result.chainTreasury, BigInt(1234))
    assertEquals(source.fresh.enact.treasury, BigInt(1234))
    assertEquals(source.fresh.accounts, fixture.accounts)
    assertEquals(source.fresh.instantaneous, fixture.instantaneous)
    assertEquals(
      get(C.forSource(result, source, source.id, 10)),
      G.OldDRep.Complete(result.snapshot, result.ratify)
    )
    assertEquals(result.id, complete(source).id)
    assert(
      result.syntheticOnly && !result.nativePayloadsValidated && !result.epochTransitionValidated && !result.repeatedEpochsValidated && !result.published
    )
  }

  test("source and epoch checks reject stale, malformed, and reconstructed capture reuse") {
    val source = applied(); val result = complete(source); val reconstructed = applied()
    assertEquals(source.id, reconstructed.id)
    for pin <- Vector(bytes(99), bytes(1, 31), null) do
      assertEquals(C.complete(source, pin, 10), Left(C.Failure.SourceMismatch))
    for epoch <- Vector[BigInt](9, 11, -1, null) do
      assertEquals(C.complete(source, source.id, epoch), Left(C.Failure.EpochMismatch))
    assertEquals(C.complete(null, source.id, 10), Left(C.Failure.SourceMismatch))
    assertEquals(
      C.forSource(result, reconstructed, reconstructed.id, 10),
      Left(C.Failure.SourceMismatch)
    )
    assertEquals(C.forSource(result, source, source.id, 11), Left(C.Failure.EpochMismatch))
    assertEquals(C.forSource(null, source, source.id, 10), Left(C.Failure.SourceMismatch))
  }

  test("registered pools omitted from mark are rejected instead of manufacturing zero shares") {
    val input = fixture
    val source = applied(
      input.copy(newMarkPoolDistribution =
        input.newMarkPoolDistribution.copy(pools = input.newMarkPoolDistribution.pools - zeroPool)
      )
    )
    assertEquals(C.complete(source, source.id, 10), Left(C.Failure.PoolDomainMismatch))
    val empty = input.copy(
      accounts = Map.empty,
      instantaneous = Map.empty,
      stakePools = Map.empty,
      newMarkPoolDistribution = G.PoolDistribution(1, Map.empty),
      deposits = G.Deposits(Map.empty, Map.empty, Map.empty, Map.empty, 0)
    )
    assert(complete(applied(empty)).snapshot.poolDistribution.isEmpty)
  }

  test("nonempty registry and every voting delegation variant remain unsupported") {
    val input = fixture; val drep = credential(9)
    val registered = input.copy(
      dreps = Map(drep -> G.DRepState(20, None, 0, Set.empty)),
      deposits = input.deposits.copy(dreps = Map(drep -> BigInt(0)))
    )
    val source = applied(registered)
    assertEquals(C.complete(source, source.id, 10), Left(C.Failure.UnsupportedGovernance))
    for vote <- Vector(G.Vote.AlwaysAbstain, G.Vote.AlwaysNoConfidence, G.Vote.Credential(drep)) do
      val delegated = applied(
        input.copy(accounts =
          input.accounts.updated(account, input.accounts(account).copy(vote = Some(vote)))
        )
      )
      assertEquals(C.complete(delegated, delegated.id, 10), Left(C.Failure.UnsupportedDelegation))
  }

  test(
    "pending governance cannot enter through genuine Applied construction and completion cannot be forged"
  ) {
    val input = fixture; val action = G.ActionId(bytes(10), 0)
    assert(G.applyBoundary(input.copy(proposals = Map(action -> payload(6))), 10).isLeft)
    assert(G.applyBoundary(input.copy(proposalDeposits = Map(account -> BigInt(1))), 10).isLeft)
    assert(G.applyBoundary(input.copy(oldDRep = G.OldDRep.Pulsing), 10).isLeft)
    assert(
      compileErrors(
        "new lab.ledger.ConwayEmptyDRepCompletion.Completed(null, null, null, null)"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "lab.ledger.ConwayEmptyDRepCompletion.complete(null: lab.ledger.ConwayEmptyGovernance.FreshPulsing, null, BigInt(10))"
      ).nonEmpty
    )
  }

  test("source identity binds treasury, committee, constitution and complete parameter bytes") {
    val input = fixture; val base = complete(applied(input))
    def sync(input: G.Input): G.Input = input.oldDRep match
      case G.OldDRep.Complete(s, r) =>
        input.copy(oldDRep =
          G.OldDRep.Complete(
            s,
            r.copy(enact =
              r.enact.copy(
                committee = input.committee,
                constitution = input.constitution,
                current = input.parameters.current,
                previous = input.parameters.previous
              )
            )
          )
        )
      case _ => fail("fixture")
    val alternatives = Vector(
      input.copy(treasury = 1235),
      sync(input.copy(committee = None)),
      sync(input.copy(constitution = input.constitution.copy(script = None))),
      sync(input.copy(parameters = input.parameters.copy(current = payload(99))))
    )
    alternatives.foreach { alternative =>
      val source = applied(alternative); val result = complete(source)
      assertNotEquals(result.id, base.id)
      assertEquals(C.forSource(base, source, source.id, 10), Left(C.Failure.SourceMismatch))
      assertEquals(result.ratify.enact, source.fresh.enact.copy(treasury = 0))
    }
  }
