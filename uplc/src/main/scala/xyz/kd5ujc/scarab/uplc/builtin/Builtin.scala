// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, SemanticsVariant, Signature}
import xyz.kd5ujc.scarab.uplc.cost.CostStream

/** What a builtin needs to know about a value of the machine `V`: whether it is a constant, and how
  * to make one. The builtins are written once for any machine; the CEK machine gives its own
  * values.
  */
trait MachineValue[V]:
  def constantOf(value: V): Option[Constant]
  def ofConstant(constant: Constant): V

  /** [[constantOf]] as the constant itself, or [[MachineValue.NoConstant]]: what a saturated
    * application reads of each argument, with no `Option` made for it.
    */
  def asConstant(value: V): Constant | MachineValue.NoConstant.type = constantOf(value) match
    case Some(c) => c
    case None    => MachineValue.NoConstant

object MachineValue:
  /** A value of the machine that is not a constant ([[MachineValue.asConstant]]). */
  case object NoConstant

/** An argument of a saturated builtin application once it is unlifted: a constant of the
  * parameter's type (`Signature.Parameter.Constant`), or any value of the machine passed as it is
  * (`Signature.Parameter.Opaque`).
  */
enum Argument[+V]:
  case Con(value: Constant)
  case Opaque(value: V)

object Argument:
  /** The argument an unlifting of the signature makes of `value` for `parameter` once it unlifts:
    * the constant of a constant parameter, the value itself for an opaque one.
    */
  def of[V](parameter: Signature.Parameter, value: V)(using mv: MachineValue[V]): Argument[V] =
    parameter match
      case Signature.Parameter.Opaque(_) => Opaque(value)
      case Signature.Parameter.Constant(_) =>
        mv.asConstant(value) match
          case c: Constant             => Con(c)
          case MachineValue.NoConstant => Opaque(value)

  /** The arguments of an application of `function` held as the machine holds them, the last one
    * first, in order and each as [[of]] makes it (one past the signature is opaque).
    */
  def unlifted[V](function: DefaultFun, arguments: List[V])(using
      MachineValue[V]
  ): Vector[Argument[V]] =
    val parameters = function.signature.parameters
    arguments.reverseIterator.zipWithIndex
      .map((value, i) => parameters.lift(i).fold(Opaque(value))(of(_, value)))
      .toVector

/** The two classes of the reference's errors (`EvaluationError structural operational`,
  * `PlutusCore/Evaluation/Error.hs`): a structural one is a malformed program, an operational one a
  * program that runs and fails. Both fail the script.
  */
enum ErrorClass:
  case Structural, Operational

/** Why an argument does not unlift (`UnliftingError`, wrapped as `UnliftingEvaluationError`,
  * `PlutusCore/Builtin/Result.hs:46-56`, plutus 1.70.0.0). The unlifting owns the cases; it may add
  * ones the reference distinguishes.
  *
  * @param position
  *   the argument's position, from 0
  */
enum UnliftingCause:
  case NotAConstant(position: Int)
  case WrongType(position: Int, expected: Signature.Type, actual: DefaultUni)

  /** The argument is of the right type but outside the range the variant unlifts it into (`Int`,
    * `Word64`, `CInteger`, `CByteString` and their kin).
    */
  case OutOfBounds(position: Int, bound: UnliftingBound)

  /** An integer outside the range of a fixed-width Haskell type (`Int`, `Word8`, `Word64`), whose
    * message names the integer: `readKnownAsInteger` (`PlutusCore/Default/Universe.hs:585-607`,
    * plutus 1.70.0.0). The bounds of `CInteger` and `CByteString`, whose messages name no value,
    * are [[OutOfBounds]].
    */
  case NotWithinBounds(position: Int, bound: UnliftingBound, value: BigInt)

  /** A `SomeConstant` argument that should be a list and is not: the structural error the
    * denotations of the list builtins raise after their cost is spent ("Expected a list but got
    * something else", `PlutusCore/Default/Builtins.hs:1663-1730,2328-2393`, plutus 1.70.0.0).
    */
  case ExpectedList(position: Int, actual: DefaultUni)

  /** A `SomeConstant` argument that should be a pair and is not (`fstPair`, `sndPair`,
    * `Builtins.hs:1638-1661`, plutus 1.70.0.0).
    */
  case ExpectedPair(position: Int, actual: DefaultUni)

  /** A `SomeConstant` argument that should be an array and is not ("Expected an array but got
    * something else", `Builtins.hs:2378-2408,2482-2497`, plutus 1.70.0.0).
    */
  case ExpectedArray(position: Int, actual: DefaultUni)

  /** `mkCons` of a value whose type is not the list's element type ("The type of the value does not
    * match the type of elements in the list", `Builtins.hs:1677-1694`, plutus 1.70.0.0).
    */
  case ElementMismatch(element: DefaultUni, value: DefaultUni)

  /** A call with the wrong number of arguments. The reference cannot make one (a runtime is applied
    * only when saturated, `PlutusCore/Builtin/Meaning.hs:234-330`, plutus 1.70.0.0), so this is a
    * defect of the caller, not a case of the reference.
    */
  case Arity(expected: Int, actual: Int)

