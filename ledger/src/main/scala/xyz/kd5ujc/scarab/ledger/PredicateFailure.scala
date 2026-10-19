// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.plutus.{EvalFailure, ExUnits}
import xyz.kd5ujc.scarab.codec.Bytes

/** A pair of values a rule compared (`Mismatch r a` in the ledger): what the transaction supplied
  * and what the rule expected.
  */
final case class Mismatch[A](supplied: A, expected: A)

/** The period an update proposal is made for (`VotingPeriod`, `Shelley/Rules/Ppup.hs:67`). */
enum VotingPeriod:
  case VoteForThisEpoch
  case VoteForNextEpoch

/** PPUP failures (`ShelleyPpupPredFailure`, `Shelley/Rules/Ppup.hs:85-107`): the update proposals
  * of the eras before Conway, which have no PPUP rule.
  */
enum PpupFailure:
  /** An update proposed by a key hash that is not a genesis key (`NonGenesisUpdatePPUP`): the
    * proposers are the supplied side, the genesis keys the expected.
    */
  case NonGenesisUpdate(mismatch: Mismatch[Set[Bytes]])

  /** An update for the wrong epoch (`PPUpdateWrongEpoch`): the current epoch, the epoch of the
    * update and the period it was meant for.
    */
  case PPUpdateWrongEpoch(current: BigInt, proposed: BigInt, period: VotingPeriod)

  /** A protocol version that does not follow the one in force (`PVCannotFollowPPUP`). */
  case PVCannotFollow(major: BigInt, minor: BigInt)

/** The evaluator's cause, retained until node-to-client serialization. The reference's
  * `FailureDescription.PlutusFailure` holds text and reconstruction bytes (`cardano-ledger`
  * f649f975, `Alonzo/Rules/Utxos.hs:259-290`); keeping the cause here preserves its cases and
  * nested exceptions instead of discarding them into the wire presentation.
  */
final case class PlutusFailure(cause: EvalFailure, reconstructionDetail: Bytes)

/** Why UTXOS refused a transaction. */
enum UtxosFailure:
  /** The `is_valid` tag of the transaction disagrees with the evaluation of its scripts. `isValid`
    * is the tag the transaction carries; `failures` are the Plutus errors when the scripts failed
    * though the tag says they pass (none when the scripts passed though the tag says they fail).
    */
  case ValidationTagMismatch(isValid: Boolean, failures: Option[Vector[PlutusFailure]])

  /** The evaluation of a script could not be set up (`CollectErrors`,
    * `Alonzo/Rules/Utxos.hs:94-100`); a phase-1 failure.
    */
  case CollectErrors(errors: cats.data.NonEmptyVector[CollectError])

/** UTXO failures, named and numbered as `ConwayUtxoPredFailure` has them; the cases of the eras
  * before Conway are those of `ShelleyUtxoPredFailure` (`Shelley/Rules/Utxo.hs:166-189`),
  * `AllegraUtxoPredFailure`, `AlonzoUtxoPredFailure` and `BabbageUtxoPredFailure` that Conway has
  * no constructor for.
  */
