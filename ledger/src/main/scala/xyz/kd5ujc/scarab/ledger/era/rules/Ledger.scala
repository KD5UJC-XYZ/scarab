// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Functor, Id, Monad}
import cats.data.{Chain, NonEmptyList, NonEmptyMap}
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.ledger.{
  GovAction as FlatGovAction,
  TranslationError,
  LedgerDefect,
  LedgerFailure,
  Mismatch,
  Proposals as StoredProposals,
  RewardAccount,
  Verdicts
}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayProposals, ConwayTxCert, ConwayUTxO}
import xyz.kd5ujc.scarab.ledger.rules.{Convert, Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  CertState,
  ChainAccountState,
  Coin,
  Conway,
  ConwayGovState,
  EpochNo,
  EraTag,
  GovState,
  Hash32,
  KeyHash,
  LedgerState,
  ScriptHash,
  Staking,
  TxIx,
  UTxOState,
  VocabError
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

/** How era `E` reads the node's record of the proposals into its [[Proposals]] and writes them
  * back, until the governance state holds the reference's `Proposals era` itself (#85): glue, the
  * reference's `proposalsGovStateL`. Only this package makes one.
  */
sealed trait ProposalsRecord[E <: EraTag]:
  def read(stored: StoredProposals): Either[ProposalsReadError, Proposals[E]]
  def write(proposals: Proposals[E]): StoredProposals

object ProposalsRecord:
  given conway: ProposalsRecord[Conway] with
    def read(stored: StoredProposals): Either[ProposalsReadError, Proposals[Conway]] =
      ConwayProposals.fromStored(stored)
    def write(proposals: Proposals[Conway]): StoredProposals = ConwayProposals.toStored(proposals)

/** Shelley's functions of LEDGER that Conway's runs (`Shelley/Rules/Ledger.hs`, cardano-ledger
  * f649f975).
  */
object ShelleyLedger:
  /** `testIncompleteAndMissingWithdrawals` (`Shelley/Rules/Ledger.hs:341-359`, cardano-ledger
    * f649f975): the withdrawals of an account that is not registered or is on another network, then
    * those that do not take the account's whole balance. Each account is read into the reference's
    * `AccountAddress`, which every decoded reward account is.
    */
  def testIncompleteAndMissingWithdrawals[F[_]: Monad, E <: EraTag](
      accounts: xyz.kd5ujc.scarab.ledger.state.Accounts[E],
      withdrawals: Map[RewardAccount, Coin]
  )(using
      a: EraAccounts[E],
      inject: InjectLedgerFailure[ShelleyLedgerPredFailure[E], E],
      ask: Ask[F, Globals],
      defect: Raise[F, LedgerDefect],
      judge: Judge[F, LedgerPredFailure[E]]
  ): F[Unit] =
    def keyed[V](m: Map[RewardAccount, V]): F[Map[AccountAddress, V]] =
      vocabDefect[F, Map[AccountAddress, V]](
        m.toVector.traverse((k, v) => Convert.accountAddress(k).map(_ -> v)).map(_.toMap)
      )
    for
      network <- ask.ask[Globals].map(_.networkId)
      undrained <- vocabDefect[F, Option[EraAccounts.Undrained]](
        EraAccounts.withdrawalsThatDoNotDrainAccounts[E](withdrawals, network, accounts)
      )
      (missing, incomplete) = undrained.getOrElse((Map.empty, Map.empty))
      missingWithdrawals <- keyed(missing)
      incompleteWithdrawals <- keyed(incomplete)
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

  /** `validateTreasuryValue` (`Conway/Rules/Ledger.hs:442-454`, cardano-ledger f649f975). */
  def validateTreasuryValue[E <: EraTag, L <: TxLevel](body: TxBody[L, E], treasury: Coin)(using
      b: ConwayEraTxBody[E],
      l: b.Level[L]
  ): Test[ConwayLedgerPredFailure[E]] =
    b.currentTreasuryValue[L](body)
      .fold(Test.pass[ConwayLedgerPredFailure[E]])(submitted =>
        Test.failureUnless(
          submitted == treasury,
          ConwayLedgerPredFailure.ConwayTreasuryValueMismatch(Mismatch(submitted, treasury))
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
      withdrawals: Map[RewardAccount, Coin]
  )(using a: ConwayEraAccounts[E]): Either[VocabError, Test[ConwayLedgerPredFailure[E]]] =
    withdrawals.keys.toVector.sorted
      .collect { case RewardAccount(_, xyz.kd5ujc.scarab.ledger.Credential.Key(h)) => h }
      .traverse(h =>
        for
          kh <- KeyHash.fromBytes[Staking](h)
          cred <- Convert.credential[Staking](xyz.kd5ujc.scarab.ledger.Credential.Key(h))
        yield kh -> cred
      )
      .map { keyHashes =>
        val nonExistentDelegations = keyHashes.collect {
          case (kh, cred)
              if EraAccounts
                .lookupAccountState[E](cred, accounts)
                .flatMap(a.dRepDelegationAccountStateL.get)
                .isEmpty =>
            kh
        }
        Test.failureOnJust(NonEmptyList.fromList(nonExistentDelegations.toList))(
          ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep(_)
        )
      }

  /** `conwayLedgerTransitionTRC` (`Conway/Rules/Ledger.hs:350-440`, cardano-ledger f649f975). For a
    * transaction whose scripts pass: the treasury value, the reference scripts' size and, after the
    * bootstrap phase, the delegation of the accounts withdrawn from, from the certificate state
    * before the certificates; where the build moves them to LEDGER ([[WithdrawalsRule]]), the
    * withdrawals' test, the DReps' expiries and the accounts drained; CERTS on the certificates;
    * GOV on the proposals and votes with the certificate state after CERTS. Then, for every
    * transaction, UTXOW, with the certificate state before the transaction in its environment, as
    * its refunds are computed from it. Every sub-rule runs whatever was recorded before it.
    *
    * The proposals are the node's record, read into the era's ([[ProposalsRecord]]) for GOV and
    * written back; a record the era cannot read is raised ([[ProposalsReadError]]). The epoch is
    * the environment's, or the slot's by the geometry of `Globals` (`epochOf`), which is total,
    * where the reference's `epochFromSlot` (`Slot.hs:66-69`, cardano-ledger f649f975) reads the
    * epoch information of its `Globals` and fails outside a bounded history: the two agree within
    * the history. UTXOW's arguments are UTXOW's ([[BabbageUtxow.babbageUtxowTransition]]).
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
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      readError: Raise[F, ProposalsReadError]
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
    // the proposals GOV judges and leaves: the node's record in the governance state, read into
    // the era's and written back
    val proposals: Stateful[F, Proposals[E]] = new Stateful[F, Proposals[E]]:
      val monad: Monad[F] = Monad[F]
      def get: F[Proposals[E]] = state.get.flatMap(ls =>
        record
          .read(gs(ls.utxo.gov).proposals)
          .fold(readError.raise[ProposalsReadError, Proposals[E]], Monad[F].pure)
      )
      def set(ps: Proposals[E]): F[Unit] = state.modify(
        utxoL
          .andThen(UTxOState.gov[E])
          .modify(g => gs.flip(gs(g).copy(proposals = record.write(ps))))
      )
    def valid(certState: CertState[E], utxoState: UTxOState[E], curEpochNo: EpochNo): F[Unit] =
      val govState = gs(utxoState.gov)
      val committee = govState.committee
      // `proposalsWithPurpose grCommitteeL proposals`: the actions of the committee's purpose
      val committeeProposals = govState.proposals.actions.collect {
        case g if g.proposal.action match
              case FlatGovAction.NoConfidence(_) | FlatGovAction.UpdateCommittee(_, _, _, _) =>
                true
              case _ => false
            =>
          g.id -> g
      }.toMap
      val accounts = accountsL.get(certState)
      for
        globals <- ask.ask[Globals]
        _ <- test(
          validateTreasuryValue[E, TxLevel.Top](txBody, env.ledgerAccount.treasury)(using b, b.top)
        )
        utxo <- ShelleyUtxo.readStored[F, E](
          ShelleyUtxo.extractKeys(utxoState.utxo.utxo, b.allInputs(txBody))._2
        )
        _ <- test(validateRefScriptSize[E, TxLevel.Top](pp, utxo, tx)(using t, b, pc, top, b.top))
        _ <-
          if ConwayEra.hardforkConwayBootstrapPhase(pv) then Monad[F].unit
          else
            vocabDefect[F, Test[ConwayLedgerPredFailure[E]]](
              validateWithdrawalsDelegated[E](accounts, withdrawals)
            ).flatMap(test)
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
                defect,
                judge
              )
              voted <- vocabDefect[F, CertState[E]](
                CERTS.updateVotingDRepExpiries[E](
                  tx,
                  curEpochNo,
                  PParams.lensHKD[E].andThen(pc.hkdDRepActivityL[Identity]).get(pp),
                  CERTS.updateDormantDRepExpiries[E](tx, curEpochNo, certState)
                )
              )
              _ <- state.modify(
                certL.modify(_ =>
                  accountsL.modify(EraAccounts.drainAccounts[E](withdrawals, _))(voted)
                )
              )
            yield ()
        _ <- judge.nest(
          CERTS.conwayCertsTransition[E, F](
            CertsEnv[E](tx, pp, curEpochNo, committee, committeeProposals),
            b.certs[TxLevel.Top](txBody)(using b.top)
          )(using
            Monad[F],
            t,
            b,
            cert,
            pc,
            c,
            embedCerts,
            embedCert,
            injectCerts,
            deleg,
            pool,
            govCert,
            ask,
            state.focus(certL),
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
          stateDefect,
          defect
        )
      )
    yield ()

/** LEDGERS (`Shelley/Rules/Ledgers.hs`, cardano-ledger f649f975), Conway's (`Conway/Era.hs:244`,
  * Babbage's `LEDGERS`, whose transition is Shelley's).
  */
object ShelleyLedgers:
  /** `ledgersTransition` (`Shelley/Rules/Ledgers.hs:185-211`, cardano-ledger f649f975): the
    * transactions in order, each annotated against the UTxO set the rule then judges it on
    * (`mkStAnnTx`, here Alonzo's [[AlonzoStAnnTx.mkAlonzoStAnnTx]] with an empty cache, its epoch
    * information and system start `slotToPOSIXTime`), and judged by LEDGER as a sub-rule with its
    * index (`zip [minBound ..]`, so no more than a `TxIx` numbers: [[TxIx.enumerate]]), its
    * failures and events embedded ([[EmbedLedgerInLedgers]]). The UTxO set a transaction is
    * annotated against is the stored outputs of its inputs, collateral and reference inputs, every
    * output the annotation reads.
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
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      govDefect: Raise[F, GovDefect],
      readError: Raise[F, ProposalsReadError],
      scripts: ConwayEraScript[E],
      plutusContext: EraPlutusContext[E],
      annotated: AlonzoStAnnTx[E] =:= StAnnTx[TxLevel.Top, E],
      embedLedger: EmbedLedgerInLedgers[E]
  ): F[Unit] =
    given Judge[F, LedgerPredFailure[E]] = judge.contramap(embedLedger.wrapFailed)
    given Tell[F, Chain[LedgerEvent[E]]] = toldIn(events)(embedLedger.wrapEvent)
    TxIx.enumerate(txs).traverse_ { (tx, ix) =>
      for
        current <- state.get
        txBody = t.body[TxLevel.Top](tx)(using t.bodyOf.top)
        utxo <- ShelleyUtxo.readStored[F, E](
          ShelleyUtxo.extractKeys(current.utxo.utxo.utxo, b.allInputs(txBody))._2
        )
        stAnnTx = annotated(
          AlonzoStAnnTx
            .mkAlonzoStAnnTx[E](slotToPOSIXTime, env.ledgersPp, utxo, Map.empty, tx)(using
              pa,
              t,
              u,
              scripts,
              plutusContext,
              needed
            )
        )
        _ <- judge.nest(
          ConwayLedger.conwayLedgerTransition[F, E](
            LedgerEnv[E](
              env.ledgersSlotNo,
              Some(env.ledgersEpochNo),
              ix,
              env.ledgersPp,
              env.ledgersAccount
            ),
            stAnnTx,
            utxoContext,
            verdicts,
            decoder
          )
        )
      yield ()
    }
