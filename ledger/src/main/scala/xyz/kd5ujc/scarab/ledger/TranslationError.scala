// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes

/** A piece of data the translation to a script context reads from the transaction and cannot
  * decode. The reference ledger holds all of these decoded already (the transaction decoder refuses
  * them), so none of them is a ledger failure of its own there; the node keeps the raw bytes of
  * datums and redeemers until a script needs them.
  */
enum DataSite:
  case Datum
  case InlineDatum
  case Redeemer

  /** A field of a parameter update, as the `ChangedParameters` of a V3 context carries it. */
  case ParameterUpdate

/** The output named by a context translation error (`Plutus/TxInfo.hs:90-103`). */
enum TxOutSource:
  case TxOutFromInput(input: TxIn)
  case TxOutFromOutput(index: Int)

/** A context translation failure, carried by [[CollectError.BadTranslation]]. The reference
  * `ContextError` belongs to its era ( `Alonzo/Plutus/TxInfo.hs:213-216`,
  * `Babbage/TxInfo.hs:253-260`, `Conway/TxInfo.hs:180-189`). It is a phase-1 failure.
  *
  * The cases the reference has no constructor for (it holds the data decoded and the cost models
  * complete) are the node's own: [[Undecodable]], [[NoScriptBytes]],
  * [[CostModelParameterTooLarge]], [[NoEraHistory]], [[NoClockSample]] (a recorded clock without
  * the slot; the translation past the horizon is [[PastHorizon]]) and [[NativeScriptRedeemer]].
  */
enum TranslationError:
  /** A redeemer names no purpose of the transaction (`RedeemerPointerPointsToNothing`). */
  case RedeemerPointerPointsToNothing(tag: Int, index: BigInt)

  /** An input of the transaction is not in the set the context is built from. */
  case UnresolvedInput(input: TxIn)

  /** A Plutus V1 context cannot show an inline datum (in any era, in a reference input too). */
  case InlineDatumsNotSupported(source: TxOutSource)

  /** A Plutus V1 context cannot show a reference script (Babbage; Conway leaves the field out). */
  case ReferenceScriptsNotSupported(source: TxOutSource)

  /** A Plutus V1 context cannot show a reference input (Babbage). */
  case ReferenceInputsNotSupported(inputs: Set[TxIn])

  /** A reference input is also an input (Plutus V3 from protocol version 11). */
  case ReferenceInputsNotDisjointFromInputs(inputs: cats.data.NonEmptyVector[TxIn])

  /** A Byron address in an output of a context built from protocol version 7 on. */
  case ByronTxOutInContext(source: TxOutSource)

  /** The language's context has no form for the certificate (`CertificateNotSupported`). */
  case CertificateNotSupported(certificate: Certificate)

  /** The language's context has no form for the purpose (`PlutusPurposeNotSupported`). */
  case PlutusPurposeNotSupported(purpose: Purpose)

  /** A delegation certificate that names neither a pool nor a DRep. */
  case DelegationWithoutDelegatee

  /** A Plutus V1 or V2 context cannot carry the governance fields of a Conway body. */
  case VotingProceduresFieldNotSupported(
      procedures: Vector[(Voter, Vector[(GovActionId, VotingProcedure)])]
  )
  case ProposalProceduresFieldNotSupported(procedures: Vector[Proposal])
  case TreasuryDonationFieldNotSupported(coin: BigInt)
  case CurrentTreasuryFieldNotSupported(coin: BigInt)

  /** Data of the transaction that does not decode as the context needs it. */
  case Undecodable(site: DataSite)

  /** The script has no bytes (a native script, or a reference script that is not there). */
  case NoScriptBytes(script: Bytes)

  /** A redeemer names a native script. */
  case NativeScriptRedeemer

  /** A cost model parameter does not fit 64 bits. */
  case CostModelParameterTooLarge

  /** The evaluation has no era history to turn slots into POSIX time with. */
  case NoEraHistory

  /** The clock the context was built with does not know the slot (`TimeTranslationPastHorizon`). */
  case NoClockSample(slot: BigInt)

  /** The slot is past the forecast horizon of the era history at the ledger tip
    * (`TimeTranslationPastHorizon`, `Alonzo/Plutus/TxInfo.hs` `transValidityInterval`): the horizon
    * is kept for the text the reference's refusal carries ([[Horizon.pastHorizonText]]).
    */
  case PastHorizon(slot: BigInt, horizon: Horizon)

  /** The text of the failure for the log, derived from the case. */
  def message: String = this match
    case RedeemerPointerPointsToNothing(tag, index) => s"redeemer ($tag, $index) names no purpose"
    case UnresolvedInput(input)                     => s"unresolved input ${input.render}"
    case InlineDatumsNotSupported(source) =>
      s"inline datums are not supported in Plutus V1: $source"
    case ReferenceScriptsNotSupported(source) =>
      s"reference scripts are not supported in Plutus V1: $source"
    case ReferenceInputsNotSupported(inputs) =>
      s"reference inputs are not supported in Plutus V1: ${inputs.toVector.sorted.map(_.render).mkString(", ")}"
    case ReferenceInputsNotDisjointFromInputs(inputs) =>
      s"reference inputs are also inputs: ${inputs.toVector.map(_.render).mkString(", ")}"
    case ByronTxOutInContext(source)  => s"Byron address in a Plutus script context: $source"
    case CertificateNotSupported(c)   => s"certificate $c in this language's script context"
    case PlutusPurposeNotSupported(p) => s"purpose $p in this language's script context"
    case DelegationWithoutDelegatee   => "delegation without a delegatee"
    case VotingProceduresFieldNotSupported(_) =>
      "voting procedures in a Plutus V1 or V2 script context"
    case ProposalProceduresFieldNotSupported(_) =>
      "proposal procedures in a Plutus V1 or V2 script context"
    case TreasuryDonationFieldNotSupported(coin) =>
      s"treasury donation $coin in a Plutus V1 or V2 script context"
    case CurrentTreasuryFieldNotSupported(coin) =>
      s"current treasury $coin in a Plutus V1 or V2 script context"
    case Undecodable(site)          => s"the ${site.toString.toLowerCase} does not decode"
    case NoScriptBytes(script)      => s"script ${script.hex} has no bytes"
    case NativeScriptRedeemer       => "a redeemer names a native script"
    case CostModelParameterTooLarge => "cost model parameter exceeds 64 bits"
    case NoEraHistory               => "no era history for slot times"
    case NoClockSample(slot)        => s"no clock sample for slot $slot"
    case PastHorizon(slot, _)       => s"slot $slot is past the forecast horizon"