enum UtxoFailure:
  case Utxos(failure: UtxosFailure)

  /** The time to live has passed (`ExpiredUTxO`, Shelley only): the time to live is the supplied
    * side, the slot the expected (`validateTimeToLive`, `Shelley/Rules/Utxo.hs:414-423`).
    */
  case ExpiredUTxO(mismatch: Mismatch[BigInt])

  /** The update proposals of the transaction were refused (`UpdateFailure`): PPUP, under UTXO in
    * Shelley, Allegra and Mary, under UTXOS from Alonzo.
    */
  case Update(failure: PpupFailure)
  case BadInputs(inputs: Set[TxIn])
  case OutsideValidityInterval(notBefore: Option[BigInt], before: Option[BigInt], slot: BigInt)
  case MaxTxSize(mismatch: Mismatch[BigInt])
  case InputSetEmpty
  case FeeTooSmall(mismatch: Mismatch[BigInt])
  case ValueNotConserved(mismatch: Mismatch[Value])
  case WrongNetwork(expected: Int, addresses: Set[Bytes])
  case WrongNetworkWithdrawal(expected: Int, accounts: Set[RewardAccount])
  case OutputTooSmall(outputs: Vector[TxOut])
  case OutputBootAddrAttrsTooBig(outputs: Vector[TxOut])
  case OutputTooBig(outputs: Vector[(Int, Int, TxOut)])
  case InsufficientCollateral(balance: BigInt, required: BigInt)
  case ScriptsNotPaid(outputs: Map[TxIn, TxOut])
  case ExUnitsTooBig(mismatch: Mismatch[ExUnits])
  case CollateralContainsNonAda(value: Value)
  case WrongNetworkInTxBody(mismatch: Mismatch[Int])
  case OutsideForecast(slot: BigInt)
  case TooManyCollateralInputs(mismatch: Mismatch[Int])
  case NoCollateralInputs
  case IncorrectTotalCollateralField(provided: BigInt, declared: BigInt)
  case BabbageOutputTooSmall(outputs: Vector[(TxOut, BigInt)])
  case BabbageNonDisjointRefInputs(inputs: Vector[TxIn])

/** UTXOW failures (`ConwayUtxowPredFailure`). */
enum UtxowFailure:
  case Utxo(failure: UtxoFailure)
  case InvalidWitnesses(keys: Vector[Bytes])
  case MissingVKeyWitnesses(hashes: Set[Bytes])
  case MissingScriptWitnesses(hashes: Set[Bytes])
  case ScriptWitnessNotValidating(hashes: Set[Bytes])
  case MissingTxBodyMetadataHash(hash: Bytes)
  case MissingTxMetadata(hash: Bytes)
  case ConflictingMetadataHash(mismatch: Mismatch[Bytes])
  case InvalidMetadata
  case ExtraneousScriptWitnesses(hashes: Set[Bytes])
  case MissingRequiredDatums(missing: Set[Bytes], received: Set[Bytes])
  case NotAllowedSupplementalDatums(unallowed: Set[Bytes], acceptable: Set[Bytes])
  case PPViewHashesDontMatch(mismatch: Mismatch[Option[Bytes]])
  case UnspendableUTxONoDatumHash(inputs: Set[TxIn])
  case MalformedScriptWitnesses(hashes: Set[Bytes])
  case MalformedReferenceScripts(hashes: Set[Bytes])

  /** A MIR certificate without the quorum of the genesis delegates' signatures
    * (`MIRInsufficientGenesisSigsUTXOW`, `Shelley/Rules/Utxow.hs:121`): the genesis delegates that
    * did sign.
    */
  case MIRInsufficientGenesisSigs(signers: Set[Bytes])

  /** The Plutus purposes that have no redeemer, with the hash of the script each needs. */
  case MissingRedeemers(purposes: Vector[(Purpose, Bytes)])

  /** The redeemers a transaction carries too many of, as `(tag, index)`. */
  case ExtraRedeemers(purposes: Vector[(Int, BigInt)])

/** Pool registration and retirement failures (`ShelleyPoolPredFailure`). */
enum PoolFailure:
  case StakePoolNotRegisteredOnKey(pool: Bytes)
  case StakePoolRetirementWrongEpoch(
      current: Mismatch[BigInt],
      latest: Mismatch[BigInt]
  )
  case StakePoolCostTooLow(mismatch: Mismatch[BigInt])
  case WrongNetworkPool(mismatch: Mismatch[Int], pool: Bytes)
  case VRFKeyHashAlreadyRegistered(pool: Bytes, vrf: Bytes)

/** Stake credential failures (`ConwayDelegPredFailure`); the cases of the eras before Conway are
  * those of `ShelleyDelegPredFailure` (`Shelley/Rules/Deleg.hs:89-120`) that Conway has no
  * constructor for.
  */
