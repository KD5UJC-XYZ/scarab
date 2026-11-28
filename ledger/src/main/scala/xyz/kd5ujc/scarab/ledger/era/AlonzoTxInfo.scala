// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import scala.collection.immutable.{SortedMap, TreeMap}
import xyz.kd5ujc.scarab.ledger.{Address, DataSite, TranslationError, TxIn}
import xyz.kd5ujc.scarab.ledger.state.{Alonzo, Coin, EraTag, Withdrawals}
import xyz.kd5ujc.scarab.plutus.PlutusData

/** Why Alonzo's translation of a transaction to a script context fails (`data AlonzoContextError
  * era`, `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs:222-230`, cardano-ledger
  * 9df9f98c), which every later era's error carries. An unsupported certificate and an unsupported
  * purpose are Alonzo's at 9df9f98c and 29a7d656, and Conway's own at f649f975
  * (`Conway/TxInfo.hs:180-189`), where Conway's injection puts them back ([[Inject.conwayAlonzo]]).
  * A time past the horizon carries the clock's typed failure, where the reference's carries its
  * `Text`; [[Undecodable]] is the node's own, as an output's inline datum is read from the bytes it
  * arrived as where the reference's `binaryDataToData` takes it as data (`Plutus/Data.hs:244-249`,
  * an `error` otherwise). An impossible failure carries its typed [[ImpossibleContext]], where the
  * reference's carries a `Text` message.
  */
enum AlonzoContextError[E <: EraTag]:
  case TranslationLogicMissingInput(txIn: TxIn)
  case TimeTranslationPastHorizon(cause: TranslationError)
  case CertificateNotSupported(txCert: TxCert[E])
  case PlutusPurposeNotSupported(purpose: PlutusPurpose[AsItem, E])

  /** An output's inline datum, at `site`, that does not decode, with the decoder's failure: never
    * of an output an era's decoder read, which reads it as data.
    */
  case Undecodable(site: DataSite, cause: PlutusData.DecodeError)

  /** `ImpossibleContextError` (`Alonzo/Plutus/TxInfo.hs:227-229`, cardano-ledger 9df9f98c): a
    * failure the ledger's own construction rules out, which the reference reports rather than
    * crash.
    */
  case ImpossibleContextError(reason: ImpossibleContext)

/** Why a context is impossible: what the reference's `ImpossibleContextError` messages say, each a
  * case of its own (ERR-3).
  */
enum ImpossibleContext:
  /** `"Missing TxInfoResult for " <> txIdToHex txId`
    * (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs:850-853`, cardano-ledger 9df9f98c):
    * no memoised information of the sub-transaction `txId` among its top level's.
    */
  case MissingTxInfoResult(txId: Bytes)

