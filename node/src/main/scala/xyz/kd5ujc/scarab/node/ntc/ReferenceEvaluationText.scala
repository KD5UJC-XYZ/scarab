// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.HaskellShow
import xyz.kd5ujc.scarab.plutus.ReferenceDiagnostic

/** Native Show and Pretty for all five EvaluationError families. Plutus 507cab8c:
  * Common/Eval.hs:58-87, Common/SerialisedScript.hs:55-112, Machine/Exception.hs:44-70,124-151,
  * Evaluation/ErrorWithCause.hs:40-62 and Cek/Internal.hs:610-624.
  */
private[ntc] object ReferenceEvaluationText:
  import ReferenceDiagnostic.*
  import ReferenceDocument.{Doc, *}

  private def language(value: LedgerLanguage): String = value match
    case LedgerLanguage.V1 => "PlutusV1"
    case LedgerLanguage.V2 => "PlutusV2"
    case LedgerLanguage.V3 => "PlutusV3"
    case LedgerLanguage.V4 => "PlutusV4"

  private def version(value: Version): String =
    s"${value.major}.${value.minor}.${value.patch}"

  private def versionShow(value: Version): String =
    s"Version {_versionMajor = ${value.major}, _versionMinor = ${value.minor}, _versionPatch = ${value.patch}}"

  private def reasonShow(value: DeserialiseFailureReason): String = value match
    case DeserialiseFailureReason.EndOfInput          => "EndOfInput"
    case DeserialiseFailureReason.ExpectedBytes       => "ExpectedBytes"
    case DeserialiseFailureReason.OtherReason(reason) => "OtherReason " + HaskellShow.text(reason)

  private def decodeShow(value: ScriptDecodeError): String = value match
    case ScriptDecodeError.CborDeserialiseError(offset, reason) =>
      s"CBORDeserialiseError (DeserialiseFailureInfo {dfOffset = $offset, dfReason = ${reasonShow(reason)}})"
    case ScriptDecodeError.Remainder(bytes) => "RemainderError " + HaskellShow.bytes(bytes)
    case ScriptDecodeError.LedgerLanguageNotAvailable(lang, introduced, current) =>
      s"LedgerLanguageNotAvailableError {sdeAffectedLang = ${language(lang)}, sdeIntroPv = $introduced, sdeThisPv = $current}"
    case ScriptDecodeError.PlutusCoreLanguageNotAvailable(v, lang, current) =>
      s"PlutusCoreLanguageNotAvailableError {sdeAffectedVersion = ${versionShow(v)}, sdeThisLang = ${language(lang)}, sdeThisPv = $current}"

  private def freeShow(value: FreeVariableError): String = value match
    case FreeVariableError.FreeUnique(unique) => s"FreeUnique (Unique {unUnique = $unique})"
    case FreeVariableError.FreeIndex(index) =>
      "FreeIndex " + (if index < 0 then s"($index)" else index.toString)

  /** The derived outer Show invokes ErrorWithCause's readable, layoutSmart-based Show only for
    * CekError. The other fields keep their own derived Show; substituting Pretty changes bytes.
    */
  def show(value: EvaluationError): String = value match
    case EvaluationError.Cek(error) =>
      "CekError " + withCause(error, ReferenceUplc.readable).renderWith(Mode.Smart)
    case EvaluationError.DeBruijn(error)            => "DeBruijnError (" + freeShow(error) + ")"
    case EvaluationError.Codec(error)               => "CodecError (" + decodeShow(error) + ")"
    case EvaluationError.CostModelParameterMismatch => "CostModelParameterMismatch"
    case EvaluationError.InvalidReturnValue         => "InvalidReturnValue"

  def pretty(value: EvaluationError): Doc = value match
    case EvaluationError.Cek(error) => withCause(error, ReferenceUplc.classic)
    case EvaluationError.DeBruijn(error) =>
      error match
        case FreeVariableError.FreeUnique(unique) => text(s"Free unique: $unique")
        case FreeVariableError.FreeIndex(index)   => text(s"Free index: $index")
    case EvaluationError.Codec(error) => decodePretty(error)
    case EvaluationError.CostModelParameterMismatch =>
      text("Cost model parameters were not as we expected")
    case EvaluationError.InvalidReturnValue =>
      text(
        "The evaluation finished but the result value is not valid. " +
          "Plutus V3 scripts must return BuiltinUnit. " +
          "Returning any other value is considered a failure."
      )

  def readable(error: ErrorWithCause): Doc = withCause(error, ReferenceUplc.readable)

  private def withCause(error: ErrorWithCause, term: Term => Doc): Doc =
    text("An error has occurred:") ++ Doc.Line ++ cek(error.error) ++
      error.cause.fold[Doc](Doc.Empty)(cause => Doc.Line ++ text("Caused by:").space(term(cause)))

  private def cek(error: CekError): Doc = error match
    case CekError.Structural(cause)  => machine(cause)
    case CekError.Operational(cause) => user(cause)

  private def machine(value: MachineError): Doc = value match
    case MachineError.NonPolymorphicInstantiation =>
      text("Attempted to instantiate a non-polymorphic term.")
    case MachineError.NonWrapUnwrapped         => text("Cannot unwrap a not wrapped term.")
    case MachineError.NonFunctionalApplication => text("Attempted to apply a non-function.")
    case MachineError.OpenTermEvaluated        => text("Cannot evaluate an open term")
    case MachineError.Unlifting(cause) =>
      text("Could not unlift a value:") ++ Doc.Line ++ unlifting(cause)
    case MachineError.BuiltinTermArgumentExpected =>
      text("A builtin expected a term argument, but something else was received")
    case MachineError.UnexpectedBuiltinTermArgument =>
      text("A builtin received a term argument when something else was expected")
    case MachineError.NonConstrScrutinized =>
      text("A non-constructor/non-builtin value was scrutinized in a case expression")
    case MachineError.MissingCaseBranch(index) =>
      text(s"Case expression missing the branch required by the scrutinee tag: $index")
    case MachineError.Panic(nativeException) =>
      join(
        lineBreak,
        Vector(
          text("Panic: a GHC exception was thrown, please report this as a bug."),
          text("The error: ").space(text(nativeException))
        )
      )

  private def unlifting(value: UnliftingError): Doc = value match
    case UnliftingError.NotAConstant => text("Not a constant")
    case UnliftingError.TypeMismatch(expected, actual) =>
      text("Type mismatch: expected: ") ++ ReferenceUplc.universe(expected) ++
        text("; actual: ") ++ ReferenceUplc.universe(actual)
    case UnliftingError.TypeErrorBypassed      => text("Panic: 'TypeError' was bypassed")
    case UnliftingError.NativeMessage(message) => text(message)

  private def user(value: CekUserError): Doc = value match
    case CekUserError.CaseBuiltin(nativeError) =>
      join(
        lineBreak,
        Vector(
          text("'case' over a value of a built-in type failed with"),
          text(caseBuiltin(nativeError))
        )
      )
    case CekUserError.OutOfBudget(remaining) =>
      join(
        lineBreak,
        Vector(
          text(
            "The machine terminated part way through evaluation due to overspending the budget."
          ),
          text("The budget when the machine terminated was:"),
          parens(
            text("{") ++
              vsep(Vector(text(s"cpu: ${remaining.cpu}"), text(s"| mem: ${remaining.memory}"))) ++
              text("}")
          ),
          text(
            "Negative numbers indicate the overspent budget; note that this only indicates the budget that was needed for the next step, not to run the program to completion."
          )
        )
      ).group
    case CekUserError.EvaluationFailure =>
      text(
        "The machine terminated because of an error, either from a built-in function or from an explicit use of 'error'."
      )

  private def caseBuiltin(value: CaseBuiltinError): String = value match
    case CaseBuiltinError.Unavailable(protocol) =>
      s"'case' on values of built-in types is not supported in protocol version $protocol"
    case CaseBuiltinError.OutOfBounds(value, branches) =>
      "'case " + ReferenceUplc.constantArgument(value).renderWith(Mode.Smart) +
        s"' is out of bounds for the given number of branches: $branches"
    case CaseBuiltinError.ExpectedNonEmptyList =>
      "Expected non-empty list, got empty list for casing list"
    case CaseBuiltinError.DataUnsupported    => "Casing on data is not supported"
    case CaseBuiltinError.NonConstructorData => "Casing on data only supports Data.Constr values"
    case CaseBuiltinError.UnsupportedType(universe) =>
      ReferenceUplc.universeArgument(universe).renderWith(Mode.Smart) + " isn't supported in 'case'"
    case CaseBuiltinError.NativeMessage(message) => message

  private def decodePretty(value: ScriptDecodeError): Doc = value match
    case ScriptDecodeError.CborDeserialiseError(offset, reason) =>
      val description = reason match
        case DeserialiseFailureReason.EndOfInput =>
          "reached the end of input while more data was expected."
        case DeserialiseFailureReason.ExpectedBytes => "the bytes inside the input are malformed."
        case DeserialiseFailureReason.OtherReason(nativeReason) => nativeReason
      text(
        s"Failed to deserialise a script: CBOR deserialisation failed at the offset $offset for the following reason: $description"
      )
    case ScriptDecodeError.Remainder(bytes) =>
      text(
        s"Script was successfully deserialised, but ${bytes.size} more bytes were encountered after the script's position."
      )
    case ScriptDecodeError.LedgerLanguageNotAvailable(lang, introduced, current) =>
      text(
        s"Your script has a Plutus Ledger Language version of ${language(lang)}. This is not yet supported by the current major protocol version $current. The major protocol version that introduces this Plutus Ledger Language is $introduced."
      )
    case ScriptDecodeError.PlutusCoreLanguageNotAvailable(v, lang, current) =>
      text(
        s"Your script has a Plutus Core version of ${version(v)}. This is not supported in ${language(lang)} and major protocol version $current."
      )
