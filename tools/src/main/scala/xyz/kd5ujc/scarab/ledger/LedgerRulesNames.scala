// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.ledger.era.{
  AlonzoContextError,
  BabbageContextError,
  CollectError as EraCollectError
}
import xyz.kd5ujc.scarab.ledger.era.conway.{ConwayContextError, ConwayTxCert}
import xyz.kd5ujc.scarab.ledger.era.{ConwayDelegCert, Delegatee}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayCertPredFailure,
  ConwayCertsPredFailure,
  ConwayDelegPredFailure,
  ConwayGovCertPredFailure,
  ConwayGovPredFailure,
  ConwayLedgerPredFailure,
  ConwayUtxoPredFailure,
  ConwayUtxosPredFailure,
  ConwayUtxowPredFailure,
  ShelleyPoolPredFailure
}
import xyz.kd5ujc.scarab.ledger.state.{Conway, Credential as StateCredential, Staking}

/** The predicate names a ledger-rules report may give as a result: the `result` values of the
  * scenario's report schema (`scenarios/ledger-rules/schema.json` of node-diversity-conformance at
  * 9dc68a75) other than `pass`. They are Amaru's names, which mostly are the reference's
  * constructors without their rule suffix.
  */
enum ReportName derives CanEqual:
  case BabbageNonDisjointRefInputs, BabbageOutputTooSmallUTxO, BadInputsUTxO,
    CommitteeHasPreviouslyResigned, CommitteeIsUnknown, ConflictingMetadataHash,
    ConwayTreasuryValueMismatch, ConwayTxRefScriptsSizeTooBig, ConwayWdrlNotDelegatedToDRep,
    DRepAlreadyRegistered, DRepNotRegistered, DecodingFailure, DelegateeDRepNotRegistered,
    DelegateeStakePoolNotRegistered, DisallowedVoters, ExtraneousScriptWitnessesUTXOW,
    FeeTooSmallUTxO, GovActionsDoNotExist, IncorrectDepositDELEG, IncorrectDepositDRep,
    IncorrectTotalCollateralField, InputSetEmptyUTxO, InsufficientCollateral,
    InvalidGuardrailsScriptHash, InvalidPrevGovActionId, InvalidWitnessesUTXOW,
    MalformedReferenceScripts, MalformedScriptWitnesses, MaxTxSizeUTxO,
    MissingScriptWitnessesUTXOW, MissingTxBodyMetadataHash, MissingTxMetadata,
    MissingVerificationKeyWitnessesUTXOW, NoCollateralInputs, OutputTooBigUTxO, OutsideForecast,
    OutsideValidityIntervalUTxO, ProposalCantFollow, ProposalProcedureNetworkIdMismatch,
    ProposalReturnAccountDoesNotExist, ScriptsNotPaidUTxO, StakeCredentialInvalidPoolDelegation,
    StakeCredentialInvalidVoteDelegation, StakeKeyHasNonZeroAccountBalance, StakeKeyNotRegistered,
    StakeKeyRegistered, StakePoolCostTooLowPOOL, StakePoolNotRegisteredOnKeyPOOL,
    StakePoolRetirementWrongEpochPOOL, TooManyCollateralInputs,
    TreasuryWithdrawalReturnAccountsDoNotExist, TreasuryWithdrawalsAllZeros, ValidationTagMismatch,
    ValueNotConservedUTxO, VotersDoNotExist, VotingOnExpiredGovAction, WithdrawalsNotInRewardsCERTS,
    WrongNetworkInTxBody, WrongNetworkInTxOutput, WrongNetworkPOOL, WrongNetworkWithdrawal,
    ByronTxOutInContext, ConflictingCommitteeUpdate, CurrentTreasuryFieldNotSupported,
    ExUnitsTooBigUTxO, ExpirationEpochTooSmall, ExtraRedeemers, InlineDatumsNotSupported,
    MalformedProposal, MissingCostModel, MissingRedeemers, MissingRequiredDatums,
    NotAllowedSupplementalDatums, PPViewHashesDontMatch, ProposalDepositIncorrect,
    ProposalProceduresFieldNotSupported, ScriptWitnessNotValidatingUTXOW,
    TreasuryDonationFieldNotSupported, UnspendableUTxONoDatumHash,
    VotingProceduresFieldNotSupported

object ReportName:
  /** The name a fixture's `expected.predicate` gives, if the report schema has it. */
  def parse(name: String): Option[ReportName] = values.find(_.toString == name)

