// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.ledger.{DataSite, TxIn, TxOutSource}
import xyz.kd5ujc.scarab.ledger.state.{Babbage, EraTag, TxIx}
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
  * f649f975; those that take the transaction's information at 9df9f98c), generic in the era over
  * its classes, with the injection of Babbage's error into the era's (`Inject (BabbageContextError
  * era) (ContextError era)`), and Babbage's instances for Plutus V1 and V2. A body's outputs are
  * translated with their indices as the reference zips them with `[minBound ..] :: [TxIx]`
  * (`Babbage/TxInfo.hs:334-338, 373-377`, cardano-ledger f649f975), so none past the last index,
  * 65,535 ([[xyz.kd5ujc.scarab.ledger.state.TxIx.enumerate]]).
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

  /** `transTxOutV1` (`Babbage/TxInfo.hs:112-128`, cardano-ledger 9df9f98c; `:110-126` at f649f975):
    * the output in the V1 form, a reference script refused, then an inline datum, then a Byron
    * address; unlike Conway's, which no longer refuses the reference script. The inline datum is
    * read as data to ask whether there is one (`isSJust (txOut ^. dataTxOutL)`, whose strict
    * `SJust` forces `binaryDataToData`): an inline datum taken unread
    * ([[BabbageTxOutRep.fromStored]]) that does not read as data is the node's own `Undecodable`,
    * where the reference raises `error`.
    */
  def transTxOutV1[E <: EraTag](txOutSource: TxOutSource, txOut: TxOut[E])(using
      o: BabbageEraTxOut[E],
      inj: Inject[BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV1.TxOut] =
    if o.referenceScript(txOut).isDefined then
      Left(inj.inject(BabbageContextError.ReferenceScriptsNotSupported(txOutSource)))
    else
      o.data(txOut) match
        case Left(e) => Left(undecodable[E](DataSite.InlineDatum, e))
        case Right(Some(_)) =>
          Left(inj.inject(BabbageContextError.InlineDatumsNotSupported(txOutSource)))
        case Right(None) =>
          AlonzoTxInfo
            .transTxOut[E](txOut)
            .toRight(inj.inject(BabbageContextError.ByronTxOutInContext(txOutSource)))

  /** `transTxInInfoV1` (`Babbage/TxInfo.hs:161-174`, cardano-ledger 9df9f98c): an input with the
    * output the UTxO set holds for it, in Babbage's V1 form.
    */
  def transTxInInfoV1[E <: EraTag](utxo: UTxO[E], txIn: TxIn)(using
      o: BabbageEraTxOut[E],
      injA: Inject[AlonzoContextError[E], ContextError[E]],
      inj: Inject[BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV1.TxInInfo] =
    for
      txOut <- AlonzoTxInfo.transLookupTxOut[E, ContextError[E]](utxo, txIn)
      plutusTxOut <- transTxOutV1(TxOutSource.TxOutFromInput(txIn), txOut)
    yield PV1.TxInInfo(transTxIn(txIn), plutusTxOut)

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
      s: AlonzoEraScript[E],
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

  /** `instance EraPlutusTxInfo 'PlutusV1 BabbageEra` (`Babbage/TxInfo.hs:359-396`, cardano-ledger
    * 9df9f98c): reference inputs refused first, then the validity interval, the inputs, the outputs
    * and the certificates in Babbage's V1 forms.
    */
  val plutusV1: EraPlutusTxInfo[Language.PlutusV1, Babbage] =
    new EraPlutusTxInfo[Language.PlutusV1, Babbage]:
      private given EraPlutusTxInfo[Language.PlutusV1, Babbage] = this
      private val t = EraTx.babbage
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given BabbageEraTxOut[Babbage] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV1] = PlutusLanguage.v1

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: TxCert[Babbage]
      ): Either[ContextError[Babbage], PV1.DCert] = AlonzoTxInfo.transTxCert[Babbage](txCert)

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Babbage],
          purpose: PlutusPurpose[AsIxItem, Babbage]
      ): Either[ContextError[Babbage], PV1.ScriptPurpose] =
        AlonzoTxInfo.transPlutusPurpose[Language.PlutusV1, Babbage](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Babbage]
      ): PlutusTxInfoResult[Language.PlutusV1, Babbage] =
        LedgerTxInfo.withTopTxLevelOnly(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          val refInputs = b.referenceInputs[TxLevel.Top](txBody)
          PlutusTxInfoResult(
            for
              _ <- Either.cond(
                refInputs.isEmpty,
                (),
                BabbageContextError.ReferenceInputsNotSupported[Babbage](refInputs)
              )
              timeRange <- AlonzoTxInfo.transValidityInterval[Babbage](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              inputs <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(transTxInInfoV1(lti.ltiUTxO, _))
              outputs <- TxIx
                .enumerate(b.outputs[TxLevel.Top](txBody))
                .traverse((out, ix) =>
                  transTxOutV1[Babbage](TxOutSource.TxOutFromOutput(ix.index), out)
                )
              txCerts <- AlonzoTxInfo.transTxBodyCerts[Language.PlutusV1, Babbage, TxLevel.Top](
                lti.ltiProtVer,
                txBody
              )
            yield PV1.TxInfo(
              txInfoInputs = inputs,
              txInfoOutputs = outputs,
              txInfoFee = transCoinToValue(b.fee(txBody)),
              txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoDCert = txCerts,
              txInfoWdrl = AlonzoTxInfo.transTxBodyWithdrawals[Babbage, TxLevel.Top](txBody),
              txInfoValidRange = timeRange,
              txInfoSignatories =
                AlonzoTxInfo.transTxBodyReqSignerHashes[Babbage, TxLevel.Top](txBody),
              txInfoData = AlonzoTxInfo.transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf),
              txInfoId = AlonzoTxInfo.transTxBodyId[Babbage, TxLevel.Top](txBody)
            )
          )
        }

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Babbage],
          txInfo: PV1.TxInfo,
          purpose: PlutusPurpose[AsIxItem, Babbage],
          redeemerData: Data
      ): Either[ContextError[Babbage], PlutusV1Args] =
        AlonzoTxInfo.toPlutusV1Args[Babbage, T](lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Babbage],
          txIn: TxIn
      ): Either[ContextError[Babbage], PV1.TxInInfo] = transTxInInfoV1(utxo, txIn)

      override def lookupTxInfoResult(
          result: BabbageTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV1, Babbage] = result.tirPlutusV1

  /** `instance EraPlutusTxInfo 'PlutusV2 BabbageEra` (`Babbage/TxInfo.hs:398-435`, cardano-ledger
    * 9df9f98c).
    */
  val plutusV2: EraPlutusTxInfo[Language.PlutusV2, Babbage] =
    new EraPlutusTxInfo[Language.PlutusV2, Babbage]:
      private given EraPlutusTxInfo[Language.PlutusV2, Babbage] = this
      private val t = EraTx.babbage
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given BabbageEraTxOut[Babbage] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV2] = PlutusLanguage.v2

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: TxCert[Babbage]
      ): Either[ContextError[Babbage], PV1.DCert] = AlonzoTxInfo.transTxCert[Babbage](txCert)

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Babbage],
          purpose: PlutusPurpose[AsIxItem, Babbage]
      ): Either[ContextError[Babbage], PV1.ScriptPurpose] =
        AlonzoTxInfo.transPlutusPurpose[Language.PlutusV2, Babbage](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Babbage]
      ): PlutusTxInfoResult[Language.PlutusV2, Babbage] =
        LedgerTxInfo.withTopTxLevelOnly(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          PlutusTxInfoResult(
            for
              timeRange <- AlonzoTxInfo.transValidityInterval[Babbage](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              inputs <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(transTxInInfoV2(lti.ltiUTxO, _))
              refInputs <- b
                .referenceInputs[TxLevel.Top](txBody)
                .toVector
                .traverse(transTxInInfoV2(lti.ltiUTxO, _))
              outputs <- TxIx
                .enumerate(b.outputs[TxLevel.Top](txBody))
                .traverse((out, ix) =>
                  transTxOutV2[Babbage](TxOutSource.TxOutFromOutput(ix.index), out)
                )
              txCerts <- AlonzoTxInfo.transTxBodyCerts[Language.PlutusV2, Babbage, TxLevel.Top](
                lti.ltiProtVer,
                txBody
              )
              plutusRedeemers <- transTxRedeemers[Language.PlutusV2, Babbage, TxLevel.Top](lti)
            yield PV2.TxInfo(
              txInfoInputs = inputs,
              txInfoReferenceInputs = refInputs,
              txInfoOutputs = outputs,
              txInfoFee = transCoinToValue(b.fee(txBody)),
              txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoDCert = txCerts,
              txInfoWdrl =
                AssocMap(AlonzoTxInfo.transTxBodyWithdrawals[Babbage, TxLevel.Top](txBody)),
              txInfoValidRange = timeRange,
              txInfoSignatories =
                AlonzoTxInfo.transTxBodyReqSignerHashes[Babbage, TxLevel.Top](txBody),
              txInfoRedeemers = plutusRedeemers,
              txInfoData =
                AssocMap(AlonzoTxInfo.transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)),
              txInfoId = AlonzoTxInfo.transTxBodyId[Babbage, TxLevel.Top](txBody)
            )
          )
        }

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Babbage],
          txInfo: PV2.TxInfo,
          purpose: PlutusPurpose[AsIxItem, Babbage],
          redeemerData: Data
      ): Either[ContextError[Babbage], PlutusV2Args] =
        toPlutusV2Args[Babbage, T](lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Babbage],
          txIn: TxIn
      ): Either[ContextError[Babbage], PV2.TxInInfo] = transTxInInfoV2(utxo, txIn)

      override def lookupTxInfoResult(
          result: BabbageTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV2, Babbage] = result.tirPlutusV2

  /** `instance EraPlutusContext BabbageEra` (`Babbage/TxInfo.hs:231-254`, cardano-ledger 9df9f98c):
    * Plutus V1 and V2, each language's information computed when first looked up.
    */
  val context: EraPlutusContext[Babbage] = new EraPlutusContext[Babbage]:
    override def mkSupportedLanguage(
        lang: xyz.kd5ujc.scarab.plutus.PlutusLanguage
    ): Option[SupportedLanguage[Babbage]] = lang match
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1 => Some(SupportedLanguage(plutusV1))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2 => Some(SupportedLanguage(plutusV2))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3 |
          xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4 =>
        None

    override def mkSupportedPlutusRunnable(
        v: Version,
        plutusScript: babbage.BabbagePlutusScript
    ): SupportedPlutusRunnable[Babbage] = plutusScript match
      case babbage.BabbagePlutusScript.V1(p) =>
        SupportedPlutusRunnable(plutusV1, PlutusRunnable.decodePlutusRunnable(v, p))
      case babbage.BabbagePlutusScript.V2(p) =>
        SupportedPlutusRunnable(plutusV2, PlutusRunnable.decodePlutusRunnable(v, p))

    override def mkTxInfoResult[T <: TxLevel](lti: LedgerTxInfo[T, Babbage]): BabbageTxInfoResult =
      BabbageTxInfoResult(plutusV1.toPlutusTxInfo(lti), plutusV2.toPlutusTxInfo(lti))

/** Babbage's memoised transaction information (`data TxInfoResult BabbageEra =
  * BabbageTxInfoResult`, `Babbage/TxInfo.hs:233-236`, cardano-ledger 9df9f98c): Plutus V1's and
  * V2's, each computed when first looked up, as the reference keeps its fields lazy.
  */
final class BabbageTxInfoResult(
    plutusV1: => PlutusTxInfoResult[Language.PlutusV1, Babbage],
    plutusV2: => PlutusTxInfoResult[Language.PlutusV2, Babbage]
):
  lazy val tirPlutusV1: PlutusTxInfoResult[Language.PlutusV1, Babbage] = plutusV1
  lazy val tirPlutusV2: PlutusTxInfoResult[Language.PlutusV2, Babbage] = plutusV2
