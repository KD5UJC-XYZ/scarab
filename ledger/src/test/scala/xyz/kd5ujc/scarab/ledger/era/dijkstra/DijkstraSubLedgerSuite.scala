// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{Chain, NonEmptySet}
import java.time.Instant
import munit.FunSuite
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger as flat
import xyz.kd5ujc.scarab.ledger.dijkstra.DijkstraTxIn
import xyz.kd5ujc.scarab.ledger.{
  EpochGeometry,
  GovActionId,
  Horizon,
  Mismatch,
  PreprodDeepTx,
  TestGeometry,
  TestLedger,
  TxEnv,
  TxIn,
  Utxo,
  Verdicts
}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraCorpus.*
import xyz.kd5ujc.scarab.ledger.era.rules.*
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Accounts,
  CertState,
  ChainAccountState,
  Coin,
  CompactCoin,
  Credential,
  DState,
  Dijkstra,
  EpochNo,
  Hash32,
  LedgerState,
  Network,
  Staking,
  UTxOState,
  UtxoTables
}
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, ScarabEvaluator}
import xyz.kd5ujc.scarab.protocol.ReferenceBuild

/** Dijkstra's SUBLEDGERS, SUBLEDGER and SUBGOV on the era types (`DijkstraSubLedgers`,
  * `DijkstraSubLedger`, `DijkstraSUBGOV`; cardano-ledger 9df9f98c
  * `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/{SubLedgers,SubLedger,SubGov}.hs`), where
  * the flat rules cannot judge (they halt on a sub-transaction):
  *   - the 28 batches of the public Leios testnet's chain (`fixtures/reference/
  *     leios-testnet-chain-contexts`, recorded with the outputs they spend; the testnet's ledger
  *     accepted each), 38 sub-transactions: SUBLEDGERS from the recorded outputs and the empty
  *     certificate state refuses the batches whose sub-transactions withdraw, their accounts
  *     missing, and admits the others; from that state with the withdrawn accounts registered, it
  *     admits all 28, its state asserted (the outputs, the instant stake, the pots, the whole
  *     certificate state and the proposals) and its events, in order, SUBGOV's by the
  *     sub-transaction's id and SUBUTXOW's; then Dijkstra's UTXOW admits each top level on the
  *     state SUBLEDGERS leaves, with the pools after it and the certificate state before the
  *     transaction (`Ledger.hs:476-485`). A component check: the chain's certificate state and
  *     proposals are not recorded;
  *   - the treasury's check, first and whatever the top level's flag, and SUBUTXOW's inputs under
  *     either flag; a top level flagged invalid skipping SUBENTITIES and SUBGOV; a proposal in a
  *     sub-transaction that registers its return account, which SUBGOV adds by the
  *     sub-transaction's id on the certificate state after SUBENTITIES, or refuses in its newtype;
  *     the epoch, the environment's or the slot's on the testnet's geometry; a committee update one
  *     sub-transaction proposes, which the next one's authorisation of a member reads through
  *     SUBENTITIES; the state threaded from one sub-transaction to the next and the order of the
  *     failures, on sub-transactions given twice; SUBLEDGERS on a small stack (STK-3);
  *   - the injections, which the reference leaves partial (`conwayToDijkstraSubLedgerPredFailure`,
  *     `SubLedger.hs:373-394`), on built values against the reference's functions, and none for the
  *     constructors it calls impossible or Conway's UTXOW failure: it does not compile.
  *
  * Incomplete (CNF-2), not run here:
  *   - the batches as the reference judged them: the fixture records neither the chain's
  *     certificate state nor its proposals; the withdrawn accounts registered here are the least
  *     state the withdrawals need, not the chain's;
  *   - SUBUTXOW given the certificate state from before SUBENTITIES, threaded from the earlier
  *     sub-transactions: no check of SUBUTXOW or SUBUTXO reads it at 9df9f98c (`getWitsVKeyNeeded _ =
  *     getConwayWitsVKeyNeeded`, `Dijkstra/UTxO.hs:161`; `sueCertState` is read nowhere else), so
  *     no verdict can show it;
  *   - any refusal of SUBLEDGER, SUBLEDGERS or SUBGOV by the reference: no recording holds one, so
  *     the refusals here are the rules' on crafted states and sub-transactions;
  *   - the comparison of the failures with the reference's bytes: the era failures have no encoder
  *     yet (#796), and the flat failures have no form for them.
  */
