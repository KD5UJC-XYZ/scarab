// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.rules

import cats.Monad
import cats.data.{Kleisli, StateT}
import cats.mtl.Handle.{handleForApplicativeError, handleKleisli}
import cats.syntax.all.*
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.ledger as node
import xyz.kd5ujc.scarab.ledger.{
  Account,
  Certificate,
  Committee,
  CommitteeAuth,
  Credential as NodeCredential,
  Datum,
  DRep as NodeDRep,
  DRepState as NodeDRepState,
  GovAction,
  GovActionId,
  GovActionState,
  GovState as NodeGovState,
  LedgerFailure,
  PoolParams,
  PoolState as NodePoolState,
  Proposal,
  Proposals,
  Redeemer,
  RewardAccount,
  Script,
  ScriptIntegrity,
  ScriptKind,
  TestLedger,
  Tx,
  TxRules,
  Value,
  Vote,
  Voter,
  Witnesses
}
import xyz.kd5ujc.scarab.ledger.state.*
import xyz.kd5ujc.scarab.ledger.TestLedger.*

/** The Conway LEDGER rules on the era-indexed state against the node's own (`ConwayRules`), on one
  * transaction at a time: both sides are given the same state (the era-indexed one is the old
  * state's mirror), and what each does with the transaction is compared: the failure, if it is
  * refused, and the state after it, if it is not (the new side's state converted back, which checks
  * the derived fields it maintains: the delegators of each pool and each DRep, the accounts, the
  * instant stake, the deposits).
  *
  * The suites of the old rules (`ConwayRulesSuite`, `ConwayGovernanceSuite`) become differential
  * tests as a whole at J2.6; this is the part of that for the rules this stage ports, with the
  * cases that the maintenance of state makes interesting.
  */