enum DelegFailure:
  case IncorrectDeposit(coin: BigInt)
  case StakeKeyRegistered(credential: Credential)
  case StakeKeyNotRegistered(credential: Credential)
  case StakeKeyHasNonZeroAccountBalance(coin: BigInt)
  case DelegateeDRepNotRegistered(credential: Credential)
  case DelegateeStakePoolNotRegistered(pool: Bytes)
  case DepositIncorrect(mismatch: Mismatch[BigInt])
  case RefundIncorrect(mismatch: Mismatch[BigInt])

  /** The credential is registered already (`StakeKeyAlreadyRegisteredDELEG`). */
  case StakeKeyAlreadyRegistered(credential: Credential)

  /** The credential to delegate is not registered (`StakeDelegationImpossibleDELEG`). */
  case StakeDelegationImpossible(credential: Credential)

  /** A pool certificate reached DELEG (`WrongCertificateTypeDELEG`). */
  case WrongCertificateType

  /** The genesis key is not one of the genesis delegations (`GenesisKeyNotInMappingDELEG`). */
  case GenesisKeyNotInMapping(genesisKey: Bytes)

  /** Another genesis key, current or scheduled, has the delegate (`DuplicateGenesisDelegateDELEG`).
    */
  case DuplicateGenesisDelegate(delegate: Bytes)

  /** Another genesis key, current or scheduled, has the VRF key (`DuplicateGenesisVRFDELEG`). */
  case DuplicateGenesisVRF(vrf: Bytes)

  /** A MIR certificate pays out more than the pot has: the amount the certificate needs is the
    * supplied side, the amount available the expected (`InsufficientForInstantaneousRewardsDELEG`).
    */
  case InsufficientForInstantaneousRewards(pot: Pot, mismatch: Mismatch[BigInt])

  /** A MIR certificate arrives too late in the epoch: the slot is the supplied side, the first slot
    * too late the expected (`MIRCertificateTooLateinEpochDELEG`, `checkSlotNotTooLate`,
    * `Shelley/Rules/Deleg.hs:386`).
    */
  case MIRCertificateTooLateinEpoch(mismatch: Mismatch[BigInt])

  /** A MIR transfer between the pots before protocol version 5 (`MIRTransferNotCurrentlyAllowed`).
    */
  case MIRTransferNotCurrentlyAllowed

  /** A MIR certificate with a negative amount before protocol version 5. */
  case MIRNegativesNotCurrentlyAllowed

  /** A MIR transfer of more than the pot has left (`InsufficientForTransferDELEG`). */
  case InsufficientForTransfer(pot: Pot, mismatch: Mismatch[BigInt])

  /** A MIR certificate that leaves a negative amount for a credential. */
  case MIRProducesNegativeUpdate

  /** A MIR transfer of a negative amount (`MIRNegativeTransfer`). */
  case MIRNegativeTransfer(pot: Pot, amount: BigInt)

  /** The pool to delegate to is not registered (`DelegateeNotRegisteredDELEG`). */
  case DelegateeNotRegistered(pool: Bytes)

/** DRep and committee certificate failures (`ConwayGovCertPredFailure`). */
enum GovCertFailure:
  case DRepAlreadyRegistered(credential: Credential)
  case DRepNotRegistered(credential: Credential)
  case DRepIncorrectDeposit(mismatch: Mismatch[BigInt])
  case CommitteeHasPreviouslyResigned(credential: Credential)
  case DRepIncorrectRefund(mismatch: Mismatch[BigInt])
  case CommitteeIsUnknown(credential: Credential)

/** CERT failures: the three certificate families. */
enum CertFailure:
  case Deleg(failure: DelegFailure)
  case Pool(failure: PoolFailure)
  case GovCert(failure: GovCertFailure)

/** CERTS failures. */
enum CertsFailure:
  case WithdrawalsNotInRewards(withdrawals: Map[RewardAccount, BigInt])
  case Cert(failure: CertFailure)

