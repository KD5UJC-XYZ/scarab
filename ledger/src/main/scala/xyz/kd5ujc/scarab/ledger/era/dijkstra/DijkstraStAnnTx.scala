// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.Eval
import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TranslationError
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoPlutusEvaluate,
  AlonzoScriptsNeeded,
  AsIxItem,
  BothLevels,
  CollectError,
  EraPParams,
  EraPlutusContext,
  EraTx,
  EraUTxO,
  Identity,
  LedgerLevelTxInfo,
  LedgerTxInfo,
  PParams,
  PlutusWithContext,
  ScriptsProvided,
  SupportedPlutusRunnable,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, TxIx}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

/** Whether a top-level Dijkstra transaction uses a Plutus language up to V3, whose scripts run in
  * the legacy mode (`dsattPlutusLegacyMode`, a `Bool` in the reference, `Tx.hs:540`, cardano-ledger
  * 9df9f98c): a mode, so an enum here (TYP-4).
  */
enum PlutusLegacyMode:
  case Legacy, NotLegacy

object PlutusLegacyMode:
  given CanEqual[PlutusLegacyMode, PlutusLegacyMode] = CanEqual.derived

/** A Dijkstra transaction of level `L` annotated with what the ledger state gives it (`data
  * DijkstraStAnnTx l era`, `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/Tx.hs:535-557`,
  * cardano-ledger 9df9f98c): the top level's ([[DijkstraStAnnTx.TopTx]]) with its sub-transactions
  * annotated, a sub-transaction's ([[DijkstraStAnnTx.SubTx]]) with the hashes of the scripts it
  * needs and its memoised transaction information, made by [[DijkstraStAnnTx.mkDijkstraStAnnTopTx]]
  * and [[DijkstraStAnnTx.mkDijkstraStAnnSubTx]].
  */
sealed abstract class DijkstraStAnnTx[L <: TxLevel]

