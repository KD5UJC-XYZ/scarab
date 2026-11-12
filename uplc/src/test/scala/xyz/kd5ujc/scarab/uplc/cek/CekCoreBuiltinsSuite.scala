// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, DefaultFun, DefaultUni, SemanticsVariant, Term}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  ErrorClass,
  EvaluationFailure,
  UnliftingCause
}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The machine with the core builtins (`builtin.core`) of variant E and the stub costing: the
  * builtins' failures reach the machine's errors and logs as the reference's do. The costing is a
  * constant per builtin ([[StubBuiltins.costOf]]) until the cost model is in.
  */
final class CekCoreBuiltinsSuite extends munit.FunSuite:
  private val parameters: MachineParameters =
    StubBuiltins
      .core(SemanticsVariant.E)
      .fold(
        e => fail(e.toString),
        MachineParameters(StubBuiltins.distinctCosts, _, CaserBuiltin.WithoutData)
      )

  private def counting(term: Term): CekReport =
    CekMachine.run(parameters, BudgetMode.Counting, term)

  private def errorOf(report: CekReport): CekError = report.result match
    case CekResult.Failure(failure) => failure.error
    case other                      => fail(s"expected a failure, got $other")

  test("the loops compute with the core builtins, at the budget of the stub builtins") {
    // Both sets of builtins share the costing, so the budgets agree when the steps do.
    val stub = StubBuiltins.parameters(StubBuiltins.distinctCosts)
    Vector(
      Terms.countdown(300) -> Constant.Integer(0),
      Terms.constrSum(100) -> Constant.Integer(5050)
    )
      .foreach { (term, expected) =>
        val report = counting(term)
        assertEquals(report.result, CekResult.SuccessConstant(expected))
        assertEquals(report.budget, CekMachine.run(stub, BudgetMode.Counting, term).budget)
      }
  }

  test("a failing denotation logs its message and fails operationally") {
    // [(force (builtin headList)) (con (list integer) [])]: Apply, Force, Builtin, Constant are
    // counted, never spent; headList costs nothing under the stub costing.
    val empty = con(Constant.List.empty(DefaultUni.Integer))
    val report = counting(app(force(builtin(DefaultFun.HeadList)), empty))
    assertEquals(
      errorOf(report),
      CekError.Builtin(DefaultFun.HeadList, BuiltinFailure.Evaluation(EvaluationFailure.EmptyList))
    )
    assertEquals(errorOf(report).errorClass, ErrorClass.Operational)
    assertEquals(report.logs, EvaluationFailure.EmptyList.message.toVector)
    assertEquals(report.budget.spent, ExBudget.of(50, 50))
  }

  test(
    "an integer outside CInteger is an operational unlifting failure that logs and costs nothing"
  ) {
    // `CInteger` arguments of addInteger in variant E: 2^262143 has 262144 bits, one too many.
    val huge = int(BigInt(1) << 262143)
    val report = counting(app(builtin(DefaultFun.AddInteger), huge, int(1)))
    errorOf(report) match
      case CekError.Builtin(DefaultFun.AddInteger, BuiltinFailure.Unlifting(failure)) =>
        assertEquals(failure.errorClass, ErrorClass.Operational)
        assertEquals(
          failure.cause,
          UnliftingCause.OutOfBounds(0, xyz.kd5ujc.scarab.uplc.builtin.UnliftingBound.CInteger)
        )
      case other => fail(s"expected an unlifting failure, got $other")
    assertEquals(report.logs, Vector("Integer out of bounds"))
    assertEquals(report.budget.spent, ExBudget.of(50, 50))
  }

  test("a constant of the wrong type is a structural unlifting failure with no log") {
    val report = counting(ifThenElse(int(1), unit, unit))
    assertEquals(errorOf(report).errorClass, ErrorClass.Structural)
    assertEquals(report.logs, Vector.empty)
  }

  test("trace logs through the core denotation") {
    val report =
      counting(app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("core")), unit))
    assertEquals(report.result, CekResult.SuccessConstant(Constant.Unit))
    assertEquals(report.logs, Vector("core"))
  }
