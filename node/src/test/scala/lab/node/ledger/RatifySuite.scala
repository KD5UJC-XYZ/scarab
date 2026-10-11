// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.Blake2b
import lab.cbor.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestLedger.*

/** RATIFY and ENACT as pure functions, and the proposal store. */
class RatifySuite extends ScalaCheckSuite:
  private val ada = BigInt(1000000)
  private val half = Rational(1, 2)

  private def id(n: Int): GovActionId =
    GovActionId(Blake2b.hash256.hash(Bytes(Vector(n.toByte))), 0)
  private def cred(n: Int): Credential =
    Credential.Key(Blake2b.hash224.hash(Bytes(Vector(n.toByte, 1))))
  private def pool(n: Int): Bytes = Blake2b.hash224.hash(Bytes(Vector(n.toByte, 2)))

  private def pp(pv: Int = 10): ProtocolParams =
    params(pv).copy(
      poolVotingThresholds = PoolVotingThresholds(half, half, half, half, half),
      drepVotingThresholds =
        DRepVotingThresholds(half, half, half, half, half, half, half, half, half, half),
      committeeMaxTermLength = 50,
      committeeMinSize = 0
    )

  private val account = RewardAccount(0, cred(100))

  private def gas(
      n: Int,
      action: GovAction,
      drep: Map[Credential, Vote] = Map.empty,
      pools: Map[Bytes, Vote] = Map.empty,
      cc: Map[Credential, Vote] = Map.empty,
      expires: BigInt = 100
  ) =
    GovActionState(id(n), cc, drep, pools, Proposal(10 * ada, account, action), 1, expires)

  private def enactState(
      committee: Option[Committee] = Some(Committee(Map.empty, Rational.zero)),
      treasury: BigInt = 1000 * ada,
      params: ProtocolParams = pp()
  ) =
    EnactState(
      committee,
      GovState.empty.constitution,
      params,
      params,
      treasury,
      Map.empty,
      PrevIds()
    )

  private def env(
      drepDistr: Map[DRep, BigInt] = Map.empty,
      drepState: Map[Credential, DRepState] = Map.empty,
      poolDistr: Map[Bytes, BigInt] = Map.empty,
      committeeState: Map[Credential, CommitteeAuth] = Map.empty,
      accounts: Map[Credential, Account] = Map.empty,
      pools: Map[Bytes, PoolState] = Map.empty,
      epoch: BigInt = 10
  ) =
    Ratify.Env(
      drepDistr,
      drepState,
      poolDistr,
      poolDistr.values.sum.max(1),
      epoch,
      committeeState,
      accounts,
      pools
    )

  private val liveDRep = DRepState(500 * ada, 1000)
  private val treasuryAction = GovAction.TreasuryWithdrawals(Vector(account -> (10 * ada)), None)
  private val infoAction = GovAction.Info

  test("DReps: abstaining stake is out, always-no-confidence counts against, silence is no") {
    val a = cred(1)
    val b = cred(2)
    val c = cred(3)
    val e = env(
      drepDistr = Map(
        DRep.Registered(a) -> 40 * ada,
        DRep.Registered(b) -> 40 * ada,
        DRep.Registered(c) -> 20 * ada,
        DRep.AlwaysAbstain -> 1000 * ada,
        DRep.AlwaysNoConfidence -> 0
      ),
      drepState = Map(a -> liveDRep, b -> liveDRep, c -> liveDRep)
    )
    def accepted(votes: Map[Credential, Vote]) =
      Ratify.ratify(e, enactState(), Vector(gas(1, treasuryAction, drep = votes))).enacted.nonEmpty
    // 40 of 100 yes: 0.4 < 1/2
    assert(!accepted(Map(a -> Vote.Yes)))
    // 80 of 100 yes
    assert(accepted(Map(a -> Vote.Yes, b -> Vote.Yes)))
    // b abstains: 40 of 60
    assert(accepted(Map(a -> Vote.Yes, b -> Vote.Abstain)))
    // exactly one half is enough
    assert(accepted(Map(a -> Vote.Yes, b -> Vote.Yes, c -> Vote.No)))
    assert(!accepted(Map(a -> Vote.Yes, b -> Vote.No, c -> Vote.No)))
  }

  test("DReps: always-no-confidence stake votes yes on a no-confidence action only") {
    val a = cred(1)
    val distr = Map[DRep, BigInt](
      DRep.Registered(a) -> 10 * ada,
      DRep.AlwaysNoConfidence -> 90 * ada
    )
    val e = env(drepDistr = distr, drepState = Map(a -> liveDRep), poolDistr = Map(pool(1) -> ada))
    val noConfidence = GovAction.NoConfidence(None)
    val votes = Map(a -> Vote.No)
    val pools = Map(pool(1) -> Vote.Yes)
    val no = Ratify.ratify(e, enactState(), Vector(gas(1, noConfidence, votes, pools)))
    assertEquals(no.enacted.map(_.id), Vector(id(1)))
    val other = GovAction.UpdateCommittee(None, Set.empty, Map.empty, half)
    val none = Ratify.ratify(e, enactState(), Vector(gas(2, other, votes, pools)))
    assertEquals(none.enacted, Vector.empty)
  }

  test("DReps: unregistered and expired DReps are ignored") {
    val live = cred(1)
    val gone = cred(2)
    val old = cred(3)
    val e = env(
      drepDistr = Map(
        DRep.Registered(live) -> 10 * ada,
        DRep.Registered(gone) -> 100 * ada,
        DRep.Registered(old) -> 100 * ada
      ),
      drepState = Map(live -> liveDRep, old -> DRepState(500 * ada, 9)),
      epoch = 10
    )
    val votes = Map(live -> Vote.Yes, gone -> Vote.No, old -> Vote.No)
    assertEquals(
      Ratify.ratify(e, enactState(), Vector(gas(1, treasuryAction, votes))).enacted.map(_.id),
      Vector(id(1))
    )
  }

  test(
    "pools: after the bootstrap phase silence is no unless the reward account delegates to a default"
  ) {
    val p1 = pool(1)
    val p2 = pool(2)
    val p3 = pool(3)
    val abstainer = cred(51)
    val contrarian = cred(52)
    def poolState(reward: Credential) =
      PoolState(
        PoolParams(
          Bytes.empty,
          Bytes.empty,
          0,
          0,
          Rational.zero,
          RewardAccount(0, reward),
          Set.empty,
          Bytes.empty,
          None
        ),
        0
      )
    val e = env(
      poolDistr = Map(p1 -> 10 * ada, p2 -> 10 * ada, p3 -> 10 * ada),
      accounts = Map(
        abstainer -> Account(0, 0, None, Some(DRep.AlwaysAbstain)),
        contrarian -> Account(0, 0, None, Some(DRep.AlwaysNoConfidence))
      ),
      pools =
        Map(p1 -> poolState(cred(50)), p2 -> poolState(abstainer), p3 -> poolState(contrarian))
    )
    val committee = GovAction.UpdateCommittee(None, Set.empty, Map.empty, half)
    def run(action: GovAction, votes: Map[Bytes, Vote]) =
      Ratify
        .ratify(
          e,
          enactState(),
          Vector(gas(1, action, drep = Map.empty, pools = votes))
        )
        .enacted
        .nonEmpty
    // DRep side: no DReps at all means a zero ratio, which a zero threshold does not need, so use
    // the pool check through a low DRep threshold
    val lowDRep = enactState(params = pp().copy(drepVotingThresholds = DRepVotingThresholds.zero))
    def pools(action: GovAction, votes: Map[Bytes, Vote]) =
      Ratify.ratify(e, lowDRep, Vector(gas(1, action, pools = votes))).enacted.nonEmpty
    // p1 votes yes; p2 abstains by default, p3 counts no: 10 of 20
    assert(pools(committee, Map(p1 -> Vote.Yes)))
    assert(!pools(committee, Map(p1 -> Vote.No)))
    // p3 delegates to always-no-confidence: no on anything but a no-confidence action
    assert(pools(GovAction.NoConfidence(None), Map(p1 -> Vote.Yes)))
    // p2 abstains by default: 10 yes of 20 non-abstaining
    assert(pools(committee, Map(p1 -> Vote.Yes, p3 -> Vote.No)))
    // hard forks count silence as no whatever the default
    val fork = GovAction.HardFork(None, 11, 0)
    assert(!pools(fork, Map(p1 -> Vote.Yes)))
    assert(pools(fork, Map(p1 -> Vote.Yes, p2 -> Vote.Yes)))
    // in the bootstrap phase silence abstains
    val boot = enactState(params = pp(9).copy(drepVotingThresholds = DRepVotingThresholds.zero))
    assert(
      Ratify
        .ratify(e, boot, Vector(gas(1, committee, pools = Map(p1 -> Vote.Yes))))
        .enacted
        .nonEmpty
    )
    assert(run(GovAction.NoConfidence(None), Map.empty) == false)
  }

  test("committee: a member who is silent votes no, resigned and expired members do not count") {
    val m1 = cred(11)
    val m2 = cred(12)
    val m3 = cred(13)
    val committee = Committee(Map(m1 -> 100, m2 -> 100, m3 -> 5), Rational(2, 3))
    val h1 = cred(21)
    val h2 = cred(22)
    val h3 = cred(23)
    val state = Map[Credential, CommitteeAuth](
      m1 -> CommitteeAuth.Hot(h1),
      m2 -> CommitteeAuth.Hot(h2),
      m3 -> CommitteeAuth.Hot(h3)
    )
    val dreps = Map[DRep, BigInt](DRep.Registered(cred(1)) -> 10 * ada)
    val base = env(drepDistr = dreps, drepState = Map(cred(1) -> liveDRep), committeeState = state)
    val yesDRep = Map(cred(1) -> Vote.Yes)
    def run(votes: Map[Credential, Vote], e: Ratify.Env = base) =
      Ratify
        .ratify(
          e,
          enactState(committee = Some(committee)),
          Vector(gas(1, treasuryAction, drep = yesDRep, cc = votes))
        )
        .enacted
        .nonEmpty
    // m3 expired at epoch 5: two members count, both must say yes for 2/3 -> ratio 1
    assert(run(Map(h1 -> Vote.Yes, h2 -> Vote.Yes)))
    // one yes, one silent: 1/2 < 2/3
    assert(!run(Map(h1 -> Vote.Yes)))
    // an abstention leaves a single yes: 1/1
    assert(run(Map(h1 -> Vote.Yes, h2 -> Vote.Abstain)))
    // a resigned member does not count
    val resigned = base.copy(committeeState = state.updated(m2, CommitteeAuth.Resigned(None)))
    assert(run(Map(h1 -> Vote.Yes), resigned))
  }

  test("committee: too few active members means no committee, except in the bootstrap phase") {
    val m1 = cred(11)
    val committee = Committee(Map(m1 -> 100), Rational.zero)
    val state = Map[Credential, CommitteeAuth](m1 -> CommitteeAuth.Resigned(None))
    val e = env(
      drepDistr = Map(DRep.Registered(cred(1)) -> ada),
      drepState = Map(cred(1) -> liveDRep),
      committeeState = state
    )
    def run(params: ProtocolParams) =
      Ratify
        .ratify(
          e,
          enactState(committee = Some(committee), params = params),
          Vector(gas(1, treasuryAction, drep = Map(cred(1) -> Vote.Yes)))
        )
        .enacted
        .nonEmpty
    assert(run(pp(10).copy(committeeMinSize = 0)))
    assert(!run(pp(10).copy(committeeMinSize = 1)))
    assert(run(pp(9).copy(committeeMinSize = 1)))
  }

  test("a committee update may not give terms longer than the maximum") {
    val short = GovAction.UpdateCommittee(None, Set.empty, Map(cred(1) -> BigInt(60)), half)
    val long = GovAction.UpdateCommittee(None, Set.empty, Map(cred(1) -> BigInt(61)), half)
    val e = env(
      drepDistr = Map(DRep.Registered(cred(7)) -> ada),
      drepState = Map(cred(7) -> liveDRep),
      poolDistr = Map(pool(1) -> ada)
    )
    def run(a: GovAction) =
      Ratify
        .ratify(
          e,
          enactState(),
          Vector(gas(1, a, drep = Map(cred(7) -> Vote.Yes), pools = Map(pool(1) -> Vote.Yes)))
        )
        .enacted
        .nonEmpty
    // epoch 10 plus the maximum term of 50
    assert(run(short))
    assert(!run(long))
  }

  test(
    "ratification order: delaying actions first, one per pass, withdrawals bounded by the treasury"
  ) {
    val a = cred(1)
    val e = env(
      drepDistr = Map(DRep.Registered(a) -> ada),
      drepState = Map(a -> liveDRep),
      poolDistr = Map(pool(1) -> ada)
    )
    val yes = Map(a -> Vote.Yes)
    val big = GovAction.TreasuryWithdrawals(Vector(account -> (900 * ada)), None)
    val big2 = GovAction.TreasuryWithdrawals(Vector(account -> (200 * ada)), None)
    val withdrawals = Ratify.ratify(
      e,
      enactState(treasury = 1000 * ada),
      Vector(gas(1, big, yes), gas(2, big2, yes))
    )
    // the second would overdraw the treasury
    assertEquals(withdrawals.enacted.map(_.id), Vector(id(1)))
    assertEquals(withdrawals.enact.withdrawals(account.credential), 900 * ada)
    assertEquals(withdrawals.enact.treasury, BigInt(0), "the enact state's treasury is reset")
    // a delaying action stops the rest of the pass, whatever their order in the store
    val constitution = GovAction.NewConstitution(None, None)
    val delayed = Ratify.ratify(
      e,
      enactState(),
      Vector(gas(1, treasuryAction, yes), gas(2, constitution, yes))
    )
    assertEquals(delayed.enacted.map(_.id), Vector(id(2)))
    assert(delayed.delayed)
  }

  test(
    "an action whose previous action is not the enacted one waits; expiry is judged afterwards"
  ) {
    val a = cred(1)
    val e = env(
      drepDistr = Map(DRep.Registered(a) -> ada),
      drepState = Map(a -> liveDRep),
      epoch = 10
    )
    val yes = Map(a -> Vote.Yes)
    val orphan =
      GovAction.ParameterChange(Some(id(99)), ParamUpdate(Map(16 -> Bytes(Vector(0x01)))), None)
    val result = Ratify.ratify(
      e,
      enactState(),
      Vector(gas(1, orphan, yes, expires = 9), gas(2, orphan, yes, expires = 10))
    )
    assertEquals(result.enacted, Vector.empty)
    assertEquals(result.expired, Set(id(1)))
  }

  test("parameter groups decide the DRep threshold and whether pools may vote") {
    val network = ParamUpdate(Map(3 -> Bytes(Vector(1))))
    val gov = ParamUpdate(Map(29 -> Bytes(Vector(1))))
    val economic = ParamUpdate(Map(16 -> Bytes(Vector(1))))
    val mixed = ParamUpdate(Map(16 -> Bytes(Vector(1)), 29 -> Bytes(Vector(1))))
    val thresholds = DRepVotingThresholds(
      Rational(1, 100),
      Rational(2, 100),
      Rational(3, 100),
      Rational(4, 100),
      Rational(5, 100),
      Rational(6, 100),
      Rational(7, 100),
      Rational(8, 100),
      Rational(9, 100),
      Rational(10, 100)
    )
    val params = pp().copy(drepVotingThresholds = thresholds)
    def drep(u: ParamUpdate) =
      Ratify.drepThreshold(params, true, GovAction.ParameterChange(None, u, None))
    assertEquals(drep(network), Ratify.Threshold.Required(Rational(6, 100)))
    assertEquals(drep(economic), Ratify.Threshold.Required(Rational(7, 100)))
    assertEquals(drep(gov), Ratify.Threshold.Required(Rational(9, 100)))
    assertEquals(drep(mixed), Ratify.Threshold.Required(Rational(9, 100)))
    assert(Ratify.isPoolVotingAllowed(GovAction.ParameterChange(None, network, None)))
    assert(!Ratify.isPoolVotingAllowed(GovAction.ParameterChange(None, economic, None)))
    // minFeeA and the governance deposit are in the security group
    assert(
      Ratify.isPoolVotingAllowed(
        GovAction.ParameterChange(None, ParamUpdate(Map(0 -> Bytes.empty)), None)
      )
    )
    assert(
      Ratify.isPoolVotingAllowed(
        GovAction.ParameterChange(None, ParamUpdate(Map(30 -> Bytes.empty)), None)
      )
    )
    assert(!Ratify.isPoolVotingAllowed(GovAction.NewConstitution(None, None)))
    assert(!Ratify.isPoolVotingAllowed(treasuryAction))
  }

  // ---------------------------------------------------------------------------------------------
  // The proposal store

  private def update(n: Int, prev: Option[GovActionId]): GovActionState =
    gas(n, GovAction.ParameterChange(prev, ParamUpdate(Map(16 -> Bytes(Vector(n.toByte)))), None))

  test("a proposal is accepted when it names the root or a stored proposal of its kind") {
    val ps = Proposals.empty
    val first = update(1, None)
    val afterFirst = ps.add(first).get
    assert(afterFirst.add(update(2, Some(id(1)))).isDefined)
    assert(afterFirst.add(update(3, None)).isDefined, "a sibling of the first")
    assert(afterFirst.add(update(4, Some(id(77)))).isEmpty, "an unknown parent")
    // another kind's proposal is not a parent
    val committee = gas(5, GovAction.NoConfidence(Some(id(1))))
    assert(afterFirst.add(committee).isEmpty)
  }

  test(
    "enacting a proposal promotes it to the root and drops its competitors with their descendants"
  ) {
    val ps = Vector(
      update(1, None),
      update(2, Some(id(1))),
      update(3, None),
      update(4, Some(id(3))),
      update(5, Some(id(2)))
    ).foldLeft(Proposals.empty)((p, g) => p.add(g).get)
    val (after, competing, expired) = ps.applyEnactment(Vector(ps.byId(id(1))), Set.empty)
    assertEquals(after.roots.paramUpdate, Some(id(1)))
    assertEquals(after.actions.map(_.id), Vector(id(2), id(5)))
    assertEquals(competing.map(_.id).toSet, Set(id(3), id(4)))
    assertEquals(expired, Vector.empty)
    // expiring the child removes its subtree
    val (pruned, _, gone) = after.applyEnactment(Vector.empty, Set(id(2)))
    assertEquals(pruned.actions, Vector.empty)
    assertEquals(gone.map(_.id).toSet, Set(id(2), id(5)))
  }

  property("after any sequence of additions and enactments every proposal descends from its root") {
    val steps = Gen.listOfN(12, Gen.choose(0, 99))
    forAll(steps) { picks =>
      val (ps, _) = picks.zipWithIndex.foldLeft((Proposals.empty, 0)) { case ((p, n), (pick, i)) =>
        val parents = None +: p.actions.map(a => Some(a.id))
        val next = update(i + 1, parents(pick % parents.size))
        val added = p.add(next).getOrElse(p)
        // sometimes enact the oldest child of the root
        val rootChildren = added.actions.filter(a =>
          GovActions.lineage(a.action).exists(_._2 == added.roots.paramUpdate)
        )
        if pick % 4 == 0 && rootChildren.nonEmpty then
          (added.applyEnactment(Vector(rootChildren.head), Set.empty)._1, n + 1)
        else (added, n)
      }
      def connected(a: GovActionState): Boolean =
        GovActions.lineage(a.action).forall { case (_, parent) =>
          parent == ps.roots.paramUpdate ||
          parent.flatMap(ps.byId.get).exists(connected)
        }
      ps.actions.forall(connected) && ps.actions.map(_.id).distinct.size == ps.actions.size
    }
  }
