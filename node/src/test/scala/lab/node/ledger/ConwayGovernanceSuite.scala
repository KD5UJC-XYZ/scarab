// SPDX-License-Identifier: Apache-2.0
package lab.node.ledger

import lab.Blake2b
import lab.cbor.{Bytes, Cbor, Value as CborValue}
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import TestLedger.*

/** Constructed governance scenarios: propose, vote, cross epoch boundaries, enact, with the supply
  * and the deposit pot checked after every step.
  */
class ConwayGovernanceSuite extends ScalaCheckSuite:
  private val ada = BigInt(1000000)
  private val half = Rational(1, 2)
  private val globals = LedgerGlobals(Supply, 100, Rational(1, 20), network = 0)

  /** A Conway ledger with three DReps, two pools, three delegating accounts and a funded payer. */
  private final class World(val pv: Int):
    val payer = key()
    val dreps = Vector.fill(3)(key())
    val stakes = Vector.fill(3)(key())
    val pools = Vector.fill(2)(key())
    val poolOwner = key()
    val committeeCold = Vector.fill(2)(key())
    val committeeHot = Vector.fill(2)(key())
    val fundIn: TxIn = txIn(77)
    val fundCoin: BigInt = 10000 * ada
    val params: ProtocolParams = TestLedger
      .params(pv)
      .copy(
        drepDeposit = 500 * ada,
        drepActivity = 20,
        govActionDeposit = 1000 * ada,
        govActionLifetime = 3,
        committeeMaxTermLength = 100,
        committeeMinSize = 0,
        // no monetary expansion or treasury cut: the treasury moves only through governance
        rho = Rational.zero,
        tau = Rational.zero,
        poolDeposit = 500 * ada,
        poolVotingThresholds = PoolVotingThresholds(half, half, half, half, half),
        drepVotingThresholds =
          DRepVotingThresholds(half, half, half, half, half, half, half, half, half, half)
      )

    def poolParams(k: Key): PoolParams =
      PoolParams(
        k.hash,
        Blake2b.hash256.hash(k.hash),
        0,
        0,
        Rational(1, 10),
        RewardAccount(0, poolOwner.credential),
        Set(poolOwner.hash),
        Bytes.empty,
        None
      )

    def initial: State =
      val stakeUtxos = stakes.zipWithIndex.map { case (sk, i) =>
        txIn(100 + i) -> out(payer.baseAddress(sk), 1000 * ada)
      }
      val accounts = (stakes.zipWithIndex.map { case (sk, i) =>
        sk.credential -> Account(
          0,
          params.keyDeposit,
          Some(pools(i % 2).hash),
          Some(DRep.Registered(dreps(i % 3).credential))
        )
      } :+ (poolOwner.credential -> Account(0, params.keyDeposit, None, None))).toMap
      val poolStates = pools.map(p => p.hash -> PoolState(poolParams(p), params.poolDeposit)).toMap
      val drepStates = dreps.zipWithIndex.map { case (k, i) =>
        k.credential -> DRepState(
          params.drepDeposit,
          1000,
          None,
          stakes.zipWithIndex.collect { case (sk, j) if j % 3 == i => sk.credential }.toSet
        )
      }.toMap
      val utxo = (stakeUtxos :+ (fundIn -> out(payer.address(), fundCoin))).toMap
      val base = state(pv, utxo.toVector).copy(
        params = params,
        accounts = accountTable(accounts),
        pools = poolTable(poolStates),
        dreps = drepTable(drepStates),
        treasury = 100000 * ada,
        epoch = 5,
        gov = GovState.empty.copy(committee = Some(Committee(Map.empty, Rational.zero)))
      )
      val withObligation = base.copy(deposited = Epoch.obligation(base))
      val marked = withObligation.copy(
        snapshots = Snapshots(Stake.snapshot(withObligation), Snapshot.empty, Snapshot.empty, 0)
      )
      val reserves = Supply - (marked.total - marked.reserves)
      val seeded = marked.copy(reserves = reserves)
      val withPulser = seeded.copy(gov =
        seeded.gov.copy(pulsed = Some(ConwayEpoch.freshPulser(seeded, seeded.epoch)))
      )
      State(withPulser, fundIn, fundCoin, 0)

  private final case class State(s: LedgerState, fund: TxIn, fundCoin: BigInt, nonce: Int)

  private def env(s: LedgerState, n: Int): TxRules.Env =
    TxRules.Env(
      slot = s.epoch * 100 + 10,
      epoch = s.epoch,
      network = 0,
      firstSlotNextEpoch = s.epoch * 100 + 100,
      stabilityWindow = 20,
      txIndex = n,
      clock = Some(slot => slot * 1000 + 1596059091000L)
    )

  /** Submit one transaction paid by `payer`; `deposits` and `refunds` balance the certificates. */
  private def submit(
      w: World,
      st: State,
      signers: Vector[Key],
      certs: Vector[Certificate] = Vector.empty,
      proposals: Vector[Proposal] = Vector.empty,
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])] = Vector.empty,
      donation: BigInt = 0,
      deposits: BigInt = 0,
      refunds: BigInt = 0,
      withdrawals: Vector[(RewardAccount, BigInt)] = Vector.empty
  ): Either[LedgerFailure, State] =
    val fee = BigInt(200)
    val change = st.fundCoin - fee - deposits + refunds - donation + withdrawals.map(_._2).sum
    val b = body(
      s"gov-${st.nonce}",
      Vector(st.fund),
      Vector(out(w.payer.address(), change)),
      fee,
      certificates = certs,
      withdrawals = withdrawals,
      votes = votes,
      proposals = proposals,
      donation = donation
    )
    TxRules(st.s, tx(b, w.payer +: signers), env(st.s, st.nonce)).map(next =>
      State(next, TxIn(b.id, 0), change, st.nonce + 1)
    )

  private def ok[A](r: Either[LedgerFailure, A]): A = r.fold(f => fail(f.message), identity)

  private def boundary(st: State): State =
    val next = Epoch.boundary(st.s, st.s.epoch + 1, globals)
    st.copy(s = next)

  private def encodedInt(v: BigInt): Bytes =
    Cbor.encode(CborValue.UInt(v)).fold(e => fail(e), identity)

  private def returnAccount(w: World, i: Int = 0): RewardAccount =
    RewardAccount(0, w.stakes(i).credential)

  private def proposal(w: World, action: GovAction, i: Int = 0): Proposal =
    Proposal(w.params.govActionDeposit, returnAccount(w, i), action)

  private def drepVote(k: Key, id: GovActionId, v: Vote) =
    (Voter.DRepVoter(k.credential), Vector(id -> v))

  private def poolVote(k: Key, id: GovActionId, v: Vote) = (Voter.Pool(k.hash), Vector(id -> v))

  private def assertInvariants(st: State, label: String): Unit =
    assertEquals(st.s.total, Supply, s"$label: supply")
    assertEquals(st.s.deposited, Epoch.obligation(st.s), s"$label: deposit pot")

  /** The id of governance action `index` of the last transaction submitted. */
  private def idOf(st: State, index: Int): GovActionId =
    GovActionId(Blake2b.hash256.hash(Bytes(s"gov-${st.nonce - 1}".getBytes.toVector)), index)

  // ---------------------------------------------------------------------------------------------

  test("a treasury withdrawal that the DReps accept is enacted two boundaries after the vote") {
    val w = World(10)
    val target = w.stakes(1)
    val action = GovAction.TreasuryWithdrawals(
      Vector(RewardAccount(0, target.credential) -> (10 * ada)),
      None
    )
    val s0 = w.initial
    assertInvariants(s0, "start")
    val s1 = ok(
      submit(w, s0, Vector.empty, proposals = Vector(proposal(w, action)), deposits = 1000 * ada)
    )
    assertInvariants(s1, "proposed")
    val id = idOf(s1, 0)
    val s2 = ok(
      submit(
        w,
        s1,
        w.dreps,
        votes = w.dreps.map(drepVote(_, id, Vote.Yes))
      )
    )
    val e6 = boundary(s2)
    assertInvariants(e6, "epoch 6")
    val ratified = e6.s.gov.pulsed.get._2
    assertEquals(ratified.enacted.map(_.id), Vector(id))
    // nothing moves until the next boundary
    assertEquals(e6.s.treasury, 100000 * ada)
    val e7 = boundary(e6)
    assertInvariants(e7, "epoch 7")
    assertEquals(e7.s.treasury, 100000 * ada - 10 * ada)
    assertEquals(
      e7.s.accounts(target.credential).balance,
      10 * ada,
      "the withdrawal reaches the account"
    )
    assertEquals(
      e7.s.accounts(w.stakes(0).credential).balance,
      1000 * ada,
      "the deposit goes back to the return account"
    )
    assert(e7.s.gov.proposals.actions.isEmpty)
  }

  test("an info action is never ratified: it expires and its deposit is returned") {
    val w = World(10)
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, GovAction.Info)),
        deposits = 1000 * ada
      )
    )
    val id = idOf(s1, 0)
    val s2 = ok(
      submit(
        w,
        s1,
        w.dreps ++ w.pools,
        votes = w.dreps.map(drepVote(_, id, Vote.Yes)) ++ w.pools.map(poolVote(_, id, Vote.Yes))
      )
    )
    // proposed in epoch 5 with lifetime 3: expires after epoch 8
    val steps = Iterator.iterate(s2)(boundary).take(6).toVector
    steps.foreach(st => assertInvariants(st, s"epoch ${st.s.epoch}"))
    // epoch 8 is the last of its life; the pulser of epoch 9 marks it expired; the boundary into 10
    // removes it and returns the deposit
    assert(steps(4).s.gov.proposals.actions.nonEmpty)
    val e10 = steps(5)
    assertEquals(e10.s.epoch, BigInt(10))
    assert(e10.s.gov.proposals.actions.isEmpty, "expired and removed")
    assertEquals(e10.s.accounts(w.stakes(0).credential).balance, 1000 * ada)
    assertEquals(e10.s.deposited, Epoch.obligation(e10.s))
  }

  test("a parameter change is enacted and removes its competitor, refunding both deposits") {
    val w = World(10)
    val a1 = GovAction.ParameterChange(None, ParamUpdate(Map(16 -> encodedInt(7 * ada))), None)
    val a2 = GovAction.ParameterChange(None, ParamUpdate(Map(16 -> encodedInt(9 * ada))), None)
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, a1, 0), proposal(w, a2, 1)),
        deposits = 2000 * ada
      )
    )
    val id1 = idOf(s1, 0)
    val s2 = ok(submit(w, s1, w.dreps, votes = w.dreps.take(2).map(drepVote(_, id1, Vote.Yes))))
    val e6 = boundary(s2)
    val e7 = boundary(e6)
    assertInvariants(e7, "enacted")
    assertEquals(e7.s.params.minPoolCost, 7 * ada)
    assertEquals(e7.s.params.minFeeA, w.params.minFeeA)
    assertEquals(e7.s.gov.proposals.roots.paramUpdate, Some(id1))
    assert(e7.s.gov.proposals.actions.isEmpty, "the competing proposal is gone")
    assertEquals(e7.s.accounts(w.stakes(0).credential).balance, 1000 * ada)
    assertEquals(e7.s.accounts(w.stakes(1).credential).balance, 1000 * ada)
    assertEquals(e7.s.prevParams.map(_.minPoolCost), Some(BigInt(0)))
  }

  test("a hard fork needs the pools as well as the DReps") {
    val w = World(10)
    val fork = GovAction.HardFork(None, 11, 0)
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, fork)),
        deposits = 1000 * ada
      )
    )
    val id = idOf(s1, 0)
    // DReps alone: not enough
    val dOnly = ok(submit(w, s1, w.dreps, votes = w.dreps.map(drepVote(_, id, Vote.Yes))))
    val e6 = boundary(dOnly)
    assertEquals(e6.s.gov.pulsed.get._2.enacted, Vector.empty)
    // both pools (each holds half of the stake of the three accounts' pools) and the DReps
    val both = ok(
      submit(
        w,
        s1,
        w.dreps ++ w.pools,
        votes = w.dreps.map(drepVote(_, id, Vote.Yes)) ++ w.pools.map(poolVote(_, id, Vote.Yes))
      )
    )
    val f7 = boundary(boundary(both))
    assertInvariants(f7, "forked")
    assertEquals(f7.s.params.version, (BigInt(11), BigInt(0)))
    assertEquals(f7.s.gov.proposals.roots.hardFork, Some(id))
  }

  test("during the bootstrap phase DRep thresholds are zero and DRep votes are refused") {
    val w = World(9)
    val s0 = w.initial
    val info = proposal(w, GovAction.Info)
    val s1 = ok(submit(w, s0, Vector.empty, proposals = Vector(info), deposits = 1000 * ada))
    val id = idOf(s1, 0)
    // a DRep may vote on an info action, but not on anything else in the bootstrap phase
    ok(submit(w, s1, w.dreps, votes = Vector(drepVote(w.dreps(0), id, Vote.Yes))))
    val param = GovAction.ParameterChange(None, ParamUpdate(Map(24 -> encodedInt(4))), None)
    val p1 =
      ok(submit(w, s1, Vector.empty, proposals = Vector(proposal(w, param)), deposits = 1000 * ada))
    val pid = idOf(p1, 0)
    val denied = submit(w, p1, w.dreps, votes = Vector(drepVote(w.dreps(0), pid, Vote.Yes)))
    assertEquals(
      denied.left.map { case LedgerFailure.Invalid(_, r, _) => r; case _ => "?" },
      Left("DisallowedVotesDuringBootstrap")
    )
    // treasury withdrawals cannot even be proposed
    val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 1) -> ada), None)
    val refused =
      submit(w, s1, Vector.empty, proposals = Vector(proposal(w, tw)), deposits = 1000 * ada)
    assertEquals(
      refused.left.map { case LedgerFailure.Invalid(_, r, _) => r; case _ => "?" },
      Left("DisallowedProposalDuringBootstrap")
    )
    // the zero DRep threshold lets a parameter change nobody voted for through (the committee and
    // pools do not stand in the way: the pools may not vote on a non-security parameter)
    val e7 = boundary(boundary(p1))
    assertEquals(e7.s.params.maxCollateralInputs, BigInt(4))
  }

  test(
    "a new committee is elected, its members authorise hot keys and then gate treasury withdrawals"
  ) {
    val w = World(10)
    val members = w.committeeCold.map(_.credential -> BigInt(60)).toMap
    val update = GovAction.UpdateCommittee(None, Set.empty, members, Rational(1, 2))
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, update)),
        deposits = 1000 * ada
      )
    )
    val id = idOf(s1, 0)
    val s2 = ok(
      submit(
        w,
        s1,
        w.dreps ++ w.pools,
        votes = w.dreps.map(drepVote(_, id, Vote.Yes)) ++ w.pools.map(poolVote(_, id, Vote.Yes))
      )
    )
    val e7 = boundary(boundary(s2))
    assertInvariants(e7, "elected")
    assertEquals(e7.s.gov.committee.map(_.members.keySet), Some(members.keySet))
    assertEquals(e7.s.gov.committee.map(_.threshold), Some(Rational(1, 2)))
    // hot key authorisation
    val auth = w.committeeCold.zip(w.committeeHot).map { case (c, h) =>
      Certificate.CommitteeAuthorize(c.credential, h.credential)
    }
    val e7a = ok(submit(w, e7, w.committeeCold, certs = auth))
    assertEquals(e7a.s.committeeState.size, 2)
    // a withdrawal now needs the committee: DReps alone are not enough
    val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 2) -> (5 * ada)), None)
    val t1 =
      ok(submit(w, e7a, Vector.empty, proposals = Vector(proposal(w, tw)), deposits = 1000 * ada))
    val tid = idOf(t1, 0)
    val dOnly = ok(submit(w, t1, w.dreps, votes = w.dreps.map(drepVote(_, tid, Vote.Yes))))
    val none = boundary(boundary(dOnly))
    assertEquals(none.s.treasury, 100000 * ada, "no committee vote, no withdrawal")
    // with one of the two members voting yes the ratio is 1/2, which meets the threshold
    val withCommittee = ok(
      submit(
        w,
        t1,
        w.dreps ++ w.committeeHot,
        votes = w.dreps.map(drepVote(_, tid, Vote.Yes)) :+
          (Voter.CommitteeHot(w.committeeHot(0).credential), Vector(tid -> Vote.Yes))
      )
    )
    val paid = boundary(boundary(withCommittee))
    assertInvariants(paid, "paid")
    assertEquals(paid.s.treasury, 100000 * ada - 5 * ada)
  }

  test("no confidence removes the committee; a constitution cannot pass without one") {
    val w = World(10)
    val noConfidence = GovAction.NoConfidence(None)
    val constitution = GovAction.NewConstitution(
      None,
      Some(Bytes(Vector.fill(28)(5.toByte))),
      Anchor("https://c.invalid", Bytes(Vector.fill(32)(9.toByte)))
    )
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, noConfidence), proposal(w, constitution, 1)),
        deposits = 2000 * ada
      )
    )
    val a = idOf(s1, 0)
    val b = idOf(s1, 1)
    val s2 = ok(
      submit(
        w,
        s1,
        w.dreps ++ w.pools,
        votes = w.dreps.flatMap(d => Vector(drepVote(d, a, Vote.Yes), drepVote(d, b, Vote.Yes))) ++
          w.pools.map(poolVote(_, a, Vote.Yes))
      )
    )
    val e6 = boundary(s2)
    // the delaying no-confidence action is first in line, so the constitution waits behind it
    assertEquals(e6.s.gov.pulsed.get._2.enacted.map(_.id), Vector(a))
    assert(e6.s.gov.pulsed.get._2.delayed)
    val e7 = boundary(e6)
    assertInvariants(e7, "after")
    assertEquals(e7.s.gov.committee, None)
    assertEquals(e7.s.gov.proposals.roots.committee, Some(a))
    val e8 = boundary(e7)
    assertEquals(e8.s.gov.constitution.script, None, "no committee, no constitution")
    assertInvariants(e8, "still pending")
  }

  test(
    "a new constitution is recorded when the DReps accept it and the committee threshold is zero"
  ) {
    val w = World(10)
    val constitution = GovAction.NewConstitution(
      None,
      Some(Bytes(Vector.fill(28)(5.toByte))),
      Anchor("https://c.invalid", Bytes(Vector.fill(32)(9.toByte)))
    )
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, constitution)),
        deposits = 1000 * ada
      )
    )
    val b = idOf(s1, 0)
    val s2 = ok(submit(w, s1, w.dreps, votes = w.dreps.map(drepVote(_, b, Vote.Yes))))
    val e7 = boundary(boundary(s2))
    assertInvariants(e7, "after")
    assertEquals(e7.s.gov.constitution.script, Some(Bytes(Vector.fill(28)(5.toByte))))
    assertEquals(e7.s.gov.constitution.anchor.url, "https://c.invalid")
    assertEquals(e7.s.gov.proposals.roots.constitution, Some(b))
  }

  test(
    "DRep registration, delegation, update and retirement keep the reverse index and the deposits"
  ) {
    val w = World(10)
    val newDRep = key()
    val voterStake = w.stakes(2)
    val s0 = w.initial
    val reg = ok(
      submit(
        w,
        s0,
        Vector(newDRep),
        certs = Vector(Certificate.RegisterDRep(newDRep.credential, 500 * ada, None)),
        deposits = 500 * ada
      )
    )
    assertInvariants(reg, "registered")
    val del = ok(
      submit(
        w,
        reg,
        Vector(voterStake),
        certs = Vector(
          Certificate.Delegate(
            voterStake.credential,
            None,
            Some(DRep.Registered(newDRep.credential)),
            None
          )
        )
      )
    )
    assertEquals(del.s.dreps(newDRep.credential).delegs, Set(voterStake.credential))
    // PV10: the old DRep forgets the delegator
    assert(!del.s.dreps(w.dreps(2).credential).delegs.contains(voterStake.credential))
    val retire = ok(
      submit(
        w,
        del,
        Vector(newDRep),
        certs = Vector(Certificate.UnregisterDRep(newDRep.credential, 500 * ada)),
        refunds = 500 * ada
      )
    )
    assertInvariants(retire, "retired")
    assertEquals(retire.s.accounts(voterStake.credential).drep, None)
    assert(!retire.s.dreps.contains(newDRep.credential))
  }

  test("a treasury donation joins the treasury at the epoch boundary and conserves the supply") {
    val w = World(10)
    val d = ok(submit(w, w.initial, Vector.empty, donation = 123 * ada))
    assertEquals(d.s.donation, 123 * ada)
    assertInvariants(d, "donated")
    val next = boundary(d)
    assertEquals(next.s.donation, BigInt(0))
    assertEquals(next.s.treasury, d.s.treasury + 123 * ada)
    assertInvariants(next, "moved")
  }

  test("a transaction names the treasury only if it is right") {
    val w = World(10)
    val s0 = w.initial
    val b = body(
      "t",
      Vector(s0.fund),
      Vector(out(w.payer.address(), s0.fundCoin - 200)),
      200,
      currentTreasury = Some(5)
    )
    assertEquals(
      TxRules(s0.s, tx(b, Vector(w.payer)), env(s0.s, 0)).left.map {
        case LedgerFailure.Invalid(_, r, _) => r; case _ => "?"
      },
      Left("ConwayTreasuryValueMismatch")
    )
    val right = body(
      "t2",
      Vector(s0.fund),
      Vector(out(w.payer.address(), s0.fundCoin - 200)),
      200,
      currentTreasury = Some(s0.s.treasury)
    )
    assert(TxRules(s0.s, tx(right, Vector(w.payer)), env(s0.s, 0)).isRight)
  }

  test("the reference-script fee grows by 1.2 for every 25 KiB") {
    val base = Rational(15)
    assertEquals(ConwayRules.tierRefScriptFee(base, 0), BigInt(0))
    assertEquals(ConwayRules.tierRefScriptFee(base, 1000), BigInt(15000))
    assertEquals(ConwayRules.tierRefScriptFee(base, 25600), BigInt(15 * 25600))
    // 25600 at 15 plus 400 at 18
    assertEquals(ConwayRules.tierRefScriptFee(base, 26000), BigInt(15 * 25600 + 400 * 18))
    assertEquals(
      ConwayRules.tierRefScriptFee(base, 51200),
      BigInt(15 * 25600 + 18 * 25600)
    )
  }

  property("supply and deposit pot survive any mix of votes, proposals and boundaries") {
    val voteGen = Gen.oneOf(Vote.Yes, Vote.No, Vote.Abstain)
    forAll(Gen.listOfN(3, voteGen), Gen.listOfN(2, voteGen), Gen.choose(1, 5)) { (dv, pv, steps) =>
      val w = World(10)
      val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 1) -> (3 * ada)), None)
      val param = GovAction.ParameterChange(None, ParamUpdate(Map(16 -> encodedInt(ada))), None)
      val s1 = ok(
        submit(
          w,
          w.initial,
          Vector.empty,
          proposals =
            Vector(proposal(w, tw), proposal(w, param, 1), proposal(w, GovAction.Info, 2)),
          deposits = 3000 * ada
        )
      )
      val ids = (0 until 3).map(idOf(s1, _))
      val s2 = ok(
        submit(
          w,
          s1,
          w.dreps ++ w.pools,
          votes = w.dreps.zip(dv).map { case (d, v) =>
            (Voter.DRepVoter(d.credential), ids.map(_ -> v).toVector)
          } ++
            w.pools.zip(pv).map { case (p, v) => (Voter.Pool(p.hash), Vector(ids(2) -> v)) }
        )
      )
      val states = Iterator.iterate(s2)(boundary).take(steps + 1).toVector
      states.forall(st => st.s.total == Supply && st.s.deposited == Epoch.obligation(st.s))
    }
  }

  property(
    "DRep and delegation certificates keep the supply, the deposit pot and the reverse index"
  ) {
    forAll(Gen.listOfN(14, Gen.choose(0, 4)), Gen.listOfN(14, Gen.choose(0, 9))) { (ops, picks) =>
      val w = World(10)
      var st = w.initial
      var extra = Vector.empty[Key]
      def pick(n: Int, size: Int) = if size == 0 then 0 else n % size
      ops.zip(picks).foreach { case (op, p) =>
        val staker = w.stakes(p % 3)
        val result: Option[Either[LedgerFailure, State]] = op match
          case 0 =>
            val k = key()
            Some(
              submit(
                w,
                st,
                Vector(k),
                certs = Vector(Certificate.RegisterDRep(k.credential, 500 * ada, None)),
                deposits = 500 * ada
              ).map { next =>
                extra = extra :+ k; next
              }
            )
          case 1 if extra.nonEmpty =>
            val k = extra(pick(p, extra.size))
            Some(
              submit(
                w,
                st,
                Vector(k),
                certs = Vector(Certificate.UnregisterDRep(k.credential, 500 * ada)),
                refunds = 500 * ada
              ).map { next =>
                extra = extra.filterNot(_ == k); next
              }
            )
          case 2 =>
            val all = (w.dreps ++ extra).filter(k => st.s.dreps.contains(k.credential))
            val target = all(pick(p, all.size))
            Some(
              submit(
                w,
                st,
                Vector(staker),
                certs = Vector(
                  Certificate.Delegate(
                    staker.credential,
                    None,
                    Some(DRep.Registered(target.credential)),
                    None
                  )
                )
              )
            )
          case 3 =>
            Some(
              submit(
                w,
                st,
                Vector(staker),
                certs = Vector(
                  Certificate.Delegate(staker.credential, None, Some(DRep.AlwaysAbstain), None)
                )
              )
            )
          case _ if extra.nonEmpty =>
            val k = extra(pick(p, extra.size))
            Some(
              submit(w, st, Vector(k), certs = Vector(Certificate.UpdateDRep(k.credential, None)))
            )
          case _ => None
        result.foreach(r => st = ok(r))
        assertEquals(st.s.total, Supply)
        assertEquals(st.s.deposited, Epoch.obligation(st.s))
        st.s.accounts.toMap.foreach { case (c, a) =>
          a.drep.foreach {
            case DRep.Registered(d) => assert(st.s.dreps(d).delegs.contains(c))
            case _                  => ()
          }
        }
        st.s.dreps.toMap.foreach { case (d, state) =>
          state.delegs.foreach(c => assertEquals(st.s.accounts(c).drep, Some(DRep.Registered(d))))
        }
      }
      true
    }
  }

  // -------------------------------------------------------------------------------------------
  // Protocol-version 11 rules (roadmap B5). The same transactions are refused by the reference node
  // in the devnet scenarios (scripts/devnet/govextra.py); the expected rule names are the ones its
  // predicate failures carry.

  private def rejection(r: Either[LedgerFailure, State]): String = r match
    case Left(LedgerFailure.Invalid(_, rule, _)) => rule
    case Left(other)                             => other.message
    case Right(_)                                => "accepted"

  test(
    "committee hot keys: authorised but unelected keys may vote before protocol version 11 only"
  ) {
    def scenario(pv: Int) =
      val w = World(pv)
      val member = w.committeeCold(0)
      val hot = w.committeeHot(0)
      val update =
        GovAction.UpdateCommittee(None, Set.empty, Map(member.credential -> BigInt(60)), half)
      val s1 = ok(
        submit(
          w,
          w.initial,
          Vector.empty,
          proposals = Vector(proposal(w, update), proposal(w, GovAction.Info)),
          deposits = 2000 * ada
        )
      )
      val updateId = idOf(s1, 0)
      val infoId = idOf(s1, 1)
      // the proposed member may authorise a hot key while the update is pending
      val s2 = ok(
        submit(
          w,
          s1,
          Vector(member),
          certs = Vector(Certificate.CommitteeAuthorize(member.credential, hot.credential))
        )
      )
      def vote(st: State, id: GovActionId) =
        submit(
          w,
          st,
          Vector(hot),
          votes = Vector((Voter.CommitteeHot(hot.credential), Vector(id -> Vote.Yes)))
        )
      val early = rejection(vote(s2, infoId))
      // elect the member, then the same vote is accepted at every version
      val s3 = ok(
        submit(
          w,
          s2,
          w.dreps ++ w.pools,
          votes = w.dreps.map(drepVote(_, updateId, Vote.Yes)) ++ w.pools.map(
            poolVote(_, updateId, Vote.Yes)
          )
        )
      )
      val elected = boundary(boundary(s3))
      assertEquals(elected.s.gov.committee.map(_.members.keySet), Some(Set(member.credential)))
      // the epoch boundary dropped the authorisation of a member who was not yet elected: authorise again
      assertEquals(elected.s.committeeState, Map.empty[Credential, CommitteeAuth])
      val again = ok(
        submit(
          w,
          elected,
          Vector(member),
          certs = Vector(Certificate.CommitteeAuthorize(member.credential, hot.credential))
        )
      )
      val s4 = ok(
        submit(
          w,
          again,
          Vector.empty,
          proposals = Vector(proposal(w, GovAction.Info)),
          deposits = 1000 * ada
        )
      )
      (early, rejection(vote(s4, idOf(s4, 0))))
    assertEquals(scenario(10), ("accepted", "accepted"))
    assertEquals(scenario(11), ("UnelectedCommitteeVoters", "accepted"))
  }

  test("committee hot keys: with no committee every hot key is unelected at protocol version 11") {
    val w = World(11)
    val member = w.committeeCold(0)
    val hot = w.committeeHot(0)
    val update =
      GovAction.UpdateCommittee(None, Set.empty, Map(member.credential -> BigInt(60)), half)
    val s1 = ok(
      submit(
        w,
        w.initial,
        Vector.empty,
        proposals = Vector(proposal(w, update), proposal(w, GovAction.Info)),
        deposits = 2000 * ada
      )
    )
    val s2 = ok(
      submit(
        w,
        s1,
        Vector(member),
        certs = Vector(Certificate.CommitteeAuthorize(member.credential, hot.credential))
      )
    )
    val refused = submit(
      w,
      s2,
      Vector(hot),
      votes = Vector((Voter.CommitteeHot(hot.credential), Vector(idOf(s1, 1) -> Vote.Yes)))
    )
    assertEquals(rejection(refused), "UnelectedCommitteeVoters")
  }

  test("protocol version 11: a pool registration may not take a VRF key hash another pool holds") {
    def register(w: World, st: State, op: Key, vrf: Bytes, fresh: Boolean) =
      val params = w.poolParams(op).copy(vrfKeyHash = vrf)
      submit(
        w,
        st,
        Vector(op, w.poolOwner),
        certs = Vector(Certificate.RegisterPool(params)),
        deposits = if fresh then w.params.poolDeposit else 0
      )
    def vrfOf(i: Int, w: World) = w.poolParams(w.pools(i)).vrfKeyHash
    def fresh(n: Int) = Blake2b.hash256.hash(Bytes(Vector(0x56, n.toByte)))
    val f1 = fresh(1)
    val f2 = fresh(2)
    val w = World(11)
    val a = key()
    val b = key()
    val s0 = w.initial
    // an active pool's key hash is taken, a free one is not
    assertEquals(
      rejection(register(w, s0, a, vrfOf(0, w), fresh = true)),
      "VRFKeyHashAlreadyRegistered"
    )
    val s1 = ok(register(w, s0, a, f1, fresh = true))
    assertEquals(rejection(register(w, s1, b, f1, fresh = true)), "VRFKeyHashAlreadyRegistered")
    // a pool keeps its own key hash when it re-registers, and may not take another pool's
    val s2 = ok(register(w, s1, a, f1, fresh = false))
    assertEquals(
      rejection(register(w, s2, a, vrfOf(1, w), fresh = false)),
      "VRFKeyHashAlreadyRegistered"
    )
    // a key hash scheduled by a re-registration is taken too, until the pool goes back to its own
    val s3 = ok(register(w, s2, a, f2, fresh = false))
    assertEquals(rejection(register(w, s3, b, f2, fresh = true)), "VRFKeyHashAlreadyRegistered")
    val s4 = ok(register(w, s3, a, f1, fresh = false))
    val s5 = ok(register(w, s4, b, f2, fresh = true))
    assertEquals(rejection(register(w, s5, b, f1, fresh = false)), "VRFKeyHashAlreadyRegistered")
    // the scheduled change takes effect at the boundary; the other pool keeps its key hash
    assertEquals(boundary(s5).s.pools(a.hash).params.vrfKeyHash, f1)
  }

  test("before protocol version 11 two pools may share a VRF key hash") {
    val w = World(10)
    val a = key()
    val params = w.poolParams(a).copy(vrfKeyHash = w.poolParams(w.pools(0)).vrfKeyHash)
    val s = ok(
      submit(
        w,
        w.initial,
        Vector(a, w.poolOwner),
        certs = Vector(Certificate.RegisterPool(params)),
        deposits = w.params.poolDeposit
      )
    )
    assertEquals(s.s.pools.size, 3)
  }