/** GOV failures (`ConwayGovPredFailure`). */
enum GovFailure:
  case GovActionsDoNotExist(ids: Vector[GovActionId])
  case MalformedProposal(action: GovAction)
  case ProposalProcedureNetworkIdMismatch(account: RewardAccount, network: Int)
  case TreasuryWithdrawalsNetworkIdMismatch(accounts: Set[RewardAccount], network: Int)
  case ProposalDepositIncorrect(mismatch: Mismatch[BigInt])
  case DisallowedVoters(votes: Vector[(Voter, GovActionId)])
  case ConflictingCommitteeUpdate(members: Set[Credential])
  case ExpirationEpochTooSmall(members: Map[Credential, BigInt])
  case InvalidPrevGovActionId(proposal: Proposal)
  case VotingOnExpiredGovAction(votes: Vector[(Voter, GovActionId)])
  case ProposalCantFollow(
      previous: Option[GovActionId],
      mismatch: Mismatch[(BigInt, BigInt)]
  )
  case InvalidGuardrailsScriptHash(supplied: Option[Bytes], expected: Option[Bytes])
  case DisallowedProposalDuringBootstrap(proposal: Proposal)
  case DisallowedVotesDuringBootstrap(votes: Vector[(Voter, GovActionId)])
  case VotersDoNotExist(voters: Vector[Voter])
  case ZeroTreasuryWithdrawals(action: GovAction)
  case ProposalReturnAccountDoesNotExist(account: RewardAccount)
  case TreasuryWithdrawalReturnAccountsDoNotExist(accounts: Vector[RewardAccount])
  case UnelectedCommitteeVoters(credentials: Vector[Credential])

/** The Conway LEDGER predicate failures (`ConwayLedgerPredFailure`): what a transaction can be
  * refused for, as a closed set whose constructors carry the payload the ledger reports. The
  * node-to-client encoding (`PredicateFailureCbor`) writes exactly these shapes, so that
  * `cardano-cli transaction submit` renders them.
  *
  * `Mempool` is the ledger's own free-text failure (`ConwayMempoolFailure`), which the node sends
  * for what the ledger does not report as a predicate failure (a full mempool).
  */
enum PredicateFailure:
  case Utxow(failure: UtxowFailure)
  case Certs(failure: CertsFailure)
  case Gov(failure: GovFailure)
  case WdrlNotDelegatedToDRep(credentials: Vector[Bytes])
  case TreasuryValueMismatch(mismatch: Mismatch[BigInt])
  case TxRefScriptsSizeTooBig(mismatch: Mismatch[Int])
  case Mempool(cause: MempoolProblem)
  case WithdrawalsMissingAccounts(withdrawals: Map[RewardAccount, BigInt])
  case IncompleteWithdrawals(mismatches: Map[RewardAccount, Mismatch[BigInt]])

  /** The name of the innermost rule that failed (the ledger's constructor, with the suffixes this
    * node has always used).
    */
  def ruleName: String = PredicateFailure.nameOf(this)

  /** The failure as text: the rule's name and its payload, for the log and for the clients that
    * only show text. Derived from the case, never an input.
    */
  def detail: String = this match
    case Utxow(UtxowFailure.Utxo(UtxoFailure.Utxos(UtxosFailure.CollectErrors(es)))) =>
      s"$ruleName: ${es.toVector.map(_.message).mkString("; ")}"
    case other => s"$ruleName: ${other.toString.take(400)}"

