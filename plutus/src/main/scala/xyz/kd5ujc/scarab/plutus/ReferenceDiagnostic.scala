// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.uplc.DefaultFun
import xyz.kd5ujc.scarab.codec.Bytes

/** Lossless inputs to the pinned native diagnostic printers. Values remain structured until the
  * presentation boundary; provider exceptions are retained separately by the evaluation result.
  * Plutus 507cab8c: Common/Eval.hs:58-73, Machine/Exception.hs:44-70, Cek/Internal.hs:497-506,
  * Core/Type.hs and Default/Universe.hs:136-155. Indices, constructor tags and budgets are
  * unbounded here so presentation cannot lose information at a JVM or provider numeric boundary.
  */
object ReferenceDiagnostic:
  final case class Budget(cpu: BigInt, memory: BigInt)
  final case class Version(major: BigInt, minor: BigInt, patch: BigInt)

  /** The pinned native language type includes V4 even before its ledger activation. This
    * presentation type does not add an execution profile to PlutusLanguage.
    */
  enum LedgerLanguage:
    case V1, V2, V3, V4

  enum Universe:
    case Integer, ByteString, Text, Unit, Bool, ProtoArray, ProtoList, ProtoPair
    case Apply(function: Universe, argument: Universe)
    case Data, BlsG1, BlsG2, BlsMlResult, Value

  enum Constant:
    case Integer(value: BigInt)
    case ByteString(value: Bytes)
    case Text(value: String)
    case UnitValue
    case Bool(value: Boolean)
    case Data(value: PlutusData)
    case List(elementType: Universe, values: Vector[Constant])
    case Pair(left: Constant, right: Constant)
    case Array(elementType: Universe, values: Vector[Constant])
    case BlsG1(compressed: Bytes)
    case BlsG2(compressed: Bytes)
    case BlsMlResult
    case Value(entries: Vector[(Bytes, Vector[(Bytes, BigInt)])])

  enum Term:
    case Var(name: String, index: BigInt)
    case Lam(name: String, index: BigInt, body: Term)
    case Apply(function: Term, argument: Term)
    case Delay(body: Term)
    case Force(body: Term)
    case Constant(value: ReferenceDiagnostic.Constant)
    case Builtin(function: DefaultFun)
    case Error
    case Constr(index: BigInt, fields: Vector[Term])
    case Case(scrutinee: Term, branches: Vector[Term])

  enum FreeVariableError:
    case FreeUnique(unique: BigInt)
    case FreeIndex(index: BigInt)

  /** The native UnliftingError contains Text. Production captures its structured source before
    * rendering; NativeMessage retains explicitly constructed native API payloads for oracle use.
    */
  enum UnliftingError:
    case NotAConstant
    case TypeMismatch(expected: Universe, actual: Universe)
    case TypeErrorBypassed
    case NativeMessage(text: String)

  enum MachineError:
    case NonPolymorphicInstantiation
    case NonWrapUnwrapped
    case NonFunctionalApplication
    case OpenTermEvaluated
    case Unlifting(cause: UnliftingError)
    case BuiltinTermArgumentExpected
    case UnexpectedBuiltinTermArgument
    case NonConstrScrutinized
    case MissingCaseBranch(index: BigInt)
    case Panic(nativeException: String)

  /** Typed inputs to DefaultUniverse.hs:919-1034 and Builtin/Case.hs:63-67. */
  enum CaseBuiltinError:
    case Unavailable(protocol: BigInt)
    case OutOfBounds(value: Constant, branches: BigInt)
    case ExpectedNonEmptyList
    case DataUnsupported
    case NonConstructorData
    case UnsupportedType(universe: Universe)
    case NativeMessage(text: String)

  enum CekUserError:
    case CaseBuiltin(cause: CaseBuiltinError)
    case OutOfBudget(remaining: Budget)
    case EvaluationFailure

  enum CekError:
    case Structural(cause: MachineError)
    case Operational(cause: CekUserError)

  final case class ErrorWithCause(error: CekError, cause: Option[Term])

  enum DeserialiseFailureReason:
    case EndOfInput, ExpectedBytes
    case OtherReason(nativeReason: String)

  enum ScriptDecodeError:
    case CborDeserialiseError(offset: BigInt, reason: DeserialiseFailureReason)
    case Remainder(bytes: Bytes)
    case LedgerLanguageNotAvailable(language: LedgerLanguage, introduced: BigInt, current: BigInt)
    case PlutusCoreLanguageNotAvailable(version: Version, language: LedgerLanguage, current: BigInt)

  enum EvaluationError:
    case Cek(error: ErrorWithCause)
    case DeBruijn(error: FreeVariableError)
    case Codec(error: ScriptDecodeError)
    case CostModelParameterMismatch
    case InvalidReturnValue
