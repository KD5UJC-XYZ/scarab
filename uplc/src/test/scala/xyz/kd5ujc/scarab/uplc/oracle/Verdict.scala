// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, ErrorClass}
import xyz.kd5ujc.scarab.uplc.cek.{BudgetMode, CekError}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  FreeVariableError,
  LedgerContext,
  LedgerEval,
  LedgerOutcome,
  ScriptResult,
  Unavailability,
  VerboseMode
}

/** What the ledger's handling of a script gives, in the terms the generated-term oracle records
  * (`scripts/oracle/plutus/generated/Main.hs`): the production path's [[LedgerOutcome]], read as
  * the oracle reads the reference's.
  */
enum Verdict:
  /** `deserialiseScript` refused the script: it is not admitted. */
  case Decode(error: ScriptDecodeError)

  /** `mkTermToEvaluate` refused the program's version (`PlutusCoreLanguageNotAvailableError`, a
    * `CodecError`).
    */
  case Setup(refusal: Availability.Refusal)

  /** `checkScope` refused the applied term (`DeBruijnError`). */
  case Scope(error: FreeVariableError)

  /** The machine ran: the outcome, the budget it spent and the lines it logged. */
  case Ran(outcome: Outcome, spent: ExBudget, logs: Vector[String])

  /** The stage the reference oracles name. */
  def stage: String = this match
    case Decode(_)                                                 => "decode"
    case Setup(_)                                                  => "setup"
    case Scope(_)                                                  => "scope"
    case Ran(Outcome.Success, _, _)                                => "success"
    case Ran(Outcome.Failed(CekError.OutOfBudget(_)), _, _)        => "budget"
    case Ran(Outcome.Failed(_) | Outcome.InvalidReturnValue, _, _) => "evaluation"

  /** The class of the error, for a failure of the machine. */
  def errorClass: Option[String] = this match
    case Ran(Outcome.Failed(e), _, _) =>
      Some(e.errorClass match
        case ErrorClass.Structural  => "structural"
        case ErrorClass.Operational => "operational")
    case _ => None

  /** The reference's constructor of the error: the machine's `MachineError` or `CekUserError`, or
    * the ledger's `EvaluationError`.
    */
  def constructor: Option[String] = this match
    case Decode(_)                             => None
    case Setup(_)                              => Some("CodecError")
    case Scope(_)                              => Some("DeBruijnError")
    case Ran(Outcome.Success, _, _)            => None
    case Ran(Outcome.InvalidReturnValue, _, _) => Some("InvalidReturnValue")
    case Ran(Outcome.Failed(e), _, _)          => Some(Verdict.constructor(e))

/** How a run of the machine ends for the ledger (`processLogsAndErrors`, `Common/Eval.hs:303-321`).
  */
enum Outcome:
  case Success
  case Failed(error: CekError)

  /** A V3 script that ran to anything but unit. */
  case InvalidReturnValue

/** The context of one cost-model list for the language and protocol of a profile, or why this
  * evaluator has none.
  */
final case class ProfileContext(
    language: PlutusLanguage,
    protocol: ProtocolMajor,
    context: Either[Unavailability, LedgerContext]
)

object Verdict:

  /** The reference's name of a machine error (`MachineError`, `PlutusCore/Evaluation/Machine/
    * Exception.hs:43-63`; `CekUserError`, `Cek/Internal.hs:497-505`), as the error is classed by
    * `builtinErrorToEvaluationError` (`Cek/Internal.hs:519-523`).
    */
  def constructor(error: CekError): String = error match
    case CekError.NonPolymorphicInstantiation   => "NonPolymorphicInstantiationMachineError"
    case CekError.NonFunctionalApplication      => "NonFunctionalApplicationMachineError"
    case CekError.OpenTermEvaluated(_)          => "OpenTermEvaluatedMachineError"
    case CekError.BuiltinTermArgumentExpected   => "BuiltinTermArgumentExpectedMachineError"
    case CekError.UnexpectedBuiltinTermArgument => "UnexpectedBuiltinTermArgumentMachineError"
    case CekError.NonConstrScrutinized          => "NonConstrScrutinizedMachineError"
    case CekError.MissingCaseBranch(_)          => "MissingCaseBranchMachineError"
    case CekError.CaseBuiltin(_)                => "CekCaseBuiltinError"
    case CekError.Builtin(_, BuiltinFailure.Unlifting(failure)) =>
      failure.errorClass match
        case ErrorClass.Structural  => "UnliftingMachineError"
        case ErrorClass.Operational => "CekEvaluationFailure"
    case CekError.Builtin(_, BuiltinFailure.Evaluation(_)) => "CekEvaluationFailure"
    case CekError.ErrorTerm                                => "CekEvaluationFailure"
    case CekError.OutOfBudget(_)                           => "CekOutOfExError"

  /** The verdict of the production path's outcome, or why it gives none. */
  def of(outcome: LedgerOutcome): Either[Unavailability, Verdict] = outcome match
    case LedgerOutcome.Refused(error)      => Right(Decode(error))
    case ScriptResult.Success(spent, logs) => Right(Ran(Outcome.Success, spent, logs))
    case ScriptResult.Unavailable(reason)  => Left(reason)
    case ScriptResult.Failure(error, spent, logs) =>
      error match
        case EvaluationError.Codec(refusal) => Right(Setup(refusal))
        case EvaluationError.DeBruijn(free) => Right(Scope(free))
        case EvaluationError.Cek(failure) => Right(Ran(Outcome.Failed(failure.error), spent, logs))
        case EvaluationError.InvalidReturnValue =>
          Right(Ran(Outcome.InvalidReturnValue, spent, logs))

  /** The context the ledger makes of a cost-model list ([[LedgerContext.make]]). */
  def context(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      costModel: Vector[Long]
  ): ProfileContext =
    ProfileContext(language, protocol, LedgerContext.make(language, costModel)._2)

  /** The script with its arguments as the node evaluates it, through the production path
    * ([[LedgerEval.evaluateSerialised]]): within `budget`, keeping the logs, as the oracle ran it.
    */
  def evaluate(
      profile: ProfileContext,
      script: Bytes,
      arguments: Vector[PlutusData],
      budget: ExBudget
  ): Either[Unavailability, Verdict] =
    profile.context.flatMap(context =>
      of(
        LedgerEval.evaluateSerialised(
          profile.language,
          profile.protocol,
          VerboseMode.Verbose,
          context,
          BudgetMode.Restricting(budget),
          script,
          arguments
        )
      )
    )

  /** The builtins a term names. */
  def builtins(term: Term): Set[DefaultFun] =
    Schemes.cata[TermF, Set[DefaultFun]](term) {
      case TermF.Builtin(function) => Set(function)
      case layer                   => layer.foldLeft(Set.empty[DefaultFun])(_ ++ _)
    }
