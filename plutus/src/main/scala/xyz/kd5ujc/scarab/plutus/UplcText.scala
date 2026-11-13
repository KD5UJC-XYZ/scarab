// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.uplc.builtin.BuiltinFailure
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.{EvaluationError, FreeVariableError, Unavailability}

/** The text of the own evaluator's typed failures, for logs and for the text of a refusal on the
  * wire: the names of their cases and the small numbers that place them, never a constant, a type
  * or a term the script supplies, which can be as large as the transaction that carries it. The
  * typed values stay in the failures for whoever needs them.
  */
private[plutus] object UplcText:
  def evaluation(error: EvaluationError): String = error match
    case EvaluationError.Cek(CekFailure(cek, _)) => s"CekError ${machine(cek)}"
    case EvaluationError.DeBruijn(FreeVariableError.FreeIndex(index)) =>
      s"DeBruijnError FreeIndex ${index.toBigInt}"
    case EvaluationError.Codec(refusal)     => s"CodecError ${refusal.productPrefix}"
    case EvaluationError.InvalidReturnValue => "InvalidReturnValue"

  def machine(error: CekError): String = error match
    case CekError.Builtin(function, BuiltinFailure.Unlifting(failure)) =>
      s"Builtin $function Unlifting ${failure.cause.productPrefix}"
    case CekError.Builtin(function, BuiltinFailure.Evaluation(failure)) =>
      s"Builtin $function ${failure.productPrefix}"
    case CekError.CaseBuiltin(failure)   => s"CaseBuiltin ${failure.productPrefix}"
    case CekError.MissingCaseBranch(tag) => s"MissingCaseBranch ${tag.toBigInt}"
    case other                           => other.productPrefix

  def decode(error: ScriptDecodeError): String = error match
    case ScriptDecodeError.Flat(failure)        => s"Flat ${failure.productPrefix}"
    case ScriptDecodeError.Remainder(remainder) => s"Remainder of ${remainder.size} bytes"
    case other                                  => other.productPrefix

  def unavailable(reason: Unavailability): String = reason match
    case Unavailability.UnsupportedProtocol(protocol) =>
      s"UnsupportedProtocol ${protocol.value}"
    case Unavailability.BuiltinDefect(function, defect) =>
      s"BuiltinDefect $function ${defect.productPrefix}"
    case Unavailability.BuiltinArity(function, expected, actual) =>
      s"BuiltinArity $function $expected $actual"
    case Unavailability.Thrown(cause) => s"Thrown ${cause.getClass.getName}"
    case other                        => other.productPrefix
