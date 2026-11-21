// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyList, NonEmptyMap, NonEmptySet}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Bytes, Rational}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.{GovActionId, Mismatch, TestLedger, Unsigned, Voter}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  Build,
  CertState,
  ColdCommittee,
  Coin,
  CompactCoin,
  Credential,
  Dijkstra,
  Empty,
  EpochNo,
  Hash32,
  Network,
  PoolParamsBlsKey,
  ScriptHash
}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Dijkstra's GOV on the era types: Conway's transition at Dijkstra
  * (`ConwayGov.conwayGovTransition` with `InjectGov.dijkstra`; cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Gov.hs:199-220`), where the oracle records
  * nothing (its cases, in the node's `DijkstraRulesOracleSuite`, have no parameter change):
  *   - `conwayToDijkstraGovPredFailure` on a built value of each of the 19 constructors, each the
  *     Dijkstra constructor of the same tag and fields; the injection and the events' identity;
  *   - the flat form has no parameter change: a failure that carries one is not projected;
  *   - the chain's two proposals, parameter changes of Dijkstra's endorser-block limits (body keys
  *     45 to 48, `fixtures/reference/leios-testnet-chain-contexts`), which the chain's ledger
  *     admitted: from a state that registers their return account, under the testnet's parameters
  *     and constitution, they are admitted in sequence, the second the child of the first, and each
  *     is refused as the rule says with another guardrails script, a wrong deposit or the second's
  *     parent missing; a stake pool and a DRep vote on the first, as the update's keys are of the
  *     security group (`Dijkstra/PParams.hs:258-269`, cardano-ledger 9df9f98c);
  *   - the empty parameter change of the Leios build's decoding corpus is malformed
  *     (`ppuWellFormed`, `Dijkstra/PParams.hs:1164`, cardano-ledger 9df9f98c), its return account
  *     of the other network refused after it.
  *
  * The chain's transactions are a component check: the fixture records neither the chain's
  * certificate state nor its proposals, so each expected verdict is the rule's, derived by hand.
  */
class DijkstraGovSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val b = EraTxBody.dijkstra
  private val c = EraCertState.dijkstra
  private given ConwayEraAccounts[Dijkstra] = c.accountsOf

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  private val leios: Globals =
    TestLedger.globals.copy(ledger =
      TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c)
    )

  // ---- the failure type and its injection --------------------------------------------------------

  private val actionId = GovActionId(Bytes(Vector.fill(32)(6.toByte)), 0)
  private val flatKey = flat.Credential.Key(Bytes(Vector.fill(28)(1.toByte)))
  private val account: AccountAddress = ok(Convert.accountAddress(flat.RewardAccount(0, flatKey)))
  private val cold: Credential[ColdCommittee] = ok(Convert.credential[ColdCommittee](flatKey))
  private given cats.Order[Credential[ColdCommittee]] = cats.Order[Credential[ColdCommittee]]
  private given cats.Order[AccountAddress] =
    cats.Order.by[AccountAddress, flat.RewardAccount](Convert.rewardAccountBack)(using
      cats.Order.fromOrdering
    )
  private val vote = (Voter.DRepVoter(flatKey), actionId)
  private val info: GovAction[Dijkstra] = GovAction.InfoAction()
  private lazy val proposal: ProposalProcedure[Dijkstra] =
    oracleProposals("gov/info").toVector.headOption.getOrElse(fail("no proposal"))
  private val script: ScriptHash = ok(ScriptHash.fromBytes(Bytes(Vector.fill(28)(9.toByte))))
  private val minor0 = Unsigned.from[Unsigned.Width.W32](BigInt(0)).getOrElse(fail("a minor 0"))
  private val version = ProtVer(Version.lit(13), minor0)

  /** The proposals of an oracle case, as the era reads them. */
  private def oracleProposals(name: String): OSet[ProposalProcedure[Dijkstra]] =
    jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .find(j => str(j, "name") == name)
      .map(j => eraProposals(hex(str(j, "proposals"))))
      .getOrElse(fail(s"no case $name"))

  /** Each of Conway's constructors, built, with the Dijkstra constructor the reference maps it to
    * (`Dijkstra/Rules/Gov.hs:224-243`, cardano-ledger 9df9f98c), in the order of the tags.
    */
  private lazy val pairs
      : Vector[(ConwayGovPredFailure[Dijkstra], DijkstraGovPredFailure[Dijkstra])] =
    import ConwayGovPredFailure as C
    import DijkstraGovPredFailure as D
    val ids = NonEmptyList.of(actionId)
    val votes = NonEmptyList.of(vote)
    val coins = Mismatch(Coin(1), Coin(2))
    val members = NonEmptySet.of(cold)
    val terms = NonEmptyMap.of(cold -> EpochNo.fromUnsigned(1L))
    val parent = Some(GovPurposeId[GovActionPurpose.HardFork](actionId))
    val versions = Mismatch(version, version)
    Vector(
      C.GovActionsDoNotExist(ids) -> D.GovActionsDoNotExist(ids),
      C.MalformedProposal(info) -> D.MalformedProposal(info),
      C.ProposalProcedureNetworkIdMismatch(account, Network.Testnet) ->
        D.ProposalProcedureNetworkIdMismatch(account, Network.Testnet),
      C.TreasuryWithdrawalsNetworkIdMismatch(NonEmptySet.of(account), Network.Testnet) ->
        D.TreasuryWithdrawalsNetworkIdMismatch(NonEmptySet.of(account), Network.Testnet),
      C.ProposalDepositIncorrect(coins) -> D.ProposalDepositIncorrect(coins),
      C.DisallowedVoters(votes) -> D.DisallowedVoters(votes),
      C.ConflictingCommitteeUpdate(members) -> D.ConflictingCommitteeUpdate(members),
      C.ExpirationEpochTooSmall(terms) -> D.ExpirationEpochTooSmall(terms),
      C.InvalidPrevGovActionId(proposal) -> D.InvalidPrevGovActionId(proposal),
      C.VotingOnExpiredGovAction(votes) -> D.VotingOnExpiredGovAction(votes),
      C.ProposalCantFollow(parent, versions) -> D.ProposalCantFollow(parent, versions),
      C.InvalidGuardrailsScriptHash(Some(script), None) ->
        D.InvalidGuardrailsScriptHash(Some(script), None),
      C.DisallowedProposalDuringBootstrap(proposal) -> D.DisallowedProposalDuringBootstrap(
        proposal
      ),
      C.DisallowedVotesDuringBootstrap(votes) -> D.DisallowedVotesDuringBootstrap(votes),
      C.VotersDoNotExist(NonEmptyList.of(vote._1)) -> D.VotersDoNotExist(NonEmptyList.of(vote._1)),
      C.ZeroTreasuryWithdrawals(info) -> D.ZeroTreasuryWithdrawals(info),
      C.ProposalReturnAccountDoesNotExist(account) -> D.ProposalReturnAccountDoesNotExist(account),
      C.TreasuryWithdrawalReturnAccountsDoNotExist(NonEmptyList.of(account)) ->
        D.TreasuryWithdrawalReturnAccountsDoNotExist(NonEmptyList.of(account)),
      C.UnelectedCommitteeVoters(NonEmptyList.of(flatKey)) ->
        D.UnelectedCommitteeVoters(NonEmptyList.of(flatKey))
    )

  test("each of Conway's failures is Dijkstra's of the same tag and fields") {
    assertEquals(pairs.size, 19)
    // the tags of both types are the constructors' places (`Conway/Rules/Gov.hs:237-255`,
    // `Dijkstra/Rules/Gov.hs:135-155`, cardano-ledger 9df9f98c)
    assertEquals(pairs.map(_._1.ordinal), (0 until 19).toVector)
    assertEquals(pairs.map(_._2.ordinal), (0 until 19).toVector)
    pairs.foreach { (conway, dijkstra) =>
      assertEquals(DijkstraGovPredFailure.conwayToDijkstraGovPredFailure(conway), dijkstra)
    }
  }

  test("Dijkstra's GOV injects Conway's failures as Dijkstra's, and Conway's events as they are") {
    pairs.foreach { (conway, dijkstra) =>
      assertEquals(InjectGov.dijkstra.injectFailure(conway), dijkstra)
    }
    val event = ConwayGovEvent.GovNewProposals[Dijkstra](
      ok(Hash32.fromBytes(actionId.txId)),
      EraGovRun.dijkstraEmpty
    )
    assertEquals(InjectGov.dijkstra.injectEvent(event), event)
  }

  // ---- the recorded proposals --------------------------------------------------------------------

  private lazy val conwayGenesis =
    json("fixtures/leios/testnet/config/conway-genesis.json").hcursor

  /** The testnet's parameters with its genesis's actions' lifetime. */
  private lazy val pp: PParams[Dijkstra] =
    era(
      testnetStored.copy(govActionLifetime =
        conwayGenesis.downField("govActionLifetime").as[BigInt].fold(e => fail(e.message), identity)
      )
    )

  /** The guardrails script of the testnet's genesis constitution. */
  private lazy val constitution: Option[ScriptHash] =
    Some(
      ok(
        ScriptHash.fromBytes(
          hex(
            conwayGenesis
              .downField("constitution")
              .get[String]("script")
              .fold(e => fail(e.message), identity)
          )
        )
      )
    )

  private lazy val epochSize: BigInt =
    json("fixtures/reference/leios-testnet-chain-contexts/cases.json").hcursor
      .get[BigInt]("epochSize")
      .fold(e => fail(e.message), identity)

  /** The chain's transactions that propose, each with its slot. */
  private lazy val proposing: Vector[(DijkstraTx[TxLevel.Top], BigInt)] =
    chainContextCases
      .map(j =>
        (
          ok(DijkstraTx.inBlock(node(hex(str(j, "bytes"))))),
          j.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity)
        )
      )
      .filter((tx, _) => b.proposalProcedures[TxLevel.Top](tx.body).toVector.nonEmpty)

  private def proposalsOf(tx: DijkstraTx[TxLevel.Top]): Vector[ProposalProcedure[Dijkstra]] =
    b.proposalProcedures[TxLevel.Top](tx.body).toVector

  private def idOf(tx: DijkstraTx[TxLevel.Top]): Hash32 = ok(Hash32.fromBytes(b.id(tx.body)))

  /** The certificate state with `accounts` registered. */
  private def registered(accounts: Vector[AccountAddress]): CertState[Dijkstra] =
    accounts.foldLeft(Empty.certState[Dijkstra]) { (state, a) =>
      c.certDStateL
        .andThen(xyz.kd5ujc.scarab.ledger.state.DState.accounts[Accounts[Dijkstra]])
        .modify(acc =>
          ConwayEraAccounts.registerConwayAccount[Dijkstra](
            a.id.credential,
            ok(CompactCoin.fromBigInt(BigInt(2000000))),
            None,
            acc
          )
        )(state)
    }

  private def returnAccount(p: ProposalProcedure[Dijkstra]): AccountAddress =
    ok(Convert.accountAddress(p.pProcReturnAddr))

  private def envOf(
      tx: DijkstraTx[TxLevel.Top],
      slot: BigInt,
      cert: CertState[Dijkstra],
      params: PParams[Dijkstra] = pp,
      guardrails: Option[ScriptHash] = constitution
  ): GovEnv[Dijkstra] =
    GovEnv[Dijkstra](
      idOf(tx),
      ok(EpochNo.fromBigInt(slot / epochSize)),
      params,
      guardrails,
      cert,
      None
    )

  /** The proposals of `tx`, with no votes or certificates. */
  private def signalOf(tx: DijkstraTx[TxLevel.Top]): GovSignal[Dijkstra] =
    GovSignal[Dijkstra](noVotes, b.proposalProcedures[TxLevel.Top](tx.body), Vector.empty)

  private def run(
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      state: Proposals[Dijkstra]
  ) = ok(EraGovRun.dijkstra(env, signal, Network.Testnet, state))

  private def failures(
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      state: Proposals[Dijkstra]
  ): Vector[GovPredFailure[Dijkstra]] = run(env, signal, state) match
    case Left(fs)    => fs.toChain.toVector
    case Right(left) => fail(s"admitted: $left")

  private def admitted(
      env: GovEnv[Dijkstra],
      signal: GovSignal[Dijkstra],
      state: Proposals[Dijkstra]
  ): Proposals[Dijkstra] = run(env, signal, state) match
    case Right((after, _)) => after
    case Left(fs)          => fail(s"refused: $fs")

  private lazy val first = proposing.headOption.getOrElse(fail("no proposal"))
  private lazy val second = proposing.lift(1).getOrElse(fail("one proposal"))
  private lazy val cert =
    registered(proposing.flatMap((tx, _) => proposalsOf(tx)).map(returnAccount).distinct)

  test("the chain's two proposals change Dijkstra's endorser-block limits, keys 45 to 48") {
    assertEquals(proposing.size, 2)
    val updates = proposing
      .flatMap((tx, _) => proposalsOf(tx))
      .map(_.pProcGovAction match
        case GovAction.ParameterChange(parent, update, guardrails) => (parent, update, guardrails)
        case other => fail(s"not a parameter change: $other"))
    assertEquals(updates.size, 2)
    // the second names the first, the first's only action, as its parent
    assertEquals(
      updates.map(_._1),
      Vector(None, Some(GovPurposeId(GovActionId(idOf(first._1).bytes, 0))))
    )
    assertEquals(updates.map(_._3), Vector(constitution, constitution))
    // the first sets keys 45 and 46, the second 47 and 48, each of the network and security groups
    // (`dppMaxEndorserBlockReferencesSize`, `dppMaxEndorserBlockTxsSize`,
    // `dppMaxEndorserBlockExUnits`, `dppMaxRefScriptSizePerEndorserBlock`,
    // `Dijkstra/PParams.hs:258-269`, cardano-ledger 9df9f98c), and no other field
    val netSec = scala.collection.immutable.SortedSet(
      PPGroups(flat.DRepGroup.Network, flat.StakePoolGroup.Security)
    )
    val none = scala.collection.immutable.SortedSet.empty[PPGroups]
    def named(update: PParamsUpdate[Dijkstra]) =
      val u = update.hkd
      Vector(
        THKD.ppGroup(u.maxEndorserBlockReferencesSize),
        THKD.ppGroup(u.maxEndorserBlockTxsSize),
        THKD.ppGroup(u.maxEndorserBlockExUnits),
        THKD.ppGroup(u.maxRefScriptSizePerEndorserBlock)
      )
    assertEquals(
      updates.map((_, update, _) => named(update)),
      Vector(Vector(netSec, netSec, none, none), Vector(none, none, netSec, netSec))
    )
    updates.foreach { (_, update, _) =>
      assertEquals(summon[ConwayEraPParams[Dijkstra]].modifiedPPGroups(update), netSec)
      assert(
        summon[ConwayEraPParams[Dijkstra]].ppuWellFormed(ProtVer(Version.lit(12), minor0), update)
      )
    }
    // the chain's sample holds no vote at the top level
    assertEquals(
      chainContextCases.count(j =>
        b.votingProcedures[TxLevel.Top](ok(DijkstraTx.inBlock(node(hex(str(j, "bytes"))))).body)
          .unVotingProcedures
          .nonEmpty
      ),
      0
    )
  }

  test("the chain's proposals are admitted in sequence, the second the child of the first") {
    val (tx1, slot1) = first
    val (tx2, slot2) = second
    val after1 = admitted(envOf(tx1, slot1, cert), signalOf(tx1), EraGovRun.dijkstraEmpty)
    val id1 = GovActionId(idOf(tx1).bytes, 0)
    val gas1 = Proposals.proposalsActionsMap(after1).getOrElse(id1, fail("not stored"))
    // proposed in the epoch of its slot, expiring the genesis's lifetime after
    val epoch1 = ok(EpochNo.fromBigInt(slot1 / epochSize))
    assertEquals(gas1.gasProposedIn, epoch1)
    assertEquals(gas1.gasExpiresAfter, ok(EpochNo.fromBigInt(slot1 / epochSize + 120)))
    assertEquals(gas1.gasProposalProcedure, proposalsOf(tx1).head)
    val after2 = admitted(envOf(tx2, slot2, cert), signalOf(tx2), after1)
    val id2 = GovActionId(idOf(tx2).bytes, 0)
    assertEquals(Proposals.proposalsActionsMap(after2).keySet, Set(id1, id2))
    val graph = after2.pGraph.grPParamUpdate.unPGraph
    assertEquals(
      graph.get(GovPurposeId(id1)).map(_.peChildren.toSet),
      Some(Set(GovPurposeId[GovActionPurpose.PParamUpdate](id2)))
    )
    assertEquals(
      graph.get(GovPurposeId(id2)).flatMap(_.peParent),
      Some(GovPurposeId[GovActionPurpose.PParamUpdate](id1))
    )
    // and the second on the store where the first was enacted, its id the root
    val enacted =
      Proposals.empty[Dijkstra](GovRelation[Option](Some(GovPurposeId(id1)), None, None, None))
    assertEquals(
      Proposals
        .proposalsActionsMap(admitted(envOf(tx2, slot2, cert), signalOf(tx2), enacted))
        .keySet,
      Set(id2)
    )
  }

  test("each chain proposal is refused as the rule says, with another state or environment") {
    val (tx1, slot1) = first
    val (tx2, slot2) = second
    val p1 = proposalsOf(tx1).head
    val p2 = proposalsOf(tx2).head
    // its parent is neither the root nor a stored action (`proposalsAddAction`)
    assertEquals(
      failures(envOf(tx2, slot2, cert), signalOf(tx2), EraGovRun.dijkstraEmpty),
      Vector(DijkstraGovPredFailure.InvalidPrevGovActionId(p2))
    )
    // another constitution's guardrails script (`checkGuardrailsScriptHash`)
    assertEquals(
      failures(envOf(tx1, slot1, cert, guardrails = None), signalOf(tx1), EraGovRun.dijkstraEmpty),
      Vector(DijkstraGovPredFailure.InvalidGuardrailsScriptHash(constitution, None))
    )
    // another deposit, and the return account not registered, reported in the rule's order and
    // reversed at the root
    val deposit = era(testnetStored.copy(govActionDeposit = BigInt(1)))
    assertEquals(
      failures(
        envOf(tx1, slot1, Empty.certState[Dijkstra], params = deposit, guardrails = constitution),
        signalOf(tx1),
        EraGovRun.dijkstraEmpty
      ),
      Vector(
        DijkstraGovPredFailure.ProposalDepositIncorrect(Mismatch(p1.pProcDeposit, Coin(1))),
        DijkstraGovPredFailure.ProposalReturnAccountDoesNotExist(returnAccount(p1))
      )
    )
  }

  test("SUBGOV is GOV in its newtype: each injection the newtype after GOV's, and its verdicts") {
    // `Dijkstra/Rules/SubGov.hs:50-56` (cardano-ledger 9df9f98c): Conway's failure is SUBGOV's
    // newtype after `conwayToDijkstraGovPredFailure`, Dijkstra's the newtype, on each of the 19
    pairs.foreach { (conway, dijkstra) =>
      assertEquals(
        InjectSubGovFailure.dijkstraConway.injectFailure(conway),
        DijkstraSubGovPredFailure(DijkstraGovPredFailure.conwayToDijkstraGovPredFailure(conway))
      )
      assertEquals(
        InjectSubGovFailure.dijkstraGov.injectFailure(dijkstra),
        DijkstraSubGovPredFailure(dijkstra)
      )
    }
    val identity = DijkstraSubGovPredFailure(pairs.head._2)
    assertEquals(InjectSubGovFailure.dijkstra.injectFailure(identity), identity)
    // GOV's transition at SUBGOV (`SubGov.hs:86`, cardano-ledger 9df9f98c): on the chain's proposals,
    // admitted with GOV's proposals and its events in SUBGOV's, and refused with GOV's failures in
    // SUBGOV's
    val (tx1, slot1) = first
    val (tx2, slot2) = second
    def compare(env: GovEnv[Dijkstra], signal: GovSignal[Dijkstra], state: Proposals[Dijkstra]) =
      (ok(EraGovRun.subGov(env, signal, Network.Testnet, state)), run(env, signal, state)) match
        case (Right((subAfter, subEvents)), Right((after, events))) =>
          assertEquals(subAfter, after)
          assertEquals(subEvents.toVector, events.toVector.map(DijkstraSubGovEvent(_)))
          true
        case (Left(subFs), Left(fs)) =>
          assertEquals(
            subFs.toChain.toVector,
            fs.toChain.toVector.map(DijkstraSubGovPredFailure(_))
          )
          false
        case other => fail(s"$other")
    val after1 = admitted(envOf(tx1, slot1, cert), signalOf(tx1), EraGovRun.dijkstraEmpty)
    assertEquals(
      Vector(
        compare(envOf(tx1, slot1, cert), signalOf(tx1), EraGovRun.dijkstraEmpty),
        compare(envOf(tx2, slot2, cert), signalOf(tx2), after1),
        compare(envOf(tx2, slot2, cert), signalOf(tx2), EraGovRun.dijkstraEmpty),
        compare(envOf(tx1, slot1, cert, guardrails = None), signalOf(tx1), EraGovRun.dijkstraEmpty)
      ),
      Vector(true, true, false, false)
    )
  }

  test("a stake pool and a DRep vote on the chain's first proposal, of the security group") {
    val (tx1, slot1) = first
    val after1 = admitted(envOf(tx1, slot1, cert), signalOf(tx1), EraGovRun.dijkstraEmpty)
    val id1 = idOf(tx1)
    // a DRep registered by Dijkstra's CERTS (the oracle's setup's DRep registration), and a pool by
    // its POOL
    val drep = jsonLines("fixtures/reference/dijkstra-rules/cases.jsonl")
      .find(j => str(j, "name") == "gov/info")
      .map(j => j.hcursor.get[Vector[String]]("setup").fold(e => fail(e.message), identity))
      .getOrElse(fail("no setup"))
      .lastOption
      .map(h => Vector(eraCert(hex(h))))
      .getOrElse(fail("no DRep registration"))
    val withDRep = ok(
      EraCertsRun.dijkstraCerts(
        leios,
        CertsEnv[Dijkstra](
          ok(DijkstraTx.inBlock(node(hex(str(utxowCases.head, "tx"))))),
          pp,
          EpochNo.fromUnsigned(1L),
          None,
          Map.empty
        ),
        cert,
        drep
      )
    ) match
      case Right((s, _)) => s
      case Left(fs)      => fail(s"the setup does not pass: $fs")
    val operator = Bytes(Vector.fill(28)(8.toByte))
    val poolParams = Build.stakePoolParams(
      flat.PoolParams(
        operator,
        Bytes(Vector.fill(32)(8.toByte)),
        BigInt(0),
        BigInt(340000000),
        Rational(1, 100),
        flat.RewardAccount(0, flatKey),
        Set.empty,
        Bytes.empty,
        None
      ),
      PoolParamsBlsKey.Leios(None)
    )
    val withPool = ok(
      EraCertsRun.dijkstraPool(
        leios,
        PoolEnv[Dijkstra](EpochNo.fromUnsigned(1L), pp),
        c.certPStateL.get(withDRep),
        PoolCert.RegPool(poolParams)
      )
    ) match
      case Right((ps, _)) => c.certPStateL.replace(ps)(withDRep)
      case Left(fs)       => fail(s"not registered: $fs")
    // `{2: drep 02.., 4: pool 08..}`, each voting yes on the first proposal
    val action = s"a18258 20${id1.bytes.hex}008201f6".replace(" ", "")
    val votes = eraVotes(
      hex(
        s"a28202581c${"02" * 28}$action" + s"8204581c${"08" * 28}$action"
      )
    )
    val env = envOf(tx1, slot1, withPool)
    val signal = GovSignal[Dijkstra](votes, OSet.empty, Vector.empty)
    val after = admitted(env, signal, after1)
    val gas = Proposals
      .proposalsActionsMap(after)
      .getOrElse(GovActionId(id1.bytes, 0), fail("not stored"))
    assertEquals(gas.gasStakePoolVotes.keySet, Set(operator))
    assertEquals(
      gas.gasDRepVotes.keySet,
      Set(flat.Credential.Key(Bytes(Vector.fill(28)(2.toByte))))
    )
  }

  test("an empty parameter change is malformed, and its failure has no flat form") {
    val tx = leiosCorpus
      .find(j => str(j, "kind") == "tx" && str(j, "name") == "tx parameter change empty")
      .flatMap(j => cbor(hex(str(j, "bytes"))))
      .map(n => ok(DijkstraTx.inBlock(n)))
      .getOrElse(fail("not in the corpus"))
    val p = proposalsOf(tx).headOption.getOrElse(fail("no proposal"))
    val cert = registered(Vector(returnAccount(p)))
    val env = GovEnv[Dijkstra](
      idOf(tx),
      EpochNo.fromUnsigned(1L),
      era(testnetStored.copy(govActionDeposit = p.pProcDeposit.value)),
      p.pProcGovAction match
        case GovAction.ParameterChange(_, _, g) => g
        case other                              => fail(s"not a parameter change: $other")
      ,
      cert,
      None
    )
    val signal = signalOf(tx)
    val fs = failures(env, signal, EraGovRun.dijkstraEmpty)
    // the update is malformed (`actionWellFormed`), then the corpus's return account is of the
    // main network (`ProposalProcedureNetworkIdMismatch`): the rule's order, reversed at the root
    val network = DijkstraGovPredFailure.ProposalProcedureNetworkIdMismatch[Dijkstra](
      returnAccount(p),
      Network.Testnet
    )
    assertEquals(returnAccount(p).networkId, Network.Mainnet)
    assertEquals(fs, Vector(network, DijkstraGovPredFailure.MalformedProposal(p.pProcGovAction)))
    // the flat Conway action cannot hold Dijkstra's update
    assertEquals(
      fs.map(EraGovRun.dijkstraStored(signal, _)),
      Vector(
        Right(
          flat.GovFailure.ProposalProcedureNetworkIdMismatch(
            Convert.rewardAccountBack(returnAccount(p)),
            Network.Testnet.wire
          )
        ),
        Left(GovActionNotRead(p.pProcGovAction))
      )
    )
    assertEquals(
      EraGovRun.dijkstraStored(signal, DijkstraGovPredFailure.InvalidPrevGovActionId(p)),
      Left(GovActionNotRead(p.pProcGovAction))
    )
  }
