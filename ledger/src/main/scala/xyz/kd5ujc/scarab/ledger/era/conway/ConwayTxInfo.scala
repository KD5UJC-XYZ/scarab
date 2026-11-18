// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.data.NonEmptyVector
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{
  DataSite,
  GovActionId,
  RewardAccount,
  TranslationError,
  TxIn,
  TxOutSource,
  Vote,
  Voter,
  VotingProcedure
}
import xyz.kd5ujc.scarab.ledger.era.{
  TxOut,
  SupportedLanguage,
  PlutusV2Args,
  PlutusV1Args,
  PV2,
  EraPlutusContext,
  ConwayEraTxBody,
  AlonzoContextError,
  AlonzoTxInfo,
  AsIxItem,
  AsItem,
  AssocMap,
  BabbageContextError,
  BabbageEraTxBody,
  BabbageEraTxOut,
  BabbageTxInfo,
  ConwayEraPParams,
  ContextError,
  Data,
  Delegatee,
  EraPlutusTxInfo,
  EraTx,
  EraTxBody,
  GovAction,
  Inject,
  Language,
  LedgerTxInfo,
  MultiAssetValue,
  OSet,
  PParamsUpdate,
  PV1,
  PV3,
  PlutusLanguage,
  PlutusTxInfoResult,
  PlutusTxInfos,
  PlutusV3Args,
  PoolCert,
  ProposalProcedure,
  ProtVer,
  ToPlutusData,
  TxBody,
  TxCert,
  TxLevel,
  UTxO,
  ValidityInterval,
  Value,
  VotingProcedures,
  ConwayDelegCert,
  ConwayGovCert,
  PlutusPurpose,
  PlutusRunnable,
  SupportedPlutusRunnable,
  Version
}
import xyz.kd5ujc.scarab.ledger.era.rules.ConwayEra
import xyz.kd5ujc.scarab.ledger.state.{
  ColdCommittee,
  Coin,
  Conway,
  Credential,
  DRep,
  DRepRole,
  EraTag,
  HotCommittee
}

/** Why Conway's translation of a transaction to a script context fails (`data ConwayContextError
  * era`, `eras/conway/impl/src/Cardano/Ledger/Conway/TxInfo.hs:180-189`, cardano-ledger f649f975).
  */
enum ConwayContextError[E <: EraTag]:
  case BabbageContextError(error: xyz.kd5ujc.scarab.ledger.era.BabbageContextError[E])
  case CertificateNotSupported(txCert: TxCert[E])
  case PlutusPurposeNotSupported(purpose: PlutusPurpose[AsItem, E])
  case CurrentTreasuryFieldNotSupported(coin: Coin)
  case VotingProceduresFieldNotSupported(votingProcedures: VotingProcedures[E])
  case ProposalProceduresFieldNotSupported(proposalProcedures: OSet[ProposalProcedure[E]])
  case TreasuryDonationFieldNotSupported(coin: Coin)
  case ReferenceInputsNotDisjointFromInputs(common: NonEmptyVector[TxIn])

/** The parameter changes of a proposal in a language's context (`class ConwayEraPlutusTxInfo (l ::
  * Language) era`, `Conway/TxInfo.hs:780-784`, cardano-ledger f649f975).
  */
trait ConwayEraPlutusTxInfo[L <: Language, E <: EraTag] extends EraPlutusTxInfo[L, E]:
  def toPlutusChangedParameters(ppu: PParamsUpdate[E]): PV3.ChangedParameters

/** Conway's translations (`eras/conway/impl/src/Cardano/Ledger/Conway/TxInfo.hs`, cardano-ledger
  * f649f975) and its instance for Plutus V3. A function the reference writes for any era whose
  * certificates, purposes or proposals are Conway's (`TxCert era ~ ConwayTxCert era`,
  * `ConwayEraPlutusTxInfo 'PlutusV3 era`) is written here for Conway's; the reference shares it
  * with Dijkstra, whose types come later (#85).
  */