object PredicateFailure:
  def utxo(f: UtxoFailure): PredicateFailure = Utxow(UtxowFailure.Utxo(f))
  def utxow(f: UtxowFailure): PredicateFailure = Utxow(f)
  def pool(f: PoolFailure): PredicateFailure = Certs(CertsFailure.Cert(CertFailure.Pool(f)))
  def deleg(f: DelegFailure): PredicateFailure = Certs(CertsFailure.Cert(CertFailure.Deleg(f)))
  def govCert(f: GovCertFailure): PredicateFailure =
    Certs(CertsFailure.Cert(CertFailure.GovCert(f)))
  def gov(f: GovFailure): PredicateFailure = Gov(f)

  private def nameOf(f: PredicateFailure): String = f match
    case Utxow(u)                                         => utxowName(u)
    case Certs(CertsFailure.WithdrawalsNotInRewards(_))   => "WithdrawalsNotInRewardsCERTS"
    case Certs(CertsFailure.Cert(CertFailure.Deleg(d)))   => delegName(d)
    case Certs(CertsFailure.Cert(CertFailure.Pool(p)))    => poolName(p)
    case Certs(CertsFailure.Cert(CertFailure.GovCert(g))) => govCertName(g)
    case Gov(g)                                           => g.productPrefix
    case WdrlNotDelegatedToDRep(_)                        => "ConwayWdrlNotDelegatedToDRep"
    case TreasuryValueMismatch(_)                         => "ConwayTreasuryValueMismatch"
    case TxRefScriptsSizeTooBig(_)                        => "ConwayTxRefScriptsSizeTooBig"
    case Mempool(_)                                       => "ConwayMempoolFailure"
    case WithdrawalsMissingAccounts(_)                    => "ConwayWithdrawalsMissingAccounts"
    case IncompleteWithdrawals(_)                         => "ConwayIncompleteWithdrawals"

  private def utxowName(u: UtxowFailure): String = u match
    case UtxowFailure.Utxo(f)                       => utxoName(f)
    case UtxowFailure.InvalidWitnesses(_)           => "InvalidWitnessesUTXOW"
    case UtxowFailure.MissingVKeyWitnesses(_)       => "MissingVKeyWitnessesUTXOW"
    case UtxowFailure.MissingScriptWitnesses(_)     => "MissingScriptWitnessesUTXOW"
    case UtxowFailure.ScriptWitnessNotValidating(_) => "ScriptWitnessNotValidatingUTXOW"
    case UtxowFailure.ExtraneousScriptWitnesses(_)  => "ExtraneousScriptWitnessesUTXOW"
    case UtxowFailure.MIRInsufficientGenesisSigs(_) => "MIRInsufficientGenesisSigsUTXOW"
    case other                                      => other.productPrefix

  private def utxoName(u: UtxoFailure): String = u match
    case UtxoFailure.Utxos(UtxosFailure.ValidationTagMismatch(_, _)) => "ValidationTagMismatch"
    case UtxoFailure.Utxos(UtxosFailure.CollectErrors(_))            => "CollectErrors"
    case UtxoFailure.Update(p)                                       => ppupName(p)
    case UtxoFailure.BadInputs(_)                                    => "BadInputsUTxO"
    case UtxoFailure.OutsideValidityInterval(_, _, _) => "OutsideValidityIntervalUTxO"
    case UtxoFailure.MaxTxSize(_)                     => "MaxTxSizeUTxO"
    case UtxoFailure.InputSetEmpty                    => "InputSetEmptyUTxO"
    case UtxoFailure.FeeTooSmall(_)                   => "FeeTooSmallUTxO"
    case UtxoFailure.ValueNotConserved(_)             => "ValueNotConservedUTxO"
    case UtxoFailure.OutputTooSmall(_) | UtxoFailure.BabbageOutputTooSmall(_) =>
      "OutputTooSmallUTxO"
    case UtxoFailure.OutputTooBig(_)              => "OutputTooBigUTxO"
    case UtxoFailure.ExUnitsTooBig(_)             => "ExUnitsTooBigUTxO"
    case UtxoFailure.WrongNetworkWithdrawal(_, _) => "WrongNetworkWithdrawal"
    case UtxoFailure.ScriptsNotPaid(_)            => "ScriptsNotPaidUTxO"
    case UtxoFailure.CollateralContainsNonAda(_)  => "CollateralContainsNonADA"
    case other                                    => other.productPrefix

  private def ppupName(p: PpupFailure): String = p match
    case PpupFailure.NonGenesisUpdate(_)         => "NonGenesisUpdatePPUP"
    case PpupFailure.PPUpdateWrongEpoch(_, _, _) => "PPUpdateWrongEpoch"
    case PpupFailure.PVCannotFollow(_, _)        => "PVCannotFollowPPUP"

  private def poolName(p: PoolFailure): String = p match
    case PoolFailure.StakePoolNotRegisteredOnKey(_)      => "StakePoolNotRegisteredOnKeyPOOL"
    case PoolFailure.StakePoolRetirementWrongEpoch(_, _) => "StakePoolRetirementWrongEpochPOOL"
    case PoolFailure.StakePoolCostTooLow(_)              => "StakePoolCostTooLowPOOL"
    case PoolFailure.WrongNetworkPool(_, _)              => "WrongNetworkPOOL"
    case PoolFailure.VRFKeyHashAlreadyRegistered(_, _)   => "VRFKeyHashAlreadyRegistered"

  private def delegName(d: DelegFailure): String = d match
    case DelegFailure.IncorrectDeposit(_)                 => "IncorrectDepositDELEG"
    case DelegFailure.StakeKeyRegistered(_)               => "StakeKeyRegistered"
    case DelegFailure.StakeKeyNotRegistered(_)            => "StakeKeyNotRegisteredDELEG"
    case DelegFailure.StakeKeyHasNonZeroAccountBalance(_) => "StakeKeyNonZeroAccountBalanceDELEG"
    case DelegFailure.DelegateeDRepNotRegistered(_)       => "DelegateeDRepNotRegisteredDELEG"
    case DelegFailure.DelegateeStakePoolNotRegistered(_)  => "DelegateeStakePoolNotRegisteredDELEG"
    case DelegFailure.DepositIncorrect(_)                 => "DepositIncorrectDELEG"
    case DelegFailure.RefundIncorrect(_)                  => "RefundIncorrectDELEG"
    case DelegFailure.StakeKeyAlreadyRegistered(_)        => "StakeKeyAlreadyRegisteredDELEG"
    case DelegFailure.StakeDelegationImpossible(_)        => "StakeDelegationImpossibleDELEG"
    case DelegFailure.WrongCertificateType                => "WrongCertificateTypeDELEG"
    case DelegFailure.GenesisKeyNotInMapping(_)           => "GenesisKeyNotInMappingDELEG"
    case DelegFailure.DuplicateGenesisDelegate(_)         => "DuplicateGenesisDelegateDELEG"
    case DelegFailure.DuplicateGenesisVRF(_)              => "DuplicateGenesisVRFDELEG"
    case DelegFailure.InsufficientForInstantaneousRewards(_, _) =>
      "InsufficientForInstantaneousRewardsDELEG"
    case DelegFailure.MIRCertificateTooLateinEpoch(_) => "MIRCertificateTooLateinEpochDELEG"
    case DelegFailure.MIRTransferNotCurrentlyAllowed  => "MIRTransferNotCurrentlyAllowed"
    case DelegFailure.MIRNegativesNotCurrentlyAllowed => "MIRNegativesNotCurrentlyAllowed"
    case DelegFailure.InsufficientForTransfer(_, _)   => "InsufficientForTransferDELEG"
    case DelegFailure.MIRProducesNegativeUpdate       => "MIRProducesNegativeUpdate"
    case DelegFailure.MIRNegativeTransfer(_, _)       => "MIRNegativeTransfer"
    case DelegFailure.DelegateeNotRegistered(_)       => "DelegateeNotRegisteredDELEG"

  private def govCertName(g: GovCertFailure): String = g match
    case GovCertFailure.DRepAlreadyRegistered(_)          => "ConwayDRepAlreadyRegistered"
    case GovCertFailure.DRepNotRegistered(_)              => "ConwayDRepNotRegistered"
    case GovCertFailure.DRepIncorrectDeposit(_)           => "ConwayDRepIncorrectDeposit"
    case GovCertFailure.CommitteeHasPreviouslyResigned(_) => "ConwayCommitteeHasPreviouslyResigned"
    case GovCertFailure.DRepIncorrectRefund(_)            => "ConwayDRepIncorrectRefund"
    case GovCertFailure.CommitteeIsUnknown(_)             => "ConwayCommitteeIsUnknown"
