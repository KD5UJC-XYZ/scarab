// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{DataSite, TxIn, TxOutSource}
import xyz.kd5ujc.scarab.ledger.state.EraTag
import xyz.kd5ujc.scarab.plutus.{ExUnits, PlutusData}

/** Why Babbage's translation of a transaction to a script context fails (`data BabbageContextError
  * era`, `eras/babbage/impl/src/Cardano/Ledger/Babbage/TxInfo.hs:253-260`, cardano-ledger
  * f649f975), which Conway's error carries.
  */
enum BabbageContextError[E <: EraTag]:
  case AlonzoContextError(error: xyz.kd5ujc.scarab.ledger.era.AlonzoContextError[E])
  case ByronTxOutInContext(txOutSource: TxOutSource)
  case RedeemerPointerPointsToNothing(ptr: PlutusPurpose[AsIx, E])
  case InlineDatumsNotSupported(txOutSource: TxOutSource)
  case ReferenceScriptsNotSupported(txOutSource: TxOutSource)
  case ReferenceInputsNotSupported(txIns: Set[TxIn])

/** Babbage's translations (`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxInfo.hs`, cardano-ledger
  * f649f975) that Conway's contexts use, generic in the era over its classes, with the injection of
  * Babbage's error into the era's (`Inject (BabbageContextError era) (ContextError era)`).
  */
