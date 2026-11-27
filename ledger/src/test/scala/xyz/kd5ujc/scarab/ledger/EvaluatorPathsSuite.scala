// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import munit.FunSuite
import xyz.kd5ujc.scarab.uplc.{Constant, PlutusLanguage, Program, ProtocolMajor, Term}
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CaserBuiltin,
  CekError,
  CekMachine,
  CekReport,
  CekResult,
  MachineParameters,
  PresentBuiltins,
  PresentCekMachine
}
import xyz.kd5ujc.scarab.uplc.cost.{EvaluationContext, ExBudget}
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  FreeVariableError,
  LedgerContext,
  LedgerEval,
  Scope,
  ScriptResult,
  VerboseMode
}

/** The node's evaluation of the recorded script requests ([[RecordedRequests]]) against an
  * evaluation that shares none of its code that #1499 changed or that runs a saturated builtin
  * application: the scope check walked on the applied term at every evaluation ([[Scope.check]]),
  * not the one the program keeps ([[Scope.applied]]), and the machine before #1411 with the
  * builtins as they were then ([[PresentCekMachine]], [[PresentBuiltins]]: each saturated
  * application unlifted as a vector, costed and denoted from what that made). Each request runs
  * within its redeemer's budget, with no limit, and within limits that run out at several points of
  * the evaluation; the two must agree in the scope's verdict, the result, the cause of a failure,
  * the logs and the budget, and the ledger's verdict ([[LedgerEval.evaluateScriptRestricting]]),
  * which reads the kept check, must report that budget and those logs.
  */
class EvaluatorPathsSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")

  private lazy val requests = RecordedRequests.sources.flatMap(_.requests)

  /** What a report says, as terms: the result or the error, the failure's cause, the logs and the
    * budget.
    */
  private final case class Seen(
      result: Either[CekError, Term],
      cause: Option[Term],
      logs: Vector[String],
      spent: ExBudget
  )

  private def seen(report: CekReport): Seen =
    val (result, cause) = report.result match
      case CekResult.Failure(failure) => (Left(failure.error), failure.cause.map(_.term))
      case success: CekResult.Success => (Right(success.term), None)
    Seen(result, cause, report.logs, report.budget.spent)

  /** The ledger's verdict as terms: a success, or a failure with the machine's error and the
    * failure's cause (none for a V3 script that returned something other than `()`).
    */
  private enum Verdict:
    case Succeeded
    case Failed(error: Option[CekError], cause: Option[Term])

  private def verdict(result: ScriptResult): (Verdict, ExBudget, Vector[String]) = result match
    case ScriptResult.Success(spent, logs) => (Verdict.Succeeded, spent, logs)
    case ScriptResult.Failure(EvaluationError.Cek(failure), spent, logs) =>
      (Verdict.Failed(Some(failure.error), failure.cause.map(_.term)), spent, logs)
    case ScriptResult.Failure(EvaluationError.InvalidReturnValue, spent, logs) =>
      (Verdict.Failed(None, None), spent, logs)
    case other => fail(s"not a verdict of the machine: $other")

  /** The limits a request runs within: its redeemer's budget, none, and fractions of what it
    * spends, so that a component runs out at several points.
    */
  private def modes(budget: ExBudget, spent: ExBudget): Vector[BudgetMode] =
    Vector(BudgetMode.Restricting(budget), BudgetMode.Counting) ++
      Vector(1L, 3L, 7L).flatMap { eighths =>
        val cpu = spent.cpu.toLong / 8 * eighths
        val memory = spent.memory.toLong / 8 * eighths
        Vector(
          BudgetMode.Restricting(ExBudget.of(cpu, Long.MaxValue / 4)),
          BudgetMode.Restricting(ExBudget.of(Long.MaxValue / 4, memory))
        )
      }

  test("every recorded request: the node's evaluation and the one before #1499 agree") {
    assert(requests.size >= 250, s"${requests.size} requests")
    val compared = requests.map { recorded =>
      val r = recorded.request
      val id = recorded.id
      val protocol = ProtocolMajor(r.protocolMajor)
      val program: Program = LedgerEval
        .deserialiseScript(r.language, protocol, r.script)
        .fold(e => fail(s"$id: $e"), identity)
      val applied =
        r.args.foldLeft(program.term)((t, a) => Term.app(t, Term.constant(Constant.Data(a))))
      val walked: Either[FreeVariableError, Unit] = Scope.check(applied)
      assertEquals(Scope.applied(program), walked, id)
      val term = LedgerEval
        .mkTermToEvaluate(r.language, protocol, program, r.args)
        .fold(e => fail(s"$id: $e"), identity)
      assertEquals(term, applied, id)
      val context = LedgerContext
        .make(r.language.evaluatedAs, r.costModel)
        ._2
        .fold(e => fail(s"$id: $e"), identity)
      val now: MachineParameters =
        context.at(protocol, program.version).fold(e => fail(s"$id: $e"), identity)
      val costs = EvaluationContext
        .build(r.language.evaluatedAs, r.costModel)
        ._2
        .toOption
        .flatMap(_.at(protocol))
        .getOrElse(fail(s"$id: no costs"))
      val before =
        PresentBuiltins.machine(costs, CaserBuiltin.forProtocol(protocol, program.version))
      val budget = ExBudget.of(r.budget.steps.toLong, r.budget.mem.toLong)
      val spent = CekMachine.run(now, BudgetMode.Counting, term).budget.spent
      modes(budget, spent).foreach { mode =>
        val report = CekMachine.run(now, mode, term)
        assertEquals(seen(report), seen(PresentCekMachine.run(before, mode, term)), s"$id $mode")
      }
      // the ledger's verdict on the request against the one `processLogsAndErrors`
      // (`Eval.hs:306-324`, plutus 1.71.0.0) gives on the report before #1499
      val report = PresentCekMachine.run(before, BudgetMode.Restricting(budget), term)
      val expected: Verdict = report.result match
        case CekResult.Failure(failure) =>
          Verdict.Failed(Some(failure.error), failure.cause.map(_.term))
        case CekResult.SuccessConstant(Constant.Unit) => Verdict.Succeeded
        case _: CekResult.Success =>
          r.language.evaluatedAs match
            case PlutusLanguage.V1 | PlutusLanguage.V2 => Verdict.Succeeded
            case PlutusLanguage.V3 | PlutusLanguage.V4 => Verdict.Failed(None, None)
      val judged = LedgerEval.evaluateScriptRestricting(
        r.language,
        protocol,
        VerboseMode.Verbose,
        context,
        budget,
        program,
        r.args
      )
      assertEquals(verdict(judged), (expected, report.budget.spent, report.logs), id)
      expected match
        case Verdict.Failed(_, _) => 1
        case Verdict.Succeeded    => 0
    }
    // both verdicts are among the compared evaluations at the redeemers' budgets
    assert(compared.sum >= 1 && compared.sum < compared.size, s"${compared.sum} failures")
  }
