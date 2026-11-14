// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import cats.data.{EitherT, Writer}
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The machine at an effect boundary: logs are told, a failure is raised with what it spent. */
final class CekEvaluateSuite extends munit.FunSuite:
  private type Logged[A] = Writer[Vector[String], A]
  private type F[A] = EitherT[Logged, CekFailed, A]
  private val parameters = StubBuiltins.parameters(StubBuiltins.distinctCosts)

  private def evaluate(term: xyz.kd5ujc.scarab.uplc.Term) =
    CekMachine.evaluate[F](parameters, BudgetMode.Counting, term).value.run

  test("a success is returned and its logs told") {
    val traced = app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("hi")), unit)
    val (logs, result) = evaluate(traced)
    assertEquals(logs, Vector("hi"))
    result match
      case Right(CekEvaluated(CekResult.SuccessConstant(c), _)) => assertEquals(c, Constant.Unit)
      case other => fail(s"expected a constant, got $other")
  }

  test("a failure is raised with the budget it spent, its logs told") {
    val (logs, result) = evaluate(app(builtin(DefaultFun.DivideInteger), int(1), int(0)))
    assertEquals(logs, Vector("Cannot divide by zero"))
    result match
      case Left(CekFailed(failure, budget)) =>
        assertEquals(
          failure.error.errorClass,
          xyz.kd5ujc.scarab.uplc.builtin.ErrorClass.Operational
        )
        assertEquals(budget.spent, ExBudget.of(4050, 60))
      case other => fail(s"expected a failure, got $other")
  }
