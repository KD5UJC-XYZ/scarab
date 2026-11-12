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

/** An argument of a saturated builtin application once it is unlifted: a constant of the
  * parameter's type (`Signature.Parameter.Constant`), or any value of the machine passed as it is
  * (`Signature.Parameter.Opaque`).
  */
enum Argument[+V]:
  case Con(value: Constant)
  case Opaque(value: V)

/** The two classes of the reference's errors (`EvaluationError structural operational`,
  * `PlutusCore/Evaluation/Error.hs`): a structural one is a malformed program, an operational one a
  * program that runs and fails. Both fail the script.
  */
enum ErrorClass:
  case Structural, Operational

/** Why an argument does not unlift (`UnliftingError`, wrapped as `UnliftingEvaluationError`,
  * `PlutusCore/Builtin/Result.hs:46-56`). The unlifting owns the cases; it may add ones the
  * reference distinguishes.
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

/** A range an argument unlifts into in some variants; the unlifting owns the cases. */
enum UnliftingBound:
  case Int64, Word64, Int, Word8, CInteger, CByteString

final case class UnliftingFailure(errorClass: ErrorClass, cause: UnliftingCause)

/** Why a denotation fails once its arguments are unlifted and its cost is spent
  * (`BuiltinEvaluationFailure`, whose message the reference emits as a log line,
  * `PlutusCore/Builtin/Result.hs:53-56`). The builtins own the cases and add the ones they need;
  * each case renders the reference's message in [[EvaluationFailure.message]].
  */
enum EvaluationFailure:
  /** `nonZeroSecondArg` (`Default/Builtins.hs:262-265`). */
  case DivisionByZero

  /** `indexByteString` (`Default/Builtins.hs:1338,1346`). */
  case ByteStringIndexOutOfBounds(index: BigInt, size: Int)

  /** `indexArray` (`Default/Builtins.hs:2401`). */
  case ArrayIndexOutOfBounds(index: BigInt, size: Int)

  /** `headList`, `tailList` (`Default/Builtins.hs:1701,1714`). */
  case EmptyList

  /** `unConstrData`, `unMapData`, `unListData`, `unIData`, `unBData`
    * (`Default/Builtins.hs:1794,1805,1816,1827,1838`).
    */
  case UnexpectedData(expected: DataShape)

  /** The text the reference emits as a log line for this failure (its argument to `fail`, which
    * `BuiltinResult` turns into a log, `Builtin/Result.hs:88-90`), or none where it emits none.
    */
  def message: Option[String] = this match
    case DivisionByZero                   => Some("Cannot divide by zero")
    case ByteStringIndexOutOfBounds(_, _) => Some("Index out of bounds")
    case ArrayIndexOutOfBounds(_, _)      => Some("Array index out of bounds")
    case EmptyList                        => Some("Expected a non-empty list but got an empty one")
    case UnexpectedData(shape) =>
      Some(s"Expected the ${shape.constructor} constructor but got a different one")

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
    * `Cek/Internal.hs:1066-1073`).
    */
  def failed[V](failure: EvaluationFailure): Outcome[V] =
    Failure(BuiltinFailure.Evaluation(failure), failure.message.toVector)

/** The meaning of one builtin once its arguments are unlifted: pure, and the same for every
  * machine.
  */
trait Denotation:
  def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V]

/** The denotation of every builtin under one semantics variant. */
trait Denotations:
  def apply(function: DefaultFun): Denotation

/** Unlifting under one semantics variant: every argument against the builtin's [[Signature]] and
  * the variant's bounds, in argument order, the first failure winning. The reference unlifts at
  * full saturation, before it costs (`toMonoF`, `PlutusCore/Builtin/Meaning.hs:304-330`).
  */
trait Unlifting:
  def apply[V](function: DefaultFun, arguments: Vector[V])(using
      MachineValue[V]
  ): Either[UnliftingFailure, Vector[Argument[V]]]

/** The cost of one builtin from its unlifted arguments (the costing function of the cost model,
  * applied to the arguments' measures, `ExMemoryUsage`).
  */
trait CostingFunction:
  def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): CostStream

/** The costing functions of every builtin under one cost model and semantics variant. */
trait Costing:
  def apply(function: DefaultFun): CostingFunction

/** A saturated application ready for the machine: spend `cost`, element by element, then run
  * `result`. The result is a function because the reference computes it only after the cost is
  * spent (`BuiltinCostedResult ExBudgetStream ~(BuiltinResult val)`,
  * `PlutusCore/Builtin/Runtime.hs:32-35`).
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
    * `evalBuiltinApp`, `Cek/Internal.hs:1052-1074`): unlift every argument; if one fails, the
    * application costs nothing and fails ([[CostStream.free]]); otherwise its cost is computed from
    * the unlifted arguments, and its denotation runs only when the machine has spent that cost.
    */
  def prepare[V](function: DefaultFun, arguments: Vector[V])(using MachineValue[V]): Prepared[V] =
    unlifting(function, arguments) match
      case Left(failure) =>
        Prepared(
          CostStream.free,
          () => Outcome.Failure(BuiltinFailure.Unlifting(failure), Vector.empty)
        )
      case Right(unlifted) =>
        Prepared(costing(function)(unlifted), () => denotations(function)(unlifted))