/** Scarab's predicate failures as the report's names, following the names the reference harness of
  * the corpus gives the reference's failures (Amaru `crates/amaru/tests/conformance/
  * validation-rules/src/Command/ValidatePhaseOne/Run.hs`, `normalizeLedgerFailure` and below, at
  * 40ba9950). Scarab's failures are Conway's era failures, the reference's constructors
  * (`ConwayLedgerPredFailure` and the failures it holds), so the correspondence is the harness's,
  * with two differences: a failure is never named after the fixture's expectation (the expectation
  * only chooses among the names Scarab's failures have, [[Selection]]), and a failure that the
  * harness names only by matching the expectation against its text is named by its case.
  *
  * A failure has no name or one: none where the schema lists no name for it (a failure of a
  * protocol version the corpus does not reach, or one the corpus does not exercise), or where the
  * name depends on a context the judgment could not give ([[FailureContext]]).
  */
object LedgerRulesNames:
  import ReportName.*

  /** A failure of Conway's LEDGER, which MEMPOOL's failures are. */
  type Failure = ConwayLedgerPredFailure[Conway]

  /** The names of `failure` in the transaction whose `context` the judgment gave. */
  def of(failure: Failure, context: FailureContext): Vector[ReportName] = failure match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(u) => utxow(u)
    case ConwayLedgerPredFailure.ConwayCertsFailure(c) => certs(c, context)
    case ConwayLedgerPredFailure.ConwayGovFailure(g)   => gov(g)
    case ConwayLedgerPredFailure.ConwayWdrlNotDelegatedToDRep(_) =>
      Vector(ConwayWdrlNotDelegatedToDRep)
    case ConwayLedgerPredFailure.ConwayTreasuryValueMismatch(_) =>
      Vector(ConwayTreasuryValueMismatch)
    case ConwayLedgerPredFailure.ConwayTxRefScriptsSizeTooBig(_) =>
      Vector(ConwayTxRefScriptsSizeTooBig)
    case ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.AllInputsSpent) =>
      Vector(if context.hasInputs then BadInputsUTxO else InputSetEmptyUTxO)
    case ConwayLedgerPredFailure.ConwayMempoolFailure(_)             => Vector.empty
    case ConwayLedgerPredFailure.ConwayWithdrawalsMissingAccounts(_) => Vector.empty
    case ConwayLedgerPredFailure.ConwayIncompleteWithdrawals(_)      => Vector.empty

  /** The mempool's refusal of a vote by an unelected committee member below protocol version 11,
    * which a block does not refuse: the harness drops it (`isMempoolOnlyFailure`), as Amaru's
    * fixtures judge a transaction as part of a block.
    */
  def mempoolOnly(failure: Failure): Boolean = failure match
    case ConwayLedgerPredFailure.ConwayMempoolFailure(MempoolProblem.UnelectedCommittee(_)) => true
    case _                                                                                  => false

  /** The constructor of the innermost rule's failure, as the reference names it: what the sidecar
    * records of a failure beside its names, for its reader.
    */
  def constructor(failure: Failure): String = failure match
    case ConwayLedgerPredFailure.ConwayUtxowFailure(u) =>
      u match
        case ConwayUtxowPredFailure.UtxoFailure(f) =>
          f match
            case ConwayUtxoPredFailure.UtxosFailure(s) => s.productPrefix
            case other                                 => other.productPrefix
        case other => other.productPrefix
    case ConwayLedgerPredFailure.ConwayCertsFailure(c) =>
      c match
        case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.DelegFailure(d)) =>
          d.productPrefix
        case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.PoolFailure(p)) =>
          p.productPrefix
        case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.GovCertFailure(g)) =>
          g.productPrefix
        case other => other.productPrefix
    case ConwayLedgerPredFailure.ConwayGovFailure(g) => g.productPrefix
    case other                                       => other.productPrefix

  private def utxow(u: ConwayUtxowPredFailure[Conway]): Vector[ReportName] = u match
    case ConwayUtxowPredFailure.UtxoFailure(f)           => utxo(f)
    case ConwayUtxowPredFailure.InvalidWitnessesUTXOW(_) => Vector(InvalidWitnessesUTXOW)
    case ConwayUtxowPredFailure.MissingVKeyWitnessesUTXOW(_) =>
      Vector(MissingVerificationKeyWitnessesUTXOW)
    case ConwayUtxowPredFailure.MissingScriptWitnessesUTXOW(_) =>
      Vector(MissingScriptWitnessesUTXOW)
    case ConwayUtxowPredFailure.ScriptWitnessNotValidatingUTXOW(_) =>
      Vector(ScriptWitnessNotValidatingUTXOW)
    case ConwayUtxowPredFailure.MissingTxBodyMetadataHash(_) => Vector(MissingTxBodyMetadataHash)
    case ConwayUtxowPredFailure.MissingTxMetadata(_)         => Vector(MissingTxMetadata)
    case ConwayUtxowPredFailure.ConflictingMetadataHash(_)   => Vector(ConflictingMetadataHash)
    case ConwayUtxowPredFailure.InvalidMetadata()            => Vector.empty
    case ConwayUtxowPredFailure.ExtraneousScriptWitnessesUTXOW(_) =>
      Vector(ExtraneousScriptWitnessesUTXOW)
    case ConwayUtxowPredFailure.MissingRedeemers(_)         => Vector(MissingRedeemers)
    case ConwayUtxowPredFailure.MissingRequiredDatums(_, _) => Vector(MissingRequiredDatums)
    case ConwayUtxowPredFailure.NotAllowedSupplementalDatums(_, _) =>
      Vector(NotAllowedSupplementalDatums)
    case ConwayUtxowPredFailure.PPViewHashesDontMatch(_)      => Vector(PPViewHashesDontMatch)
    case ConwayUtxowPredFailure.UnspendableUTxONoDatumHash(_) => Vector(UnspendableUTxONoDatumHash)
    case ConwayUtxowPredFailure.ExtraRedeemers(_)             => Vector(ExtraRedeemers)
    case ConwayUtxowPredFailure.MalformedScriptWitnesses(_)   => Vector(MalformedScriptWitnesses)
    case ConwayUtxowPredFailure.MalformedReferenceScripts(_)  => Vector(MalformedReferenceScripts)
    case ConwayUtxowPredFailure.ScriptIntegrityHashMismatch(_, _) => Vector.empty

  private def utxos(u: ConwayUtxosPredFailure[Conway]): Vector[ReportName] = u match
    case ConwayUtxosPredFailure.ValidationTagMismatch(_, _) => Vector(ValidationTagMismatch)
    case ConwayUtxosPredFailure.CollectErrors(errors) =>
      errors.toList.toVector.flatMap(collect).distinct

  private def utxo(u: ConwayUtxoPredFailure[Conway]): Vector[ReportName] = u match
    case ConwayUtxoPredFailure.UtxosFailure(f)  => utxos(f)
    case ConwayUtxoPredFailure.BadInputsUTxO(_) => Vector(BadInputsUTxO)
    case ConwayUtxoPredFailure.OutsideValidityIntervalUTxO(_, _) =>
      Vector(OutsideValidityIntervalUTxO)
    case ConwayUtxoPredFailure.MaxTxSizeUTxO(_)             => Vector(MaxTxSizeUTxO)
    case ConwayUtxoPredFailure.InputSetEmptyUTxO()          => Vector(InputSetEmptyUTxO)
    case ConwayUtxoPredFailure.FeeTooSmallUTxO(_)           => Vector(FeeTooSmallUTxO)
    case ConwayUtxoPredFailure.ValueNotConservedUTxO(_)     => Vector(ValueNotConservedUTxO)
    case ConwayUtxoPredFailure.WrongNetwork(_, _)           => Vector(WrongNetworkInTxOutput)
    case ConwayUtxoPredFailure.WrongNetworkWithdrawal(_, _) => Vector(WrongNetworkWithdrawal)
    case ConwayUtxoPredFailure.OutputTooSmallUTxO(_)        => Vector(BabbageOutputTooSmallUTxO)
    case ConwayUtxoPredFailure.OutputBootAddrAttrsTooBig(_) => Vector.empty
    case ConwayUtxoPredFailure.OutputTooBigUTxO(_)          => Vector(OutputTooBigUTxO)
    case ConwayUtxoPredFailure.InsufficientCollateral(_, _) => Vector(InsufficientCollateral)
    case ConwayUtxoPredFailure.ScriptsNotPaidUTxO(_)        => Vector(ScriptsNotPaidUTxO)
    case ConwayUtxoPredFailure.ExUnitsTooBigUTxO(_)         => Vector(ExUnitsTooBigUTxO)
    case ConwayUtxoPredFailure.CollateralContainsNonADA(_)  => Vector.empty
    case ConwayUtxoPredFailure.WrongNetworkInTxBody(_)      => Vector(WrongNetworkInTxBody)
    case ConwayUtxoPredFailure.OutsideForecast(_)           => Vector(OutsideForecast)
    case ConwayUtxoPredFailure.TooManyCollateralInputs(_)   => Vector(TooManyCollateralInputs)
    case ConwayUtxoPredFailure.NoCollateralInputs()         => Vector(NoCollateralInputs)
    case ConwayUtxoPredFailure.IncorrectTotalCollateralField(_, _) =>
      Vector(IncorrectTotalCollateralField)
    case ConwayUtxoPredFailure.BabbageOutputTooSmallUTxO(_)   => Vector(BabbageOutputTooSmallUTxO)
    case ConwayUtxoPredFailure.BabbageNonDisjointRefInputs(_) => Vector(BabbageNonDisjointRefInputs)

  /** A `CollectError` of UTXOS: the harness names `NoCostModel` and a time past the horizon, and
    * the context errors by the constructor named in the failure's text; the other errors have no
    * name in the schema.
    */
  private def collect(e: EraCollectError[Conway]): Vector[ReportName] = e match
    case EraCollectError.NoCostModel(_)    => Vector(MissingCostModel)
    case EraCollectError.NoRedeemer(_)     => Vector.empty
    case EraCollectError.NoWitness(_)      => Vector.empty
    case EraCollectError.BadTranslation(t) => context(t)

  private def context(e: ConwayContextError[Conway]): Vector[ReportName] = e match
    case ConwayContextError.BabbageContextError(b)       => babbage(b)
    case ConwayContextError.CertificateNotSupported(_)   => Vector.empty
    case ConwayContextError.PlutusPurposeNotSupported(_) => Vector.empty
    case ConwayContextError.CurrentTreasuryFieldNotSupported(_) =>
      Vector(CurrentTreasuryFieldNotSupported)
    case ConwayContextError.VotingProceduresFieldNotSupported(_) =>
      Vector(VotingProceduresFieldNotSupported)
    case ConwayContextError.ProposalProceduresFieldNotSupported(_) =>
      Vector(ProposalProceduresFieldNotSupported)
    case ConwayContextError.TreasuryDonationFieldNotSupported(_) =>
      Vector(TreasuryDonationFieldNotSupported)
    case ConwayContextError.ReferenceInputsNotDisjointFromInputs(_) => Vector.empty

  private def babbage(e: BabbageContextError[Conway]): Vector[ReportName] = e match
    case BabbageContextError.AlonzoContextError(a)             => alonzo(a)
    case BabbageContextError.ByronTxOutInContext(_)            => Vector(ByronTxOutInContext)
    case BabbageContextError.RedeemerPointerPointsToNothing(_) => Vector.empty
    case BabbageContextError.InlineDatumsNotSupported(_)       => Vector(InlineDatumsNotSupported)
    case BabbageContextError.ReferenceScriptsNotSupported(_)   => Vector.empty
    case BabbageContextError.ReferenceInputsNotSupported(_)    => Vector.empty

  private def alonzo(e: AlonzoContextError[Conway]): Vector[ReportName] = e match
    case AlonzoContextError.TranslationLogicMissingInput(_)   => Vector.empty
    case AlonzoContextError.TimeTranslationPastHorizon(cause) => horizon(cause)
    case AlonzoContextError.CertificateNotSupported(_)        => Vector.empty
    case AlonzoContextError.PlutusPurposeNotSupported(_)      => Vector.empty
    case AlonzoContextError.Undecodable(_, _)                 => Vector.empty
    case AlonzoContextError.ImpossibleContextError(_)         => Vector.empty

  /** A time the clock could not translate: past its horizon, or with no sample, the harness's
    * `OutsideForecast`; the node's own missing era history has no name.
    */
  private def horizon(cause: TranslationError): Vector[ReportName] = cause match
    case TranslationError.PastHorizon(_, _) | TranslationError.NoClockSample(_) =>
      Vector(OutsideForecast)
    case _ => Vector.empty

  private def certs(
      c: ConwayCertsPredFailure[Conway],
      context: FailureContext
  ): Vector[ReportName] =
    c match
      case ConwayCertsPredFailure.WithdrawalsNotInRewardsCERTS(_) =>
        Vector(WithdrawalsNotInRewardsCERTS)
      case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.DelegFailure(d)) =>
        deleg(d, context)
      case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.PoolFailure(p)) => pool(p)
      case ConwayCertsPredFailure.CertFailure(ConwayCertPredFailure.GovCertFailure(g)) =>
        govCert(g)

  /** Conway DELEG. The reference refuses the deregistration and every delegation of an unregistered
    * credential with one failure (`StakeKeyNotRegisteredDELEG`, `Conway/Rules/Deleg.hs`), which
    * carries the credential and not the certificate; Amaru names it by the certificate, which the
    * judgment attributes ([[FailureContext.unregistered]]).
    */
  private def deleg(
      d: ConwayDelegPredFailure[Conway],
      context: FailureContext
  ): Vector[ReportName] = d match
    case ConwayDelegPredFailure.IncorrectDepositDELEG(_)   => Vector(IncorrectDepositDELEG)
    case ConwayDelegPredFailure.StakeKeyRegisteredDELEG(_) => Vector(StakeKeyRegistered)
    case ConwayDelegPredFailure.StakeKeyNotRegisteredDELEG(credential) =>
      context.unregistered.get(credential).toVector.map(_.name)
    case ConwayDelegPredFailure.StakeKeyHasNonZeroAccountBalanceDELEG(_) =>
      Vector(StakeKeyHasNonZeroAccountBalance)
    case ConwayDelegPredFailure.DelegateeDRepNotRegisteredDELEG(_) =>
      Vector(DelegateeDRepNotRegistered)
    case ConwayDelegPredFailure.DelegateeStakePoolNotRegisteredDELEG(_) =>
      Vector(DelegateeStakePoolNotRegistered)
    case ConwayDelegPredFailure.DepositIncorrectDELEG(_) => Vector.empty
    case ConwayDelegPredFailure.RefundIncorrectDELEG(_)  => Vector.empty

  private def pool(p: ShelleyPoolPredFailure[Conway]): Vector[ReportName] = p match
    case ShelleyPoolPredFailure.StakePoolNotRegisteredOnKeyPOOL(_) =>
      Vector(StakePoolNotRegisteredOnKeyPOOL)
    case ShelleyPoolPredFailure.StakePoolRetirementWrongEpochPOOL(_, _) =>
      Vector(StakePoolRetirementWrongEpochPOOL)
    case ShelleyPoolPredFailure.StakePoolCostTooLowPOOL(_)        => Vector(StakePoolCostTooLowPOOL)
    case ShelleyPoolPredFailure.WrongNetworkPOOL(_, _)            => Vector(WrongNetworkPOOL)
    case ShelleyPoolPredFailure.PoolMedataHashTooBig(_, _)        => Vector.empty
    case ShelleyPoolPredFailure.VRFKeyHashAlreadyRegistered(_, _) => Vector.empty

  /** Conway GOVCERT; the harness names an incorrect refund as an incorrect deposit. */
  private def govCert(g: ConwayGovCertPredFailure[Conway]): Vector[ReportName] = g match
    case ConwayGovCertPredFailure.ConwayDRepAlreadyRegistered(_) => Vector(DRepAlreadyRegistered)
    case ConwayGovCertPredFailure.ConwayDRepNotRegistered(_)     => Vector(DRepNotRegistered)
    case ConwayGovCertPredFailure.ConwayDRepIncorrectDeposit(_)  => Vector(IncorrectDepositDRep)
    case ConwayGovCertPredFailure.ConwayCommitteeHasPreviouslyResigned(_) =>
      Vector(CommitteeHasPreviouslyResigned)
    case ConwayGovCertPredFailure.ConwayDRepIncorrectRefund(_) => Vector(IncorrectDepositDRep)
    case ConwayGovCertPredFailure.ConwayCommitteeIsUnknown(_)  => Vector(CommitteeIsUnknown)

  /** Conway GOV; the harness names the votes of unelected committee members (protocol version 11)
    * as votes of voters that do not exist.
    */
  private def gov(g: ConwayGovPredFailure[Conway]): Vector[ReportName] = g match
    case ConwayGovPredFailure.GovActionsDoNotExist(_) => Vector(GovActionsDoNotExist)
    case ConwayGovPredFailure.MalformedProposal(_)    => Vector(MalformedProposal)
    case ConwayGovPredFailure.ProposalProcedureNetworkIdMismatch(_, _) =>
      Vector(ProposalProcedureNetworkIdMismatch)
    case ConwayGovPredFailure.TreasuryWithdrawalsNetworkIdMismatch(_, _) => Vector.empty
    case ConwayGovPredFailure.ProposalDepositIncorrect(_)   => Vector(ProposalDepositIncorrect)
    case ConwayGovPredFailure.DisallowedVoters(_)           => Vector(DisallowedVoters)
    case ConwayGovPredFailure.ConflictingCommitteeUpdate(_) => Vector(ConflictingCommitteeUpdate)
    case ConwayGovPredFailure.ExpirationEpochTooSmall(_)    => Vector(ExpirationEpochTooSmall)
    case ConwayGovPredFailure.InvalidPrevGovActionId(_)     => Vector(InvalidPrevGovActionId)
    case ConwayGovPredFailure.VotingOnExpiredGovAction(_)   => Vector(VotingOnExpiredGovAction)
    case ConwayGovPredFailure.ProposalCantFollow(_, _)      => Vector(ProposalCantFollow)
    case ConwayGovPredFailure.InvalidGuardrailsScriptHash(_, _) =>
      Vector(InvalidGuardrailsScriptHash)
    case ConwayGovPredFailure.DisallowedProposalDuringBootstrap(_) => Vector.empty
    case ConwayGovPredFailure.DisallowedVotesDuringBootstrap(_)    => Vector.empty
    case ConwayGovPredFailure.VotersDoNotExist(_)                  => Vector(VotersDoNotExist)
    case ConwayGovPredFailure.ZeroTreasuryWithdrawals(_) => Vector(TreasuryWithdrawalsAllZeros)
    case ConwayGovPredFailure.ProposalReturnAccountDoesNotExist(_) =>
      Vector(ProposalReturnAccountDoesNotExist)
    case ConwayGovPredFailure.TreasuryWithdrawalReturnAccountsDoNotExist(_) =>
      Vector(TreasuryWithdrawalReturnAccountsDoNotExist)
    case ConwayGovPredFailure.UnelectedCommitteeVoters(_) => Vector(VotersDoNotExist)

