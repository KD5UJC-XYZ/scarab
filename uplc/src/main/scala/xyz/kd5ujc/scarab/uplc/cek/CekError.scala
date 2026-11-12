// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DeBruijn,
  DefaultFun,
  DefaultUni,
  ProtocolMajor,
  Term,
  Word64
}
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, ErrorClass}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget

/** Why an evaluation failed: the machine's errors of the reference, `MachineError` and
  * `CekUserError` (`PlutusCore/Evaluation/Machine/Exception.hs:43-63`, `Cek/Internal.hs:497-505`,
  * plutus 1.70.0.0 `507cab8c`), each in the class the reference throws it in. A structural error is
  * a malformed program, an operational one a program that runs and fails; both fail the script.
  */
enum CekError:
  /** `NonPolymorphicInstantiationMachineError`: `force` of a value that is neither a `delay` nor a
    * builtin (`Cek/Internal.hs:976-977`).
    */
  case NonPolymorphicInstantiation

  /** `NonFunctionalApplicationMachineError`: an application of a value that is neither a `lam` nor
    * a builtin (`Cek/Internal.hs:1007-1008`).
    */
  case NonFunctionalApplication

  /** `OpenTermEvaluatedMachineError`: a variable that no binder binds
    * (`Cek/Internal.hs:1082-1088`).
    */
  case OpenTermEvaluated(index: DeBruijn)

  /** `BuiltinTermArgumentExpectedMachineError`: `force` of a builtin that expects a term argument
    * (`Cek/Internal.hs:974-975`).
    */
  case BuiltinTermArgumentExpected

  /** `UnexpectedBuiltinTermArgumentMachineError`: an argument to a builtin that expects a `force`
    * (`Cek/Internal.hs:1005-1006`).
    */
  case UnexpectedBuiltinTermArgument

  /** `NonConstrScrutinizedMachineError`: `case` of a value that is neither a `constr` nor a
    * constant (`Cek/Internal.hs:950`).
    */
  case NonConstrScrutinized

  /** `MissingCaseBranchMachineError`: `case` of a `constr` whose tag has no branch
    * (`Cek/Internal.hs:936-944`).
    */
  case MissingCaseBranch(tag: Word64)

  /** `CekCaseBuiltinError`: `case` of a constant that the caser refuses
    * (`Cek/Internal.hs:946-947`).
    */
  case CaseBuiltin(error: CaseBuiltinError)

  /** A saturated builtin application failed (`throwBuiltinErrorWithCause`,
    * `Cek/Internal.hs:1071-1073`): a structural unlifting failure is `UnliftingMachineError`, an
    * operational one and a failure of the denotation are `CekEvaluationFailure`
    * (`builtinErrorToEvaluationError`, `Cek/Internal.hs:519-523`).
    */
  case Builtin(function: DefaultFun, failure: BuiltinFailure)

  /** `CekEvaluationFailure` from the term `error` (`Cek/Internal.hs:874-875`). */
  case ErrorTerm

  /** `CekOutOfExError`: a restricting budget overspent (`ExBudgetMode.hs:146-165`).
    *
    * @param remaining
    *   the budget left once the charge that overspent it was spent, negative in at least one
    *   component
    */
  case OutOfBudget(remaining: ExBudget)

  def errorClass: ErrorClass = this match
    case NonPolymorphicInstantiation | NonFunctionalApplication | OpenTermEvaluated(_) |
        BuiltinTermArgumentExpected | UnexpectedBuiltinTermArgument | NonConstrScrutinized |
        MissingCaseBranch(_) =>
      ErrorClass.Structural
    case Builtin(_, BuiltinFailure.Unlifting(failure)) => failure.errorClass
    case Builtin(_, BuiltinFailure.Evaluation(_))      => ErrorClass.Operational
    case CaseBuiltin(_) | ErrorTerm | OutOfBudget(_)   => ErrorClass.Operational

/** Why the caser refused a constant (`HeadError`, `PlutusCore/Default/Universe.hs:966-1034`,
  * `PlutusCore/Builtin/Case.hs:63-67`). The reference renders each as text; these are its cases.
  */
enum CaseBuiltinError:
  /** `unavailableCaserBuiltin`: casing on constants before protocol 11 (`Case.hs:63-67`). */
  case Unavailable(protocol: ProtocolMajor)

  /** `outOfBoundsErr`: the constant selects no branch, or the number of branches does not fit its
    * type (`Universe.hs:928-935`).
    */
  case OutOfBounds(scrutinee: Constant, branches: Int)

  /** A list cased with one branch is empty (`Universe.hs:993`). */
  case EmptyList

  /** `caseBuiltinNoData`: casing on `Data` before protocol 12 (`Universe.hs:1011-1016`). */
  case DataUnsupported

  /** A `Data` that is not a `Constr` (`Universe.hs:1031`). */
  case DataNotConstr

  /** A type that has no casing (`Universe.hs:1005`). */
  case UnsupportedType(uni: DefaultUni)

/** The term the reference attaches to an error (`ErrorWithCause`), discharged only when it is read:
  * a discharge can be large, and nothing on the ledger's path reads it.
  */
final class Cause private (compute: () => Term):
  lazy val term: Term = compute()
  override def toString: String = "Cause"

object Cause:
  def apply(term: => Term): Cause = new Cause(() => term)
  def of(value: CekValue): Cause = new Cause(() => CekValue.discharge(value))

/** A failed evaluation: the error and the term it is about (`ErrorWithCause`, with no cause for an
  * overspent budget, `ExBudgetMode.hs:162-165`).
  */
final case class CekFailure(error: CekError, cause: Option[Cause])
