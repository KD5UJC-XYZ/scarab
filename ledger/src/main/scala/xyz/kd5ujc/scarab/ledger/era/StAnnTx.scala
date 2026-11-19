// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.data.NonEmptyList
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.TranslationError
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

/** A top-level transaction annotated with what the ledger state gives it (`data AlonzoStAnnTx l
  * era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:556-565`, cardano-ledger f649f975): the
  * scripts it needs and provides, the languages its Plutus scripts use, the cache of runnable
  * scripts with its own added, and its Plutus scripts with their contexts or why they cannot be
  * collected. The reference's only constructor is of a top-level transaction, as here, where the
  * family [[StAnnTx]] is this at the top level alone.
  */
final case class AlonzoStAnnTx[E <: EraTag](
    asatTx: Tx[TxLevel.Top, E],
    asatScriptsNeeded: ScriptsNeeded[E],
    asatScriptsProvided: ScriptsProvided[E],
    asatPlutusLanguagesUsed: Set[Lang],
    asatPlutusRunnableCache: Map[Bytes, SupportedPlutusRunnable[E]],
    asatPlutusScriptsWithContext: Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]]
)

/** Alonzo's annotated transactions (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo.hs:121-160`,
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:177-214`, cardano-ledger f649f975).
  */
object AlonzoStAnnTx:
  /** `mkAlonzoStAnnTx` (`Alonzo.hs:121-160`, cardano-ledger f649f975): the transaction with the
    * scripts it needs and provides, the needed Plutus scripts resolved against those provided and
    * the cache, the languages they use, and the scripts with their contexts at the parameters'
    * version and with their cost models. The epoch information and the system start are the one
    * clock function of [[LedgerTxInfo]]. The reference's `mkStAnnTx` member of `ApplyTx`, which
    * this is for Conway, comes with the mempool.
    */
  def mkAlonzoStAnnTx[E <: EraTag](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[E],
      utxo: UTxO[E],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[E]],
      tx: Tx[TxLevel.Top, E]
  )(using
      p: AlonzoEraPParams[E],
      x: AlonzoEraTx[E],
      u: AlonzoEraUTxO[E],
      s: ConwayEraScript[E],
      c: EraPlutusContext[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]],
      needed: ScriptsNeeded[E] =:= AlonzoScriptsNeeded[E]
  ): AlonzoStAnnTx[E] =
    val protVer = p.ppProtocolVersionL.get(pp)
    val top = u.txOf.bodyOf.top
    val scriptsNeeded =
      u.getScriptsNeeded[TxLevel.Top](utxo, u.txOf.body[TxLevel.Top](tx)(using top))(using top)
    val scriptsProvided = u.getScriptsProvided[TxLevel.Top](utxo, tx)(using top)
    val (newStAnnTxCache, plutusScriptsUsed) =
      AlonzoPlutusEvaluate.resolveNeededPlutusScriptsWithPurpose(
        protVer,
        scriptsProvided,
        needed(scriptsNeeded),
        stAnnTxCache
      )
    val ledgerTxInfo = LedgerTxInfo(protVer, slotToPOSIXTime, utxo, tx)
    AlonzoStAnnTx(
      asatTx = tx,
      asatScriptsNeeded = scriptsNeeded,
      asatScriptsProvided = scriptsProvided,
      asatPlutusLanguagesUsed = plutusScriptsUsed.map((_, r) => r.language).toSet,
      asatPlutusRunnableCache = newStAnnTxCache,
      asatPlutusScriptsWithContext = AlonzoPlutusEvaluate.scriptsWithContextFromLedgerTxInfo(
        ledgerTxInfo,
        PParams.lensHKD[E].andThen(p.hkdCostModelsL[Identity]).get(pp),
        plutusScriptsUsed
      )
    )

  /** `scriptsProvidedAlonzoStAnnTx` (`Alonzo/UTxO.hs:177-184`, cardano-ledger f649f975). */
  def scriptsProvidedAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): ScriptsProvided[E] =
    stAnnTx.asatScriptsProvided

  /** `scriptsNeededAlonzoStAnnTx` (`Alonzo/UTxO.hs:186-193`, cardano-ledger f649f975). */
  def scriptsNeededAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): ScriptsNeeded[E] =
    stAnnTx.asatScriptsNeeded

  /** `plutusScriptsWithContextAlonzoStAnnTx` (`Alonzo/UTxO.hs:195-204`, cardano-ledger f649f975).
    */
  def plutusScriptsWithContextAlonzoStAnnTx[E <: EraTag](
      stAnnTx: AlonzoStAnnTx[E]
  ): Either[NonEmptyList[CollectError[E]], List[PlutusWithContext]] =
    stAnnTx.asatPlutusScriptsWithContext

  /** `plutusLanguagesUsedAlonzoStAnnTx` (`Alonzo/UTxO.hs:206-214`, cardano-ledger f649f975). */
  def plutusLanguagesUsedAlonzoStAnnTx[E <: EraTag](stAnnTx: AlonzoStAnnTx[E]): Set[Lang] =
    stAnnTx.asatPlutusLanguagesUsed
