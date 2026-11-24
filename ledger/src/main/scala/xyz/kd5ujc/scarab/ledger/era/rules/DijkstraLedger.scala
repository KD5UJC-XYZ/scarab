// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.rules

import cats.{Id, Monad}
import cats.data.Chain
import cats.mtl.{Ask, Raise, Stateful, Tell}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{LedgerDefect, TranslationError, Verdicts}
import xyz.kd5ujc.scarab.ledger.dijkstra.{DijkstraLedgerFailure, DijkstraPurpose}
import xyz.kd5ujc.scarab.ledger.era.*
import xyz.kd5ujc.scarab.ledger.era.Relation.*
import xyz.kd5ujc.scarab.ledger.era.dijkstra.{DijkstraPlutusPurpose, DijkstraStAnnTx, DijkstraUTxO}
import xyz.kd5ujc.scarab.ledger.rules.{Globals, Judge, Sts, Test, focus}
import xyz.kd5ujc.scarab.ledger.state.{
  Coin,
  Dijkstra,
  EpochNo,
  EraTag,
  Hash32,
  LedgerState,
  ScriptHash
}
import xyz.kd5ujc.scarab.plutus.{ScriptDecoder, ScriptEvaluator}

// LEDGER of Dijkstra as the reference writes it (cardano-ledger 9df9f98c,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Rules/Ledger.hs`), with its failures, events,
// injections and the embeddings of UTXOW, ENTITIES, GOV and SUBLEDGERS, and LEDGERS at Dijkstra,
// which is Babbage's (`Dijkstra/Era.hs:296`), with its injections (`Dijkstra/Rules/Ledgers.hs`).
// LEDGER runs SUBLEDGERS on the sub-transactions first, then the top level's checks, ENTITIES and
// GOV on a transaction flagged valid, and UTXOW. The reference's two partial injections
// (`conwayToDijkstraLedgerPredFailure` and `shelleyToDijkstraLedgerPredFailure`, `:530-553`) are
// total here: Conway's over the constructors it maps ([[DijkstraLedger.ConwayFailure]]), Shelley's
// with DELEGS's failure of no value at Dijkstra ([[DelegsPredFailure]]).

/** `data DijkstraLedgerPredFailure era` (`Dijkstra/Rules/Ledger.hs:98-105`, cardano-ledger
  * 9df9f98c), its constructors in the order of their tags (1 to 6, `:252-259`).
  */
enum DijkstraLedgerPredFailure[E <: EraTag]:
  case DijkstraUtxowFailure(failure: UtxowPredFailure[E])
  case DijkstraEntitiesFailure(failure: EntitiesRuleFailure[E])
  case DijkstraGovFailure(failure: GovPredFailure[E])

  /** The treasury the body declares (supplied) and the chain's (expected). */
  case DijkstraTreasuryValueMismatch(mismatch: Mismatch[RelEQ, Coin])

  /** The reference scripts' size over the batch (supplied) and the parameters' limit (expected), as
    * the reference's 64-bit `Int`.
    */
  case DijkstraTxRefScriptsSizeTooBig(mismatch: Mismatch[RelLTEQ, Long])
  case DijkstraSubLedgersFailure(failure: SubLedgersPredFailure[E])

/** `data DijkstraLedgerEvent era` (`Dijkstra/Rules/Ledger.hs:279-284`, cardano-ledger 9df9f98c). */
enum DijkstraLedgerEvent[E <: EraTag]:
  case UtxowEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.UtxowEvent[E])
  case EntitiesEvent(event: EntitiesRuleEvent[E])
  case GovEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.GovEvent[E])
  case SubLedgersEvent(event: xyz.kd5ujc.scarab.ledger.era.rules.SubLedgersEvent[E])

object DijkstraLedgerEvent:
  given [E <: EraTag]: CanEqual[DijkstraLedgerEvent[E], DijkstraLedgerEvent[E]] = CanEqual.derived

/** What a Dijkstra LEDGER failure's flat form (`DijkstraLedgerFailure`) cannot hold: UTXOW's
  * ([[DijkstraUtxowUnprojected]]), an action of GOV's failure that none of the transaction's
  * proposals was read as ([[GovActionNotRead]]), and the failures of ENTITIES and SUBLEDGERS, which
  * the flat rules halt before (`DijkstraPending`) and so have no flat form of.
  */