class DijkstraSubLedgerSuite extends FunSuite:
  private given BothLevels[TxLevel.Top] = BothLevels.Top
  private val bodies = EraTxBody.dijkstra
  private val txs = EraTx.dijkstra
  private val utxos = EraUTxO.dijkstra
  private val c = EraCertState.dijkstra
  private given ConwayEraAccounts[Dijkstra] = c.accountsOf

  private def ok[A](e: Either[?, A]): A = e.fold(v => fail(s"$v"), identity)

  /** The Leios build's globals, on the testnet's geometry as the fixture records it: its system
    * start, slot length and epoch size, by which the epoch of a slot is reckoned where the
    * environment gives none.
    */
  private lazy val leios: Globals =
    TestLedger.globals.copy(
      ledger = TestLedger.globals.ledger.copy(referenceBuild = ReferenceBuild.LeiosB004fe0c),
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

  private val fixture = json("fixtures/reference/leios-testnet-chain-contexts/cases.json")

  /** The testnet's parameters as its genesis files give them, as `DijkstraSubUtxowSuite` reads
    * them, with the cost models the fixture records in force on the chain.
    */
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

  private lazy val systemStart: BigInt =
    BigInt(Instant.parse(str(fixture, "systemStart")).getEpochSecond)

  private lazy val slotLength: BigInt =
    fixture.hcursor.get[BigInt]("slotLength").fold(e => fail(e.message), identity)

  private lazy val epochSize: BigInt =
    fixture.hcursor.get[BigInt]("epochSize").fold(e => fail(e.message), identity)

  private def clock(slot: BigInt): Either[flat.TranslationError, BigInt] =
    Right((systemStart + slot * slotLength) * 1000)

  private lazy val history: Horizon =
    val params = Horizon.Params(epochSize, slotLength * 1000, BigInt(10).pow(9), BigInt(10).pow(9))
    Horizon
      .History(Vector.empty, Horizon.Edge(0, 0, 0), params, Some(params))
      .at(Horizon.Transition.Unknown(Some(BigInt(0))))

  private def context(validation: TxEnv.Validation) =
    DijkstraUtxoContext(Network.Testnet, systemStart * 1000, history, validation)

  private def read(bytes: Bytes, c: io.circe.Json): (DijkstraTx[TxLevel.Top], UTxO[Dijkstra]) =
    val tx = ok(DijkstraTx.inBlock(node(bytes)))
    val utxo = c.hcursor
      .get[Vector[(String, String)]]("utxo")
      .fold(e => fail(e.message), identity)
      .map((i, o) =>
        txIn(ok(DijkstraTxIn.decode(node(hex(i))))) -> ok(DijkstraTxOut.decode(node(hex(o))))
      )
    (tx, UTxO[Dijkstra](utxo.toMap))

  private lazy val start: LedgerState[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.state.Build.dijkstra(EpochNo.fromUnsigned(0L), stored).es.ls

  private def storedState(utxo: UTxO[Dijkstra]): UTxOState[Dijkstra] =
    start.utxo.copy[Dijkstra](
      utxo = UtxoTables(Utxo(utxo.unUTxO.view.mapValues(StoredTxOut.dijkstra.toStored).toMap)),
      instantStake = ok(EraStake.dijkstra.addInstantStake(utxo, start.utxo.instantStake))
    )

  private def slotOf(c: io.circe.Json): BigInt =
    c.hcursor.get[BigInt]("slot").fold(e => fail(e.message), identity)

  private val noProposals: Proposals[Dijkstra] =
    Proposals.empty[Dijkstra](GovRelation[Option](None, None, None, None))

  /** A batch read and annotated, as `mkDijkstraStAnnTopTx` makes it with an empty cache. */
  private final case class Batch(
      name: String,
      slot: BigInt,
      tx: DijkstraTx[TxLevel.Top],
      utxo: UTxO[Dijkstra],
      top: DijkstraStAnnTx.TopTx,
      subs: List[DijkstraStAnnTx[TxLevel.Sub]]
  )

  private def batchOf(c: io.circe.Json, bytes: Bytes): Batch =
    val (tx, utxo) = read(bytes, c)
    val top = DijkstraStAnnTx.mkDijkstraStAnnTopTx(clock, pp, utxo, Map.empty, tx)
    Batch(str(c, "name"), slotOf(c), tx, utxo, top, utxos.subTransactionsStAnnTx(top))

  private lazy val batches: Vector[(io.circe.Json, Batch)] =
    chainContextCases
      .map(c => c -> batchOf(c, hex(str(c, "bytes"))))
      .filter(_._2.subs.nonEmpty)

  private def subTx(sub: DijkstraStAnnTx[TxLevel.Sub]): Tx[TxLevel.Sub, Dijkstra] =
    txs.txStAnnTx[TxLevel.Sub](sub)(using BothLevels.Sub)

  private def subBody(sub: DijkstraStAnnTx[TxLevel.Sub]): TxBody[TxLevel.Sub, Dijkstra] =
    txs.body[TxLevel.Sub](subTx(sub))(using BothLevels.Sub)

  private def withdrawalsOf(sub: DijkstraStAnnTx[TxLevel.Sub]): SortedMap[AccountAddress, Coin] =
    ok(ENTITIES.keyed(bodies.withdrawals[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)))

  private def certsOf(sub: DijkstraStAnnTx[TxLevel.Sub]): Vector[DijkstraTxCert] =
    bodies.certs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)

  private def inputsOf(sub: DijkstraStAnnTx[TxLevel.Sub]): Set[TxIn] =
    bodies.inputs[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)

  private def outputsOf(sub: DijkstraStAnnTx[TxLevel.Sub]): UTxO[Dijkstra] =
    UTxOs.txouts[Dijkstra, TxLevel.Sub](subBody(sub))(using bodies, BothLevels.Sub)

  private def subId(sub: DijkstraStAnnTx[TxLevel.Sub]): Hash32 =
    ok(Hash32.fromBytes(bodies.id[TxLevel.Sub](subBody(sub))(using BothLevels.Sub)))

  private def accountsOf(state: CertState[Dijkstra]): Accounts[Dijkstra] =
    c.certDStateL.get(state).accounts

  private def balanceOf(cred: Credential[Staking], accounts: Accounts[Dijkstra]): Option[Coin] =
    EraAccounts
      .lookupAccountState[Dijkstra](cred, accounts)
      .map(summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL.get(_).toCoin)

  /** `state` with `cred` 's account registered with a deposit of 2 ada and `balance`. */
  private def registered(cred: Credential[Staking], balance: BigInt)(
      state: CertState[Dijkstra]
  ): CertState[Dijkstra] =
    c.certDStateL
      .andThen(DState.accounts[Accounts[Dijkstra]])
      .modify { accounts =>
        val added = ConwayEraAccounts.registerConwayAccount[Dijkstra](
          cred,
          ok(CompactCoin.fromBigInt(BigInt(2000000))),
          None,
          accounts
        )
        EraAccounts.adjustAccountState[Dijkstra](
          summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL
            .replace(ok(CompactCoin.fromBigInt(balance))),
          cred,
          added
        )
      }(state)

  private def envOf(
      b: Batch,
      original: CertState[Dijkstra],
      flag: IsPhase2Valid = IsPhase2Valid.Phase2Valid,
      treasury: Coin = Coin(0)
  ): SubLedgerEnv[Dijkstra] =
    SubLedgerEnv[Dijkstra](
      b.slot,
      Some(ok(EpochNo.fromBigInt(b.slot / epochSize))),
      pp,
      ChainAccountState(treasury, Coin(0)),
      b.utxo,
      accountsOf(original),
      flag
    )

  private def subLedgers(
      b: Batch,
      cert: CertState[Dijkstra],
      subs: List[DijkstraStAnnTx[TxLevel.Sub]],
      validation: TxEnv.Validation = TxEnv.Validation.All
  ) =
    DijkstraSubLedgerRun.subLedgers(
      leios,
      envOf(b, cert),
      subs,
      context(validation),
      LedgerState(cert, storedState(b.utxo)),
      noProposals,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  private def subLedger(
      env: SubLedgerEnv[Dijkstra],
      sub: DijkstraStAnnTx[TxLevel.Sub],
      state: LedgerState[Dijkstra],
      validation: TxEnv.Validation = TxEnv.Validation.All
  ) =
    DijkstraSubLedgerRun.subLedger(
      leios,
      env,
      sub,
      context(validation),
      state,
      noProposals,
      Verdicts.Direct,
      ScarabEvaluator.defaultDecoder
    )

  private def inSubLedgers(f: DijkstraSubLedgerPredFailure[Dijkstra]) =
    DijkstraSubLedgersPredFailure.SubLedgerFailure[Dijkstra](f)

  private def inputsFailure(sub: DijkstraStAnnTx[TxLevel.Sub]) =
    DijkstraSubLedgerPredFailure.SubUtxowFailure[Dijkstra](
      DijkstraSubUtxowPredFailure.SubUtxoFailure(
        DijkstraSubUtxoPredFailure.SubBadInputsUTxO(
          NonEmptySet.fromSet(SortedSet.from(inputsOf(sub))).getOrElse(fail("no input"))
        )
      )
    )

  // ---- the testnet's chain -----------------------------------------------------------------------

  test(
    "the chain's 38 sub-transactions: 3 withdraw, 2 register an account, none votes or proposes"
  ) {
    assertEquals(batches.size, 28)
    val subs = batches.flatMap(_._2.subs)
    assertEquals(subs.size, 38)
    def count(p: TxBody[TxLevel.Sub, Dijkstra] => Boolean) = subs.count(s => p(subBody(s)))
    given BothLevels[TxLevel.Sub] = BothLevels.Sub
    assertEquals(
      Vector(
        count(bodies.withdrawals[TxLevel.Sub](_).nonEmpty),
        count(bodies.certs[TxLevel.Sub](_).nonEmpty),
        count(bodies.currentTreasuryValue[TxLevel.Sub](_).nonEmpty),
        count(bodies.votingProcedures[TxLevel.Sub](_).unVotingProcedures.nonEmpty),
        count(bodies.proposalProcedures[TxLevel.Sub](_).size > 0),
        count(bodies.directDeposits[TxLevel.Sub](_).nonEmpty),
        count(bodies.accountBalanceIntervals[TxLevel.Sub](_).nonEmpty),
        count(bodies.treasuryDonation[TxLevel.Sub](_) != Coin(0))
      ),
      Vector(3, 2, 0, 0, 0, 0, 0, 0)
    )
    // the certificates register accounts, and the sub-transactions that hold them withdraw nothing
    assert(
      subs
        .filter(certsOf(_).nonEmpty)
        .forall(s =>
          withdrawalsOf(s).isEmpty && certsOf(s).forall {
            case DijkstraTxCert.DijkstraTxCertDeleg(
                  DijkstraDelegCert.DijkstraRegCert(_, _) |
                  DijkstraDelegCert.DijkstraRegDelegCert(_, _, _)
                ) =>
              true
            case _ => false
          }
        )
    )
  }

  test(
    "SUBLEDGERS from the empty certificate state refuses the batches whose sub-transactions withdraw"
  ) {
    // from the empty state, as the reference did not judge them: each sub-transaction that
    // withdraws is SUBENTITIES' refusal of its accounts, missing from the current and the original
    // accounts; the root's blocks are in the reverse of their order, each SUBLEDGER's in its own
    val empty = start.cert
    val judged = batches.map { (_, b) =>
      val expected = b.subs.reverse.flatMap { sub =>
        val w = withdrawalsOf(sub)
        if w.isEmpty then Nil
        else
          List(
            SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra](w),
            SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal[Dijkstra](w)
          ).map(f => inSubLedgers(DijkstraSubLedgerPredFailure.SubEntitiesFailure(f)))
      }
      subLedgers(b, empty, b.subs) match
        case Right(Left(fs)) =>
          assertEquals(fs.toChain.toList, expected, b.name)
          false
        case Right(Right(_)) =>
          assertEquals(expected, Nil, b.name)
          true
        case Left(defect) => fail(s"${b.name}: $defect")
    }
    assertEquals((judged.count(identity), judged.count(!_)), (25, 3))
  }

  /** The certificate state with each account the batch's sub-transactions withdraw from registered
    * with the amounts they withdraw: the least state the withdrawals need.
    */
  private def withdrawable(b: Batch): CertState[Dijkstra] =
    b.subs
      .flatMap(withdrawalsOf(_).toList)
      .groupMapReduce(_._1.id.credential)(_._2.value)(_ + _)
      .foldLeft(start.cert)((state, cw) => registered(cw._1, cw._2)(state))

  test(
    "SUBLEDGERS admits every batch with the withdrawn accounts registered, and UTXOW the top level"
  ) {
    val refused = batches.flatMap { (_, b) =>
      val before = withdrawable(b)
      val utxoBefore = storedState(b.utxo)
      subLedgers(b, before, b.subs) match
        case Right(Right((after, proposals, events))) =>
          // the outputs every sub-transaction spends and creates, in order; the instant stake moved
          // with them; the fees, deposits and donation as they were
          val (utxo, moved, diffs) =
            b.subs.foldLeft(
              (b.utxo.unUTxO, utxoBefore.instantStake, Vector.empty[DijkstraSubUtxoEvent[Dijkstra]])
            ) { case ((current, stake, told), sub) =>
              val consumed = UTxO[Dijkstra](current.view.filterKeys(inputsOf(sub)).toMap)
              val created = outputsOf(sub)
              (
                current -- inputsOf(sub) ++ created.unUTxO,
                ok(
                  EraStake.dijkstra
                    .addInstantStake(created, stake)
                    .flatMap(EraStake.dijkstra.deleteInstantStake(consumed, _))
                ),
                told :+ DijkstraSubUtxoEvent.TxUTxODiff(consumed, created)
              )
            }
          assertEquals(
            after.utxo.utxo.utxo.toMap,
            storedState(UTxO[Dijkstra](utxo)).utxo.utxo.toMap,
            b.name
          )
          assertEquals(after.utxo.instantStake, moved, b.name)
          assertEquals(
            (after.utxo.fees, after.utxo.deposited, after.utxo.donation, after.utxo.gov),
            (utxoBefore.fees, utxoBefore.deposited, utxoBefore.donation, utxoBefore.gov),
            b.name
          )
          // the certificate state: the accounts withdrawn from drained, those registered added with
          // their deposits and no balance, the rest as it was
          val accountsL = c.certDStateL.andThen(DState.accounts[Accounts[Dijkstra]])
          val drained = b.subs
            .flatMap(withdrawalsOf(_).keys.map(_.id.credential))
            .foldLeft(accountsOf(before))((accounts, cred) =>
              EraAccounts.adjustAccountState[Dijkstra](
                summon[ConwayEraAccounts[Dijkstra]].balanceAccountStateL
                  .replace(ok(CompactCoin.fromBigInt(BigInt(0)))),
                cred,
                accounts
              )
            )
          val registrations = b.subs.flatMap(certsOf)
          val expectedAccounts = registrations.foldLeft(drained) {
            case (
                  accounts,
                  DijkstraTxCert.DijkstraTxCertDeleg(DijkstraDelegCert.DijkstraRegCert(cred, d))
                ) =>
              assertEquals(d, Coin(2000000), b.name)
              ConwayEraAccounts.registerConwayAccount[Dijkstra](
                cred,
                ok(CompactCoin.fromCoin(d)),
                None,
                accounts
              )
            case (_, other) => fail(s"${b.name}: not a registration: $other")
          }
          assertEquals(after.cert, accountsL.replace(expectedAccounts)(before), b.name)
          assertEquals(proposals, noProposals, b.name)
          // the events, of each sub-transaction in order: SUBENTITIES' (none: no pool), SUBGOV's by
          // the sub-transaction's id, with no proposal and no vote, then SUBUTXOW's outputs
          assertEquals(
            events.toVector,
            b.subs.toVector.zip(diffs).flatMap { (sub, diff) =>
              Vector[DijkstraSubLedgerEvent[Dijkstra]](
                DijkstraSubLedgerEvent.SubGovEvent(
                  DijkstraSubGovEvent(ConwayGovEvent.GovNewProposals(subId(sub), noProposals))
                ),
                DijkstraSubLedgerEvent.SubGovEvent(
                  DijkstraSubGovEvent(
                    ConwayGovEvent.GovRemovedVotes(
                      subId(sub),
                      SortedSet.empty(using
                        Ordering.Tuple2(using flat.Voter.ordering, GovActionId.ordering)
                      ),
                      SortedSet.empty(using
                        cats
                          .Order[xyz.kd5ujc.scarab.ledger.state.Credential[
                            xyz.kd5ujc.scarab.ledger.state.DRepRole
                          ]]
                          .toOrdering
                      )
                    )
                  )
                ),
                DijkstraSubLedgerEvent.SubUtxowEvent(DijkstraSubUtxowEvent.SubUtxo(diff))
              ).map(DijkstraSubLedgersEvent.SubLedgerEvent[Dijkstra](_))
            },
            b.name
          )
          // UTXOW on the top level, with the pools after SUBLEDGERS and the certificate state before
          // the transaction (`Dijkstra/Rules/Ledger.hs:476-485`, cardano-ledger 9df9f98c)
          DijkstraUtxoRun.run(
            DijkstraUtxoEnv[Dijkstra](b.slot, pp, c.certPStateL.get(after.cert), before, b.utxo),
            b.top,
            context(TxEnv.Validation.All),
            after.utxo,
            ScarabEvaluator.default,
            Verdicts.Direct,
            ScarabEvaluator.defaultDecoder
          ) match
            case Right(Right((top, _))) =>
              val levels = bodies.inputs[TxLevel.Top](b.tx.body) ++ b.subs.flatMap(inputsOf)
              val created = UTxOs.txouts[Dijkstra, TxLevel.Top](b.tx.body).unUTxO.keySet ++
                b.subs.flatMap(outputsOf(_).unUTxO.keySet)
              assertEquals(levels.filter(top.utxo.utxo.contains), Set.empty[TxIn], b.name)
              assertEquals(created.filterNot(top.utxo.utxo.contains), Set.empty[TxIn], b.name)
              None
            case other => Some(s"${b.name}: the top level: $other")
        case other => Some(s"${b.name}: $other")
    }
    assertEquals(refused, Vector.empty[String])
  }

  // ---- crafted cases -----------------------------------------------------------------------------

  /** A sub-transaction given twice: the state threaded from the first to the second, which
    * SUBENTITIES and SUBUTXOW refuse in the order SUBLEDGER runs them.
    */
  test(
    "SUBLEDGERS threads the state: a registering sub-transaction given twice is refused the second time"
  ) {
    val (b, sub) = batches.iterator
      .flatMap((_, b) => b.subs.find(certsOf(_).nonEmpty).map(b -> _))
      .nextOption()
      .getOrElse(fail("no sub-transaction registers"))
    val cred = certsOf(sub) match
      case Vector(DijkstraTxCert.DijkstraTxCertDeleg(d)) => d.credential
      case other                                         => fail(s"not one registration: $other")
    val once = subLedgers(b, start.cert, List(sub)) match
      case Right(Right((after, _, _))) => after
      case other                       => fail(s"$other")
    assertEquals(balanceOf(cred, accountsOf(once.cert)), Some(Coin(0)))
    assertEquals(
      subLedgers(b, start.cert, List(sub, sub)).map(_.left.map(_.toChain.toVector)),
      Right(
        Left(
          Vector(
            inSubLedgers(
              DijkstraSubLedgerPredFailure.SubEntitiesFailure(
                SubEntitiesPredFailure.SubCertsFailure(
                  DijkstraSubCertsPredFailure[Dijkstra](
                    DijkstraSubCertPredFailure.SubDelegFailure(
                      DijkstraSubDelegPredFailure(
                        ConwayDelegPredFailure.StakeKeyRegisteredDELEG(cred)
                      )
                    )
                  )
                )
              )
            ),
            inSubLedgers(inputsFailure(sub))
          )
        )
      )
    )
  }

  /** The first batch whose sub-transaction `index` has no redeemers: re-encoded, its witnesses'
    * bytes change, and the integrity hash is over them as they arrived.
    */
  private lazy val plainHost: (io.circe.Json, Batch, Int) =
    batches.iterator
      .flatMap((c, b) =>
        b.subs.zipWithIndex.collectFirst {
          case (s, i)
              if txs.witsOf
                .rdmrsTxWits(txs.wits[TxLevel.Sub](subTx(s))(using BothLevels.Sub))
                .entries
                .isEmpty && withdrawalsOf(s).isEmpty && certsOf(s).isEmpty =>
            (c, b, i)
        }
      )
      .nextOption()
      .getOrElse(fail("no sub-transaction without redeemers"))

  private def leaf(v: Value): Node = Node(v, Bytes.empty)

  private def isKey(k: Node, key: Int): Boolean = k.value match
    case Value.UInt(n) => n == BigInt(key)
    case _             => false

  /** The batch of `c` with each sub-transaction `edits` names given its fields, each in place of
    * the body's field of its key, encoded again and read.
    */
  private def withSubsFields(c: io.circe.Json, edits: Map[Int, Vector[(Int, Node)]]): Batch =
    def edited(sub: Node, fields: Vector[(Int, Node)]): Node = sub.value match
      case Value.Arr(subBody +: rest) =>
        subBody.value match
          case Value.Map(fs) =>
            val kept = fs.filterNot((k, _) => fields.exists((key, _) => isKey(k, key)))
            val added = fields.map((k, v) => leaf(Value.UInt(k)) -> v)
            leaf(Value.Arr(leaf(Value.Map(kept ++ added)) +: rest))
          case other => fail(s"not a body: $other")
      case other => fail(s"not a sub-transaction: $other")
    def inSubs(subs: Node): Node = subs.value match
      case Value.Tag(tag, inner) => leaf(Value.Tag(tag, inSubs(inner)))
      case Value.Arr(xs) if edits.keys.forall(xs.indices.contains) =>
        leaf(Value.Arr(xs.zipWithIndex.map((x, i) => edits.get(i).fold(x)(edited(x, _)))))
      case other => fail(s"not the sub-transactions $edits: $other")
    val bytes = node(hex(str(c, "bytes"))).value match
      case Value.Arr(txBody +: rest) =>
        txBody.value match
          case Value.Map(fs) =>
            val withSubs = fs.map((k, v) => if isKey(k, 23) then k -> inSubs(v) else k -> v)
            ok(Cbor.encode(Value.Arr(leaf(Value.Map(withSubs)) +: rest)))
          case other => fail(s"not a body: $other")
      case other => fail(s"not a transaction: $other")
    batchOf(c, bytes)

  /** The batch of `c` with its sub-transaction `index` given `fields` ([[withSubsFields]]). */
  private def withSubFields(c: io.circe.Json, index: Int, fields: Vector[(Int, Node)]): Batch =
    withSubsFields(c, Map(index -> fields))

  test(
    "the treasury is checked first and whatever the top level's flag, its failure SUBLEDGER's own"
  ) {
    // a sub-transaction naming a treasury of 100 (body field 21), judged under `NonStatic`: its
    // signature, over the body as it was, is a static check
    val (c, _, index) = plainHost
    val b = withSubFields(c, index, Vector(21 -> leaf(Value.UInt(100))))
    val sub = b.subs(index)
    assertEquals(
      bodies.currentTreasuryValue[TxLevel.Sub](subBody(sub))(using BothLevels.Sub),
      Some(Coin(100))
    )
    val state = LedgerState(start.cert, storedState(b.utxo))
    def judged(treasury: BigInt, flag: IsPhase2Valid, from: LedgerState[Dijkstra] = state) =
      subLedger(envOf(b, start.cert, flag, Coin(treasury)), sub, from, TxEnv.Validation.NonStatic)
        .map(_.left.map(_.toChain.toVector))
    val mismatch = DijkstraSubLedgerPredFailure.SubTreasuryValueMismatch[Dijkstra](
      Mismatch(Coin(100), Coin(101))
    )
    assert(judged(100, IsPhase2Valid.Phase2Valid).exists(_.isRight))
    assertEquals(
      judged(101, IsPhase2Valid.Phase2Valid).map(_.map(_ => ())),
      Right(Left(Vector(mismatch)))
    )
    assertEquals(
      judged(101, IsPhase2Valid.Phase2Invalid).map(_.map(_ => ())),
      Right(Left(Vector(mismatch)))
    )
    // its inputs already spent: SUBUTXOW's block, the last recorded, is reported first at the root
    val spent =
      LedgerState[Dijkstra](start.cert, storedState(UTxO[Dijkstra](b.utxo.unUTxO -- inputsOf(sub))))
    assertEquals(
      judged(101, IsPhase2Valid.Phase2Valid, spent).map(_.map(_ => ())),
      Right(Left(Vector(inputsFailure(sub), mismatch)))
    )
    // the treasury the chain's and the top level flagged invalid: SUBUTXOW still checks the inputs
    // (`SubUtxo.hs:211-262`, cardano-ledger 9df9f98c)
    assertEquals(
      judged(100, IsPhase2Valid.Phase2Invalid, spent).map(_.map(_ => ())),
      Right(Left(Vector(inputsFailure(sub))))
    )
  }

  test("a top level flagged invalid skips SUBENTITIES and SUBGOV, and SUBUTXOW leaves the state") {
    val (b, sub) = batches.iterator
      .flatMap((_, b) => b.subs.find(withdrawalsOf(_).nonEmpty).map(b -> _))
      .nextOption()
      .getOrElse(fail("no sub-transaction withdraws"))
    val state = LedgerState(start.cert, storedState(b.utxo))
    val w = withdrawalsOf(sub)
    // flagged valid: SUBENTITIES refuses the accounts it lacks; its block, one level below the
    // root, is in the order it was recorded
    assertEquals(
      subLedger(envOf(b, start.cert), sub, state)
        .map(_.left.map(_.toChain.toVector))
        .map(_.map(_ => ())),
      Right(
        Left(
          Vector(
            SubEntitiesPredFailure.SubWithdrawalAccountsMissingFromOriginal[Dijkstra](w),
            SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra](w)
          ).map(DijkstraSubLedgerPredFailure.SubEntitiesFailure[Dijkstra](_))
        )
      )
    )
    // flagged invalid: neither SUBENTITIES nor SUBGOV runs, and SUBUTXOW's checks pass and change
    // nothing; nothing is told
    subLedger(envOf(b, start.cert, IsPhase2Valid.Phase2Invalid), sub, state) match
      case Right(Right((after, proposals, events))) =>
        assertEquals(after, state)
        assertEquals(proposals, noProposals)
        assertEquals(events, Chain.empty)
      case other => fail(s"$other")
  }

  private def accountNode(cred: Credential[Staking]): Node =
    val account = Convert.back(cred) match
      case flat.Credential.Key(h)    => f"${0xe0 | Network.Testnet.wire}%02x${h.hex}"
      case flat.Credential.Script(h) => f"${0xf0 | Network.Testnet.wire}%02x${h.hex}"
    leaf(Value.ByteString(hex(account)))

  /** A proposal of `action`, an information action by default, with `deposit` returned to
    * `returnTo`.
    */
  private def proposalNode(
      deposit: BigInt,
      returnTo: Credential[Staking],
      action: Node = leaf(Value.Arr(Vector(leaf(Value.UInt(6)))))
  ): Node =
    val anchor = leaf(
      Value.Arr(
        Vector(
          leaf(Value.Text("https://example.invalid/a")),
          leaf(Value.ByteString(Bytes(Vector.fill(32)(0x44.toByte))))
        )
      )
    )
    leaf(
      Value.Arr(
        Vector(
          leaf(Value.UInt(deposit)),
          accountNode(returnTo),
          action,
          anchor
        )
      )
    )

  /** A script's credential whose account a proposal's deposit returns to. */
  private lazy val returnScript: Bytes = Bytes(Vector.fill(28)(0x76.toByte))
  private lazy val returnTo: Credential[Staking] =
    ok(Convert.credential[Staking](flat.Credential.Script(returnScript)))

  /** The first plain sub-transaction proposing an information action with `amount`, and when
    * `registers` registering the account the deposit returns to (body fields 20 and 4), from the
    * state without it, under `NonStatic`, as its bodies are encoded again: its environment `adjust`
    * ed, and the sub-transaction.
    */
  private def proposing(
      amount: BigInt,
      registers: Boolean,
      flag: IsPhase2Valid = IsPhase2Valid.Phase2Valid,
      adjust: SubLedgerEnv[Dijkstra] => SubLedgerEnv[Dijkstra] = identity
  ) =
    val (c, _, index) = plainHost
    val registration = leaf(
      Value.Arr(
        Vector(
          leaf(
            Value.Arr(
              Vector(
                leaf(Value.UInt(7)),
                leaf(Value.Arr(Vector(leaf(Value.UInt(1)), leaf(Value.ByteString(returnScript))))),
                leaf(Value.UInt(stored.keyDeposit))
              )
            )
          )
        )
      )
    )
    val b = withSubFields(
      c,
      index,
      Vector(20 -> leaf(Value.Arr(Vector(proposalNode(amount, returnTo))))) ++
        Option.when(registers)(4 -> registration)
    )
    val sub = b.subs(index)
    (
      b,
      sub,
      subLedger(
        adjust(envOf(b, start.cert, flag)),
        sub,
        LedgerState(start.cert, storedState(b.utxo)),
        TxEnv.Validation.NonStatic
      )
    )

  test("SUBGOV reads the certificate state after SUBENTITIES, and adds a proposal by its id") {
    // the sub-transaction registers the account its proposal's deposit returns to: GOV finds it, as
    // it reads the certificate state SUBENTITIES left (`SubLedger.hs:265-296`, cardano-ledger
    // 9df9f98c)
    val deposit = stored.govActionDeposit
    val (b, sub, admitted) = proposing(deposit, registers = true)
    admitted match
      case Right(Right((after, proposals, events))) =>
        assertEquals(
          Proposals.proposalsActionsMap(proposals).keySet,
          Set(GovActionId(subId(sub).bytes, 0))
        )
        assertEquals(balanceOf(returnTo, accountsOf(after.cert)), Some(Coin(0)))
        assert(
          events.exists {
            case DijkstraSubLedgerEvent.SubGovEvent(
                  DijkstraSubGovEvent(ConwayGovEvent.GovNewProposals(id, ps))
                ) =>
              id == subId(sub) && ps == proposals
            case _ => false
          },
          events.toString
        )
      case other => fail(s"${b.name}: $other")
    val returnAccount =
      ok(Convert.accountAddress(flat.RewardAccount(0, flat.Credential.Script(returnScript))))
    def refused(f: DijkstraGovPredFailure[Dijkstra]) =
      Right(
        Left(
          Vector(DijkstraSubLedgerPredFailure.SubGovFailure[Dijkstra](DijkstraSubGovPredFailure(f)))
        )
      )
    // without the registration, the account does not exist: GOV's refusal, Dijkstra's by GOV's
    // injection, in SUBGOV's newtype
    assertEquals(
      proposing(deposit, registers = false)._3
        .map(_.left.map(_.toChain.toVector))
        .map(_.map(_ => ())),
      refused(DijkstraGovPredFailure.ProposalReturnAccountDoesNotExist(returnAccount))
    )
    // another deposit
    assertEquals(
      proposing(deposit + 1, registers = true)._3
        .map(_.left.map(_.toChain.toVector))
        .map(_.map(_ => ())),
      refused(
        DijkstraGovPredFailure.ProposalDepositIncorrect(Mismatch(Coin(deposit + 1), Coin(deposit)))
      )
    )
    // flagged invalid, neither SUBENTITIES nor SUBGOV runs
    proposing(deposit, registers = true, IsPhase2Valid.Phase2Invalid)._3 match
      case Right(Right((after, proposals, _))) =>
        assertEquals(proposals, noProposals)
        assertEquals(after.cert, start.cert)
      case other => fail(s"$other")
  }

  test("the epoch is the environment's, or the slot's by the geometry of Globals") {
    // the proposal's epoch, which GOV reads (`gasProposedIn`): the slot's on the testnet's 21600-slot
    // epochs where the environment gives none (`epochFromSlot`, `SubLedger.hs:254`, cardano-ledger
    // 9df9f98c), and the environment's when it gives one
    assertEquals(epochSize, BigInt(21600))
    def proposedIn(adjust: SubLedgerEnv[Dijkstra] => SubLedgerEnv[Dijkstra]) =
      proposing(stored.govActionDeposit, registers = true, adjust = adjust)._3 match
        case Right(Right((_, proposals, _))) =>
          Proposals.proposalsActionsMap(proposals).values.map(_.gasProposedIn).toVector
        case other => fail(s"$other")
    val (b, _, _) = proposing(stored.govActionDeposit, registers = true)
    val fromSlot = ok(EpochNo.fromBigInt(b.slot / epochSize))
    assertEquals(proposedIn(_.copy(sleEpochNo = None)), Vector(fromSlot))
    val supplied = EpochNo.fromUnsigned(7L)
    assertNotEquals(supplied, fromSlot)
    assertEquals(proposedIn(_.copy(sleEpochNo = Some(supplied))), Vector(supplied))
  }

  test("SUBLEDGERS judges thousands of sub-transactions on a small stack (STK-3)") {
    // the real fold, its judgment's interpretation and the flattening of its failures, on a thread
    // whose stack is far too small for a recursion over the sub-transactions: one sub-transaction
    // given 2000 times, admitted the first time and refused 1999 times, its inputs spent
    val (_, b, index) = plainHost
    val sub = b.subs(index)
    val judged = PreprodDeepTx.onSmallStack(subLedgers(b, start.cert, List.fill(2000)(sub)))
    assertEquals(
      judged.map(_.left.map(_.toChain.toVector)).map(_.map(_ => ())),
      Right(Left(Vector.fill(1999)(inSubLedgers(inputsFailure(sub)))))
    )
  }

  private def noRedeemers(sub: DijkstraStAnnTx[TxLevel.Sub]): Boolean =
    txs.witsOf.rdmrsTxWits(txs.wits[TxLevel.Sub](subTx(sub))(using BothLevels.Sub)).entries.isEmpty

  /** The first batch whose first two sub-transactions have no redeemers, withdraw nothing, hold no
    * certificate, and the second spends none of the first's outputs.
    */
  private lazy val twoPlain: (io.circe.Json, Batch) =
    batches
      .find((_, b) =>
        b.subs match
          case s1 :: s2 :: _ =>
            List(s1, s2)
              .forall(s => noRedeemers(s) && withdrawalsOf(s).isEmpty && certsOf(s).isEmpty) &&
            inputsOf(s2).intersect(outputsOf(s1).unUTxO.keySet).isEmpty
          case _ => false
      )
      .getOrElse(fail("no batch with two such sub-transactions"))

  test("SUBLEDGERS reports the later sub-transaction's block first at the root") {
    // two sub-transactions given twice: the second time each is refused for its spent inputs, the
    // second's block reported before the first's, as the root's blocks are in the reverse of their
    // order
    val (_, b) = twoPlain
    val (s1, s2) = b.subs match
      case x :: y :: _ => (x, y)
      case _           => fail("not two sub-transactions")
    assertEquals(
      subLedgers(b, start.cert, List(s1, s2, s1, s2))
        .map(_.left.map(_.toChain.toVector))
        .map(_.map(_ => ())),
      Right(Left(Vector(inSubLedgers(inputsFailure(s2)), inSubLedgers(inputsFailure(s1)))))
    )
  }

  test(
    "a committee update one sub-transaction proposes is the next one's to authorise a member for"
  ) {
    // the first sub-transaction proposes a committee update adding a script's cold credential and an
    // information action; the second authorises a hot key for that credential, which GOVCERT
    // admits only for a current member or one a stored committee proposal adds
    // (`Conway/Rules/GovCert.hs`): SUBENTITIES reads the committee's proposals from the proposals
    // SUBGOV left. Under `NonStatic`, as the bodies are encoded again
    val (host, b0) = twoPlain
    val coldHash = Bytes(Vector.fill(28)(0x31.toByte))
    val cold = ok(
      Convert
        .credential[xyz.kd5ujc.scarab.ledger.state.ColdCommittee](flat.Credential.Script(coldHash))
    )
    val returnTo = Credential.KeyHashObj(
      ok(
        xyz.kd5ujc.scarab.ledger.state.KeyHash
          .fromBytes[Staking](Bytes(Vector.fill(28)(0x76.toByte)))
      )
    )
    val coldNode = leaf(Value.Arr(Vector(leaf(Value.UInt(1)), leaf(Value.ByteString(coldHash)))))
    val update = leaf(
      Value.Arr(
        Vector(
          leaf(Value.UInt(4)),
          leaf(Value.Null),
          leaf(Value.Arr(Vector.empty)),
          leaf(Value.Map(Vector(coldNode -> leaf(Value.UInt(100000))))),
          leaf(Value.Tag(30, leaf(Value.Arr(Vector(leaf(Value.UInt(1)), leaf(Value.UInt(2)))))))
        )
      )
    )
    val deposit = stored.govActionDeposit
    val hot = leaf(
      Value.Arr(
        Vector(leaf(Value.UInt(0)), leaf(Value.ByteString(Bytes(Vector.fill(28)(0x32.toByte)))))
      )
    )
    val authorisation =
      leaf(Value.Arr(Vector(leaf(Value.Arr(Vector(leaf(Value.UInt(14)), coldNode, hot))))))
    val b = withSubsFields(
      host,
      Map(
        0 -> Vector(
          20 -> leaf(
            Value.Arr(
              Vector(proposalNode(deposit, returnTo, update), proposalNode(deposit, returnTo))
            )
          )
        ),
        1 -> Vector(4 -> authorisation)
      )
    )
    val (proposer, authoriser) = b.subs match
      case x :: y :: _ => (x, y)
      case _           => fail("not two sub-transactions")
    assertEquals(b0.subs.size, b.subs.size)
    val cert = registered(returnTo, 0)(start.cert)
    def judged(subs: List[DijkstraStAnnTx[TxLevel.Sub]]) =
      DijkstraSubLedgerRun.subLedgers(
        leios,
        envOf(b, cert),
        subs,
        context(TxEnv.Validation.NonStatic),
        LedgerState(cert, storedState(b.utxo)),
        noProposals,
        Verdicts.Direct,
        ScarabEvaluator.defaultDecoder
      )
    val updateId = GovActionId(subId(proposer).bytes, 0)
    judged(List(proposer, authoriser)) match
      case Right(Right((after, proposals, _))) =>
        assertEquals(
          Proposals.proposalsActionsMap(proposals).keySet,
          Set(updateId, GovActionId(subId(proposer).bytes, 1))
        )
        // the committee's purpose: the update alone, by its id, and in the node's record
        assertEquals(
          Proposals.proposalsWithPurpose(GovRelation.grCommitteeL, proposals).keySet,
          Set(GovPurposeId[GovActionPurpose.Committee](updateId))
        )
        val record = DijkstraSubLedger.committeeProposals(proposals)
        assertEquals(record.keySet, Set(updateId))
        assert(
          record.values.forall(_.action match
            case flat.GovAction.UpdateCommittee(None, removed, added, _) =>
              removed.isEmpty && added == Map(flat.Credential.Script(coldHash) -> BigInt(100000))
            case _ => false),
          record.toString
        )
        assertEquals(
          c.certVStateL.get(after.cert).committeeState.creds.get(cold),
          Some(
            xyz.kd5ujc.scarab.ledger.state.CommitteeAuthorization.CommitteeHotCredential(
              ok(
                Convert.credential[xyz.kd5ujc.scarab.ledger.state.HotCommittee](
                  flat.Credential.Key(Bytes(Vector.fill(28)(0x32.toByte)))
                )
              )
            )
          )
        )
      case other => fail(s"$other")
    // without the proposal before it, the credential is unknown: GOVCERT's refusal in SUBGOVCERT's
    // newtype, through SUBCERT, SUBCERTS and SUBENTITIES
    assertEquals(
      judged(List(authoriser)).map(_.left.map(_.toChain.toVector)).map(_.map(_ => ())),
      Right(
        Left(
          Vector(
            inSubLedgers(
              DijkstraSubLedgerPredFailure.SubEntitiesFailure(
                SubEntitiesPredFailure.SubCertsFailure(
                  DijkstraSubCertsPredFailure[Dijkstra](
                    DijkstraSubCertPredFailure.SubGovCertFailure(
                      DijkstraSubGovCertPredFailure(
                        DijkstraGovCertPredFailure.DijkstraCommitteeIsUnknown(cold)
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

  // ---- the injections ----------------------------------------------------------------------------

  test("each injection into SUBLEDGER and SUBLEDGERS equals the reference's composition") {
    val account = ok(
      Convert.accountAddress(
        flat.RewardAccount(0, flat.Credential.Key(Bytes(Vector.fill(28)(4.toByte))))
      )
    )
    val missing = Map(account -> Coin(1))
    val certs = ConwayCertsPredFailure.CertFailure[Dijkstra](
      ConwayCertPredFailure.DelegFailure(ConwayDelegPredFailure.IncorrectDepositDELEG(Coin(1)))
    )
    val gov = DijkstraGovPredFailure.ProposalDepositIncorrect[Dijkstra](Mismatch(Coin(1), Coin(2)))
    val subCerts = DijkstraSUBCERTS.conwayToDijkstraSubCertsPredFailure(certs)
    def sub[F](f: F)(using i: InjectSubLedgerFailure[F, Dijkstra]) = i.injectFailure(f)
    // `conwayToDijkstraSubLedgerPredFailure` (`Dijkstra/Rules/SubLedger.hs:385-391`, cardano-ledger
    // 9df9f98c) on the constructors it maps whose payloads SUBLEDGER's injections take whole
    assertEquals(
      sub(new ConwayLedgerPredFailure.ConwayCertsFailure[Dijkstra](certs)),
      DijkstraSubLedgerPredFailure.SubEntitiesFailure[Dijkstra](
        SubEntitiesPredFailure.SubCertsFailure(subCerts)
      )
    )
    assertEquals(
      sub(new ConwayLedgerPredFailure.ConwayGovFailure[Dijkstra](gov)),
      DijkstraSubLedgerPredFailure.SubGovFailure[Dijkstra](DijkstraSubGovPredFailure(gov))
    )
    assertEquals(
      sub(new ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[Dijkstra](missing)),
      DijkstraSubLedgerPredFailure.SubEntitiesFailure[Dijkstra](
        SubEntitiesPredFailure.SubWithdrawalAccountsMissing(missing)
      )
    )
    assertEquals(
      sub(
        new ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Dijkstra](
          Mismatch(Coin(1), Coin(2))
        )
      ),
      DijkstraSubLedgerPredFailure.SubTreasuryValueMismatch[Dijkstra](Mismatch(Coin(1), Coin(2)))
    )
    // the sub-rules' (`:150-160`)
    val subGov = DijkstraSubGovPredFailure[Dijkstra](gov)
    assertEquals(sub(subGov), DijkstraSubLedgerPredFailure.SubGovFailure[Dijkstra](subGov))
    val utxow = DijkstraSubUtxowPredFailure.SubInvalidMetadata[Dijkstra]()
    assertEquals(sub(utxow), DijkstraSubLedgerPredFailure.SubUtxowFailure[Dijkstra](utxow))
    val entities = SubEntitiesPredFailure.SubWithdrawalAccountsMissing[Dijkstra](missing)
    assertEquals(sub(entities), DijkstraSubLedgerPredFailure.SubEntitiesFailure[Dijkstra](entities))
    assertEquals(
      sub(subCerts),
      DijkstraSubLedgerPredFailure.SubEntitiesFailure[Dijkstra](
        SubEntitiesPredFailure.SubCertsFailure(subCerts)
      )
    )
    val treasury =
      DijkstraSubLedgerPredFailure.SubTreasuryValueMismatch[Dijkstra](Mismatch(Coin(1), Coin(2)))
    assertEquals(sub(treasury), treasury)
    // SUBLEDGERS's (`Dijkstra/Rules/SubLedgers.hs:91-94`, cardano-ledger 9df9f98c)
    assertEquals(
      InjectSubLedgersFailure.dijkstraSubLedger.injectFailure(treasury),
      DijkstraSubLedgersPredFailure.SubLedgerFailure[Dijkstra](treasury)
    )
    assertEquals(
      InjectSubLedgersFailure.dijkstra.injectFailure(inSubLedgers(treasury)),
      inSubLedgers(treasury)
    )
  }

  test(
    "the constructors the reference calls impossible for SUBLEDGER, and UTXOW's, have no injection"
  ) {
    // the control: a constructor SUBLEDGER reaches has an injection, in the same form
    assertEquals(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[Dijkstra], Dijkstra]]"
      ),
      ""
    )
    // the four `conwayToDijkstraSubLedgerPredFailure` calls impossible (`:389, 392-394`), Conway's
    // UTXOW failure, whose payload SUBUTXOW's injection takes in part, and the whole type
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayMempoolFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayIncompleteWithdrawals[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure.ConwayUtxowFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
    assert(
      compileErrors(
        "summon[InjectSubLedgerFailure[ConwayLedgerPredFailure[Dijkstra], Dijkstra]]"
      ).nonEmpty
    )
  }
