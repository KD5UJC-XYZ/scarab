// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.all.*
import scala.collection.immutable.SortedSet
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{RewardAccount, TxIn, TxOutSource}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AlonzoTxInfo,
  AsIx,
  AsIxItem,
  AssocMap,
  BabbageEraTxOut,
  BabbageTxInfo,
  BothLevels,
  ConwayEraTxCert,
  ConwayGovCert,
  ContextError,
  Data,
  Delegatee,
  DijkstraEraTxBody,
  EraPlutusTxInfo,
  EraTx,
  EraTxOut,
  Inject,
  Language,
  LedgerTxInfo,
  PParamsUpdate,
  PV1,
  PV2,
  PV3,
  PlutusLanguage,
  PlutusPurpose,
  PlutusTxInfoResult,
  PlutusTxInfos,
  PlutusV1Args,
  PlutusV2Args,
  PlutusV3Args,
  PoolCert,
  ProtVer,
  ToPlutusData,
  TxBody,
  TxCert,
  TxLevel,
  UTxO
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayContextError, ConwayEraPlutusTxInfo, ConwayTxInfo}
import xyz.kd5ujc.scarab.ledger.state.{Coin, Credential, Dijkstra, EraTag, Guard, ScriptHash, TxIx}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

// Dijkstra's script contexts (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`,
// cardano-ledger 9df9f98c): the types the annotated transaction names, and the translations of
// Plutus V1 to V3. Plutus V4's translation and Dijkstra's `EraPlutusContext` instance come with
// Dijkstra's Plutus V4 contexts (#85, https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-6981185123).

/** Why a Dijkstra transaction has no script context (`data DijkstraContextError era`,
  * `TxInfo.hs:155-172`, cardano-ledger 9df9f98c): Conway's failures, a sub-transaction's failure at
  * the top level, and what Dijkstra adds. A transaction id is its body's hash, the account balance
  * intervals and the direct deposits the body's fields.
  */
enum DijkstraContextError[E <: EraTag]:
  case ConwayContextError(error: xyz.kd5ujc.scarab.ledger.era.conway.ConwayContextError[E])
  case SubTxContextError(txId: Bytes, error: ContextError[E])
  case PointerPresentInOutput(txOutSource: TxOutSource)
  case UnsupportedScriptInSubTx(language: Lang, txId: Bytes)
  case DirectDepositsNotSupported(directDeposits: Map[RewardAccount, Coin])
  case AccountBalanceIntervalsNotSupported(
      accountBalanceIntervals: Map[RewardAccount, AccountBalanceInterval]
  )
  case GuardScriptHashesNotSupported(scriptHashes: NonEmptyList[ScriptHash])
  case RequiredTopLevelGuardsNotSupported(guards: NonEmptySet[Credential[Guard]])
  case ScriptHashNotFoundForPurpose(purpose: PlutusPurpose[AsIx, E])

/** Dijkstra's memoised transaction information (`data TxInfoResult DijkstraEra =
  * DijkstraTxInfoResult`, `TxInfo.hs:302-307`, cardano-ledger 9df9f98c): Plutus V1's to V4's, each
  * computed when first looked up, as the reference keeps its fields lazy. Each slot is the shared
  * [[PlutusTxInfoResult]] (`Alonzo/Plutus/Context.hs:193-196`, cardano-ledger 9df9f98c), V4's of
  * the V4 `TxInfo` (`Context.hs:280`).
  */
final class DijkstraTxInfoResult(
    plutusV1: => PlutusTxInfoResult[Language.PlutusV1, Dijkstra],
    plutusV2: => PlutusTxInfoResult[Language.PlutusV2, Dijkstra],
    plutusV3: => PlutusTxInfoResult[Language.PlutusV3, Dijkstra],
    plutusV4: => PlutusTxInfoResult[Language.PlutusV4, Dijkstra]
):
  lazy val tirPlutusV1: PlutusTxInfoResult[Language.PlutusV1, Dijkstra] = plutusV1
  lazy val tirPlutusV2: PlutusTxInfoResult[Language.PlutusV2, Dijkstra] = plutusV2
  lazy val tirPlutusV3: PlutusTxInfoResult[Language.PlutusV3, Dijkstra] = plutusV3
  lazy val tirPlutusV4: PlutusTxInfoResult[Language.PlutusV4, Dijkstra] = plutusV4