/** A range an argument unlifts into in some variants; the unlifting owns the cases. */
enum UnliftingBound:
  case Int64, Word64, Int, Word8, CInteger, CByteString

  /** The name `typeRep` shows for the type the bound reads through: an `Int` reads as an `Int64`
    * (`PlutusCore/Default/Universe.hs:615-617`, plutus 1.70.0.0).
    */
  def typeName: String = this match
    case Int | Int64 => "Int64"
    case Word64      => "Word64"
    case Word8       => "Word8"
    case CInteger    => "CInteger"
    case CByteString => "CByteString"

final case class UnliftingFailure(errorClass: ErrorClass, cause: UnliftingCause):
  /** The log line of the failure: `throwError` puts the message of every operational unlifting
    * error into the logs, a structural one gets none (`MonadError BuiltinError BuiltinResult`,
    * `PlutusCore/Builtin/Result.hs:198-220`, plutus 1.70.0.0).
    */
  def message: Option[String] = errorClass match
    case ErrorClass.Structural => None
    case ErrorClass.Operational =>
      cause match
        case UnliftingCause.OutOfBounds(_, UnliftingBound.CInteger) => Some("Integer out of bounds")
        case UnliftingCause.OutOfBounds(_, UnliftingBound.CByteString) =>
          Some("ByteString overflow")
        case UnliftingCause.NotWithinBounds(_, bound, value) =>
          Some(s"$value is not within the bounds of ${bound.typeName}")
        case _ => None

/** Why a denotation fails once its arguments are unlifted and its cost is spent
  * (`BuiltinEvaluationFailure`, whose message the reference emits as a log line,
  * `PlutusCore/Builtin/Result.hs:53-56`, plutus 1.70.0.0). The builtins own the cases and add the
  * ones they need; each case renders the reference's message in [[EvaluationFailure.message]].
  */
