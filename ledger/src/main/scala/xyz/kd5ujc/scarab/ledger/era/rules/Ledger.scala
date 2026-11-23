// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyList, NonEmptyMap}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  GovActionState as StoredGovActionState,
  TranslationError,
  LedgerDefect,
  LedgerFailure,
  Proposals as StoredProposals,
  Verdicts
}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.RelEQ
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayProposals, ConwayTxCert, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  AccountId,
  CertState,
  ChainAccountState,
  Coin,
  Conway,
  ConwayGovState,
  Credential,
  EpochNo,
  EraTag,
  GovState,
  Hash32,
  KeyHash,
  LedgerState,
  ScriptHash,
  TxIx,
  UTxOState,
  VocabError,
  Withdrawals
}

// Conway's LEDGER and the LEDGERS it runs in, as the reference writes them (cardano-ledger
// f649f975 `eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Ledger.hs`, Shelley's
// `Rules/Ledger.hs` for the withdrawals' test and `Rules/Ledgers.hs`, which Conway's LEDGERS is),
// each generic in its era over the reference's classes, the sub-rules run as UTXOW runs UTXO, their
// failures and events embedded ([[EmbedInLedger]]).

private def vocabDefect[F[_]: Monad, A](result: Either[VocabError, A])(using
    Raise[F, LedgerDefect]
): F[A] = Sts.lift[F, LedgerDefect, A](result.leftMap(LedgerFailure.Vocab(_)))

/** The events of a sub-rule, which `wrap` puts in the rule's (`wrapEvent`). */
private def toldIn[F[_], G, H](events: Tell[F, Chain[H]])(wrap: G => H): Tell[F, Chain[G]] =
  new Tell[F, Chain[G]]:
    val functor: Functor[F] = events.functor
    def tell(l: Chain[G]): F[Unit] = events.tell(l.map(wrap))

/** `data LedgerEnv era` (`Shelley/Rules/Ledger.hs:97-104`, cardano-ledger f649f975): the slot, the
  * epoch if known, the transaction's index in its block, the parameters and the chain's accounts.
  */
final case class LedgerEnv[E <: EraTag](
    ledgerSlotNo: BigInt,
    ledgerEpochNo: Option[EpochNo],
    ledgerIx: TxIx,
    ledgerPp: PParams[E],
    ledgerAccount: ChainAccountState
)

/** `data ShelleyLedgersEnv era` (`Shelley/Rules/Ledgers.hs:65-70`, cardano-ledger f649f975). */
final case class LedgersEnv[E <: EraTag](
    ledgersSlotNo: BigInt,
    ledgersEpochNo: EpochNo,
    ledgersPp: PParams[E],
    ledgersAccount: ChainAccountState
)

/** How era `E` reads the node's flat record of the proposals into its [[Proposals]] and writes them
  * back, until the governance state holds the reference's `Proposals era` itself (#85): glue, the
  * reference's `proposalsGovStateL`, Conway's only; Dijkstra's governance state is the era's
  * record, whose proposals its LEDGER is given (`DijkstraLedger`). Only this package makes one.
  */
sealed trait ProposalsRecord[E <: EraTag]:
  def read(stored: StoredProposals): Either[ProposalsReadError, Proposals[E]]
  def write(proposals: Proposals[E]): Either[ProposalsWriteError, StoredProposals]

  /** An action's state as the node records it, as the rules below LEDGER read the committee's
    * actions ([[CertsEnv]]).
    */
  def storedAction(gas: GovActionState[E]): StoredGovActionState

