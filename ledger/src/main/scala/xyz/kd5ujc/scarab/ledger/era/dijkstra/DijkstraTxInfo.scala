// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.data.{NonEmptyList, NonEmptySet}
import cats.syntax.all.*
import scala.collection.immutable.{SortedMap, SortedSet}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  Address,
  DataSite,
  GovActionId,
  RewardAccount,
  TranslationError,
  TxIn,
  TxOutSource,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  AlonzoTxInfo,
  AsIx,
  AsIxItem,
  AssocMap,
  BabbageContextError,
  BabbageEraTxOut,
  BabbageTxInfo,
  BinaryData,
  BothLevels,
  ConwayEraTxCert,
  ConwayGovCert,
  ContextError,
  Data,
  Datum,
  Delegatee,
  DijkstraEraTxBody,
  EraPlutusContext,
  EraPlutusTxInfo,
  EraScript,
  EraTx,
  EraTxOut,
  GovAction,
  GovActionPurpose,
  GovPurposeId,
  ImpossibleContext,
  Inject,
  Language,
  LedgerLevelTxInfo,
  LedgerTxInfo,
  MultiAssetValue,
  PParamsUpdate,
  PV1,
  PV2,
  PV3,
  PV4,
  PlutusLanguage,
  PlutusPurpose,
  PlutusRunnable,
  PlutusTxInfoResult,
  PlutusTxInfos,
  PlutusV1Args,
  PlutusV2Args,
  PlutusV3Args,
  PlutusV4Args,
  PoolCert,
  ProposalProcedure,
  ProtVer,
  SupportedLanguage,
  SupportedPlutusRunnable,
  ToPlutusData,
  TxBody,
  TxCert,
  TxLevel,
  TxOut,
  UTxO,
  UnitInterval,
  ValidityInterval,
  Value,
  Version,
  VotingProcedures
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayContextError, ConwayEraPlutusTxInfo, ConwayTxInfo}
import xyz.kd5ujc.scarab.ledger.state.{
  AccountAddress,
  Coin,
  ColdCommittee,
  Credential,
  Dijkstra,
  EraTag,
  Guard,
  KeyRole,
  ScriptHash,
  TxIx,
  Withdrawals
}
import xyz.kd5ujc.scarab.plutus.PlutusLanguage as Lang