/** Alonzo's translations (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Plutus/TxInfo.hs`,
  * cardano-ledger f649f975; those that take the transaction's information at 9df9f98c), which the
  * later eras' contexts use, and Alonzo's instances for Plutus V1.
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
    * by hash, in the hashes' order.
    */
  def transTxWitsDatums[E <: EraTag](txWits: TxWits[E])(using
      w: AlonzoEraTxWits[E]
  ): Vector[(PV1.DatumHash, PV1.Datum)] =
    transDatums(w.datsTxWits(txWits).datums)

  /** `transDatums` (`Alonzo/Plutus/TxInfo.hs:387-388`, cardano-ledger 9df9f98c), of the map a
    * `TxDats` holds: each datum by its hash, in the hashes' order.
    */
  def transDatums(datums: SortedMap[Bytes, Data]): Vector[(PV1.DatumHash, PV1.Datum)] =
    datums.toVector.map((hash, data) => transDataPair(hash, data.getPlutusData))

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

  /** `transValidityInterval` (`Alonzo/Plutus/TxInfo.hs:319-341`, cardano-ledger 9df9f98c;
    * `:252-274` at f649f975): the interval in POSIX time, an interval with an end alone closed at
    * its end (`PV1.to`), unlike Conway's; a slot the clock cannot translate refused
    * (`TimeTranslationPastHorizon`, with the clock's typed failure).
    */
  def transValidityInterval[E <: EraTag](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      vi: ValidityInterval
  )(using
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV1.POSIXTimeRange] =
    def transSlotToPOSIXTime(slot: BigInt): Either[ContextError[E], PV1.POSIXTime] =
      slotToPOSIXTime(slot)
        .bimap(e => inj.inject(AlonzoContextError.TimeTranslationPastHorizon(e)), PV1.POSIXTime(_))
    vi match
      case ValidityInterval(None, None)    => Right(PV1.Interval.always)
      case ValidityInterval(Some(i), None) => transSlotToPOSIXTime(i).map(PV1.Interval.from)
      case ValidityInterval(None, Some(i)) => transSlotToPOSIXTime(i).map(PV1.Interval.to)
      case ValidityInterval(Some(i), Some(j)) =>
        for
          t1 <- transSlotToPOSIXTime(i)
          t2 <- transSlotToPOSIXTime(j)
        yield PV1.Interval(PV1.lowerBound(t1), PV1.strictUpperBound(t2))

  /** `transTxOut` (`Alonzo/Plutus/TxInfo.hs:276-286`, cardano-ledger f649f975): the output in the
    * V1 form, none at a Byron address.
    */
  def transTxOut[E <: EraTag](txOut: TxOut[E])(using
      o: AlonzoEraTxOut[E],
      v: Value[E] =:= MultiAssetValue
  ): Option[PV1.TxOut] =
    val address = o.address(txOut)
    for
      _ <- address.kind match
        case Address.Kind.Byron(_)            => None
        case Address.Kind.Shelley(_, _, _, _) => Some(())
      addr <- transAddr(address)
    yield PV1.TxOut(addr, transValue(v(o.value(txOut))), o.dataHash(txOut).map(transDataHash))

  /** `transTxBodyId` of V1 and V2 (`Alonzo/Plutus/TxInfo.hs:288-289`, cardano-ledger f649f975): the
    * hash of the body.
    */
  def transTxBodyId[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: EraTxBody[E],
      l: b.Level[T]
  ): PV1.TxId =
    PV1.TxId(transSafeHash(b.id[T](txBody)))

  /** `transWithdrawals` (`Alonzo/Plutus/TxInfo.hs:301-305`, cardano-ledger f649f975): by the
    * staking credential of each account, folded in the accounts' order, so a later account of the
    * same credential replaces an earlier one's amount.
    */
  def transWithdrawals(withdrawals: Withdrawals): TreeMap[PV1.StakingCredential, BigInt] =
    withdrawals.unWithdrawals
      .foldLeft(TreeMap.empty[PV1.StakingCredential, BigInt]) { case (ans, (accountAddress, n)) =>
        ans.updated(PV1.StakingCredential.StakingHash(transAccountAddress(accountAddress)), n.value)
      }

  /** `transTxBodyWithdrawals` of V1 and V2 (`Alonzo/Plutus/TxInfo.hs:308-309`, cardano-ledger
    * f649f975): the withdrawals by staking credential, in the credentials' order.
    */
  def transTxBodyWithdrawals[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: EraTxBody[E],
      l: b.Level[T]
  ): Vector[(PV1.StakingCredential, BigInt)] =
    transWithdrawals(b.withdrawals[T](txBody)).toVector

  /** `transMintValue` of V1 and V2 (`Alonzo/Plutus/TxInfo.hs:344-345`, cardano-ledger f649f975): a
    * zero lovelace entry, then the assets.
    */
  def transMintValue(m: Map[Bytes, Map[Bytes, BigInt]]): PV1.Value =
    transCoinToValue(Coin(0)) <> transMultiAsset(m)

  /** `transTxCertCommon` (`Alonzo/Plutus/TxInfo.hs:435-454`, cardano-ledger 9df9f98c): a
    * certificate in the V1 form, one Conway deprecated or added refused
    * (`CertificateNotSupported`), where f649f975's gives none and Conway refuses it (`:364-379`).
    */
  def transTxCertCommon[E <: EraTag](txCert: TxCert[E])(using
      c: ShelleyEraTxCert[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV1.DCert] =
    c.getRegTxCert(txCert)
      .map(stakeCred =>
        PV1.DCert.DCertDelegRegKey(PV1.StakingCredential.StakingHash(transCred(stakeCred)))
      )
      .orElse(
        c.getUnRegTxCert(txCert)
          .map(stakeCred =>
            PV1.DCert.DCertDelegDeRegKey(PV1.StakingCredential.StakingHash(transCred(stakeCred)))
          )
      )
      .orElse(c.getDelegStakeTxCert(txCert).map { (stakeCred, keyHash) =>
        PV1.DCert.DCertDelegDelegate(
          PV1.StakingCredential.StakingHash(transCred(stakeCred)),
          transKeyHash(keyHash)
        )
      })
      .orElse(c.getRegPoolTxCert(txCert).map { params =>
        PV1.DCert
          .DCertPoolRegister(transKeyHash(params.id), PV1.PubKeyHash(params.vrf.bytes))
      })
      .orElse(c.getRetirePoolTxCert(txCert).map { (poolId, retireEpochNo) =>
        PV1.DCert.DCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
      })
      .toRight(inj.inject(AlonzoContextError.CertificateNotSupported(txCert)))

  /** `transTxCert` (`Alonzo/Plutus/TxInfo.hs:423-432`, cardano-ledger 9df9f98c; `:353-362` at
    * f649f975, which tries the common form first, with the same results): a certificate of an era
    * up to Babbage in the V1 form, a genesis delegation and a move of instantaneous rewards as
    * their constants, the rest in the common form.
    */
  def transTxCert[E <: EraTag](txCert: TxCert[E])(using
      c: ShelleyEraTxCertToBabbage[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV1.DCert] =
    c.getGenesisDelegTxCert(txCert)
      .map(_ => PV1.DCert.DCertGenesis)
      .orElse(c.getMirTxCert(txCert).map(_ => PV1.DCert.DCertMir))
      .fold(transTxCertCommon[E](txCert))(Right(_))

  /** `transPlutusPurpose` (`Alonzo/Plutus/TxInfo.hs:456-472`, cardano-ledger 9df9f98c): a purpose
    * in the V1 and V2 form, read through the script class's readers, a certificate's through the
    * language's `toPlutusTxCert`; another purpose refused (`PlutusPurposeNotSupported`). It
    * replaces f649f975's Conway `transPlutusPurposeV1V2` (`Conway/TxInfo.hs:724-739`), whose
    * results are the same, and is f649f975's (`Alonzo/Plutus/TxInfo.hs:381-392`) for Alonzo's
    * purpose, the one of Babbage, which has no other.
    */
  def transPlutusPurpose[L <: Language, E <: EraTag](
      pv: ProtVer,
      purpose: PlutusPurpose[AsIxItem, E]
  )(using
      t: EraPlutusTxInfo[L, E],
      cert: PlutusTxCert[L] =:= PV1.DCert,
      s: AlonzoEraScript[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV1.ScriptPurpose] =
    s.toSpendingPurpose[AsIxItem](purpose)
      .map(a => Right(PV1.ScriptPurpose.Spending(transTxIn(a.item))))
      .orElse(
        s.toMintingPurpose[AsIxItem](purpose)
          .map(a => Right(PV1.ScriptPurpose.Minting(transPolicyID(a.item))))
      )
      .orElse(
        s.toCertifyingPurpose[AsIxItem](purpose)
          .map(a => t.toPlutusTxCert(pv, a.item).map(c => PV1.ScriptPurpose.Certifying(cert(c))))
      )
      .orElse(
        s.toWithdrawingPurpose[AsIxItem](purpose)
          .map(a =>
            Right(
              PV1.ScriptPurpose.Rewarding(
                PV1.StakingCredential.StakingHash(transAccountAddress(a.item))
              )
            )
          )
      )
      .getOrElse(
        Left(
          inj.inject(
            AlonzoContextError.PlutusPurposeNotSupported(
              s.hoistPlutusPurpose[AsIxItem, AsItem](
                [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
              )(purpose)
            )
          )
        )
      )

  /** `toLegacyPlutusArgs` (`Alonzo/Plutus/TxInfo.hs:189-203`, cardano-ledger 9df9f98c): the purpose
    * in the language's form in its context, the redeemer, and the datum of a spending script before
    * them.
    */
  def toLegacyPlutusArgs[L <: Language, E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      mkScriptContext: PlutusScriptPurpose[L] => PlutusScriptContext[L],
      scriptPurpose: PlutusPurpose[AsIxItem, E],
      maybeSpendingData: Option[Data],
      redeemerData: Data
  )(using
      t: EraPlutusTxInfo[L, E]
  ): Either[ContextError[E], LegacyPlutusArgs[L]] =
    t.toPlutusScriptPurpose(lti, scriptPurpose).map(mkScriptContext).map { scriptContext =>
      val redeemer = redeemerData.getPlutusData
      maybeSpendingData match
        case None => LegacyPlutusArgs.LegacyPlutusArgs2(redeemer, scriptContext)
        case Some(spendingData) =>
          LegacyPlutusArgs.LegacyPlutusArgs3(spendingData.getPlutusData, redeemer, scriptContext)
    }

  /** `getSpendingDatum` of the information's UTxO set and transaction, at the transaction's level,
    * for the purpose (`Alonzo/Plutus/TxInfo.hs:186-187`, cardano-ledger 9df9f98c). Its failure,
    * which only an inline datum taken unread ([[BabbageTxOutRep.fromStored]]) can reach, is the
    * node's own `Undecodable`, where the reference raises `error` when the arguments force it. Only
    * a spending purpose has a datum, and its purpose's translation cannot fail, so the failure is
    * the same before the purpose's or after it.
    */
  def spendingDatum[E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      plutusPurpose: PlutusPurpose[AsIxItem, E]
  )(using
      u: AlonzoEraUTxO[E],
      s: AlonzoEraScript[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], Option[Data]] =
    u.getSpendingDatum[T](
      lti.ltiUTxO,
      lti.ltiTx,
      s.hoistPlutusPurpose[AsIxItem, AsItem](
        [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
      )(plutusPurpose)
    )(using LedgerTxInfo.toSTxLevel(lti)(using u.txOf.bodyOf))
      .leftMap(e => inj.inject(AlonzoContextError.Undecodable(DataSite.InlineDatum, e)))

  /** `toPlutusV1Args` (`Alonzo/Plutus/TxInfo.hs:166-187`, cardano-ledger 9df9f98c): the legacy
    * arguments, with the spending datum of the information's transaction.
    */
  def toPlutusV1Args[E <: EraTag, T <: TxLevel](
      lti: LedgerTxInfo[T, E],
      txInfo: PV1.TxInfo,
      scriptPurpose: PlutusPurpose[AsIxItem, E],
      redeemerData: Data
  )(using
      t: EraPlutusTxInfo[Language.PlutusV1, E],
      u: AlonzoEraUTxO[E],
      s: AlonzoEraScript[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PlutusV1Args] =
    for
      maybeSpendingData <- spendingDatum(lti, scriptPurpose)
      args <- toLegacyPlutusArgs[Language.PlutusV1, E, T](
        lti,
        PV1.ScriptContext(txInfo, _),
        scriptPurpose,
        maybeSpendingData,
        redeemerData
      )
    yield PlutusV1Args(args)

  /** `instance EraPlutusTxInfo 'PlutusV1 AlonzoEra` (`Alonzo/Plutus/TxInfo.hs:130-165`,
    * cardano-ledger f649f975, Alonzo's behaviour; the shape of 9df9f98c, `:132-164`, whose
    * translations take the transaction's information): the validity interval, the inputs and the
    * certificates in that order, the first to fail refusing the information. An input the UTxO set
    * does not hold is refused; one at a Byron address, and an output at one, are left out, not
    * refused (`catMaybes`, `mapMaybe transTxOut`, `:145-149`), so no output is read by its index.
    */
  val plutusV1: EraPlutusTxInfo[Language.PlutusV1, Alonzo] =
    new EraPlutusTxInfo[Language.PlutusV1, Alonzo]:
      private given EraPlutusTxInfo[Language.PlutusV1, Alonzo] = this
      private val t = EraTx.alonzo
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given AlonzoEraTxOut[Alonzo] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV1] = PlutusLanguage.v1

      // `toPlutusTxCert _ _ = pure . transTxCert`, `:131`, cardano-ledger f649f975
      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: TxCert[Alonzo]
      ): Either[ContextError[Alonzo], PV1.DCert] = transTxCert[Alonzo](txCert)

      // `toPlutusScriptPurpose = transPlutusPurpose`, `:133`, cardano-ledger f649f975
      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Alonzo],
          purpose: PlutusPurpose[AsIxItem, Alonzo]
      ): Either[ContextError[Alonzo], PV1.ScriptPurpose] =
        transPlutusPurpose[Language.PlutusV1, Alonzo](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Alonzo]
      ): PlutusTxInfoResult[Language.PlutusV1, Alonzo] =
        LedgerTxInfo.withTopTxLevelOnly(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          PlutusTxInfoResult(
            for
              timeRange <- transValidityInterval[Alonzo](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              txInsMaybes <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(toPlutusTxInInfo(lti.ltiUTxO, _))
              txCerts <- transTxBodyCerts[Language.PlutusV1, Alonzo, TxLevel.Top](
                lti.ltiProtVer,
                txBody
              )
            yield PV1.TxInfo(
              txInfoInputs = txInsMaybes.flatten,
              txInfoOutputs = b.outputs[TxLevel.Top](txBody).flatMap(transTxOut[Alonzo]),
              txInfoFee = transCoinToValue(b.fee(txBody)),
              txInfoMint = transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoDCert = txCerts,
              txInfoWdrl = transTxBodyWithdrawals[Alonzo, TxLevel.Top](txBody),
              txInfoValidRange = timeRange,
              txInfoSignatories = transTxBodyReqSignerHashes[Alonzo, TxLevel.Top](txBody),
              txInfoData = transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf),
              txInfoId = transTxBodyId[Alonzo, TxLevel.Top](txBody)
            )
          )
        }

      // `toPlutusArgs = toPlutusV1Args`, `:161`, cardano-ledger f649f975
      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Alonzo],
          txInfo: PV1.TxInfo,
          purpose: PlutusPurpose[AsIxItem, Alonzo],
          redeemerData: Data
      ): Either[ContextError[Alonzo], PlutusV1Args] =
        toPlutusV1Args[Alonzo, T](lti, txInfo, purpose, redeemerData)

      // `toPlutusTxInInfo`, `:163-165`, cardano-ledger f649f975: the output the UTxO set holds,
      // none at a Byron address
      override def toPlutusTxInInfo(
          utxo: UTxO[Alonzo],
          txIn: TxIn
      ): Either[ContextError[Alonzo], Option[PV1.TxInInfo]] =
        transLookupTxOut[Alonzo, ContextError[Alonzo]](utxo, txIn)
          .map(txOut => transTxOut[Alonzo](txOut).map(PV1.TxInInfo(transTxIn(txIn), _)))

      override def lookupTxInfoResult(
          result: AlonzoTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV1, Alonzo] = result.tirPlutusV1

  /** `instance EraPlutusContext AlonzoEra` (`Alonzo/Plutus/TxInfo.hs:196-211`, cardano-ledger
    * f649f975; `:205-220` at 9df9f98c): Plutus V1 alone, its information computed when first looked
    * up.
    */
  val context: EraPlutusContext[Alonzo] = new EraPlutusContext[Alonzo]:
    override def mkSupportedLanguage(
        lang: xyz.kd5ujc.scarab.plutus.PlutusLanguage
    ): Option[SupportedLanguage[Alonzo]] = lang match
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1 => Some(SupportedLanguage(plutusV1))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2 | xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3 |
          xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4 =>
        None

    override def mkSupportedPlutusRunnable(
        v: Version,
        plutusScript: alonzo.AlonzoPlutusScript
    ): SupportedPlutusRunnable[Alonzo] = plutusScript match
      case alonzo.AlonzoPlutusScript.AlonzoPlutusV1(p) =>
        SupportedPlutusRunnable(plutusV1, PlutusRunnable.decodePlutusRunnable(v, p))

    override def mkTxInfoResult[T <: TxLevel](lti: LedgerTxInfo[T, Alonzo]): AlonzoTxInfoResult =
      AlonzoTxInfoResult(plutusV1.toPlutusTxInfo(lti))

/** Alonzo's memoised transaction information (`newtype TxInfoResult AlonzoEra =
  * AlonzoTxInfoResult`, `Alonzo/Plutus/TxInfo.hs:198-199`, cardano-ledger f649f975; `:207-208` at
  * 9df9f98c): Plutus V1's, computed when first looked up, as the reference's newtype over a lazy
  * value is.
  */
final class AlonzoTxInfoResult(plutusV1: => PlutusTxInfoResult[Language.PlutusV1, Alonzo]):
  lazy val tirPlutusV1: PlutusTxInfoResult[Language.PlutusV1, Alonzo] = plutusV1
