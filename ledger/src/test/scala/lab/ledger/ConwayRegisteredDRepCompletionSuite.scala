// SPDX-License-Identifier: Apache-2.0
package lab.ledger

import lab.cbor.{Bytes, Cbor, Value as V}
import ConwayEmptyGovernance as G
import ConwayRegisteredDRepCompletion as C
import ConwayStake as S

class ConwayRegisteredDRepCompletionSuite extends munit.FunSuite:
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

  private def captured(name: String, epoch: BigInt): G.Applied =
    val normal = Vector(
      (3, 5, 95, Some(G.Vote.Credential(credential(64)))),
      (4, 3, 20, Some(G.Vote.Credential(credential(64)))),
      (5, 0, 0, Some(G.Vote.Credential(credential(65)))),
      (6, 11, 7, Some(G.Vote.Credential(credential(66))))
    )
    val rows: Vector[(Int, Int, Int, Option[G.Vote])] = name match
      case "special-and-unregistered" =>
        Vector(
          (3, 5, 95, Some(G.Vote.AlwaysAbstain)),
          (4, 3, 20, Some(G.Vote.AlwaysNoConfidence)),
          (5, 0, 0, None),
          (6, 11, 7, Some(G.Vote.Credential(credential(67))))
        )
      case "zero-entry" => Vector((3, 0, 0, Some(G.Vote.Credential(credential(64)))))
      case _            => normal
    val accounts = rows
      .map((id, reward, _, vote) => credential(id) -> G.Account(reward, 2, Some(pool), vote))
      .toMap
    val registry = Vector(64 -> 0, 65 -> 1, 66 -> 8).map { (id, expiry) =>
      credential(id) -> G.DRepState(
        expiry,
        None,
        5,
        accounts.collect {
          case (who, a) if a.vote.contains(G.Vote.Credential(credential(id))) => who
        }.toSet
      )
    }.toMap
    val input = fixture.copy(
      epoch = epoch - 1,
      accounts = accounts,
      dreps = registry,
      instantaneous =
        rows.filter(_._3 > 0).map((id, _, stake, _) => credential(id) -> BigInt(stake)).toMap,
      deposits = fixture.deposits.copy(
        stake = accounts.map((c, a) => c -> a.deposit),
        dreps = registry.map((c, d) => c -> d.deposit),
        total = accounts.size * 2 + 14 + 15
      )
    )
    get(G.applyBoundary(input, epoch))
  private def voteText(v: G.Vote): String = v match
    case G.Vote.Credential(c)      => "key-" + c.hash.hex
    case G.Vote.AlwaysAbstain      => "abstain"
    case G.Vote.AlwaysNoConfidence => "no-confidence"
  test(
    "four actual native force-completion cases match exact distribution registry expiry and treasury"
  ) {
    val stream = getClass.getResourceAsStream("/registered-drep/native-result.txt")
    val raw =
      try stream.readAllBytes()
      finally stream.close()
    val sha = java.security.MessageDigest
      .getInstance("SHA-256")
      .digest(raw)
      .map(b => f"${b & 255}%02x")
      .mkString
    assertEquals(sha, "b3ec8d4ae76e58341ecea5fe590b09bb33ea9ae9469382d6b350d72be9a45918")
    val lines = new String(raw, "US-ASCII").linesIterator.toVector
    assertEquals(lines.head, "registered-drep-completion-native-v1")
    assertEquals(lines.size, 5)
    lines.tail.foreach { line =>
      val fields = line.split("\\|", -1)
      val source = captured(fields(0), BigInt(fields(1)))
      val result = complete(source)
      val distribution = result.snapshot.drepDistribution.toVector
        .map((v, n) => voteText(v) + ":" + n)
        .sorted
        .mkString(",")
      val registry = result.snapshot.dreps.toVector
        .map((c, d) => "key:" + c.hash.hex + ":" + d.expiry)
        .sorted
        .mkString(",")
      assertEquals(distribution, fields(4)); assertEquals(registry, fields(5))
      assertEquals(result.chainTreasury.toString, fields(2));
      assertEquals(result.ratify.enact.treasury.toString, fields(3))
      assertEquals(result.ratify.enact, source.fresh.enact.copy(treasury = 0))
      assertEquals(
        result.snapshot.poolDistribution,
        source.fresh.stakePoolDistribution.pools.map((p, s) => p -> s.stake)
      )
      assertEquals(result.snapshot.dreps, source.fresh.dreps)
      assertEquals(result.snapshot.proposals, Vector.empty)
      assert(
        !result.ratify.delayed && result.ratify.enacted.isEmpty && result.ratify.expired.isEmpty
      )
    }
  }
  test("expired DReps are retained and zero delegated stake inserts explicit entry") {
    val source = captured("expired-three", 10); val result = complete(source)
    assertEquals(result.snapshot.drepDistribution(G.Vote.Credential(credential(64))), BigInt(123))
    assertEquals(result.snapshot.drepDistribution(G.Vote.Credential(credential(65))), BigInt(0))
    assertEquals(source.fresh.dreps(credential(64)).expiry, BigInt(0))
    assert(result.source eq source)
    assertEquals(
      get(C.forSource(result, source, source.id, 10)),
      G.OldDRep.Complete(result.snapshot, result.ratify)
    )
    assertEquals(source.fresh.enact.treasury, BigInt(1234))
  }
  test("capture identity rejects equal reconstruction wrong pin and wrong epoch") {
    val source = captured("registered-three", 1); val result = complete(source)
    val other = captured("registered-three", 1)
    assertEquals(source.id, other.id)
    assertEquals(C.forSource(result, other, other.id, 1), Left(C.Failure.SourceMismatch))
    assertEquals(C.complete(source, bytes(99), 1), Left(C.Failure.SourceMismatch))
    assertEquals(C.complete(source, source.id, 2), Left(C.Failure.EpochMismatch))
    assertEquals(C.forSource(result, source, source.id, 2), Left(C.Failure.EpochMismatch))
  }
  test("coin overflow fails instead of wrapping CompactCoin while preserving capture") {
    val original = captured("zero-entry", 10)
    val input = original.before.copy(
      accounts = original.before.accounts.updated(
        credential(3),
        original.before.accounts(credential(3)).copy(rewards = (BigInt(1) << 64) - 1)
      ),
      instantaneous = Map(credential(3) -> BigInt(1))
    )
    val source = get(G.applyBoundary(input, 10))
    assertEquals(C.complete(source, source.id, 10), Left(C.Failure.ArithmeticOverflow))
    assertEquals(source.fresh.instantaneous, Map(credential(3) -> BigInt(1)))
  }
  test("omitted registered pool remains unsupported and old empty profile remains closed") {
    val input = fixture.copy(newMarkPoolDistribution =
      fixture.newMarkPoolDistribution.copy(pools = fixture.newMarkPoolDistribution.pools - zeroPool)
    )
    val source = applied(input)
    assertEquals(C.complete(source, source.id, 10), Left(C.Failure.PoolDomainMismatch))
    val registered = captured("registered-three", 1)
    assert(ConwayEmptyDRepCompletion.complete(registered, registered.id, 1).isLeft)
  }
  test("unsupported proposals and pending work cannot be introduced through the capability") {
    val action = G.ActionId(bytes(10), 0)
    assert(G.applyBoundary(fixture.copy(proposals = Map(action -> payload(9))), 10).isLeft)
    assert(G.applyBoundary(fixture.copy(proposalDeposits = Map(account -> BigInt(1))), 10).isLeft)
    assert(G.applyBoundary(fixture.copy(oldDRep = G.OldDRep.Pulsing), 10).isLeft)
    assert(
      compileErrors(
        "new lab.ledger.ConwayRegisteredDRepCompletion.Completed(null,null,null,null)"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "lab.ledger.ConwayRegisteredDRepCompletion.complete(null: lab.ledger.ConwayEmptyGovernance.FreshPulsing,null,BigInt(1))"
      ).nonEmpty
    )
  }