// Dijkstra's script contexts (`eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/TxInfo.hs`,
// cardano-ledger 9df9f98c): the types the annotated transaction names, the translations of Plutus
// V1 to V4 and Dijkstra's `EraPlutusContext` instance.

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

  // ---- Plutus V4 (`TxInfo.hs:571-1058`, cardano-ledger 9df9f98c)

  /** A purpose by its index alone (`hoistPlutusPurpose toAsIx`). */
  private def asIx(p: DijkstraPlutusPurpose[AsIxItem]): DijkstraPlutusPurpose[AsIx] =
    EraScript.dijkstra.hoistPlutusPurpose[AsIxItem, AsIx](
      [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsIx(a)
    )(p)

  /** `transTxInV4` (`TxInfo.hs:631-632`, cardano-ledger 9df9f98c). */
  def transTxInV4(txIn: TxIn): PV4.TxOutRef =
    PV4.TxOutRef(ConwayTxInfo.transTxId(txIn.id), txIn.index)

  /** `transTxInInfoV4` (`TxInfo.hs:634-648`, cardano-ledger 9df9f98c): an input with the output the
    * UTxO set holds for it, in the V4 form.
    */
  def transTxInInfoV4[E <: EraTag](utxo: UTxO[E], txIn: TxIn)(using
      o: BabbageEraTxOut[E],
      injA: Inject[AlonzoContextError[E], ContextError[E]],
      injB: Inject[BabbageContextError[E], ContextError[E]],
      injD: Inject[DijkstraContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV4.TxInInfo] =
    for
      txOut <- AlonzoTxInfo.transLookupTxOut[E, ContextError[E]](utxo, txIn)
      plutusTxOut <- transTxOutV4(TxOutSource.TxOutFromInput(txIn), txOut)
    yield PV4.TxInInfo(transTxInV4(txIn), plutusTxOut)

  /** `transTxOutV4` (`TxInfo.hs:650-690`, cardano-ledger 9df9f98c): the output in the V4 form, the
    * account a base address stakes to by its credential; a pointer address refused
    * (`PointerPresentInOutput`, Dijkstra's) and a Byron address (`ByronTxOutInContext`, Babbage's).
    * An inline datum is read as data only once the address is accepted, as the reference's lazy
    * `datum` is forced only then; its failure is the node's own `Undecodable`, as in
    * [[BabbageTxInfo.transTxOutV2]].
    */
  def transTxOutV4[E <: EraTag](txOutSource: TxOutSource, txOut: TxOut[E])(using
      o: BabbageEraTxOut[E],
      injB: Inject[BabbageContextError[E], ContextError[E]],
      injD: Inject[DijkstraContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV4.TxOut] =
    val value = AlonzoTxInfo.transValue(v(o.value(txOut)))
    val referenceScript =
      BabbageTxInfo.transReferenceScript(o.referenceScript(txOut))(using o.scripts)
    def datum: Either[ContextError[E], PV2.OutputDatum] = o.datum(txOut) match
      case Datum.NoDatum       => Right(PV2.OutputDatum.NoOutputDatum)
      case Datum.DatumHash(dh) => Right(PV2.OutputDatum.OutputDatumHash(transDataHash(dh)))
      case Datum.Datum(binaryData) =>
        BinaryData
          .binaryDataToData(binaryData)
          .bimap(
            BabbageTxInfo.undecodable[E](DataSite.InlineDatum, _),
            d => PV2.OutputDatum.OutputDatum(PV1.Datum(d.getPlutusData))
          )
    val addr: Either[ContextError[E], PV4.Address] = o.address(txOut).kind match
      case Address.Kind.Shelley(_, payment, stake, pointer) =>
        (stake, pointer) match
          case (Some(stakeCred), _) =>
            Right(
              PV4.Address(transCredFlat(payment), Some(PV4.AccountId(transCredFlat(stakeCred))))
            )
          case (None, Some(_)) =>
            Left(injD.inject(DijkstraContextError.PointerPresentInOutput(txOutSource)))
          case (None, None) => Right(PV4.Address(transCredFlat(payment), None))
      case Address.Kind.Byron(_) =>
        Left(injB.inject(BabbageContextError.ByronTxOutInContext(txOutSource)))
    for
      a <- addr
      d <- datum
    yield PV4.TxOut(a, value, d, referenceScript)

  /** `transWithdrawals` (`TxInfo.hs:692-693`, cardano-ledger 9df9f98c): by the account's
    * credential, in the accounts' order.
    */
  def transWithdrawals(withdrawals: Withdrawals): AssocMap[PV1.Credential, PV1.Lovelace] =
    ConwayTxInfo.transMap(transAccountAddressToCredential, transCoinToLovelace)(
      withdrawals.unWithdrawals
    )

  /** `transDirectDeposits` (`TxInfo.hs:695-696`, cardano-ledger 9df9f98c): by the account's
    * credential, in the accounts' order.
    */
  def transDirectDeposits(
      deposits: Map[RewardAccount, Coin]
  ): AssocMap[PV1.Credential, PV1.Lovelace] =
    ConwayTxInfo.transMap(transAccountAddressToCredentialFlat, transCoinToLovelace)(deposits)

  /** `transCredToAccountId` (`TxInfo.hs:698-699`, cardano-ledger 9df9f98c). */
  def transCredToAccountId[R <: KeyRole](c: Credential[R]): PV4.AccountId =
    PV4.AccountId(transCred(c))

  /** `transTxCertV4` (`TxInfo.hs:701-730`, cardano-ledger 9df9f98c): a certificate in the V4 form,
    * an account's by its `AccountId`, every deposit and refund given. Written for Dijkstra's
    * certificates, whose match is exhaustive, so the reference's last case (`error "Impossible"`)
    * has no counterpart.
    */
  def transTxCertV4(txCert: DijkstraTxCert): PV4.TxCert = txCert match
    case DijkstraTxCert.DijkstraTxCertPool(PoolCert.RegPool(params)) =>
      PV4.TxCert.TxCertPoolRegister(transKeyHash(params.id), PV1.PubKeyHash(params.vrf.bytes))
    case DijkstraTxCert.DijkstraTxCertPool(PoolCert.RetirePool(poolId, retireEpochNo)) =>
      PV4.TxCert.TxCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraRegCert(stakeCred, deposit)
        ) =>
      PV4.TxCert.TxCertRegAccount(transCredToAccountId(stakeCred), transCoinToLovelace(deposit))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraUnRegCert(stakeCred, refund)
        ) =>
      PV4.TxCert.TxCertUnRegAccount(transCredToAccountId(stakeCred), transCoinToLovelace(refund))
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraDelegCert(stakeCred, delegatee)
        ) =>
      PV4.TxCert.TxCertDelegAccount(
        transCredToAccountId(stakeCred),
        ConwayTxInfo.transDelegatee(delegatee)
      )
    case DijkstraTxCert.DijkstraTxCertDeleg(
          DijkstraDelegCert.DijkstraRegDelegCert(stakeCred, delegatee, deposit)
        ) =>
      PV4.TxCert.TxCertRegAccountDeleg(
        transCredToAccountId(stakeCred),
        ConwayTxInfo.transDelegatee(delegatee),
        transCoinToLovelace(deposit)
      )
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)) =>
      PV4.TxCert.TxCertAuthHotCommittee(
        ConwayTxInfo.transColdCommitteeCred(cold),
        ConwayTxInfo.transHotCommitteeCred(hot)
      )
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, _)) =>
      PV4.TxCert.TxCertResignColdCommittee(ConwayTxInfo.transColdCommitteeCred(cold))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayRegDRep(drepCred, deposit, _)) =>
      PV4.TxCert.TxCertRegDRep(ConwayTxInfo.transDRepCred(drepCred), transCoinToLovelace(deposit))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayUnRegDRep(drepCred, refund)) =>
      PV4.TxCert.TxCertUnRegDRep(ConwayTxInfo.transDRepCred(drepCred), transCoinToLovelace(refund))
    case DijkstraTxCert.DijkstraTxCertGov(ConwayGovCert.ConwayUpdateDRep(drepCred, _)) =>
      PV4.TxCert.TxCertUpdateDRep(ConwayTxInfo.transDRepCred(drepCred))

  /** `transTxBodyRequiredTopLevelGuards` (`TxInfo.hs:732-736`, cardano-ledger 9df9f98c): each
    * required guard's credential with its datum, in the credentials' order.
    */
  def transTxBodyRequiredTopLevelGuards[L <: TxLevel](txBody: TxBody[L, Dijkstra])(using
      b.Level[L]
  ): AssocMap[PV1.Credential, Option[PV1.Datum]] =
    ConwayTxInfo.transMap(
      transCred[Guard],
      (d: Option[Data]) => d.map(x => transDatum(x.getPlutusData))
    )(b.requiredTopLevelGuards[L](txBody))(using Credential.guardCredentialOrder.toOrdering)

  /** `transAccountAddressToAccountId` (`TxInfo.hs:738-739`, cardano-ledger 9df9f98c). */
  def transAccountAddressToAccountId(a: RewardAccount): PV4.AccountId =
    PV4.AccountId(transAccountAddressFlat(a))

  /** `transAccountAddressToCredential` (`TxInfo.hs:741-742`, cardano-ledger 9df9f98c). */
  def transAccountAddressToCredential(a: AccountAddress): PV1.Credential = transAccountAddress(a)

  /** `transAccountAddressToCredential` of an account the node holds untyped (a direct deposit's).
    */
  def transAccountAddressToCredentialFlat(a: RewardAccount): PV1.Credential =
    transAccountAddressFlat(a)

  /** `transValidityInterval` of Dijkstra (`TxInfo.hs:745-756`, cardano-ledger 9df9f98c): each end
    * in POSIX time, or open; a slot the clock cannot translate refused
    * ([[ConwayTxInfo.transSlotToPOSIXTime]]), the start first.
    */
  def transValidityInterval[E <: EraTag](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      vi: ValidityInterval
  )(using
      Inject[AlonzoContextError[E], ContextError[E]]
  ): Either[ContextError[E], PV4.POSIXTimeRange] =
    def transSlot(slot: BigInt) = ConwayTxInfo.transSlotToPOSIXTime[E](slotToPOSIXTime, slot)
    for
      pFrom <- vi.invalidBefore.traverse(transSlot)
      pTo <- vi.invalidHereafter.traverse(transSlot)
    yield PV4.POSIXTimeRange(pFrom, pTo)

  /** `transAccountBalanceInterval` (`TxInfo.hs:758-763`, cardano-ledger 9df9f98c). */
  def transAccountBalanceInterval(i: AccountBalanceInterval): PV4.AccountBalanceInterval = i match
    case AccountBalanceInterval.AccountBalanceExact(c) =>
      PV4.AccountBalanceInterval.AccountBalanceExact(transCoinToLovelace(c))
    case AccountBalanceInterval.AccountBalanceLowerBound(l) =>
      PV4.AccountBalanceInterval.AccountBalanceLowerBound(transCoinToLovelace(l.unInclusive))
    case AccountBalanceInterval.AccountBalanceUpperBound(u) =>
      PV4.AccountBalanceInterval.AccountBalanceUpperBound(transCoinToLovelace(u.unExclusive))
    case AccountBalanceInterval.AccountBalanceBothBounds(l, u) =>
      PV4.AccountBalanceInterval.AccountBalanceBothBounds(
        transCoinToLovelace(l.unInclusive),
        transCoinToLovelace(u.unExclusive)
      )

  /** `transAccountBalanceIntervals` (`TxInfo.hs:765-768`, cardano-ledger 9df9f98c): by the
    * account's `AccountId`, in the accounts' order.
    */
  def transAccountBalanceIntervals(
      intervals: Map[RewardAccount, AccountBalanceInterval]
  ): PV4.AccountBalanceIntervals =
    PV4.AccountBalanceIntervals(
      ConwayTxInfo.transMap(transAccountAddressToAccountId, transAccountBalanceInterval)(intervals)
    )

  /** `transTxBodyGuards` (`TxInfo.hs:770-771`, cardano-ledger 9df9f98c): the guards' credentials in
    * the order read.
    */
  def transTxBodyGuards[L <: TxLevel](txBody: TxBody[L, Dijkstra])(using
      b.Level[L]
  ): Vector[PV1.Credential] =
    b.guards[L](txBody).toVector.map(transCred)

  /** `scriptPurposeToScriptInfo` of V4 (`TxInfo.hs:773-807`, cardano-ledger 9df9f98c): the purpose,
    * with the datum of a spending script; a guard's with the whole transaction at the top level
    * ([[transGuardingTopTxInfo]]) and none at a sub-transaction's, its script's hash looked up by
    * its purpose first (`ScriptHashNotFoundForPurpose`). Written for Dijkstra, the one era with
    * Plutus V4; the reference's is for any era with Dijkstra's classes.
    */
  def scriptPurposeToScriptInfo[T <: TxLevel](
      datum: Option[PV1.Datum],
      lti: LedgerTxInfo[T, Dijkstra],
      txInfo: PV4.TxInfo,
      ixPlutusPurpose: DijkstraPlutusPurpose[AsIx],
      scriptPurpose: PV4.ScriptPurpose
  )(using
      EraPlutusTxInfo[Language.PlutusV4, Dijkstra]
  ): Either[ContextError[Dijkstra], PV4.ScriptInfo] =
    scriptPurpose match
      case PV4.ScriptPurpose.Spending(_, ref) => Right(PV4.ScriptInfo.SpendingScript(ref, datum))
      case PV4.ScriptPurpose.Minting(_, currencySymbol) =>
        Right(PV4.ScriptInfo.MintingScript(currencySymbol))
      case PV4.ScriptPurpose.Withdrawing(_, credential) =>
        Right(PV4.ScriptInfo.WithdrawingScript(PV4.AccountId(credential)))
      case PV4.ScriptPurpose.Certifying(_, ix, cert) =>
        Right(PV4.ScriptInfo.CertifyingScript(ix, cert))
      case PV4.ScriptPurpose.Voting(_, vote) => Right(PV4.ScriptInfo.VotingScript(vote))
      case PV4.ScriptPurpose.Proposing(_, ix, proposal) =>
        Right(PV4.ScriptInfo.ProposingScript(ix, proposal))
      case PV4.ScriptPurpose.Guarding(_, ix) =>
        for
          guardingScriptHash <- lti.ltiScriptHashesUsed
            .get(ixPlutusPurpose)
            .toRight(DijkstraContextError.ScriptHashNotFoundForPurpose[Dijkstra](ixPlutusPurpose))
          topTxInfo <- LedgerTxInfo.withBothTxLevels(lti)(
            transGuardingTopTxInfo(txInfo, guardingScriptHash, _).map(Some(_))
          )(_ => Right(None))
        yield PV4.ScriptInfo.GuardingScript(ix, topTxInfo)

  /** `scriptHashFromScriptPurpose` (`TxInfo.hs:809-817`, cardano-ledger 9df9f98c). */
  def scriptHashFromScriptPurpose(scriptPurpose: PV4.ScriptPurpose): PV1.ScriptHash =
    scriptPurpose match
      case PV4.ScriptPurpose.Spending(sh, _)      => sh
      case PV4.ScriptPurpose.Minting(sh, _)       => sh
      case PV4.ScriptPurpose.Withdrawing(sh, _)   => sh
      case PV4.ScriptPurpose.Certifying(sh, _, _) => sh
      case PV4.ScriptPurpose.Voting(sh, _)        => sh
      case PV4.ScriptPurpose.Proposing(sh, _, _)  => sh
      case PV4.ScriptPurpose.Guarding(sh, _)      => sh

  /** What `transGuardingTopTxInfo` folds over each transaction of the batch (`foldMapBatch`): a
    * level's body fields and witness datums.
    */
  private final case class BatchTx(
      txId: Bytes,
      withdrawals: Withdrawals,
      directDeposits: Map[RewardAccount, Coin],
      vldt: ValidityInterval,
      votes: VotingProcedures[Dijkstra],
      datums: SortedMap[Bytes, Data],
      mint: Map[Bytes, Map[Bytes, BigInt]],
      requiredTopLevelGuards: Map[Credential[Guard], Option[Data]],
      treasuryDonation: Coin
  )

  private def batchTx[L <: TxLevel](tx: DijkstraTx[L])(using b.Level[L]): BatchTx =
    val txBody = t.body[L](tx)
    BatchTx(
      b.id[L](txBody),
      b.withdrawals[L](txBody),
      b.directDeposits[L](txBody),
      b.vldt[L](txBody),
      b.votingProcedures[L](txBody),
      t.witsOf.datsTxWits(t.wits[L](tx)).datums,
      b.mint[L](txBody),
      b.requiredTopLevelGuards[L](txBody),
      b.treasuryDonation[L](txBody)
    )

  /** `Map.unionWith (<>)` of coins (`instance Semigroup DirectDeposits`,
    * `libs/cardano-ledger-core/src/Cardano/Ledger/Address.hs:1001-1002`, cardano-ledger 9df9f98c).
    */
  private def unionCoins(
      a: Map[RewardAccount, Coin],
      c: Map[RewardAccount, Coin]
  ): Map[RewardAccount, Coin] =
    c.foldLeft(a) { case (acc, (k, v)) =>
      acc.updated(k, acc.get(k).fold(v)(p => Coin(p.value + v.value)))
    }

  /** `instance Semigroup ValidityInterval` (`Allegra/Scripts.hs:138-142`, cardano-ledger 9df9f98c):
    * the later start and the earlier end, an open end giving way to a closed one.
    */
  private def unionValidity(a: ValidityInterval, c: ValidityInterval): ValidityInterval =
    ValidityInterval(
      (a.invalidBefore, c.invalidBefore)
        .mapN(_ max _)
        .orElse(a.invalidBefore)
        .orElse(c.invalidBefore),
      (a.invalidHereafter, c.invalidHereafter)
        .mapN(_ min _)
        .orElse(a.invalidHereafter)
        .orElse(c.invalidHereafter)
    )

  /** `instance Semigroup (VotingProcedures era)` (`Conway/Governance/Procedures.hs:437-440`,
    * cardano-ledger 9df9f98c): each voter's votes, a later vote on an action replacing an earlier.
    */
  private def unionVotes(
      a: SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]],
      c: SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]]
  ): SortedMap[Voter, SortedMap[GovActionId, VotingProcedure]] =
    c.foldLeft(a) { case (acc, (voter, votes)) =>
      acc.updated(voter, acc.get(voter).fold(votes)(_ ++ votes))
    }

  /** `filterMultiAsset` of the quantities `keep` (`Mary/Value.hs:901-912`, cardano-ledger
    * 9df9f98c): a policy left with no asset dropped.
    */
  private def filterMint(
      m: Map[Bytes, Map[Bytes, BigInt]],
      keep: BigInt => Boolean
  ): Map[Bytes, Map[Bytes, BigInt]] =
    m.flatMap((policy, names) =>
      Option(names.filter((_, q) => keep(q))).filter(_.nonEmpty).map(policy -> _)
    )

  /** `instance Semigroup MultiAsset` (`Mary/Value.hs:174-176`, cardano-ledger 9df9f98c): the
    * quantities summed, a zero sum and a policy left with no asset dropped (`canonicalMapUnion`).
    */
  private def unionMint(
      a: Map[Bytes, Map[Bytes, BigInt]],
      c: Map[Bytes, Map[Bytes, BigInt]]
  ): Map[Bytes, Map[Bytes, BigInt]] =
    c.foldLeft(a) { case (acc, (policy, names)) =>
      val merged = names.foldLeft(acc.getOrElse(policy, Map.empty[Bytes, BigInt])) {
        case (ns, (name, q)) =>
          val sum = ns.getOrElse(name, BigInt(0)) + q
          if sum == 0 then ns - name else ns.updated(name, sum)
      }
      if merged.isEmpty then acc - policy else acc.updated(policy, merged)
    }

  /** `transGuardingTopTxInfo` (`TxInfo.hs:819-925`, cardano-ledger 9df9f98c): the whole transaction
    * for a guard at the top level. Each sub-transaction's V4 information is the one memoised for it
    * (`lookupTxInfoResult` of the top level's `LedgerTopTxInfo`), its failure the sub-transaction's
    * (`SubTxContextError`) and a missing one `ImpossibleContextError`, in the sub-transactions'
    * order; then the batch, the sub-transactions in order and the top level last (`foldMapBatch`),
    * its validity interval's translation failing after them. Written for Dijkstra, the one era with
    * Plutus V4.
    */
  def transGuardingTopTxInfo(
      txInfo: PV4.TxInfo,
      guardingScriptHash: Bytes,
      lti: LedgerTxInfo[TxLevel.Top, Dijkstra]
  )(using
      l: EraPlutusTxInfo[Language.PlutusV4, Dijkstra]
  ): Either[ContextError[Dijkstra], PV4.TopTxInfo] =
    val tx = lti.ltiTx
    val txBody = t.body[TxLevel.Top](tx)
    val subTxInfoResults = lti.ltiLevelTxInfo match
      case LedgerLevelTxInfo.LedgerTopTxInfo(results) => results
    // the datum the transaction's required top-level guard of the guard's script gives, which
    // `MalformedGuardDatums` makes present
    def lookupRequiredTopLevelGuardDatum(btx: BatchTx): Option[(Bytes, Data)] =
      btx.requiredTopLevelGuards
        .collectFirst {
          case (Credential.ScriptHashObj(h), datum) if h.bytes == guardingScriptHash => datum
        }
        .flatten
        .map(btx.txId -> _)
    val subTxs = b.subTransactions(txBody).entries.map(_._2)
    val subBatch = subTxs.map { subTx =>
      given BothLevels[TxLevel.Sub] = BothLevels.Sub
      batchTx[TxLevel.Sub](subTx)
    }
    val topBatch = batchTx[TxLevel.Top](tx)
    for
      subTransactions <- subBatch.traverse { sub =>
        (subTxInfoResults.get(sub.txId) match
          case None =>
            Left(
              summon[Inject[AlonzoContextError[Dijkstra], ContextError[Dijkstra]]].inject(
                AlonzoContextError.ImpossibleContextError(
                  ImpossibleContext.MissingTxInfoResult(sub.txId)
                )
              )
            )
          case Some(txInfoResults) => l.lookupTxInfoResult(txInfoResults).unPlutusTxInfoResult
        ).leftMap(DijkstraContextError.SubTxContextError[Dijkstra](sub.txId, _))
      }
      batch = subBatch :+ topBatch
      batchTimeRange <- transValidityInterval[Dijkstra](
        lti.ltiSlotToPOSIXTime,
        batch.map(_.vldt).foldLeft(ValidityInterval(None, None))(unionValidity)
      )
    yield
      val batchTxInfo = subTransactions :+ txInfo
      val batchGuardDatums =
        (subBatch.flatMap(lookupRequiredTopLevelGuardDatum) ++
          lookupRequiredTopLevelGuardDatum(topBatch)).toMap
      val batchMints = batch.map(x => filterMint(x.mint, _ > 0)).foldLeft(Map.empty)(unionMint)
      val batchBurns = batch.map(x => filterMint(x.mint, _ < 0)).foldLeft(Map.empty)(unionMint)
      val batchVotes = batch
        .map(_.votes.unVotingProcedures)
        .foldLeft(SortedMap.empty[Voter, SortedMap[GovActionId, VotingProcedure]])(unionVotes)
      val batchDatums = batch
        .map(_.datums)
        .foldLeft(SortedMap.empty[Bytes, Data])((acc, ds) => ds ++ acc)
      val batchRequiredTopLevelGuards = SortedSet.from(
        batch.flatMap(_.requiredTopLevelGuards.keys)
      )(using Credential.guardCredentialOrder.toOrdering)
      // the script hashes in the ledger's order, which is Plutus's
      val batchRedeemerHashes = batchTxInfo
        .flatMap(_.txInfoRedeemers.toList.map((sp, _) => scriptHashFromScriptPurpose(sp)))
        .distinct
        .sortBy(_.getScriptHash)
      // the ledger's rules make every treasury amount given the same, so the first
      val batchCurrentTreasuryAmount =
        batchTxInfo.flatMap(_.txInfoCurrentTreasuryAmount).headOption
      val simplified = PV4.TopTxInfoSimplified(
        ttisIds = batchTxInfo.map(_.txInfoId),
        ttisInputs = batchTxInfo.flatMap(_.txInfoInputs),
        ttisReferenceInputs = batchTxInfo.flatMap(_.txInfoReferenceInputs),
        ttisOutputs = batchTxInfo.flatMap(_.txInfoOutputs),
        ttisMints = ConwayTxInfo.transMintValue(batchMints),
        ttisBurns = ConwayTxInfo.transMintValue(batchBurns),
        ttisTxCerts = batchTxInfo.flatMap(_.txInfoTxCerts),
        ttisWithdrawals = transWithdrawals(batch.map(_.withdrawals).combineAll),
        ttisDirectDeposits =
          transDirectDeposits(batch.map(_.directDeposits).foldLeft(Map.empty)(unionCoins)),
        ttisValidRange = batchTimeRange,
        ttisGuards = batchTxInfo.flatMap(_.txInfoGuards),
        ttisRequiredTopLevelGuards = batchRequiredTopLevelGuards.toVector.map(transCred),
        ttisRedeemerHashes = batchRedeemerHashes,
        ttisData = AssocMap(AlonzoTxInfo.transDatums(batchDatums)),
        ttisVotes = transVotingProcedures(new VotingProcedures[Dijkstra](batchVotes)),
        ttisProposalProcedures = batchTxInfo.flatMap(_.txInfoProposalProcedures),
        ttisCurrentTreasuryAmount = batchCurrentTreasuryAmount,
        ttisTreasuryDonations = transCoinToLovelace(Coin(batch.map(_.treasuryDonation.value).sum))
      )
      PV4.TopTxInfo(
        topTxInfoSubTransactions = subTransactions,
        topTxInfoDatums = ConwayTxInfo.transMap(
          ConwayTxInfo.transTxId,
          (d: Data) => transDatum(d.getPlutusData)
        )(batchGuardDatums),
        topTxInfoStartingAccountBalanceIntervals =
          transAccountBalanceIntervals(b.startingAccountBalanceIntervals(txBody)),
        topTxInfoSimplified = simplified
      )

  /** `toPlutusV4Args` (`TxInfo.hs:927-956`, cardano-ledger 9df9f98c): the script context of the
    * information, the redeemer, the purpose's information and the hash of the script, the purpose
    * first, then the spending datum ([[AlonzoTxInfo.spendingDatum]]). Written for Dijkstra, the one
    * era with Plutus V4.
    */
  def toPlutusV4Args[T <: TxLevel](
      lti: LedgerTxInfo[T, Dijkstra],
      txInfo: PV4.TxInfo,
      plutusPurpose: DijkstraPlutusPurpose[AsIxItem],
      redeemerData: Data
  )(using
      l: EraPlutusTxInfo[Language.PlutusV4, Dijkstra]
  ): Either[ContextError[Dijkstra], PlutusV4Args] =
    for
      scriptPurpose <- l.toPlutusScriptPurpose(lti, plutusPurpose)
      spendDatum <- AlonzoTxInfo.spendingDatum(lti, plutusPurpose)
      scriptInfo <- scriptPurposeToScriptInfo(
        spendDatum.map(d => transDatum(d.getPlutusData)),
        lti,
        txInfo,
        asIx(plutusPurpose),
        scriptPurpose
      )
    yield PlutusV4Args(
      PV4.ScriptContext(
        txInfo,
        BabbageTxInfo.transRedeemer(redeemerData),
        scriptInfo,
        scriptHashFromScriptPurpose(scriptPurpose)
      )
    )

  /** `transPlutusPurposeV4` (`TxInfo.hs:958-990`, cardano-ledger 9df9f98c): the purpose in the V4
    * form with the hash of the script used for it, which is looked up first
    * (`ScriptHashNotFoundForPurpose`). Written for Dijkstra's purposes, whose match is exhaustive,
    * so the reference's last case (`PlutusPurposeNotSupported`) has no counterpart.
    */
  def transPlutusPurposeV4[T <: TxLevel](
      lti: LedgerTxInfo[T, Dijkstra],
      plutusPurpose: DijkstraPlutusPurpose[AsIxItem]
  )(using
      l: ConwayEraPlutusTxInfo[Language.PlutusV4, Dijkstra]
  ): Either[ContextError[Dijkstra], PV4.ScriptPurpose] =
    val pv = lti.ltiProtVer
    val ixPurpose = asIx(plutusPurpose)
    lti.ltiScriptHashesUsed.get(ixPurpose) match
      case None => Left(DijkstraContextError.ScriptHashNotFoundForPurpose[Dijkstra](ixPurpose))
      case Some(scriptHash) =>
        val sh = PV1.ScriptHash(scriptHash)
        plutusPurpose match
          case DijkstraPlutusPurpose.DijkstraSpending(AsIxItem(_, txIn)) =>
            Right(
              PV4.ScriptPurpose
                .Spending(sh, PV4.TxOutRef(ConwayTxInfo.transTxId(txIn.id), txIn.index))
            )
          case DijkstraPlutusPurpose.DijkstraMinting(AsIxItem(_, pId)) =>
            Right(PV4.ScriptPurpose.Minting(sh, AlonzoTxInfo.transPolicyID(pId)))
          case DijkstraPlutusPurpose.DijkstraCertifying(AsIxItem(ix, cert)) =>
            l.toPlutusTxCert(pv, cert).map(PV4.ScriptPurpose.Certifying(sh, ix.value, _))
          case DijkstraPlutusPurpose.DijkstraWithdrawing(AsIxItem(_, account)) =>
            Right(PV4.ScriptPurpose.Withdrawing(sh, transAccountAddressToCredential(account)))
          case DijkstraPlutusPurpose.DijkstraVoting(AsIxItem(_, voter)) =>
            Right(PV4.ScriptPurpose.Voting(sh, ConwayTxInfo.transVoter(voter)))
          case DijkstraPlutusPurpose.DijkstraProposing(AsIxItem(ix, proc)) =>
            Right(
              PV4.ScriptPurpose.Proposing(
                sh,
                ix.value,
                transProposal[Language.PlutusV4, Dijkstra](proc)
              )
            )
          case DijkstraPlutusPurpose.DijkstraGuarding(AsIxItem(ix, _)) =>
            Right(PV4.ScriptPurpose.Guarding(sh, ix.value))

  /** `transVotingProcedures` of V4 (`TxInfo.hs:992-995`, cardano-ledger 9df9f98c): the voters and
    * each voter's actions in their order.
    */
  def transVotingProcedures[E <: EraTag](
      vps: VotingProcedures[E]
  ): AssocMap[PV3.Voter, AssocMap[PV4.GovernanceActionId, PV3.Vote]] =
    AssocMap(vps.unVotingProcedures.toVector.map { (voter, votes) =>
      ConwayTxInfo.transVoter(voter) -> AssocMap(votes.toVector.map { (id, procedure) =>
        transGovActionId(id) -> ConwayTxInfo.transVote(procedure)
      })
    })

  /** `transProposal` of V4 (`TxInfo.hs:997-1007`, cardano-ledger 9df9f98c): the anchor left out. */
  def transProposal[L <: Language, E <: EraTag](p: ProposalProcedure[E])(using
      ConwayEraPlutusTxInfo[L, E]
  ): PV4.ProposalProcedure =
    PV4.ProposalProcedure(
      transCoinToLovelace(p.pProcDeposit),
      transAccountAddressFlat(p.pProcReturnAddr),
      transGovAction[L, E](p.pProcGovAction)
    )

  /** `transGovActionId` of V4 (`TxInfo.hs:1009-1014`, cardano-ledger 9df9f98c). */
  def transGovActionId(id: GovActionId): PV4.GovernanceActionId =
    PV4.GovernanceActionId(ConwayTxInfo.transTxId(id.txId), id.index)

  /** `transGovAction` of V4 (`TxInfo.hs:1016-1051`, cardano-ledger 9df9f98c): the removed committee
    * members in their set's order and the added ones in their map's, the guardrails script and a
    * parent by their translations, the update by the language's.
    */
  def transGovAction[L <: Language, E <: EraTag](a: GovAction[E])(using
      t: ConwayEraPlutusTxInfo[L, E]
  ): PV4.GovernanceAction =
    def transGovPolicy(h: Option[ScriptHash]) = h.map(transScriptHash)
    def transPrevGovActionId[P <: GovActionPurpose](p: Option[GovPurposeId[P]]) =
      p.map(id => transGovActionId(id.unGovPurposeId))
    given Ordering[Credential[ColdCommittee]] =
      Credential.coldCommitteeCredentialOrder.toOrdering
    a match
      case GovAction.ParameterChange(pGovActionId, ppu, govPolicy) =>
        PV4.GovernanceAction.ParameterChange(
          transPrevGovActionId(pGovActionId),
          t.toPlutusChangedParameters(ppu),
          transGovPolicy(govPolicy)
        )
      case GovAction.HardForkInitiation(pGovActionId, protVer) =>
        PV4.GovernanceAction.HardForkInitiation(
          transPrevGovActionId(pGovActionId),
          transProtVer(protVer)
        )
      case GovAction.TreasuryWithdrawals(withdrawals, govPolicy) =>
        PV4.GovernanceAction.TreasuryWithdrawals(
          ConwayTxInfo.transMap(transAccountAddressFlat, transCoinToLovelace)(withdrawals),
          transGovPolicy(govPolicy)
        )
      case GovAction.NoConfidence(pGovActionId) =>
        PV4.GovernanceAction.NoConfidence(transPrevGovActionId(pGovActionId))
      case GovAction.UpdateCommittee(pGovActionId, ccToRemove, ccToAdd, threshold) =>
        PV4.GovernanceAction.UpdateCommittee(
          transPrevGovActionId(pGovActionId),
          ccToRemove.toVector.sorted.map(ConwayTxInfo.transColdCommitteeCred),
          ConwayTxInfo.transMap(ConwayTxInfo.transColdCommitteeCred, transEpochNo)(ccToAdd),
          transBoundedRationalV4(threshold)
        )
      case GovAction.NewConstitution(pGovActionId, constitution) =>
        PV4.GovernanceAction.NewConstitution(
          transPrevGovActionId(pGovActionId),
          PV4.Constitution(constitution.script.map(PV1.ScriptHash(_)))
        )
      case GovAction.InfoAction() => PV4.GovernanceAction.InfoAction

  /** `transProtVer` of V4 (`TxInfo.hs:1053-1055`, cardano-ledger 9df9f98c). */
  def transProtVer(pv: ProtVer): PV4.ProtocolVersion =
    PV4.ProtocolVersion(BigInt(pv.pvMajor.value), pv.pvMinor.value)

  /** `transBoundedRational` of V4 (`TxInfo.hs:1057-1058`, cardano-ledger 9df9f98c): the ratio
    * through `fromHaskellRatio` ([[PlutusTxInfos.transBoundedRational]]), in V4's newtype.
    */
  def transBoundedRationalV4(r: UnitInterval): PV4.Rational =
    PV4.Rational(PlutusTxInfos.transBoundedRational(r))

  /** `instance EraPlutusTxInfo 'PlutusV4 DijkstraEra` (`TxInfo.hs:574-629`) and `instance
    * ConwayEraPlutusTxInfo 'PlutusV4 DijkstraEra` (`TxInfo.hs:571-572`, cardano-ledger 9df9f98c):
    * the information of a transaction of either level, with no guard of the features a V1 to V3
    * context cannot show, a sub-transaction's at its index; the reference inputs that are inputs
    * refused at every version.
    */
  val plutusV4: ConwayEraPlutusTxInfo[Language.PlutusV4, Dijkstra] =
    new ConwayEraPlutusTxInfo[Language.PlutusV4, Dijkstra]:
      private given ConwayEraPlutusTxInfo[Language.PlutusV4, Dijkstra] = this

      override def plutusLanguage: PlutusLanguage[Language.PlutusV4] = PlutusLanguage.v4

      override def toPlutusChangedParameters(ppu: PParamsUpdate[Dijkstra]): PV3.ChangedParameters =
        PV3.ChangedParameters(ToPlutusData[PParamsUpdate[Dijkstra]].toPlutusData(ppu))

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: DijkstraTxCert
      ): Either[ContextError[Dijkstra], PV4.TxCert] =
        Right(transTxCertV4(txCert))

      override def toPlutusScriptPurpose[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          purpose: DijkstraPlutusPurpose[AsIxItem]
      ): Either[ContextError[Dijkstra], PV4.ScriptPurpose] =
        transPlutusPurposeV4(lti, purpose)

      override def toPlutusTxInfo[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra]
      ): PlutusTxInfoResult[Language.PlutusV4, Dijkstra] =
        given b.Level[T] = LedgerTxInfo.toSTxLevel(lti)(using b)
        val tx = lti.ltiTx
        val txBody = t.body[T](tx)
        PlutusTxInfoResult[Language.PlutusV4, Dijkstra](
          for
            timeRange <- transValidityInterval[Dijkstra](
              lti.ltiSlotToPOSIXTime,
              b.vldt[T](txBody)
            )
            inputsInfo <- b
              .inputs[T](txBody)
              .toVector
              .traverse(transTxInInfoV4[Dijkstra](lti.ltiUTxO, _))
            refInputsInfo <- b
              .referenceInputs[T](txBody)
              .toVector
              .traverse(transTxInInfoV4[Dijkstra](lti.ltiUTxO, _))
            _ <- ConwayTxInfo.checkReferenceInputsNotDisjointFromInputs[Dijkstra, T](txBody)
            outputs <- TxIx
              .enumerate(b.outputs[T](txBody))
              .traverse((out, ix) =>
                transTxOutV4[Dijkstra](TxOutSource.TxOutFromOutput(ix.index), out)
              )
            txCerts <- AlonzoTxInfo
              .transTxBodyCerts[Language.PlutusV4, Dijkstra, T](lti.ltiProtVer, txBody)
            plutusRedeemers <- BabbageTxInfo
              .transTxRedeemers[Language.PlutusV4, Dijkstra, T](lti)
          yield PV4.TxInfo(
            txInfoId = ConwayTxInfo.transTxBodyId[Dijkstra, T](txBody),
            txInfoSubTxIx = lti.ltiLevelTxInfo match
              case LedgerLevelTxInfo.LedgerTopTxInfo(_)    => None
              case LedgerLevelTxInfo.LedgerSubTxInfo(txIx) => Some(BigInt(txIx.index))
            ,
            txInfoInputs = inputsInfo,
            txInfoReferenceInputs = refInputsInfo,
            txInfoOutputs = outputs,
            txInfoMint = ConwayTxInfo.transMintValue(b.mint[T](txBody)),
            txInfoTxCerts = txCerts,
            txInfoWithdrawals = transWithdrawals(b.withdrawals[T](txBody)),
            txInfoDirectDeposits = transDirectDeposits(b.directDeposits[T](txBody)),
            txInfoAccountBalanceIntervals =
              transAccountBalanceIntervals(b.accountBalanceIntervals[T](txBody)),
            txInfoValidRange = timeRange,
            txInfoGuards = transTxBodyGuards[T](txBody),
            txInfoRequiredTopLevelGuards = transTxBodyRequiredTopLevelGuards[T](txBody),
            txInfoRedeemers = plutusRedeemers,
            txInfoData = AssocMap(AlonzoTxInfo.transTxWitsDatums(t.wits[T](tx))(using t.witsOf)),
            txInfoVotes = transVotingProcedures(b.votingProcedures[T](txBody)),
            txInfoProposalProcedures = b
              .proposalProcedures[T](txBody)
              .toVector
              .map(transProposal[Language.PlutusV4, Dijkstra]),
            txInfoCurrentTreasuryAmount =
              b.currentTreasuryValue[T](txBody).map(transCoinToLovelace),
            txInfoTreasuryDonation = transCoinToLovelace(b.treasuryDonation[T](txBody))
          )
        )

      override def toPlutusArgs[T <: TxLevel](
          lti: LedgerTxInfo[T, Dijkstra],
          txInfo: PV4.TxInfo,
          purpose: DijkstraPlutusPurpose[AsIxItem],
          redeemerData: Data
      ): Either[ContextError[Dijkstra], PlutusV4Args] =
        toPlutusV4Args(lti, txInfo, purpose, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Dijkstra],
          txIn: TxIn
      ): Either[ContextError[Dijkstra], PV4.TxInInfo] =
        transTxInInfoV4(utxo, txIn)

      override def lookupTxInfoResult(
          result: DijkstraTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV4, Dijkstra] = result.tirPlutusV4

  /** `instance EraPlutusContext DijkstraEra` (`TxInfo.hs:299-331`, cardano-ledger 9df9f98c): Plutus
    * V1 to V4, each language's information computed when first looked up.
    */
  val context: EraPlutusContext[Dijkstra] = new EraPlutusContext[Dijkstra]:
    override def mkSupportedLanguage(lang: Lang): Option[SupportedLanguage[Dijkstra]] =
      lang match
        case Lang.V1 => Some(SupportedLanguage(plutusV1))
        case Lang.V2 => Some(SupportedLanguage(plutusV2))
        case Lang.V3 => Some(SupportedLanguage(plutusV3))
        case Lang.V4 => Some(SupportedLanguage(plutusV4))

    override def mkSupportedPlutusRunnable(
        v: Version,
        plutusScript: DijkstraPlutusScript
    ): SupportedPlutusRunnable[Dijkstra] = plutusScript match
      case DijkstraPlutusScript.V1(p) =>
        SupportedPlutusRunnable(plutusV1, PlutusRunnable.decodePlutusRunnable(v, p))
      case DijkstraPlutusScript.V2(p) =>
        SupportedPlutusRunnable(plutusV2, PlutusRunnable.decodePlutusRunnable(v, p))
      case DijkstraPlutusScript.V3(p) =>
        SupportedPlutusRunnable(plutusV3, PlutusRunnable.decodePlutusRunnable(v, p))
      case DijkstraPlutusScript.V4(p) =>
        SupportedPlutusRunnable(plutusV4, PlutusRunnable.decodePlutusRunnable(v, p))

    override def mkTxInfoResult[T <: TxLevel](
        lti: LedgerTxInfo[T, Dijkstra]
    ): DijkstraTxInfoResult =
      DijkstraTxInfoResult(
        plutusV1.toPlutusTxInfo(lti),
        plutusV2.toPlutusTxInfo(lti),
        plutusV3.toPlutusTxInfo(lti),
        plutusV4.toPlutusTxInfo(lti)
      )
