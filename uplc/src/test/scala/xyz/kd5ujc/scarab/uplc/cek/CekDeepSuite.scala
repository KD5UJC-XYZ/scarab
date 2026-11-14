// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{Constant, SmallStack, Term}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** Terms, stacks of frames, environments and results as deep as a script may make them, on a 256
  * KiB stack (ADR 0011): the machine is a loop, its frames and environments are on the heap, and a
  * discharge is an unfold.
  */
final class CekDeepSuite extends munit.FunSuite:
  private val parameters = StubBuiltins.parameters(StubBuiltins.distinctCosts)
  private val Depth = 200000

  private def counting(term: Term): CekReport =
    CekMachine.run(parameters, BudgetMode.Counting, term)

  test("a deep term: force and delay, many batches of the slippage") {
    val report = SmallStack.run(counting(forceDelay(Depth, unit)))
    assertEquals(report.result, CekResult.SuccessConstant(Constant.Unit))
    // Depth Force (600, 6) and Delay (500, 5) steps, one Constant (100, 1), the start-up (50, 50).
    assertEquals(
      report.budget.spent,
      ExBudget.of(Depth.toLong * 1100 + 150, Depth.toLong * 11 + 51)
    )
  }

  test("a deep stack of frames: the argument of the argument of ...") {
    val term = nest(Depth, int(1))(t => app(lam(v(1)), t))
    assertEquals(
      SmallStack.run(counting(term)).result,
      CekResult.SuccessConstant(Constant.Integer(1))
    )
  }

  test("a deep environment: the outermost variable from under every binder") {
    // [(lam [(lam .. v_n ..) (con k)]) (con n)]: the innermost body names the outermost binder.
    val term = (1 to Depth).foldLeft(v(Depth.toLong))((t, k) => app(lam(t), int(k)))
    assertEquals(
      SmallStack.run(counting(term)).result,
      CekResult.SuccessConstant(Constant.Integer(Depth))
    )
  }

  test("a deep result, discharged under its environment") {
    // [(lam y (lam x (delay .. (delay y)))) (con 5)]: y is replaced under every delay.
    val term = app(lam(lam(nest(Depth, v(2))(delay))), int(5))
    val expected = lam(nest(Depth, int(5))(delay))
    SmallStack.run {
      counting(term).result match
        case success: CekResult.SuccessNonConstant => assertEquals(success.term, expected)
        case other                                 => fail(s"expected a value, got $other")
    }
  }

  test("a deep constr: frames while its fields compute, then a deep value") {
    val term = nest(Depth, unit)(t => constr(0, t))
    SmallStack.run {
      counting(term).result match
        case success: CekResult.SuccessNonConstant => assertEquals(success.term, term)
        case other                                 => fail(s"expected a value, got $other")
    }
  }

  test("a result whose discharge would be exponential is not discharged unless read") {
    // Each layer is a delay of [p p] with p the layer below: the discharged term doubles in size per
    // layer, 2^100 nodes here. The reference's result term is lazy and the ledger never reads it.
    val term = nest(100, unit)(t => app(lam(delay(app(v(1), v(1)))), t))
    counting(term).result match
      case _: CekResult.SuccessNonConstant => ()
      case other                           => fail(s"expected a value, got $other")
  }