enum DijkstraLedgerUnprojected:
  case Utxow(cause: DijkstraUtxowUnprojected)
  case Gov(cause: GovActionNotRead[Dijkstra])
  case Entities(failure: EntitiesPredFailure[Dijkstra])
  case SubLedgers(failure: DijkstraSubLedgersPredFailure[Dijkstra])

object DijkstraLedgerPredFailure:
  given [E <: EraTag]: CanEqual[DijkstraLedgerPredFailure[E], DijkstraLedgerPredFailure[E]] =
    CanEqual.derived

  /** The failure in the node's flat form (`DijkstraLedgerFailure`), which the flat Dijkstra rules
    * report: glue the comparisons read until the era failures have their own encoders (#796),
    * one-way. UTXOW's through [[DijkstraUtxowPredFailure.toStored]], a missing redeemer's purpose
    * by `purpose`, and GOV's through [[DijkstraGovPredFailure.toStored]], an action by `read`.
    */
  def toStored(
      failure: DijkstraLedgerPredFailure[Dijkstra],
      purpose: DijkstraPlutusPurpose[AsItem] => Option[DijkstraPurpose],
      read: GovAction[Dijkstra] => Option[xyz.kd5ujc.scarab.ledger.GovAction]
  ): Either[DijkstraLedgerUnprojected, DijkstraLedgerFailure] =
    failure match
      case DijkstraUtxowFailure(f) =>
        DijkstraUtxowPredFailure
          .toStored(f, purpose)
          .bimap(DijkstraLedgerUnprojected.Utxow(_), DijkstraLedgerFailure.UtxowFailure(_))
      case DijkstraEntitiesFailure(f) => Left(DijkstraLedgerUnprojected.Entities(f))
      case DijkstraGovFailure(f) =>
        DijkstraGovPredFailure
          .toStored(f, read)
          .bimap(DijkstraLedgerUnprojected.Gov(_), DijkstraLedgerFailure.GovFailure(_))
      case DijkstraTreasuryValueMismatch(m) =>
        Right(
          DijkstraLedgerFailure.TreasuryValueMismatch(m.storedBy(_.value))
        )
      case DijkstraTxRefScriptsSizeTooBig(m) =>
        Right(DijkstraLedgerFailure.TxRefScriptsSizeTooBig(m.stored))
      case DijkstraSubLedgersFailure(f) => Left(DijkstraLedgerUnprojected.SubLedgers(f))

/** The `Embed` instances of Dijkstra's LEDGER's sub-rules (`Dijkstra/Rules/Ledger.hs:489-508,
  * 520-528, 555-573`, cardano-ledger 9df9f98c): how the failures and events of UTXOW, ENTITIES, GOV
  * and SUBLEDGERS sit in LEDGER's (`wrapFailed`, `wrapEvent`).
  */
trait EmbedInDijkstraLedger[E <: EraTag]:
  def wrapUtxowFailed(failure: UtxowPredFailure[E]): LedgerPredFailure[E]
  def wrapUtxowEvent(event: UtxowEvent[E]): LedgerEvent[E]
  def wrapEntitiesFailed(failure: EntitiesRuleFailure[E]): LedgerPredFailure[E]
  def wrapEntitiesEvent(event: EntitiesRuleEvent[E]): LedgerEvent[E]
  def wrapGovFailed(failure: GovPredFailure[E]): LedgerPredFailure[E]
  def wrapGovEvent(event: GovEvent[E]): LedgerEvent[E]
  def wrapSubLedgersFailed(failure: SubLedgersPredFailure[E]): LedgerPredFailure[E]
  def wrapSubLedgersEvent(event: SubLedgersEvent[E]): LedgerEvent[E]

