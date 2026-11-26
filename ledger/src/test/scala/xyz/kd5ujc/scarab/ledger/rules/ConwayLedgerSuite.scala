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
  Value,
  Vote,
  Voter,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*
import xyz.kd5ujc.scarab.ledger.era
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit
import xyz.kd5ujc.scarab.ledger.era.conway.ConwayTestKit.Fails
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayCertsPredFailure,
  ConwayDelegPredFailure,
  ConwayGovCertPredFailure,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxowPredFailure,
  ShelleyPoolPredFailure
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** The Conway LEDGER rules on the era-indexed state, on one transaction at a time: the suite builds
  * the native state, runs the rules on the transaction and checks what they do with it: the
  * failure, if it is refused, and the state after it, if it is not. The rules are Conway's LEDGER
  * on the era types (`conwayLedgerTransitionTRC`, `Conway/Rules/Ledger.hs:320-519`, cardano-ledger
  * f649f975), and LEDGERS over it (`ledgersTransition`, `Shelley/Rules/Ledgers.hs:185-209`,
  * cardano-ledger f649f975), on the transactions the suite builds as values written with their true
  * bytes ([[era.conway.ConwayTestKit.encode]]), each judged to its first failure. Every accepted
  * state is also checked for what the rules keep beside what they change ([[consistent]]): the
  * delegators of each pool and each DRep against the accounts, the instant stake against the UTxO,
  * the deposits against the obligation.
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

  private type Failure = era.rules.LedgerPredFailure[Conway]
  private type Verdict = Either[Vector[Failure], ConwayTestKit.Admitted]

  /** The transaction of `b`, signed by `signers`, with `witnesses` beside their key witnesses,
    * written with its true bytes ([[ConwayTestKit.encode]]).
    */
  private def tx(
      b: node.TxBody,
      signers: Vector[Key],
      witnesses: Witnesses = Witnesses.empty,
      isValid: Boolean = true
  ): ConwayTestKit.Built =
    ConwayTestKit.encode(TestLedger.tx(b, Vector.empty, witnesses, isValid), signers)

  /** The rules on one transaction, each to its first failure: Conway's LEDGER on the era types
    * ([[ConwayTestKit.judge]]), or, with `block`, LEDGERS on a block of that one
    * ([[ConwayTestKit.judgeBlock]]). An accepted state is [[consistent]].
    */
  private def run(
      s: NewEpochState[Conway],
      t: ConwayTestKit.Built,
      ctx: TxEnv = TestLedger.env,
      block: Boolean = false
  ): Verdict =
    val result =
      if block then ConwayTestKit.judgeBlock(s, t, ctx, RuleT.Policy.FirstFailure)
      else ConwayTestKit.judge(s, t, ctx, RuleT.Policy.FirstFailure)
    result.foreach(after => consistent(after.nes))
    result

  private def accepted(s: NewEpochState[Conway], t: ConwayTestKit.Built): NewEpochState[Conway] =
    run(s, t) match
      case Right(next) => next.nes
      case Left(f)     => fail(s"expected the transaction to be accepted: $f")

  /** The rules refuse `t` on `s` for the failure `rule` names, the first check that failed. */
  private def refusedBy(s: NewEpochState[Conway], t: ConwayTestKit.Built)(
      rule: PartialFunction[Failure, Unit]
  )(using munit.Location): Unit = run(s, t) match
    case Left(Vector(f)) => assert(rule.isDefinedAt(f), s"refused for $f")
    case other           => fail(s"expected a refusal: ${outcome(other)}")

  /** What the rules did, for the message of a failed test: the refusal, or that they accepted. */
  private def outcome(r: Verdict): String =
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
      ps.future.valuesIterator.map(_.vrf.bytes)

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

  // the fee for size is the constant 200 whatever the size, as it was for the transactions of 100
  // bytes the suite built as values (`TestLedger.tx`) under `minFeeA = 1, minFeeB = 100`: their
  // true bytes are larger ([[ConwayTestKit.encode]]), and the fees the tests pay stay as they were
  private def pparams: node.ProtocolParams =
    params(10).copy(
      minFeeA = 0,
      minFeeB = 200,
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
    val funded = Build.funded(
      Build.nes[Conway](5, pp),
      funds.map((in, o) => in -> ConwayTestKit.stored(o)).toMap
    )
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
  ): ConwayTestKit.Built =
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
    refusedBy(s, t) {
      case Fails.Deleg(ConwayDelegPredFailure.DelegateeStakePoolNotRegisteredDELEG(_)) =>
    }
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
    assertEquals(pStateOf(next).future.get(poolId(op)).map(_.cost.value), Some(BigInt(340)))
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
    refusedBy(withFundsOf(s)(s2), auth) {
      case Fails.GovCert(ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(_)) =>
    }
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
    refusedBy(s11, t) { case Fails.Gov(ConwayGovPredFailure.UnelectedCommitteeVoters(_)) => }
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
    refusedBy(s, unknownAction) { case Fails.Gov(ConwayGovPredFailure.GovActionsDoNotExist(_)) => }
    val unknownVoter = pay(
      "novoter",
      alice,
      10000 * ada,
      200,
      votes = Vector(Voter.DRepVoter(carol.credential) -> Vector(id -> Vote.Yes)),
      signers = Vector(carol)
    )
    refusedBy(s, unknownVoter) { case Fails.Gov(ConwayGovPredFailure.VotersDoNotExist(_)) => }
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
    refusedBy(s, partial) {
      case Fails.Certs(ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(_)) =>
    }
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
    refusedBy(s, cheap) { case Fails.Utxo(ConwayUtxoPredFailure.FeeTooSmallUTxO(_)) => }
    val ghost = tx(
      body("ghost", Vector(txIn(77)), Vector(out(alice.baseAddress(alice), ada)), 200),
      Vector(alice)
    )
    refusedBy(s, ghost) { case Fails.Utxo(ConwayUtxoPredFailure.BadInputsUTxO(_)) => }
    val unsigned = tx(
      body(
        "unsigned",
        Vector(coinIn),
        Vector(out(alice.baseAddress(alice), 10000 * ada - 200)),
        200
      ),
      Vector.empty
    )
    refusedBy(s, unsigned) {
      case Fails.Utxow(ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(_)) =>
    }
    val unbalanced = tx(
      body("unbalanced", Vector(coinIn), Vector(out(alice.baseAddress(alice), 10000 * ada)), 200),
      Vector(alice)
    )
    refusedBy(s, unbalanced) { case Fails.Utxo(ConwayUtxoPredFailure.ValueNotConservedUTxO(_)) => }
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
    // its true bytes, 1500 certificates, votes and key witnesses, are far past the suite's largest
    // transaction: the size allowed is not what this test is about
    val wide = pparams.copy(maxTxSize = 1 << 20)
    val s = base(
      fundedBy(alice),
      pools = Vector(poolParams(pool)),
      dreps = keys.map(k => cred[DRepRole](k) -> drepState(30)),
      proposals = Proposals(Vector(action), node.PrevIds()),
      pp = wide
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
        assertEquals(ConwayNewEpochState.accounts.get(next.nes).states.size, width)
        assertEquals(proposalsOf(next.nes).byId(action.id).drepVotes.size, width)
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
    run(s, t) match
      case Left(f) => fail(s"refused: $f")
      case Right(after) =>
        assertEquals(Invariants.poolDelegators(after.nes.es.ls.cert), Vector.empty)
        assertEquals(
          ConwayLedgerState.pools.get(after.nes.es.ls).values.map(_.delegators.size).sum,
          1
        )
  }

  test("the rules are not tied to their interpreter: RuleT and the reference stack agree") {
    // Haskell's `ReaderT Globals` over `StateT` over `Either` runs the same algebra (design 2.2):
    // Conway's LEDGER on the era types (`ConwayLedger.conwayLedgerTransition`) on Kleisli over
    // StateT over Either, its first failure raised, against RuleT to its first failure
    import cats.data.{Chain, NonEmptyChain}
    import cats.mtl.{Raise, Stateful, Tell}
    import xyz.kd5ujc.scarab.ledger.era.{EraLedgerRun, Phase2Deferral, Proposals as EraProposals}
    import xyz.kd5ujc.scarab.ledger.era.rules.{LedgerEvent, ProposalsRecord}
    type Rec = era.rules.LedgerPredFailure[Conway]
    type Ev = LedgerEvent[Conway]
    type Stop = Either[EraLedgerRun.Defect, Rec]
    type St = (LedgerState[Conway], Chain[Ev])
    type R[A] = Either[Stop, A]
    type S[A] = StateT[R, St, A]
    type K[A] = Kleisli[S, Globals, A]
    given Monad[K] = Kleisli.catsDataMonadForKleisli[S, Globals]
    given Stateful[K, LedgerState[Conway]] =
      summon[Stateful[K, St]].focus(
        monocle.Lens[St, LedgerState[Conway]](_._1)(l => s => (l, s._2))
      )
    given Tell[K, Chain[Ev]] = new Tell[K, Chain[Ev]]:
      val functor: cats.Functor[K] = summon[Monad[K]]
      def tell(events: Chain[Ev]): K[Unit] =
        summon[Stateful[K, St]].modify((l, e) => (l, e ++ events))
    val stop = summon[Raise[K, Stop]]
    given Raise[K, era.ScriptVerdictUnavailable] =
      stop.contramap(d => Left(EraLedgerRun.Defect.Script(d)))
    given Raise[K, era.rules.UtxoStateDefect] =
      stop.contramap(d => Left(EraLedgerRun.Defect.Utxo(d)))
    given Raise[K, node.LedgerDefect] = stop.contramap(d => Left(EraLedgerRun.Defect.Ledger(d)))
    given Raise[K, era.rules.GovDefect] = stop.contramap(d => Left(EraLedgerRun.Defect.Gov(d)))
    given Raise[K, era.ProposalsReadError] =
      stop.contramap(d => Left(EraLedgerRun.Defect.Proposals(d)))
    given Stateful[K, EraProposals[Conway]] = ProposalsRecord.stateful[K, Conway]
    given Judge[K, Rec] = Judge.raising[K, Rec, Stop](Right(_))
    given Phase2Deferral[K, Conway] = Phase2Deferral.now
    val ctx = TestLedger.env
    given xyz.kd5ujc.scarab.plutus.ScriptEvaluator[K] =
      request => Monad[K].map(Monad[K].unit)(_ => ctx.evaluator.evaluate(request))
    val pool1 = key()
    val s = base(fundedBy(alice), pools = Vector(poolParams(pool1)))
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
      val onRuleT = ConwayTestKit.eraLedger(s, t.typed, ctx, RuleT.Policy.FirstFailure)
      val (env, stAnnTx, context) = ConwayTestKit.inputsOf(s, t.typed, ctx)
      val onStack = era.rules.ConwayLedger
        .conwayLedgerTransition[K, Conway](env, stAnnTx, context, ctx.verdicts, ctx.decoder)
        .run(NativeRules.globalsOf(ctx))
        .run((s.es.ls, Chain.empty))
        .map(_._1)
      val same = (onRuleT, onStack) match
        case (Left(d), Left(Left(e)))          => d == e
        case (Right(Left(fs)), Left(Right(f))) => fs == NonEmptyChain.one(f)
        case (Right(Right((a, ea))), Right((b, eb))) =>
          cats.Eq[LedgerState[Conway]].eqv(a, b) && ea == eb
        case _ => false
      assert(same, s"RuleT $onRuleT, the stack $onStack")
    // the two transactions: one admitted, one refused
    assert(
      ConwayTestKit.eraLedger(s, delegate.typed, ctx).isRight &&
        ConwayTestKit.eraLedger(s, cheap.typed, ctx).exists(_.isLeft)
    )
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
    refusedBy(s, reg("dup", one, Blake2bHash(two.hash))) {
      case Fails.Pool(ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(_, _)) =>
    }
    val fresh = Blake2bHash(Bytes(Vector(1, 2, 3)))
    val own = Set(poolParams(one).vrfKeyHash, poolParams(two).vrfKeyHash)
    assertEquals(vrfKeys(pStateOf(s)), own)
    val s1 = accepted(s, reg("fresh", one, fresh))
    assertEquals(pStateOf(s1).future.get(poolId(one)).map(_.vrf.bytes), Some(fresh))
    // the map the rules leave: the keys in use and the scheduled one
    assertEquals(vrfKeys(pStateOf(s1)), own + fresh)
    // the scheduled key is held: another pool cannot take it
    refusedBy(withFundsOf(s)(s1), reg("taken", two, fresh)) {
      case Fails.Pool(ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(_, _)) =>
    }
    // a second re-registration in the epoch frees the first key
    val other = Blake2bHash(Bytes(Vector(4, 5, 6)))
    val s2 = accepted(withFundsOf(s)(s1), reg("again", one, other))
    assertEquals(pStateOf(s2).future.get(poolId(one)).map(_.vrf.bytes), Some(other))
    assertEquals(vrfKeys(pStateOf(s2)), own + other)
    accepted(withFundsOf(s)(s2), reg("freed", two, fresh))
  }

  test(
    "re-registering with the pool's own key hash and then another in one epoch drops the key in use (the reference's quirk)"
  ) {
    // `Pool.hs:274-286` (cardano-ledger f649f975): a re-registration whose future parameters hold
    // another key hash deletes that one from the map, whether or not it is also the key of the
    // registered pool, so the map is no longer every key the pools use.
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
      fundedBy(alice).map((in, o) => in -> ConwayTestKit.stored(o)).toMap
    )
    val t = pay(
      "quirk",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(poolParams(one).copy(vrfKeyHash = other))),
      signers = Vector(one)
    )
    // the state is built without its deposits, so it is not [[consistent]]: LEDGER alone
    val after = ConwayTestKit
      .judge(start, t, TestLedger.env, RuleT.Policy.FirstFailure)
      .fold(f => fail(s"refused: $f"), _.nes.es.ls)
    // the reference's map before: the key in use; after: the new key, and not the one the
    // registered pool still uses
    assertEquals(vrfKeys(ConwayLedgerState.pState.get(start.es.ls)), Set(own))
    val ps = ConwayLedgerState.pState.get(after)
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
    val shared11 =
      VRFVerKeyHash.fromBytes(poolParams(one).vrfKeyHash).fold(e => fail(e.toString), identity)
    def counted(build: ReferenceBuild, ps: PState): Option[Long] =
      HardFork
        .populateVRFKeyHashes(build, ps)
        .vrfKeyHashes
        .get(shared11)
        .map(_.value.bits)
    // from an empty map, as the reference has it before version 11: the same in every build
    ReferenceBuild.values.foreach(build =>
      assertEquals(counted(build, before.copy(vrfKeyHashes = Sorted.empty)), Some(2L), build.name)
    )
    // where the map already holds the key (once, as the state was built): 11.1 adds the two pools
    // to it (`HardFork.hs:107-125`, cardano-ledger f649f975), 11.2 and the Leios build count them
    // again from nothing (`State/CertState.hs:525-535` at 29a7d656, 528-538 at 9df9f98c)
    assertEquals(counted(ReferenceBuild.Release11_1, before), Some(3L))
    assertEquals(counted(ReferenceBuild.Release11_2, before), Some(2L))
    assertEquals(counted(ReferenceBuild.Leios, before), Some(2L))
  }

  test("under 11.1 HARDFORK keeps a count at the largest word where adding one would wrap") {
    // `combine` keeps the old count where `+ 1` would reach zero (`HardFork.hs:121-125`,
    // cardano-ledger f649f975)
    val one = key()
    val s = base(fundedBy(alice), pools = Vector(poolParams(one)))
    val before = pStateOf(s)
    val vrf =
      VRFVerKeyHash.fromBytes(poolParams(one).vrfKeyHash).fold(e => fail(e.toString), identity)
    val full = NonZero.from(Word64.max).fold(e => fail(e.toString), identity)
    val saturated = before.copy(vrfKeyHashes = before.vrfKeyHashes.updated(vrf, full))
    assertEquals(
      HardFork
        .populateVRFKeyHashes(ReferenceBuild.Release11_1, saturated)
        .vrfKeyHashes
        .get(vrf),
      Some(full)
    )
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
      tx(bodyWith(None), Vector(alice), wits).flat,
      pp,
      Set(ScriptKind.PlutusV3)
    )(using xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)

    def ctx(succeeds: Boolean) =
      TestLedger.env.copy(
        evaluator = scripted(succeeds),
        decoder = xyz.kd5ujc.scarab.plutus.ScriptDecoder.lenient
      )

    def attempt(
        succeeds: Boolean,
        isValid: Boolean,
        block: Boolean = false
    ): Verdict =
      attemptWith(succeeds, isValid, bodyWith(hash), block)

    def attemptWith(
        succeeds: Boolean,
        isValid: Boolean,
        b: node.TxBody,
        block: Boolean = false
    ): Verdict =
      // the integrity hash covers the witnesses, not the body: the same one serves any body
      run(state, tx(b, Vector(alice), wits, isValid = isValid), ctx(succeeds), block)

  test(
    "Plutus V3: the verdict of the scripts must equal is_valid; a failed one pays its collateral"
  ) {
    val c = ScriptCase(pparams, withdrawal = None)
    c.attempt(succeeds = true, isValid = true) match
      case Right(next) =>
        assertEquals((next.succeeded, next.failed), (1L, 0L))
        assert(!utxoOf(next.nes).contains(c.lockedIn))
      case Left(f) => fail(s"expected the valid transaction to be accepted: $f")
    c.attempt(succeeds = false, isValid = false) match
      case Right(next) =>
        // the collateral is taken, what the script locks stays
        val utxo = utxoOf(next.nes)
        assert(!utxo.contains(c.collateralIn) && utxo.contains(c.lockedIn))
        assertEquals(next.failed, 1L)
        assertEquals(feesOf(next.nes), 100 * ada)
      case Left(f) => fail(s"expected the invalid transaction to take its collateral: $f")
    // a verdict that is not the tag's is refused, as the node refuses it
    for (succeeds, isValid) <- Vector((false, true), (true, false)) do
      assert(c.attempt(succeeds, isValid).isLeft, s"expected a refusal for $succeeds / $isValid")
  }

  // The withdrawal accounts of a transaction are of the network of the ledger, whichever validity
  // tag the transaction has (`validateWrongNetworkWithdrawal`, `Babbage/Rules/Utxo.hs:400`
  // (cardano-ledger f649f975), in the rule UTXO that every transaction passes). The expected
  // verdicts are those of that rule in the pinned reference; the refusals of the two tags are also
  // taken from the reference node on the devnet (`ntccases.py`, `DevnetRefusedTxSuite`). Each case
  // is run as a transaction (LEDGER) and as a block of it (LEDGERS).
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
          val utxo = utxoOf(next.nes)
          assert(!utxo.contains(invalidHere.collateralIn) && utxo.contains(invalidHere.lockedIn))
          assertEquals((next.failed, feesOf(next.nes)), (1L, 100 * ada))
        case Left(f) => fail(s"expected the collateral to be taken: $f")
      ScriptCase(pp, there).attempt(succeeds = false, isValid = false, block) match
        case Left(Vector(Fails.Utxo(ConwayUtxoPredFailure.WrongNetworkWithdrawal(_, _)))) => ()
        case other =>
          fail(s"expected the withdrawal of another network to be refused: ${outcome(other)}")
      // the same for a transaction whose scripts succeed: here LEDGER's own check of the
      // withdrawals refuses it first (CERTS before protocol version 11, LEDGER from it)
      val validHere = ScriptCase(pp, here)
      validHere.attempt(succeeds = true, isValid = true, block) match
        case Right(next) => assert(!utxoOf(next.nes).contains(validHere.lockedIn))
        case Left(f)     => fail(s"expected the transaction to be accepted: $f")
      ScriptCase(pp, there).attempt(succeeds = true, isValid = true, block) match
        case Left(Vector(Fails.Certs(ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(_))))
            if protocol < 11 =>
          ()
        case Left(Vector(ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(_)))
            if protocol >= 11 =>
          ()
        case other =>
          fail(s"expected the withdrawal of another network to be refused: ${outcome(other)}")
    }

  test("a transaction flagged invalid collects its collateral only if the rules of UTXO hold") {
    val pp = pparams
    val c = ScriptCase(pp, Some(RewardAccount(0, alice.credential)))
    val ok = c.bodyWith(c.hash)
    // the baseline: the collateral is taken
    c.attemptWith(succeeds = false, isValid = false, ok) match
      case Right(next) => assert(!utxoOf(next.nes).contains(c.collateralIn))
      case Left(f)     => fail(s"expected the collateral to be taken: $f")
    def refused(what: String, b: node.TxBody)(rule: PartialFunction[Failure, Unit]): Unit =
      c.attemptWith(succeeds = false, isValid = false, b) match
        case Left(Vector(f)) => assert(rule.isDefinedAt(f), s"$what: refused for $f")
        case other           => fail(s"$what: expected a refusal: ${outcome(other)}")
    val otherNetwork = node.Address
      .decode(Bytes(0x61.toByte +: alice.hash.value))
      .fold(e => fail(e.toString), identity)
    // a returned collateral output on another network, a collateral input that does not exist,
    // more collateral inputs than allowed (three), outputs that do not balance
    refused(
      "collateral return",
      c.bodyWith(c.hash, collateralReturn = Some(out(otherNetwork, 90 * ada)))
    ) { case Fails.Utxo(ConwayUtxoPredFailure.WrongNetwork(_, _)) => }
    // `feesOK` comes before the inputs (`Babbage/Rules/Utxo.hs:370-376`, cardano-ledger f649f975):
    // the collateral that does not exist pays nothing, which is the first failure
    refused("missing collateral", c.bodyWith(c.hash, collateral = Vector(txIn(40)))) {
      case Fails.Utxo(ConwayUtxoPredFailure.InsufficientCollateral(_, _)) =>
    }
    refused(
      "many collateral",
      c.bodyWith(c.hash, collateral = Vector(c.collateralIn, txIn(41), txIn(42), txIn(43)))
    ) { case Fails.Utxo(ConwayUtxoPredFailure.TooManyCollateralInputs(_)) => }
    refused("balance", c.bodyWith(c.hash, outputs = Some(Vector(out(bob.address(), 49 * ada))))) {
      case Fails.Utxo(ConwayUtxoPredFailure.ValueNotConservedUTxO(_)) =>
    }
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
    refusedBy(s, withCollateral(Vector(txIn(40)))) {
      case Fails.Utxo(ConwayUtxoPredFailure.BadInputsUTxO(_)) =>
    }
    refusedBy(s, withCollateral(Vector(coinIn, txIn(40), txIn(41), txIn(42)))) {
      case Fails.Utxo(ConwayUtxoPredFailure.BadInputsUTxO(_)) =>
    }
    refusedBy(
      base(
        Vector(coinIn, txIn(2), txIn(3), txIn(4))
          .map(_ -> out(alice.baseAddress(alice), 10000 * ada))
      ),
      withCollateral(Vector(coinIn, txIn(2), txIn(3), txIn(4)))
    ) { case Fails.Utxo(ConwayUtxoPredFailure.TooManyCollateralInputs(_)) => }
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
    refusedBy(s, paying(byron(0, None))) {
      case Fails.Utxo(ConwayUtxoPredFailure.WrongNetwork(_, _)) =>
    }
    // 64 bytes of attributes are allowed, 65 are not; Conway reports Shelley's failure as
    // `OutputTooBigUTxO` (`Conway/Rules/Utxo.hs:158-161` (cardano-ledger f649f975),
    // `Allegra/Rules/Utxo.hs:387`)
    accepted(s, paying(byron(64, Some(42))))
    refusedBy(s, paying(byron(65, Some(42)))) {
      case Fails.Utxo(ConwayUtxoPredFailure.OutputTooBigUTxO(_)) =>
    }
  }