object ProposalsRecord:
  given conway: ProposalsRecord[Conway] with
    def read(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
      ConwayProposals.fromStored(stored)
    def write(proposals: Proposals[Conway]): Either[ProposalsWriteError, StoredProposals] =
      Right(ConwayProposals.toStored(proposals))
    def storedAction(gas: GovActionState[Conway]): StoredGovActionState =
      ConwayProposals.storedAction(gas)

  /** The proposals of the ledger state's governance record, read into the era's on every `get` and
    * written back on every `set`: a record the era cannot read is raised (`readError`), and
    * proposals it cannot hold (`writeError`). For a judgment of one transaction, whose LEDGER reads
    * them for CERTS's committee actions and for GOV; a run over many holds them once beside the
    * ledger state instead ([[ConwayBlockRun]]).
    */
  def stateful[F[_]: Monad, E <: EraTag](using
      state: Stateful[F, LedgerState[E]],
      gs: GovState[E] =:= ConwayGovState,
      record: ProposalsRecord[E],
      readError: Raise[F, ProposalsReadError],
      writeError: Raise[F, ProposalsWriteError]
  ): Stateful[F, Proposals[E]] = new Stateful[F, Proposals[E]]:
    val monad: Monad[F] = Monad[F]
    def get: F[Proposals[E]] = state.get.flatMap(ls =>
      record
        .read(gs(ls.utxo.gov).proposals)
        .fold(readError.raise[ProposalsReadError, Proposals[E]], Monad[F].pure)
    )
    def set(ps: Proposals[E]): F[Unit] =
      record
        .write(ps)
        .fold(
          writeError.raise[ProposalsWriteError, Unit],
          stored =>
            state.modify(
              LedgerState
                .utxo[E]
                .andThen(UTxOState.gov[E])
                .modify(g => gs.flip(gs(g).copy(proposals = stored)))
            )
        )

/** Shelley's functions of LEDGER that Conway's runs (`Shelley/Rules/Ledger.hs`, cardano-ledger
  * f649f975).
  */
object ShelleyLedger:
  /** `testIncompleteAndMissingWithdrawals` (`Shelley/Rules/Ledger.hs:341-359`, cardano-ledger
    * f649f975): the withdrawals of an account that is not registered or is on another network, then
    * those that do not take the account's whole balance.
    */
  def testIncompleteAndMissingWithdrawals[F[_]: Monad, E <: EraTag](
      accounts: xyz.kd5ujc.scarab.ledger.state.Accounts[E],
      withdrawals: Withdrawals
  )(using
      a: EraAccounts[E],
      inject: InjectLedgerFailure[ShelleyLedgerPredFailure[E], E],
      ask: Ask[F, Globals],
      judge: Judge[F, LedgerPredFailure[E]]
  ): F[Unit] =
    for
      network <- ask.ask[Globals].map(_.networkId.wire)
      (missingWithdrawals, incompleteWithdrawals) = EraAccounts
        .withdrawalsThatDoNotDrainAccounts[E](withdrawals, network, accounts)
        .fold((Map.empty[AccountAddress, Coin], Map.empty[AccountAddress, Mismatch[RelEQ, Coin]]))(
          (missing, incomplete) => (missing.unWithdrawals, incomplete)
        )
      _ <-
        if missingWithdrawals.isEmpty then Monad[F].unit
        else
          judge.fail(
            inject.injectFailure(
              ShelleyLedgerPredFailure.ShelleyWithdrawalsMissingAccounts(missingWithdrawals)
            )
          )
      _ <- NonEmptyMap
        .fromMap(SortedMap.from(incompleteWithdrawals))
        .traverse_(ms =>
          judge.fail(
            inject.injectFailure(ShelleyLedgerPredFailure.ShelleyIncompleteWithdrawals(ms))
          )
        )
    yield ()

/** Conway's LEDGER (`Conway/Rules/Ledger.hs`, cardano-ledger f649f975). */
object ConwayLedger:
  /** `shelleyToConwayLedgerPredFailure` (`Conway/Rules/Ledger.hs:189-196`, cardano-ledger
    * f649f975): DELEGS's failure, which the reference calls impossible in Conway (`error`), has no
    * value where the era has no DELEGS ([[DelegsPredFailure]]), which `void` witnesses.
    */
  def shelleyToConwayLedgerPredFailure[E <: EraTag](failure: ShelleyLedgerPredFailure[E])(using
      void: DelegsPredFailure[E] =:= Nothing
  ): ConwayLedgerPredFailure[E] = failure match
    case ShelleyLedgerPredFailure.UtxowFailure(x)  => ConwayLedgerPredFailure.ConwayUtxowFailure(x)
    case ShelleyLedgerPredFailure.DelegsFailure(x) => void(x)
    case ShelleyLedgerPredFailure.ShelleyWithdrawalsMissingAccounts(x) =>
      ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(x)
    case ShelleyLedgerPredFailure.ShelleyIncompleteWithdrawals(x) =>
      ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(x)

  /** `validateTreasuryValue` (`Conway/Rules/Ledger.hs:442-454`, cardano-ledger f649f975), of a body
    * of either level: its failure the constructor it reports, so that SUBLEDGER's injection over
    * the constructors it reaches serves it (Dijkstra's `SubLedger.hs:260`, cardano-ledger
    * 9df9f98c).
    */
  def validateTreasuryValue[E <: EraTag, L <: TxLevel](body: TxBody[L, E], treasury: Coin)(using
      b: ConwayEraTxBody[E],
      l: b.Level[L]
  ): Test[ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[E]] =
    b.currentTreasuryValue[L](body)
      .fold(Test.pass[ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[E]])(submitted =>
        Test.failureUnless(
          submitted == treasury,
          new ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[E](Mismatch(submitted, treasury))
        )
      )

  /** `validateRefScriptSize` (`Conway/Rules/Ledger.hs:456-471`, cardano-ledger f649f975): the size
    * and the limit compared as the reference's 64-bit `Int` (`fromIntegral @Word32 @Int`).
    */
  def validateRefScriptSize[E <: EraTag, L <: TxLevel](
      pp: PParams[E],
      utxo: UTxO[E],
      tx: Tx[L, E]
  )(using
      t: EraTx[E],
      b: BabbageEraTxBody[E],
      p: ConwayEraPParams[E],
      l: t.bodyOf.Level[L],
      lb: b.Level[L]
  ): Test[ConwayLedgerPredFailure[E]] =
    val totalRefScriptSize = ConwayUTxO.txNonDistinctRefScriptsSize[E, L](utxo, tx)
    val maxRefScriptSizePerTx = p.ppMaxRefScriptSizePerTxG.get(pp).value.toLong
    Test.failureUnless(
      totalRefScriptSize <= maxRefScriptSizePerTx,
      ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(
        Mismatch(totalRefScriptSize, maxRefScriptSizePerTx)
      )
    )

  /** `validateWithdrawalsDelegated` (`Conway/Rules/Ledger.hs:473-488`, cardano-ledger f649f975):
    * the key hashes of the accounts withdrawn from, in the withdrawals' order (`Map.toList` of
    * `AccountAddress` keys), that do not delegate to a DRep in `accounts`.
    */
  def validateWithdrawalsDelegated[E <: EraTag](
      accounts: xyz.kd5ujc.scarab.ledger.state.Accounts[E],
      withdrawals: Withdrawals
  )(using a: ConwayEraAccounts[E]): Test[ConwayLedgerPredFailure[E]] =
    val wdrlsKeyHashes = withdrawals.unWithdrawals.keys.toVector.collect {
      case AccountAddress(_, AccountId(Credential.KeyHashObj(kh))) => kh
    }
    val nonExistentDelegations = wdrlsKeyHashes.filter(keyHash =>
      EraAccounts
        .lookupAccountState[E](Credential.KeyHashObj(keyHash), accounts)
        .flatMap(a.dRepDelegationAccountStateL.get)
        .isEmpty
    )
    Test.failureOnJust(NonEmptyList.fromList(nonExistentDelegations.toList))(
      ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep(_)
    )

  /** `conwayLedgerTransitionTRC` (`Conway/Rules/Ledger.hs:350-440`, cardano-ledger f649f975). For a
    * transaction whose scripts pass: the treasury value, the reference scripts' size and, after the
    * bootstrap phase, the delegation of the accounts withdrawn from, from the certificate state
    * before the certificates; where the build moves them to LEDGER ([[WithdrawalsRule]]), the
    * withdrawals' test, the DReps' expiries and the accounts drained; CERTS on the certificates;
    * GOV on the proposals and votes with the certificate state after CERTS. Then, for every
    * transaction, UTXOW, with the certificate state before the transaction in its environment, as
    * its refunds are computed from it. Every sub-rule runs whatever was recorded before it.
    *
    * The proposals are the era's, in their own capability beside the ledger state (`proposals`),
    * which GOV judges and leaves and whose committee's actions CERTS reads (`proposalsWithPurpose
    * grCommitteeL`, `:367-370`), each as the node records it ([[ProposalsRecord.storedAction]]):
    * the caller holds them, read from the node's record once for a block ([[ConwayBlockRun]]), or
    * on every read and write for one transaction ([[ProposalsRecord.stateful]]). The epoch is the
    * environment's, or the slot's by the geometry of `Globals` (`epochOf`), which is total, where
    * the reference's `epochFromSlot` (`Slot.hs:66-69`, cardano-ledger f649f975) reads the epoch
    * information of its `Globals` and fails outside a bounded history: the two agree within the
    * history. UTXOW's arguments are UTXOW's ([[BabbageUtxow.babbageUtxowTransition]]).
    */
  def conwayLedgerTransition[F[_]: Monad, E <: EraTag](
      env: LedgerEnv[E],
      stAnnTx: StAnnTx[TxLevel.Top, E],
      context: UtxoContext[E],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      e: EraUTxO[E],
      t: AlonzoEraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E],
      w: AlonzoEraTxWits[E],
      pa: AlonzoEraPParams[E],
      pb: BabbageEraPParams[E],
      pc: ConwayEraPParams[E],
      tc: ConwayEraTxCert[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      v: Value[E] =:= MultiAssetValue,
      u: AlonzoEraUTxO[E],
      sc: ConwayEraScript[E],
      era: Era[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E],
      cached: StAnnTxCache[E] =:= Map[xyz.kd5ujc.scarab.codec.Bytes, SupportedPlutusRunnable[E]],
      cert: TxCert[E] =:= ConwayTxCert,
      gs: GovState[E] =:= ConwayGovState,
      record: ProposalsRecord[E],
      allegra: InjectUtxoFailure[AllegraUtxoPredFailure[E], E],
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E],
      utxos: InjectAlonzoUtxos[E],
      embedUtxos: EmbedUtxosInUtxo[E],
      shelleyW: InjectUtxowFailure[ShelleyUtxowPredFailure[E], E],
      alonzoW: InjectUtxowFailure[AlonzoUtxowPredFailure[E], E],
      babbageW: InjectUtxowFailure[BabbageUtxowPredFailure[E], E],
      embedUtxo: EmbedUtxoInUtxow[E],
      embedCerts: EmbedCertInCerts[E],
      embedCert: EmbedInCert[E],
      injectCerts: InjectCertsFailure[ConwayCertsPredFailure[E], E],
      certsBase: CertsBaseCase[E],
      deleg: InjectDeleg[E],
      pool: InjectPool[E],
      govCert: InjectGovCert[E],
      gov: InjectGov[E],
      injectLedger: InjectLedgerFailure[ConwayLedgerPredFailure[E], E],
      injectShelley: InjectLedgerFailure[ShelleyLedgerPredFailure[E], E],
      embed: EmbedInLedger[E],
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[E]],
      judge: Judge[F, LedgerPredFailure[E]],
      events: Tell[F, Chain[LedgerEvent[E]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      proposals: Stateful[F, Proposals[E]],
      stored: StoredOutputs[F, E]
  ): F[Unit] =
    given acc: ConwayEraAccounts[E] = c.accountsOf
    val top = t.bodyOf.top
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)(using top)
    val txBody = t.body[TxLevel.Top](tx)(using top)
    val pp = env.ledgerPp
    val pv = pc.ppProtocolVersionL.get(pp)
    val certL = LedgerState.cert[E]
    val utxoL = LedgerState.utxo[E]
    val accountsL = c.certDStateL.andThen(
      xyz.kd5ujc.scarab.ledger.state.DState.accounts[
        xyz.kd5ujc.scarab.ledger.state.Accounts[E]
      ]
    )
    val withdrawals = b.withdrawals[TxLevel.Top](txBody)(using b.top)
    def test(t: Test[ConwayLedgerPredFailure[E]]): F[Unit] =
      Sts.runTest[F, LedgerPredFailure[E]](t.leftMap(_.map(injectLedger.injectFailure)))
    def valid(certState: CertState[E], utxoState: UTxOState[E], curEpochNo: EpochNo): F[Unit] =
      val govState = gs(utxoState.gov)
      val committee = govState.committee
      val accounts = accountsL.get(certState)
      for
        // `proposalsWithPurpose grCommitteeL proposals`: the actions of the committee's purpose,
        // of the proposals before the transaction
        before <- proposals.get
        committeeProposals = Proposals
          .proposalsWithPurpose[E, GovActionPurpose.Committee](GovRelation.grCommitteeL, before)
          .valuesIterator
          .map(gas => gas.gasId -> record.storedAction(gas))
          .toMap
        globals <- ask.ask[Globals]
        _ <- test(
          validateTreasuryValue[E, TxLevel.Top](txBody, env.ledgerAccount.treasury)(using b, b.top)
        )
        utxo <- stored.readAt(utxoState.utxo.utxo, b.allInputs(txBody))
        _ <- test(validateRefScriptSize[E, TxLevel.Top](pp, utxo, tx)(using t, b, pc, top, b.top))
        _ <-
          if ConwayEra.hardforkConwayBootstrapPhase(pv) then Monad[F].unit
          else test(validateWithdrawalsDelegated[E](accounts, withdrawals))
        // `hardforkConwayMoveWithdrawalsAndDRepChecksToLedgerRule` of the build the judgment
        // follows (`Conway/Era.hs:283-284`, cardano-ledger f649f975; at every version at 29a7d656
        // and 9df9f98c, `Conway/Rules/Ledger.hs:397-403` at 29a7d656), which the flat rules gate
        // at version 11 under every build
        // (https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7055304484)
        _ <-
          if !WithdrawalsRule.of(globals.ledger.referenceBuild).inLedger(pv) then Monad[F].unit
          else
            for
              _ <- ShelleyLedger.testIncompleteAndMissingWithdrawals[F, E](accounts, withdrawals)(
                using
                Monad[F],
                c.accountsOf,
                injectShelley,
                ask,
                judge
              )
              voted <- vocabDefect[F, CertState[E]](
                CERTS.updateVotingDRepExpiries[E, TxLevel.Top](
                  tx,
                  curEpochNo,
                  PParams.lensHKD[E].andThen(pc.hkdDRepActivityL[Identity]).get(pp),
                  CERTS.updateDormantDRepExpiries[E, TxLevel.Top](tx, curEpochNo, certState)(using
                    t,
                    b,
                    c,
                    top,
                    b.top
                  )
                )(using t, b, c, top, b.top)
              )
              _ <- state.modify(
                certL.modify(_ =>
                  accountsL.modify(EraAccounts.drainAccounts[E](withdrawals, _))(voted)
                )
              )
            yield ()
        // Conway's CERT raises nothing of its own: `Raise` is contravariant in its error, so the
        // defects' capability is also one of `Nothing`
        _ <- judge.nest(
          CERTS.conwayCertsTransition[E, Nothing, F](
            CertsEnv[E](tx, pp, curEpochNo, committee, committeeProposals),
            b.certs[TxLevel.Top](txBody)(using b.top)
          )(using
            Monad[F],
            t,
            b,
            pc,
            c,
            embedCerts,
            CertRule.conway[E](using cert, pc, c, embedCert, deleg, pool, govCert),
            certsBase,
            injectCerts,
            ask,
            state.focus(certL),
            defect,
            defect,
            judge.contramap(embed.wrapCertsFailed),
            toldIn(events)(embed.wrapCertsEvent)
          )
        )
        certStateAfterCERTS <- state.inspect(certL.get)
        txId <- vocabDefect[F, Hash32](Hash32.fromBytes(b.id[TxLevel.Top](txBody)(using b.top)))
        guardrails <- vocabDefect[F, Option[ScriptHash]](
          govState.constitution.script.traverse(ScriptHash.fromBytes)
        )
        _ <- judge.nest(
          ConwayGov.conwayGovTransition[F, E](
            GovEnv[E](txId, curEpochNo, pp, guardrails, certStateAfterCERTS, committee),
            GovSignal[E](
              b.votingProcedures[TxLevel.Top](txBody)(using b.top),
              b.proposalProcedures[TxLevel.Top](txBody)(using b.top),
              b.certs[TxLevel.Top](txBody)(using b.top)
            ),
            context.netId
          )(using
            Monad[F],
            pc,
            c,
            tc,
            gov,
            proposals,
            judge.contramap(embed.wrapGovFailed),
            toldIn(events)(embed.wrapGovEvent),
            govDefect
          )
        )
      yield ()
    for
      globals <- ask.ask[Globals]
      curEpochNo <- env.ledgerEpochNo.fold(
        vocabDefect[F, EpochNo](EpochNo.fromBigInt(globals.epochOf(env.ledgerSlotNo)))
      )(Monad[F].pure)
      before <- state.get
      _ <- t.isValid(tx) match
        case IsPhase2Valid.Phase2Valid   => valid(before.cert, before.utxo, curEpochNo)
        case IsPhase2Valid.Phase2Invalid => Monad[F].unit
      // UTXOW with the certificate state before the transaction in its environment, so its
      // refunds are computed from it (`Conway/Rules/Ledger.hs:428-439`, cardano-ledger f649f975)
      _ <- judge.nest(
        BabbageUtxow.babbageUtxowTransition[F, E](
          UtxoEnv[E](env.ledgerSlotNo, pp, before.cert),
          stAnnTx,
          context,
          verdicts,
          decoder
        )(using
          Monad[F],
          e,
          t,
          b,
          c,
          w,
          pa,
          pb,
          k,
          s,
          v,
          u,
          sc,
          era,
          needed,
          cached,
          allegra,
          alonzo,
          babbage,
          utxos,
          embedUtxos,
          shelleyW,
          alonzoW,
          babbageW,
          embedUtxo,
          state.focus(utxoL),
          judge.contramap(embed.wrapUtxowFailed),
          toldIn(events)(embed.wrapUtxowEvent),
          evaluator,
          unavailable,
          deferral,
          stateDefect,
          defect
        )
      )
    yield ()