enum EvaluationFailure:
  /** `nonZeroSecondArg` (`Default/Builtins.hs:262-265`, plutus 1.70.0.0). */
  case DivisionByZero

  /** `indexByteString` (`Default/Builtins.hs:1338,1346`, plutus 1.70.0.0). */
  case ByteStringIndexOutOfBounds(index: BigInt, size: Int)

  /** `indexArray` (`Default/Builtins.hs:2401`, plutus 1.70.0.0). */
  case ArrayIndexOutOfBounds(index: BigInt, size: Int)

  /** `headList`, `tailList` (`Default/Builtins.hs:1701,1714`, plutus 1.70.0.0). */
  case EmptyList

  /** `unConstrData`, `unMapData`, `unListData`, `unIData`, `unBData`
    * (`Default/Builtins.hs:1794,1805,1816,1827,1838`, plutus 1.70.0.0).
    */
  case UnexpectedData(expected: DataShape)

  /** `decodeUtf8` of bytes that are not well-formed UTF-8 (`Default/Builtins.hs:1595,1606`, plutus
    * 1.70.0.0).
    */
  case InvalidUtf8

  /** A BLS12-381 builtin (`PlutusCore/Crypto/BLS12_381/G1.hs`, `G2.hs`). */
  case Bls(failure: xyz.kd5ujc.scarab.uplc.bls.BlsFailure)

  /** An argument of a signature builtin that does not deserialise (`PlutusCore/Crypto/Ed25519.hs`,
    * `Secp256k1.hs`).
    */
  case SignatureInput(failure: extended.SignatureInputFailure)

  /** The conversions and bitwise builtins (`PlutusCore/Bitwise.hs`). The reference emits several
    * lines; [[message]] joins them with a newline, and the outcome carries them one by one.
    */
  case Bitwise(failure: extended.BitwiseFailure)

  /** `expModInteger` (`PlutusCore/Crypto/ExpMod.hs`). */
  case ExpMod(failure: extended.ExpModFailure)

  /** The `Value` builtins (`PlutusCore/Value.hs`). */
  case ValueOperation(failure: extended.ValueFailure)

  /** A library the builtin calls failed where the reference's cannot: a defect of this
    * implementation, not a verdict on the script. The reference has no message for it.
    */
  case Defect(function: DefaultFun, defect: extended.LibraryDefect)

  /** `multiIndexArray` (`PlutusCore/Arrays.hs:48-69`, plutus 1.70.0.0). */
  case ArrayIndices(failure: extended.ArrayFailure)

  /** The text the reference emits as a log line for this failure (its argument to `fail`, which
    * `BuiltinResult` turns into a log, `Builtin/Result.hs:88-90`, plutus 1.70.0.0), or none where
    * it emits none.
    */
  def message: Option[String] = this match
    case DivisionByZero                   => Some("Cannot divide by zero")
    case ByteStringIndexOutOfBounds(_, _) => Some("Index out of bounds")
    case ArrayIndexOutOfBounds(_, _)      => Some("Array index out of bounds")
    case EmptyList                        => Some("Expected a non-empty list but got an empty one")
    case UnexpectedData(shape) =>
      Some(s"Expected the ${shape.constructor} constructor but got a different one")
    case InvalidUtf8             => Some("decodeUtf8: invalid input")
    case Bls(failure)            => Some(failure.message)
    case SignatureInput(failure) => Some(failure.lines.mkString("\n"))
    case Bitwise(failure)        => Some(failure.lines.mkString("\n"))
    case ExpMod(failure)         => Some(failure.lines.mkString("\n"))
    case ValueOperation(failure) => Some(failure.lines.mkString("\n"))
    case Defect(_, _)            => None
    case ArrayIndices(failure)   => Some(failure.lines.mkString("\n"))

  /** The lines the reference emits for this failure, one per `emit` or `fail`, which is what the
    * machine logs ([[Outcome.failed]]); [[message]] joins them for a single-line rendering.
    */
  def logs: Vector[String] = this match
    case SignatureInput(failure) => failure.lines
    case Bitwise(failure)        => failure.lines
    case ExpMod(failure)         => failure.lines
    case ValueOperation(failure) => failure.lines
    case ArrayIndices(failure)   => failure.lines
    case other                   => other.message.toVector

/** The constructor of `Data` a deconstructing builtin expects. */
enum DataShape:
  case Constr, Map, List, I, B

  /** The constructor's name in the reference's messages. */
  def constructor: String = toString

enum BuiltinFailure:
  case Unlifting(failure: UnliftingFailure)
  case Evaluation(failure: EvaluationFailure)

/** The result of a saturated denotation (`BuiltinResult`, `PlutusCore/Builtin/Result.hs`): a value,
  * or a failure, each with the log lines it emits (`trace` emits one on success).
  */
enum Outcome[+V]:
  case Success(value: V, logs: Vector[String])
  case Failure(failure: BuiltinFailure, logs: Vector[String])

object Outcome:
  def succeeded[V](value: V): Outcome[V] = Success(value, Vector.empty)

  /** A denotation's failure with the reference's log line: every denotation fails through this, and
    * the machine emits an outcome's logs exactly once, before it raises (`evalBuiltinApp`,
    * `Cek/Internal.hs:1066-1073`, plutus 1.70.0.0).
    */
  def failed[V](failure: EvaluationFailure): Outcome[V] =
    Failure(BuiltinFailure.Evaluation(failure), failure.logs)

/** The meaning of one builtin once its arguments are unlifted: pure, and the same for every
  * machine.
  */
trait Denotation:
  def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V]

  /** [[apply]] of a saturated application of `function` whose arguments unlift
    * ([[Unlifting.refusal]]), given as the machine holds them, the last one first: each argument as
    * the unlifting makes it ([[Argument.of]]).
    */
  def applied[V](function: DefaultFun, arguments: List[V])(using MachineValue[V]): Outcome[V] =
    apply(Argument.unlifted(function, arguments))

