// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import scala.collection.immutable.TreeMap
import xyz.kd5ujc.scarab.ledger.{Address, DataSite, RewardAccount, TranslationError, TxIn}
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
  def transWithdrawals(
      withdrawals: Map[RewardAccount, Coin]
  ): TreeMap[PV1.StakingCredential, BigInt] =
    withdrawals.toVector
      .sortBy(_._1)
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

  /** `transTxCertCommon` (`Alonzo/Plutus/TxInfo.hs:364-379`, cardano-ledger f649f975): a
    * certificate in the V1 form, none for one Conway deprecated or added.
    */
  def transTxCertCommon[E <: EraTag](txCert: TxCert[E])(using
      c: ShelleyEraTxCert[E]
  ): Option[PV1.DCert] =
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
          .DCertPoolRegister(transKeyHash(params.sppId), PV1.PubKeyHash(params.value.vrfKeyHash))
      })
      .orElse(c.getRetirePoolTxCert(txCert).map { (poolId, retireEpochNo) =>
        PV1.DCert.DCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
      })

  /** `toLegacyPlutusArgs` (`Alonzo/Plutus/TxInfo.hs:180-194`, cardano-ledger f649f975): the purpose
    * in the language's form in its context, the redeemer, and the datum of a spending script before
    * them. The redeemer and the datum are read from the bytes they arrived as, after the purpose,
    * whose failure is the reference's.
    */
  def toLegacyPlutusArgs[L <: Language, E <: EraTag](
      pv: ProtVer,
      mkScriptContext: PlutusScriptPurpose[L] => PlutusScriptContext[L],
      scriptPurpose: PlutusPurpose[AsIxItem, E],
      maybeSpendingData: Option[Bytes],
      redeemerData: Bytes
  )(using
      t: EraPlutusTxInfo[L, E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], LegacyPlutusArgs[L]] =
    def undecodable(site: DataSite) = inj.inject(AlonzoContextError.Undecodable(site))
    for
      scriptContext <- t.toPlutusScriptPurpose(pv, scriptPurpose).map(mkScriptContext)
      redeemer <- decodeData(redeemerData, DataSite.Redeemer).leftMap(undecodable)
      spendingData <- maybeSpendingData.traverse(decodeData(_, DataSite.Datum)).leftMap(undecodable)
    yield spendingData match
      case None        => LegacyPlutusArgs.LegacyPlutusArgs2(redeemer, scriptContext)
      case Some(datum) => LegacyPlutusArgs.LegacyPlutusArgs3(datum, redeemer, scriptContext)

  /** `toPlutusV1Args` (`Alonzo/Plutus/TxInfo.hs:167-178`, cardano-ledger f649f975). */
  def toPlutusV1Args[E <: EraTag](
      pv: ProtVer,
      txInfo: PV1.TxInfo,
      scriptPurpose: PlutusPurpose[AsIxItem, E],
      maybeSpendingData: Option[Bytes],
      redeemerData: Bytes
  )(using
      t: EraPlutusTxInfo[Language.PlutusV1, E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PlutusV1Args] =
    toLegacyPlutusArgs[Language.PlutusV1, E](
      pv,
      PV1.ScriptContext(txInfo, _),
      scriptPurpose,
      maybeSpendingData,
      redeemerData
    ).map(PlutusV1Args(_))
