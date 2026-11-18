// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.dijkstra

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.{Bytes, Rational, Unique}
import xyz.kd5ujc.scarab.ledger.{Credential, DRep, RewardAccount}

/** The orders the Dijkstra decoders find repeats with compare two values equal exactly when they
  * are equal, so the check by sorting refuses what the check by `distinct` refused.
  */
class DijkstraOrderSuite extends ScalaCheckSuite:
  import DijkstraOrder.given

  // two of each, so that equal values are common
  private val hash: Gen[Bytes] = Gen.oneOf(1, 2).map(b => Bytes(Vector.fill(28)(b.toByte)))
  private val small: Gen[BigInt] = Gen.choose(0, 2).map(BigInt(_))
  private val ratio: Gen[Rational] = Gen.oneOf(Rational(1, 2), Rational(1, 3))
  private val credential: Gen[Credential] =
    Gen
      .zip(Gen.oneOf(true, false), hash)
      .map((k, h) => if k then Credential.Key(h) else Credential.Script(h))
  private val account: Gen[RewardAccount] =
    Gen.zip(Gen.choose(0, 1), credential).map(RewardAccount(_, _))
  private val anchor: Gen[DijkstraAnchor] =
    Gen
      .zip(Gen.oneOf("a", "b"), Gen.oneOf(0, 1))
      .map((u, h) => DijkstraAnchor(u, Bytes(Vector(h.toByte))))
  private val drep: Gen[DRep] =
    Gen.oneOf(
      credential.map(DRep.Registered(_)),
      Gen.const(DRep.AlwaysAbstain),
      Gen.const(DRep.AlwaysNoConfidence)
    )
  private val actionId: Gen[DijkstraGovActionId] =
    Gen.zip(hash, Gen.choose(0, 1)).map(DijkstraGovActionId(_, _))
  private val relay: Gen[DijkstraRelay] = Gen.oneOf(
    Gen
      .zip(Gen.option(Gen.choose(1, 2)), Gen.option(hash), Gen.option(hash))
      .map(DijkstraRelay.SingleHostAddr(_, _, _)),
    Gen
      .zip(Gen.option(Gen.choose(1, 2)), Gen.oneOf("a", "b"))
      .map(DijkstraRelay.SingleHostName(_, _)),
    Gen.oneOf("a", "b").map(DijkstraRelay.MultiHostName(_))
  )
  private val pool: Gen[DijkstraPoolParams] =
    for
      operator <- hash
      vrf <- hash
      bls <- Gen.option(Gen.zip(hash, hash).map(DijkstraBlsKey(_, _)))
      pledge <- small
      cost <- small
      margin <- ratio
      reward <- account
      owners <- Gen.containerOf[Set, Bytes](hash)
      relays <- Gen.listOfN(2, relay).map(_.toVector)
      metadata <- Gen.option(
        Gen
          .zip(Gen.oneOf("a", "b"), Gen.oneOf(0, 1))
          .map((u, h) => DijkstraPoolMetadata(u, Bytes(Vector(h.toByte))))
      )
    yield DijkstraPoolParams(
      operator,
      vrf,
      bls,
      pledge,
      cost,
      margin,
      reward,
      owners,
      relays,
      metadata
    )
  private val delegatee: Gen[DijkstraDelegatee] = Gen.oneOf(
    hash.map(DijkstraDelegatee.Stake(_)),
    drep.map(DijkstraDelegatee.Vote(_)),
    Gen.zip(hash, drep).map(DijkstraDelegatee.StakeVote(_, _))
  )
  private val certificate: Gen[DijkstraCert] = Gen.oneOf(
    Gen.zip(credential, small).map(DijkstraCert.Register(_, _)),
    Gen.zip(credential, small).map(DijkstraCert.Unregister(_, _)),
    Gen.zip(credential, delegatee).map(DijkstraCert.Delegate(_, _)),
    Gen.zip(credential, delegatee, small).map(DijkstraCert.RegisterDelegate(_, _, _)),
    pool.map(DijkstraCert.RegisterPool(_)),
    Gen.zip(hash, small).map(DijkstraCert.RetirePool(_, _)),
    Gen.zip(credential, credential).map(DijkstraCert.AuthorizeCommitteeHotKey(_, _)),
    Gen.zip(credential, Gen.option(anchor)).map(DijkstraCert.ResignCommitteeColdKey(_, _)),
    Gen.zip(credential, small, Gen.option(anchor)).map(DijkstraCert.RegisterDRep(_, _, _)),
    Gen.zip(credential, small).map(DijkstraCert.UnregisterDRep(_, _)),
    Gen.zip(credential, Gen.option(anchor)).map(DijkstraCert.UpdateDRep(_, _))
  )
  private val paramValue: Gen[DijkstraParamValue] = Gen.oneOf(
    small.map(DijkstraParamValue.Number(_)),
    ratio.map(DijkstraParamValue.Ratio(_)),
    Gen.option(ratio).map(DijkstraParamValue.OptionalRatio(_)),
    Gen.option(Gen.choose(0L, 1L)).map(DijkstraParamValue.OptionalNumber(_)),
    Gen
      .mapOf(Gen.zip(Gen.choose(0, 1), Gen.listOfN(2, Gen.choose(0L, 1L)).map(_.toVector)))
      .map(DijkstraParamValue.CostModels(_)),
    Gen.zip(ratio, ratio).map(DijkstraParamValue.Pair(_, _)),
    Gen.zip(small, small).map(DijkstraParamValue.ExUnits(_, _)),
    Gen.listOfN(2, ratio).map(v => DijkstraParamValue.Thresholds(v.toVector))
  )
  private val action: Gen[DijkstraGovAction] = Gen.oneOf(
    Gen
      .zip(Gen.option(actionId), Gen.mapOf(Gen.zip(Gen.choose(0, 2), paramValue)), Gen.option(hash))
      .map((p, f, s) => DijkstraGovAction.ParameterChange(p, DijkstraParamUpdate(f), s)),
    Gen
      .zip(Gen.option(actionId), Gen.choose(0L, 1L), Gen.choose(0L, 1L))
      .map(DijkstraGovAction.HardForkInitiation(_, _, _)),
    Gen
      .zip(Gen.mapOf(Gen.zip(account, small)), Gen.option(hash))
      .map(DijkstraGovAction.TreasuryWithdrawals(_, _)),
    Gen.option(actionId).map(DijkstraGovAction.NoConfidence(_)),
    Gen
      .zip(
        Gen.option(actionId),
        Gen.containerOf[Set, Credential](credential),
        Gen.mapOf(Gen.zip(credential, small)),
        ratio
      )
      .map(DijkstraGovAction.UpdateCommittee(_, _, _, _)),
    Gen
      .zip(Gen.option(actionId), anchor, Gen.option(hash))
      .map(DijkstraGovAction.NewConstitution(_, _, _)),
    Gen.const(DijkstraGovAction.InfoAction)
  )
  private val proposal: Gen[DijkstraProposal] =
    Gen.zip(small, account, action, anchor).map(DijkstraProposal(_, _, _, _))
  private val voter: Gen[DijkstraVoter] = Gen.oneOf(
    credential.map(DijkstraVoter.Committee(_)),
    credential.map(DijkstraVoter.DRep(_)),
    hash.map(DijkstraVoter.StakePool(_))
  )
  private val txIn: Gen[DijkstraTxIn] = Gen.zip(hash, Gen.choose(0, 1)).map(DijkstraTxIn(_, _))

  private def agrees[A](gen: Gen[A])(using order: Ordering[A]): Prop =
    Prop.forAll(gen, gen, gen) { (a, b, c) =>
      assertEquals(order.compare(a, b) == 0, a == b)
      assertEquals(order.compare(a, a), 0)
      assertEquals(Integer.signum(order.compare(a, b)), -Integer.signum(order.compare(b, a)))
      val sorted = Vector(a, b, c).sorted
      assert(
        order.lteq(sorted(0), sorted(1)) && order.lteq(sorted(1), sorted(2)) && order.lteq(
          sorted(0),
          sorted(2)
        )
      )
    } && Prop.forAll(Gen.listOf(gen)) { xs =>
      assertEquals(Unique.repeats(xs.toVector), xs.distinct.sizeIs != xs.size)
    }

  property("certificates")(agrees(certificate))
  property("proposals")(agrees(proposal))
  property("voters")(agrees(voter))
  property("inputs")(agrees(txIn))
  property("action ids")(agrees(actionId))

  // values alike but for one field each: unequal, and refused as distinct elements of a set
  test("each field takes part in the order") {
    val base = DijkstraPoolParams(
      Bytes(Vector(1)),
      Bytes(Vector(2)),
      None,
      1,
      1,
      Rational(1, 2),
      RewardAccount(0, Credential.Key(Bytes(Vector(3)))),
      Set.empty,
      Vector.empty,
      Some(DijkstraPoolMetadata("a", Bytes(Vector(4))))
    )
    val pools = Vector(
      base.copy(operator = Bytes(Vector(9))),
      base.copy(vrf = Bytes(Vector(9))),
      base.copy(pledge = 2),
      base.copy(cost = 2),
      base.copy(margin = Rational(1, 3)),
      base.copy(metadata = Some(DijkstraPoolMetadata("a", Bytes(Vector(9))))),
      base.copy(metadata = Some(DijkstraPoolMetadata("b", Bytes(Vector(4)))))
    )
    pools.foreach { p =>
      assert(summon[Ordering[DijkstraPoolParams]].compare(base, p) != 0, p)
      assertEquals(Unique.repeats(Vector(base, p)), false)
    }
    val anchor = DijkstraAnchor("a", Bytes(Vector(1)))
    assert(
      summon[Ordering[DijkstraAnchor]].compare(anchor, anchor.copy(hash = Bytes(Vector(2)))) != 0
    )
    assert(summon[Ordering[DijkstraAnchor]].compare(anchor, anchor.copy(url = "b")) != 0)
  }

  test("a proposal is its equal however its committee's set and map were built") {
    val (a, b) =
      (Credential.Key(Bytes(Vector.fill(28)(1))), Credential.Script(Bytes(Vector.fill(28)(2))))
    def committee(removed: Set[Credential], added: Map[Credential, BigInt]) =
      DijkstraProposal(
        1,
        RewardAccount(0, a),
        DijkstraGovAction.UpdateCommittee(None, removed, added, Rational(1, 2)),
        DijkstraAnchor("a", Bytes(Vector(0)))
      )
    val x = committee(Set(a, b), Map(a -> 1, b -> 2))
    val y = committee(Set(b, a), Map(b -> 2, a -> 1))
    assertEquals(x, y)
    assertEquals(summon[Ordering[DijkstraProposal]].compare(x, y), 0)
  }