object BabbageTxInfo:
  import PlutusTxInfos.*

  private def alonzo[E <: EraTag](
      e: AlonzoContextError[E]
  )(using inj: Inject[BabbageContextError[E], ContextError[E]]): ContextError[E] =
    inj.inject(BabbageContextError.AlonzoContextError(e))

  /** The node's undecodable data at `site`, with the decoder's failure, as the era's error. */
  def undecodable[E <: EraTag](site: DataSite, cause: PlutusData.DecodeError)(using
      Inject[BabbageContextError[E], ContextError[E]]
  ): ContextError[E] = alonzo[E](AlonzoContextError.Undecodable(site, cause))

  /** `transReferenceScript` (`Babbage/TxInfo.hs:100-106`, cardano-ledger f649f975): the hash of the
    * reference script.
    */
  def transReferenceScript[E <: EraTag](script: Option[Script[E]])(using
      s: EraScript[E]
  ): Option[PV1.ScriptHash] =
    script.map(x => PV1.ScriptHash(s.hashScript(x)))

  /** `transTxOutV2` (`Babbage/TxInfo.hs:130-156`, cardano-ledger f649f975): the output in the V2
    * form, a Byron address refused; an inline datum is read as data through `binaryDataToData`,
    * only for an address that is not Byron, as the reference's lazy `datum` is forced only there
    * (lines 142-156). Its failure, the node's own `Undecodable`, is where the reference raises
    * `error`: only an inline datum taken unread ([[BabbageTxOutRep.fromStored]]) can reach it.
    */
  def transTxOutV2[E <: EraTag](txOutSource: TxOutSource, txOut: TxOut[E])(using
      o: BabbageEraTxOut[E],
      inj: Inject[BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV2.TxOut] =
    val value = o.value(txOut)
    val referenceScript = transReferenceScript(o.referenceScript(txOut))(using o.scripts)
    def datum: Either[ContextError[E], PV2.OutputDatum] = o.datum(txOut) match
      case Datum.NoDatum       => Right(PV2.OutputDatum.NoOutputDatum)
      case Datum.DatumHash(dh) => Right(PV2.OutputDatum.OutputDatumHash(transDataHash(dh)))
      case Datum.Datum(binaryData) =>
        BinaryData
          .binaryDataToData(binaryData)
          .bimap(
            undecodable[E](DataSite.InlineDatum, _),
            d => PV2.OutputDatum.OutputDatum(PV1.Datum(d.getPlutusData))
          )
    transAddr(o.address(txOut)) match
      case None => Left(inj.inject(BabbageContextError.ByronTxOutInContext(txOutSource)))
      case Some(addr) =>
        datum.map(d => PV2.TxOut(addr, AlonzoTxInfo.transValue(v(value)), d, referenceScript))

  /** `transRedeemer` (`Babbage/TxInfo.hs:188-189`, cardano-ledger f649f975). */
  def transRedeemer(data: Data): PV1.Redeemer = PV1.Redeemer(data.getPlutusData)

  /** `transRedeemerPointerV2V3` (`Babbage/TxInfo.hs:195-211`, cardano-ledger 9df9f98c; f649f975's
    * `transRedeemerPtr`, `:191-207`, of the body and the protocol version): the purpose a
    * redeemer's pointer names in the transaction's body, in the language's form, with the redeemer;
    * a pointer that names nothing refused.
    */
  def transRedeemerPointerV2V3[L <: Language, E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      redeemer: (PlutusPurpose[AsIx, E], (Data, ExUnits))
  )(using
      t: EraPlutusTxInfo[L, E],
      x: AlonzoEraTx[E],
      inj: Inject[BabbageContextError[E], ContextError[E]]
  ): Either[ContextError[E], (PlutusScriptPurpose[L], PV1.Redeemer)] =
    given x.bodyOf.Level[T] = LedgerTxInfo.toSTxLevel(lti)(using x.bodyOf)
    val (ptr, (d, _)) = redeemer
    x.bodyOf.redeemerPointerInverse[T](x.body[T](lti.ltiTx), ptr) match
      case None => Left(inj.inject(BabbageContextError.RedeemerPointerPointsToNothing(ptr)))
      case Some(sp) =>
        t.toPlutusScriptPurpose(lti, sp)
          .map(plutusScriptPurpose => (plutusScriptPurpose, transRedeemer(d)))

  /** `transTxRedeemers` (`Babbage/TxInfo.hs:215-229`, cardano-ledger 9df9f98c): every redeemer of
    * the information's transaction, in the order of their pointers, by its purpose.
    */
  def transTxRedeemers[L <: Language, E <: EraTag, T <: TxLevel](lti: LedgerTxInfo[T, E])(using
      t: EraPlutusTxInfo[L, E],
      x: AlonzoEraTx[E],
      inj: Inject[BabbageContextError[E], ContextError[E]]
  ): Either[ContextError[E], AssocMap[PlutusScriptPurpose[L], PV1.Redeemer]] =
    given x.bodyOf.Level[T] = LedgerTxInfo.toSTxLevel(lti)(using x.bodyOf)
    x.witsOf
      .rdmrsTxWits(x.wits[T](lti.ltiTx))
      .entries
      .toVector
      .traverse(transRedeemerPointerV2V3[L, E, T](lti, _))
      .map(AssocMap(_))

  /** `transTxInInfoV2` (`Babbage/TxInfo.hs:174-186`, cardano-ledger f649f975): an input with the
    * output the UTxO set holds for it, in the V2 form.
    */
  def transTxInInfoV2[E <: EraTag](utxo: UTxO[E], txIn: TxIn)(using
      o: BabbageEraTxOut[E],
      inj: Inject[BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV2.TxInInfo] =
    for
      txOut <- AlonzoTxInfo
        .transLookupTxOut[E, AlonzoContextError[E]](utxo, txIn)
        .leftMap(e => alonzo[E](e))
      plutusTxOut <- transTxOutV2(TxOutSource.TxOutFromInput(txIn), txOut)
    yield PV2.TxInInfo(transTxIn(txIn), plutusTxOut)

  /** `toPlutusV2Args` (`Babbage/TxInfo.hs:437-458`, cardano-ledger 9df9f98c): the legacy arguments,
    * with the spending datum of the information's transaction.
    */
  def toPlutusV2Args[E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      txInfo: PV2.TxInfo,
      scriptPurpose: PlutusPurpose[AsIxItem, E],
      redeemerData: Data
  )(using
      t: EraPlutusTxInfo[Language.PlutusV2, E],
      u: AlonzoEraUTxO[E],
      s: ConwayEraScript[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PlutusV2Args] =
    for
      maybeSpendingData <- AlonzoTxInfo.spendingDatum(lti, scriptPurpose)
      args <- AlonzoTxInfo.toLegacyPlutusArgs[Language.PlutusV2, E, T](
        lti,
        PV2.ScriptContext(txInfo, _),
        scriptPurpose,
        maybeSpendingData,
        redeemerData
      )
    yield PlutusV2Args(args)
