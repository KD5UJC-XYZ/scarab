// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.PlutusLanguage

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

/** Why the ledger refused to set up the evaluation of a script: `CollectError` (`NoRedeemer`,
  * `NoWitness`, `NoCostModel`, `BadTranslation`; `Alonzo/Plutus/Context.hs:357-362`) with the
  * context errors of the eras (`ContextError`: `Alonzo/Plutus/TxInfo.hs:213-216`,
  * `Babbage/TxInfo.hs:253-260`, `Conway/TxInfo.hs:180-189`). A phase-1 failure in the reference.
  *
  * The cases the reference has no constructor for (it holds the data decoded and the cost models
  * complete) are the node's own: [[Undecodable]], [[NoScriptBytes]],
  * [[CostModelParameterTooLarge]], [[NoEraHistory]], [[NoClockSample]], [[NativeScriptRedeemer]],
  * [[SpentOutputHasNoDatum]] and [[DatumNotInWitnesses]].
  */
enum TranslationError:
  /** A redeemer names no purpose of the transaction (`RedeemerPointerPointsToNothing`). */
  case NoRedeemer(tag: Int, index: BigInt)

  /** The script a redeemer needs is not among the witnesses or the reference scripts. */
  case NoWitness(script: Bytes)

  /** The parameters hold no cost model for the language. */
  case NoCostModel(language: PlutusLanguage)

  /** An input of the transaction is not in the set the context is built from. */
  case UnresolvedInput(input: TxIn)

  /** A Plutus V1 context cannot show an inline datum. */
  case InlineDatumsNotSupported

  /** A Plutus V1 context cannot show a reference script. */
  case ReferenceScriptsNotSupported

  /** A Plutus V1 context cannot show a reference input (Babbage). */
  case ReferenceInputsNotSupported

  /** A reference input is also an input (Plutus V3 from protocol version 11). */
  case ReferenceInputsNotDisjointFromInputs

  /** A Byron address in an output of a context built from protocol version 7 on. */
  case ByronTxOutInContext

  /** The language's context has no form for the certificate (`CertificateNotSupported`). */
  case CertificateNotSupported(certificate: Certificate)

  /** The language's context has no form for the purpose (`PlutusPurposeNotSupported`). */
  case PlutusPurposeNotSupported(purpose: Purpose)

  /** A delegation certificate that names neither a pool nor a DRep. */
  case DelegationWithoutDelegatee

  /** A Plutus V1 or V2 context cannot carry the governance fields of a Conway body. */
  case GovernanceFieldsNotSupported

  /** The datum of a spent output is not in the witnesses (`MissingRequiredDatums` is checked
    * earlier in UTXOW; the context is built from what the witnesses hold).
    */
  case DatumNotInWitnesses(hash: Bytes)

  /** A spent output with a datum hash carries none the script could be given. */
  case SpentOutputHasNoDatum

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

  /** The text of the failure for the log, derived from the case. */
  def message: String = this match
    case NoRedeemer(tag, index)               => s"redeemer ($tag, $index) names no purpose"
    case NoWitness(script)                    => s"script ${script.hex} is missing"
    case NoCostModel(language)                => s"no cost model for $language"
    case UnresolvedInput(input)               => s"unresolved input ${input.render}"
    case InlineDatumsNotSupported             => "inline datums are not supported in Plutus V1"
    case ReferenceScriptsNotSupported         => "reference scripts are not supported in Plutus V1"
    case ReferenceInputsNotSupported          => "reference inputs are not supported in Plutus V1"
    case ReferenceInputsNotDisjointFromInputs => "a reference input is also an input"
    case ByronTxOutInContext                  => "Byron address in a Plutus script context"
    case CertificateNotSupported(c)           => s"certificate $c in this language's script context"
    case PlutusPurposeNotSupported(p)         => s"purpose $p in this language's script context"
    case DelegationWithoutDelegatee           => "delegation without a delegatee"
    case GovernanceFieldsNotSupported =>
      "governance fields in a Plutus V1 or V2 script context"
    case DatumNotInWitnesses(h)     => s"datum ${h.hex} is not in the witnesses"
    case SpentOutputHasNoDatum      => "spent output has no datum"
    case Undecodable(site)          => s"the ${site.toString.toLowerCase} does not decode"
    case NoScriptBytes(script)      => s"script ${script.hex} has no bytes"
    case NativeScriptRedeemer       => "a redeemer names a native script"
    case CostModelParameterTooLarge => "cost model parameter exceeds 64 bits"
    case NoEraHistory               => "no era history for slot times"
    case NoClockSample(slot)        => s"no clock sample for slot $slot"