object DijkstraStAnnTx:
  /** `DijkstraStAnnTopTx` (`Tx.hs:536-546`, cardano-ledger 9df9f98c). Its scripts with their
    * contexts are a deferred value, collected when first read and then kept (`Eval.later`), as the
    * reference's field is lazy: its consumer (`plutusScriptsWithContextDijkstraStAnnTx`,
    * `Dijkstra/UTxO.hs:258-276`) reads them after phase 1, the top level's first, so reading any
    * other member runs no translation.
    */
  final case class TopTx(
      dsattTx: DijkstraTx[TxLevel.Top],
      dsattScriptsNeeded: AlonzoScriptsNeeded[Dijkstra],
      dsattScriptsProvided: ScriptsProvided[Dijkstra],
      dsattPlutusLegacyMode: PlutusLegacyMode,
      dsattPlutusLanguagesUsed: Set[Lang],
      dsattPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      dsattPlutusScriptsWithContext: Eval[
        Either[NonEmptyList[CollectError[Dijkstra]], List[PlutusWithContext]]
      ],
      dsattSubTransactions: List[DijkstraStAnnTx[TxLevel.Sub]]
  ) extends DijkstraStAnnTx[TxLevel.Top]

  /** `DijkstraStAnnSubTx` (`Tx.hs:547-557`, cardano-ledger 9df9f98c). Its scripts with their
    * contexts are a deferred value, collected when first read and then kept, as the top level's are
    * ([[TopTx]]).
    */
  final case class SubTx(
      dsastTx: DijkstraTx[TxLevel.Sub],
      dsastScriptsNeeded: AlonzoScriptsNeeded[Dijkstra],
      dsastScriptsHashesNeeded: Set[Bytes],
      dsastScriptsProvided: ScriptsProvided[Dijkstra],
      dsastTxInfoResult: DijkstraTxInfoResult,
      dsastPlutusLanguagesUsed: Set[Lang],
      dsastPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      dsastPlutusScriptsWithContext: Eval[
        Either[NonEmptyList[CollectError[Dijkstra]], List[PlutusWithContext]]
      ]
  ) extends DijkstraStAnnTx[TxLevel.Sub]

  private val t = EraTx.dijkstra
  private val b = t.bodyOf
  private val u = EraUTxO.dijkstra
  private val p = EraPParams.dijkstra

  /** The languages of the scripts used (`plutusLanguage spr`, `Dijkstra.hs:156-157, 212-213`,
    * cardano-ledger 9df9f98c).
    */
  private def languages(
      used: List[(DijkstraPlutusPurpose[AsIxItem], SupportedPlutusRunnable[Dijkstra])]
  ): Set[Lang] = used.map((_, r) => r.language).toSet

  /** `mkDijkstraStAnnTopTx` (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra.hs:111-169`,
    * cardano-ledger 9df9f98c): the transaction with the scripts it needs and provides, the needed
    * Plutus scripts resolved against those provided and the cache, each sub-transaction annotated
    * at its index ([[mkDijkstraStAnnSubTx]], `zipWith ... [TxIx 0 ..]`, none past index 65,535)
    * with the scripts the whole transaction provides and the updated cache, the legacy mode when a
    * language up to Plutus V3 is used, and the scripts with their contexts at the parameters'
    * version and with their cost models, of the transaction's information with the scripts used and
    * each sub-transaction's memoised information by its id (`LedgerTopTxInfo`). The epoch
    * information and the system start are the one clock function of [[LedgerTxInfo]].
    */
  def mkDijkstraStAnnTopTx(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      tx: DijkstraTx[TxLevel.Top]
  ): TopTx =
    given BothLevels[TxLevel.Top] = BothLevels.Top
    val txBody = t.body[TxLevel.Top](tx)
    val protVer = p.ppProtocolVersionL.get(pp)
    val scriptsNeeded = u.getScriptsNeeded[TxLevel.Top](utxo, txBody)
    val scriptsProvided = u.getScriptsProvided[TxLevel.Top](utxo, tx)
    val (newStAnnTxCache, plutusScriptsUsed) =
      AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
        protVer,
        scriptsProvided,
        scriptsNeeded,
        stAnnTxCache
      )
    // the cache need not be folded over the sub-transactions: the scripts provided are the whole
    // transaction's
    val stAnnSubTxs = TxIx
      .enumerate(b.subTransactions(txBody).entries.map(_._2))
      .map((sub, txIx) =>
        mkDijkstraStAnnSubTx(
          slotToPOSIXTime,
          pp,
          utxo,
          scriptsProvided,
          newStAnnTxCache,
          txIx,
          sub
        )
      )
      .toList
    val ledgerTxInfo = LedgerTxInfo(
      protVer,
      slotToPOSIXTime,
      utxo,
      tx,
      plutusScriptsUsed,
      LedgerLevelTxInfo.LedgerTopTxInfo(stAnnSubTxs.map { sub =>
        given BothLevels[TxLevel.Sub] = BothLevels.Sub
        b.id[TxLevel.Sub](t.body[TxLevel.Sub](sub.dsastTx)) -> sub.dsastTxInfoResult
      }.toMap)
    )
    val languagesUsed = languages(plutusScriptsUsed)
    TopTx(
      dsattTx = tx,
      dsattScriptsNeeded = scriptsNeeded,
      dsattScriptsProvided = scriptsProvided,
      dsattPlutusLegacyMode =
        if languagesUsed.exists(_.id <= Lang.V3.id) then PlutusLegacyMode.Legacy
        else PlutusLegacyMode.NotLegacy,
      dsattPlutusLanguagesUsed = languagesUsed,
      dsattPlutusRunnableCache = newStAnnTxCache,
      dsattPlutusScriptsWithContext = Eval.later(
        AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfo(
          ledgerTxInfo,
          PParams.lensHKD[Dijkstra].andThen(p.hkdCostModelsL[Identity]).get(pp)
        )
      ),
      dsattSubTransactions = stAnnSubTxs
    )

  /** `mkDijkstraStAnnSubTx` (`Dijkstra.hs:171-219`, cardano-ledger 9df9f98c): the sub-transaction
    * at `txIx` with the scripts it needs, their hashes, the scripts its whole transaction provides,
    * its needed Plutus scripts resolved against them and the cache, and its memoised information
    * (`mkTxInfoResult`), from which its scripts with their contexts are collected
    * (`scriptsWithContextFromLedgerTxInfoWithResult`) and which its top level's information holds.
    */
  def mkDijkstraStAnnSubTx(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Dijkstra],
      utxo: UTxO[Dijkstra],
      scriptsProvided: ScriptsProvided[Dijkstra],
      plutusScriptsCache: Map[Bytes, SupportedPlutusRunnable[Dijkstra]],
      txIx: TxIx,
      tx: DijkstraTx[TxLevel.Sub]
  )(using c: EraPlutusContext[Dijkstra]): SubTx =
    given BothLevels[TxLevel.Sub] = BothLevels.Sub
    val protVer = p.ppProtocolVersionL.get(pp)
    val scriptsNeeded = u.getScriptsNeeded[TxLevel.Sub](utxo, t.body[TxLevel.Sub](tx))
    val (_, plutusScriptsUsed) = AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
      protVer,
      scriptsProvided,
      scriptsNeeded,
      plutusScriptsCache
    )
    val ledgerTxInfo = LedgerTxInfo(
      protVer,
      slotToPOSIXTime,
      utxo,
      tx,
      plutusScriptsUsed,
      LedgerLevelTxInfo.LedgerSubTxInfo(txIx)
    )
    val txInfoResult = c.mkTxInfoResult(ledgerTxInfo)
    SubTx(
      dsastTx = tx,
      dsastScriptsNeeded = scriptsNeeded,
      dsastScriptsHashesNeeded = u.getScriptsHashesNeeded(scriptsNeeded),
      dsastScriptsProvided = scriptsProvided,
      dsastTxInfoResult = txInfoResult,
      dsastPlutusLanguagesUsed = languages(plutusScriptsUsed),
      dsastPlutusRunnableCache = plutusScriptsCache,
      dsastPlutusScriptsWithContext = Eval.later(
        AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfoWithResult(
          ledgerTxInfo,
          txInfoResult,
          PParams.lensHKD[Dijkstra].andThen(p.hkdCostModelsL[Identity]).get(pp)
        )
      )
    )