/** Dijkstra's translations (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`,
  * cardano-ledger 9df9f98c) and its instances for Plutus V1 to V3, on Conway's, Babbage's and
  * Alonzo's translations, which are generic in the era. A V1 to V3 script runs at the top level
  * alone: at a sub-transaction's its information is the language's refusal
  * ([[transFailUnsupportedScriptInSubTx]]), the level read from the information
  * ([[LedgerTxInfo.withBothTxLevels]]). A body's outputs are translated with their indices as the
  * reference zips them with `[minBound ..] :: [TxIx]` (`TxInfo.hs:346-350, 406-410, 450-454`), so
  * none past the last index, 65,535 ([[xyz.kd5ujc.scarab.ledger.state.TxIx.enumerate]]).
  */
object DijkstraTxInfo:
  import PlutusTxInfos.*

  private val t = EraTx.dijkstra
  private val b = t.bodyOf
  private given b.Level[TxLevel.Top] = BothLevels.Top
  private given BabbageEraTxOut[Dijkstra] = EraTxOut.dijkstra

  /** `transTxCertV1V2` of Dijkstra (`TxInfo.hs:370-390`, cardano-ledger 9df9f98c): a registration,
    * an unregistration, a delegation to a pool and a pool's registration or retirement in the V1
    * form, a registration's deposit and an unregistration's refund left out; any other certificate
    * refused (`CertificateNotSupported`, Alonzo's). Unlike Conway's, it has no certificate of
    * Shelley's form to fall back on.
    */
  def transTxCertV1V2[E <: EraTag](txCert: TxCert[E])(using
      c: ConwayEraTxCert[E],
      inj: Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV1.DCert] =
    c.getRegDepositTxCert(txCert)
      .map((stakeCred, _) =>
        PV1.DCert.DCertDelegRegKey(PV1.StakingCredential.StakingHash(transCred(stakeCred)))
      )
      .orElse(
        c.getUnRegDepositTxCert(txCert)
          .map((stakeCred, _) =>
            PV1.DCert.DCertDelegDeRegKey(PV1.StakingCredential.StakingHash(transCred(stakeCred)))
          )
      )
      .orElse(c.getDelegTxCert(txCert).collect { case (stakeCred, Delegatee.DelegStake(keyHash)) =>
        PV1.DCert.DCertDelegDelegate(
          PV1.StakingCredential.StakingHash(transCred(stakeCred)),
          transKeyHash(keyHash)
        )
      })
      .orElse(c.getRegPoolTxCert(txCert).map { params =>
        PV1.DCert.DCertPoolRegister(transKeyHash(params.id), PV1.PubKeyHash(params.vrf.bytes))
      })
      .orElse(c.getRetirePoolTxCert(txCert).map { (poolId, retireEpochNo) =>
        PV1.DCert.DCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
      })
      .toRight(inj.inject(AlonzoContextError.CertificateNotSupported(txCert)))

  /** `guardDijkstraFeaturesForPlutusV1toV3` (`TxInfo.hs:486-519`, cardano-ledger 9df9f98c), of the
    * top-level body: direct deposits, then account balance intervals, then required top-level
    * guards, then guards that are scripts (in the guards' order), none of which a V1 to V3 context
    * can show, refused.
    */
  def guardDijkstraFeaturesForPlutusV1toV3[E <: EraTag](txBody: TxBody[TxLevel.Top, E])(using
      b: DijkstraEraTxBody[E],
      l: b.Level[TxLevel.Top],
      inj: Inject[DijkstraContextError[E], ContextError[E]]
  ): Either[ContextError[E], Unit] =
    val directDeposits = b.directDeposits[TxLevel.Top](txBody)
    val accountBalanceIntervals = b.accountBalanceIntervals[TxLevel.Top](txBody)
    val requiredTopLevelGuards = b.requiredTopLevelGuards[TxLevel.Top](txBody)
    val scriptHashes = b.guards[TxLevel.Top](txBody).toVector.collect {
      case Credential.ScriptHashObj(sh) => sh
    }
    if directDeposits.nonEmpty then
      Left(inj.inject(DijkstraContextError.DirectDepositsNotSupported(directDeposits)))
    else if accountBalanceIntervals.nonEmpty then
      Left(
        inj.inject(
          DijkstraContextError.AccountBalanceIntervalsNotSupported(accountBalanceIntervals)
        )
      )
    else
      NonEmptySet.fromSet(
        SortedSet.from(requiredTopLevelGuards.keySet)(using
          Credential.guardCredentialOrder.toOrdering
        )
      ) match
        case Some(neRequiredTopLevelGuards) =>
          Left(
            inj.inject(
              DijkstraContextError.RequiredTopLevelGuardsNotSupported(neRequiredTopLevelGuards)
            )
          )
        case None =>
          NonEmptyList.fromList(scriptHashes.toList) match
            case Some(neScriptHashes) =>
              Left(inj.inject(DijkstraContextError.GuardScriptHashesNotSupported(neScriptHashes)))
            case None => Right(())

  /** `transFailUnsupportedScriptInSubTx` (`TxInfo.hs:521-532`, cardano-ledger 9df9f98c): the
    * information of a V1 to V3 language at a sub-transaction, its refusal with the language and the
    * sub-transaction's id. Written for Dijkstra, as the transaction family at the sub-transaction
    * level is Dijkstra's alone; the reference's is for any era.
    */
  def transFailUnsupportedScriptInSubTx[L <: Language](tx: DijkstraTx[TxLevel.Sub])(using
      lang: PlutusLanguage[L]
  ): PlutusTxInfoResult[L, Dijkstra] =
    given BothLevels[TxLevel.Sub] = BothLevels.Sub
    PlutusTxInfoResult(
      Left(
        DijkstraContextError.UnsupportedScriptInSubTx(
          lang.language,
          b.id[TxLevel.Sub](t.body[TxLevel.Sub](tx))
        )
      )
    )

  /** `transTxCertV3` of Dijkstra (`TxInfo.hs:534-566`, cardano-ledger 9df9f98c): a certificate in
    * the V3 form, a registration's deposit and an unregistration's refund always given, unlike
    * Conway's at version 9. The reference's last case (`error "Impossible"`) has no counterpart:
    * the match is exhaustive.
    */
  def transTxCertV3(txCert: DijkstraTxCert): PV3.TxCert = txCert match
    case DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(params)) =>
      PV3.TxCert.TxCertPoolRegister(transKeyHash(params.id), PV1.PubKeyHash(params.vrf.bytes))
    case DijkstraTxCert.DijkstraTxCertPool(PoolCert.RetirePool(poolId, retireEpochNo)) =>
      PV3.TxCert.TxCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraRegCert(stakeCred, deposit)
        ) =>
      PV3.TxCert.TxCertRegStaking(transCred(stakeCred), Some(transCoinToLovelace(deposit)))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraUnRegCert(stakeCred, refund)
        ) =>
      PV3.TxCert.TxCertUnRegStaking(transCred(stakeCred), Some(transCoinToLovelace(refund)))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraDelegCert(stakeCred, delegatee)
        ) =>
      PV3.TxCert.TxCertDelegStaking(transCred(stakeCred), ConwayTxInfo.transDelegatee(delegatee))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraRegDelegCert(stakeCred, delegatee, deposit)
        ) =>
      PV3.TxCert.TxCertRegDeleg(
        transCred(stakeCred),
        ConwayTxInfo.transDelegatee(delegatee),
        transCoinToLovelace(deposit)
      )
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)) =>
      PV3.TxCert.TxCertAuthHotCommittee(
        ConwayTxInfo.transColdCommitteeCred(cold),
        ConwayTxInfo.transHotCommitteeCred(hot)
      )
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, _)) =>
      PV3.TxCert.TxCertResignColdCommittee(ConwayTxInfo.transColdCommitteeCred(cold))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayRegDRep(drepCred, deposit, _)) =>
      PV3.TxCert.TxCertRegDRep(ConwayTxInfo.transDRepCred(drepCred), transCoinToLovelace(deposit))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayUnRegDRep(drepCred, refund)) =>
      PV3.TxCert.TxCertUnRegDRep(ConwayTxInfo.transDRepCred(drepCred), transCoinToLovelace(refund))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayUpdateDRep(drepCred, _)) =>
      PV3.TxCert.TxCertUpdateDRep(ConwayTxInfo.transDRepCred(drepCred))

  /** `instance EraPlutusTxInfo 'PlutusV1 DijkstraEra` (`TxInfo.hs:333-368`, cardano-ledger
    * 9df9f98c): at the top level, Conway's guard of V1 and V2, then Dijkstra's, then Conway's V1
    * translations; at a sub-transaction, the language's refusal.
    */
  val plutusV1: EraPlutusTxInfo[Language.PlutusV1, Dijkstra] =
    new EraPlutusTxInfo[Language.PlutusV1, Dijkstra]:
      private given EraPlutusTxInfo[Language.PlutusV1, Dijkstra] = this

      override def plutusLanguage: PlutusLanguage[Language.PlutusV1] = PlutusLanguage.v1

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: DijkstraTxCert
      ): Either[ContextError[Dijkstra], PV1.DCert] = transTxCertV1V2[Dijkstra](txCert)

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          purpose: DijkstraPlutusPurpose[AsIxItem]
      ): Either[ContextError[Dijkstra], PV1.ScriptPurpose] =
        AlonzoTxInfo.transPlutusPurpose[Language.PlutusV1, Dijkstra](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Dijkstra]
      ): PlutusTxInfoResult[Language.PlutusV1, Dijkstra] =
        LedgerTxInfo.withBothTxLevels(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          PlutusTxInfoResult[Language.PlutusV1, Dijkstra](
            for
              _ <- ConwayTxInfo.guardConwayFeaturesForPlutusV1V2[Dijkstra, TxLevel.Top](txBody)
              _ <- guardDijkstraFeaturesForPlutusV1toV3[Dijkstra](txBody)
              timeRange <- ConwayTxInfo.transValidityInterval[Dijkstra](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              inputs <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(ConwayTxInfo.transTxInInfoV1(lti.ltiUTxO, _))
              _ <- b
                .referenceInputs[TxLevel.Top](txBody)
                .toVector
                .traverse(ConwayTxInfo.transTxInInfoV1(lti.ltiUTxO, _))
              outputs <- TxIx
                .enumerate(b.outputs[TxLevel.Top](txBody))
                .traverse((out, ix) =>
                  ConwayTxInfo.transTxOutV1[Dijkstra](TxOutSource.TxOutFromOutput(ix.index), out)
                )
              txCerts <- AlonzoTxInfo
                .transTxBodyCerts[Language.PlutusV1, Dijkstra, TxLevel.Top](lti.ltiProtVer, txBody)
              datums = AlonzoTxInfo
                .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
            yield PV1.TxInfo(
              txInfoInputs = inputs,
              txInfoOutputs = outputs,
              txInfoFee = transCoinToValue(b.fee(txBody)),
              txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoDCert = txCerts,
              txInfoWdrl = AlonzoTxInfo.transTxBodyWithdrawals[Dijkstra, TxLevel.Top](txBody),
              txInfoValidRange = timeRange,
              txInfoSignatories =
                AlonzoTxInfo.transTxBodyReqSignerHashes[Dijkstra, TxLevel.Top](txBody),
              txInfoData = datums,
              txInfoId = AlonzoTxInfo.transTxBodyId[Dijkstra, TxLevel.Top](txBody)
            )
          )
        } { sub =>
          transFailUnsupportedScriptInSubTx[Language.PlutusV1](sub.ltiTx)
        }

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          txInfo: PV1.TxInfo,
          purpose: DijkstraPlutusPurpose[AsIxItem],
          redeemerData: Data
      ): Either[ContextError[Dijkstra], PlutusV1Args] =
        AlonzoTxInfo.toPlutusV1Args[Dijkstra, T](lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Dijkstra],
          txIn: TxIn
      ): Either[ContextError[Dijkstra], PV1.TxInInfo] =
        ConwayTxInfo.transTxInInfoV1(utxo, txIn)

      override def lookupTxInfoResult(
          result: DijkstraTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV1, Dijkstra] = result.tirPlutusV1

  /** `instance EraPlutusTxInfo 'PlutusV2 DijkstraEra` (`TxInfo.hs:392-431`, cardano-ledger
    * 9df9f98c): at the top level, Conway's guard of V1 and V2, then Dijkstra's, then Babbage's V2
    * translations; at a sub-transaction, the language's refusal.
    */
  val plutusV2: EraPlutusTxInfo[Language.PlutusV2, Dijkstra] =
    new EraPlutusTxInfo[Language.PlutusV2, Dijkstra]:
      private given EraPlutusTxInfo[Language.PlutusV2, Dijkstra] = this

      override def plutusLanguage: PlutusLanguage[Language.PlutusV2] = PlutusLanguage.v2

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: DijkstraTxCert
      ): Either[ContextError[Dijkstra], PV1.DCert] = transTxCertV1V2[Dijkstra](txCert)

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          purpose: DijkstraPlutusPurpose[AsIxItem]
      ): Either[ContextError[Dijkstra], PV1.ScriptPurpose] =
        AlonzoTxInfo.transPlutusPurpose[Language.PlutusV2, Dijkstra](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Dijkstra]
      ): PlutusTxInfoResult[Language.PlutusV2, Dijkstra] =
        LedgerTxInfo.withBothTxLevels(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          PlutusTxInfoResult[Language.PlutusV2, Dijkstra](
            for
              _ <- ConwayTxInfo.guardConwayFeaturesForPlutusV1V2[Dijkstra, TxLevel.Top](txBody)
              _ <- guardDijkstraFeaturesForPlutusV1toV3[Dijkstra](txBody)
              timeRange <- ConwayTxInfo.transValidityInterval[Dijkstra](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              inputs <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(BabbageTxInfo.transTxInInfoV2(lti.ltiUTxO, _))
              refInputs <- b
                .referenceInputs[TxLevel.Top](txBody)
                .toVector
                .traverse(BabbageTxInfo.transTxInInfoV2(lti.ltiUTxO, _))
              outputs <- TxIx
                .enumerate(b.outputs[TxLevel.Top](txBody))
                .traverse((out, ix) =>
                  BabbageTxInfo.transTxOutV2[Dijkstra](TxOutSource.TxOutFromOutput(ix.index), out)
                )
              txCerts <- AlonzoTxInfo
                .transTxBodyCerts[Language.PlutusV2, Dijkstra, TxLevel.Top](lti.ltiProtVer, txBody)
              plutusRedeemers <- BabbageTxInfo
                .transTxRedeemers[Language.PlutusV2, Dijkstra, TxLevel.Top](lti)
              datums = AlonzoTxInfo
                .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
            yield PV2.TxInfo(
              txInfoInputs = inputs,
              txInfoReferenceInputs = refInputs,
              txInfoOutputs = outputs,
              txInfoFee = transCoinToValue(b.fee(txBody)),
              txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoDCert = txCerts,
              txInfoWdrl =
                AssocMap(AlonzoTxInfo.transTxBodyWithdrawals[Dijkstra, TxLevel.Top](txBody)),
              txInfoValidRange = timeRange,
              txInfoSignatories =
                AlonzoTxInfo.transTxBodyReqSignerHashes[Dijkstra, TxLevel.Top](txBody),
              txInfoRedeemers = plutusRedeemers,
              txInfoData = AssocMap(datums),
              txInfoId = AlonzoTxInfo.transTxBodyId[Dijkstra, TxLevel.Top](txBody)
            )
          )
        } { sub =>
          transFailUnsupportedScriptInSubTx[Language.PlutusV2](sub.ltiTx)
        }

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          txInfo: PV2.TxInfo,
          purpose: DijkstraPlutusPurpose[AsIxItem],
          redeemerData: Data
      ): Either[ContextError[Dijkstra], PlutusV2Args] =
        BabbageTxInfo.toPlutusV2Args[Dijkstra, T](lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Dijkstra],
          txIn: TxIn
      ): Either[ContextError[Dijkstra], PV2.TxInInfo] =
        BabbageTxInfo.transTxInInfoV2(utxo, txIn)

      override def lookupTxInfoResult(
          result: DijkstraTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV2, Dijkstra] = result.tirPlutusV2

  /** `instance EraPlutusTxInfo 'PlutusV3 DijkstraEra` (`TxInfo.hs:433-484`) and `instance
    * ConwayEraPlutusTxInfo 'PlutusV3 DijkstraEra` (`TxInfo.hs:568-569`, cardano-ledger 9df9f98c):
    * at the top level, Dijkstra's guard, then Conway's V3 translations, the reference inputs that
    * are inputs refused at every version (Conway's refuses them from version 11); at a
    * sub-transaction, the language's refusal.
    */
  val plutusV3: ConwayEraPlutusTxInfo[Language.PlutusV3, Dijkstra] =
    new ConwayEraPlutusTxInfo[Language.PlutusV3, Dijkstra]:
      private given ConwayEraPlutusTxInfo[Language.PlutusV3, Dijkstra] = this

      override def plutusLanguage: PlutusLanguage[Language.PlutusV3] = PlutusLanguage.v3

      override def toPlutusChangedParameters(ppu: PParamsUpdate[Dijkstra]): PV3.ChangedParameters =
        PV3.ChangedParameters(ToPlutusData[PParamsUpdate[Dijkstra]].toPlutusData(ppu))

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: DijkstraTxCert
      ): Either[ContextError[Dijkstra], PV3.TxCert] =
        Right(transTxCertV3(txCert))

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          purpose: DijkstraPlutusPurpose[AsIxItem]
      ): Either[ContextError[Dijkstra], PV3.ScriptPurpose] =
        ConwayTxInfo.transPlutusPurposeV3[Dijkstra](lti.ltiProtVer, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti0: LedgerTxInfo[T, Dijkstra]
      ): PlutusTxInfoResult[Language.PlutusV3, Dijkstra] =
        LedgerTxInfo.withBothTxLevels(lti0) { lti =>
          val tx = lti.ltiTx
          val txBody = t.body[TxLevel.Top](tx)
          PlutusTxInfoResult[Language.PlutusV3, Dijkstra](
            for
              _ <- guardDijkstraFeaturesForPlutusV1toV3[Dijkstra](txBody)
              timeRange <- ConwayTxInfo.transValidityInterval[Dijkstra](
                lti.ltiSlotToPOSIXTime,
                b.vldt[TxLevel.Top](txBody)
              )
              inputsInfo <- b
                .inputs[TxLevel.Top](txBody)
                .toVector
                .traverse(ConwayTxInfo.transTxInInfoV3(lti.ltiUTxO, _))
              refInputsInfo <- b
                .referenceInputs[TxLevel.Top](txBody)
                .toVector
                .traverse(ConwayTxInfo.transTxInInfoV3(lti.ltiUTxO, _))
              _ <- ConwayTxInfo
                .checkReferenceInputsNotDisjointFromInputs[Dijkstra, TxLevel.Top](txBody)
              outputs <- TxIx
                .enumerate(b.outputs[TxLevel.Top](txBody))
                .traverse((out, ix) =>
                  BabbageTxInfo.transTxOutV2[Dijkstra](TxOutSource.TxOutFromOutput(ix.index), out)
                )
              txCerts <- AlonzoTxInfo
                .transTxBodyCerts[Language.PlutusV3, Dijkstra, TxLevel.Top](lti.ltiProtVer, txBody)
              plutusRedeemers <- BabbageTxInfo
                .transTxRedeemers[Language.PlutusV3, Dijkstra, TxLevel.Top](lti)
              datums = AlonzoTxInfo
                .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
            yield PV3.TxInfo(
              txInfoInputs = inputsInfo,
              txInfoReferenceInputs = refInputsInfo,
              txInfoOutputs = outputs,
              txInfoFee = transCoinToLovelace(b.fee(txBody)),
              txInfoMint = ConwayTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
              txInfoTxCerts = txCerts,
              txInfoWdrl = ConwayTxInfo.transTxBodyWithdrawals[Dijkstra, TxLevel.Top](txBody),
              txInfoValidRange = timeRange,
              txInfoSignatories =
                AlonzoTxInfo.transTxBodyReqSignerHashes[Dijkstra, TxLevel.Top](txBody),
              txInfoRedeemers = plutusRedeemers,
              txInfoData = AssocMap(datums),
              txInfoId = ConwayTxInfo.transTxBodyId[Dijkstra, TxLevel.Top](txBody),
              txInfoVotes =
                ConwayTxInfo.transVotingProcedures(b.votingProcedures[TxLevel.Top](txBody)),
              txInfoProposalProcedures = b
                .proposalProcedures[TxLevel.Top](txBody)
                .toVector
                .map(ConwayTxInfo.transProposal[Language.PlutusV3, Dijkstra]),
              txInfoCurrentTreasuryAmount =
                b.currentTreasuryValue[TxLevel.Top](txBody).map(transCoinToLovelace),
              txInfoTreasuryDonation =
                Option.unless(b.treasuryDonation[TxLevel.Top](txBody).value == 0)(
                  transCoinToLovelace(b.treasuryDonation[TxLevel.Top](txBody))
                )
            )
          )
        } { sub =>
          transFailUnsupportedScriptInSubTx[Language.PlutusV3](sub.ltiTx)
        }

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          txInfo: PV3.TxInfo,
          purpose: DijkstraPlutusPurpose[AsIxItem],
          redeemerData: Data
      ): Either[ContextError[Dijkstra], PlutusV3Args] =
        ConwayTxInfo.toPlutusV3Args[Dijkstra, T](lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Dijkstra],
          txIn: TxIn
      ): Either[ContextError[Dijkstra], PV3.TxInInfo] =
        ConwayTxInfo.transTxInInfoV3(utxo, txIn)

      override def lookupTxInfoResult(
          result: DijkstraTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV3, Dijkstra] = result.tirPlutusV3
