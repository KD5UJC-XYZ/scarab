// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import java.time.Instant
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.Unsigned
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  Horizon,
  TestGeometry,
  TestLedger,
  TxEnv,
  Utxo,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  CertState,
  ChainAccountState,
  Coin,
  DeltaCoin,
  CompactCoin,
  ConwayAccounts,
  Credential,
  DState,
  Dijkstra,
  Hash32,
  ScriptHash,
  Staking,
  EpochNo,
  LedgerState,
  Network,
  TxIx,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Dijkstra's LEDGER, LEDGERS and MEMPOOL on the era types (`DijkstraLedger`, `DijkstraLedgers`,
  * `DijkstraMempool`; cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{Ledger,Ledgers,Mempool}.hs`), where the
  * flat rules cannot judge (they halt at ENTITIES and SUBLEDGERS):
  *   - the 200 transactions of the public Leios testnet's chain (`fixtures/reference/
  *     leios-testnet-chain-contexts`, recorded with the outputs they spend; the testnet's ledger
  *     accepted each), the 28 batches among them: from the empty state LEDGER refuses exactly those
  *     whose seed holds anything, each with the failures its sub-rules give it run one by one; from
  *     its seed, the least state its body needs, derived from the body alone, LEDGER admits each,
  *     every script run by the node's evaluator, and leaves the state its parts derive without
  *     LEDGER's transition, its events the whole vector its sub-rules tell run one by one;
  *   - the state each sub-rule is given: ENTITIES the accounts before the batch, GOV the
  *     certificate state after ENTITIES (a crafted registration of a proposal's return account),
  *     the failing flag ENTITIES inherits from LEDGER's own checks, the treasury and the reference
  *     scripts' size checked whatever the flag, and for a batch flagged invalid SUBLEDGERS run (a
  *     sub-transaction's refusal of its network) and SUBENTITIES and ENTITIES skipped;
  *   - the reference's own cases of the pinned suite (`eras/dijkstra/impl/testlib/Test/Cardano/
  *     Ledger/Dijkstra/Imp/`, cardano-ledger 9df9f98c), each with its exact expectation, on batches
  *     built as its fixups leave them (`DijkstraImpBatch`), with real signatures and every check:
  *     UTXOW's pool state after SUBLEDGERS and the certificate state before the transaction for the
  *     batch's balance (`UtxoSpec.hs:525`, a fresh pool a sub-transaction registers re-registered
  *     by the top level), a sub-transaction's reference script and one two share counted towards
  *     the batch (`LedgerSpec.hs:122`, `:138`), the size before the treasury (`:195`), and
  *     MEMPOOL's look at the top level's inputs alone (`MempoolSpec.hs:44`, `:91`);
  *   - LEDGERS as LEDGER one level down and LEDGER after LEDGER over a block's transactions, each
  *     annotated against the state the one before left (a chain block, and a built one whose second
  *     transaction spends the script output the first pays); MEMPOOL as LEDGER one level down, and
  *     its refusal of a transaction all of whose inputs are spent;
  *   - the injections into LEDGER, LEDGERS and MEMPOOL on built values against the reference's
  *     compositions, every family LEDGERS lists, and none for the constructors the reference calls
  *     impossible or the failures LEDGERS and MEMPOOL have no instance for: they do not compile.
  *
  * Incomplete (CNF-2), not run here:
  *   - the chain's transactions as the reference judged them: the fixture records neither the
  *     chain's certificate state nor its proposals, so the seed is the least state the bodies need
  *     and the verdicts are the rules';
  *   - UTXOW's pool state after SUBLEDGERS on recorded data: no recording holds a sub-transaction
  *     registering a pool (the reference's own case above judges it);
  *   - UTXOW's certificate state before the transaction where the reference's case above does not
  *     read it, the key witnesses it needs and the deposits the UTxO state records
  *     (`Dijkstra/Rules/Utxow.hs:297`, `Dijkstra/Rules/Utxo.hs:422-426`), and ENTITIES' committee
  *     proposals after SUBLEDGERS: neither a recording nor a case of the pinned suite tells them
  *     apart from the states after, and UTXOW admits the chain's two transactions that unregister
  *     accounts with either certificate state (a component check);
  *   - any refusal of LEDGER's own checks or MEMPOOL's on recorded data: no recording holds one, so
  *     the refusals here are the rules' on crafted and built transactions and states, the built
  *     ones against the pinned suite's expectations;
  *   - the comparison of the failures with the reference's bytes, which `DijkstraRulesOracleSuite`
  *     makes through the flat form on the oracle's cases: the era failures have no encoder yet
  *     (#796).
  */
class DijkstraLedgerSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val bodies = EraTxBody.dijkstra

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private val fixture = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  private lazy val systemStart: BigInt =
    BigInt(Instant.parse(str(fixture, "systemStart")).getEpochSecond)
  private lazy val slotLength: BigInt =
    fixture.hcursor.get[BigInt]("slotLength").fold(e => fail(e.message), identity)
  private lazy val epochSize: BigInt =
    fixture.hcursor.get[BigInt]("epochSize").fold(e => fail(e.message), identity)

  private lazy val leios: Globals =
    TestLedger.globals.copy(
      ledger = TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.Leios),
      geometry = EpochGeometry
        .checked(
          Instant.ofEpochSecond(systemStart.toLong),
          (slotLength * 1000).toLong,
          epochSize.toLong,
          TestGeometry.k(2),
          TestGeometry.f("0.5")
        )
        .fold(e => fail(e.message), identity)
    )

  private lazy val stored: flat.ProtocolParams =
    def genesis(era: String) = json(s"fixtures/leios/testnet/config/$era-genesis.json")
    flat.dijkstra.DijkstraFixture.genesis.upgrade(
      flat.ProtocolParams
        .fromGenesis(genesis("shelley"), genesis("alonzo"), Some(genesis("conway")))
        .fold(e => fail(e.toString), identity)
    )

  private lazy val pp: PParams[Dijkstra] =
    val languages = PlutusLanguage.values.map(l => s"Plutus$l" -> l).toMap
    val models = fixture.hcursor
      .get[Map[String, Vector[BigInt]]]("costModels")
      .fold(e => fail(e.message), identity)
      .map((name, values) => languages.getOrElse(name, fail(name)).id -> values)
    PParams
      .lensHKD[Dijkstra]
      .andThen(EraPParams.dijkstra.hkdCostModelsL[Identity])
      .replace(CostModels.from(models).fold(e => fail(e.toString), identity))(era(stored))

  private def clock(slot: BigInt): Either[flat.TranslationError, BigInt] =
    Right((systemStart + slot * slotLength) * 1000)

  private lazy val history: Horizon =
    val params = Horizon.Params(epochSize, slotLength * 1000, BigInt(10).pow(9), BigInt(10).pow(9))
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(BigInt(0))))

  private def context(validation: TxEnv.Validation) =
    DijkstraUtxoContext(
      Network.Testnet,
      xyz.kd5ujc.scarab.ledger.SystemStart(Instant.ofEpochSecond(systemStart.toLong)),
      history,
      validation
    )

  private lazy val start: LedgerState[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(EpochNo.fromUnsigned(0L), stored).es.ls

  private val noProposals: Proposals[Dijkstra] =
    Proposals.empty[Dijkstra](GovRelation[Option](None, None, None, None))

  /** A chain transaction read, its recorded outputs and the slot it is judged at. */
  private final case class Case(
      name: String,
      slot: BigInt,
      index: Int,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra]
  )

  private def caseOf(c: io.circe.Json): Case =
    val tx = ok(DijkstraTx.inBlock(node(hex(str(c, "bytes")))))
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) =>
        txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(DijkstraTxOut.decode(node(hex(o))))
      )
    val slot = c.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity)
    // an endorser block's transaction is recorded at the slot of the ranking block that certifies
    // the endorser block (`slot`) and at the endorser block's own (`ebSlot`); one whose validity
    // ends before the first is judged at the second, as `DijkstraUtxowSuite` shows
    val ends = bodies.vldt[TxLevel.Top](tx.body).invalidHereafter
    val at =
      if ends.exists(_ <= slot) then
        c.hcursor
          .get[BigInt]("ebSlot")
          .fold(e => fail(s"${str(c, "name")}: ${e.message}"), identity)
      else slot
    Case(
      str(c, "name"),
      at,
      c.hcursor.get[Int]("index").fold(e => fail(e.message), identity),
      tx,
      UTxO[Dijkstra](utxo.toMap)
    )

  private lazy val chain: Vector[Case] = chainContextCases.map(caseOf)

  private def storedState(utxo: UTxO[Dijkstra]): UTxOState[Dijkstra] =
    start.utxo.copy[Dijkstra](
      utxo = UtxoTables(Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)),
      instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, start.utxo.instantStake))
    )

  private def envOf(k: Case, treasury: Coin): LedgerEnv[Dijkstra] =
    LedgerEnv[Dijkstra](
      k.slot,
      Some(ok(EpochNo.fromBigInt(k.slot / epochSize))),
      TxIx.txIxFromIntegral(k.index).getOrElse(fail(s"${k.name}: index ${k.index}")),
      pp,
      ChainAccountState(treasury, Coin(0))
    )

  private def annotate(k: Case): DijkstraStAnnTx.TopTx =
    DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, k.utxo, Map.empty, k.tx)

  private def judge(
      k: Case,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra] = noProposals,
      treasury: Coin = Coin(0),
      validation: TxEnv.Validation = TxEnv.Validation.All
  ) =
    DijkstraLedgerRun.ledger(
      leios,
      envOf(k, treasury),
      annotate(k),
      context(validation),
      state,
      proposals,
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  // ---- the seeded state of a chain transaction ---------------------------------------------------

  private val sub = BothLevels.Sub

  private def subBodies(tx: DijkstraTx[TxLevel.Top]): Vector[TxBody[TxLevel.Sub, Dijkstra]] =
    bodies.subTransactions(tx.body).toVector.map(_.body)

  private def keyed(m: Map[flat.RewardAccount, Coin]): Map[AccountAddress, Coin] =
    ok(ENTITIES.keyed(m)).toMap

  private def withdrawalsOf(tx: DijkstraTx[TxLevel.Top]): Vector[Map[AccountAddress, Coin]] =
    subBodies(tx).map(b => bodies.withdrawals[TxLevel.Sub](b)(using sub).unWithdrawals.toMap) :+
      bodies.withdrawals[TxLevel.Top](tx.body).unWithdrawals.toMap

  private def depositsOf(tx: DijkstraTx[TxLevel.Top]): Vector[Map[AccountAddress, Coin]] =
    subBodies(tx).map(b => keyed(bodies.directDeposits[TxLevel.Sub](b)(using sub))) :+
      keyed(bodies.directDeposits[TxLevel.Top](tx.body))

  private def certsOf(tx: DijkstraTx[TxLevel.Top]): Vector[Vector[DijkstraTxCert]] =
    subBodies(tx).map(b => bodies.certs[TxLevel.Sub](b)(using sub)) :+
      bodies.certs[TxLevel.Top](tx.body)

  private def proposalsOf(tx: DijkstraTx[TxLevel.Top]): Vector[ProposalProcedure[Dijkstra]] =
    subBodies(tx).flatMap(b => bodies.proposalProcedures[TxLevel.Sub](b)(using sub).toVector) ++
      bodies.proposalProcedures[TxLevel.Top](tx.body).toVector

  /** The intervals the batch's levels give the current and the original balances. */
  private def intervalsOf(
      tx: DijkstraTx[TxLevel.Top]
  ): Vector[(AccountAddress, AccountBalanceInterval)] =
    (subBodies(tx).flatMap(b =>
      bodies.accountBalanceIntervals[TxLevel.Sub](b)(using sub).toVector
    ) ++
      bodies.accountBalanceIntervals[TxLevel.Top](tx.body).toVector ++
      bodies.startingAccountBalanceIntervals(tx.body).toVector)
      .map((a, i) => ok(Convert.accountAddress(a)) -> i)

  private def registeredBy(tx: DijkstraTx[TxLevel.Top]): Set[Credential[Staking]] =
    certsOf(tx).flatten.collect {
      case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(cred, _)) => cred
      case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegDelegCert(cred, _, _)) =>
        cred
    }.toSet

  /** The accounts a level's certificates unregister or delegate, with the deposit an unregistration
    * refunds, that no earlier certificate of the batch registers.
    */
  private def heldBefore(tx: DijkstraTx[TxLevel.Top]): Map[Credential[Staking], Coin] =
    certsOf(tx).flatten
      .foldLeft((Set.empty[Credential[Staking]], Map.empty[Credential[Staking], Coin])) {
        case ((registered, held), DijkstraTxCert.DijkstraTxCertDeleg(cert)) =>
          cert match
            case DijkstraDelegCert.DijkstraRegCert(cred, _)         => (registered + cred, held)
            case DijkstraDelegCert.DijkstraRegDelegCert(cred, _, _) => (registered + cred, held)
            case DijkstraDelegCert.DijkstraUnRegCert(cred, refund) if !registered(cred) =>
              (registered, held.updated(cred, refund))
            case DijkstraDelegCert.DijkstraDelegCert(cred, _) if !registered(cred) =>
              (registered, held.updatedWith(cred)(_.orElse(Some(Coin(2000000)))))
            case _ => (registered, held)
        case (acc, _) => acc
      }
      ._2

  private val c = EraCertState.dijkstra
  private given ConwayEraAccounts[Dijkstra] = c.accountsOf
  private val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]])

  private def compact(coin: Coin): CompactCoin = ok(CompactCoin.fromCoin(coin))

  private def adjust(cred: Credential[Staking], f: CompactCoin => CompactCoin)(
      state: CertState[Dijkstra]
  ): CertState[Dijkstra] =
    accountsL.modify(
      EraAccounts.adjustAccountState[Dijkstra](
        summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL.modify(f),
        cred,
        _
      )
    )(state)

  /** The testnet's genesis constitution, whose guardrails script its two proposals name. */
  private lazy val constitution: flat.Constitution =
    val conway = json("fixtures/leios/testnet/config/conway-genesis.json").hcursor
      .downField("constitution")
    flat.Constitution(
      start.utxo.gov.cgsConstitution.anchor,
      Some(hex(conway.get[String]("script").fold(e => fail(e.message), identity)))
    )

  /** What a chain transaction is seeded with: the certificate state, the UTxO state and the era's
    * proposals.
    */
  private final case class Seed(
      cert: CertState[Dijkstra],
      utxo: UTxOState[Dijkstra],
      proposals: Proposals[Dijkstra]
  ):
    def state: LedgerState[Dijkstra] = LedgerState(cert, utxo)

  /** The least state a chain transaction needs, derived from its body alone: each account a level
    * withdraws from, deposits to (unless the batch registers it), bounds the balance of, or
    * unregisters or delegates before registering it, and each proposal's return account, registered
    * with the deposit an unregistration refunds, or 2 ada, and the balance the batch withdraws
    * (within the bounds given); the testnet's constitution; and for a parameter change whose parent
    * is named, the proposals rooted at that parent, as when it was enacted. The recorded outputs
    * are the UTxO set. The fixture records neither the chain's certificate state nor its proposals
    * (CNF-2).
    */
  private def seed(k: Case): Seed =
    val withdrawn = withdrawalsOf(k.tx).flatten.groupMapReduce(_._1)(_._2.value)(_ + _)
    val bounds = intervalsOf(k.tx)
    val depositTo =
      depositsOf(k.tx).flatMap(_.keys).filterNot(a => registeredBy(k.tx)(a.id.credential))
    val returns = proposalsOf(k.tx).map(p => ok(Convert.accountAddress(p.pProcReturnAddr)))
    val held = heldBefore(k.tx)
    val accounts =
      (withdrawn.keys ++ bounds.map(_._1) ++ depositTo ++ returns).map(_.id.credential).toSet ++
        held.keySet
    def lower(i: AccountBalanceInterval): BigInt = i match
      case AccountBalanceInterval.AccountBalanceLowerBound(Inclusive(l))    => l.value
      case AccountBalanceInterval.AccountBalanceBothBounds(Inclusive(l), _) => l.value
      case AccountBalanceInterval.AccountBalanceExact(e)                    => e.value
      case AccountBalanceInterval.AccountBalanceUpperBound(_)               => 0
    def balanceOf(cred: Credential[Staking]): BigInt =
      (withdrawn.collect { case (a, w) if a.id.credential == cred => w }.toVector ++
        bounds.collect { case (a, i) if a.id.credential == cred => lower(i) }).maxOption
        .getOrElse(BigInt(0))
    val cert = accounts.toVector.foldLeft(start.cert) { (state, cred) =>
      val balance = balanceOf(cred)
      val deposit = held.getOrElse(cred, Coin(2000000))
      val added = accountsL.modify(
        ConwayEraAccounts.registerConwayAccount[Dijkstra](cred, compact(deposit), None, _)
      )(state)
      adjust(cred, _ => compact(Coin(balance)))(added)
    }
    val parents = proposalsOf(k.tx).flatMap(_.pProcGovAction match
      case GovAction.ParameterChange(parent, _, _) => parent
      case _                                       => None)
    Seed(
      cert,
      storedState(k.utxo).copy[Dijkstra](gov = start.utxo.gov.copy(cgsConstitution = constitution)),
      Proposals.empty[Dijkstra](GovRelation[Option](parents.headOption, None, None, None))
    )

  /** The state a chain transaction admitted leaves, derived without LEDGER's transition: every
    * level's inputs spent and outputs created, the instant stake moved with them, the top level's
    * fee, every level's donation, the deposits by the obligations' change and the proposals'
    * deposits; the certificate state of each level in turn, the sub-transactions' then the top
    * level's, its withdrawals taken from the balances, CERTS alone on its certificates and its
    * direct deposits added; and the proposals GOV alone leaves on the top level's signal, as no
    * sub-transaction of the chain proposes or votes.
    */
  private def expected(k: Case, s: Seed): (LedgerState[Dijkstra], Proposals[Dijkstra]) =
    val epoch = ok(EpochNo.fromBigInt(k.slot / epochSize))
    val levels = withdrawalsOf(k.tx).zip(certsOf(k.tx)).zip(depositsOf(k.tx))
    val cert = levels.foldLeft(s.cert) { case (state, ((withdrawals, certs), deposits)) =>
      val drained = withdrawals.foldLeft(state)((st, w) =>
        adjust(w._1.id.credential, _.subtract(compact(w._2)))(st)
      )
      val certified = ok(
        EraCertsRun.dijkstraCerts(
          leios,
          CertsEnv[Dijkstra](k.tx, pp, epoch, None, Map.empty),
          drained,
          certs
        )
      ).fold(fs => fail(s"${k.name}: CERTS refuses $fs"), _._1)
      deposits.foldLeft(certified)((st, d) => adjust(d._1.id.credential, _.add(compact(d._2)))(st))
    }
    val txId = ok(Hash32.fromBytes(bodies.id[TxLevel.Top](k.tx.body)))
    val proposals = ok(
      EraGovRun.dijkstra(
        GovEnv[Dijkstra](
          txId,
          epoch,
          pp,
          ok(constitution.script.traverse(ScriptHash.fromBytes)),
          cert,
          None
        ),
        GovSignal[Dijkstra](
          bodies.votingProcedures[TxLevel.Top](k.tx.body),
          bodies.proposalProcedures[TxLevel.Top](k.tx.body),
          bodies.certs[TxLevel.Top](k.tx.body)
        ),
        Network.Testnet,
        s.proposals
      )
    ).fold(fs => fail(s"${k.name}: GOV refuses $fs"), _._1)
    val inputs = bodies.inputs[TxLevel.Top](k.tx.body) ++
      subBodies(k.tx).flatMap(bodies.inputs[TxLevel.Sub](_)(using sub))
    val created = UTxO[Dijkstra](
      UTxOs.txouts[Dijkstra, TxLevel.Top](k.tx.body).unUTxO ++
        subBodies(k.tx).flatMap(UTxOs.txouts[Dijkstra, TxLevel.Sub](_)(using bodies, sub).unUTxO)
    )
    val consumed = UTxO[Dijkstra](k.utxo.unUTxO.view.filterKeys(inputs).toMap)
    def total(o: Obligations): BigInt =
      o.oblStake.value + o.oblPool.value + o.oblDRep.value + o.oblProposal.value
    val obligations =
      total(c.obligationCertState(cert)) - total(c.obligationCertState(s.cert)) +
        (Proposals.proposalsActionsMap(proposals).values.map(_.gasDeposit.value).sum -
          Proposals.proposalsActionsMap(s.proposals).values.map(_.gasDeposit.value).sum)
    val donation = bodies.treasuryDonation[TxLevel.Top](k.tx.body).value +
      subBodies(k.tx).map(bodies.treasuryDonation[TxLevel.Sub](_)(using sub).value).sum
    val before = s.utxo
    val utxo = before.copy[Dijkstra](
      utxo = UtxoTables(
        Utxo(
          (k.utxo.unUTxO -- inputs ++ created.unUTxO).view
            .mapValues(StoredTxOut.dijkstra.toStored)
            .toMap
        )
      ),
      fees = Coin(before.fees.value + bodies.fee(k.tx.body).value),
      deposited = Coin(before.deposited.value + obligations),
      donation = Coin(before.donation.value + donation),
      instantStake = ok(
        EraStake.dijkstra
          .addInstantStake(created, before.instantStake)
          .flatMap(EraStake.dijkstra.deleteInstantStake(consumed, _))
      )
    )
    (LedgerState(cert, utxo), proposals)

  /** What LEDGER's sub-rules give a chain transaction run one by one without LEDGER's transition,
    * each one level down as LEDGER nests it, in its order (`Dijkstra/Rules/Ledger.hs:404-487`,
    * cardano-ledger 9df9f98c): SUBLEDGERS from the state before, ENTITIES and GOV, for a
    * transaction flagged valid, and UTXOW, each given the state the one before left, or the one
    * before it after a refusal, when the rest must admit, as no part's check here waits on a
    * failure before it. The failures as LEDGER at the root reports its blocks, last first
    * (`Judgment.flatten`), each wrapped as LEDGER embeds it; the events in the order told, wrapped
    * likewise.
    */
  private final case class Parts(
      failures: Vector[DijkstraLedgerPredFailure[Dijkstra]],
      events: Vector[LedgerEvent[Dijkstra]]
  )

  private def parts(
      k: Case,
      state: LedgerState[Dijkstra],
      proposals: Proposals[Dijkstra]
  ): Parts =
    val stAnnTx = annotate(k)
    val epoch = epochOf(k)
    val original = accountsOf(state.cert)
    val sub = DijkstraSubLedgerRun.subLedgers(
      leios,
      SubLedgerEnv[Dijkstra](
        k.slot,
        Some(epoch),
        pp,
        ChainAccountState(Coin(0), Coin(0)),
        k.utxo,
        original,
        txs.isValid(k.tx)
      ),
      EraUTxO.dijkstra.subTransactionsStAnnTx(stAnnTx),
      context(TxEnv.Validation.All),
      state,
      proposals,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      depth = 1
    ) match
      case Right(judged) => judged
      case Left(defect)  => fail(s"${k.name}: $defect")
    val (afterSub, proposalsAfterSub) = sub.fold(_ => (state, proposals), (st, ps, _) => (st, ps))
    val committee = afterSub.utxo.gov.cgsCommittee
    val valid = txs.isValid(k.tx) == IsPhase2Valid.Phase2Valid
    val entities = Option.when(valid)(
      ok(
        EraCertsRun.entities(
          leios,
          EntitiesEnv[Dijkstra](
            epoch,
            pp,
            committee,
            DijkstraSubLedger.committeeProposals(proposalsAfterSub),
            original
          ),
          afterSub.cert,
          stAnnTx,
          depth = 1
        )
      )
    )
    // after SUBLEDGERS refused, ENTITIES starts failing: its checks guarded by `whenFailureFree`
    // do not run (`Extended.hs:725-727`, `libs/small-steps`, cardano-ledger 9df9f98c)
    val entitiesFailing = Option.when(valid && sub.isLeft)(
      ok(
        EraCertsRun.entitiesAfterFailure(
          leios,
          EntitiesEnv[Dijkstra](
            epoch,
            pp,
            committee,
            DijkstraSubLedger.committeeProposals(proposalsAfterSub),
            original
          ),
          afterSub.cert,
          stAnnTx,
          EntitiesPredFailure.WithdrawalAccountsMissing(Map.empty),
          depth = 1
        )
      )
    )
    val certAfterEntities = entities.flatMap(_.toOption).fold(afterSub.cert)(_._1)
    val gov = Option.when(valid)(
      ok(
        EraGovRun.dijkstra(
          GovEnv[Dijkstra](
            ok(Hash32.fromBytes(bodies.id[TxLevel.Top](k.tx.body))),
            epoch,
            pp,
            ok(afterSub.utxo.gov.cgsConstitution.script.traverse(ScriptHash.fromBytes)),
            certAfterEntities,
            committee
          ),
          GovSignal[Dijkstra](
            bodies.votingProcedures[TxLevel.Top](k.tx.body),
            bodies.proposalProcedures[TxLevel.Top](k.tx.body),
            bodies.certs[TxLevel.Top](k.tx.body)
          ),
          Network.Testnet,
          proposalsAfterSub,
          depth = 1
        )
      )
    )
    val utxow = ok(
      DijkstraUtxoRun.run(
        DijkstraUtxoEnv[Dijkstra](
          k.slot,
          pp,
          c.certPStateL.get(afterSub.cert),
          state.cert,
          k.utxo
        ),
        stAnnTx,
        context(TxEnv.Validation.All),
        afterSub.utxo,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder,
        depth = 1
      )
    )
    def failed[A, B](judged: Option[Either[cats.data.NonEmptyChain[A], B]]): Vector[A] =
      judged.flatMap(_.left.toOption).fold(Vector.empty[A])(_.toChain.toVector)
    def told[A, B, Ev](judged: Option[Either[A, (B, cats.data.Chain[Ev])]]): Vector[Ev] =
      judged.flatMap(_.toOption).fold(Vector.empty[Ev])(_._2.toVector)
    val blocks = Vector(
      failed(Some(sub)).map(
        DijkstraLedgerPredFailure.DijkstraSubLedgersFailure[Dijkstra](_)
      ),
      entitiesFailing
        .getOrElse(failed(entities))
        .map(DijkstraLedgerPredFailure.DijkstraEntitiesFailure[Dijkstra](_)),
      failed(gov).map(DijkstraLedgerPredFailure.DijkstraGovFailure[Dijkstra](_)),
      failed(Some(utxow)).map(DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](_))
    )
    // a part refused, those after it admit: but for ENTITIES after SUBLEDGERS, none of them is
    // judged here with a failure before it
    assert(blocks.dropWhile(_.isEmpty).drop(1).forall(_.isEmpty), s"${k.name}: $blocks")
    Parts(
      blocks.reverse.flatten,
      sub
        .fold(_ => Vector.empty, _._3.toVector)
        .map(DijkstraLedgerEvent.SubLedgersEvent[Dijkstra](_)) ++
        told(entities).map(DijkstraLedgerEvent.EntitiesEvent[Dijkstra](_)) ++
        told(gov).map(DijkstraLedgerEvent.GovEvent[Dijkstra](_)) ++
        told(Some(utxow)).map(DijkstraLedgerEvent.UtxowEvent[Dijkstra](_))
    )

  // ---- the testnet's chain -----------------------------------------------------------------------

  test("the chain's 200 transactions: 28 batches, every one flagged valid") {
    assertEquals(chain.size, 200)
    assertEquals(chain.count(k => subBodies(k.tx).nonEmpty), 28)
    assertEquals(chain.map(k => txs.isValid(k.tx)).distinct, Vector(IsPhase2Valid.Phase2Valid))
  }

  private val txs = EraTx.dijkstra

  /** Whether a chain transaction's seed holds anything the empty state does not. */
  private def needsSeed(k: Case): Boolean =
    val s = seed(k)
    s.cert != start.cert || s.proposals != noProposals

  test("from the empty state LEDGER refuses exactly the transactions whose seed holds something") {
    // each with the failures its sub-rules give it run one by one
    val refused = chain.flatMap { k =>
      val from = LedgerState(start.cert, storedState(k.utxo))
      judge(k, from) match
        case Right(Right(_)) => None
        case Right(Left(fs)) =>
          assertEquals(fs.toChain.toVector, parts(k, from, noProposals).failures, k.name)
          Some(k)
        case Left(defect) => fail(s"${k.name}: $defect")
    }
    assertEquals(refused.size, 26)
    assertEquals(refused.map(_.name), chain.filter(needsSeed).map(_.name))
  }

  test("LEDGER admits every chain transaction from its seed, the state as its parts derive") {
    val wrong = chain.flatMap { k =>
      val s = seed(k)
      judge(k, s.state, s.proposals) match
        case Right(Right((after, proposals, events))) =>
          val (state, expectedProposals) = expected(k, s)
          assertEquals(after.cert, state.cert, k.name)
          assertEquals(after.utxo.utxo.utxo.toMap, state.utxo.utxo.utxo.toMap, k.name)
          assertEquals(after.utxo.instantStake, state.utxo.instantStake, k.name)
          assertEquals(
            (after.utxo.fees, after.utxo.deposited, after.utxo.donation, after.utxo.gov),
            (state.utxo.fees, state.utxo.deposited, state.utxo.donation, state.utxo.gov),
            k.name
          )
          // the whole state, the UTxO tables compared by the set they hold (above), as they also
          // keep the changes that made it
          assertEquals(
            LedgerState(after.cert, after.utxo.copy[Dijkstra](utxo = state.utxo.utxo)),
            state,
            k.name
          )
          assertEquals(proposals, expectedProposals, k.name)
          // the events in the order LEDGER runs its sub-rules: SUBLEDGERS', ENTITIES', GOV's two
          // (the proposals after), UTXOW's, the whole vector as the parts tell it
          val judgedParts = parts(k, s.state, s.proposals)
          assertEquals(judgedParts.failures, Vector.empty, k.name)
          assertEquals(events.toVector, judgedParts.events, k.name)
          val txId = ok(Hash32.fromBytes(bodies.id[TxLevel.Top](k.tx.body)))
          assertEquals(
            events.toVector.collect { case DijkstraLedgerEvent.GovEvent(g) => g }.headOption,
            Some(ConwayGovEvent.GovNewProposals[Dijkstra](txId, proposals)),
            k.name
          )
          assertEquals(
            events.exists {
              case DijkstraLedgerEvent.SubLedgersEvent(_) => true
              case _                                      => false
            },
            subBodies(k.tx).nonEmpty,
            k.name
          )
          None
        case other => Some(s"${k.name}: $other")
    }
    assertEquals(wrong, Vector.empty[String])
  }

  // ---- which state each sub-rule is given --------------------------------------------------------

  private def epochOf(k: Case): EpochNo = ok(EpochNo.fromBigInt(k.slot / epochSize))

  private def accountsOf(state: CertState[Dijkstra]): Accounts[Dijkstra] = accountsL.get(state)

  test("UTXOW admits the chain's unregistering transactions with either certificate state") {
    // a component check of what no recording tells apart (CNF-2): the chain's two transactions that
    // unregister accounts at the top level, judged by UTXOW with the certificate state before the
    // transaction, as LEDGER gives it (`Dijkstra/Rules/Ledger.hs:482`, cardano-ledger 9df9f98c), and
    // with the one ENTITIES leaves, without the accounts: each refund is the one its certificate
    // declares, so both admit them
    val unregistering = chain.filter(k =>
      subBodies(k.tx).isEmpty && bodies.certs[TxLevel.Top](k.tx.body).exists {
        case DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraUnRegCert(_, _)) => true
        case _                                                                             => false
      }
    )
    assertEquals(unregistering.size, 2)
    unregistering.foreach { k =>
      val s = seed(k)
      val afterEntities = ok(
        EraCertsRun.entities(
          leios,
          EntitiesEnv[Dijkstra](epochOf(k), pp, None, Map.empty, accountsOf(s.cert)),
          s.cert,
          annotate(k)
        )
      ).fold(fs => fail(s"${k.name}: $fs"), _._1)
      assertNotEquals(accountsOf(afterEntities), accountsOf(s.cert), k.name)
      Vector(s.cert, afterEntities).foreach { original =>
        val judged = DijkstraUtxoRun.run(
          DijkstraUtxoEnv[Dijkstra](k.slot, pp, c.certPStateL.get(s.cert), original, k.utxo),
          annotate(k),
          context(TxEnv.Validation.All),
          s.utxo,
          ScarabEvaluator.default,
          Verdicts.Direct,
          ScarabEvaluator.defaultDecoder
        )
        assert(judged.exists(_.isRight), s"${k.name}: $judged")
      }
    }
  }

  test("ENTITIES is given the accounts before the batch: the sub-transactions' withdrawals count") {
    // the batches whose sub-transactions withdraw: SUBLEDGERS takes the withdrawals from the
    // balances, so against the accounts it leaves the batch withdraws more than an account held;
    // LEDGER admits them (above) with the accounts before the transaction
    // (`Dijkstra/Rules/Ledger.hs:442`, cardano-ledger 9df9f98c)
    val withdrawing = chain.filter(k =>
      subBodies(k.tx).exists(b =>
        bodies.withdrawals[TxLevel.Sub](b)(using sub).unWithdrawals.values.exists(_.value > 0)
      )
    )
    assert(withdrawing.nonEmpty)
    withdrawing.foreach { k =>
      val s = seed(k)
      val stAnnTx = annotate(k)
      val afterSubLedgers = DijkstraSubLedgerRun.subLedgers(
        leios,
        SubLedgerEnv[Dijkstra](
          k.slot,
          Some(epochOf(k)),
          pp,
          ChainAccountState(Coin(0), Coin(0)),
          k.utxo,
          accountsOf(s.cert),
          IsPhase2Valid.Phase2Valid
        ),
        EraUTxO.dijkstra.subTransactionsStAnnTx(stAnnTx),
        context(TxEnv.Validation.All),
        s.state,
        s.proposals,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      ) match
        case Right(Right((after, _, _))) => after.cert
        case other                       => fail(s"${k.name}: $other")
      def entities(original: Accounts[Dijkstra]) =
        ok(
          EraCertsRun.entities(
            leios,
            EntitiesEnv[Dijkstra](epochOf(k), pp, None, Map.empty, original),
            afterSubLedgers,
            stAnnTx
          )
        )
      assert(entities(accountsOf(s.cert)).isRight, k.name)
      entities(accountsOf(afterSubLedgers)) match
        case Left(fs) =>
          assert(
            fs.forall {
              case EntitiesPredFailure.WithdrawalAmountsExceedingOriginalBalance(_) => true
              case _                                                                => false
            },
            s"${k.name}: $fs"
          )
        case other => fail(s"${k.name}: $other")
    }
  }

  // ---- crafted transactions ----------------------------------------------------------------------

  private def leaf(v: xyz.kd5ujc.scarab.codec.Value): xyz.kd5ujc.scarab.codec.Node =
    xyz.kd5ujc.scarab.codec.Node(v, xyz.kd5ujc.scarab.codec.Bytes.empty)

  /** `k` 's transaction encoded again with its body's fields changed by `fields` and its flag
    * `valid`, the rest as it was read: a block's transaction, whose flag is its last item
    * (`block_transaction`, `eras/dijkstra/impl/cddl/data/dijkstra.cddl:8-9`, cardano-ledger
    * 9df9f98c), outside the body its signatures are over.
    */
  private def crafted(
      k: Case,
      valid: Boolean,
      fields: Vector[(xyz.kd5ujc.scarab.codec.Node, xyz.kd5ujc.scarab.codec.Node)] => Vector[
        (xyz.kd5ujc.scarab.codec.Node, xyz.kd5ujc.scarab.codec.Node)
      ] = identity
  ): Case =
    import xyz.kd5ujc.scarab.codec.{Cbor, Value}
    val bytes = chainContextCases
      .find(j => str(j, "name") == k.name)
      .map(j => str(j, "bytes"))
      .getOrElse(fail(k.name))
    node(hex(bytes)).value match
      case Value.Arr(Vector(txBody, wits, aux, _)) =>
        val body = txBody.value match
          case Value.Map(fs) => if fields eq identity then txBody else leaf(Value.Map(fields(fs)))
          case other         => fail(s"not a body: $other")
        val raw = Cbor
          .encode(Value.Arr(Vector(body, wits, aux, leaf(Value.Bool(valid)))))
          .fold(e => fail(s"$e"), identity)
        k.copy(tx = ok(DijkstraTx.inBlock(node(raw))))
      case other => fail(s"not a transaction: $other")

  private def failuresOf(
      judged: DijkstraLedgerRun.Judged[LedgerPredFailure[Dijkstra], LedgerEvent[Dijkstra]]
  ): Vector[DijkstraLedgerPredFailure[Dijkstra]] = judged match
    case Right(Left(fs)) => fs.toChain.toVector
    case Right(Right(_)) => Vector.empty
    case Left(defect)    => fail(s"$defect")

  private def isOf(f: DijkstraLedgerPredFailure[Dijkstra]): Int = f match
    case DijkstraLedgerPredFailure.DijkstraUtxowFailure(_)           => 1
    case DijkstraLedgerPredFailure.DijkstraEntitiesFailure(_)        => 2
    case DijkstraLedgerPredFailure.DijkstraGovFailure(_)             => 3
    case DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch(_)  => 4
    case DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig(_) => 5
    case DijkstraLedgerPredFailure.DijkstraSubLedgersFailure(_)      => 6

  test("the batch's reference scripts are checked whatever the flag, before ENTITIES") {
    // a chain transaction with reference scripts and withdrawals, from the empty state, which
    // ENTITIES alone refuses, by its check of the current accounts that runs only while nothing
    // has failed (`whenFailureFree`, `Entities.hs:286`); under a limit below its scripts' size,
    // flagged valid, the size is the one failure: recorded before ENTITIES, it is in the failing
    // flag ENTITIES inherits (`Extended.hs:725-727`, cardano-ledger 9df9f98c); flagged invalid
    // (the flag is outside the body, so its signatures hold), the size and UTXOW's
    val k = chain
      .find(k =>
        subBodies(k.tx).isEmpty && bodies
          .withdrawals[TxLevel.Top](k.tx.body)
          .unWithdrawals
          .nonEmpty &&
          DijkstraUTxO.batchNonDistinctRefScriptsSize(k.utxo, k.tx) > 0
      )
      .getOrElse(fail("no such transaction"))
    val size = DijkstraUTxO.batchNonDistinctRefScriptsSize(k.utxo, k.tx)
    val small = PParams
      .lensHKD[Dijkstra]
      .andThen(DijkstraEraPParams.dijkstra.hkdMaxRefScriptSizePerTxL[Identity])
      .replace(
        flat.Unsigned
          .from[flat.Unsigned.Width.W32](BigInt(size - 1))
          .getOrElse(fail(s"$size"))
      )(pp)
    def judged(c: Case, params: PParams[Dijkstra]) =
      failuresOf(
        DijkstraLedgerRun.ledger(
          leios,
          envOf(c, Coin(0)).copy(ledgerPp = params),
          DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, params, c.utxo, Map.empty, c.tx),
          context(TxEnv.Validation.All),
          LedgerState(start.cert, storedState(c.utxo)),
          noProposals,
          ScarabEvaluator.default,
          Verdicts.Direct,
          ScarabEvaluator.defaultDecoder
        )
      )
    val tooBig =
      DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
        Mismatch(size, size - 1)
      )
    val entities = judged(k, pp)
    assert(entities.nonEmpty && entities.forall(isOf(_) == 2), s"$entities")
    assertEquals(judged(k, small), Vector(tooBig))
    val invalid = judged(crafted(k, valid = false), small)
    assert(invalid.exists(isOf(_) == 1), s"$invalid")
    assertEquals(invalid.filterNot(isOf(_) == 1), Vector(tooBig))
  }

  test("the declared treasury is checked whatever the flag, against the chain's") {
    // a chain transaction without sub-transactions given a declared treasury (body field 21),
    // judged under the policy that skips the signal's checks, as its signatures are over the
    // body as recorded
    import xyz.kd5ujc.scarab.codec.Value
    val k = chain
      .find(k => subBodies(k.tx).isEmpty && !needsSeed(k))
      .getOrElse(fail("no such transaction"))
    val treasury = leaf(Value.UInt(21)) -> leaf(Value.UInt(5000000))
    def judged(c: Case, chainTreasury: Coin) =
      failuresOf(
        judge(
          c,
          LedgerState(start.cert, storedState(c.utxo)),
          treasury = chainTreasury,
          validation = TxEnv.Validation.NonStatic
        )
      )
    val mismatch =
      DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch[Dijkstra](
        Mismatch(Coin(5000000), Coin(7000000))
      )
    val valid = crafted(k, valid = true, _ :+ treasury)
    assertEquals(bodies.currentTreasuryValue[TxLevel.Top](valid.tx.body), Some(Coin(5000000)))
    assertEquals(judged(valid, Coin(7000000)), Vector(mismatch))
    assertEquals(judged(valid, Coin(5000000)), Vector.empty)
    val invalid = judged(crafted(k, valid = false, _ :+ treasury), Coin(7000000))
    assertEquals(invalid.filterNot(isOf(_) == 1), Vector(mismatch))
  }

  test(
    "GOV is given the certificate state after ENTITIES: a return account the transaction registers"
  ) {
    // the chain's first proposal, from a state without its return account: GOV refuses the
    // proposal's return account; encoded again with a registration of that account (body field
    // 4), judged under the policy that skips the signal's checks, as its signatures are over the
    // body as recorded, GOV finds the account ENTITIES registered (`Dijkstra/Rules/Ledger.hs:461`,
    // cardano-ledger 9df9f98c); the other failures, UTXOW's of the changed body among them, are not
    // this test's
    import xyz.kd5ujc.scarab.codec.Value
    val k = chain
      .find(k => bodies.proposalProcedures[TxLevel.Top](k.tx.body).size > 0)
      .getOrElse(fail("no proposal"))
    assert(bodies.certs[TxLevel.Top](k.tx.body).isEmpty)
    val returnKey = proposalsOf(k.tx).head.pProcReturnAddr.credential match
      case flat.Credential.Key(h) => h
      case other                  => fail(s"not a key: $other")
    val s = seed(k)
    val without = accountsL.modify(a =>
      ConwayAccounts(
        a.states.removed(ok(Convert.credential[Staking](flat.Credential.Key(returnKey))))
      )
    )(s.cert)
    def returnRefused(c: Case) =
      failuresOf(
        judge(
          c,
          LedgerState(without, s.utxo),
          s.proposals,
          validation = TxEnv.Validation.NonStatic
        )
      ).exists {
        case DijkstraLedgerPredFailure.DijkstraGovFailure(
              DijkstraGovPredFailure.ProposalReturnAccountDoesNotExist(_)
            ) =>
          true
        case _ => false
      }
    val registration = leaf(Value.UInt(4)) -> leaf(
      Value.Arr(
        Vector(
          leaf(
            Value.Arr(
              Vector(
                leaf(Value.UInt(7)),
                leaf(Value.Arr(Vector(leaf(Value.UInt(0)), leaf(Value.ByteString(returnKey))))),
                leaf(Value.UInt(2000000))
              )
            )
          )
        )
      )
    )
    val registering = crafted(k, valid = true, _ :+ registration)
    assertEquals(bodies.certs[TxLevel.Top](registering.tx.body).size, 1)
    assert(returnRefused(k))
    assert(!returnRefused(registering))
  }

  // ---- the reference's own cases: `Dijkstra/Imp/{Ledger,Mempool,Utxo}Spec.hs` --------------------

  private val B = DijkstraImpBatch

  private lazy val payer = TestLedger.key()

  /** A built batch, judged at the slot of the chain's first transaction. */
  private def built(
      name: String,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra],
      index: Int = 0
  ): Case =
    Case(name, chain.head.slot, index, tx, utxo)

  private val fee: BigInt = 1000000

  /** A top level spending `ins`, paying `fee` and the rest to the payer, with `fields` and the
    * sub-transactions `subs`, signed by the payer and `signers`.
    */
  private def batch(
      ins: Vector[B.In],
      change: BigInt,
      subs: Vector[xyz.kd5ujc.scarab.codec.Node],
      signers: Vector[TestLedger.Key] = Vector.empty,
      fields: Vector[(Int, xyz.kd5ujc.scarab.codec.Node)] = Vector.empty,
      valid: Boolean = true
  ): DijkstraTx[TxLevel.Top] =
    val body = B.body(
      (Vector(
        0 -> B.set(ins.map(_.node)*),
        1 -> B.arr(B.out(B.keyAddress(payer), change)),
        2 -> B.uint(fee)
      ) ++ Option.when(subs.nonEmpty)(23 -> B.set(subs*)) ++ fields)*
    )
    B.topTx(body, B.witnesses(body, payer +: signers), valid)

  /** A sub-transaction spending `in` alone, with no outputs but those `fields` give, and `fields`,
    * signed by the payer and `signers`.
    */
  private def spending(
      in: B.In,
      fields: Vector[(Int, xyz.kd5ujc.scarab.codec.Node)],
      signers: Vector[TestLedger.Key] = Vector.empty
  ): xyz.kd5ujc.scarab.codec.Node =
    val outputs = Option.unless(fields.exists(_._1 == 1))(1 -> B.arr())
    B.subTx(B.body(((0 -> B.set(in.node)) +: (outputs.toVector ++ fields))*), payer +: signers)

  private def paid(coin: BigInt): xyz.kd5ujc.scarab.codec.Node = B.out(B.keyAddress(payer), coin)

  private def credOf(key: TestLedger.Key): Credential[Staking] =
    ok(Convert.credential[Staking](flat.Credential.Key(key.hash)))

  /** The certificate state with an account of each key registered with the key deposit, holding the
    * balance given (`registerAccountAddress`, `fundAccountBalance`).
    */
  private def registering(accounts: (TestLedger.Key, BigInt)*): CertState[Dijkstra] =
    accounts.foldLeft(start.cert) { case (state, (key, balance)) =>
      val added = accountsL.modify(
        ConwayEraAccounts.registerConwayAccount[Dijkstra](
          credOf(key),
          compact(Coin(stored.keyDeposit)),
          None,
          _
        )
      )(state)
      adjust(credOf(key), _ => compact(Coin(balance)))(added)
    }

  /** LEDGER at the root on a built batch from `cert` and its UTxO, under `params`. */
  private def ledgerOf(
      k: Case,
      cert: CertState[Dijkstra] = start.cert,
      params: PParams[Dijkstra] = pp,
      treasury: Coin = Coin(0)
  ): Vector[DijkstraLedgerPredFailure[Dijkstra]] =
    failuresOf(
      DijkstraLedgerRun.ledger(
        leios,
        envOf(k, treasury).copy(ledgerPp = params),
        DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, params, k.utxo, Map.empty, k.tx),
        context(TxEnv.Validation.All),
        LedgerState(cert, storedState(k.utxo)),
        noProposals,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      )
    )

  private def maxRefScriptSize(n: Long): PParams[Dijkstra] =
    PParams
      .lensHKD[Dijkstra]
      .andThen(DijkstraEraPParams.dijkstra.hkdMaxRefScriptSizePerTxL[Identity])
      .replace(flat.Unsigned.from[flat.Unsigned.Width.W32](BigInt(n)).getOrElse(fail(s"$n")))(pp)

  /** `refScriptInput` (`Dijkstra/Imp/LedgerSpec.hs:257-262`, cardano-ledger 9df9f98c): an output
    * holding `purposeIsWellformedNoDatum SPlutusV3` as its reference script, and the script's size,
    * `originalBytesSize`, the length of its binary (`Alonzo/Scripts.hs:553-555`,
    * `Plutus/Language.hs:261`).
    */
  private val refIn = B.fresh(0x50)
  private lazy val refOut =
    B.refScriptOut(B.keyAddress(payer), 10000000, B.purposeIsWellformedNoDatumV3)
  private val refSize: Long = 629

  test(
    "the reference's example scripts: the hashes it lists, and the size of the reference script"
  ) {
    assertEquals(
      B.scriptHash(3, B.purposeIsWellformedNoDatumV3),
      B.purposeIsWellformedNoDatumV3Hash
    )
    assertEquals(B.scriptHash(3, B.alwaysFailsWithDatumV3), B.alwaysFailsWithDatumV3Hash)
    assertEquals(B.purposeIsWellformedNoDatumV3.value.size.toLong, refSize)
  }

  test("a sub-transaction's reference script counts towards the batch's size (LedgerSpec.hs:122)") {
    // `Dijkstra/Imp/LedgerSpec.hs:122-136`, cardano-ledger 9df9f98c: the limit one byte below the
    // script, a sub-transaction that only references it (the fixup gives it an input of its own,
    // `addSubTxIn`, `ImpTest.hs:483-490`), the one failure; at the limit (`:167-172`), admitted
    val subIn = B.fresh(0x51)
    val topIn = B.fresh(0x52)
    val k = built(
      "LedgerSpec.hs:122",
      batch(
        Vector(topIn),
        11000000 - fee,
        Vector(spending(subIn, Vector(18 -> B.set(refIn.node))))
      ),
      B.utxo(refIn -> refOut, subIn -> paid(1000000), topIn -> paid(10000000))
    )
    assertEquals(
      ledgerOf(k, params = maxRefScriptSize(refSize - 1)),
      Vector(
        DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
          Mismatch(refSize, refSize - 1)
        )
      )
    )
    assertEquals(ledgerOf(k, params = maxRefScriptSize(refSize)), Vector.empty)
  }

  test(
    "a batch applied without its predicates reads none of its or its sub-transactions' members"
  ) {
    // a closure's transaction (`ValidateNone`, `DijkstraClosure.applyUnchecked`): no predicate's
    // condition is evaluated (`Extended.hs:715-720`, cardano-ledger 9df9f98c), so neither the top
    // level's members nor a sub-transaction's are read, and the state after is the one the made
    // annotation leaves, its sub-transaction's input spent
    val subIn = B.fresh(0x5a)
    val topIn = B.fresh(0x5b)
    val k = built(
      "unchecked batch",
      batch(
        Vector(topIn),
        11000000 - fee,
        Vector(spending(subIn, Vector(18 -> B.set(refIn.node))))
      ),
      B.utxo(refIn -> refOut, subIn -> paid(1000000), topIn -> paid(10000000))
    )
    val made = DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, k.utxo, Map.empty, k.tx)
    assertEquals(made.dsattSubTransactions.value.size, 1)
    def run(stAnnTx: DijkstraStAnnTx.TopTx) =
      DijkstraBbodyRun.unchecked(
        leios,
        envOf(k, Coin(0)),
        stAnnTx,
        context(TxEnv.Validation.All),
        LedgerState(start.cert, storedState(k.utxo)),
        noProposals,
        ScarabEvaluator.default,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      )
    val expected = run(made)
    val after = expected match
      case Right(Right((bbody, _, _))) => bbody.ls.utxo.utxo.utxo
      case other                       => fail(s"$other")
    assert(after.get(xyz.kd5ujc.scarab.ledger.TxIn(subIn.txId, BigInt(subIn.ix))).isEmpty)
    assertEquals(run(DijkstraBbodyRun.unreadMembers(made, fail(_))), expected)
  }

  test("a reference script two sub-transactions share counts once for each (LedgerSpec.hs:138)") {
    // `Dijkstra/Imp/LedgerSpec.hs:138-159`, cardano-ledger 9df9f98c: under a limit of the script's
    // size, two sub-transactions spending an input each and referencing it total twice the size
    val (in1, in2, topIn) = (B.fresh(0x53), B.fresh(0x54), B.fresh(0x55))
    val subs = Vector(in1, in2).map(in => spending(in, Vector(18 -> B.set(refIn.node))))
    val k = built(
      "LedgerSpec.hs:138",
      batch(Vector(topIn), 30000000 - fee, subs),
      B.utxo(
        refIn -> refOut,
        in1 -> paid(10000000),
        in2 -> paid(10000000),
        topIn -> paid(10000000)
      )
    )
    assertEquals(
      ledgerOf(k, params = maxRefScriptSize(refSize)),
      Vector(
        DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
          Mismatch(2 * refSize, refSize)
        )
      )
    )
  }

  test("the reference scripts' size is reported before the treasury (LedgerSpec.hs:195)") {
    // `Dijkstra/Imp/LedgerSpec.hs:195-218`, cardano-ledger 9df9f98c: a top level declaring a
    // treasury one lovelace above the chain's and referencing the script, under a limit one byte
    // below it: the two failures, the size first, though LEDGER checks the treasury first
    // (`Dijkstra/Rules/Ledger.hs:424-425`): the root reports its failures last first
    val topIn = B.fresh(0x56)
    val k = built(
      "LedgerSpec.hs:195",
      batch(
        Vector(topIn),
        10000000 - fee,
        Vector.empty,
        fields = Vector(18 -> B.set(refIn.node), 21 -> B.uint(5000001))
      ),
      B.utxo(refIn -> refOut, topIn -> paid(10000000))
    )
    assertEquals(
      ledgerOf(k, params = maxRefScriptSize(refSize - 1), treasury = Coin(5000000)),
      Vector(
        DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
          Mismatch(refSize, refSize - 1)
        ),
        DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch[Dijkstra](
          Mismatch(Coin(5000001), Coin(5000000))
        )
      )
    )
  }

  test("a batch flagged invalid runs SUBLEDGERS, but neither SUBENTITIES nor LEDGER's ENTITIES") {
    // a sub-transaction naming the main network in its body, which SUBUTXO refuses whatever the
    // flag (`SubWrongNetworkInTxBody`, as `Dijkstra/Imp/MempoolSpec.hs:91-104`, cardano-ledger
    // 9df9f98c, expects it), and withdrawing from an account no state holds, as the top level does,
    // which SUBENTITIES and ENTITIES refuse: flagged valid, all of them; flagged invalid, SUBLEDGERS
    // still runs its sub-transaction's SUBUTXOW (`Dijkstra/Rules/SubLedger.hs`) and the network is
    // refused, SUBENTITIES and ENTITIES skipped
    val (stranger, other) = (TestLedger.key(), TestLedger.key())
    val (subIn, topIn) = (B.fresh(0x57), B.fresh(0x58))
    def withdrawing(key: TestLedger.Key) =
      5 -> B.cmap(B.bytes(B.rewardAccount(key)) -> B.uint(0))
    def k(valid: Boolean) = built(
      "invalid batch",
      batch(
        Vector(topIn),
        11000000 - fee,
        Vector(spending(subIn, Vector(withdrawing(stranger), 15 -> B.uint(1)), Vector(stranger))),
        Vector(other),
        Vector(withdrawing(other)),
        valid
      ),
      B.utxo(subIn -> paid(1000000), topIn -> paid(10000000))
    )
    val network = DijkstraLedgerPredFailure.DijkstraSubLedgersFailure[Dijkstra](
      DijkstraSubLedgersPredFailure.SubLedgerFailure(
        DijkstraSubLedgerPredFailure.SubUtxowFailure(
          DijkstraSubUtxowPredFailure.SubUtxoFailure(
            DijkstraSubUtxoPredFailure.SubWrongNetworkInTxBody(
              Mismatch(Network.Mainnet, Network.Testnet)
            )
          )
        )
      )
    )
    val valid = ledgerOf(k(true))
    assert(valid.contains(network), s"$valid")
    assert(valid.exists(isOf(_) == 2), s"$valid")
    assert(
      valid.exists {
        case DijkstraLedgerPredFailure.DijkstraSubLedgersFailure(
              DijkstraSubLedgersPredFailure.SubLedgerFailure(
                DijkstraSubLedgerPredFailure.SubEntitiesFailure(_)
              )
            ) =>
          true
        case _ => false
      },
      s"$valid"
    )
    // and UTXOS, which evaluates the scripts of a transaction flagged invalid only while nothing
    // has failed (`whenFailureFree`, `Babbage/Rules/Utxos.hs:209`, cardano-ledger 9df9f98c),
    // reports no flag mismatch
    assertEquals(ledgerOf(k(false)), Vector(network))
  }

  test(
    "a fresh pool a sub-transaction registers is the top level's to re-register (UtxoSpec.hs:525)"
  ) {
    // `Dijkstra/Imp/UtxoSpec.hs:525-552`, cardano-ledger 9df9f98c, at `m` = 1 ada
    // (`genBatchOnlyBalancedAmounts`, `:699-727`, its lower bound) made fully balanced
    // (`genFullyBalancedAmounts`, `:729-745`): the sub-transaction spends 5m, pays 2m and deposits 3m
    // to an account; the top level spends 8m and the pool's deposit, withdraws 5m, pays 10m and a
    // fee of 3m; in legacy mode, as a Plutus V3 script (`alwaysFailsWithDatum`) locks a further
    // input of 4m, which the fee takes too (`mkTopTxLegacyModePhase2Invalid`, `:413-419`). The
    // sub-transaction registers a fresh pool, and the top level re-registers it: with the pool state
    // after SUBLEDGERS, which UTXOW is given (`Dijkstra/Rules/Ledger.hs:481`), the top level owes
    // no deposit and is unbalanced by the pool's, the batch balanced; the one failure is the legacy
    // mode's (`batchBalances True`, `:804-818`: consumed 8m + deposit + 5m + 4m, produced
    // 10m + 3m + 4m), and the script, which would fail, is not run. The batch balances with the
    // certificate state before the transaction, which UTXOW's batch-wide check reads
    // (`Dijkstra/Rules/Utxo.hs:330, 370-376`, cardano-ledger 9df9f98c): with the one after
    // SUBLEDGERS the fresh pool's deposit
    // would go uncounted and `ValueNotConservedUTxO` follow
    val m = BigInt(1000000)
    val deposit = stored.poolDeposit
    val (poolKey, poolReward, depositTo) = (TestLedger.key(), TestLedger.key(), TestLedger.key())
    val (topIn, scriptIn, subIn, collateral) =
      (B.fresh(0x61), B.fresh(0x62), B.fresh(0x63), B.fresh(0x64))
    val script = B.alwaysFailsWithDatumV3
    // the script's spending datum and redeemer, 0 both (`PlutusArgs (P.I 0) (Just $ P.I 0)`,
    // `Alonzo/ImpTest.hs:406`, cardano-ledger 9df9f98c): the output `produceScriptAt` makes
    // (`UtxoSpec.hs:657-663`) holds the datum's hash (`fixupOutputDatums`,
    // `Alonzo/ImpTest.hs:338-349`) and the spending transaction's witnesses the datum
    // (`fixupDatums`, `:284-306`)
    val datum = B.uint(0)
    val pool = B.arr(
      B.uint(3),
      B.bytes(poolKey.hash),
      B.bytes(xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(32)(9.toByte))),
      B.uint(0),
      B.uint(stored.minPoolCost),
      B.tagged(30, B.arr(B.uint(0), B.uint(1))),
      B.bytes(B.rewardAccount(poolReward)),
      B.arr(),
      B.arr(),
      B.nil
    )
    val sub = spending(
      subIn,
      Vector(
        1 -> B.arr(paid(2 * m)),
        4 -> B.set(pool),
        25 -> B.cmap(B.bytes(B.rewardAccount(depositTo)) -> B.uint(3 * m))
      ),
      Vector(poolKey)
    )
    val redeemers = B.cmap(
      B.arr(B.uint(0), B.uint(1)) -> B.arr(B.uint(0), B.arr(B.uint(1000000), B.uint(500000000)))
    )
    def topBody(integrity: xyz.kd5ujc.scarab.codec.Bytes) = B.body(
      0 -> B.set(topIn.node, scriptIn.node),
      1 -> B.arr(paid(10 * m)),
      2 -> B.uint(3 * m + 4 * m),
      4 -> B.set(pool),
      5 -> B.cmap(B.bytes(B.rewardAccount(payer)) -> B.uint(5 * m)),
      11 -> B.bytes(integrity),
      13 -> B.set(collateral.node),
      23 -> B.set(sub)
    )
    def topOf(integrity: xyz.kd5ujc.scarab.codec.Bytes) =
      val body = topBody(integrity)
      B.topTx(
        body,
        B.witnesses(
          body,
          Vector(payer, poolKey),
          4 -> B.set(datum),
          5 -> redeemers,
          7 -> B.set(B.bytes(script))
        )
      )
    // the fixup's script integrity hash (`fixupPPHash`), over the redeemers, the datum and Plutus
    // V3's view
    val unhashed = topOf(xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(32)(0.toByte)))
    val integrity = ScriptIntegrity
      .mkScriptIntegrity[Dijkstra, TxLevel.Top](pp, unhashed, Set(PlutusLanguage.V3))
      .map(ScriptIntegrity.hashScriptIntegrity(_))
      .getOrElse(fail("no script integrity"))
    val k = built(
      "UtxoSpec.hs:525",
      topOf(integrity),
      B.utxo(
        topIn -> paid(8 * m + deposit),
        scriptIn -> B.datumHashOut(
          B.scriptAddress(B.scriptHash(3, script)),
          4 * m,
          B.dataHash(datum)
        ),
        subIn -> paid(5 * m),
        collateral -> paid(30000000)
      )
    )
    assertEquals(
      ledgerOf(k, registering(payer -> 5 * m, poolReward -> 0, depositTo -> 0)),
      Vector(
        DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](
          DijkstraUtxowPredFailure.UtxoFailure(
            DijkstraUtxoPredFailure.ValueNotConservedInLegacyMode(
              Mismatch(
                MultiAssetValue.ofAda(8 * m + deposit + 5 * m + 4 * m),
                MultiAssetValue.ofAda(10 * m + 3 * m + 4 * m)
              )
            )
          )
        )
      )
    )
  }

  // ---- LEDGERS -----------------------------------------------------------------------------------

  private def ledgersEnv(k: Case): LedgersEnv[Dijkstra] =
    LedgersEnv[Dijkstra](k.slot, epochOf(k), pp, ChainAccountState(Coin(0), Coin(0)))

  private def ledgers(k: Case, txs: Vector[Tx[TxLevel.Top, Dijkstra]], s: Seed) =
    DijkstraLedgerRun.ledgers(
      leios,
      ledgersEnv(k),
      txs,
      context(TxEnv.Validation.All),
      s.state,
      s.proposals,
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder,
      clock
    )

  test("LEDGERS of one chain transaction is its LEDGER embedded, from its seed and from empty") {
    // LEDGER one level down, as LEDGERS nests it: a sub-rule's failures are one block, in the
    // order it recorded them, where the root's are reported last first (`Judgment.flatten`)
    chain.foreach { k =>
      val s = seed(k)
      val empty = Seed(start.cert, storedState(k.utxo), noProposals)
      Vector(s, empty).foreach { from =>
        val one = ledgers(k, Vector(k.tx), from).map(_.leftMap(_.toChain.toVector))
        val alone = DijkstraLedgerRun
          .ledger(
            leios,
            envOf(k, Coin(0)),
            annotate(k),
            context(TxEnv.Validation.All),
            from.state,
            from.proposals,
            ScarabEvaluator.default,
            Verdicts.Direct,
            ScarabEvaluator.defaultDecoder,
            depth = 1
          )
          .map(
            _.bimap(
              _.toChain.toVector.map(ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](_)),
              (st, ps, es) => (st, ps, es.map(ShelleyLedgersEvent.LedgerEvent[Dijkstra](_)))
            )
          )
        assertEquals(one, alone, k.name)
      }
    }
  }

  /** LEDGER on each of `block` 's transactions in turn, from the state the one before left, each
    * annotated against that state's UTxO set, as LEDGERS annotates it (`ledgersTransition`,
    * `Babbage/Rules/Ledgers.hs:129-136`, cardano-ledger 9df9f98c): the state, the proposals and the
    * events after the block.
    */
  private def inTurn(
      block: Vector[Case],
      from: Seed
  ): DijkstraLedgerRun.After[LedgerEvent[Dijkstra]] =
    block.foldLeft((from.state, from.proposals, cats.data.Chain.empty[LedgerEvent[Dijkstra]])) {
      case ((st, ps, es), k) =>
        judge(k.copy(utxo = DijkstraUtxoRun.originalUtxo(st.utxo.utxo.utxo, k.tx)), st, ps) match
          case Right(Right((s2, p2, e2))) => (s2, p2, es ++ e2)
          case other                      => fail(s"${k.name}: $other")
    }

  private def sameAsInTurn(block: Vector[Case], from: Seed): Unit =
    val (after, proposals, events) = inTurn(block, from)
    ledgers(block.head, block.map(_.tx), from) match
      case Right(Right((st, ps, es))) =>
        assertEquals(st, after)
        assertEquals(ps, proposals)
        assertEquals(es, events.map(ShelleyLedgersEvent.LedgerEvent[Dijkstra](_)))
      case other => fail(s"$other")

  test("LEDGERS threads the state through a block's transactions, as LEDGER after LEDGER does") {
    // the block of the sample with the most transactions: their seeds and recorded outputs
    // together, judged in the block's order, and LEDGER on each in turn from the state the one
    // before left
    val block = chainContextCases
      .groupBy(j => j.hcursor.get[BigInt]("block").fold(e => fail(e.message), identity))
      .maxBy((b, js) => (js.size, -b))
      ._2
      .map(j => chain.find(_.name == str(j, "name")).getOrElse(fail(str(j, "name"))))
      .sortBy(_.index)
    assert(block.size >= 3, s"${block.size}")
    val seeds = block.map(seed)
    val utxo = UTxO[Dijkstra](block.flatMap(_.utxo.unUTxO).toMap)
    val together = Seed(
      seeds
        .map(_.cert)
        .reduce((a, b) =>
          accountsL.modify(_ => ConwayAccounts(accountsOf(a).states ++ accountsOf(b).states))(a)
        ),
      storedState(utxo).copy[Dijkstra](gov = seeds.head.utxo.gov),
      seeds.head.proposals
    )
    sameAsInTurn(block, together)
  }

  test("LEDGERS annotates each transaction against the state the one before left") {
    // a built block: the first transaction pays an output to a native script (`all []`), the
    // second spends it with the script witnessed. Annotated against the UTxO set before the block,
    // the second needs no script, and the one it gives is extraneous; LEDGERS annotates it against
    // the state the first left (`Babbage/Rules/Ledgers.hs:129-136`, cardano-ledger 9df9f98c) and
    // admits both
    val allOf = B.arr(B.uint(1), B.arr())
    val lock = B.scriptHash(0, B.encode(allOf))
    val first = B.fresh(0x72)
    val body1 = B.body(
      0 -> B.set(first.node),
      1 -> B.arr(B.out(B.scriptAddress(lock), 10000000), paid(10000000 - fee)),
      2 -> B.uint(fee)
    )
    val created = B.In(B.id(body1), 0)
    val body2 = B.body(
      0 -> B.set(created.node),
      1 -> B.arr(paid(10000000 - fee)),
      2 -> B.uint(fee)
    )
    val utxo = B.utxo(first -> paid(20000000))
    val block = Vector(
      built("pays the script", B.topTx(body1, B.witnesses(body1, Vector(payer))), utxo),
      built(
        "spends it",
        B.topTx(body2, B.witnesses(body2, Vector.empty, 1 -> B.set(allOf))),
        utxo,
        index = 1
      )
    )
    val from = Seed(start.cert, storedState(utxo), noProposals)
    // the stale annotation refuses the second
    val afterFirst = inTurn(block.take(1), from)
    assert(
      judge(block(1), afterFirst._1, afterFirst._2) match
        case Right(Left(_)) => true
        case _              => false
    )
    sameAsInTurn(block, from)
  }

  test("LEDGERS hands each annotation's script cache to the next (Babbage/Rules/Ledgers.hs:124)") {
    // two built transactions providing the same Plutus V3 script: LEDGERS starts with an empty
    // cache once and carries `cacheStAnnTxG` from each annotation into the next
    // (`Babbage/Rules/Ledgers.hs:129-137`, cardano-ledger 9df9f98c), so the second's runnable is the
    // one the first's annotation built; annotated with an empty cache it is built again
    val script = B.alwaysFailsWithDatumV3
    val hash = B.scriptHash(3, script)
    val (in1, in2) = (B.fresh(0x76), B.fresh(0x77))
    def providing(in: B.In) =
      val body =
        B.body(0 -> B.set(in.node), 1 -> B.arr(paid(10000000 - fee)), 2 -> B.uint(fee))
      B.topTx(body, B.witnesses(body, Vector(payer), 7 -> B.set(B.bytes(script))))
    val utxo = B.utxo(in1 -> paid(10000000), in2 -> paid(10000000))
    val txs = Vector(providing(in1), providing(in2))
    val k = built("cache", txs.head, utxo)
    DijkstraLedgerRun.ledgersCaches(
      leios,
      ledgersEnv(k),
      txs,
      LedgerState(start.cert, storedState(utxo)),
      noProposals,
      clock
    ) match
      case Right(Right((_, _, caches))) =>
        caches.toVector match
          case Vector(first, second) =>
            val runnable = first.getOrElse(hash, fail("the first caches no runnable"))
            assert(second.get(hash).exists(_ eq runnable))
            val again = EraTx.dijkstra.cacheStAnnTx[TxLevel.Top](
              DijkstraLedgers.mkStAnnTx(clock, pp)(utxo, Map.empty, txs(1))
            )
            assert(again.get(hash).exists(r => !(r eq runnable)))
          case other => fail(s"$other")
      case other => fail(s"$other")
  }

  // ---- the injections ----------------------------------------------------------------------------

  private lazy val account: AccountAddress =
    ok(
      Convert.accountAddress(
        flat.RewardAccount(
          0,
          flat.Credential.Key(xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(28)(4.toByte)))
        )
      )
    )

  private lazy val cred: Credential[Staking] = account.id.credential

  private given cats.Order[AccountAddress] =
    cats.Order.by[AccountAddress, flat.RewardAccount](Convert.rewardAccountBack)(using
      cats.Order.fromOrdering
    )

  test("each injection into LEDGER equals the reference's composition") {
    def into[F](f: F)(using i: InjectLedgerFailure[F, Dijkstra]): LedgerPredFailure[Dijkstra] =
      i.injectFailure(f)
    val missing = Map(account -> Coin(1))
    val inexact = cats.data.NonEmptyMap.one(account, Mismatch[RelEQ, Coin](Coin(1), Coin(2)))
    val deleg = ConwayDelegPredFailure.IncorrectDepositDELEG[Dijkstra](Coin(1))
    val certs =
      ConwayCertsPredFailure.CertFailure[Dijkstra](ConwayCertPredFailure.DelegFailure(deleg))
    val utxow = DijkstraUtxowPredFailure.InvalidMetadata[Dijkstra]()
    val gov =
      DijkstraGovPredFailure.ProposalDepositIncorrect[Dijkstra](Mismatch(Coin(1), Coin(2)))
    val entities = DijkstraLedgerPredFailure.DijkstraEntitiesFailure[Dijkstra](_)
    // `conwayToDijkstraLedgerPredFailure` (`Dijkstra/Rules/Ledger.hs:534-543`, cardano-ledger
    // 9df9f98c) on the seven constructors it maps
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayUtxowFailure[Dijkstra](utxow)),
      DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](utxow)
    )
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayCertsFailure[Dijkstra](certs)),
      entities(EntitiesPredFailure.CertsFailure(certs))
    )
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayGovFailure[Dijkstra](gov)),
      DijkstraLedgerPredFailure.DijkstraGovFailure[Dijkstra](gov)
    )
    assertEquals(
      into(
        new ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Dijkstra](
          Mismatch(Coin(1), Coin(2))
        )
      ),
      DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch[Dijkstra](
        Mismatch(Coin(1), Coin(2))
      )
    )
    assertEquals(
      into(
        new ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Dijkstra](Mismatch(2L, 1L))
      ),
      DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](Mismatch(2L, 1L))
    )
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[Dijkstra](missing)),
      entities(EntitiesPredFailure.WithdrawalAccountsMissing(missing))
    )
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayIncompleteWithdrawals[Dijkstra](inexact)),
      entities(EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode(inexact))
    )
    // `shelleyToDijkstraLedgerPredFailure` (`:549-553`)
    assertEquals(
      into(ShelleyLedgerPredFailure.UtxowFailure[Dijkstra](utxow)),
      DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](utxow)
    )
    assertEquals(
      into(ShelleyLedgerPredFailure.ShelleyWithdrawalsMissingAccounts[Dijkstra](missing)),
      entities(EntitiesPredFailure.WithdrawalAccountsMissing(missing))
    )
    assertEquals(
      into(ShelleyLedgerPredFailure.ShelleyIncompleteWithdrawals[Dijkstra](inexact)),
      entities(EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode(inexact))
    )
    // `AccountAlreadyRegistered` (`:113-119`): DELEG's own refusal of a second registration
    assertEquals(
      into(AccountAlreadyRegistered[Dijkstra](cred)),
      entities(
        EntitiesPredFailure.CertsFailure(
          ConwayCertsPredFailure.CertFailure(
            ConwayCertPredFailure.DelegFailure(ConwayDelegPredFailure.StakeKeyRegisteredDELEG(cred))
          )
        )
      )
    )
    // UTXOW's and those below it (`:129-166`), through UTXOW's injection
    val utxo = DijkstraUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()
    assertEquals(into(utxow), DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](utxow))
    assertEquals(
      into(utxo),
      DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](
        DijkstraUtxowPredFailure.UtxoFailure(utxo)
      )
    )
    assertEquals(
      into(new ShelleyUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()),
      DijkstraLedgerPredFailure.DijkstraUtxowFailure[Dijkstra](
        DijkstraUtxowPredFailure.UtxoFailure(utxo)
      )
    )
    // ENTITIES' and those below it (`:168-187`, `:192-193`), through ENTITIES' injection
    assertEquals(
      into(EntitiesPredFailure.WithdrawalAccountsMissing[Dijkstra](missing)),
      entities(EntitiesPredFailure.WithdrawalAccountsMissing(missing))
    )
    assertEquals(into(certs), entities(EntitiesPredFailure.CertsFailure(certs)))
    assertEquals(into(deleg), entities(EntitiesPredFailure.CertsFailure(certs)))
    // GOV's (`:189-190`, `:195-196`)
    assertEquals(into(gov), DijkstraLedgerPredFailure.DijkstraGovFailure[Dijkstra](gov))
    assertEquals(
      into(
        ConwayGovPredFailure.ProposalDepositIncorrect[Dijkstra](Mismatch(Coin(1), Coin(2)))
      ),
      DijkstraLedgerPredFailure.DijkstraGovFailure[Dijkstra](gov)
    )
    // SUBLEDGERS' and SUBENTITIES' (`:198-205`)
    val subEntities = SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra](missing)
    val subLedgers = DijkstraSubLedgersPredFailure.SubLedgerFailure[Dijkstra](
      DijkstraSubLedgerPredFailure.SubEntitiesFailure(subEntities)
    )
    assertEquals(
      into(subLedgers),
      DijkstraLedgerPredFailure.DijkstraSubLedgersFailure[Dijkstra](subLedgers)
    )
    assertEquals(
      into(subEntities),
      DijkstraLedgerPredFailure.DijkstraSubLedgersFailure[Dijkstra](subLedgers)
    )
    // the identity (`:121`)
    val own = DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch[Dijkstra](
      Mismatch(Coin(1), Coin(2))
    )
    assertEquals(into(own), own)
  }

  test("the constructors the reference calls impossible for LEDGER have no injection") {
    // the control: a constructor LEDGER reaches has one, in the same form
    assertEquals(
      compileErrors(
        "summon[InjectLedgerFailure[ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    // `error "Impossible"` (`Dijkstra/Rules/Ledger.hs:538, 541`, cardano-ledger 9df9f98c)
    assert(
      compileErrors(
        "summon[InjectLedgerFailure[ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgerFailure[ConwayLedgerPredFailure.ConwayMempoolFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgerFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    // and DELEGS's failure has no value at Dijkstra (`:551`)
    assert(
      compileErrors(
        "ShelleyLedgerPredFailure.DelegsFailure[Dijkstra](ConwayDelegPredFailure.IncorrectDepositDELEG[Dijkstra](Coin(1)))"
      ).nonEmpty
    )
  }

  test("LEDGERS injects the failures the reference lists, LEDGER's in Shelley's LedgerFailure") {
    def into[F](f: F)(using i: InjectLedgersFailure[F, Dijkstra]): LedgersPredFailure[Dijkstra] =
      i.injectFailure(f)
    def viaLedger[F](f: F)(using
        i: InjectLedgerFailure[F, Dijkstra]
    ): LedgersPredFailure[Dijkstra] =
      ShelleyLedgersPredFailure.LedgerFailure(i.injectFailure(f))
    val utxow = DijkstraUtxowPredFailure.InvalidMetadata[Dijkstra]()
    val utxo = DijkstraUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()
    val deleg = ConwayDelegPredFailure.IncorrectDepositDELEG[Dijkstra](Coin(1))
    val certs =
      ConwayCertsPredFailure.CertFailure[Dijkstra](ConwayCertPredFailure.DelegFailure(deleg))
    val gov =
      DijkstraGovPredFailure.ProposalDepositIncorrect[Dijkstra](Mismatch(Coin(1), Coin(2)))
    val own = DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch[Dijkstra](
      Mismatch(Coin(1), Coin(2))
    )
    // `Dijkstra/Rules/Ledgers.hs:25-82`, cardano-ledger 9df9f98c
    assertEquals(into(own), ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](own))
    assertEquals(
      into(ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](own)),
      ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](own)
    )
    assertEquals(into(utxow), viaLedger(utxow))
    assertEquals(into(utxo), viaLedger(utxo))
    val shelley: DijkstraUtxo.ShelleyFailure[Dijkstra] =
      new ShelleyUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()
    assertEquals(into(shelley), viaLedger(utxo))
    assertEquals(into(certs), viaLedger(certs))
    assertEquals(into(ConwayCertPredFailure.DelegFailure[Dijkstra](deleg)), viaLedger(certs))
    assertEquals(into(deleg), viaLedger(certs))
    assertEquals(into(gov), viaLedger(gov))
    assertEquals(
      into(
        ConwayGovPredFailure.ProposalDepositIncorrect[Dijkstra](Mismatch(Coin(1), Coin(2)))
      ),
      viaLedger(gov)
    )
    // the other ten families (`:33-40`, `:45-61`, `:72-76`), each against the constructors the
    // reference composes, not through LEDGER's injection
    def ledgerOf(f: DijkstraLedgerPredFailure[Dijkstra]): LedgersPredFailure[Dijkstra] =
      ShelleyLedgersPredFailure.LedgerFailure[Dijkstra](f)
    def inUtxow(f: DijkstraUtxowPredFailure[Dijkstra]) =
      ledgerOf(DijkstraLedgerPredFailure.DijkstraUtxowFailure(f))
    def inUtxo(f: DijkstraUtxoPredFailure[Dijkstra]) =
      inUtxow(DijkstraUtxowPredFailure.UtxoFailure(f))
    def inCert(f: ConwayCertPredFailure[Dijkstra]) =
      ledgerOf(
        DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
          EntitiesPredFailure.CertsFailure(ConwayCertsPredFailure.CertFailure(f))
        )
      )
    val hash = xyz.kd5ujc.scarab.codec.Bytes(Vector.fill(32)(7.toByte))
    val integrity = Mismatch[RelEQ, Option[xyz.kd5ujc.scarab.codec.Bytes]](None, Some(hash))
    // `Dijkstra/Rules/Ledgers.hs:33-34`, cardano-ledger 9df9f98c, through
    // `babbageToConwayUtxowPredFailure`
    assertEquals(
      into(BabbageUtxowPredFailure.ScriptIntegrityHashMismatch[Dijkstra](integrity, None)),
      inUtxow(DijkstraUtxowPredFailure.ScriptIntegrityHashMismatch(integrity, None))
    )
    // `:36-37`, through `alonzoToConwayUtxowPredFailure`
    assertEquals(
      into(AlonzoUtxowPredFailure.PPViewHashesDontMatch[Dijkstra](integrity)),
      inUtxow(DijkstraUtxowPredFailure.PPViewHashesDontMatch(integrity))
    )
    // `:39-40`, through `shelleyToConwayUtxowPredFailure`
    assertEquals(
      into(ShelleyUtxowPredFailure.MissingTxBodyMetadataHash[Dijkstra](hash)),
      inUtxow(DijkstraUtxowPredFailure.MissingTxBodyMetadataHash(hash))
    )
    // `:45-46`, `:48-49`, `:57-58` and `:60-61`, Dijkstra's UTXO's constructors of Babbage's,
    // Alonzo's, Shelley's and Allegra's failures (`Dijkstra/Rules/Utxo.hs:182-195`, cardano-ledger
    // 9df9f98c)
    val babbage: DijkstraUtxo.BabbageFailure[Dijkstra] =
      new BabbageUtxoPredFailure.IncorrectTotalCollateralField[Dijkstra](DeltaCoin(1), Coin(2))
    assertEquals(
      into(babbage),
      inUtxo(DijkstraUtxoPredFailure.IncorrectTotalCollateralField(DeltaCoin(1), Coin(2)))
    )
    val alonzo: DijkstraUtxo.AlonzoFailure[Dijkstra] =
      new AlonzoUtxoPredFailure.TooManyCollateralInputs[Dijkstra](
        Mismatch(Unsigned.lit(4), Unsigned.lit(3))
      )
    assertEquals(
      into(alonzo),
      inUtxo(
        DijkstraUtxoPredFailure.TooManyCollateralInputs(Mismatch(Unsigned.lit(4), Unsigned.lit(3)))
      )
    )
    val badInput =
      cats.data.NonEmptySet.one(chain.head.utxo.unUTxO.keys.head)(using cats.Order.fromOrdering)
    val shelleyBad: DijkstraUtxo.ShelleyFailure[Dijkstra] =
      new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](badInput)
    assertEquals(into(shelleyBad), inUtxo(DijkstraUtxoPredFailure.BadInputsUTxO(badInput)))
    val interval = ValidityInterval(None, Some(BigInt(9)))
    val allegra: DijkstraUtxo.AllegraFailure[Dijkstra] =
      new AllegraUtxoPredFailure.OutsideValidityIntervalUTxO[Dijkstra](interval, BigInt(10))
    assertEquals(
      into(allegra),
      inUtxo(DijkstraUtxoPredFailure.OutsideValidityIntervalUTxO(interval, BigInt(10)))
    )
    // `:51-52` and `:54-55`, UTXOS's: Alonzo's through `alonzoToConwayUtxosPredFailure`
    val tag = ConwayUtxosPredFailure.ValidationTagMismatch[Dijkstra](
      IsPhase2Valid.Phase2Invalid,
      TagMismatchDescription.PassedUnexpectedly
    )
    assertEquals(into(tag), inUtxo(DijkstraUtxoPredFailure.UtxosFailure(tag)))
    assertEquals(
      into(
        AlonzoUtxosPredFailure.ValidationTagMismatch[Dijkstra](
          IsPhase2Valid.Phase2Invalid,
          TagMismatchDescription.PassedUnexpectedly
        )
      ),
      inUtxo(DijkstraUtxoPredFailure.UtxosFailure(tag))
    )
    // `:72-73`, through Dijkstra's POOL's injection of Shelley's (`Dijkstra/Rules/Pool.hs:146`,
    // cardano-ledger 9df9f98c), and `:75-76`
    assertEquals(
      into(
        ShelleyPoolPredFailure.StakePoolCostTooLowPOOL[Dijkstra](Mismatch(Coin(1), Coin(2)))
      ),
      inCert(
        ConwayCertPredFailure.PoolFailure(
          DijkstraPoolPredFailure.StakePoolCostTooLowPOOL(Mismatch(Coin(1), Coin(2)))
        )
      )
    )
    val drep =
      DijkstraGovCertPredFailure.DijkstraDRepIncorrectDeposit[Dijkstra](
        Mismatch(Coin(1), Coin(2))
      )
    assertEquals(into(drep), inCert(ConwayCertPredFailure.GovCertFailure(drep)))
    // the instances are contravariant: a constructor's type finds its family's instance, or the
    // union of the constructors Dijkstra's UTXO reaches
    assertEquals(
      summon[InjectLedgersFailure[ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra], Dijkstra]]
        .injectFailure(new ShelleyUtxoPredFailure.BadInputsUTxO[Dijkstra](badInput)),
      inUtxo(DijkstraUtxoPredFailure.BadInputsUTxO(badInput))
    )
    assertEquals(
      summon[InjectLedgersFailure[DijkstraUtxoPredFailure.InputSetEmptyUTxO[Dijkstra], Dijkstra]]
        .injectFailure(new DijkstraUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()),
      inUtxo(utxo)
    )
  }

  test("LEDGERS has no instance for the failures the reference does not list") {
    assertEquals(
      compileErrors("summon[InjectLedgersFailure[DijkstraUtxowPredFailure[Dijkstra], Dijkstra]]"),
      ""
    )
    // the ten the reference lists for LEDGER and not for LEDGERS
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[ShelleyLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[ConwayUtxowPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[DijkstraUtxo.ConwayFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[EntitiesPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[DijkstraPoolPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[ConwayGovCertPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[DijkstraSubLedgersPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[SubEntitiesPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectLedgersFailure[AccountAlreadyRegistered[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }

  // ---- MEMPOOL -----------------------------------------------------------------------------------

  private def mempool(k: Case, state: LedgerState[Dijkstra], proposals: Proposals[Dijkstra]) =
    DijkstraLedgerRun.mempool(
      leios,
      envOf(k, Coin(0)),
      annotate(k),
      context(TxEnv.Validation.All),
      state,
      proposals,
      ScarabEvaluator.default,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  test(
    "MEMPOOL is LEDGER embedded on every chain transaction, and refuses it again as a duplicate"
  ) {
    chain.foreach { k =>
      val s = seed(k)
      val empty = Seed(start.cert, storedState(k.utxo), noProposals)
      Vector(s, empty).foreach { from =>
        val judged = mempool(k, from.state, from.proposals).map(_.leftMap(_.toChain.toVector))
        val alone = DijkstraLedgerRun
          .ledger(
            leios,
            envOf(k, Coin(0)),
            annotate(k),
            context(TxEnv.Validation.All),
            from.state,
            from.proposals,
            ScarabEvaluator.default,
            Verdicts.Direct,
            ScarabEvaluator.defaultDecoder,
            depth = 1
          )
          .map(
            _.bimap(
              _.toChain.toVector.map(DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](_)),
              (st, ps, es) => (st, ps, es.map(DijkstraMempoolEvent.LedgerEvent[Dijkstra](_)))
            )
          )
        assertEquals(judged, alone, k.name)
      }
      // on the state it left, none of its top-level inputs is unspent: MEMPOOL refuses it alone,
      // and LEDGER does not run (`whenFailureFreeDefault`, `Dijkstra/Rules/Mempool.hs:206`,
      // cardano-ledger 9df9f98c)
      mempool(k, s.state, s.proposals) match
        case Right(Right((after, proposals, _))) =>
          assertEquals(
            mempool(k, after, proposals).map(_.leftMap(_.toChain.toVector)),
            Right(Left(Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()))),
            k.name
          )
        case other => fail(s"${k.name}: $other")
    }
  }

  test(
    "a batch whose sub-transaction's input is unspent, but whose own are not (MempoolSpec.hs:44)"
  ) {
    // `Dijkstra/Imp/MempoolSpec.hs:44-53`, cardano-ledger 9df9f98c, without fixups: the top level's
    // input spent, its sub-transaction's in the UTxO set; MEMPOOL asks of the top level's inputs
    // alone (`Dijkstra/Rules/Mempool.hs:196-206`), so the one failure, and LEDGER does not run
    val (spent, live) = (B.fresh(0x70), B.fresh(0x71))
    val liveSub = B.subTx(B.body(0 -> B.set(live.node), 1 -> B.arr()), Vector.empty)
    val body = B.body(0 -> B.set(spent.node), 1 -> B.arr(), 2 -> B.uint(0), 23 -> B.set(liveSub))
    val k = built(
      "MempoolSpec.hs:44",
      B.topTx(body, B.witnesses(body, Vector.empty)),
      B.utxo(live -> paid(10000000))
    )
    assertEquals(
      subBodies(k.tx).flatMap(bodies.inputs[TxLevel.Sub](_)(using sub)).toSet,
      k.utxo.unUTxO.keySet
    )
    assertEquals(
      mempool(k, LedgerState(start.cert, storedState(k.utxo)), noProposals)
        .map(_.leftMap(_.toChain.toVector)),
      Right(Left(Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())))
    )
  }

  test("no ledger check runs when all inputs are spent (MempoolSpec.hs:91)") {
    // `Dijkstra/Imp/MempoolSpec.hs:91-104`, cardano-ledger 9df9f98c: a sub-transaction naming the
    // main network; without fixups, its top level's input spent, the one failure is that all are;
    // with them (an input for each level, the fee, the signatures), SUBUTXO's refusal of the
    // network, through LEDGER, alone
    val (spent, subIn, topIn) = (B.fresh(0x73), B.fresh(0x74), B.fresh(0x75))
    val bare = B.subTx(B.body(0 -> B.set(), 1 -> B.arr(), 15 -> B.uint(1)), Vector.empty)
    val body = B.body(0 -> B.set(spent.node), 1 -> B.arr(), 2 -> B.uint(0), 23 -> B.set(bare))
    val unfixed =
      built("MempoolSpec.hs:91", B.topTx(body, B.witnesses(body, Vector.empty)), B.utxo())
    assertEquals(
      mempool(unfixed, LedgerState(start.cert, storedState(unfixed.utxo)), noProposals)
        .map(_.leftMap(_.toChain.toVector)),
      Right(Left(Vector(DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]())))
    )
    val fixed = built(
      "MempoolSpec.hs:91",
      batch(Vector(topIn), 11000000 - fee, Vector(spending(subIn, Vector(15 -> B.uint(1))))),
      B.utxo(subIn -> paid(1000000), topIn -> paid(10000000))
    )
    assertEquals(
      mempool(fixed, LedgerState(start.cert, storedState(fixed.utxo)), noProposals)
        .map(_.leftMap(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
              DijkstraLedgerPredFailure.DijkstraSubLedgersFailure(
                DijkstraSubLedgersPredFailure.SubLedgerFailure(
                  DijkstraSubLedgerPredFailure.SubUtxowFailure(
                    DijkstraSubUtxowPredFailure.SubUtxoFailure(
                      DijkstraSubUtxoPredFailure.SubWrongNetworkInTxBody(
                        Mismatch(Network.Mainnet, Network.Testnet)
                      )
                    )
                  )
                )
              )
            )
          )
        )
      )
    )
  }

  test("a transaction one of whose inputs is unspent goes on to LEDGER") {
    // a chain transaction of two inputs or more, judged again on the state it left with one of
    // them restored: not all its inputs are spent, so MEMPOOL runs LEDGER, which refuses the
    // others as spent
    val k = chain.find(k => bodies.inputs[TxLevel.Top](k.tx.body).size >= 2).getOrElse(fail("none"))
    val s = seed(k)
    val after = mempool(k, s.state, s.proposals) match
      case Right(Right((st, _, _))) => st
      case other                    => fail(s"$other")
    val in = bodies.inputs[TxLevel.Top](k.tx.body).head
    val restored = after.copy[Dijkstra](utxo =
      after.utxo.copy[Dijkstra](utxo =
        UtxoTables(Utxo(after.utxo.utxo.utxo.toMap.updated(in, s.utxo.utxo.utxo.toMap(in))))
      )
    )
    mempool(k, restored, s.proposals) match
      case Right(Left(fs)) =>
        assert(
          fs.forall {
            case DijkstraMempoolPredFailure.LedgerFailure(_) => true
            case _                                           => false
          },
          s"$fs"
        )
        assert(
          fs.exists {
            case DijkstraMempoolPredFailure.LedgerFailure(
                  DijkstraLedgerPredFailure.DijkstraUtxowFailure(
                    DijkstraUtxowPredFailure.UtxoFailure(DijkstraUtxoPredFailure.BadInputsUTxO(_))
                  )
                ) =>
              true
            case _ => false
          },
          s"$fs"
        )
      case other => fail(s"$other")
  }

  test("each injection into MEMPOOL equals the reference's, and there are only its three") {
    def into[F](f: F)(using i: InjectMempoolFailure[F, Dijkstra]): MempoolPredFailure[Dijkstra] =
      i.injectFailure(f)
    val utxo = DijkstraUtxoPredFailure.InputSetEmptyUTxO[Dijkstra]()
    val own = DijkstraMempoolPredFailure.AllInputsAreSpent[Dijkstra]()
    // `Dijkstra/Rules/Mempool.hs:89-98`, cardano-ledger 9df9f98c
    assertEquals(into(own), own)
    assertEquals(
      into(
        new ConwayLedgerPredFailure.ConwayMempoolFailure[Dijkstra](
          flat.MempoolProblem.AllInputsSpent
        )
      ),
      own
    )
    val other = flat.MempoolProblem.UnelectedCommittee(Vector.empty)
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayMempoolFailure[Dijkstra](other)),
      DijkstraMempoolPredFailure.MempoolFailure[Dijkstra](other)
    )
    val treasury = Mismatch[RelEQ, Coin](Coin(1), Coin(2))
    assertEquals(
      into(new ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Dijkstra](treasury)),
      DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
        DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch(treasury)
      )
    )
    assertEquals(
      into(utxo),
      DijkstraMempoolPredFailure.LedgerFailure[Dijkstra](
        DijkstraLedgerPredFailure.DijkstraUtxowFailure(DijkstraUtxowPredFailure.UtxoFailure(utxo))
      )
    )
    // the one the reference calls impossible (`conwayToDijkstraLedgerPredFailure`), and the
    // failures it has no instance for
    assert(
      compileErrors(
        "summon[InjectMempoolFailure[ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectMempoolFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectMempoolFailure[DijkstraLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectMempoolFailure[DijkstraUtxowPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectMempoolFailure[EntitiesPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }
