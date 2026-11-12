// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.oracle

import cats.syntax.all.*
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.*
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  Builtins,
  Denotation,
  Denotations,
  ErrorClass,
  EvaluationFailure
}
import xyz.kd5ujc.scarab.uplc.builtin.core.{CoreDenotations, CoreUnlifting, UnsupportedVariant}
import xyz.kd5ujc.scarab.uplc.builtin.extended.ExtendedDenotations
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CaserBuiltin,
  CekError,
  CekMachine,
  CekResult,
  MachineParameters
}
import xyz.kd5ujc.scarab.uplc.cost.{CostModelApplyError, EvaluationContext, ExBudget}
import xyz.kd5ujc.scarab.uplc.flat.{ScriptDecodeError, SerialisedScript}

/** What the ledger's evaluation of a script gives, in the terms the generated-term oracle records
  * (`scripts/oracle/plutus/generated/Main.hs`).
  */
enum Verdict:
  /** `deserialiseScript` refused the script: it is not admitted. */
  case Decode(error: ScriptDecodeError)

  /** `mkTermToEvaluate` refused the program's version (`PlutusCoreLanguageNotAvailableError`, a
    * `CodecError`).
    */
  case Setup(refusal: Availability.Refusal)

  /** `checkScope` refused the applied term (`DeBruijnError`). */
  case Scope(index: DeBruijn)

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
    case Ran(Outcome.Failed(e), _, _)          => Some(LedgerEvaluation.constructor(e))

/** How a run of the machine ends for the ledger (`processLogsAndErrors`, `Common/Eval.hs:303-321`).
  */
enum Outcome:
  case Success
  case Failed(error: CekError)

  /** A V3 script that ran to anything but unit. */
  case InvalidReturnValue

/** Why this evaluator gives no verdict: a defect of its own, never a script failure. */
enum NoVerdict:
  case Context(error: CostModelApplyError)
  case NoCosts(protocol: ProtocolMajor)
  case Unsupported(variant: UnsupportedVariant)
  case MissingDenotations(functions: Set[DefaultFun])
  case Defect(failure: EvaluationFailure)
  case Thrown(cause: Throwable)

/** A machine for one profile and cost-model list. */
final case class LedgerMachine(
    language: PlutusLanguage,
    protocol: ProtocolMajor,
    parameters: MachineParameters,
    denoted: Set[DefaultFun]
)

/** The ledger's evaluation of a script for the tests, until the ledger entry point of the evaluator
  * exists: `deserialiseScript` (`PlutusLedgerApi/Common/SerialisedScript.hs:254-266`), then
  * `evaluateScriptRestricting` (`Common/Eval.hs:269-274`): `mkTermToEvaluate` (lines 107-130)
  * applies the arguments, checks the program's version and the scope of the applied term, the
  * machine runs it within the budget with the parameters of the profile's variant and the caser of
  * its protocol (`toMachineParameters`, `defaultCaserBuiltinFor`, lines 132-146), and
  * `processLogsAndErrors` (lines 303-321) refuses a V3 result other than unit, all in plutus
  * 1.70.0.0 (`507cab8c`).
  */
object LedgerEvaluation:

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

  /** The machine of `language` at `protocol` with the context the ledger makes from `costModel`. */
  def machine(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      costModel: Vector[Long]
  ): Either[NoVerdict, LedgerMachine] =
    for
      context <- EvaluationContext.build(language, costModel)._2.leftMap(NoVerdict.Context(_))
      costs <- context.at(protocol).toRight(NoVerdict.NoCosts(protocol))
      core <- CoreDenotations.of(costs.variant).leftMap(NoVerdict.Unsupported(_))
      extended <- ExtendedDenotations.of(costs.variant).leftMap(NoVerdict.Unsupported(_))
      unlifting <- CoreUnlifting.of(costs.variant).leftMap(NoVerdict.Unsupported(_))
    yield
      val meanings: Map[DefaultFun, Denotation] = core ++ extended
      val denotations: Denotations = function => meanings(function)
      LedgerMachine(
        language,
        protocol,
        MachineParameters(
          costs.machineCosts,
          Builtins(costs.variant, unlifting, denotations, costs.costing),
          CaserBuiltin.forProtocol(protocol)
        ),
        meanings.keySet
      )

  /** `checkScope` (`UntypedPlutusCore/Check/Scope.hs:28-55`): every variable is bound, and the walk
    * does not enter `constr` or `case`, so a variable there that no binder binds is the machine's
    * to find. The first variable out of scope in the reference's order, if any.
    */
  def checkScope(term: Term): Option[DeBruijn] =
    @tailrec def go(todo: List[(Term, Long)]): Option[DeBruijn] = todo match
      case Nil => None
      case (t, level) :: rest =>
        t.unfix match
          case TermF.Var(i) =>
            val index = i.index.bits
            if index != 0L && java.lang.Long.compareUnsigned(index, level) <= 0 then go(rest)
            else Some(i)
          case TermF.LamAbs(body)         => go((body, level + 1) :: rest)
          case TermF.Apply(function, arg) => go((function, level) :: (arg, level) :: rest)
          case TermF.Force(inner)         => go((inner, level) :: rest)
          case TermF.Delay(inner)         => go((inner, level) :: rest)
          case _                          => go(rest)
    go(List((term, 0L)))

  /** The builtins a term names. */
  def builtins(term: Term): Set[DefaultFun] =
    Schemes.cata[TermF, Set[DefaultFun]](term) {
      case TermF.Builtin(function) => Set(function)
      case layer                   => layer.foldLeft(Set.empty[DefaultFun])(_ ++ _)
    }

  /** `evaluateScriptRestricting` of the script with these arguments within `budget`. */
  def evaluate(
      machine: LedgerMachine,
      script: Bytes,
      arguments: Vector[PlutusData],
      budget: ExBudget
  ): Either[NoVerdict, Verdict] =
    SerialisedScript.deserialiseScript(machine.language, machine.protocol, script) match
      case Left(error) => Right(Verdict.Decode(error))
      case Right(program) =>
        val applied =
          arguments.foldLeft(program.term)((t, d) => Term.app(t, Term.constant(Constant.Data(d))))
        SerialisedScript.forEvaluation(machine.language, machine.protocol, program) match
          case Left(refusal) => Right(Verdict.Setup(refusal))
          case Right(_) =>
            checkScope(applied) match
              case Some(index) => Right(Verdict.Scope(index))
              case None        => run(machine, applied, budget)

  private def run(
      machine: LedgerMachine,
      term: Term,
      budget: ExBudget
  ): Either[NoVerdict, Verdict] =
    val absent = builtins(term).filterNot(machine.denoted.contains)
    if absent.nonEmpty then Left(NoVerdict.MissingDenotations(absent))
    else
      Either
        .catchNonFatal(CekMachine.run(machine.parameters, BudgetMode.Restricting(budget), term))
        .leftMap(NoVerdict.Thrown(_))
        .flatMap { report =>
          val spent = report.budget.spent
          def ran(outcome: Outcome) = Right(Verdict.Ran(outcome, spent, report.logs))
          report.result match
            case CekResult.Failure(failure) =>
              failure.error match
                case CekError.Builtin(
                      _,
                      BuiltinFailure.Evaluation(defect: EvaluationFailure.Defect)
                    ) =>
                  Left(NoVerdict.Defect(defect))
                case error => ran(Outcome.Failed(error))
            case CekResult.SuccessConstant(Constant.Unit) => ran(Outcome.Success)
            case _ =>
              machine.language.evaluatedAs match
                case PlutusLanguage.V1 | PlutusLanguage.V2 => ran(Outcome.Success)
                case _                                     => ran(Outcome.InvalidReturnValue)
        }