/** The certificate a refusal of an unregistered credential is about, as Amaru names the refusal
  * (Amaru `crates/amaru-ledger/src/rules/transaction/fixture.rs:509-518, 535-537` at 40ba9950): an
  * unregistration, a delegation to a pool, of the vote, or of both. A delegation of both is a
  * delegation of the vote first and then to a pool (`phase_one/certificates.rs:418-422`), so its
  * refusal is the vote's.
  */
enum CertificateKind derives CanEqual:
  case Unregistration, PoolDelegation, VoteDelegation, PoolAndVoteDelegation

  def name: ReportName = this match
    case Unregistration        => ReportName.StakeKeyNotRegistered
    case PoolDelegation        => ReportName.StakeCredentialInvalidPoolDelegation
    case VoteDelegation        => ReportName.StakeCredentialInvalidVoteDelegation
    case PoolAndVoteDelegation => ReportName.StakeCredentialInvalidVoteDelegation

object CertificateKind:
  /** The kind of a certificate that can refuse an unregistered credential: an unregistration
    * (Shelley's or Conway's, `ConwayUnRegCert` with or without its refund), or a delegation
    * (`ConwayDelegCert`; its registering form, `ConwayRegDelegCert`, refuses a registered
    * credential instead).
    */
  def of(certificate: ConwayTxCert): Option[CertificateKind] = certificate match
    case ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayUnRegCert(_, _)) =>
      Some(Unregistration)
    case ConwayTxCert.ConwayTxCertDeleg(ConwayDelegCert.ConwayDelegCert(_, delegatee)) =>
      Some(delegatee match
        case Delegatee.DelegStake(_)        => PoolDelegation
        case Delegatee.DelegVote(_)         => VoteDelegation
        case Delegatee.DelegStakeVote(_, _) => PoolAndVoteDelegation)
    case ConwayTxCert.ConwayTxCertDeleg(
          ConwayDelegCert.ConwayRegCert(_, _) | ConwayDelegCert.ConwayRegDelegCert(_, _, _)
        ) =>
      None
    case ConwayTxCert.ConwayTxCertPool(_) | ConwayTxCert.ConwayTxCertGov(_) => None

/** What a failure's name depends on beyond the failure: whether the transaction spends any input,
  * which tells the two meanings of the mempool's "all inputs are spent" apart as the harness tells
  * them, and, for each credential refused as unregistered, the kind of the one certificate that
  * refused it. A credential that several certificates of different kinds refused has none: the
  * failures do not say which is which.
  */
final case class FailureContext(
    hasInputs: Boolean,
    unregistered: Map[StateCredential[Staking], CertificateKind]
)
