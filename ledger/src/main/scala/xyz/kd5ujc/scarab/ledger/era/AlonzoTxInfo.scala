// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{DataSite, TranslationError, TxIn}
import xyz.kd5ujc.scarab.ledger.state.{Coin, EraTag}

/** Why Alonzo's translation of a transaction to a script context fails (`data AlonzoContextError
  * era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs:213-216`, cardano-ledger
  * f649f975), which every later era's error carries. A time past the horizon carries the clock's
  * typed failure, where the reference's carries its `Text`; [[Undecodable]] is the node's own, as
  * the reference holds the data of a transaction decoded.
  */
enum AlonzoContextError[E <: EraTag]:
  case TranslationLogicMissingInput(txIn: TxIn)
  case TimeTranslationPastHorizon(cause: TranslationError)

  /** Data of the transaction, at `site`, that does not decode: never of a transaction or an output
    * an era's decoder read, which reads each as data.
    */
  case Undecodable(site: DataSite)

/** Alonzo's translations (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs`,
  * cardano-ledger f649f975) that the later eras' contexts use.
  */
object AlonzoTxInfo:
  import PlutusTxInfos.*

  /** `transLookupTxOut` (`Alonzo/Plutus/TxInfo.hs:240-249`, cardano-ledger f649f975): the output of
    * an input, which the UTxO set must hold.
    */
  def transLookupTxOut[E <: EraTag, A](utxo: UTxO[E], txIn: TxIn)(using
      inj: Inject[AlonzoContextError[E], A]
  ): Either[A, TxOut[E]] =
    utxo.unUTxO
      .get(txIn)
      .toRight(inj.inject(AlonzoContextError.TranslationLogicMissingInput(txIn)))

  /** `transTxBodyCerts` (`Alonzo/Plutus/TxInfo.hs:292-299`, cardano-ledger f649f975): each
    * certificate in the language's form, in the body's order.
    */
  def transTxBodyCerts[L <: Language, E <: EraTag, T <: TxLevel](pv: ProtVer, txBody: TxBody[T, E])(
      using
      t: EraPlutusTxInfo[L, E],
      b: EraTxBody[E],
      l: b.Level[T]
  ): Either[ContextError[E], Vector[PlutusTxCert[L]]] =
    b.certs[T](txBody).traverse(t.toPlutusTxCert(pv, _))

  /** `transTxBodyReqSignerHashes` (`Alonzo/Plutus/TxInfo.hs:313-314`, cardano-ledger f649f975): the
    * required signers in their set's order.
    */
  def transTxBodyReqSignerHashes[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: AlonzoEraTxBody[E],
      l: b.Level[T]
  ): Vector[PV1.PubKeyHash] =
    b.reqSignerHashes[T](txBody).toVector.map(transKeyHashBytes)

  /** `transTxWitsDatums` (`Alonzo/Plutus/TxInfo.hs:317-318`, cardano-ledger f649f975): the datums
    * by hash, in the hashes' order, each read from the bytes it arrived as.
    */
  def transTxWitsDatums[E <: EraTag](txWits: TxWits[E])(using
      w: AlonzoEraTxWits[E]
  ): Either[DataSite, Vector[(PV1.DatumHash, PV1.Datum)]] =
    w.datsTxWits(txWits)
      .datums
      .toVector
      .traverse((hash, raw) => decodeData(raw, DataSite.Datum).map(transDataPair(hash, _)))

  /** `transPolicyID` (`Alonzo/Plutus/TxInfo.hs:323-324`, cardano-ledger f649f975). */
  def transPolicyID(policy: Bytes): PV1.CurrencySymbol = PV1.CurrencySymbol(policy)

  /** `transAssetName` (`Alonzo/Plutus/TxInfo.hs:326-327`, cardano-ledger f649f975). */
  def transAssetName(name: Bytes): PV1.TokenName = PV1.TokenName(name)

  /** `transMultiAsset` (`Alonzo/Plutus/TxInfo.hs:329-336`, cardano-ledger f649f975): the policies
    * and each policy's names in ascending order (`foldrWithKey'` consing each entry).
    */
  def transMultiAsset(m: Map[Bytes, Map[Bytes, BigInt]]): PV1.Value =
    PV1.Value(AssocMap(m.toVector.sortBy(_._1).map { (policy, names) =>
      transPolicyID(policy) -> AssocMap(
        names.toVector.sortBy(_._1).map((name, q) => transAssetName(name) -> q)
      )
    }))

  /** `transValue` (`Alonzo/Plutus/TxInfo.hs:347-348`, cardano-ledger f649f975): the lovelace, then
    * the assets.
    */
  def transValue(v: MultiAssetValue): PV1.Value =
    transCoinToValue(Coin(v.lovelace)) <> transMultiAsset(v.assets)