/** LEDGERS, Conway's (`Conway/Era.hs:244`, cardano-ledger f649f975) and Dijkstra's
  * ([[DijkstraLedgers]]): Babbage's `LEDGERS` both (`Babbage/Rules/Ledgers.hs`), Shelley's failures
  * and events (`Shelley/Rules/Ledgers.hs`).
  */
object ShelleyLedgers:
  /** The fold of Babbage's `ledgersTransition` (`Babbage/Rules/Ledgers.hs:124-142`, cardano-ledger
    * f649f975, the same at 9df9f98c): the transactions in order, each annotated by the era's
    * `mkStAnnTx` (`annotate`) against the UTxO set the rule then judges it on and the cache the
    * annotation before left (`cacheStAnnTxG`), the first against `emptyCache` (`mempty`), and
    * judged by the era's LEDGER (`ledger`) as a sub-rule with its index (`zip [minBound ..]`, so no
    * more than a `TxIx` numbers: [[TxIx.enumerate]]) and the environment's slot, epoch, parameters
    * and chain accounts. The UTxO set a transaction is annotated against is the stored outputs of
    * its inputs, collateral and reference inputs ([[EraTxBody.allInputs]]), every output the
    * annotation reads, read into the era; one the era does not read is raised
    * ([[UtxoStateDefect]]). `ledger` runs with its failures and events embedded by the caller
    * ([[EmbedLedgerInLedgers]]).
    */
  def ledgersFold[F[_]: Monad, E <: EraTag](
      env: LedgersEnv[E],
      txs: Vector[Tx[TxLevel.Top, E]],
      emptyCache: StAnnTxCache[E]
  )(annotate: (UTxO[E], StAnnTxCache[E], Tx[TxLevel.Top, E]) => StAnnTx[TxLevel.Top, E])(
      ledger: (LedgerEnv[E], StAnnTx[TxLevel.Top, E]) => F[Unit]
  )(using
      t: EraTx[E],
      state: Stateful[F, LedgerState[E]],
      judge: Judge[F, LedgersPredFailure[E]],
      stored: StoredOutputs[F, E]
  ): F[Unit] =
    TxIx
      .enumerate(txs)
      .foldLeftM(emptyCache) { case (cache, (tx, ix)) =>
        for
          current <- state.get
          txBody = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
          utxo <- stored.readAt(current.utxo.utxo.utxo, t.bodyOf.allInputs(txBody))
          stAnnTx = annotate(utxo, cache, tx)
          _ <- judge.nest(
            ledger(
              LedgerEnv[E](
                env.ledgersSlotNo,
                Some(env.ledgersEpochNo),
                ix,
                env.ledgersPp,
                env.ledgersAccount
              ),
              stAnnTx
            )
          )
        yield t.cacheStAnnTx[TxLevel.Top](stAnnTx)(using t.bodyOf.top)
      }
      .void

  /** Babbage's `ledgersTransition` (`Babbage/Rules/Ledgers.hs:124-142`, cardano-ledger f649f975) at
    * Conway ([[ledgersFold]]): each transaction annotated by `mkStAnnTx`, here Alonzo's
    * [[AlonzoStAnnTx.mkAlonzoStAnnTx]] with the cache the one before left, its epoch information
    * and system start `slotToPOSIXTime`, and judged by Conway's LEDGER, its failures and events
    * embedded ([[EmbedLedgerInLedgers]]).
    */
  def ledgersTransition[F[_]: Monad, E <: EraTag](
      env: LedgersEnv[E],
      txs: Vector[Tx[TxLevel.Top, E]],
      utxoContext: UtxoContext[E],
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      e: EraUTxO[E],
      t: AlonzoEraTx[E],
      b: ConwayEraTxBody[E],
      c: ConwayEraCertState[E],
      w: AlonzoEraTxWits[E],
      pa: AlonzoEraPParams[E],
      pb: BabbageEraPParams[E],
      pc: ConwayEraPParams[E],
      tc: ConwayEraTxCert[E],
      k: EraStake[E],
      s: StoredTxOut[E],
      v: Value[E] =:= MultiAssetValue,
      u: AlonzoEraUTxO[E],
      era: Era[E],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E],
      cached: StAnnTxCache[E] =:= Map[xyz.kd5ujc.scarab.codec.Bytes, SupportedPlutusRunnable[E]],
      cert: TxCert[E] =:= ConwayTxCert,
      gs: GovState[E] =:= ConwayGovState,
      record: ProposalsRecord[E],
      allegra: InjectUtxoFailure[AllegraUtxoPredFailure[E], E],
      alonzo: InjectUtxoFailure[AlonzoUtxoPredFailure[E], E],
      babbage: InjectUtxoFailure[BabbageUtxoPredFailure[E], E],
      utxos: InjectAlonzoUtxos[E],
      embedUtxos: EmbedUtxosInUtxo[E],
      shelleyW: InjectUtxowFailure[ShelleyUtxowPredFailure[E], E],
      alonzoW: InjectUtxowFailure[AlonzoUtxowPredFailure[E], E],
      babbageW: InjectUtxowFailure[BabbageUtxowPredFailure[E], E],
      embedUtxo: EmbedUtxoInUtxow[E],
      embedCerts: EmbedCertInCerts[E],
      embedCert: EmbedInCert[E],
      injectCerts: InjectCertsFailure[ConwayCertsPredFailure[E], E],
      certsBase: CertsBaseCase[E],
      deleg: InjectDeleg[E],
      pool: InjectPool[E],
      govCert: InjectGovCert[E],
      gov: InjectGov[E],
      injectLedger: InjectLedgerFailure[ConwayLedgerPredFailure[E], E],
      injectShelley: InjectLedgerFailure[ShelleyLedgerPredFailure[E], E],
      embed: EmbedInLedger[E],
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[E]],
      judge: Judge[F, LedgersPredFailure[E]],
      events: Tell[F, Chain[LedgersEvent[E]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      deferral: Phase2Deferral[F, E],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      proposals: Stateful[F, Proposals[E]],
      scripts: ConwayEraScript[E],
      plutusContext: EraPlutusContext[E],
      annotated: AlonzoStAnnTx[E] =:= StAnnTx[TxLevel.Top, E],
      embedLedger: EmbedLedgerInLedgers[E],
      stored: StoredOutputs[F, E]
  ): F[Unit] =
    given Judge[F, LedgerPredFailure[E]] = judge.contramap(embedLedger.wrapFailed)
    given Tell[F, Chain[LedgerEvent[E]]] = toldIn(events)(embedLedger.wrapEvent)
    ledgersFold[F, E](env, txs, cached.flip(Map.empty))((utxo, cache, tx) =>
      annotated(
        AlonzoStAnnTx
          .mkAlonzoStAnnTx[E](slotToPOSIXTime, env.ledgersPp, utxo, cached(cache), tx)(using
            pa,
            t,
            u,
            scripts,
            plutusContext,
            needed
          )
      )
    )((ledgerEnv, stAnnTx) =>
      ConwayLedger.conwayLedgerTransition[F, E](ledgerEnv, stAnnTx, utxoContext, verdicts, decoder)
    )
