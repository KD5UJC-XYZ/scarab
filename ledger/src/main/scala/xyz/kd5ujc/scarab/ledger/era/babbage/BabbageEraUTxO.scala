// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.babbage

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{TranslationError, TxIn}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoEraUTxO,
  AlonzoScriptsNeeded,
  AlonzoStAnnTx,
  AlonzoUTxO,
  AsItem,
  BabbageUTxO,
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
  Babbage,
  Coin,
  Credential,
  KeyHash,
  ShelleyCertState,
  StakePool,
  Staking
}
import xyz.kd5ujc.scarab.plutus.{PlutusData, PlutusLanguage}

/** Babbage's unspent outputs and its instances of the UTxO classes (`instance EraUTxO BabbageEra`
  * and `instance AlonzoEraUTxO BabbageEra`, cardano-ledger f649f975
  * `eras/babbage/impl/src/Cardano/Ledger/Babbage/UTxO.hs:43-72`).
  */
object BabbageEraUTxO:
  /** Each stored output read as a Babbage output ([[BabbageTxOut.fromStored]]), the first one it
    * refuses refusing the whole: only a damaged store holds such an output.
    */
  def fromStored(
      stored: Map[TxIn, xyz.kd5ujc.scarab.ledger.TxOut]
  ): Either[EraTxError, UTxO[Babbage]] =
    stored.toVector
      .traverse((in, out) => BabbageTxOut.fromStored(out).map(in -> _))
      .map(outs => UTxO[Babbage](outs.toMap))

  /** `mkStAnnTx` of Babbage (`mkStAnnTx = mkAlonzoStAnnTx`, `Babbage.hs:65`, cardano-ledger
    * f649f975): the transaction annotated against `utxo` with the cache `stAnnTxCache`.
    */
  def mkStAnnTx(
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      pp: PParams[Babbage],
      utxo: UTxO[Babbage],
      stAnnTxCache: Map[Bytes, SupportedPlutusRunnable[Babbage]],
      tx: BabbageTx
  ): AlonzoStAnnTx[Babbage] =
    AlonzoStAnnTx.mkAlonzoStAnnTx[Babbage](slotToPOSIXTime, pp, utxo, stAnnTxCache, tx)

  val instance: AlonzoEraUTxO[Babbage] { val txOf: EraTx.babbage.type } =
    new AlonzoEraUTxO[Babbage]:
      override val txOf: EraTx.babbage.type = EraTx.babbage
      private val b = txOf.bodyOf

      // `getConsumedValue = getConsumedMaryValue`, `Babbage/UTxO.hs:46`, cardano-ledger f649f975
      override def getConsumedValue[L <: TxLevel](
          pp: PParams[Babbage],
          lookupStakingDeposit: Credential[Staking] => Option[Coin],
          utxo: UTxO[Babbage],
          body: TxBody[L, Babbage]
      )(using l: txOf.bodyOf.Level[L]): MultiAssetValue =
        MaryUTxO.getConsumedMaryValue[Babbage, L](pp, lookupStakingDeposit, utxo, body)(using b, l)

      // `getProducedValue = getProducedMaryValue`, `Babbage/UTxO.hs:48`, cardano-ledger f649f975
      override def getProducedValue(
          pp: PParams[Babbage],
          isPoolRegistered: KeyHash[StakePool] => Boolean,
          body: TxBody[TxLevel.Top, Babbage]
      ): MultiAssetValue =
        MaryUTxO.getProducedMaryValue[Babbage](pp, isPoolRegistered, body)(using b)

      // `getScriptsProvided = getBabbageScriptsProvided`, `Babbage/UTxO.hs:50`, cardano-ledger
      // f649f975
      override def getScriptsProvided[L <: TxLevel](utxo: UTxO[Babbage], tx: Tx[L, Babbage])(using
          l: txOf.bodyOf.Level[L]
      ): ScriptsProvided[Babbage] =
        BabbageUTxO.getBabbageScriptsProvided[Babbage, L](utxo, tx)(using txOf, b, l, l)

      // `getScriptsNeeded = getAlonzoScriptsNeeded`, `Babbage/UTxO.hs:52`, cardano-ledger f649f975
      override def getScriptsNeeded[L <: TxLevel](utxo: UTxO[Babbage], body: TxBody[L, Babbage])(
          using l: txOf.bodyOf.Level[L]
      ): AlonzoScriptsNeeded[Babbage] =
        AlonzoUTxO.getAlonzoScriptsNeeded[Babbage, L](utxo, body)(using b, l, EraScript.babbage)

      // `getScriptsHashesNeeded = getAlonzoScriptsHashesNeeded`, `Babbage/UTxO.hs:55`,
      // cardano-ledger f649f975
      override def getScriptsHashesNeeded(scriptsNeeded: AlonzoScriptsNeeded[Babbage]): Set[Bytes] =
        AlonzoUTxO.getAlonzoScriptsHashesNeeded(scriptsNeeded)

      // `getWitsVKeyNeeded = getAlonzoWitsVKeyNeeded`, `Babbage/UTxO.hs:57`, cardano-ledger
      // f649f975: the genesis delegations read from the certificate state's delegation state
      // through Babbage's certificate-state class (`dsGenDelegs (certState ^. certDStateL)`,
      // `Shelley/UTxO.hs:263`), a body of the top level only
      override def getWitsVKeyNeeded[L <: TxLevel](
          certState: ShelleyCertState,
          utxo: UTxO[Babbage],
          body: TxBody[L, Babbage]
      )(using l: txOf.bodyOf.Level[L]): Set[Bytes] =
        l match
          case TopOnly.Top =>
            AlonzoUTxO.getAlonzoWitsVKeyNeeded[Babbage](
              EraCertState.babbage.certDStateL.get(certState).genDelegs,
              utxo,
              body
            )(using
              b
            )

      // `getMinFeeTxUtxo pp tx _ = getShelleyMinFeeTxUtxo pp tx`, `Babbage/UTxO.hs:59`,
      // cardano-ledger f649f975: no reference script is priced before Conway
      override def getMinFeeTxUtxo[L <: TxLevel](
          pp: PParams[Babbage],
          tx: Tx[L, Babbage],
          utxo: UTxO[Babbage]
      )(using l: txOf.bodyOf.Level[L]): Coin =
        ShelleyUTxO.getShelleyMinFeeTxUtxo[Babbage, L](pp, tx)(using txOf, l)

      // `getSupplementalDataHashes = getBabbageSupplementalDataHashes`, `Babbage/UTxO.hs:62`,
      // cardano-ledger f649f975
      override def getSupplementalDataHashes[L <: TxLevel](
          utxo: UTxO[Babbage],
          txBody: TxBody[L, Babbage]
      )(using l: txOf.bodyOf.Level[L]): Set[Bytes] =
        BabbageUTxO.getBabbageSupplementalDataHashes[Babbage, L](utxo, txBody)(using b, l)

      // `getSpendingDatum = getBabbageSpendingDatum`, `Babbage/UTxO.hs:64`, cardano-ledger
      // f649f975
      override def getSpendingDatum[L <: TxLevel](
          utxo: UTxO[Babbage],
          tx: Tx[L, Babbage],
          sp: PlutusPurpose[AsItem, Babbage]
      )(using l: txOf.bodyOf.Level[L]): Either[PlutusData.DecodeError, Option[Data]] =
        BabbageUTxO.getBabbageSpendingDatum[Babbage, L](utxo, tx, sp)(using
          txOf,
          l,
          b.outputsOf,
          EraScript.babbage
        )

      // `scriptsProvidedStAnnTx = scriptsProvidedAlonzoStAnnTx` and the other three,
      // `Babbage/UTxO.hs:66-72`, cardano-ledger f649f975
      override def scriptsProvidedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Babbage])(using
          l: txOf.bodyOf.Level[L]
      ): ScriptsProvided[Babbage] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.scriptsProvidedAlonzoStAnnTx(stAnnTx)

      override def scriptsNeededStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Babbage])(using
          l: txOf.bodyOf.Level[L]
      ): AlonzoScriptsNeeded[Babbage] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.scriptsNeededAlonzoStAnnTx(stAnnTx)

      override def plutusScriptsWithContextStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Babbage])(using
          l: txOf.bodyOf.Level[L]
      ): Either[cats.data.NonEmptyList[CollectError[Babbage]], List[PlutusWithContext]] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.plutusScriptsWithContextAlonzoStAnnTx(stAnnTx)

      override def plutusLanguagesUsedStAnnTx[L <: TxLevel](stAnnTx: StAnnTx[L, Babbage])(using
          l: txOf.bodyOf.Level[L]
      ): Set[PlutusLanguage] =
        l match
          case TopOnly.Top => AlonzoStAnnTx.plutusLanguagesUsedAlonzoStAnnTx(stAnnTx)