object ConwayTxInfo:
  import PlutusTxInfos.*

  /** `transTxInInfoV3` (`Conway/TxInfo.hs:338-350`, cardano-ledger f649f975): an input with the
    * output the UTxO set holds for it, in the V3 form.
    */
  def transTxInInfoV3[E <: EraTag](utxo: UTxO[E], txIn: TxIn)(using
      o: BabbageEraTxOut[E],
      inj: Inject[xyz.kd5ujc.scarab.ledger.era.BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV3.TxInInfo] =
    for
      txOut <- AlonzoTxInfo
        .transLookupTxOut[E, AlonzoContextError[E]](utxo, txIn)
        .leftMap(e => inj.inject(BabbageContextError.AlonzoContextError(e)))
      plutusTxOut <- BabbageTxInfo.transTxOutV2(TxOutSource.TxOutFromInput(txIn), txOut)
    yield PV3.TxInInfo(transTxIn(txIn), plutusTxOut)

  /** `transTxId` of V3 (`Conway/TxInfo.hs:536-537`, cardano-ledger f649f975). */
  def transTxId(txId: Bytes): PV3.TxId = PV3.TxId(transSafeHash(txId))

  /** `transTxBodyId` of V3 (`Conway/TxInfo.hs:539-540`, cardano-ledger f649f975): the hash of the
    * body.
    */
  def transTxBodyId[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: EraTxBody[E],
      l: b.Level[T]
  ): PV3.TxId =
    PV3.TxId(transSafeHash(b.id[T](txBody)))

  /** `transTxIn` of V3 (`Conway/TxInfo.hs:542-543`, cardano-ledger f649f975). */
  def transTxIn(txIn: TxIn): PV3.TxOutRef = PV3.TxOutRef(transTxId(txIn.id), txIn.index)

  /** `transMintValue` of V3 (`Conway/TxInfo.hs:545-546`, cardano-ledger f649f975): the assets
    * alone, no lovelace.
    */
  def transMintValue(m: Map[Bytes, Map[Bytes, BigInt]]): PV3.MintValue =
    PV3.MintValue(AlonzoTxInfo.transMultiAsset(m).getValue)

  /** `transMap` (`Conway/TxInfo.hs:697-699`, cardano-ledger f649f975): the map's entries in its
    * keys' order, each translated, a repeat of a translated key kept.
    */
  def transMap[K, V, A, C](transKey: K => A, transValue: V => C)(m: Map[K, V])(using
      Ordering[K]
  ): AssocMap[A, C] =
    AssocMap(m.toVector.sortBy(_._1).map((k, v) => (transKey(k), transValue(v))))

  /** `transTxBodyWithdrawals` (`Conway/TxInfo.hs:549-551`, cardano-ledger f649f975): by the
    * account's credential, in the accounts' order.
    */
  def transTxBodyWithdrawals[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: EraTxBody[E],
      l: b.Level[T]
  ): AssocMap[PV1.Credential, PV1.Lovelace] =
    transMap(transAccountAddress, transCoinToLovelace)(b.withdrawals[T](txBody))

  /** `transTxCert` (`Conway/TxInfo.hs:559-597`, cardano-ledger f649f975): a certificate in the V3
    * form. At protocol version 9 a registration's deposit and an unregistration's refund are left
    * out (`hardforkConwayBootstrapPhase`), as the chain has them so.
    */
  def transTxCert(pv: ProtVer, txCert: ConwayTxCert): PV3.TxCert =
    def depositUnlessBootstrap(c: Coin) =
      Option.unless(ConwayEra.hardforkConwayBootstrapPhase(pv))(transCoinToLovelace(c))
    txCert match
      case ConwayTxCert.ConwayTxCertPool(PoolCert.RegPool(params)) =>
        PV3.TxCert.TxCertPoolRegister(
          transKeyHash(params.sppId),
          PV1.PubKeyHash(params.value.vrfKeyHash)
        )
      case ConwayTxCert.ConwayTxCertPool(PoolCert.RetirePool(poolId, retireEpochNo)) =>
        PV3.TxCert.TxCertPoolRetire(transKeyHash(poolId), transEpochNo(retireEpochNo))
      case ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayRegCert(stakeCred, None)) =>
        PV3.TxCert.TxCertRegStaking(transCred(stakeCred), None)
      case ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(stakeCred, None)) =>
        PV3.TxCert.TxCertUnRegStaking(transCred(stakeCred), None)
      case ConwayTxCert.ConwayTxCertDeleg(
            ConwayDelegCert.ConwayRegCert(stakeCred, Some(deposit))
          ) =>
        PV3.TxCert.TxCertRegStaking(transCred(stakeCred), depositUnlessBootstrap(deposit))
      case ConwayTxCert.ConwayTxCertDeleg(
            ConwayDelegCert.ConwayUnRegCert(stakeCred, Some(refund))
          ) =>
        PV3.TxCert.TxCertUnRegStaking(transCred(stakeCred), depositUnlessBootstrap(refund))
      case ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(stakeCred, delegatee)) =>
        PV3.TxCert.TxCertDelegStaking(transCred(stakeCred), transDelegatee(delegatee))
      case ConwayTxCert.ConwayTxCertDeleg(
            ConwayDelegCert.ConwayRegDelegCert(stakeCred, delegatee, deposit)
          ) =>
        PV3.TxCert.TxCertRegDeleg(
          transCred(stakeCred),
          transDelegatee(delegatee),
          transCoinToLovelace(deposit)
        )
      case ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayAuthCommitteeHotKey(cold, hot)) =>
        PV3.TxCert.TxCertAuthHotCommittee(transColdCommitteeCred(cold), transHotCommitteeCred(hot))
      case ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayResignCommitteeColdKey(cold, _)) =>
        PV3.TxCert.TxCertResignColdCommittee(transColdCommitteeCred(cold))
      case ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayRegDRep(drepCred, deposit, _)) =>
        PV3.TxCert.TxCertRegDRep(transDRepCred(drepCred), transCoinToLovelace(deposit))
      case ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayUnRegDRep(drepCred, refund)) =>
        PV3.TxCert.TxCertUnRegDRep(transDRepCred(drepCred), transCoinToLovelace(refund))
      case ConwayTxCert.ConwayTxCertGov(ConwayGovCert.ConwayUpdateDRep(drepCred, _)) =>
        PV3.TxCert.TxCertUpdateDRep(transDRepCred(drepCred))

  /** `transDRepCred` (`Conway/TxInfo.hs:599-600`, cardano-ledger f649f975). */
  def transDRepCred(c: Credential[DRepRole]): PV3.DRepCredential = PV3.DRepCredential(transCred(c))

  /** `transColdCommitteeCred` (`Conway/TxInfo.hs:602-603`, cardano-ledger f649f975). */
  def transColdCommitteeCred(c: Credential[ColdCommittee]): PV3.ColdCommitteeCredential =
    PV3.ColdCommitteeCredential(transCred(c))

  /** `transHotCommitteeCred` (`Conway/TxInfo.hs:605-606`, cardano-ledger f649f975). */
  def transHotCommitteeCred(c: Credential[HotCommittee]): PV3.HotCommitteeCredential =
    PV3.HotCommitteeCredential(transCred(c))

  /** `transDelegatee` (`Conway/TxInfo.hs:608-612`, cardano-ledger f649f975). */
  def transDelegatee(d: Delegatee): PV3.Delegatee = d match
    case Delegatee.DelegStake(poolId) => PV3.Delegatee.DelegStake(transKeyHash(poolId))
    case Delegatee.DelegVote(drep)    => PV3.Delegatee.DelegVote(transDRep(drep))
    case Delegatee.DelegStakeVote(poolId, drep) =>
      PV3.Delegatee.DelegStakeVote(transKeyHash(poolId), transDRep(drep))

  /** `transDRep` (`Conway/TxInfo.hs:614-618`, cardano-ledger f649f975): a key's or a script's DRep
    * by its credential (`DRepCredential`).
    */
  def transDRep(d: DRep): PV3.DRep = d match
    case DRep.DRepKeyHash(h) =>
      PV3.DRep.DRep(transDRepCred(Credential.KeyHashObj[DRepRole](h)))
    case DRep.DRepScriptHash(h) =>
      PV3.DRep.DRep(transDRepCred(Credential.ScriptHashObj[DRepRole](h)))
    case DRep.DRepAlwaysAbstain      => PV3.DRep.DRepAlwaysAbstain
    case DRep.DRepAlwaysNoConfidence => PV3.DRep.DRepAlwaysNoConfidence

  /** `transPlutusPurposeV3` (`Conway/TxInfo.hs:627-645`, cardano-ledger f649f975): a purpose in the
    * V3 form, a certificate's and a proposal's with their index. Every Conway purpose has a V3
    * form, so the reference's `PlutusPurposeNotSupported` is of a later era's purposes.
    */
  def transPlutusPurposeV3(
      pv: ProtVer,
      purpose: ConwayPlutusPurpose[AsIxItem]
  )(using t: ConwayEraPlutusTxInfo[Language.PlutusV3, Conway]): PV3.ScriptPurpose =
    purpose match
      case ConwayPlutusPurpose.ConwaySpending(AsIxItem(_, txIn)) =>
        PV3.ScriptPurpose.Spending(transTxIn(txIn))
      case ConwayPlutusPurpose.ConwayMinting(AsIxItem(_, policyId)) =>
        PV3.ScriptPurpose.Minting(AlonzoTxInfo.transPolicyID(policyId))
      case ConwayPlutusPurpose.ConwayCertifying(AsIxItem(ix, txCert)) =>
        PV3.ScriptPurpose.Certifying(ix.value, transTxCert(pv, txCert))
      case ConwayPlutusPurpose.ConwayWithdrawing(AsIxItem(_, accountAddress)) =>
        PV3.ScriptPurpose.Rewarding(transAccountAddress(accountAddress))
      case ConwayPlutusPurpose.ConwayVoting(AsIxItem(_, voter)) =>
        PV3.ScriptPurpose.Voting(transVoter(voter))
      case ConwayPlutusPurpose.ConwayProposing(AsIxItem(ix, proposal)) =>
        PV3.ScriptPurpose.Proposing(ix.value, transProposal(proposal))

  /** `transVoter` (`Conway/TxInfo.hs:647-651`, cardano-ledger f649f975). */
  def transVoter(v: Voter): PV3.Voter = v match
    case Voter.CommitteeHot(cred) =>
      PV3.Voter.CommitteeVoter(PV3.HotCommitteeCredential(transCredFlat(cred)))
    case Voter.DRepVoter(cred) => PV3.Voter.DRepVoter(PV3.DRepCredential(transCredFlat(cred)))
    case Voter.Pool(keyHash)   => PV3.Voter.StakePoolVoter(transKeyHashBytes(keyHash))

  /** `transGovActionId` (`Conway/TxInfo.hs:653-658`, cardano-ledger f649f975). */
  def transGovActionId(id: GovActionId): PV3.GovernanceActionId =
    PV3.GovernanceActionId(transTxId(id.txId), id.index)

  /** `transGovAction` (`Conway/TxInfo.hs:660-695`, cardano-ledger f649f975): the removed committee
    * members in their set's order and the added ones in their map's, the guardrails script and a
    * parent by their translations.
    */
  def transGovAction(a: GovAction[Conway])(using
      t: ConwayEraPlutusTxInfo[Language.PlutusV3, Conway]
  ): PV3.GovernanceAction =
    def transGovPolicy(h: Option[xyz.kd5ujc.scarab.ledger.state.ScriptHash]) =
      h.map(transScriptHash)
    def transPrevGovActionId[P <: xyz.kd5ujc.scarab.ledger.era.GovActionPurpose](
        p: Option[xyz.kd5ujc.scarab.ledger.era.GovPurposeId[P]]
    ) = p.map(id => transGovActionId(id.unGovPurposeId))
    given Ordering[Credential[ColdCommittee]] =
      Credential.coldCommitteeCredentialOrder.toOrdering
    a match
      case GovAction.ParameterChange(pGovActionId, ppu, govPolicy) =>
        PV3.GovernanceAction.ParameterChange(
          transPrevGovActionId(pGovActionId),
          t.toPlutusChangedParameters(ppu),
          transGovPolicy(govPolicy)
        )
      case GovAction.HardForkInitiation(pGovActionId, protVer) =>
        PV3.GovernanceAction.HardForkInitiation(
          transPrevGovActionId(pGovActionId),
          transProtVer(protVer)
        )
      case GovAction.TreasuryWithdrawals(withdrawals, govPolicy) =>
        PV3.GovernanceAction.TreasuryWithdrawals(
          transMap(transAccountAddress, transCoinToLovelace)(withdrawals),
          transGovPolicy(govPolicy)
        )
      case GovAction.NoConfidence(pGovActionId) =>
        PV3.GovernanceAction.NoConfidence(transPrevGovActionId(pGovActionId))
      case GovAction.UpdateCommittee(pGovActionId, ccToRemove, ccToAdd, threshold) =>
        PV3.GovernanceAction.UpdateCommittee(
          transPrevGovActionId(pGovActionId),
          ccToRemove.toVector.sorted.map(transColdCommitteeCred),
          transMap(transColdCommitteeCred, transEpochNo)(ccToAdd),
          transBoundedRational(threshold)
        )
      case GovAction.NewConstitution(pGovActionId, constitution) =>
        PV3.GovernanceAction.NewConstitution(
          transPrevGovActionId(pGovActionId),
          PV3.Constitution(constitution.script.map(PV1.ScriptHash(_)))
        )
      case GovAction.InfoAction() => PV3.GovernanceAction.InfoAction

  /** `transVotingProcedures` (`Conway/TxInfo.hs:701-704`, cardano-ledger f649f975): the voters and
    * each voter's actions in their order.
    */
  def transVotingProcedures(
      vps: VotingProcedures[Conway]
  ): AssocMap[PV3.Voter, AssocMap[PV3.GovernanceActionId, PV3.Vote]] =
    AssocMap(vps.unVotingProcedures.toVector.map { (voter, votes) =>
      transVoter(voter) -> AssocMap(votes.toVector.map { (id, procedure) =>
        transGovActionId(id) -> transVote(procedure)
      })
    })

  /** `transVote` of a procedure's vote (`Conway/TxInfo.hs:706-710`, cardano-ledger f649f975). */
  def transVote(p: VotingProcedure): PV3.Vote = p.vote match
    case Vote.No      => PV3.Vote.VoteNo
    case Vote.Yes     => PV3.Vote.VoteYes
    case Vote.Abstain => PV3.Vote.Abstain

  /** `transProposal` (`Conway/TxInfo.hs:712-722`, cardano-ledger f649f975): the anchor left out. */
  def transProposal(p: ProposalProcedure[Conway])(using
      ConwayEraPlutusTxInfo[Language.PlutusV3, Conway]
  ): PV3.ProposalProcedure =
    PV3.ProposalProcedure(
      transCoinToLovelace(p.pProcDeposit),
      transAccountAddress(p.pProcReturnAddr),
      transGovAction(p.pProcGovAction)
    )

  /** `transProtVer` (`Conway/TxInfo.hs:741-743`, cardano-ledger f649f975). */
  def transProtVer(pv: ProtVer): PV3.ProtocolVersion =
    PV3.ProtocolVersion(BigInt(pv.pvMajor.value), pv.pvMinor.value)

  /** `scriptPurposeToScriptInfo` (`Conway/TxInfo.hs:768-776`, cardano-ledger f649f975): the
    * purpose, with the datum of a spending script.
    */
  def scriptPurposeToScriptInfo(
      sp: PV3.ScriptPurpose,
      maybeSpendingData: Option[PV1.Datum]
  ): PV3.ScriptInfo = sp match
    case PV3.ScriptPurpose.Spending(txIn) => PV3.ScriptInfo.SpendingScript(txIn, maybeSpendingData)
    case PV3.ScriptPurpose.Minting(policyId)       => PV3.ScriptInfo.MintingScript(policyId)
    case PV3.ScriptPurpose.Certifying(ix, cert)    => PV3.ScriptInfo.CertifyingScript(ix, cert)
    case PV3.ScriptPurpose.Rewarding(account)      => PV3.ScriptInfo.RewardingScript(account)
    case PV3.ScriptPurpose.Voting(voter)           => PV3.ScriptInfo.VotingScript(voter)
    case PV3.ScriptPurpose.Proposing(ix, proposal) => PV3.ScriptInfo.ProposingScript(ix, proposal)

  /** `toPlutusV3Args` (`Conway/TxInfo.hs:745-766`, cardano-ledger f649f975): the script context of
    * the information, the redeemer and the purpose with the spending datum.
    */
  def toPlutusV3Args(
      pv: ProtVer,
      txInfo: PV3.TxInfo,
      plutusPurpose: ConwayPlutusPurpose[AsIxItem],
      maybeSpendingData: Option[Data],
      redeemerData: Data
  )(using
      t: ConwayEraPlutusTxInfo[Language.PlutusV3, Conway]
  ): Either[ContextError[Conway], PlutusV3Args] =
    t.toPlutusScriptPurpose(pv, plutusPurpose).map { scriptPurpose =>
      PlutusV3Args(
        PV3.ScriptContext(
          txInfo,
          BabbageTxInfo.transRedeemer(redeemerData),
          scriptPurposeToScriptInfo(
            scriptPurpose,
            maybeSpendingData.map(d => transDatum(d.getPlutusData))
          )
        )
      )
    }

  /** `transValidityInterval` of Conway (`Conway/TxInfo.hs:790-814`, cardano-ledger f649f975): the
    * interval in POSIX time, an interval with an end alone open at its end, unlike Alonzo's; a slot
    * the clock cannot translate refused (`TimeTranslationPastHorizon`).
    */
  def transValidityInterval[E <: EraTag, A](
      slotToPOSIXTime: BigInt => Either[TranslationError, BigInt],
      vi: ValidityInterval
  )(using inj: Inject[AlonzoContextError[E], A]): Either[A, PV1.POSIXTimeRange] =
    def transSlotToPOSIXTime(slot: BigInt): Either[A, PV1.POSIXTime] =
      slotToPOSIXTime(slot)
        .bimap(e => inj.inject(AlonzoContextError.TimeTranslationPastHorizon(e)), PV1.POSIXTime(_))
    vi match
      case ValidityInterval(None, None)    => Right(PV1.Interval.always)
      case ValidityInterval(Some(i), None) => transSlotToPOSIXTime(i).map(PV1.Interval.from)
      case ValidityInterval(None, Some(i)) =>
        transSlotToPOSIXTime(i).map(t =>
          PV1.Interval(PV1.LowerBound(PV1.Extended.NegInf, true), PV1.strictUpperBound(t))
        )
      case ValidityInterval(Some(i), Some(j)) =>
        for
          t1 <- transSlotToPOSIXTime(i)
          t2 <- transSlotToPOSIXTime(j)
        yield PV1.Interval(PV1.lowerBound(t1), PV1.strictUpperBound(t2))

  /** `checkReferenceInputsNotDisjointFromInputs` (`Conway/TxInfo.hs:816-827`, cardano-ledger
    * f649f975): the inputs that are also reference inputs, in their order, refused.
    */
  def checkReferenceInputsNotDisjointFromInputs[E <: EraTag, T <: TxLevel](
      txBody: TxBody[T, E]
  )(using
      b: BabbageEraTxBody[E],
      l: b.Level[T],
      inj: Inject[ConwayContextError[E], ContextError[E]]
  ): Either[ContextError[E], Unit] =
    NonEmptyVector.fromVector(
      b.inputs[T](txBody).intersect(b.referenceInputs[T](txBody)).toVector
    ) match
      case Some(common) =>
        Left(inj.inject(ConwayContextError.ReferenceInputsNotDisjointFromInputs(common)))
      case None => Right(())

  /** `instance EraPlutusTxInfo 'PlutusV3 ConwayEra` (`Conway/TxInfo.hs:482-534`) and `instance
    * ConwayEraPlutusTxInfo 'PlutusV3 ConwayEra` (`Conway/TxInfo.hs:786-787`, cardano-ledger
    * f649f975).
    */
  val plutusV3: ConwayEraPlutusTxInfo[Language.PlutusV3, Conway] =
    new ConwayEraPlutusTxInfo[Language.PlutusV3, Conway]:
      private given ConwayEraPlutusTxInfo[Language.PlutusV3, Conway] = this
      private val t = EraTx.conway
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given BabbageEraTxOut[Conway] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV3] = PlutusLanguage.v3

      override def toPlutusChangedParameters(ppu: PParamsUpdate[Conway]): PV3.ChangedParameters =
        PV3.ChangedParameters(ToPlutusData[PParamsUpdate[Conway]].toPlutusData(ppu))

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: ConwayTxCert
      ): Either[ContextError[Conway], PV3.TxCert] =
        Right(transTxCert(pv, txCert))

      override def toPlutusScriptPurpose(
          pv: ProtVer,
          purpose: ConwayPlutusPurpose[AsIxItem]
      ): Either[ContextError[Conway], PV3.ScriptPurpose] =
        Right(transPlutusPurposeV3(pv, purpose))

      override def toPlutusTxInfo(
          lti: LedgerTxInfo[Conway]
      ): PlutusTxInfoResult[Language.PlutusV3, Conway] =
        val tx = lti.ltiTx
        val txBody = t.body[TxLevel.Top](tx)
        val pv = lti.ltiProtVer
        val result = for
          timeRange <- transValidityInterval[Conway, ContextError[Conway]](
            lti.ltiSlotToPOSIXTime,
            b.vldt[TxLevel.Top](txBody)
          )
          inputsInfo <- b
            .inputs[TxLevel.Top](txBody)
            .toVector
            .traverse(transTxInInfoV3(lti.ltiUTxO, _))
          refInputsInfo <- b
            .referenceInputs[TxLevel.Top](txBody)
            .toVector
            .traverse(transTxInInfoV3(lti.ltiUTxO, _))
          _ <-
            if pv.pvMajor.value >= 11 then
              checkReferenceInputsNotDisjointFromInputs[Conway, TxLevel.Top](txBody)
            else Right(())
          outputs <- b
            .outputs[TxLevel.Top](txBody)
            .zipWithIndex
            .traverse((out, ix) =>
              BabbageTxInfo.transTxOutV2[Conway](TxOutSource.TxOutFromOutput(ix), out)
            )
          txCerts <- AlonzoTxInfo.transTxBodyCerts[Language.PlutusV3, Conway, TxLevel.Top](
            pv,
            txBody
          )
          plutusRedeemers <- BabbageTxInfo.transTxRedeemers[Language.PlutusV3, Conway, TxLevel.Top](
            pv,
            tx
          )
          datums = AlonzoTxInfo
            .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
        yield PV3.TxInfo(
          txInfoInputs = inputsInfo,
          txInfoReferenceInputs = refInputsInfo,
          txInfoOutputs = outputs,
          txInfoFee = transCoinToLovelace(b.fee(txBody)),
          txInfoMint = transMintValue(b.mint[TxLevel.Top](txBody)),
          txInfoTxCerts = txCerts,
          txInfoWdrl = transTxBodyWithdrawals[Conway, TxLevel.Top](txBody),
          txInfoValidRange = timeRange,
          txInfoSignatories = AlonzoTxInfo.transTxBodyReqSignerHashes[Conway, TxLevel.Top](txBody),
          txInfoRedeemers = plutusRedeemers,
          txInfoData = AssocMap(datums),
          txInfoId = transTxBodyId[Conway, TxLevel.Top](txBody),
          txInfoVotes = transVotingProcedures(b.votingProcedures[TxLevel.Top](txBody)),
          txInfoProposalProcedures =
            b.proposalProcedures[TxLevel.Top](txBody).toVector.map(transProposal),
          txInfoCurrentTreasuryAmount =
            b.currentTreasuryValue[TxLevel.Top](txBody).map(transCoinToLovelace),
          txInfoTreasuryDonation =
            Option.unless(b.treasuryDonation[TxLevel.Top](txBody).value == 0)(
              transCoinToLovelace(b.treasuryDonation[TxLevel.Top](txBody))
            )
        )
        PlutusTxInfoResult(result.map(txInfo => _ => Right(txInfo)))

      override def toPlutusArgs(
          pv: ProtVer,
          txInfo: PV3.TxInfo,
          purpose: ConwayPlutusPurpose[AsIxItem],
          maybeSpendingData: Option[Data],
          redeemerData: Data
      ): Either[ContextError[Conway], PlutusV3Args] =
        toPlutusV3Args(pv, txInfo, purpose, maybeSpendingData, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Conway],
          txIn: TxIn
      ): Either[ContextError[Conway], PV3.TxInInfo] =
        transTxInInfoV3(utxo, txIn)

      override def lookupTxInfoResult(
          result: ConwayTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV3, Conway] = result.tirPlutusV3

  /** `transTxOutV1` of Conway (`Conway/TxInfo.hs:304-320`, cardano-ledger f649f975): the output in
    * the V1 form, an inline datum refused, then a Byron address; a reference script, which the V1
    * output has no field for, is left out, unlike Babbage's. The inline datum is read as data to
    * ask whether there is one (`isSJust (txOut ^. dataTxOutL)`, whose strict `SJust` forces
    * `binaryDataToData`): an inline datum taken unread ([[BabbageTxOutRep.fromStored]]) that does
    * not read as data is the node's own `Undecodable`, where the reference raises `error`.
    */
  def transTxOutV1[E <: EraTag](txOutSource: TxOutSource, txOut: TxOut[E])(using
      o: BabbageEraTxOut[E],
      inj: Inject[xyz.kd5ujc.scarab.ledger.era.BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV1.TxOut] =
    o.data(txOut) match
      case Left(e) => Left(BabbageTxInfo.undecodable[E](DataSite.InlineDatum, e))
      case Right(Some(_)) =>
        Left(inj.inject(BabbageContextError.InlineDatumsNotSupported(txOutSource)))
      case Right(None) =>
        AlonzoTxInfo
          .transTxOut[E](txOut)
          .toRight(inj.inject(BabbageContextError.ByronTxOutInContext(txOutSource)))

  /** `transTxInInfoV1` of Conway (`Conway/TxInfo.hs:322-335`, cardano-ledger f649f975): an input
    * with the output the UTxO set holds for it, in the V1 form.
    */
  def transTxInInfoV1[E <: EraTag](utxo: UTxO[E], txIn: TxIn)(using
      o: BabbageEraTxOut[E],
      inj: Inject[xyz.kd5ujc.scarab.ledger.era.BabbageContextError[E], ContextError[E]],
      v: Value[E] =:= MultiAssetValue
  ): Either[ContextError[E], PV1.TxInInfo] =
    for
      txOut <- AlonzoTxInfo
        .transLookupTxOut[E, AlonzoContextError[E]](utxo, txIn)
        .leftMap(e => inj.inject(BabbageContextError.AlonzoContextError(e)))
      plutusTxOut <- transTxOutV1(TxOutSource.TxOutFromInput(txIn), txOut)
    yield PV1.TxInInfo(PlutusTxInfos.transTxIn(txIn), plutusTxOut)

  /** `guardConwayFeaturesForPlutusV1V2` (`Conway/TxInfo.hs:352-381`, cardano-ledger f649f975), of
    * the transaction's body: votes, then proposals, then a treasury donation, then the current
    * treasury, none of which a V1 or V2 context can show, refused.
    */
  def guardConwayFeaturesForPlutusV1V2[E <: EraTag, T <: TxLevel](txBody: TxBody[T, E])(using
      b: ConwayEraTxBody[E],
      l: b.Level[T],
      inj: Inject[ConwayContextError[E], ContextError[E]]
  ): Either[ContextError[E], Unit] =
    val votingProcedures = b.votingProcedures[T](txBody)
    val proposalProcedures = b.proposalProcedures[T](txBody)
    val treasuryDonation = b.treasuryDonation[T](txBody)
    if votingProcedures.unVotingProcedures.nonEmpty then
      Left(inj.inject(ConwayContextError.VotingProceduresFieldNotSupported(votingProcedures)))
    else if proposalProcedures.size != 0 then
      Left(inj.inject(ConwayContextError.ProposalProceduresFieldNotSupported(proposalProcedures)))
    else if treasuryDonation.value != 0 then
      Left(inj.inject(ConwayContextError.TreasuryDonationFieldNotSupported(treasuryDonation)))
    else
      b.currentTreasuryValue[T](txBody) match
        case None => Right(())
        case Some(treasury) =>
          Left(inj.inject(ConwayContextError.CurrentTreasuryFieldNotSupported(treasury)))

  /** `transTxCertV1V2` (`Conway/TxInfo.hs:383-397`, cardano-ledger f649f975): a registration or an
    * unregistration with its deposit or refund without it, else Alonzo's common form, else refused.
    */
  def transTxCertV1V2(txCert: ConwayTxCert): Either[ContextError[Conway], PV1.DCert] =
    val c = xyz.kd5ujc.scarab.ledger.era.EraTxCert.conway
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
      .orElse(AlonzoTxInfo.transTxCertCommon[Conway](txCert)(using c))
      .toRight(ConwayContextError.CertificateNotSupported(txCert))

  /** `transPlutusPurposeV1V2` (`Conway/TxInfo.hs:724-739`, cardano-ledger f649f975) over Alonzo's
    * `transPlutusPurpose` (`Alonzo/Plutus/TxInfo.hs:381-392`), written out: Alonzo's purposes are
    * not among the era types yet. A certificate takes the V1 and V2 form both languages'
    * `toPlutusTxCert` give (`transTxCertV1V2`); a vote and a proposal are refused.
    */
  def transPlutusPurposeV1V2(
      purpose: ConwayPlutusPurpose[AsIxItem]
  ): Either[ContextError[Conway], PV1.ScriptPurpose] = purpose match
    case ConwayPlutusPurpose.ConwaySpending(AsIxItem(_, txIn)) =>
      Right(PV1.ScriptPurpose.Spending(PlutusTxInfos.transTxIn(txIn)))
    case ConwayPlutusPurpose.ConwayMinting(AsIxItem(_, policyId)) =>
      Right(PV1.ScriptPurpose.Minting(AlonzoTxInfo.transPolicyID(policyId)))
    case ConwayPlutusPurpose.ConwayCertifying(AsIxItem(_, txCert)) =>
      transTxCertV1V2(txCert).map(PV1.ScriptPurpose.Certifying(_))
    case ConwayPlutusPurpose.ConwayWithdrawing(AsIxItem(_, accountAddress)) =>
      Right(
        PV1.ScriptPurpose.Rewarding(
          PV1.StakingCredential.StakingHash(transAccountAddress(accountAddress))
        )
      )
    case ConwayPlutusPurpose.ConwayVoting(_) | ConwayPlutusPurpose.ConwayProposing(_) =>
      Left(
        ConwayContextError.PlutusPurposeNotSupported(
          ConwayPlutusPurpose.hoistPlutusPurpose[AsIxItem, AsItem](
            [ix, it] => (a: AsIxItem[ix, it]) => AsIxItem.toAsItem(a)
          )(purpose)
        )
      )

  /** `instance EraPlutusTxInfo 'PlutusV1 ConwayEra` (`Conway/TxInfo.hs:399-437`, cardano-ledger
    * f649f975).
    */
  val plutusV1: EraPlutusTxInfo[Language.PlutusV1, Conway] =
    new EraPlutusTxInfo[Language.PlutusV1, Conway]:
      private given EraPlutusTxInfo[Language.PlutusV1, Conway] = this
      private val t = EraTx.conway
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given BabbageEraTxOut[Conway] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV1] = PlutusLanguage.v1

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: ConwayTxCert
      ): Either[ContextError[Conway], PV1.DCert] = transTxCertV1V2(txCert)

      override def toPlutusScriptPurpose(
          pv: ProtVer,
          purpose: ConwayPlutusPurpose[AsIxItem]
      ): Either[ContextError[Conway], PV1.ScriptPurpose] = transPlutusPurposeV1V2(purpose)

      override def toPlutusTxInfo(
          lti: LedgerTxInfo[Conway]
      ): PlutusTxInfoResult[Language.PlutusV1, Conway] =
        val tx = lti.ltiTx
        val txBody = t.body[TxLevel.Top](tx)
        val result = for
          _ <- guardConwayFeaturesForPlutusV1V2[Conway, TxLevel.Top](txBody)
          timeRange <- transValidityInterval[Conway, ContextError[Conway]](
            lti.ltiSlotToPOSIXTime,
            b.vldt[TxLevel.Top](txBody)
          )
          inputs <- b
            .inputs[TxLevel.Top](txBody)
            .toVector
            .traverse(transTxInInfoV1(lti.ltiUTxO, _))
          _ <- b
            .referenceInputs[TxLevel.Top](txBody)
            .toVector
            .traverse(transTxInInfoV1(lti.ltiUTxO, _))
          outputs <- b
            .outputs[TxLevel.Top](txBody)
            .zipWithIndex
            .traverse((out, ix) => transTxOutV1[Conway](TxOutSource.TxOutFromOutput(ix), out))
          txCerts <- AlonzoTxInfo.transTxBodyCerts[Language.PlutusV1, Conway, TxLevel.Top](
            lti.ltiProtVer,
            txBody
          )
          datums = AlonzoTxInfo
            .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
        yield PV1.TxInfo(
          txInfoInputs = inputs,
          txInfoOutputs = outputs,
          txInfoFee = transCoinToValue(b.fee(txBody)),
          txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
          txInfoDCert = txCerts,
          txInfoWdrl = AlonzoTxInfo.transTxBodyWithdrawals[Conway, TxLevel.Top](txBody),
          txInfoValidRange = timeRange,
          txInfoSignatories = AlonzoTxInfo.transTxBodyReqSignerHashes[Conway, TxLevel.Top](txBody),
          txInfoData = datums,
          txInfoId = AlonzoTxInfo.transTxBodyId[Conway, TxLevel.Top](txBody)
        )
        PlutusTxInfoResult(result.map(txInfo => _ => Right(txInfo)))

      override def toPlutusArgs(
          pv: ProtVer,
          txInfo: PV1.TxInfo,
          purpose: ConwayPlutusPurpose[AsIxItem],
          maybeSpendingData: Option[Data],
          redeemerData: Data
      ): Either[ContextError[Conway], PlutusV1Args] =
        AlonzoTxInfo.toPlutusV1Args[Conway](pv, txInfo, purpose, maybeSpendingData, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Conway],
          txIn: TxIn
      ): Either[ContextError[Conway], PV1.TxInInfo] =
        transTxInInfoV1(utxo, txIn)

      override def lookupTxInfoResult(
          result: ConwayTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV1, Conway] = result.tirPlutusV1

  /** `instance EraPlutusTxInfo 'PlutusV2 ConwayEra` (`Conway/TxInfo.hs:439-480`, cardano-ledger
    * f649f975).
    */
  val plutusV2: EraPlutusTxInfo[Language.PlutusV2, Conway] =
    new EraPlutusTxInfo[Language.PlutusV2, Conway]:
      private given EraPlutusTxInfo[Language.PlutusV2, Conway] = this
      private val t = EraTx.conway
      private val b = t.bodyOf
      private given b.Level[TxLevel.Top] = b.top
      private given BabbageEraTxOut[Conway] = b.outputsOf

      override def plutusLanguage: PlutusLanguage[Language.PlutusV2] = PlutusLanguage.v2

      override def toPlutusTxCert(
          pv: ProtVer,
          txCert: ConwayTxCert
      ): Either[ContextError[Conway], PV1.DCert] = transTxCertV1V2(txCert)

      override def toPlutusScriptPurpose(
          pv: ProtVer,
          purpose: ConwayPlutusPurpose[AsIxItem]
      ): Either[ContextError[Conway], PV1.ScriptPurpose] = transPlutusPurposeV1V2(purpose)

      override def toPlutusTxInfo(
          lti: LedgerTxInfo[Conway]
      ): PlutusTxInfoResult[Language.PlutusV2, Conway] =
        val tx = lti.ltiTx
        val txBody = t.body[TxLevel.Top](tx)
        val pv = lti.ltiProtVer
        val result = for
          _ <- guardConwayFeaturesForPlutusV1V2[Conway, TxLevel.Top](txBody)
          timeRange <- transValidityInterval[Conway, ContextError[Conway]](
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
          outputs <- b
            .outputs[TxLevel.Top](txBody)
            .zipWithIndex
            .traverse((out, ix) =>
              BabbageTxInfo.transTxOutV2[Conway](TxOutSource.TxOutFromOutput(ix), out)
            )
          txCerts <- AlonzoTxInfo.transTxBodyCerts[Language.PlutusV2, Conway, TxLevel.Top](
            pv,
            txBody
          )
          plutusRedeemers <- BabbageTxInfo.transTxRedeemers[Language.PlutusV2, Conway, TxLevel.Top](
            pv,
            tx
          )
          datums = AlonzoTxInfo
            .transTxWitsDatums(t.wits[TxLevel.Top](tx))(using t.witsOf)
        yield PV2.TxInfo(
          txInfoInputs = inputs,
          txInfoReferenceInputs = refInputs,
          txInfoOutputs = outputs,
          txInfoFee = transCoinToValue(b.fee(txBody)),
          txInfoMint = AlonzoTxInfo.transMintValue(b.mint[TxLevel.Top](txBody)),
          txInfoDCert = txCerts,
          txInfoWdrl = AssocMap(AlonzoTxInfo.transTxBodyWithdrawals[Conway, TxLevel.Top](txBody)),
          txInfoValidRange = timeRange,
          txInfoSignatories = AlonzoTxInfo.transTxBodyReqSignerHashes[Conway, TxLevel.Top](txBody),
          txInfoRedeemers = plutusRedeemers,
          txInfoData = AssocMap(datums),
          txInfoId = AlonzoTxInfo.transTxBodyId[Conway, TxLevel.Top](txBody)
        )
        PlutusTxInfoResult(result.map(txInfo => _ => Right(txInfo)))

      override def toPlutusArgs(
          pv: ProtVer,
          txInfo: PV2.TxInfo,
          purpose: ConwayPlutusPurpose[AsIxItem],
          maybeSpendingData: Option[Data],
          redeemerData: Data
      ): Either[ContextError[Conway], PlutusV2Args] =
        BabbageTxInfo.toPlutusV2Args[Conway](pv, txInfo, purpose, maybeSpendingData, redeemerData)

      override def toPlutusTxInInfo(
          utxo: UTxO[Conway],
          txIn: TxIn
      ): Either[ContextError[Conway], PV2.TxInInfo] =
        BabbageTxInfo.transTxInInfoV2(utxo, txIn)

      override def lookupTxInfoResult(
          result: ConwayTxInfoResult
      ): PlutusTxInfoResult[Language.PlutusV2, Conway] = result.tirPlutusV2

  /** `instance EraPlutusContext ConwayEra` (`Conway/TxInfo.hs:149-178`, cardano-ledger f649f975):
    * Plutus V1 to V3, each language's information computed when first looked up.
    */
  val context: EraPlutusContext[Conway] = new EraPlutusContext[Conway]:
    override def mkSupportedLanguage(
        lang: xyz.kd5ujc.scarab.plutus.PlutusLanguage
    ): Option[SupportedLanguage[Conway]] = lang match
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V1 => Some(SupportedLanguage(plutusV1))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V2 => Some(SupportedLanguage(plutusV2))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V3 => Some(SupportedLanguage(plutusV3))
      case xyz.kd5ujc.scarab.plutus.PlutusLanguage.V4 => None

    // `Conway/TxInfo.hs:164-167`, cardano-ledger f649f975
    override def mkSupportedPlutusRunnable(
        v: Version,
        plutusScript: ConwayPlutusScript
    ): SupportedPlutusRunnable[Conway] = plutusScript match
      case ConwayPlutusScript.V1(p) =>
        SupportedPlutusRunnable(plutusV1, PlutusRunnable.decodePlutusRunnable(v, p))
      case ConwayPlutusScript.V2(p) =>
        SupportedPlutusRunnable(plutusV2, PlutusRunnable.decodePlutusRunnable(v, p))
      case ConwayPlutusScript.V3(p) =>
        SupportedPlutusRunnable(plutusV3, PlutusRunnable.decodePlutusRunnable(v, p))

    override def mkTxInfoResult(lti: LedgerTxInfo[Conway]): ConwayTxInfoResult =
      ConwayTxInfoResult(
        plutusV1.toPlutusTxInfo(lti),
        plutusV2.toPlutusTxInfo(lti),
        plutusV3.toPlutusTxInfo(lti)
      )

/** Conway's memoised transaction information (`data TxInfoResult ConwayEra = ConwayTxInfoResult`,
  * `Conway/TxInfo.hs:152-156`, cardano-ledger f649f975): Plutus V1's, V2's and V3's, each computed
  * when first looked up, as the reference keeps its fields lazy.
  */
final class ConwayTxInfoResult(
    plutusV1: => PlutusTxInfoResult[Language.PlutusV1, Conway],
    plutusV2: => PlutusTxInfoResult[Language.PlutusV2, Conway],
    plutusV3: => PlutusTxInfoResult[Language.PlutusV3, Conway]
):
  lazy val tirPlutusV1: PlutusTxInfoResult[Language.PlutusV1, Conway] = plutusV1
  lazy val tirPlutusV2: PlutusTxInfoResult[Language.PlutusV2, Conway] = plutusV2
  lazy val tirPlutusV3: PlutusTxInfoResult[Language.PlutusV3, Conway] = plutusV3