/** The denotation of every builtin under one semantics variant. */
trait Denotations:
  def apply(function: DefaultFun): Denotation

/** Unlifting under one semantics variant: every argument against the builtin's [[Signature]] and
  * the variant's bounds, in argument order, the first failure winning. The reference unlifts at
  * full saturation, before it costs (`toMonoF`, `PlutusCore/Builtin/Meaning.hs:304-330`, plutus
  * 1.70.0.0).
  */
trait Unlifting:
  def apply[V](function: DefaultFun, arguments: Vector[V])(using
      MachineValue[V]
  ): Either[UnliftingFailure, Vector[Argument[V]]]

  /** [[apply]]'s failure for a saturated application of `function`, its arguments given as the
    * machine holds them, the last one first; none when every argument unlifts.
    */
  def refusal[V](function: DefaultFun, arguments: List[V])(using
      MachineValue[V]
  ): Option[UnliftingFailure] =
    apply(function, arguments.reverseIterator.toVector).swap.toOption

/** The cost of one builtin from its unlifted arguments (the costing function of the cost model,
  * applied to the arguments' measures, `ExMemoryUsage`).
  */
trait CostingFunction:
  def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream

  /** [[apply]] of a saturated application of `function` whose arguments unlift, given as the
    * machine holds them, the last one first ([[Denotation.applied]]).
    */
  def applied[V](function: DefaultFun, arguments: List[V])(using MachineValue[V]): CostStream =
    apply(Argument.unlifted(function, arguments))

/** The costing functions of every builtin under one cost model and semantics variant. */
trait Costing:
  def apply(function: DefaultFun): CostingFunction

/** A saturated application ready for the machine: spend `cost`, element by element, then run
  * `result`. The result is a function because the reference computes it only after the cost is
  * spent (`BuiltinCostedResult ExBudgetStream ~(BuiltinResult val)`,
  * `PlutusCore/Builtin/Runtime.hs:32-35`, plutus 1.70.0.0).
  */
final case class Prepared[V](cost: CostStream, result: () => Outcome[V])

/** The builtins of one evaluation: semantics variant, unlifting, denotations and costing. */
final case class Builtins(
    variant: SemanticsVariant,
    unlifting: Unlifting,
    denotations: Denotations,
    costing: Costing
):
  /** A saturated application, in the reference's order (`toMonoF`, `Meaning.hs:247-262,304-330`;
    * `evalBuiltinApp`, `Cek/Internal.hs:1052-1074`, plutus 1.70.0.0): unlift every argument; if one
    * fails, the application costs nothing and fails ([[CostStream.free]]), logging the message of
    * an operational unlifting error ([[UnliftingFailure.message]]); otherwise its cost is computed
    * from the unlifted arguments, and its denotation runs only when the machine has spent that
    * cost.
    */
  def prepare[V](function: DefaultFun, arguments: Vector[V])(using MachineValue[V]): Prepared[V] =
    unlifting(function, arguments) match
      case Left(failure) => Prepared(CostStream.free, () => Builtins.refused(failure))
      case Right(unlifted) =>
        Prepared(costing(function)(unlifted), () => denotations(function)(unlifted))

  /** [[prepare]] in the steps the machine takes, on the arguments as it holds them (the last one
    * first), with nothing made between the steps: the unlifting's failure, if any ([[refusal]]),
    * which costs nothing and is [[Builtins.refused]]; otherwise the cost ([[cost]]), and once the
    * machine has spent it the result ([[result]]).
    */
  def refusal[V](function: DefaultFun, arguments: List[V])(using
      MachineValue[V]
  ): Option[UnliftingFailure] =
    unlifting.refusal(function, arguments)

  def cost[V](function: DefaultFun, arguments: List[V])(using MachineValue[V]): CostStream =
    costing(function).applied(function, arguments)

  def result[V](function: DefaultFun, arguments: List[V])(using MachineValue[V]): Outcome[V] =
    denotations(function).applied(function, arguments)

object Builtins:
  /** The outcome of an application whose arguments do not unlift, logging the message of an
    * operational unlifting error ([[UnliftingFailure.message]]).
    */
  def refused[V](failure: UnliftingFailure): Outcome[V] =
    Outcome.Failure(BuiltinFailure.Unlifting(failure), failure.message.toVector)
