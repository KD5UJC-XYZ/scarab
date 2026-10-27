// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.data.{Kleisli, StateT}
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import munit.FunSuite
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Certificate,
  Committee,
  Datum,
  GovAction,
  GovActionId,
  GovActionState,
  LedgerFailure,
  PoolParams,
  Proposal,
  Proposals,
  Redeemer,
  RewardAccount,
  Script,
  ScriptIntegrity,
  ScriptKind,
  TestLedger,
  TxEnv,
  Tx,
  Value,
  Vote,
  Voter,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*

/** The Conway LEDGER rules on the era-indexed state, on one transaction at a time: the suite builds
  * the native state, runs the rules on the transaction and checks what they do with it: the
  * failure, if it is refused, and the state after it, if it is not. Every accepted state is also
  * checked for what the rules keep beside what they change ([[consistent]]): the delegators of each
  * pool and each DRep against the accounts, the instant stake against the UTxO, the deposits
  * against the obligation.
  */
class ConwayLedgerSuite extends FunSuite:
  private val alice = key()
  private val bob = key()
  private val carol = key()
  private val ada = BigInt(1000000)

  // ---- the native vocabulary of the test keys and amounts

  private def ok[A](e: Either[VocabError, A]): A = e.fold(v => fail(s"$v"), identity)
  private def compact(n: BigInt): CompactCoin = ok(CompactCoin.fromBigInt(n))
  private def epochNo(n: BigInt): EpochNo = ok(EpochNo.fromBigInt(n))
  private def cred[R <: KeyRole](k: Key): Credential[R] = ok(Convert.credential[R](k.credential))
  private def poolId(k: Key): KeyHash[StakePool] = ok(Convert.keyHash[StakePool](k.hash))

  /** The credential of a registered DRep, `None` for the two predefined ones. */
  private def drepCredential(d: DRep): Option[Credential[DRepRole]] = d match
    case DRep.DRepKeyHash(h)                                  => Some(Credential.KeyHashObj(h))
    case DRep.DRepScriptHash(h)                               => Some(Credential.ScriptHashObj(h))
    case DRep.DRepAlwaysAbstain | DRep.DRepAlwaysNoConfidence => None

  /** An account with no pool, holding `deposit` and `balance`, delegating its vote to `drep`. */
  private def account(
      deposit: BigInt,
      balance: BigInt = 0,
      drep: Option[DRep] = None
  ): ConwayAccountState =
    ConwayAccountState(compact(balance), compact(deposit), None, drep)

  /** A DRep registered with the suite's deposit, active until `expiry`. */
  private def drepState(expiry: BigInt): DRepState =
    DRepState(epochNo(expiry), None, compact(500 * ada), Sorted.emptySet)

  // ---- what the rules do with a transaction

  /** The rules on one transaction: LEDGER, or, with `block`, LEDGERS on a block of that one. An
    * accepted state is [[consistent]].
    */
  private def run(
      s: NewEpochState[Conway],
      t: Tx,
      ctx: TxEnv = TestLedger.env,
      block: Boolean = false
  ): Either[LedgerFailure, Counted[NewEpochState[Conway]]] =
    val result =
      if block then
        Sts(Ledgers)(
          NativeRules.globalsOf(ctx),
          LedgersEnv(ctx, s.es.chainAccount.treasury),
          Build.counted(s),
          Vector(t)
        ).map(c => Counted(ConwayNewEpochState.ls.replace(c.state)(s), c.plutus))
      else NativeRules.txIn[Conway](s, t, ctx)
    result.foreach(after => consistent(after.state))
    result

  private def accepted(s: NewEpochState[Conway], t: Tx): NewEpochState[Conway] = run(s, t) match
    case Right(next) => next.state
    case Left(f)     => fail(s"expected the transaction to be accepted: $f")

  private def refusedWith(s: NewEpochState[Conway], t: Tx): String = run(s, t) match
    case Left(LedgerFailure.Rejected.Single(_, f)) => f.ruleName
    case other                                     => fail(s"expected a refusal: ${outcome(other)}")

  /** What the rules did, for the message of a failed test: the refusal, or that they accepted. */
  private def outcome(r: Either[LedgerFailure, Counted[NewEpochState[Conway]]]): String =
    r.fold(f => s"refused: $f", _ => "accepted")

  /** What the rules keep beside what they change, against what it must agree with: the delegators
    * of the pools ([[Invariants.poolDelegators]]) and of the DReps ([[drepDelegators]]) against the
    * accounts, the instant stake against the stake of the UTxO (`utxosInstantStake`), the deposits
    * against the obligation of the state.
    */
  private def consistent(nes: NewEpochState[Conway]): Unit =
    val ls = nes.es.ls
    val cert = ConwayLedgerState.cert.get(ls)
    assertEquals(Invariants.poolDelegators(cert), Vector.empty)
    assertEquals(drepDelegators(cert), Vector.empty)
    val utxo = ls.utxo.utxo.utxo.valuesIterator.toVector
    assertEquals(
      ConwayLedgerState.instantStake.get(ls),
      ok(InstantStakeOps.update(ConwayInstantStake(Sorted.empty), utxo, Nil))
    )
    assertEquals(ls.utxo.deposited.value, NativeRules.obligation(nes))

  /** The DReps' delegators against the accounts' vote delegations, each pair that disagrees: an
    * account delegating to a registered DRep that does not list it, a DRep listing an account that
    * does not delegate to it.
    */
  private def drepDelegators(
      cert: ConwayCertState
  ): Vector[(Credential[DRepRole], Credential[Staking])] =
    val accounts = CertLens.accounts.get(cert)
    val dreps = CertLens.dreps.get(cert)
    val missing = for
      (c, a) <- accounts.iterator
      d <- a.drep.flatMap(drepCredential).iterator
      s <- dreps.get(d).iterator
      if !s.delegators.contains(c)
    yield d -> c
    val stale = for
      (d, s) <- dreps.iterator
      c <- s.delegators.iterator
      if !accounts.get(c).flatMap(_.drep).flatMap(drepCredential).contains(d)
    yield d -> c
    (missing ++ stale).toVector

  // ---- reading the state

  private def accountOf(nes: NewEpochState[Conway], k: Key): Option[ConwayAccountState] =
    ConwayNewEpochState.accounts.get(nes).states.get(cred[Staking](k))

  private def pStateOf(nes: NewEpochState[Conway]): PState = ConwayLedgerState.pState.get(nes.es.ls)

  private def proposalsOf(nes: NewEpochState[Conway]): Proposals =
    ConwayLedgerState.proposals.get(nes.es.ls)

  private def utxoOf(nes: NewEpochState[Conway]): node.Utxo = nes.es.ls.utxo.utxo.utxo

  private def feesOf(nes: NewEpochState[Conway]): BigInt = nes.es.ls.utxo.fees.value

  /** The VRF key hashes of the pools' map (`psVRFKeyHashes`). */
  private def vrfKeys(ps: PState): Set[Bytes] = ps.vrfKeyHashes.keySet.unsorted.map(_.bytes)

  /** The VRF key hashes the pools use, registered or waiting in a re-registration. */
  private def vrfKeysOfPools(ps: PState): Set[Bytes] =
    ps.stakePools.valuesIterator.map(_.vrf.bytes).toSet ++
      ps.future.valuesIterator.map(_.vrfKeyHash)

  /** `next` with the UTxO of `start` again (and the instant stake it makes): the funds a first
    * transaction spent, for a second one against the state the first left.
    */
  private def withFundsOf(start: NewEpochState[Conway])(
      next: NewEpochState[Conway]
  ): NewEpochState[Conway] =
    val funds = start.es.ls.utxo
    ConwayNewEpochState.ls
      .andThen(LedgerState.utxo[Conway])
      .modify(_.copy[Conway](utxo = funds.utxo, instantStake = funds.instantStake))(next)

  // ---- a Conway state to start from

  private val voter = key()

  private def poolParams(operator: Key, cost: BigInt = 0): PoolParams =
    PoolParams(
      operator.hash,
      Blake2bHash(operator.hash),
      pledge = 0,
      cost = cost,
      margin = Rational(1, 100),
      rewardAccount = RewardAccount(0, operator.credential),
      owners = Set(operator.hash),
      relays = Bytes.empty,
      metadata = None
    )

  private def Blake2bHash(b: Bytes): Bytes =
    xyz.kd5ujc.scarab.crypto.Blake2b.hash256.hash(b)

  private def pparams: node.ProtocolParams =
    params(10).copy(
      drepDeposit = 500 * ada,
      drepActivity = 20,
      govActionDeposit = 1000 * ada,
      govActionLifetime = 6,
      minPoolCost = 0,
      costModels = Map(0 -> Vector(1, 2, 3), 1 -> Vector(4, 5, 6, 7), 2 -> Vector(8, 9)),
      keyDeposit = 2 * ada,
      poolDeposit = 500 * ada,
      eMax = 18
    )

  /** The state in epoch 5 with `pp` in force, `funds` as its UTxO, the accounts, the pools
    * (registered with a deposit of 500 ada, [[Build.pool]]), the DReps, the proposals and the
    * committee given, the delegators of each pool and DRep those of the accounts, the deposits the
    * obligation and the reserves what makes the total [[TestLedger.Supply]].
    */
  private def base(
      funds: Vector[(node.TxIn, node.TxOut)],
      accounts: Vector[(Credential[Staking], ConwayAccountState)] = Vector.empty,
      pools: Vector[PoolParams] = Vector.empty,
      dreps: Vector[(Credential[DRepRole], DRepState)] = Vector.empty,
      proposals: Proposals = Proposals.empty,
      committee: Option[Committee] = None,
      committeeState: Vector[(Credential[ColdCommittee], CommitteeAuthorization)] = Vector.empty,
      pp: node.ProtocolParams = pparams
  ): NewEpochState[Conway] =
    val funded = Build.funded(Build.nes[Conway](5, pp), funds.toMap)
    val registered = pools.foldLeft(funded)((nes, p) => Build.pool(nes, p, 500 * ada))
    def delegatorsBy[K](
        to: ConwayAccountState => Option[K]
    ): Map[K, SortedSet[Credential[Staking]]] =
      accounts
        .flatMap((c, a) => to(a).map(_ -> c))
        .groupMap(_._1)(_._2)
        .view
        .mapValues(Sorted.set(_))
        .toMap
    val byPool = delegatorsBy(_.pool)
    val byDRep = delegatorsBy(_.drep.flatMap(drepCredential))
    val ls = registered.es.ls
    val cert = ConwayLedgerState.cert.get(ls)
    val withCert = cert.copy(
      vState = cert.vState.copy(
        dreps = Sorted
          .map(dreps)
          .transform((c, d) => d.copy(delegators = byDRep.getOrElse(c, d.delegators))),
        committeeState = CommitteeState(Sorted.map(committeeState))
      ),
      pState = cert.pState.copy(stakePools =
        cert.pState.stakePools.transform((id, p) =>
          p.copy(delegators = byPool.getOrElse(id, p.delegators))
        )
      ),
      dState = cert.dState.copy(accounts = ConwayAccounts(Sorted.map(accounts)))
    )
    val withGov = ConwayLedgerState.gov.modify(
      _.copy(proposals = proposals, committee = committee)
    )(ConwayLedgerState.cert.replace(withCert)(ls))
    val built = ConwayNewEpochState.ls.replace(withGov)(registered)
    val deposited = NativeRules.obligation(built)
    val held = Coin(deposited)
    val reserves = Coin(TestLedger.Supply - utxoOf(built).coin - deposited)
    ConwayNewEpochState.reserves.replace(reserves)(
      ConwayNewEpochState.ls
        .andThen(LedgerState.utxo[Conway])
        .andThen(UTxOState.deposited[Conway])
        .replace(held)(built)
    )

  private val coinIn = txIn(1)

  private def fundedBy(k: Key, coin: BigInt = 10000 * ada, i: Int = 1) =
    Vector(txIn(i) -> out(k.baseAddress(k), coin))

  /** A transaction of `k` spending its funds `in` with `fee`, the change back to it. */
  private def pay(
      id: String,
      k: Key,
      funds: BigInt,
      fee: BigInt,
      certificates: Vector[Certificate] = Vector.empty,
      extra: BigInt = 0,
      signers: Vector[Key] = Vector.empty,
      votes: Vector[(Voter, Vector[(GovActionId, Vote)])] = Vector.empty,
      proposals: Vector[Proposal] = Vector.empty,
      withdrawals: Map[RewardAccount, BigInt] = Map.empty
  ): Tx =
    val b = body(
      id,
      Vector(coinIn),
      Vector(out(k.baseAddress(k), funds - fee - extra + withdrawals.map(_._2).sum)),
      fee,
      certificates = certificates,
      votes = votes,
      proposals = proposals,
      withdrawals = withdrawals
    )
    tx(b, (k +: signers).distinct)

  private val hot = key()
  private val cold = key()

  test("a payment: the outputs move, the instant stake follows, the fee is collected") {
    val s = base(fundedBy(alice))
    val next = accepted(s, pay("pay", alice, 10000 * ada, 200))
    assertEquals(feesOf(next), BigInt(200))
    // the stake of the output is that of the credential in its address
    assertEquals(
      ConwayLedgerState.instantStake
        .get(next.es.ls)
        .credentialStake
        .get(cred[Staking](alice))
        .map(_.toBigInt),
      Some(10000 * ada - 200)
    )
  }

  test("a stake registration with a delegation, then a re-delegation, an unregistration") {
    val pool1 = key()
    val pool2 = key()
    val s = base(
      fundedBy(alice),
      pools = Vector(poolParams(pool1), poolParams(pool2))
    )
    val register = pay(
      "reg",
      alice,
      10000 * ada,
      200,
      certificates = Vector(
        Certificate.Delegate(bob.credential, Some(pool1.hash), None, Some(2 * ada))
      ),
      extra = 2 * ada,
      signers = Vector(bob)
    )
    val s1 = accepted(s, register)
    assertEquals(accountOf(s1, bob).flatMap(_.pool), Some(poolId(pool1)))
    // moved to the other pool
    val redelegate = tx(
      body(
        "redelegate",
        Vector(txIn(1)),
        Vector(out(alice.baseAddress(alice), 10000 * ada - 400)),
        400,
        certificates = Vector(Certificate.Delegate(bob.credential, Some(pool2.hash), None, None))
      ),
      Vector(alice, bob)
    )
    // the funds of the first transaction are spent: make the second against the original state
    val s2 = accepted(withFundsOf(s)(s1), redelegate)
    assertEquals(accountOf(s2, bob).flatMap(_.pool), Some(poolId(pool2)))
    val unregister = tx(
      body(
        "unreg",
        Vector(txIn(1)),
        Vector(out(alice.baseAddress(alice), 10000 * ada - 400 + 2 * ada)),
        400,
        certificates = Vector(Certificate.Unregister(bob.credential, Some(2 * ada)))
      ),
      Vector(alice, bob)
    )
    val s3 = accepted(withFundsOf(s)(s2), unregister)
    assertEquals(accountOf(s3, bob), None)
  }

  test("a delegation to a pool that is not registered is refused as the node refuses it") {
    val s = base(fundedBy(alice))
    val t = pay(
      "nopool",
      alice,
      10000 * ada,
      200,
      certificates = Vector(
        Certificate.Delegate(bob.credential, Some(key().hash), None, Some(2 * ada))
      ),
      extra = 2 * ada,
      signers = Vector(bob)
    )
    assertEquals(refusedWith(s, t), "DelegateeStakePoolNotRegisteredDELEG")
  }

  test("a pool registration, a re-registration, a retirement") {
    val op = key()
    val s = base(fundedBy(alice), pools = Vector(poolParams(op)))
    val rereg = pay(
      "rereg",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(poolParams(op, cost = 340))),
      signers = Vector(op)
    )
    val next = accepted(s, rereg)
    assertEquals(pStateOf(next).future.get(poolId(op)).map(_.cost), Some(BigInt(340)))
    val retire = pay(
      "retire",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RetirePool(op.hash, 8)),
      signers = Vector(op)
    )
    assertEquals(pStateOf(accepted(s, retire)).retiring.get(poolId(op)), Some(epochNo(8)))
    val fresh = key()
    val reg = pay(
      "newpool",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(poolParams(fresh))),
      extra = 500 * ada,
      signers = Vector(fresh)
    )
    assert(pStateOf(accepted(s, reg)).stakePools.contains(poolId(fresh)))
  }

  test("DRep registration, delegation to it, a vote that extends it, unregistration") {
    val drepCred = bob.credential
    val s = base(
      fundedBy(alice),
      accounts = Vector(cred[Staking](carol) -> account(2 * ada))
    )
    val reg = pay(
      "regdrep",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterDRep(drepCred, 500 * ada, None)),
      extra = 500 * ada,
      signers = Vector(bob)
    )
    val s1 = accepted(s, reg)
    assert(ConwayNewEpochState.dreps.get(s1).contains(cred[DRepRole](bob)))
    val delegate = pay(
      "votedelegate",
      alice,
      10000 * ada,
      200,
      certificates = Vector(
        Certificate.Delegate(carol.credential, None, Some(node.DRep.Registered(drepCred)), None)
      ),
      signers = Vector(carol)
    )
    val s2 = accepted(withFundsOf(s)(s1), delegate)
    assertEquals(
      ConwayNewEpochState.dreps.get(s2).get(cred[DRepRole](bob)).map(_.delegators),
      Some(Sorted.set(Vector(cred[Staking](carol))))
    )
    val unreg = pay(
      "unregdrep",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.UnregisterDRep(drepCred, 500 * ada)),
      extra = -500 * ada,
      signers = Vector(bob)
    )
    val s3 = accepted(withFundsOf(s)(s2), unreg)
    // the delegator loses the delegation
    assertEquals(accountOf(s3, carol).map(_.drep), Some(None))
  }

  test("a committee authorisation, a resignation, an authorisation after it is refused") {
    val committee = Committee(Map(cold.credential -> BigInt(100)), Rational(1, 2))
    val s = base(fundedBy(alice), committee = Some(committee))
    val auth = pay(
      "auth",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.CommitteeAuthorize(cold.credential, hot.credential)),
      signers = Vector(cold)
    )
    val s1 = accepted(s, auth)
    assertEquals(
      CertLens.committee.get(ConwayLedgerState.cert.get(s1.es.ls)).get(cred[ColdCommittee](cold)),
      Some(CommitteeAuthorization.CommitteeHotCredential(cred[HotCommittee](hot)))
    )
    val resign = pay(
      "resign",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.CommitteeResign(cold.credential, None)),
      signers = Vector(cold)
    )
    val s2 = accepted(withFundsOf(s)(s1), resign)
    assertEquals(refusedWith(withFundsOf(s)(s2), auth), "ConwayCommitteeHasPreviouslyResigned")
  }

  /** A state with an info action to vote on, and the committee member `cold` authorising `hot`. */
  private def votingState(elected: Boolean, pp: node.ProtocolParams = pparams) =
    val action = GovActionState(
      GovActionId(Bytes.fromArray(Array.fill(32)(9.toByte)), 0),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(1000 * ada, RewardAccount(0, alice.credential), GovAction.Info),
      5,
      20
    )
    val committee =
      if elected then Some(Committee(Map(cold.credential -> BigInt(100)), Rational(1, 2)))
      else Some(Committee(Map.empty, Rational(1, 2)))
    (
      base(
        fundedBy(alice),
        proposals = Proposals(Vector(action), node.PrevIds()),
        committee = committee,
        committeeState = Vector(
          cred[ColdCommittee](cold) ->
            CommitteeAuthorization.CommitteeHotCredential(cred[HotCommittee](hot))
        ),
        accounts = Vector(cred[Staking](alice) -> account(2 * ada)),
        dreps = Vector(cred[DRepRole](voter) -> drepState(5)),
        pp = pp
      ),
      action.id
    )

  test("votes of a committee hot key, a DRep: recorded on the action, the DRep's expiry moves") {
    val (s, id) = votingState(elected = true)
    val t = pay(
      "vote",
      alice,
      10000 * ada,
      200,
      votes = Vector(
        Voter.CommitteeHot(hot.credential) -> Vector(id -> Vote.Yes),
        Voter.DRepVoter(voter.credential) -> Vector(id -> Vote.No)
      ),
      signers = Vector(hot, voter)
    )
    val next = accepted(s, t)
    val gas = proposalsOf(next).byId(id)
    assertEquals(gas.committeeVotes, Map(hot.credential -> Vote.Yes))
    assertEquals(gas.drepVotes, Map(voter.credential -> Vote.No))
  }

  test(
    "an authorised hot key that is not elected votes before protocol version 11 (preprod 187 to 193) and not from it"
  ) {
    val (s, id) = votingState(elected = false)
    val t = pay(
      "unelected",
      alice,
      10000 * ada,
      200,
      votes = Vector(Voter.CommitteeHot(hot.credential) -> Vector(id -> Vote.Yes)),
      signers = Vector(hot)
    )
    val next = accepted(s, t)
    assertEquals(proposalsOf(next).byId(id).committeeVotes, Map(hot.credential -> Vote.Yes))
    val (s11, _) = votingState(elected = false, pp = pparams.copy(protocolMajor = 11))
    assertEquals(refusedWith(s11, t), "UnelectedCommitteeVoters")
  }

  test("a vote on an action that does not exist, a vote by an unknown voter") {
    val (s, id) = votingState(elected = true)
    val missing = GovActionId(Bytes.fromArray(Array.fill(32)(1.toByte)), 3)
    val unknownAction = pay(
      "noaction",
      alice,
      10000 * ada,
      200,
      votes = Vector(Voter.DRepVoter(voter.credential) -> Vector(missing -> Vote.Yes)),
      signers = Vector(voter)
    )
    assertEquals(refusedWith(s, unknownAction), "GovActionsDoNotExist")
    val unknownVoter = pay(
      "novoter",
      alice,
      10000 * ada,
      200,
      votes = Vector(Voter.DRepVoter(carol.credential) -> Vector(id -> Vote.Yes)),
      signers = Vector(carol)
    )
    assertEquals(refusedWith(s, unknownVoter), "VotersDoNotExist")
  }

  test("a proposal: its deposit is taken, it is in the store") {
    val s = base(
      fundedBy(alice),
      accounts = Vector(cred[Staking](alice) -> account(2 * ada))
    )
    val t = pay(
      "propose",
      alice,
      10000 * ada,
      200,
      extra = 1000 * ada,
      proposals = Vector(Proposal(1000 * ada, RewardAccount(0, alice.credential), GovAction.Info))
    )
    val next = accepted(s, t)
    assertEquals(proposalsOf(next).actions.size, 1)
    assertEquals(
      next.es.ls.utxo.deposited.value,
      s.es.ls.utxo.deposited.value + 1000 * ada
    )
  }

  test("a withdrawal drains the account; one that does not is refused") {
    val s = base(
      fundedBy(alice),
      accounts = Vector(
        cred[Staking](alice) -> account(2 * ada, 7 * ada, Some(DRep.DRepAlwaysAbstain))
      )
    )
    val good =
      pay(
        "wd",
        alice,
        10000 * ada,
        200,
        withdrawals = Map(RewardAccount(0, alice.credential) -> 7 * ada)
      )
    assertEquals(accountOf(accepted(s, good), alice).map(_.balance.toBigInt), Some(BigInt(0)))
    val partial =
      pay(
        "wd2",
        alice,
        10000 * ada,
        200,
        withdrawals = Map(RewardAccount(0, alice.credential) -> 3 * ada)
      )
    assertEquals(refusedWith(s, partial), "WithdrawalsNotInRewardsCERTS")
  }

  test("a transaction flagged invalid that has no script to fail is refused") {
    val s = base(fundedBy(alice))
    val b = body(
      "collateral",
      Vector(coinIn),
      Vector.empty,
      200,
      collateral = Vector(coinIn),
      collateralReturn = Some(out(alice.baseAddress(alice), 10000 * ada - 300))
    )
    // no redeemers: nothing for it to fail, so the node refuses a transaction flagged invalid
    val t = tx(b, Vector(alice), isValid = false)
    assert(run(s, t).isLeft, "expected a refusal, the transaction was accepted")
  }

  test("the failures of the node are the failures of the new rules") {
    val s = base(fundedBy(alice))
    // not enough fee, wrong network in the body, expired validity interval, an input that is not there
    val cheap = pay("cheap", alice, 10000 * ada, 1)
    assertEquals(refusedWith(s, cheap), "FeeTooSmallUTxO")
    val ghost = tx(
      body("ghost", Vector(txIn(77)), Vector(out(alice.baseAddress(alice), ada)), 200),
      Vector(alice)
    )
    assertEquals(refusedWith(s, ghost), "BadInputsUTxO")
    val unsigned = tx(
      body(
        "unsigned",
        Vector(coinIn),
        Vector(out(alice.baseAddress(alice), 10000 * ada - 200)),
        200
      ),
      Vector.empty
    )
    assertEquals(refusedWith(s, unsigned), "MissingVKeyWitnessesUTXOW")
    val unbalanced = tx(
      body("unbalanced", Vector(coinIn), Vector(out(alice.baseAddress(alice), 10000 * ada)), 200),
      Vector(alice)
    )
    assertEquals(refusedWith(s, unbalanced), "ValueNotConservedUTxO")
  }

  // ---- width: ADR 0011

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.getOrElse(fail("the thread did not finish")).fold(throw _, identity)

  test("a transaction of thousands of certificates and votes runs on a 256 KiB stack") {
    val pool = key()
    val width = 1500
    val keys = Vector.fill(width)(key())
    val action = GovActionState(
      GovActionId(Bytes.fromArray(Array.fill(32)(9.toByte)), 0),
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(1000 * ada, RewardAccount(0, alice.credential), GovAction.Info),
      5,
      20
    )
    val s = base(
      fundedBy(alice),
      pools = Vector(poolParams(pool)),
      dreps = keys.map(k => cred[DRepRole](k) -> drepState(30)),
      proposals = Proposals(Vector(action), node.PrevIds())
    )
    val registrations =
      keys.map(k => Certificate.Delegate(k.credential, Some(pool.hash), None, Some(2 * ada)))
    val t = pay(
      "wide",
      alice,
      10000 * ada,
      200,
      certificates = registrations,
      extra = 2 * ada * width,
      votes = keys.map(k => Voter.DRepVoter(k.credential) -> Vector(action.id -> Vote.Yes)),
      signers = keys
    )
    onSmallStack(run(s, t)) match
      case Right(next) =>
        assertEquals(ConwayNewEpochState.accounts.get(next.state).states.size, width)
        assertEquals(proposalsOf(next.state).byId(action.id).drepVotes.size, width)
      case Left(f) => fail(s"expected the wide transaction to be accepted: $f")
  }

  test("votes are recorded once per action: the later vote of a voter on an action counts") {
    val id = GovActionId(Bytes.fromArray(Array.fill(32)(9.toByte)), 0)
    val other = GovActionId(Bytes.fromArray(Array.fill(32)(8.toByte)), 1)
    def action(i: GovActionId) = GovActionState(
      i,
      Map.empty,
      Map.empty,
      Map.empty,
      Proposal(0, RewardAccount(0, alice.credential), GovAction.Info),
      5,
      20
    )
    val ps = Proposals(Vector(action(id), action(other)), node.PrevIds())
    val cast = ps.withVotes(
      Vector(
        (Voter.DRepVoter(bob.credential), Vote.Yes, id),
        (Voter.DRepVoter(bob.credential), Vote.No, id),
        (Voter.Pool(alice.hash), Vote.Abstain, other)
      )
    )
    assertEquals(cast.byId(id).drepVotes, Map(bob.credential -> Vote.No))
    assertEquals(cast.byId(other).poolVotes, Map(alice.hash -> Vote.Abstain))
    // the same as casting them one at a time
    val oneByOne = Vector(
      (Voter.DRepVoter(bob.credential), Vote.Yes, id),
      (Voter.DRepVoter(bob.credential), Vote.No, id),
      (Voter.Pool(alice.hash), Vote.Abstain, other)
    ).foldLeft(ps)((p, v) => p.vote(v._1, v._2, v._3))
    assertEquals(cast, oneByOne)
  }

  test("the delegators of the pools are the accounts that delegate to them, after every step") {
    val pool1 = key()
    val s = base(fundedBy(alice), pools = Vector(poolParams(pool1)))
    val t = pay(
      "delegate",
      alice,
      10000 * ada,
      200,
      certificates =
        Vector(Certificate.Delegate(bob.credential, Some(pool1.hash), None, Some(2 * ada))),
      extra = 2 * ada,
      signers = Vector(bob)
    )
    assertEquals(Invariants.poolDelegators(ConwayLedgerState.cert.get(s.es.ls)), Vector.empty)
    JudgedSts(Ledger)(LedgerFailure.Rejected(t.body.id, _))(
      globals,
      LedgerEnv(TestLedger.env, Coin.zero),
      Build.counted(s),
      t
    ) match
      case Left(f) => fail(f.message)
      case Right(after) =>
        assertEquals(Invariants.poolDelegators(after.state.cert), Vector.empty)
        assertEquals(
          ConwayLedgerState.pools.get(after.state).values.map(_.delegators.size).sum,
          1
        )
  }

  test("the rules are not tied to their interpreter: RuleT and the reference stack agree") {
    // Haskell's `ReaderT Globals` over `StateT` over `Either` runs the same algebra (design 2.2)
    type R[A] = Either[LedgerFailure, A]
    type S[A] = StateT[R, ConwayLedger, A]
    type K[A] = Kleisli[S, Globals, A]
    given Monad[K] = Kleisli.catsDataMonadForKleisli[S, Globals]
    val pool1 = key()
    val s = base(fundedBy(alice), pools = Vector(poolParams(pool1)))
    val start = Build.counted(s)
    val ledgerEnv = LedgerEnv(TestLedger.env, s.es.chainAccount.treasury)
    val delegate = pay(
      "delegate",
      alice,
      10000 * ada,
      200,
      certificates =
        Vector(Certificate.Delegate(bob.credential, Some(pool1.hash), None, Some(2 * ada))),
      extra = 2 * ada,
      signers = Vector(bob)
    )
    val cheap = pay("cheap", alice, 10000 * ada, 1)
    for t <- Vector(delegate, cheap) do
      val onRuleT =
        JudgedSts(Ledger)(LedgerFailure.Rejected(t.body.id, _))(globals, ledgerEnv, start, t)
      val onStack = Ledger
        .transition[K](ledgerEnv, t)(using
          summon,
          summon,
          summon,
          summon,
          Judge.raising[K, xyz.kd5ujc.scarab.ledger.PredicateFailure, LedgerFailure](
            LedgerFailure.Rejected(t.body.id, _)
          )
        )
        .run(globals)
        .run(start)
        .map(_._1)
      assertEquals(onRuleT, onStack)
  }

  test(
    "from protocol version 11 the VRF key hashes are unique, kept by registrations and re-registrations"
  ) {
    val one = key()
    val two = key()
    val pp11 = pparams.copy(protocolMajor = 11)
    val s = base(
      fundedBy(alice),
      pools = Vector(poolParams(one), poolParams(two)),
      pp = pp11
    )
    def withVrf(op: Key, vrf: Bytes) = poolParams(op).copy(vrfKeyHash = vrf)
    def reg(id: String, op: Key, vrf: Bytes) = pay(
      id,
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(withVrf(op, vrf))),
      signers = Vector(op)
    )
    // a key that another pool holds is refused, the pool's own is not
    assertEquals(
      refusedWith(s, reg("dup", one, Blake2bHash(two.hash))),
      "VRFKeyHashAlreadyRegistered"
    )
    val fresh = Blake2bHash(Bytes(Vector(1, 2, 3)))
    val own = Set(poolParams(one).vrfKeyHash, poolParams(two).vrfKeyHash)
    assertEquals(vrfKeys(pStateOf(s)), own)
    val s1 = accepted(s, reg("fresh", one, fresh))
    assertEquals(pStateOf(s1).future.get(poolId(one)).map(_.vrfKeyHash), Some(fresh))
    // the map the rules leave: the keys in use and the scheduled one
    assertEquals(vrfKeys(pStateOf(s1)), own + fresh)
    // the scheduled key is held: another pool cannot take it
    assertEquals(
      refusedWith(withFundsOf(s)(s1), reg("taken", two, fresh)),
      "VRFKeyHashAlreadyRegistered"
    )
    // a second re-registration in the epoch frees the first key
    val other = Blake2bHash(Bytes(Vector(4, 5, 6)))
    val s2 = accepted(withFundsOf(s)(s1), reg("again", one, other))
    assertEquals(pStateOf(s2).future.get(poolId(one)).map(_.vrfKeyHash), Some(other))
    assertEquals(vrfKeys(pStateOf(s2)), own + other)
    accepted(withFundsOf(s)(s2), reg("freed", two, fresh))
  }

  test(
    "re-registering with the pool's own key hash and then another in one epoch drops the key in use (the reference's quirk)"
  ) {
    // `Pool.hs:274-286`: a re-registration whose future parameters hold another key hash deletes
    // that one from the map, whether or not it is also the key of the registered pool, so the
    // map is no longer every key the pools use.
    val one = key()
    val pp11 = pparams.copy(protocolMajor = 11)
    val own = poolParams(one).vrfKeyHash
    val other = Blake2bHash(Bytes(Vector(7, 8, 9)))
    val start = Build.funded(
      Build.pool(
        Build.nes[Conway](5, pp11),
        poolParams(one),
        500 * ada,
        future = Some(poolParams(one))
      ),
      fundedBy(alice).toMap
    )
    val t = pay(
      "quirk",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(poolParams(one).copy(vrfKeyHash = other))),
      signers = Vector(one)
    )
    val after = JudgedSts(Ledger)(LedgerFailure.Rejected(t.body.id, _))(
      globals,
      LedgerEnv(TestLedger.env, start.es.chainAccount.treasury),
      Build.counted(start),
      t
    ).fold(f => fail(f.message), identity)
    // the reference's map before: the key in use; after: the new key, and not the one the
    // registered pool still uses
    assertEquals(vrfKeys(ConwayLedgerState.pState.get(start.es.ls)), Set(own))
    val ps = ConwayLedgerState.pState.get(after.state)
    assertEquals(vrfKeys(ps), Set(other))
    // what the pools use: both
    assertEquals(vrfKeysOfPools(ps), Set(own, other))
  }

  test(
    "entering protocol version 11 populates the VRF key hashes, a key held twice counted twice"
  ) {
    val one = key()
    val two = key()
    val shared = poolParams(two).copy(vrfKeyHash = poolParams(one).vrfKeyHash)
    val s = base(fundedBy(alice), pools = Vector(poolParams(one), shared))
    val before = pStateOf(s)
    val after = HardFork.populateVRFKeyHashes(before.copy(vrfKeyHashes = Sorted.empty))
    val shared11 =
      VRFVerKeyHash.fromBytes(poolParams(one).vrfKeyHash).fold(e => fail(e.toString), identity)
    assertEquals(after.vrfKeyHashes.get(shared11).map(_.value.bits), Some(2L))
    // the same where the map already holds the key (once, as the state was built): it starts
    // again
    assertEquals(HardFork.populateVRFKeyHashes(before).vrfKeyHashes, after.vrfKeyHashes)
  }

  /** A Plutus V3 transaction that spends an output its script locks, with one collateral input and,
    * if given, a withdrawal of nothing from `withdrawal`. The verdict of the evaluator is the
    * test's; the state holds the account of `alice` (a key credential delegated to a DRep, as the
    * withdrawal of a key credential needs from protocol version 10) with an empty balance.
    */
  private final class ScriptCase(
      pp: node.ProtocolParams,
      withdrawal: Option[RewardAccount]
  ):
    val plutus = Script.plutus(ScriptKind.PlutusV3, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      node.Address
        .decode(Bytes(0x70.toByte +: plutus.hash.value))
        .fold(e => fail(e.toString), identity)
    val datum = Bytes(Vector(0x01))
    val locked = node
      .TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    // more outputs of alice, for a transaction with several collateral inputs
    val state: NewEpochState[Conway] = base(
      Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked) ++
        (41 to 43).map(i => txIn(i) -> out(alice.address(), 10 * ada)),
      accounts =
        Vector(cred[Staking](alice) -> account(2 * ada, drep = Some(DRep.DRepAlwaysAbstain))),
      pp = pp
    )
    val units = ExUnits(1000, 1000000)
    // `{ [0, 0] : [1, [1000, 1000000]] }` and `[ 1 ]`
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      redeemersRaw = Some(
        Bytes(
          Vector(0xa1, 0x82, 0x00, 0x00, 0x82, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
            0x40).map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (pp.priceMem * Rational(1000) +
      pp.priceSteps * Rational(1000000)).ceil

    def bodyWith(
        hash: Option[Bytes],
        collateral: Vector[node.TxIn] = Vector(collateralIn),
        collateralReturn: Option[node.TxOut] = None,
        outputs: Option[Vector[node.TxOut]] = None
    ): node.TxBody =
      body(
        "v3",
        Vector(lockedIn),
        outputs.getOrElse(Vector(out(bob.address(), 50 * ada - fee))),
        fee,
        withdrawals = withdrawal.map(_ -> BigInt(0)).toMap,
        collateral = collateral,
        collateralReturn = collateralReturn,
        scriptDataHash = hash
      )

    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None), Vector(alice), wits),
      pp,
      Set(ScriptKind.PlutusV3)
    )

    def ctx(succeeds: Boolean) =
      TestLedger.env.copy(
        evaluator = scripted(succeeds),
        decoder = xyz.kd5ujc.scarab.plutus.ScriptDecoder.lenient
      )

    def attempt(
        succeeds: Boolean,
        isValid: Boolean,
        block: Boolean = false
    ): Either[LedgerFailure, Counted[NewEpochState[Conway]]] =
      attemptWith(succeeds, isValid, bodyWith(hash), block)

    def attemptWith(
        succeeds: Boolean,
        isValid: Boolean,
        b: node.TxBody,
        block: Boolean = false
    ): Either[LedgerFailure, Counted[NewEpochState[Conway]]] =
      // the integrity hash covers the witnesses, not the body: the same one serves any body
      run(state, tx(b, Vector(alice), wits, isValid = isValid), ctx(succeeds), block)

  test(
    "Plutus V3: the verdict of the scripts must equal is_valid; a failed one pays its collateral"
  ) {
    val c = ScriptCase(pparams, withdrawal = None)
    c.attempt(succeeds = true, isValid = true) match
      case Right(next) =>
        assertEquals((next.plutus.succeeded, next.plutus.failed), (1L, 0L))
        assert(!utxoOf(next.state).contains(c.lockedIn))
      case Left(f) => fail(s"expected the valid transaction to be accepted: $f")
    c.attempt(succeeds = false, isValid = false) match
      case Right(next) =>
        // the collateral is taken, what the script locks stays
        val utxo = utxoOf(next.state)
        assert(!utxo.contains(c.collateralIn) && utxo.contains(c.lockedIn))
        assertEquals(next.plutus.failed, 1L)
        assertEquals(feesOf(next.state), 100 * ada)
      case Left(f) => fail(s"expected the invalid transaction to take its collateral: $f")
    // a verdict that is not the tag's is refused, as the node refuses it
    for (succeeds, isValid) <- Vector((false, true), (true, false)) do
      assert(c.attempt(succeeds, isValid).isLeft, s"expected a refusal for $succeeds / $isValid")
  }

  // The withdrawal accounts of a transaction are of the network of the ledger, whichever validity
  // tag the transaction has (`validateWrongNetworkWithdrawal`, `Babbage/Rules/Utxo.hs:400`, in the
  // rule UTXO that every transaction passes). The expected verdicts are those of that rule in the
  // pinned reference; the refusals of the two tags are also taken from the reference node on the
  // devnet (`ntccases.py`, `DevnetRefusedTxSuite`). Each case is run as a transaction (LEDGER) and
  // as a block of it (LEDGERS).
  for
    protocol <- Vector(10, 11)
    block <- Vector(false, true)
  do
    test(
      s"the network of a withdrawal is checked for both validity tags (protocol $protocol, ${
          if block then "a block" else "a transaction"
        })"
    ) {
      val pp = pparams.copy(protocolMajor = protocol)
      val here = Some(RewardAccount(0, alice.credential))
      val there = Some(RewardAccount(1, alice.credential))
      // a failed script, a transaction flagged invalid: with an account of the network the
      // collateral is taken, with one of another it is refused
      val invalidHere = ScriptCase(pp, here)
      invalidHere.attempt(succeeds = false, isValid = false, block) match
        case Right(next) =>
          val utxo = utxoOf(next.state)
          assert(!utxo.contains(invalidHere.collateralIn) && utxo.contains(invalidHere.lockedIn))
          assertEquals((next.plutus.failed, feesOf(next.state)), (1L, 100 * ada))
        case Left(f) => fail(s"expected the collateral to be taken: $f")
      ScriptCase(pp, there).attempt(succeeds = false, isValid = false, block) match
        case Left(LedgerFailure.Rejected.Single(_, f)) =>
          assertEquals(f.ruleName, "WrongNetworkWithdrawal")
        case other =>
          fail(s"expected the withdrawal of another network to be refused: ${outcome(other)}")
      // the same for a transaction whose scripts succeed: here LEDGER's own check of the
      // withdrawals refuses it first (CERTS before protocol version 11, LEDGER from it)
      val validHere = ScriptCase(pp, here)
      validHere.attempt(succeeds = true, isValid = true, block) match
        case Right(next) => assert(!utxoOf(next.state).contains(validHere.lockedIn))
        case Left(f)     => fail(s"expected the transaction to be accepted: $f")
      ScriptCase(pp, there).attempt(succeeds = true, isValid = true, block) match
        case Left(LedgerFailure.Rejected.Single(_, f)) =>
          assertEquals(
            f.ruleName,
            if protocol < 11 then "WithdrawalsNotInRewardsCERTS"
            else "ConwayWithdrawalsMissingAccounts"
          )
        case other =>
          fail(s"expected the withdrawal of another network to be refused: ${outcome(other)}")
    }

  test("a transaction flagged invalid collects its collateral only if the rules of UTXO hold") {
    val pp = pparams
    val c = ScriptCase(pp, Some(RewardAccount(0, alice.credential)))
    val ok = c.bodyWith(c.hash)
    // the baseline: the collateral is taken
    c.attemptWith(succeeds = false, isValid = false, ok) match
      case Right(next) => assert(!utxoOf(next.state).contains(c.collateralIn))
      case Left(f)     => fail(s"expected the collateral to be taken: $f")
    def refused(what: String, b: node.TxBody): String =
      c.attemptWith(succeeds = false, isValid = false, b) match
        case Left(LedgerFailure.Rejected.Single(_, f)) => f.ruleName
        case other => fail(s"$what: expected a refusal: ${outcome(other)}")
    val otherNetwork = node.Address
      .decode(Bytes(0x61.toByte +: alice.hash.value))
      .fold(e => fail(e.toString), identity)
    // a returned collateral output on another network, a collateral input that does not exist,
    // more collateral inputs than allowed (three), outputs that do not balance
    assertEquals(
      refused(
        "collateral return",
        c.bodyWith(c.hash, collateralReturn = Some(out(otherNetwork, 90 * ada)))
      ),
      "WrongNetwork"
    )
    // `feesOK` comes before the inputs (`Babbage/Rules/Utxo.hs:362-368`): the collateral that does
    // not exist pays nothing, which is the first failure
    assertEquals(
      refused("missing collateral", c.bodyWith(c.hash, collateral = Vector(txIn(40)))),
      "InsufficientCollateral"
    )
    assertEquals(
      refused(
        "many collateral",
        c.bodyWith(c.hash, collateral = Vector(c.collateralIn, txIn(41), txIn(42), txIn(43)))
      ),
      "TooManyCollateralInputs"
    )
    assertEquals(
      refused("balance", c.bodyWith(c.hash, outputs = Some(Vector(out(bob.address(), 49 * ada))))),
      "ValueNotConservedUTxO"
    )
  }

  test("collateral inputs exist and are not too many, whether or not there are redeemers") {
    val s = base(fundedBy(alice))
    def withCollateral(collateral: Vector[node.TxIn]) =
      tx(
        body(
          "coll",
          Vector(coinIn),
          Vector(out(alice.baseAddress(alice), 10000 * ada - 200)),
          200,
          collateral = collateral
        ),
        Vector(alice)
      )
    // no redeemers: the collateral is not looked at by the fee rule, but `allInputs` holds it
    assertEquals(refusedWith(s, withCollateral(Vector(txIn(40)))), "BadInputsUTxO")
    assertEquals(
      refusedWith(s, withCollateral(Vector(coinIn, txIn(40), txIn(41), txIn(42)))),
      "BadInputsUTxO"
    )
    assertEquals(
      refusedWith(
        base(
          Vector(coinIn, txIn(2), txIn(3), txIn(4))
            .map(_ -> out(alice.baseAddress(alice), 10000 * ada))
        ),
        withCollateral(Vector(coinIn, txIn(2), txIn(3), txIn(4)))
      ),
      "TooManyCollateralInputs"
    )
    accepted(s, withCollateral(Vector(coinIn)))
  }

  test("an output with a bootstrap address: its attributes and its network") {
    val s = base(fundedBy(alice))
    val key64 = Bytes(Vector.fill(64)(1.toByte))
    // an address with the attribute 5 (not interpreted) of `size` bytes, `magic` as attribute 2
    def byron(size: Int, magic: Option[Long]) =
      import xyz.kd5ujc.scarab.ledger.byron.{ByronAddress, ByronCbor}
      if size == 0 then ByronAddress.make(ByronAddress.VerKey, key64, magic)
      else
        val attributes = ByronCbor.concat(
          ByronCbor.of(0xa2, 0x02),
          ByronCbor.bytes(ByronCbor.uint(magic.getOrElse(0L))),
          ByronCbor.of(0x05),
          ByronCbor.bytes(Bytes(Vector.fill(size)(2.toByte)))
        )
        val body = ByronCbor.concat(
          ByronCbor.of(0x83),
          ByronCbor.bytes(ByronAddress.rootOf(ByronAddress.VerKey, key64, attributes)),
          attributes,
          ByronCbor.uint(ByronAddress.VerKey)
        )
        ByronCbor.concat(
          ByronCbor.of(0x82, 0xd8, 0x18),
          ByronCbor.bytes(body),
          ByronCbor.uint(ByronCbor.crc32(body))
        )
    def paying(raw: Bytes) =
      val to = node.Address.decode(raw).fold(e => fail(e.toString), identity)
      tx(
        body(
          "byron",
          Vector(coinIn),
          Vector(out(to, 5 * ada), out(alice.baseAddress(alice), 10000 * ada - 5 * ada - 200)),
          200
        ),
        Vector(alice)
      )
    // the ledger is on the test network (0): an address with a magic is of it, one without is not
    accepted(s, paying(byron(0, Some(42))))
    assertEquals(refusedWith(s, paying(byron(0, None))), "WrongNetwork")
    // 64 bytes of attributes are allowed, 65 are not
    accepted(s, paying(byron(64, Some(42))))
    assertEquals(
      refusedWith(s, paying(byron(65, Some(42)))),
      "OutputBootAddrAttrsTooBig"
    )
  }