object EmbedInDijkstraLedger:
  /** Dijkstra's: LEDGER's constructor of each sub-rule. */
  given dijkstra: EmbedInDijkstraLedger[Dijkstra] with
    def wrapUtxowFailed(failure: UtxowPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraUtxowFailure(failure)
    def wrapUtxowEvent(event: UtxowEvent[Dijkstra]): LedgerEvent[Dijkstra] =
      DijkstraLedgerEvent.UtxowEvent(event)
    def wrapEntitiesFailed(failure: EntitiesRuleFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(failure)
    def wrapEntitiesEvent(event: EntitiesRuleEvent[Dijkstra]): LedgerEvent[Dijkstra] =
      DijkstraLedgerEvent.EntitiesEvent(event)
    def wrapGovFailed(failure: GovPredFailure[Dijkstra]): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraGovFailure(failure)
    def wrapGovEvent(event: GovEvent[Dijkstra]): LedgerEvent[Dijkstra] =
      DijkstraLedgerEvent.GovEvent(event)
    def wrapSubLedgersFailed(
        failure: SubLedgersPredFailure[Dijkstra]
    ): LedgerPredFailure[Dijkstra] =
      DijkstraLedgerPredFailure.DijkstraSubLedgersFailure(failure)
    def wrapSubLedgersEvent(event: SubLedgersEvent[Dijkstra]): LedgerEvent[Dijkstra] =
      DijkstraLedgerEvent.SubLedgersEvent(event)

/** LEDGER of Dijkstra (`Dijkstra/Rules/Ledger.hs`, cardano-ledger 9df9f98c). */
object DijkstraLedger:
  /** The constructors of Conway's LEDGER failure that `conwayToDijkstraLedgerPredFailure` maps
    * (`Dijkstra/Rules/Ledger.hs:530-543`, cardano-ledger 9df9f98c): the two it calls impossible
    * (`ConwayWdrlNotDelegatedToDRep`, removed in Dijkstra, and `ConwayMempoolFailure`, moved to
    * MEMPOOL) left out, by D7a's decision on the reference's partial injections (2026-11-20, #85).
    * Each payload is taken whole.
    */
  type ConwayFailure[E <: EraTag] = ConwayLedgerPredFailure.ConwayUtxowFailure[E] |
    ConwayLedgerPredFailure.ConwayCertsFailure[E] | ConwayLedgerPredFailure.ConwayGovFailure[E] |
    ConwayLedgerPredFailure.ConwayTreasuryValueMismatch[E] |
    ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig[E] |
    ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts[E] |
    ConwayLedgerPredFailure.ConwayIncompleteWithdrawals[E]

  /** `conwayToDijkstraLedgerPredFailure` (`Dijkstra/Rules/Ledger.hs:530-543`, cardano-ledger
    * 9df9f98c), on [[ConwayFailure]]: UTXOW's and GOV's failures as Dijkstra's constructors of
    * them, CERTS's in ENTITIES', the accounts missing ENTITIES' own and the incomplete withdrawals
    * ENTITIES' inexact ones in the legacy mode.
    */
  def conwayToDijkstraLedgerPredFailure(
      failure: ConwayFailure[Dijkstra]
  ): DijkstraLedgerPredFailure[Dijkstra] = failure match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(f) =>
      DijkstraLedgerPredFailure.DijkstraUtxowFailure(f)
    case ConwayLedgerPredFailure.ConwayCertsFailure(f) =>
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(EntitiesPredFailure.CertsFailure(f))
    case ConwayLedgerPredFailure.ConwayGovFailure(f) =>
      DijkstraLedgerPredFailure.DijkstraGovFailure(f)
    case ConwayLedgerPredFailure.ConwayTreasuryValueMismatch(mm) =>
      DijkstraLedgerPredFailure.DijkstraTreasuryValueMismatch(mm)
    case ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(mm) =>
      DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig(mm)
    case ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(ws) =>
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
        EntitiesPredFailure.WithdrawalAccountsMissing(ws)
      )
    case ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(ws) =>
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
        EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode(ws)
      )

  /** `shelleyToDijkstraLedgerPredFailure` (`Dijkstra/Rules/Ledger.hs:545-553`, cardano-ledger
    * 9df9f98c): DELEGS's failure, which the reference calls impossible (`error`), has no value at
    * Dijkstra ([[DelegsPredFailure]]), as at Conway.
    */
  def shelleyToDijkstraLedgerPredFailure(
      failure: ShelleyLedgerPredFailure[Dijkstra]
  ): DijkstraLedgerPredFailure[Dijkstra] = failure match
    case ShelleyLedgerPredFailure.UtxowFailure(x) =>
      DijkstraLedgerPredFailure.DijkstraUtxowFailure(x)
    case ShelleyLedgerPredFailure.DelegsFailure(x) => x
    case ShelleyLedgerPredFailure.ShelleyWithdrawalsMissingAccounts(x) =>
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
        EntitiesPredFailure.WithdrawalAccountsMissing(x)
      )
    case ShelleyLedgerPredFailure.ShelleyIncompleteWithdrawals(x) =>
      DijkstraLedgerPredFailure.DijkstraEntitiesFailure(
        EntitiesPredFailure.WithdrawalAmountsInexactInLegacyMode(x)
      )

  /** `validateAllRefScriptSize` (`Dijkstra/Rules/Ledger.hs:346-362`, cardano-ledger 9df9f98c): the
    * reference scripts of the batch, the top level's and each sub-transaction's
    * (`batchNonDistinctRefScriptsSize`), against `maxRefScriptSizePerTx`, compared as the
    * reference's 64-bit `Int` (`fromIntegral @Word32 @Int`).
    */
  def validateAllRefScriptSize(
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      tx: Tx[TxLevel.Top, Dijkstra]
  ): Test[DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra]] =
    val totalRefScriptSize = DijkstraUTxO.batchNonDistinctRefScriptsSize(utxo, tx)
    val maxRefScriptSizePerTx = EraPParams.dijkstra.ppMaxRefScriptSizePerTxG.get(pp).value.toLong
    Test.failureUnless(
      totalRefScriptSize <= maxRefScriptSizePerTx,
      new DijkstraLedgerPredFailure.DijkstraTxRefScriptsSizeTooBig[Dijkstra](
        Mismatch(totalRefScriptSize, maxRefScriptSizePerTx)
      )
    )

  private val t = EraTx.dijkstra
  private val b = EraTxBody.dijkstra
  private val u = EraUTxO.dijkstra
  private val c = EraCertState.dijkstra
  private given BothLevels[TxLevel.Top] = BothLevels.Top

  /** `dijkstraLedgerTransition` (`Dijkstra/Rules/Ledger.hs:392-487`, cardano-ledger 9df9f98c),
    * every failure recorded and injected into LEDGER's, in the reference's order:
    *   - SUBLEDGERS on the sub-transactions, whatever the top level's flag, with the UTxO set and
    *     the accounts before the transaction and the top level's flag in its environment
    *     (`:404-418`), on the ledger state and the proposals;
    *   - for every transaction, the treasury it declares against the chain's (`:423`) and the
    *     batch's reference scripts against the UTxO set before it (`:424`);
    *   - for a transaction flagged valid (`:426-471`): ENTITIES on the certificate state after
    *     SUBLEDGERS, with the committee of the governance state, its proposals among those after
    *     SUBLEDGERS and the accounts before the transaction; then GOV on the proposals after
    *     SUBLEDGERS, with the transaction's id, the constitution's guardrails script and the
    *     certificate state after ENTITIES;
    *   - UTXOW (`:475-486`) on the UTxO state after SUBLEDGERS, with the pool state after
    *     SUBLEDGERS, the certificate state before the transaction and the UTxO set before it.
    *
    * Each sub-rule's failures and events are embedded in LEDGER's ([[EmbedInDijkstraLedger]]). The
    * certificate state after is ENTITIES', or SUBLEDGERS' for a transaction flagged invalid
    * (`:487`).
    *
    * The UTxO set before the transaction (`originalUtxo`, `:400`) is the stored outputs of the
    * batch's inputs, reference inputs and collateral inputs, every level's
    * ([[EraTxBody.allInputs]]), which every lookup of LEDGER and its sub-rules reads, read into the
    * era: one the era does not read is raised ([[UtxoStateDefect]]). The proposals are the era's
    * ([[Proposals]]), in their own capability beside the ledger state, as SUBLEDGER's are
    * ([[DijkstraSubLedger.dijkstraSubLedgerTransition]]); the committee and the constitution are
    * the governance state's. The epoch is the environment's, or the slot's by the geometry of
    * `Globals`, as in Conway's LEDGER ([[ConwayLedger]]); an id, a script hash or an epoch outside
    * the state's vocabulary is raised ([[LedgerFailure.Vocab]]), and the sub-rules raise their own
    * defects.
    */
  def dijkstraLedgerTransition[F[_]: Monad](
      env: LedgerEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, LedgerPredFailure[Dijkstra]],
      events: Tell[F, Chain[LedgerEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect],
      deferral: Phase2Deferral[F, Dijkstra]
  ): F[Unit] =
    val txBody = t.body[TxLevel.Top](t.txStAnnTx[TxLevel.Top](stAnnTx))
    for
      ledgerState <- state.get
      originalUtxo <- ShelleyUtxo.readStored[F, Dijkstra](
        ShelleyUtxo.restrictKeys(ledgerState.utxo.utxo.utxo, b.allInputs(txBody))
      )
      _ <- dijkstraLedgerTransitionOn[F](originalUtxo)(env, stAnnTx, context, verdicts, decoder)
    yield ()

  /** [[dijkstraLedgerTransition]] with the UTxO set before the transaction given, `originalUtxo`:
    * the stored outputs of the batch's every input read into the era from the state the transition
    * runs on, which the caller read for the annotation (`applyLeiosClosure`, which annotates and
    * judges against the one UTxO set, as the reference does, `Leios.hs:240-252`,
    * ouroboros-consensus 2416493b).
    */
  private[rules] def dijkstraLedgerTransitionOn[F[_]: Monad](
      originalUtxo: UTxO[Dijkstra]
  )(
      env: LedgerEnv[Dijkstra],
      stAnnTx: StAnnTx[TxLevel.Top, Dijkstra],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, LedgerPredFailure[Dijkstra]],
      events: Tell[F, Chain[LedgerEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect],
      deferral: Phase2Deferral[F, Dijkstra]
  ): F[Unit] =
    val embed = EmbedInDijkstraLedger.dijkstra
    val tx = t.txStAnnTx[TxLevel.Top](stAnnTx)
    val txBody = t.body[TxLevel.Top](tx)
    val pp = env.ledgerPp
    val certL = LedgerState.cert[Dijkstra]
    val utxoL = LedgerState.utxo[Dijkstra]
    val accountsL = c.certDStateL.andThen(
      xyz.kd5ujc.scarab.ledger.state.DState
        .accounts[xyz.kd5ujc.scarab.ledger.state.Accounts[Dijkstra]]
    )
    def test[A](check: => Test[A])(wrap: A => DijkstraLedgerPredFailure[Dijkstra]): F[Unit] =
      Sts.runTest[F, LedgerPredFailure[Dijkstra]](check.leftMap(_.map(wrap)))
    for
      ledgerState <- state.get
      originalAccounts = accountsL.get(ledgerState.cert)
      _ <- judge.nest(
        DijkstraSubLedgers.dijkstraSubLedgersTransition[F](
          SubLedgerEnv[Dijkstra](
            env.ledgerSlotNo,
            env.ledgerEpochNo,
            pp,
            env.ledgerAccount,
            originalUtxo,
            originalAccounts,
            t.isValid(tx)
          ),
          u.subTransactionsStAnnTx(stAnnTx),
          context,
          verdicts,
          decoder
        )(using
          summon[Monad[F]],
          ask,
          state,
          proposals,
          judge.contramap(embed.wrapSubLedgersFailed),
          told(events)(embed.wrapSubLedgersEvent),
          stateDefect,
          defect,
          poolDefect,
          govDefect
        )
      )
      afterSubLedgers <- state.get
      globals <- ask.ask[Globals]
      curEpochNo <- env.ledgerEpochNo.fold(
        vocab[F, EpochNo](EpochNo.fromBigInt(globals.epochOf(env.ledgerSlotNo)))
      )(Monad[F].pure)
      _ <- test(
        ConwayLedger.validateTreasuryValue[Dijkstra, TxLevel.Top](
          txBody,
          env.ledgerAccount.treasury
        )(using b, summon[BothLevels[TxLevel.Top]])
      )(conwayToDijkstraLedgerPredFailure)
      _ <- test(validateAllRefScriptSize(pp, originalUtxo, tx))(identity)
      _ <- t.isValid(tx) match
        case IsPhase2Valid.Phase2Valid =>
          val govState = afterSubLedgers.utxo.gov
          val committee = govState.cgsCommittee
          for
            proposalsAfterSubLedgers <- proposals.get
            _ <- judge.nest(
              ENTITIES.dijkstraEntitiesTransition[F](
                EntitiesEnv[Dijkstra](
                  curEpochNo,
                  pp,
                  committee,
                  DijkstraSubLedger.committeeProposals(proposalsAfterSubLedgers),
                  originalAccounts
                ),
                stAnnTx,
                context.validation
              )(using
                summon[Monad[F]],
                ask,
                state.focus(certL),
                defect,
                poolDefect,
                judge.contramap(embed.wrapEntitiesFailed),
                told(events)(embed.wrapEntitiesEvent)
              )
            )
            certStateAfterEntities <- state.inspect(certL.get)
            txId <- vocab[F, Hash32](Hash32.fromBytes(b.id[TxLevel.Top](txBody)))
            guardrails <- vocab[F, Option[ScriptHash]](
              govState.cgsConstitution.script.traverse(ScriptHash.fromBytes)
            )
            _ <- judge.nest(
              ConwayGov.conwayGovTransition[F, Dijkstra](
                GovEnv[Dijkstra](
                  txId,
                  curEpochNo,
                  pp,
                  guardrails,
                  certStateAfterEntities,
                  committee
                ),
                GovSignal[Dijkstra](
                  b.votingProcedures[TxLevel.Top](txBody),
                  b.proposalProcedures[TxLevel.Top](txBody),
                  b.certs[TxLevel.Top](txBody)
                ),
                context.netId
              )(using
                summon[Monad[F]],
                EraPParams.dijkstra,
                EraCertState.dijkstra,
                EraTxCert.dijkstra,
                InjectGov.dijkstra,
                proposals,
                judge.contramap(embed.wrapGovFailed),
                told(events)(embed.wrapGovEvent),
                govDefect
              )
            )
          yield ()
        case IsPhase2Valid.Phase2Invalid => Monad[F].unit
      _ <- judge.nest(
        DijkstraUtxow.dijkstraUtxowTransition[F](
          DijkstraUtxoEnv[Dijkstra](
            env.ledgerSlotNo,
            pp,
            c.certPStateL.get(afterSubLedgers.cert),
            ledgerState.cert,
            originalUtxo
          ),
          stAnnTx,
          context,
          verdicts,
          decoder
        )(using
          summon[Monad[F]],
          state.focus(utxoL),
          judge.contramap(embed.wrapUtxowFailed),
          told(events)(embed.wrapUtxowEvent),
          evaluator,
          unavailable,
          stateDefect,
          defect,
          deferral
        )
      )
    yield ()

/** LEDGERS of Dijkstra (`EraRule "LEDGERS" DijkstraEra = Babbage.LEDGERS DijkstraEra`,
  * `Dijkstra/Era.hs:296`, cardano-ledger 9df9f98c), Babbage's transition.
  */
object DijkstraLedgers:
  /** `mkStAnnTx` at Dijkstra: [[DijkstraStAnnTx.mkDijkstraStAnnTopTx]] with the system start's
    * `slotToPOSIXTime` and the environment's parameters, against a UTxO set and a cache.
    */
  def mkStAnnTx(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Dijkstra]
  )(
      utxo: UTxO[Dijkstra],
      cache: StAnnTxCache[Dijkstra],
      tx: Tx[TxLevel.Top, Dijkstra]
  ): StAnnTx[TxLevel.Top, Dijkstra] =
    DijkstraStAnnTx.mkDijkstraStAnnTopTx(slotToPOSIXTime, pp, utxo, cache, tx)

  /** Babbage's `ledgersTransition` (`Babbage/Rules/Ledgers.hs:124-142`, cardano-ledger 9df9f98c) at
    * Dijkstra ([[ShelleyLedgers.ledgersFold]]): each transaction annotated by [[mkStAnnTx]] against
    * the UTxO set the rule then judges it on and the cache the annotation before left, the first's
    * empty, and judged by Dijkstra's LEDGER with its index.
    */
  def ledgersTransition[F[_]: Monad](
      env: LedgersEnv[Dijkstra],
      txs: Vector[Tx[TxLevel.Top, Dijkstra]],
      context: DijkstraUtxoContext,
      verdicts: Verdicts,
      decoder: ScriptDecoder[Id],
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt]
  )(using
      ask: Ask[F, Globals],
      state: Stateful[F, LedgerState[Dijkstra]],
      proposals: Stateful[F, Proposals[Dijkstra]],
      judge: Judge[F, LedgersPredFailure[Dijkstra]],
      events: Tell[F, Chain[LedgersEvent[Dijkstra]]],
      evaluator: ScriptEvaluator[F],
      unavailable: Raise[F, ScriptVerdictUnavailable],
      stateDefect: Raise[F, UtxoStateDefect],
      defect: Raise[F, LedgerDefect],
      poolDefect: Raise[F, PoolDefect],
      govDefect: Raise[F, GovDefect],
      deferral: Phase2Deferral[F, Dijkstra]
  ): F[Unit] =
    val embedLedger = EmbedLedgerInLedgers.dijkstra
    given Judge[F, LedgerPredFailure[Dijkstra]] = judge.contramap(embedLedger.wrapFailed)
    given Tell[F, Chain[LedgerEvent[Dijkstra]]] = told(events)(embedLedger.wrapEvent)
    ShelleyLedgers.ledgersFold[F, Dijkstra](env, txs, Map.empty)(
      mkStAnnTx(slotToPOSIXTime, env.ledgersPp)
    )((ledgerEnv, stAnnTx) =>
      DijkstraLedger.dijkstraLedgerTransition[F](ledgerEnv, stAnnTx, context, verdicts, decoder)
    )
