// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Rational
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.*
import xyz.kd5ujc.scarab.ledger.rules.{
  Convert,
  EraLedger,
  EraNes,
  InstantStakeOps,
  Invariants,
  NativeRules,
  StakeDistribution
}
import xyz.kd5ujc.scarab.ledger.state as native
import xyz.kd5ujc.scarab.ledger.state.{
  Build,
  ChainAccountState,
  ColdCommittee,
  CompactCoin,
  Conway,
  ConwayAccountState,
  ConwayAccounts,
  ConwayGovState,
  DRComplete,
  DRepRole,
  Empty,
  EpochNo,
  KeyHash,
  NewEpochState,
  PState,
  ShelleyBasedEra,
  Sorted,
  StakePool,
  Staking,
  VRFVerKeyHash,
  VState,
  VocabError
}

import TestLedger.*

/** Constructed governance scenarios: propose, vote, cross epoch boundaries, enact, with the supply
  * and the deposit pot checked after every step. The state is the native Conway state throughout:
  * the LEDGER rules run on each transaction and the tick ([[NativeRules.boundary]]) on each
  * boundary, and the state the rules leave is the next step's.
  */
class ConwayGovernanceSuite extends ScalaCheckSuite:
  private val ada = BigInt(1000000)
  private val half = Rational(1, 2)
  private val globals = LedgerGlobals(Supply, 100, Rational(1, 20), network = 0)

  /** A value the vocabulary must admit: the test's own keys and amounts. */
  private def must[A](v: Either[VocabError, A]): A = v.fold(e => fail(s"$e"), identity)

  private def staking(k: Key): native.Credential[Staking] =
    must(Convert.credential[Staking](k.credential))

  private def drepCredential(k: Key): native.Credential[DRepRole] =
    must(Convert.credential[DRepRole](k.credential))

  private def coldCredential(k: Key): native.Credential[ColdCommittee] =
    must(Convert.credential[ColdCommittee](k.credential))

  private def poolId(k: Key): KeyHash[StakePool] = must(Convert.keyHash[StakePool](k.hash))

  private def compact(n: BigInt): CompactCoin = must(CompactCoin.fromBigInt(n))

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
    val treasury: BigInt = 100000 * ada
    val params: ProtocolParams = TestLedger
      .params(pv)
      // no monetary expansion or treasury cut: the treasury moves only through governance
      .copy(
        drepDeposit = 500 * ada,
        drepActivity = 20,
        govActionDeposit = 1000 * ada,
        govActionLifetime = 3,
        committeeMaxTermLength = 100,
        committeeMinSize = 0,
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

    /** Stake account `i` delegates to pool `i % 2` and to DRep `i % 3`; the pool owner's account is
      * registered and delegates to neither. The pools list their delegators and the DReps theirs,
      * the deposit pot is the obligation, the mark snapshot is the stake distribution of the state,
      * and the reserves make the total the supply.
      */
    def initial: Step =
      val stakeUtxos = stakes.zipWithIndex.map { case (sk, i) =>
        txIn(100 + i) -> out(payer.baseAddress(sk), 1000 * ada)
      }
      val funded =
        NativeRules.stateIn[Conway](pv, stakeUtxos :+ (fundIn -> out(payer.address(), fundCoin)))
      val registered =
        pools.foldLeft(funded)((nes, p) => Build.pool(nes, poolParams(p), params.poolDeposit))
      val delegations = stakes.zipWithIndex.map { case (sk, i) =>
        (
          staking(sk),
          poolId(pools(i % 2)),
          must(Convert.drep(DRep.Registered(dreps(i % 3).credential)))
        )
      }
      val keyDeposit = compact(params.keyDeposit)
      val accounts = ConwayAccounts(
        Sorted.map(
          delegations.map((c, p, d) =>
            c -> ConwayAccountState(CompactCoin.fromUnsigned(0L), keyDeposit, Some(p), Some(d))
          ) :+ (staking(poolOwner) ->
            ConwayAccountState(CompactCoin.fromUnsigned(0L), keyDeposit, None, None))
        )
      )
      val ls = registered.es.ls
      val stakePools = ls.cert.pState.stakePools.transform((id, s) =>
        s.copy(delegators = Sorted.set(delegations.collect { case (c, p, _) if p == id => c }))
      )
      val drepStates = Sorted.map(dreps.zipWithIndex.map { case (k, i) =>
        drepCredential(k) -> native.DRepState(
          EpochNo.fromUnsigned(1000L),
          None,
          compact(params.drepDeposit),
          Sorted.set(stakes.zipWithIndex.collect { case (sk, j) if j % 3 == i => staking(sk) })
        )
      })
      val committee = Some(Committee(Map.empty, Rational.zero))
      val gov0 = ls.utxo.gov
      // the pulser of a state before its first boundary: the ratification of nothing over the
      // enact state as it stands
      val enact =
        EnactState(committee, gov0.constitution, params, params, treasury, Map.empty, PrevIds())
      val gov = gov0.copy(
        committee = committee,
        curPParams = params,
        prevPParams = params,
        drepPulsing = DRComplete(
          PulsingSnapshot.empty,
          RatifyState(enact, Vector.empty, Set.empty, delayed = false)
        )
      )
      val cert = ls.cert.copy(
        vState = ls.cert.vState.copy(dreps = drepStates),
        pState = ls.cert.pState.copy(stakePools = stakePools),
        dState = ls.cert.dState.copy(accounts = accounts)
      )
      val governed = ls.copy[Conway](cert = cert, utxo = ls.utxo.copy(gov = gov))
      val pending = registered.copy(es = registered.es.copy(ls = governed))
      val deposited = must(native.Coin.fromBigInt(NativeRules.obligation(pending)))
      val withDeposits = governed.copy[Conway](utxo = governed.utxo.copy(deposited = deposited))
      val mark = must(
        EraLedger
          .activeStake(withDeposits)
          .flatMap(StakeDistribution.snapshot(_, withDeposits.cert.pState.stakePools))
      )
      val unreserved = Step(
        registered.copy(es =
          registered.es.copy(
            chainAccount =
              ChainAccountState(must(native.Coin.fromBigInt(treasury)), native.Coin.zero),
            ls = withDeposits,
            snapshots = registered.es.snapshots.copy(mark = mark)
          )
        ),
        fundIn,
        fundCoin,
        0
      )
      val reserves = must(native.Coin.fromBigInt(Supply - unreserved.total))
      unreserved.copy(nes =
        unreserved.nes.copy(es =
          unreserved.nes.es
            .copy(chainAccount = unreserved.nes.es.chainAccount.copy(reserves = reserves))
        )
      )

  /** The native state of a step, with the output that funds the next transaction. */
  private final case class Step(
      nes: NewEpochState[Conway],
      fund: TxIn,
      fundCoin: BigInt,
      nonce: Int
  ):
    def gov: ConwayGovState = nes.es.ls.utxo.gov
    def vState: VState = nes.es.ls.cert.vState
    def pState: PState = nes.es.ls.cert.pState
    def accounts: SortedMap[native.Credential[Staking], ConwayAccountState] =
      nes.es.ls.cert.dState.accounts.states
    def epoch: BigInt = nes.epoch.toBigInt
    def params: ProtocolParams = gov.curPParams
    def treasury: BigInt = nes.es.chainAccount.treasury.value
    def donation: BigInt = nes.es.ls.utxo.donation.value
    def deposited: BigInt = nes.es.ls.utxo.deposited.value

    def account(k: Key): ConwayAccountState =
      accounts.getOrElse(staking(k), fail(s"no account of ${k.credential}"))

    def drepState(k: Key): native.DRepState =
      vState.dreps.getOrElse(drepCredential(k), fail(s"no DRep ${k.credential}"))

    /** Total value in existence; the supply when the ledger is consistent. */
    def total: BigInt =
      val u = nes.es.ls.utxo
      u.utxo.utxo.coin + u.fees.value + u.deposited.value + u.donation.value + treasury +
        nes.es.chainAccount.reserves.value + accounts.valuesIterator.map(_.balance.toBigInt).sum

  private def env(st: Step, n: Int): TxEnv =
    TxEnv(
      slot = st.epoch * 100 + 10,
      epoch = st.epoch,
      network = 0,
      firstSlotNextEpoch = st.epoch * 100 + 100,
      stabilityWindow = 20,
      txIndex = n,
      clock = Some(PosixClock.Total(slot => slot * 1000 + 1596059091000L))
    )

  /** Submit one transaction paid by `payer`; `deposits` and `refunds` balance the certificates. */
  private def submit(
      w: World,
      st: Step,
      signers: Vector[Key],
      certs: Vector[Certificate] = Vector.empty,
      proposals: Vector[Proposal] = Vector.empty,
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])] = Vector.empty,
      donation: BigInt = 0,
      deposits: BigInt = 0,
      refunds: BigInt = 0,
      withdrawals: Map[RewardAccount, BigInt] = Map.empty
  ): Either[LedgerFailure, Step] =
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
    NativeRules
      .txIn[Conway](st.nes, tx(b, w.payer +: signers), env(st, st.nonce))
      .map(next => Step(next.state, TxIn(b.id, 0), change, st.nonce + 1))

  private def ok[A](r: Either[LedgerFailure, A]): A = r.fold(f => fail(f.message), identity)

  /** The rule's refusal of a transaction, or `None` if it is accepted. */
  private def refusal[A](r: Either[LedgerFailure, A]): Option[PredicateFailure] = r match
    case Left(LedgerFailure.Rejected(_, f)) => Some(f)
    case Left(other)                        => fail(s"not a rule's refusal: ${other.message}")
    case Right(_)                           => None

  /** The state one epoch later: the tick into the next epoch, on the state the rules left. */
  private def boundary(st: Step): Step =
    NativeRules.boundary(EraNes.InEra(ShelleyBasedEra.SConway, st.nes), globals) match
      case next: EraNes.InEra[x] =>
        val nes = NewEpochState
          .retype(next.era, ShelleyBasedEra.SConway)(next.nes)
          .getOrElse(fail(s"the boundary left Conway for ${next.era}"))
        st.copy(nes = nes)

  private def returnAccount(w: World, i: Int): RewardAccount =
    RewardAccount(0, w.stakes(i).credential)

  private def proposal(w: World, action: GovAction, i: Int = 0): Proposal =
    Proposal(w.params.govActionDeposit, returnAccount(w, i), action)

  private def drepVote(k: Key, id: GovActionId, v: Vote) =
    (Voter.DRepVoter(k.credential), Vector(id -> v))

  private def poolVote(k: Key, id: GovActionId, v: Vote) = (Voter.Pool(k.hash), Vector(id -> v))

  /** What the rules keep incrementally and what it must agree with: the supply, the deposit pot
    * against the obligation, the pools' delegators against the accounts, and the instant stake
    * against the UTxO set.
    */
  private def assertInvariants(st: Step, label: String): Unit =
    assertEquals(st.total, Supply, s"$label: supply")
    assertEquals(st.deposited, NativeRules.obligation(st.nes), s"$label: deposit pot")
    assertEquals(
      Invariants.poolDelegators(st.nes.es.ls.cert),
      Vector.empty,
      s"$label: pool delegators"
    )
    val instant = must(
      InstantStakeOps.update(
        Empty.instantStake[Conway],
        st.nes.es.ls.utxo.utxo.utxo.valuesIterator.toVector,
        Nil
      )
    )
    assertEquals(st.nes.es.ls.utxo.instantStake, instant, s"$label: instant stake")

  /** The id of governance action `index` of the last transaction submitted. */
  private def idOf(st: Step, index: Int): GovActionId =
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
    val ratified = e6.gov.drepPulsing.ratify
    assertEquals(ratified.enacted.map(_.id), Vector(id))
    // nothing moves until the next boundary
    assertEquals(e6.treasury, 100000 * ada)
    val e7 = boundary(e6)
    assertInvariants(e7, "epoch 7")
    assertEquals(e7.treasury, 100000 * ada - 10 * ada)
    assertEquals(
      e7.account(target).balance.toBigInt,
      10 * ada,
      "the withdrawal reaches the account"
    )
    assertEquals(
      e7.account(w.stakes(0)).balance.toBigInt,
      1000 * ada,
      "the deposit goes back to the return account"
    )
    assert(e7.gov.proposals.actions.isEmpty)
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
    steps.foreach(st => assertInvariants(st, s"epoch ${st.epoch}"))
    // epoch 8 is the last of its life; the pulser of epoch 9 marks it expired; the boundary into 10
    // removes it and returns the deposit
    assert(steps(4).gov.proposals.actions.nonEmpty)
    val e10 = steps(5)
    assertEquals(e10.epoch, BigInt(10))
    assert(e10.gov.proposals.actions.isEmpty, "expired and removed")
    assertEquals(e10.account(w.stakes(0)).balance.toBigInt, 1000 * ada)
    assertEquals(e10.deposited, NativeRules.obligation(e10.nes))
  }

  test("a parameter change is enacted and removes its competitor, refunding both deposits") {
    val w = World(10)
    val a1 = GovAction.ParameterChange(None, conwayUpdate(16 -> (7 * ada)), None)
    val a2 = GovAction.ParameterChange(None, conwayUpdate(16 -> (9 * ada)), None)
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
    assertEquals(e7.params.minPoolCost, 7 * ada)
    assertEquals(e7.params.minFeeA, w.params.minFeeA)
    assertEquals(e7.gov.proposals.roots.paramUpdate, Some(id1))
    assert(e7.gov.proposals.actions.isEmpty, "the competing proposal is gone")
    assertEquals(e7.account(w.stakes(0)).balance.toBigInt, 1000 * ada)
    assertEquals(e7.account(w.stakes(1)).balance.toBigInt, 1000 * ada)
    assertEquals(e7.gov.prevPParams.minPoolCost, BigInt(0))
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
    assertEquals(e6.gov.drepPulsing.ratify.enacted, Vector.empty)
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
    assertEquals(f7.params.version, (BigInt(11), BigInt(0)))
    assertEquals(f7.gov.proposals.roots.hardFork, Some(id))
  }

  test("during the bootstrap phase DRep thresholds are zero and DRep votes are refused") {
    val w = World(9)
    val s0 = w.initial
    val info = proposal(w, GovAction.Info)
    val s1 = ok(submit(w, s0, Vector.empty, proposals = Vector(info), deposits = 1000 * ada))
    val id = idOf(s1, 0)
    // a DRep may vote on an info action, but not on anything else in the bootstrap phase
    ok(submit(w, s1, w.dreps, votes = Vector(drepVote(w.dreps(0), id, Vote.Yes))))
    val param = GovAction.ParameterChange(None, conwayUpdate(24 -> 4), None)
    val p1 =
      ok(submit(w, s1, Vector.empty, proposals = Vector(proposal(w, param)), deposits = 1000 * ada))
    val pid = idOf(p1, 0)
    val denied = submit(w, p1, w.dreps, votes = Vector(drepVote(w.dreps(0), pid, Vote.Yes)))
    assertEquals(
      refusal(denied),
      Some(
        PredicateFailure.gov(
          GovFailure.DisallowedVotesDuringBootstrap(
            Vector(Voter.DRepVoter(w.dreps(0).credential) -> pid)
          )
        )
      )
    )
    // treasury withdrawals cannot even be proposed
    val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 1) -> ada), None)
    val refused =
      submit(w, s1, Vector.empty, proposals = Vector(proposal(w, tw)), deposits = 1000 * ada)
    assertEquals(
      refusal(refused),
      Some(PredicateFailure.gov(GovFailure.DisallowedProposalDuringBootstrap(proposal(w, tw))))
    )
    // the zero DRep threshold lets a parameter change nobody voted for through (the committee and
    // pools do not stand in the way: the pools may not vote on a non-security parameter)
    val e7 = boundary(boundary(p1))
    assertEquals(e7.params.maxCollateralInputs, BigInt(4))
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
    assertEquals(e7.gov.committee.map(_.members.keySet), Some(members.keySet))
    assertEquals(e7.gov.committee.map(_.threshold), Some(Rational(1, 2)))
    // hot key authorisation
    val auth = w.committeeCold.zip(w.committeeHot).map { case (c, h) =>
      Certificate.CommitteeAuthorize(c.credential, h.credential)
    }
    val e7a = ok(submit(w, e7, w.committeeCold, certs = auth))
    assertEquals(
      e7a.vState.committeeState.creds.keySet.toSet,
      w.committeeCold.map(coldCredential).toSet
    )
    // a withdrawal now needs the committee: DReps alone are not enough
    val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 2) -> (5 * ada)), None)
    val t1 =
      ok(submit(w, e7a, Vector.empty, proposals = Vector(proposal(w, tw)), deposits = 1000 * ada))
    val tid = idOf(t1, 0)
    val dOnly = ok(submit(w, t1, w.dreps, votes = w.dreps.map(drepVote(_, tid, Vote.Yes))))
    val none = boundary(boundary(dOnly))
    assertEquals(none.treasury, 100000 * ada, "no committee vote, no withdrawal")
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
    assertEquals(paid.treasury, 100000 * ada - 5 * ada)
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
    assertEquals(e6.gov.drepPulsing.ratify.enacted.map(_.id), Vector(a))
    assert(e6.gov.drepPulsing.ratify.delayed)
    val e7 = boundary(e6)
    assertInvariants(e7, "after")
    assertEquals(e7.gov.committee, None)
    assertEquals(e7.gov.proposals.roots.committee, Some(a))
    val e8 = boundary(e7)
    assertEquals(e8.gov.constitution.script, None, "no committee, no constitution")
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
    assertEquals(e7.gov.constitution.script, Some(Bytes(Vector.fill(28)(5.toByte))))
    assertEquals(e7.gov.constitution.anchor.url, "https://c.invalid")
    assertEquals(e7.gov.proposals.roots.constitution, Some(b))
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
    assertEquals(del.drepState(newDRep).delegators.toSet, Set(staking(voterStake)))
    // PV10: the old DRep forgets the delegator
    assert(!del.drepState(w.dreps(2)).delegators.contains(staking(voterStake)))
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
    assertEquals(retire.account(voterStake).drep, None)
    assert(!retire.vState.dreps.contains(drepCredential(newDRep)))
  }

  test("a treasury donation joins the treasury at the epoch boundary and conserves the supply") {
    val w = World(10)
    val d = ok(submit(w, w.initial, Vector.empty, donation = 123 * ada))
    assertEquals(d.donation, 123 * ada)
    assertInvariants(d, "donated")
    val next = boundary(d)
    assertEquals(next.donation, BigInt(0))
    assertEquals(next.treasury, d.treasury + 123 * ada)
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
      refusal(NativeRules.txIn[Conway](s0.nes, tx(b, Vector(w.payer)), env(s0, 0))),
      Some(PredicateFailure.TreasuryValueMismatch(Mismatch(BigInt(5), s0.treasury)))
    )
    val right = body(
      "t2",
      Vector(s0.fund),
      Vector(out(w.payer.address(), s0.fundCoin - 200)),
      200,
      currentTreasury = Some(s0.treasury)
    )
    assert(NativeRules.txIn[Conway](s0.nes, tx(right, Vector(w.payer)), env(s0, 0)).isRight)
  }

  test("the reference-script fee grows by 1.2 for every 25 KiB") {
    val base = Rational(15)
    assertEquals(RefScripts.tierRefScriptFee(base, 0), BigInt(0))
    assertEquals(RefScripts.tierRefScriptFee(base, 1000), BigInt(15000))
    assertEquals(RefScripts.tierRefScriptFee(base, 25600), BigInt(15 * 25600))
    // 25600 at 15 plus 400 at 18
    assertEquals(RefScripts.tierRefScriptFee(base, 26000), BigInt(15 * 25600 + 400 * 18))
    assertEquals(
      RefScripts.tierRefScriptFee(base, 51200),
      BigInt(15 * 25600 + 18 * 25600)
    )
  }

  property("supply and deposit pot survive any mix of votes, proposals and boundaries") {
    val voteGen = Gen.oneOf(Vote.Yes, Vote.No, Vote.Abstain)
    forAll(Gen.listOfN(3, voteGen), Gen.listOfN(2, voteGen), Gen.choose(1, 5)) { (dv, pv, steps) =>
      val w = World(10)
      val tw = GovAction.TreasuryWithdrawals(Vector(returnAccount(w, 1) -> (3 * ada)), None)
      val param = GovAction.ParameterChange(None, conwayUpdate(16 -> ada), None)
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
      states.forall(st => st.total == Supply && st.deposited == NativeRules.obligation(st.nes))
    }
  }

  /** Every account that delegates to a registered DRep is among its delegators, and every delegator
    * of a DRep delegates to it.
    */
  private def assertDRepIndex(st: Step): Unit =
    st.accounts.foreach { (c, a) =>
      a.drep.foreach {
        case native.DRep.DRepKeyHash(h) =>
          assert(st.vState.dreps.get(native.Credential.KeyHashObj(h)).exists(_.delegators(c)))
        case native.DRep.DRepScriptHash(h) =>
          assert(st.vState.dreps.get(native.Credential.ScriptHashObj(h)).exists(_.delegators(c)))
        case native.DRep.DRepAlwaysAbstain | native.DRep.DRepAlwaysNoConfidence => ()
      }
    }
    st.vState.dreps.foreach { (d, state) =>
      val delegated = d match
        case native.Credential.KeyHashObj(h)    => native.DRep.DRepKeyHash(h)
        case native.Credential.ScriptHashObj(h) => native.DRep.DRepScriptHash(h)
      state.delegators.foreach(c =>
        assertEquals(st.accounts.get(c).flatMap(_.drep), Some(delegated))
      )
    }

  property(
    "DRep and delegation certificates keep the supply, the deposit pot and the reverse index"
  ) {
    forAll(Gen.listOfN(14, Gen.choose(0, 4)), Gen.listOfN(14, Gen.choose(0, 9))) { (ops, picks) =>
      val w = World(10)
      def pick(n: Int, size: Int) = if size == 0 then 0 else n % size
      ops.zip(picks).foldLeft((w.initial, Vector.empty[Key])) { case ((st, extra), (op, p)) =>
        val staker = w.stakes(p % 3)
        val (next, more) = op match
          case 0 =>
            val k = key()
            val registered = submit(
              w,
              st,
              Vector(k),
              certs = Vector(Certificate.RegisterDRep(k.credential, 500 * ada, None)),
              deposits = 500 * ada
            )
            (ok(registered), extra :+ k)
          case 1 if extra.nonEmpty =>
            val k = extra(pick(p, extra.size))
            val retired = submit(
              w,
              st,
              Vector(k),
              certs = Vector(Certificate.UnregisterDRep(k.credential, 500 * ada)),
              refunds = 500 * ada
            )
            (ok(retired), extra.filterNot(_ == k))
          case 2 =>
            val all = (w.dreps ++ extra).filter(k => st.vState.dreps.contains(drepCredential(k)))
            val target = all(pick(p, all.size))
            val delegated = submit(
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
            (ok(delegated), extra)
          case 3 =>
            val abstained = submit(
              w,
              st,
              Vector(staker),
              certs = Vector(
                Certificate.Delegate(staker.credential, None, Some(DRep.AlwaysAbstain), None)
              )
            )
            (ok(abstained), extra)
          case _ if extra.nonEmpty =>
            val k = extra(pick(p, extra.size))
            val updated =
              submit(w, st, Vector(k), certs = Vector(Certificate.UpdateDRep(k.credential, None)))
            (ok(updated), extra)
          case _ => (st, extra)
        assertEquals(next.total, Supply)
        assertEquals(next.deposited, NativeRules.obligation(next.nes))
        assertDRepIndex(next)
        (next, more)
      }
      true
    }
  }

  // -------------------------------------------------------------------------------------------
  // Protocol-version 11 rules (roadmap B5). The same transactions are refused by the reference node
  // in the devnet scenarios (scripts/devnet/govextra.py); the expected failures are the ones its
  // predicate failures carry.

  test(
    "committee hot keys: authorised but unelected keys may vote before protocol version 11 only"
  ) {
    // the refusal of the hot key's vote before its member is elected and after, and the key
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
      def vote(st: Step, id: GovActionId) =
        submit(
          w,
          st,
          Vector(hot),
          votes = Vector((Voter.CommitteeHot(hot.credential), Vector(id -> Vote.Yes)))
        )
      val early = refusal(vote(s2, infoId))
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
      assertEquals(elected.gov.committee.map(_.members.keySet), Some(Set(member.credential)))
      // the epoch boundary dropped the authorisation of a member who was not yet elected: authorise again
      assert(elected.vState.committeeState.creds.isEmpty)
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
      (early, refusal(vote(s4, idOf(s4, 0))), hot)
    val (early10, late10, _) = scenario(10)
    assertEquals((early10, late10), (None, None))
    val (early11, late11, hot11) = scenario(11)
    assertEquals(
      (early11, late11),
      (
        Some(
          PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector(hot11.credential)))
        ),
        None
      )
    )
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
    assertEquals(
      refusal(refused),
      Some(PredicateFailure.gov(GovFailure.UnelectedCommitteeVoters(Vector(hot.credential))))
    )
  }

  test("protocol version 11: a pool registration may not take a VRF key hash another pool holds") {
    def register(w: World, st: Step, op: Key, vrf: Bytes, fresh: Boolean) =
      val params = w.poolParams(op).copy(vrfKeyHash = vrf)
      submit(
        w,
        st,
        Vector(op, w.poolOwner),
        certs = Vector(Certificate.RegisterPool(params)),
        deposits = if fresh then w.params.poolDeposit else 0
      )
    def taken(op: Key, vrf: Bytes) =
      Some(PredicateFailure.pool(PoolFailure.VRFKeyHashAlreadyRegistered(op.hash, vrf)))
    def vrfOf(i: Int, w: World) = w.poolParams(w.pools(i)).vrfKeyHash
    def fresh(n: Int) = Blake2b.hash256.hash(Bytes(Vector(0x56, n.toByte)))
    def keys(st: Step) = st.pState.vrfKeyHashes.keysIterator.map(_.bytes).toSet
    val f1 = fresh(1)
    val f2 = fresh(2)
    val w = World(11)
    val a = key()
    val b = key()
    val s0 = w.initial
    // an active pool's key hash is taken, a free one is not
    assertEquals(refusal(register(w, s0, a, vrfOf(0, w), fresh = true)), taken(a, vrfOf(0, w)))
    val s1 = ok(register(w, s0, a, f1, fresh = true))
    assertEquals(refusal(register(w, s1, b, f1, fresh = true)), taken(b, f1))
    // a pool keeps its own key hash when it re-registers, and may not take another pool's
    val s2 = ok(register(w, s1, a, f1, fresh = false))
    assertEquals(refusal(register(w, s2, a, vrfOf(1, w), fresh = false)), taken(a, vrfOf(1, w)))
    // a key hash scheduled by a re-registration is taken too, until the pool goes back to its own
    val s3 = ok(register(w, s2, a, f2, fresh = false))
    // the reference's map: the second re-registration of the epoch forgets the key of the first,
    // which is the key the pool has in use (`Pool.hs:274-286`)
    assertEquals(keys(s3), Set(vrfOf(0, w), vrfOf(1, w), f2))
    assertEquals(refusal(register(w, s3, b, f2, fresh = true)), taken(b, f2))
    val s4 = ok(register(w, s3, a, f1, fresh = false))
    val s5 = ok(register(w, s4, b, f2, fresh = true))
    assertEquals(keys(s5), Set(vrfOf(0, w), vrfOf(1, w), f1, f2))
    assertEquals(refusal(register(w, s5, b, f1, fresh = false)), taken(b, f1))
    // the scheduled change takes effect at the boundary; the other pool keeps its key hash
    assertEquals(
      boundary(s5).pState.stakePools.get(poolId(a)).map(_.vrf),
      Some(must(VRFVerKeyHash.fromBytes(f1)))
    )
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
    assertEquals(s.pState.stakePools.size, 3)
  }
