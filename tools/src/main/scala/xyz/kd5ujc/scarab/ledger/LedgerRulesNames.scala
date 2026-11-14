// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

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

/** Scarab's predicate failures as the report's names, following the names the reference harness of
  * the corpus gives the reference's failures (Amaru `crates/amaru/tests/conformance/
  * validation-rules/src/Command/ValidatePhaseOne/Run.hs`, `normalizeLedgerFailure` and below, at
  * 40ba9950). Scarab's failures are the reference's constructors, so the correspondence is the
  * harness's, with two differences: no name is chosen by the fixture's expectation, and a failure
  * that the harness names only by matching the expectation against its text is named by its case.
  *
  * A failure has no name, several names, or one. None: the schema lists no name for it (a failure
  * of a protocol version the corpus does not reach, or one the corpus does not exercise). Several:
  * the reference's one failure is several of Amaru's, which tell apart what the reference does not;
  * the first is the closest to the reference's own name.
  */
object LedgerRulesNames:
  import ReportName.*

  /** The names of `failure`; `hasInputs` is whether the transaction spends any input, which tells
    * the two meanings of the mempool's "all inputs are spent" apart, as the harness tells them.
    */
  def of(failure: PredicateFailure, hasInputs: Boolean): Vector[ReportName] = failure match
    case PredicateFailure.Utxow(u)                  => utxow(u)
    case PredicateFailure.Certs(c)                  => certs(c)
    case PredicateFailure.Gov(g)                    => gov(g)
    case PredicateFailure.WdrlNotDelegatedToDRep(_) => Vector(ConwayWdrlNotDelegatedToDRep)
    case PredicateFailure.TreasuryValueMismatch(_)  => Vector(ConwayTreasuryValueMismatch)
    case PredicateFailure.TxRefScriptsSizeTooBig(_) => Vector(ConwayTxRefScriptsSizeTooBig)
    case PredicateFailure.Mempool(MempoolProblem.AllInputsSpent) =>
      Vector(if hasInputs then BadInputsUTxO else InputSetEmptyUTxO)
    case PredicateFailure.Mempool(_)                    => Vector.empty
    case PredicateFailure.WithdrawalsMissingAccounts(_) => Vector.empty
    case PredicateFailure.IncompleteWithdrawals(_)      => Vector.empty

  /** The mempool's refusal of a vote by an unelected committee member below protocol version 11,
    * which a block does not refuse: the harness drops it (`isMempoolOnlyFailure`), as Amaru's
    * fixtures judge a transaction as part of a block.
    */
  def mempoolOnly(failure: PredicateFailure): Boolean = failure match
    case PredicateFailure.Mempool(MempoolProblem.UnelectedCommittee(_)) => true
    case _                                                              => false

  private def utxow(u: UtxowFailure): Vector[ReportName] = u match
    case UtxowFailure.Utxo(f)                       => utxo(f)
    case UtxowFailure.InvalidWitnesses(_)           => Vector(InvalidWitnessesUTXOW)
    case UtxowFailure.MissingVKeyWitnesses(_)       => Vector(MissingVerificationKeyWitnessesUTXOW)
    case UtxowFailure.MissingScriptWitnesses(_)     => Vector(MissingScriptWitnessesUTXOW)
    case UtxowFailure.ScriptWitnessNotValidating(_) => Vector(ScriptWitnessNotValidatingUTXOW)
    case UtxowFailure.MissingTxBodyMetadataHash(_)  => Vector(MissingTxBodyMetadataHash)
    case UtxowFailure.MissingTxMetadata(_)          => Vector(MissingTxMetadata)
    case UtxowFailure.ConflictingMetadataHash(_)    => Vector(ConflictingMetadataHash)
    case UtxowFailure.InvalidMetadata               => Vector.empty
    case UtxowFailure.ExtraneousScriptWitnesses(_)  => Vector(ExtraneousScriptWitnessesUTXOW)
    case UtxowFailure.MissingRequiredDatums(_, _)   => Vector(MissingRequiredDatums)
    case UtxowFailure.NotAllowedSupplementalDatums(_, _) => Vector(NotAllowedSupplementalDatums)
    case UtxowFailure.PPViewHashesDontMatch(_)           => Vector(PPViewHashesDontMatch)
    case UtxowFailure.ScriptIntegrityHashMismatch(_, _)  => Vector.empty
    case UtxowFailure.UnspendableUTxONoDatumHash(_)      => Vector(UnspendableUTxONoDatumHash)
    case UtxowFailure.MalformedScriptWitnesses(_)        => Vector(MalformedScriptWitnesses)
    case UtxowFailure.MalformedReferenceScripts(_)       => Vector(MalformedReferenceScripts)
    case UtxowFailure.MIRInsufficientGenesisSigs(_)      => Vector.empty
    case UtxowFailure.MissingRedeemers(_)                => Vector(MissingRedeemers)
    case UtxowFailure.ExtraRedeemers(_)                  => Vector(ExtraRedeemers)

  private def utxo(u: UtxoFailure): Vector[ReportName] = u match
    case UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(_, _)) =>
      Vector(ValidationTagMismatch)
    case UtxoFailure.Utxos(UtxosFailure.CollectErrors(errors)) =>
      errors.toVector.flatMap(collect).distinct
    case UtxoFailure.ExpiredUTxO(_)                      => Vector.empty
    case UtxoFailure.Update(_)                           => Vector.empty
    case UtxoFailure.BadInputs(_)                        => Vector(BadInputsUTxO)
    case UtxoFailure.OutsideValidityInterval(_, _, _)    => Vector(OutsideValidityIntervalUTxO)
    case UtxoFailure.MaxTxSize(_)                        => Vector(MaxTxSizeUTxO)
    case UtxoFailure.InputSetEmpty                       => Vector(InputSetEmptyUTxO)
    case UtxoFailure.FeeTooSmall(_)                      => Vector(FeeTooSmallUTxO)
    case UtxoFailure.ValueNotConserved(_)                => Vector(ValueNotConservedUTxO)
    case UtxoFailure.WrongNetwork(_, _)                  => Vector(WrongNetworkInTxOutput)
    case UtxoFailure.WrongNetworkWithdrawal(_, _)        => Vector(WrongNetworkWithdrawal)
    case UtxoFailure.OutputTooSmall(_)                   => Vector(BabbageOutputTooSmallUTxO)
    case UtxoFailure.OutputBootAddrAttrsTooBig(_)        => Vector.empty
    case UtxoFailure.OutputTooBig(_)                     => Vector(OutputTooBigUTxO)
    case UtxoFailure.InsufficientCollateral(_, _)        => Vector(InsufficientCollateral)
    case UtxoFailure.ScriptsNotPaid(_)                   => Vector(ScriptsNotPaidUTxO)
    case UtxoFailure.ExUnitsTooBig(_)                    => Vector(ExUnitsTooBigUTxO)
    case UtxoFailure.CollateralContainsNonAda(_)         => Vector.empty
    case UtxoFailure.WrongNetworkInTxBody(_)             => Vector(WrongNetworkInTxBody)
    case UtxoFailure.OutsideForecast(_)                  => Vector(OutsideForecast)
    case UtxoFailure.TooManyCollateralInputs(_)          => Vector(TooManyCollateralInputs)
    case UtxoFailure.NoCollateralInputs                  => Vector(NoCollateralInputs)
    case UtxoFailure.IncorrectTotalCollateralField(_, _) => Vector(IncorrectTotalCollateralField)
    case UtxoFailure.BabbageOutputTooSmall(_)            => Vector(BabbageOutputTooSmallUTxO)
    case UtxoFailure.BabbageNonDisjointRefInputs(_)      => Vector(BabbageNonDisjointRefInputs)

  /** A `CollectError` of UTXOS: the harness names `NoCostModel` and a time past the horizon, and
    * the context errors by the constructor named in the failure's text; the other errors have no
    * name in the schema.
    */
  private def collect(e: CollectError): Vector[ReportName] = e match
    case CollectError.NoCostModel(_)    => Vector(MissingCostModel)
    case CollectError.NoRedeemer(_)     => Vector.empty
    case CollectError.NoWitness(_)      => Vector.empty
    case CollectError.BadTranslation(t) => translation(t)

  private def translation(t: TranslationError): Vector[ReportName] = t match
    case TranslationError.PastHorizon(_, _)           => Vector(OutsideForecast)
    case TranslationError.NoClockSample(_)            => Vector(OutsideForecast)
    case TranslationError.InlineDatumsNotSupported(_) => Vector(InlineDatumsNotSupported)
    case TranslationError.ByronTxOutInContext(_)      => Vector(ByronTxOutInContext)
    case TranslationError.VotingProceduresFieldNotSupported(_) =>
      Vector(VotingProceduresFieldNotSupported)
    case TranslationError.ProposalProceduresFieldNotSupported(_) =>
      Vector(ProposalProceduresFieldNotSupported)
    case TranslationError.TreasuryDonationFieldNotSupported(_) =>
      Vector(TreasuryDonationFieldNotSupported)
    case TranslationError.CurrentTreasuryFieldNotSupported(_) =>
      Vector(CurrentTreasuryFieldNotSupported)
    case TranslationError.RedeemerPointerPointsToNothing(_, _)    => Vector.empty
    case TranslationError.UnresolvedInput(_)                      => Vector.empty
    case TranslationError.ReferenceScriptsNotSupported(_)         => Vector.empty
    case TranslationError.ReferenceInputsNotSupported(_)          => Vector.empty
    case TranslationError.ReferenceInputsNotDisjointFromInputs(_) => Vector.empty
    case TranslationError.CertificateNotSupported(_)              => Vector.empty
    case TranslationError.PlutusPurposeNotSupported(_)            => Vector.empty
    case TranslationError.DelegationWithoutDelegatee              => Vector.empty
    case TranslationError.Undecodable(_)                          => Vector.empty
    case TranslationError.NoScriptBytes(_)                        => Vector.empty
    case TranslationError.NativeScriptRedeemer                    => Vector.empty
    case TranslationError.LanguageWithoutContext(_)               => Vector.empty
    case TranslationError.CostModelParameterTooLarge              => Vector.empty
    case TranslationError.NoEraHistory                            => Vector.empty

  private def certs(c: CertsFailure): Vector[ReportName] = c match
    case CertsFailure.WithdrawalsNotInRewards(_)   => Vector(WithdrawalsNotInRewardsCERTS)
    case CertsFailure.Cert(CertFailure.Deleg(d))   => deleg(d)
    case CertsFailure.Cert(CertFailure.Pool(p))    => pool(p)
    case CertsFailure.Cert(CertFailure.GovCert(g)) => govCert(g)

  /** Conway DELEG. The reference refuses the deregistration and every delegation of an unregistered
    * credential with one failure (`StakeKeyNotRegisteredDELEG`, `Conway/Rules/Deleg.hs`); Amaru
    * names it by the certificate (an unregistration, a delegation to a pool, of a vote), which the
    * failure does not carry.
    */
  private def deleg(d: DelegFailure): Vector[ReportName] = d match
    case DelegFailure.IncorrectDeposit(_)   => Vector(IncorrectDepositDELEG)
    case DelegFailure.StakeKeyRegistered(_) => Vector(StakeKeyRegistered)
    case DelegFailure.StakeKeyNotRegistered(_) =>
      Vector(
        StakeKeyNotRegistered,
        StakeCredentialInvalidPoolDelegation,
        StakeCredentialInvalidVoteDelegation
      )
    case DelegFailure.StakeKeyHasNonZeroAccountBalance(_) =>
      Vector(StakeKeyHasNonZeroAccountBalance)
    case DelegFailure.DelegateeDRepNotRegistered(_)      => Vector(DelegateeDRepNotRegistered)
    case DelegFailure.DelegateeStakePoolNotRegistered(_) => Vector(DelegateeStakePoolNotRegistered)
    case DelegFailure.DepositIncorrect(_)                => Vector.empty
    case DelegFailure.RefundIncorrect(_)                 => Vector.empty
    case DelegFailure.StakeKeyAlreadyRegistered(_)       => Vector.empty
    case DelegFailure.StakeDelegationImpossible(_)       => Vector.empty
    case DelegFailure.WrongCertificateType               => Vector.empty
    case DelegFailure.GenesisKeyNotInMapping(_)          => Vector.empty
    case DelegFailure.DuplicateGenesisDelegate(_)        => Vector.empty
    case DelegFailure.DuplicateGenesisVRF(_)             => Vector.empty
    case DelegFailure.InsufficientForInstantaneousRewards(_, _) => Vector.empty
    case DelegFailure.MIRCertificateTooLateinEpoch(_)           => Vector.empty
    case DelegFailure.MIRTransferNotCurrentlyAllowed            => Vector.empty
    case DelegFailure.MIRNegativesNotCurrentlyAllowed           => Vector.empty
    case DelegFailure.InsufficientForTransfer(_, _)             => Vector.empty
    case DelegFailure.MIRProducesNegativeUpdate                 => Vector.empty
    case DelegFailure.MIRNegativeTransfer(_, _)                 => Vector.empty
    case DelegFailure.DelegateeNotRegistered(_)                 => Vector.empty

  private def pool(p: PoolFailure): Vector[ReportName] = p match
    case PoolFailure.StakePoolNotRegisteredOnKey(_) => Vector(StakePoolNotRegisteredOnKeyPOOL)
    case PoolFailure.StakePoolRetirementWrongEpoch(_, _) =>
      Vector(StakePoolRetirementWrongEpochPOOL)
    case PoolFailure.StakePoolCostTooLow(_)            => Vector(StakePoolCostTooLowPOOL)
    case PoolFailure.WrongNetworkPool(_, _)            => Vector(WrongNetworkPOOL)
    case PoolFailure.PoolMedataHashTooBig(_, _)        => Vector.empty
    case PoolFailure.VRFKeyHashAlreadyRegistered(_, _) => Vector.empty

  /** Conway GOVCERT; the harness names an incorrect refund as an incorrect deposit. */
  private def govCert(g: GovCertFailure): Vector[ReportName] = g match
    case GovCertFailure.DRepAlreadyRegistered(_)          => Vector(DRepAlreadyRegistered)
    case GovCertFailure.DRepNotRegistered(_)              => Vector(DRepNotRegistered)
    case GovCertFailure.DRepIncorrectDeposit(_)           => Vector(IncorrectDepositDRep)
    case GovCertFailure.CommitteeHasPreviouslyResigned(_) => Vector(CommitteeHasPreviouslyResigned)
    case GovCertFailure.DRepIncorrectRefund(_)            => Vector(IncorrectDepositDRep)
    case GovCertFailure.CommitteeIsUnknown(_)             => Vector(CommitteeIsUnknown)

  /** Conway GOV; the harness names the votes of unelected committee members (protocol version 11)
    * as votes of voters that do not exist.
    */
  private def gov(g: GovFailure): Vector[ReportName] = g match
    case GovFailure.GovActionsDoNotExist(_) => Vector(GovActionsDoNotExist)
    case GovFailure.MalformedProposal(_)    => Vector(MalformedProposal)
    case GovFailure.ProposalProcedureNetworkIdMismatch(_, _) =>
      Vector(ProposalProcedureNetworkIdMismatch)
    case GovFailure.TreasuryWithdrawalsNetworkIdMismatch(_, _) => Vector.empty
    case GovFailure.ProposalDepositIncorrect(_)                => Vector(ProposalDepositIncorrect)
    case GovFailure.DisallowedVoters(_)                        => Vector(DisallowedVoters)
    case GovFailure.ConflictingCommitteeUpdate(_)              => Vector(ConflictingCommitteeUpdate)
    case GovFailure.ExpirationEpochTooSmall(_)                 => Vector(ExpirationEpochTooSmall)
    case GovFailure.InvalidPrevGovActionId(_)                  => Vector(InvalidPrevGovActionId)
    case GovFailure.VotingOnExpiredGovAction(_)                => Vector(VotingOnExpiredGovAction)
    case GovFailure.ProposalCantFollow(_, _)                   => Vector(ProposalCantFollow)
    case GovFailure.InvalidGuardrailsScriptHash(_, _)    => Vector(InvalidGuardrailsScriptHash)
    case GovFailure.DisallowedProposalDuringBootstrap(_) => Vector.empty
    case GovFailure.DisallowedVotesDuringBootstrap(_)    => Vector.empty
    case GovFailure.VotersDoNotExist(_)                  => Vector(VotersDoNotExist)
    case GovFailure.ZeroTreasuryWithdrawals(_)           => Vector(TreasuryWithdrawalsAllZeros)
    case GovFailure.ProposalReturnAccountDoesNotExist(_) =>
      Vector(ProposalReturnAccountDoesNotExist)
    case GovFailure.TreasuryWithdrawalReturnAccountsDoNotExist(_) =>
      Vector(TreasuryWithdrawalReturnAccountsDoNotExist)
    case GovFailure.UnelectedCommitteeVoters(_) => Vector(VotersDoNotExist)