class ConwayLedgerSuite extends FunSuite:
  type Result[A] = Either[MirrorError, A]
  type M[A] = Kleisli[Result, MirrorEnv, A]
  private val mirror: LedgerMirror[M] = LedgerMirror[M]
  private val env = FlatStateGen.env
  private val at: WithOrigin[SlotNo] = WithOrigin.At(SlotNo.fromUnsigned(50L))

  private def run[A](program: M[A]): Result[A] = program.run(env)

  private val globals = Globals(
    node.LedgerGlobals(TestLedger.Supply, 100, Rational(1, 2), network = 0, updateQuorum = 2),
    env.geometry
  )

  private val alice = key()
  private val bob = key()
  private val carol = key()
  private val ada = BigInt(1000000)

  /** What a side did with a transaction. */
  private enum Out:
    case Accepted(state: node.LedgerState)
    case Refused(failure: LedgerFailure)
    case Broken(why: String)

  private def oldSide(s: node.LedgerState, t: Tx, ctx: TxRules.Env): Out =
    TxRules(s, t, ctx) match
      case Right(next) => Out.Accepted(next)
      case Left(f)     => Out.Refused(f)

  private def newSide(s: node.LedgerState, t: Tx, ctx: TxRules.Env): Out =
    run(mirror.to[Conway](at, s)) match
      case Left(e) => Out.Broken(s"to the mirror: ${e.show}")
      case Right(image) =>
        val nes = image.value.nes
        val result = Sts(Ledger)(
          globals,
          LedgerEnv(ctx, nes.es.chainAccount.treasury),
          Counted(nes.es.ls, s.plutus),
          t
        )
        result match
          case Left(f)      => Out.Refused(f)
          case Right(after) =>
            // before protocol version 11 the reference does not keep the VRF key hashes of the
            // pools (the hard fork into 11 populates them): the mirror of the old state derives
            // them from version 10, so they are derived here too
            val ls =
              if s.params.protocolMajor == 10 then
                ConwayLedgerState.pState.modify(vrfFromPools)(after.state)
              else after.state
            val next = image.value.copy(
              nes = nes.copy(es = nes.es.copy(ls = ls)),
              extras = image.value.extras.copy(plutus = after.plutus)
            )
            run(mirror.from[Conway](at, next)) match
              case Left(e)      => Out.Broken(s"back from the mirror: ${e.show}")
              case Right(state) => Out.Accepted(state)

  /** The VRF key hashes as the mirror derives them: once for each pool that uses a key, as
    * registered or as re-registered.
    */
  private def vrfFromPools(ps: PState): PState =
    val ids = ps.stakePools.keySet ++ ps.future.keySet
    val keys = ids.toList.flatMap { id =>
      (ps.stakePools.get(id).map(_.vrf) ++
        ps.future.get(id).flatMap(f => VRFVerKeyHash.fromBytes(f.vrfKeyHash).toOption)).toSet
    }
    ps.copy(vrfKeyHashes = Sorted.map(keys.groupMapReduce(identity)(_ => 1L)(_ + _).toList.flatMap {
      (k, n) => NonZero.from(Word64.fromUnsigned(n)).toOption.map(k -> _)
    }))

  /** Both sides on one transaction. */
  private def both(
      s: node.LedgerState,
      t: Tx,
      ctx: TxRules.Env = TestLedger.env
  ): (Out, Out) = (oldSide(s, t, ctx), newSide(s, t, ctx))

  private def agree(s: node.LedgerState, t: Tx, ctx: TxRules.Env = TestLedger.env): Out =
    val (o, n) = both(s, t, ctx)
    (o, n) match
      case (Out.Accepted(a), Out.Accepted(b)) =>
        // what the node holds as the same, through the mirror's normal form
        assertEquals(LedgerMirror.normalised(0, b), LedgerMirror.normalised(0, a))
      case _ => assertEquals(n, o)
    n

  private def accepted(s: node.LedgerState, t: Tx): node.LedgerState = agree(s, t) match
    case Out.Accepted(next) => next
    case other              => fail(s"expected the transaction to be accepted: $other")

  private def refusedWith(s: node.LedgerState, t: Tx): String = agree(s, t) match
    case Out.Refused(LedgerFailure.Rejected(_, f)) => f.ruleName
    case other                                     => fail(s"expected a refusal: $other")

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

  private def base(
      funds: Vector[(node.TxIn, node.TxOut)],
      accounts: Map[NodeCredential, Account] = Map.empty,
      pools: Map[Bytes, NodePoolState] = Map.empty,
      dreps: Map[NodeCredential, NodeDRepState] = Map.empty,
      gov: NodeGovState = NodeGovState.empty,
      committeeState: Map[NodeCredential, CommitteeAuth] = Map.empty,
      pp: node.ProtocolParams = pparams
  ): node.LedgerState =
    val s = state(pp.protocolMajor.toInt, funds)
    val deposits = accounts.values.map(_.deposit).sum + pools.values.map(_.deposit).sum +
      dreps.values.map(_.deposit).sum + gov.proposals.totalDeposits
    s.copy(
      params = pp,
      accounts = accounts,
      pools = pools,
      dreps = dreps,
      gov = gov,
      committeeState = committeeState,
      deposited = deposits,
      reserves = s.reserves - deposits,
      // an epoch's worth of the rest of the state the mirror needs
      epoch = TestLedger.env.epoch
    )

  private def poolOf(operator: Key, deposit: BigInt = 500 * ada): NodePoolState =
    NodePoolState(poolParams(operator), deposit)

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
      withdrawals: Vector[(RewardAccount, BigInt)] = Vector.empty
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
    assertEquals(next.fees, BigInt(200))
    // the stake of the output is that of the credential in its address
    assertEquals(next.utxo.stake.get(alice.credential), Some(10000 * ada - 200))
  }

  test("a stake registration with a delegation, then a re-delegation, an unregistration") {
    val pool1 = key()
    val pool2 = key()
    val s = base(
      fundedBy(alice),
      pools = Map(pool1.hash -> poolOf(pool1), pool2.hash -> poolOf(pool2))
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
    assertEquals(s1.accounts(bob.credential).pool, Some(pool1.hash))
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
    val s2 = accepted(s1.copy(utxo = s.utxo), redelegate)
    assertEquals(s2.accounts(bob.credential).pool, Some(pool2.hash))
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
    val s3 = accepted(s2.copy(utxo = s.utxo), unregister)
    assertEquals(s3.accounts.get(bob.credential), None)
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
    val s = base(fundedBy(alice), pools = Map(op.hash -> poolOf(op)))
    val rereg = pay(
      "rereg",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RegisterPool(poolParams(op, cost = 340))),
      signers = Vector(op)
    )
    val next = accepted(s, rereg)
    assertEquals(next.futurePools.get(op.hash).map(_.cost), Some(BigInt(340)))
    val retire = pay(
      "retire",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.RetirePool(op.hash, 8)),
      signers = Vector(op)
    )
    assertEquals(accepted(s, retire).retiring.get(op.hash), Some(BigInt(8)))
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
    assert(accepted(s, reg).pools.contains(fresh.hash))
  }

  test("DRep registration, delegation to it, a vote that extends it, unregistration") {
    val drepCred = bob.credential
    val s = base(
      fundedBy(alice),
      accounts = Map(
        carol.credential -> Account(0, 2 * ada, None, None)
      )
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
    assert(s1.dreps.contains(drepCred))
    val delegate = pay(
      "votedelegate",
      alice,
      10000 * ada,
      200,
      certificates = Vector(
        Certificate.Delegate(carol.credential, None, Some(NodeDRep.Registered(drepCred)), None)
      ),
      signers = Vector(carol)
    )
    val s2 = accepted(s1.copy(utxo = s.utxo), delegate)
    assertEquals(s2.dreps(drepCred).delegs, Set(carol.credential))
    val unreg = pay(
      "unregdrep",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.UnregisterDRep(drepCred, 500 * ada)),
      extra = -500 * ada,
      signers = Vector(bob)
    )
    val s3 = accepted(s2.copy(utxo = s.utxo), unreg)
    // the delegator loses the delegation
    assertEquals(s3.accounts(carol.credential).drep, None)
  }

  test("a committee authorisation, a resignation, an authorisation after it is refused") {
    val committee = Committee(Map(cold.credential -> BigInt(100)), Rational(1, 2))
    val s = base(fundedBy(alice), gov = NodeGovState.empty.copy(committee = Some(committee)))
    val auth = pay(
      "auth",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.CommitteeAuthorize(cold.credential, hot.credential)),
      signers = Vector(cold)
    )
    val s1 = accepted(s, auth)
    assertEquals(s1.committeeState.get(cold.credential), Some(CommitteeAuth.Hot(hot.credential)))
    val resign = pay(
      "resign",
      alice,
      10000 * ada,
      200,
      certificates = Vector(Certificate.CommitteeResign(cold.credential, None)),
      signers = Vector(cold)
    )
    val s2 = accepted(s1.copy(utxo = s.utxo), resign)
    assertEquals(refusedWith(s2.copy(utxo = s.utxo), auth), "ConwayCommitteeHasPreviouslyResigned")
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
        gov = NodeGovState.empty.copy(
          proposals = Proposals(Vector(action), node.PrevIds()),
          committee = committee
        ),
        committeeState = Map(cold.credential -> CommitteeAuth.Hot(hot.credential)),
        accounts = Map(alice.credential -> Account(0, 2 * ada, None, None)),
        dreps = Map(voter.credential -> NodeDRepState(500 * ada, 5)),
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
    val gas = next.gov.proposals.byId(id)
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
    assertEquals(next.gov.proposals.byId(id).committeeVotes, Map(hot.credential -> Vote.Yes))
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
      accounts = Map(alice.credential -> Account(0, 2 * ada, None, None))
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
    assertEquals(next.gov.proposals.actions.size, 1)
    assertEquals(next.deposited, s.deposited + 1000 * ada)
  }

  test("a withdrawal drains the account; one that does not is refused") {
    val s = base(
      fundedBy(alice),
      accounts =
        Map(alice.credential -> Account(7 * ada, 2 * ada, None, Some(NodeDRep.AlwaysAbstain)))
    )
    val good =
      pay(
        "wd",
        alice,
        10000 * ada,
        200,
        withdrawals = Vector(RewardAccount(0, alice.credential) -> 7 * ada)
      )
    assertEquals(accepted(s, good).accounts(alice.credential).balance, BigInt(0))
    val partial =
      pay(
        "wd2",
        alice,
        10000 * ada,
        200,
        withdrawals = Vector(RewardAccount(0, alice.credential) -> 3 * ada)
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
    agree(s, t) match
      case Out.Refused(_) => ()
      case other          => fail(s"expected a refusal, got $other")
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
      pools = Map(pool.hash -> poolOf(pool)),
      dreps = keys.map(k => k.credential -> NodeDRepState(500 * ada, 30)).toMap,
      gov = NodeGovState.empty.copy(proposals = Proposals(Vector(action), node.PrevIds()))
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
    val (o, n) = onSmallStack(both(s, t))
    assertEquals(n, o)
    n match
      case Out.Accepted(next) =>
        assertEquals(next.accounts.size, width)
        assertEquals(next.gov.proposals.byId(action.id).drepVotes.size, width)
      case other => fail(s"expected the wide transaction to be accepted: $other")
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
    val s = base(fundedBy(alice), pools = Map(pool1.hash -> poolOf(pool1)))
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
    run(mirror.to[Conway](at, s)).map(_.value.nes.es.ls) match
      case Left(e) => fail(e.show)
      case Right(ls) =>
        assertEquals(Invariants.poolDelegators(ls.cert), Vector.empty)
        Sts(Ledger)(
          globals,
          LedgerEnv(TestLedger.env, Coin.zero),
          Counted(ls, s.plutus),
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
    val s = base(fundedBy(alice), pools = Map(pool1.hash -> poolOf(pool1)))
    val image = run(mirror.to[Conway](at, s)).fold(e => fail(e.show), identity).value
    val start = Counted(image.nes.es.ls, s.plutus)
    val ledgerEnv = LedgerEnv(TestLedger.env, image.nes.es.chainAccount.treasury)
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
      val onRuleT = Sts(Ledger)(globals, ledgerEnv, start, t)
      val onStack = Ledger
        .transition[K](ledgerEnv, t)(using summon, summon, summon, summon)
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
      pools = Map(one.hash -> poolOf(one), two.hash -> poolOf(two)),
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
    val s1 = accepted(s, reg("fresh", one, fresh))
    assertEquals(s1.futurePools(one.hash).vrfKeyHash, fresh)
    // the scheduled key is held: another pool cannot take it
    assertEquals(
      refusedWith(s1.copy(utxo = s.utxo), reg("taken", two, fresh)),
      "VRFKeyHashAlreadyRegistered"
    )
    // a second re-registration in the epoch frees the first key
    val other = Blake2bHash(Bytes(Vector(4, 5, 6)))
    val s2 = accepted(s1.copy(utxo = s.utxo), reg("again", one, other))
    assertEquals(s2.futurePools(one.hash).vrfKeyHash, other)
    accepted(s2.copy(utxo = s.utxo), reg("freed", two, fresh))
  }

  test(
    "entering protocol version 11 populates the VRF key hashes, a key held twice counted twice"
  ) {
    val one = key()
    val two = key()
    val s = base(fundedBy(alice), pools = Map(one.hash -> poolOf(one), two.hash -> poolOf(two)))
    val shared = poolParams(two).copy(vrfKeyHash = poolParams(one).vrfKeyHash)
    val image = run(
      mirror
        .to[Conway](at, s.copy(pools = s.pools.updated(two.hash, NodePoolState(shared, 500 * ada))))
    )
      .fold(e => fail(e.show), identity)
      .value
    val before = ConwayLedgerState.pState.get(image.nes.es.ls)
    val after = HardFork.populateVRFKeyHashes(before.copy(vrfKeyHashes = Sorted.empty))
    val shared11 =
      VRFVerKeyHash.fromBytes(poolParams(one).vrfKeyHash).fold(e => fail(e.toString), identity)
    assertEquals(after.vrfKeyHashes.get(shared11).map(_.value.bits), Some(2L))
    // the same where the map was already derived: it starts again
    assertEquals(HardFork.populateVRFKeyHashes(before).vrfKeyHashes, after.vrfKeyHashes)
  }

  test(
    "Plutus V3: the verdict of the scripts must equal is_valid; a failed one pays its collateral"
  ) {
    val plutus = Script.plutus(ScriptKind.PlutusV3, Bytes(Vector.fill(10)(7)))
    val scriptAddress =
      node.Address.decode(Bytes(0x70.toByte +: plutus.hash.value)).fold(e => fail(e), identity)
    val datum = Bytes(Vector(0x01))
    val locked = node
      .TxOut(scriptAddress, Value.coin(50 * ada))
      .copy(datum = Datum.Hash(Blake2b.hash256.hash(datum)))
    val collateralIn = txIn(2)
    val lockedIn = txIn(3)
    val s = base(
      Vector(collateralIn -> out(alice.address(), 100 * ada), lockedIn -> locked)
    )
    val units = ExUnits(1000, 1000000)
    val wits = Witnesses(
      Vector.empty,
      scripts = Vector(plutus),
      datums = Vector(datum),
      redeemers = Vector(Redeemer(0, 0, Bytes(Vector(0x01)), units)),
      // `{ [0, 0] : [1, [1000, 1000000]] }` and `[ 1 ]`
      redeemersRaw = Some(
        Bytes(
          Vector(0xa1, 0x82, 0x00, 0x00, 0x82, 0x01, 0x82, 0x19, 0x03, 0xe8, 0x1a, 0x00, 0x0f, 0x42,
            0x40).map(_.toByte)
        )
      ),
      datumsRaw = Some(Bytes(Vector(0x81, 0x01).map(_.toByte)))
    )
    val fee = BigInt(100 + 100) + (pparams.priceMem * Rational(1000) +
      pparams.priceSteps * Rational(1000000)).ceil
    def bodyWith(hash: Option[Bytes]) =
      body(
        "v3",
        Vector(lockedIn),
        Vector(out(bob.address(), 50 * ada - fee)),
        fee,
        collateral = Vector(collateralIn),
        scriptDataHash = hash
      )
    val hash = ScriptIntegrity.hash(
      tx(bodyWith(None), Vector(alice), wits),
      pparams,
      Set(ScriptKind.PlutusV3)
    )
    def ctx(succeeds: Boolean) =
      TestLedger.env.copy(
        evaluator = scripted(succeeds),
        decoder = xyz.kd5ujc.scarab.plutus.ScriptDecoder.lenient
      )
    def attempt(succeeds: Boolean, isValid: Boolean) =
      agree(s, tx(bodyWith(hash), Vector(alice), wits, isValid = isValid), ctx(succeeds))
    attempt(succeeds = true, isValid = true) match
      case Out.Accepted(next) =>
        assertEquals((next.plutus.succeeded, next.plutus.failed), (1L, 0L))
        assert(!next.utxo.contains(lockedIn))
      case other => fail(s"expected the valid transaction to be accepted: $other")
    attempt(succeeds = false, isValid = false) match
      case Out.Accepted(next) =>
        // the collateral is taken, what the script locks stays
        assert(!next.utxo.contains(collateralIn) && next.utxo.contains(lockedIn))
        assertEquals(next.plutus.failed, 1L)
        assertEquals(next.fees, 100 * ada)
      case other => fail(s"expected the invalid transaction to take its collateral: $other")
    // a verdict that is not the tag's is refused, as the node refuses it
    for (succeeds, isValid) <- Vector((false, true), (true, false)) do
      attempt(succeeds, isValid) match
        case Out.Refused(_) => ()
        case other          => fail(s"expected a refusal for $succeeds / $isValid: $other")
  }
