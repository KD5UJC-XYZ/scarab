// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational, Unique}

/** The orders the ordered sets of a body find their repeats with compare two certificates, or two
  * proposals, equal exactly when they are equal, so the check by sorting refuses what the check by
  * `distinct` refused.
  */
class IdentityOrderSuite extends ScalaCheckSuite:
  // two of each, so that equal values are common
  private val hash: Gen[Bytes] = Gen.oneOf(1, 2).map(b => Bytes(Vector.fill(28)(b.toByte)))
  private val small: Gen[BigInt] = Gen.choose(0, 2).map(BigInt(_))
  private val credential: Gen[Credential] =
    Gen
      .zip(Gen.oneOf(true, false), hash)
      .map((k, h) => if k then Credential.Key(h) else Credential.Script(h))
  private val anchor: Gen[Anchor] =
    Gen
      .zip(Gen.oneOf("a", "b"), Gen.oneOf(0, 1))
      .map((u, h) => Anchor(u, Bytes(Vector.fill(32)(h.toByte))))
  private val account: Gen[RewardAccount] =
    Gen.zip(Gen.choose(0, 1), credential).map(RewardAccount(_, _))
  private val drep: Gen[DRep] =
    Gen.oneOf(
      credential.map(DRep.Registered(_)),
      Gen.const(DRep.AlwaysAbstain),
      Gen.const(DRep.AlwaysNoConfidence)
    )
  private val actionId: Gen[GovActionId] = Gen.zip(hash, small).map(GovActionId(_, _))
  private val relays: Gen[Bytes] =
    Gen.oneOf(
      Bytes.empty,
      Bytes(Vector(0x81, 0x82, 0x00, 0x01).map(_.toByte)),
      Bytes(Vector(0x9f, 0x82, 0x00, 0x01, 0xff).map(_.toByte))
    )
  private val pool: Gen[PoolParams] =
    for
      operator <- hash
      vrf <- hash
      pledge <- small
      cost <- small
      margin <- Gen.oneOf(Rational(1, 2), Rational(1, 3))
      reward <- account
      owners <- Gen.containerOf[Set, Bytes](hash)
      relay <- relays
      metadata <- Gen.option(Gen.const(Bytes(Vector(0x82, 0x61, 0x75, 0x40).map(_.toByte))))
    yield PoolParams(operator, vrf, pledge, cost, margin, reward, owners, relay, metadata)

  private val certificate: Gen[Certificate] = Gen.oneOf(
    Gen.zip(credential, Gen.option(small)).map(Certificate.Register(_, _)),
    Gen.zip(credential, Gen.option(small)).map(Certificate.Unregister(_, _)),
    Gen
      .zip(credential, Gen.option(hash), Gen.option(drep), Gen.option(small))
      .map(Certificate.Delegate(_, _, _, _)),
    pool.map(Certificate.RegisterPool(_)),
    Gen.zip(hash, small).map(Certificate.RetirePool(_, _)),
    Gen.zip(hash, hash, hash).map(Certificate.GenesisDelegate(_, _, _)),
    Gen
      .zip(Gen.oneOf(Pot.values.toSeq), Gen.either(small, Gen.mapOf(Gen.zip(credential, small))))
      .map(Certificate.MoveRewards(_, _)),
    Gen.zip(credential, small, Gen.option(anchor)).map(Certificate.RegisterDRep(_, _, _)),
    Gen.zip(credential, small).map(Certificate.UnregisterDRep(_, _)),
    Gen.zip(credential, Gen.option(anchor)).map(Certificate.UpdateDRep(_, _)),
    Gen.zip(credential, credential).map(Certificate.CommitteeAuthorize(_, _)),
    Gen.zip(credential, Gen.option(anchor)).map(Certificate.CommitteeResign(_, _))
  )

  // fields of each kind of value, two values each, one of them written two ways (`18 01` is 1)
  private val parameters: Vector[(BigInt, Bytes)] = Vector(
    0 -> "01",
    0 -> "1801",
    0 -> "02",
    9 -> "d81e820102",
    9 -> "d81e820103",
    18 -> "a100820102",
    18 -> "a100820103",
    19 -> "82d81e820102d81e820103",
    20 -> "820102",
    20 -> "820103",
    25 -> "85d81e820102d81e820102d81e820102d81e820102d81e820102",
    25 -> "85d81e820102d81e820102d81e820102d81e820102d81e820103"
  ).map((k, v) => BigInt(k) -> Bytes.fromHex(v).fold(e => fail(e.message), identity))

  private val update: Gen[ConwayParamUpdate] =
    Gen
      .someOf(parameters)
      .map(_.toVector.distinctBy(_._1))
      .map(fields => ConwayParamUpdate.fromEncoded(fields).fold(e => fail(e.message), identity))

  private val action: Gen[GovAction] = Gen.oneOf(
    Gen.zip(Gen.option(actionId), update, Gen.option(hash)).map(GovAction.ParameterChange(_, _, _)),
    Gen.const(GovAction.Info),
    Gen.option(actionId).map(GovAction.NoConfidence(_)),
    Gen.zip(Gen.option(actionId), small, small).map(GovAction.HardFork(_, _, _)),
    Gen
      .zip(Gen.listOf(Gen.zip(account, small)).map(_.toVector), Gen.option(hash))
      .map(GovAction.TreasuryWithdrawals(_, _)),
    Gen
      .zip(
        Gen.option(actionId),
        Gen.containerOf[Set, Credential](credential),
        Gen.mapOf(Gen.zip(credential, small)),
        Gen.oneOf(Rational(1, 2), Rational(2, 3))
      )
      .map(GovAction.UpdateCommittee(_, _, _, _)),
    Gen.zip(Gen.option(actionId), Gen.option(hash), anchor).map(GovAction.NewConstitution(_, _, _))
  )

  private val proposal: Gen[Proposal] =
    Gen.zip(small, account, action, anchor).map(Proposal(_, _, _, _))

  property("two certificates compare equal exactly when they are equal") {
    Prop.forAll(certificate, certificate) { (a, b) =>
      val (x, y) = (Certificate.identity(a), Certificate.identity(b))
      assertEquals(Certificate.identityOrder.compare(x, y) == 0, x == y)
      assertEquals(
        Integer.signum(Certificate.identityOrder.compare(x, y)),
        -Integer.signum(Certificate.identityOrder.compare(y, x))
      )
    }
  }

  property("certificates repeat by the order exactly when they repeat by equality") {
    Prop.forAll(Gen.listOf(certificate)) { cs =>
      val ids = cs.toVector.map(Certificate.identity)
      assertEquals(Unique.repeats(ids), ids.distinct.sizeIs != ids.size)
    }
  }

  property("two proposals compare equal exactly when they are equal") {
    Prop.forAll(proposal, proposal) { (a, b) =>
      val (x, y) = (Proposal.identity(a), Proposal.identity(b))
      val order = summon[Ordering[ProposalIdentity]]
      assertEquals(order.compare(x, y) == 0, x == y)
      assertEquals(Integer.signum(order.compare(x, y)), -Integer.signum(order.compare(y, x)))
    }
  }

  property("the orders are transitive") {
    Prop.forAll(certificate, certificate, certificate, proposal, proposal, proposal) {
      (a, b, c, p, q, r) =>
        val byCertificate = Certificate.identityOrder
        val byProposal = summon[Ordering[ProposalIdentity]]
        val cs = Vector(a, b, c).map(Certificate.identity).sorted(using byCertificate)
        val ps = Vector(p, q, r).map(Proposal.identity).sorted(using byProposal)
        assert(byCertificate.lteq(cs(0), cs(1)) && byCertificate.lteq(cs(1), cs(2)), cs)
        assert(byCertificate.lteq(cs(0), cs(2)), cs)
        assert(byProposal.lteq(ps(0), ps(1)) && byProposal.lteq(ps(1), ps(2)), ps)
        assert(byProposal.lteq(ps(0), ps(2)), ps)
    }
  }

  // a value against the same value with its sets and maps built in the other order
  property("a proposal is its equal however its sets and maps were built") {
    Prop.forAll(proposal) { p =>
      val rebuilt = p.action match
        case GovAction.UpdateCommittee(previous, removed, added, quorum) =>
          GovAction.UpdateCommittee(
            previous,
            removed.toVector.reverse.toSet,
            added.toVector.reverse.toMap,
            quorum
          )
        case other => other
      val (x, y) = (Proposal.identity(p), Proposal.identity(p.copy(action = rebuilt)))
      assertEquals(x, y)
      assertEquals(summon[Ordering[ProposalIdentity]].compare(x, y), 0)
    }
  }

  // proposals alike but for their update: one field written two ways is one value, two values two
  test("a parameter change is its typed values, whatever their encoding") {
    def proposal(fields: (Int, String)*): ProposalIdentity =
      val update = ConwayParamUpdate
        .fromEncoded(
          fields.toVector
            .map((k, v) => BigInt(k) -> Bytes.fromHex(v).fold(e => fail(e.message), identity))
        )
        .fold(e => fail(e.message), identity)
      val account = RewardAccount(0, Credential.Key(Bytes(Vector.fill(28)(1.toByte))))
      Proposal.identity(
        Proposal(
          1,
          account,
          GovAction.ParameterChange(None, update, None),
          Anchor("a", Bytes.empty)
        )
      )
    val order = summon[Ordering[ProposalIdentity]]
    val (one, oneAgain, two) = (proposal(0 -> "01"), proposal(0 -> "1801"), proposal(0 -> "02"))
    assertEquals(one, oneAgain)
    assertEquals(order.compare(one, oneAgain), 0)
    assertNotEquals(one, two)
    assert(order.compare(one, two) < 0 && order.compare(two, one) > 0)
    val (half, third) = (proposal(9 -> "d81e820102"), proposal(9 -> "d81e820103"))
    assert(order.compare(half, third) != 0 && half != third)
    val (models, others) = (proposal(18 -> "a100820102"), proposal(18 -> "a100820103"))
    assert(order.compare(models, others) != 0 && models != others)
    assertEquals(
      order
        .compare(proposal(0 -> "01", 9 -> "d81e820102"), proposal(9 -> "d81e820102", 0 -> "1801")),
      0
    )
  }

  property("proposals repeat by the order exactly when they repeat by equality") {
    Prop.forAll(Gen.listOf(proposal)) { ps =>
      val ids = ps.toVector.map(Proposal.identity)
      assertEquals(Unique.repeats(ids), ids.distinct.sizeIs != ids.size)
    }
  }
