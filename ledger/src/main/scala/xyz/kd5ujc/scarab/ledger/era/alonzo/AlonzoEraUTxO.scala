// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.alonzo

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TranslationError, TxIn}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraUTxO,
  AlonzoScriptsNeeded,
  AlonzoStAnnTx,
  AlonzoUTxO,
  AsItem,
  CollectError,
  Data,
  EraCertState,
  EraScript,
  EraTx,
  EraTxError,
  MaryUTxO,
  MultiAssetValue,
  PParams,
  PlutusPurpose,
  PlutusWithContext,
  ScriptsProvided,
  ShelleyUTxO,
  StAnnTx,
  SupportedPlutusRunnable,
  TopOnly,
  Tx,
  TxBody,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.state.{
  Alonzo,
  Coin,
  Credential,
  KeyHash,
  ShelleyCertState,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}

/** Alonzo's unspent outputs and its instances of the UTxO classes (`instance EraUTxO AlonzoEra` and
  * `instance AlonzoEraUTxO AlonzoEra`, cardano-ledger f649f975
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/UTxO.hs:112-175`).
  */
object AlonzoEraUTxO:
  /** Each stored output read as an Alonzo output ([[AlonzoTxOut.fromStored]]), the first one it
    * refuses refusing the whole: only a damaged store holds such an output.
    */
  def fromStored(
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  ): Either[EraTxError, UTxO[Alonzo]] =
    stored.toVector
      .traverse((in, out) => AlonzoTxOut.fromStored(out).map(in -> _))
      .map(outs => UTxO[Alonzo](outs.toMap))

  /** `mkStAnnTx` of Alonzo (`mkStAnnTx = mkAlonzoStAnnTx`, `Alonzo.hs:82`, cardano-ledger
    * f649f975): the transaction annotated against `utxo` with the cache `stAnnTxCache`.
    */
  def mkStAnnTx(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Alonzo],
      utxo: UTxO[Alonzo],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[Alonzo]],
      tx: AlonzoTx
  ): AlonzoStAnnTx[Alonzo] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Alonzo](slotToPOSIXTime, pp, utxo, stAnnTxCache, tx)

  val instance: AlonzoEraUTxO[Alonzo] { val txOf: EraTx.alonzo.type } =
    new AlonzoEraUTxO[Alonzo]:
      override val txOf: EraTx.alonzo.type = EraTx.alonzo
      private val b = txOf.bodyOf

      // `getConsumedValue = getConsumedMaryValue`, `Alonzo/UTxO.hs:115`, cardano-ledger f649f975
      override def getConsumedValue[L <: TxLevel](
          pp: PParams[Alonzo],
          lookupStakingDeposit: Credential[Staking] => Option[Coin],
          utxo: UTxO[Alonzo],
          body: TxBody[L, Alonzo]
      )(using l: txOf.bodyOf.Level[L]): MultiAssetValue =
        MaryUTxO.getConsumedMaryValue[Alonzo, L](pp, lookupStakingDeposit, utxo, body)(using b, l)

      // `getProducedValue = getProducedMaryValue`, `Alonzo/UTxO.hs:117`, cardano-ledger f649f975
      override def getProducedValue(
          pp: PParams[Alonzo],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          body: TxBody[TxLevel.Top, Alonzo]
      ): MultiAssetValue =
        MaryUTxO.getProducedMaryValue[Alonzo](pp, isPoolRegistered, body)(using b)

      // `getScriptsProvided _ tx = ScriptsProvided (tx ^. witsTxL . scriptTxWitsL)`,
      // `Alonzo/UTxO.hs:119`, cardano-ledger f649f975: the witnesses' scripts alone, Alonzo having
      // no reference script
      override def getScriptsProvided[L <: TxLevel](utxo: UTxO[Alonzo], tx: Tx[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): ScriptsProvided[Alonzo] =
        ScriptsProvided(txOf.witsOf.scriptTxWits(txOf.wits[L](tx)))

      // `getScriptsNeeded = getAlonzoScriptsNeeded`, `Alonzo/UTxO.hs:121`, cardano-ledger f649f975
      override def getScriptsNeeded[L <: TxLevel](utxo: UTxO[Alonzo], body: TxBody[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): AlonzoScriptsNeeded[Alonzo] =
        AlonzoUTxO.getAlonzoScriptsNeeded[Alonzo, L](utxo, body)(using b, l, EraScript.alonzo)

      // `getScriptsHashesNeeded = getAlonzoScriptsHashesNeeded`, `Alonzo/UTxO.hs:123`,
      // cardano-ledger f649f975
      override def getScriptsHashesNeeded(scriptsNeeded: AlonzoScriptsNeeded[Alonzo]): Set[Bytes] =
        AlonzoUTxO.getAlonzoScriptsHashesNeeded(scriptsNeeded)

      // `getWitsVKeyNeeded = getAlonzoWitsVKeyNeeded`, `Alonzo/UTxO.hs:125`, cardano-ledger
      // f649f975: the genesis delegations read from the certificate state's delegation state
      // through Alonzo's certificate-state class (`dsGenDelegs (certState ^. certDStateL)`,
      // `Shelley/UTxO.hs:263`), a body of the top level only
      override def getWitsVKeyNeeded[L <: TxLevel](
          certState: ShelleyCertState,
          utxo: UTxO[Alonzo],
          body: TxBody[L, Alonzo]
      )(using l: txOf.bodyOf.Level[L]): Set[Bytes] =
        l match
          case TopOnly.Top =>
            AlonzoUTxO.getAlonzoWitsVKeyNeeded[Alonzo](
              EraCertState.alonzo.certDStateL.get(certState).genDelegs,
              utxo,
              body
            )(using b)

      // `getMinFeeTxUtxo pp tx _ = getShelleyMinFeeTxUtxo pp tx`, `Alonzo/UTxO.hs:127`,
      // cardano-ledger f649f975
      override def getMinFeeTxUtxo[L <: TxLevel](
          pp: PParams[Alonzo],
          tx: Tx[L, Alonzo],
          utxo: UTxO[Alonzo]
      )(using l: txOf.bodyOf.Level[L]): Coin =
        ShelleyUTxO.getShelleyMinFeeTxUtxo[Alonzo, L](pp, tx)(using txOf, l)

      // `getSupplementalDataHashes _ = getAlonzoSupplementalDataHashes`, `Alonzo/UTxO.hs:165`,
      // cardano-ledger f649f975
      override def getSupplementalDataHashes[L <: TxLevel](
          utxo: UTxO[Alonzo],
          txBody: TxBody[L, Alonzo]
      )(using l: txOf.bodyOf.Level[L]): Set[Bytes] =
        AlonzoUTxO.getAlonzoSupplementalDataHashes[Alonzo, L](txBody)(using b, l, b.outputsOf)

      // `getSpendingDatum = getAlonzoSpendingDatum`, `Alonzo/UTxO.hs:167`, cardano-ledger
      // f649f975: never a failure, as no inline datum is read
      override def getSpendingDatum[L <: TxLevel](
          utxo: UTxO[Alonzo],
          tx: Tx[L, Alonzo],
          sp: PlutusPurpose[AsItem, Alonzo]
      )(using l: txOf.bodyOf.Level[L]): Either[PlutusData.DecodeError, Option[Data]] =
        Right(
          AlonzoUTxO.getAlonzoSpendingDatum[Alonzo, L](utxo, tx, sp)(using
            txOf,
            l,
            b.outputsOf,
            EraScript.alonzo
          )
        )

      // `scriptsProvidedStAnnTx = scriptsProvidedAlonzoStAnnTx` and the other three,
      // `Alonzo/UTxO.hs:169-175`, cardano-ledger f649f975
      override def scriptsProvidedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): ScriptsProvided[Alonzo] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.scriptsProvidedAlonzoStAnnTx(stAnnTx)

      override def scriptsNeededStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): AlonzoScriptsNeeded[Alonzo] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.scriptsNeededAlonzoStAnnTx(stAnnTx)

      override def plutusScriptsWithContextStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): Either[cats.data.NonEmptyList[CollectError[Alonzo]], List[PlutusWithContext]] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.plutusScriptsWithContextAlonzoStAnnTx(stAnnTx)

      override def plutusLanguagesUsedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Alonzo])(using
          l: txOf.bodyOf.Level[L]
      ): Set[PlutusLanguage] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.plutusLanguagesUsedAlonzoStAnnTx(stAnnTx)
