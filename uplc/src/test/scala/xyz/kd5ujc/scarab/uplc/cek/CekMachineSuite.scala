// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cek

import xyz.kd5ujc.scarab.uplc.{
  Constant,
  DefaultFun,
  DefaultUni,
  PlutusData,
  ProtocolMajor,
  Signature,
  Term,
  Word64
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  ErrorClass,
  EvaluationFailure,
  UnliftingCause,
  UnliftingFailure
}
import cats.Eval
import xyz.kd5ujc.scarab.uplc.cost.{CostStream, ExBudget}
import xyz.kd5ujc.scarab.uplc.cek.Terms.*

/** The machine's transitions, budget and errors on small programs whose budgets are derived by hand
  * from the reference's rules (`Cek/Internal.hs`, `Cek/ExBudgetMode.hs`, plutus 1.70.0.0
  * `507cab8c`), with [[StubBuiltins.distinctCosts]]: start-up (50, 50); per step, in [[StepKind]]
  * order, Constant (100, 1), Variable (200, 2), LamAbs (300, 3), Apply (400, 4), Delay (500, 5),
  * Force (600, 6), Builtin (700, 7), Constr (800, 8), Case (900, 9).
  */
final class CekMachineSuite extends munit.FunSuite:
  private val parameters = StubBuiltins.parameters(StubBuiltins.distinctCosts)

  private def counting(term: Term, p: MachineParameters = parameters): CekReport =
    CekMachine.run(p, BudgetMode.Counting, term)

  private def restricting(
      limit: ExBudget,
      term: Term,
      p: MachineParameters = parameters
  ): CekReport =
    CekMachine.run(p, BudgetMode.Restricting(limit), term)

  private def constantOf(report: CekReport): Constant = report.result match
    case CekResult.SuccessConstant(c) => c
    case other                        => fail(s"expected a constant, got $other")

  private def errorOf(report: CekReport): CekError = report.result match
    case CekResult.Failure(failure) => failure.error
    case other                      => fail(s"expected a failure, got $other")

  private def termOf(report: CekReport): Term = report.result match
    case success: CekResult.Success => success.term
    case other                      => fail(s"expected a value, got $other")

  test("an application of a lambda: four steps, start-up first") {
    // [(lam x x) (con integer 42)]: Apply, LamAbs, Constant, Variable.
    // CPU 50 + 400 + 300 + 100 + 200 = 1050; memory 50 + 4 + 3 + 1 + 2 = 60.
    val report = counting(app(lam(v(1)), int(42)))
    assertEquals(constantOf(report), Constant.Integer(42))
    assertEquals(report.budget, BudgetState.Counted(ExBudget.of(1050, 60)))
    assertEquals(report.logs, Vector.empty)
  }

  test("a builtin takes its forces, then its arguments, and runs when saturated") {
    // [(force (builtin ifThenElse)) (con bool True) (con integer 1) (con integer 2)]:
    // Apply x3 (1200, 12), Force (600, 6), Builtin (700, 7), Constant x3 (300, 3), the
    // stub ifThenElse (3000, 1) and start-up (50, 50): (5850, 79).
    val report = counting(app(force(builtin(DefaultFun.IfThenElse)), bool(true), int(1), int(2)))
    assertEquals(constantOf(report), Constant.Integer(1))
    assertEquals(report.budget.spent, ExBudget.of(5850, 79))
  }

  test("a partial application is a value, discharged to its spine") {
    val partial = app(force(builtin(DefaultFun.IfThenElse)), bool(true), int(1))
    assertEquals(termOf(counting(partial)), partial)
    val forced = force(builtin(DefaultFun.IfThenElse))
    assertEquals(termOf(counting(forced)), forced)
    // An argument is discharged in the spine: (lam x x) becomes itself, its environment empty.
    val withLambda = app(force(builtin(DefaultFun.IfThenElse)), bool(true), lam(v(1)))
    assertEquals(termOf(counting(withLambda)), withLambda)
  }

  test("a discharged value has the values of its environment in place of their variables") {
    // [(lam y (lam x [x y])) (con integer 5)] is the closure (lam x [x y]) with y = 5.
    val report = counting(app(lam(lam(app(v(1), v(2)))), int(5)))
    assertEquals(termOf(report), lam(app(v(1), int(5))))
    // A variable that the environment does not bind stays as it is: under (delay ..) in an
    // environment of one value, index 2 is free (only the machine reached it unevaluated).
    val open = counting(app(lam(delay(app(v(1), v(2)))), int(7)))
    assertEquals(termOf(open), delay(app(int(7), v(2))))
  }

  test("an argument that does not unlift costs nothing and fails, structurally") {
    // [(builtin addInteger) (con integer 1) (con bool True)]: the five steps stay counted and are
    // never spent; the application costs nothing (`builtinRuntimeFailure`, Runtime.hs:110-111),
    // so only the start-up is spent.
    val report = counting(app(builtin(DefaultFun.AddInteger), int(1), bool(true)))
    assertEquals(
      errorOf(report),
      CekError.Builtin(
        DefaultFun.AddInteger,
        BuiltinFailure.Unlifting(
          UnliftingFailure(
            ErrorClass.Structural,
            UnliftingCause.WrongType(1, Signature.Type.Integer, DefaultUni.Bool)
          )
        )
      )
    )
    assertEquals(errorOf(report).errorClass, ErrorClass.Structural)
    assertEquals(report.budget.spent, ExBudget.of(50, 50))
    // Restricted to exactly the start-up, it is still the unlifting that fails, not the budget.
    val restricted =
      restricting(ExBudget.of(50, 50), app(builtin(DefaultFun.AddInteger), int(1), bool(true)))
    assertEquals(errorOf(restricted).errorClass, ErrorClass.Structural)
    assertEquals(restricted.budget, BudgetState.Restricted(ExBudget.of(50, 50), ExBudget.zero))
  }

  test("a builtin's cost is spent element by element before its denotation runs") {
    // [(force (builtin trace)) (con string "hi") (con integer 1)]: steps Apply x2, Force, Builtin,
    // Constant x2 are counted, not spent (six, below the slippage). Trace's stream is (1000, 10)
    // then (5000, 10).
    val program = app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("hi")), int(1))
    // CPU 6049: start-up leaves 5999, the first element 4999, the second -1. The denotation never
    // runs, so nothing is logged.
    val short = restricting(ExBudget.of(6049, 1000), program)
    assertEquals(errorOf(short), CekError.OutOfBudget(ExBudget.of(-1, 1000 - 50 - 20)))
    assertEquals(short.logs, Vector.empty, "the denotation must not run")
    // CPU 6050: the cost is covered and the log is emitted; then the final flush spends the steps
    // kind by kind: Constant x2 (200, 2) takes the CPU to -200, so the flush stops there.
    val flush = restricting(ExBudget.of(6050, 1000), program)
    assertEquals(errorOf(flush), CekError.OutOfBudget(ExBudget.of(-200, 1000 - 50 - 20 - 2)))
    assertEquals(flush.logs, Vector("hi"), "the log is emitted before the budget runs out")
  }

  test("an overspent budget forces no more of a builtin's cost stream") {
    // addInteger costing (1000, 1) without end: start-up leaves 10500 CPU, ten elements 500, the
    // eleventh -500. The machine stops there; counting, it would never stop.
    val endless = parameters.copy(builtins =
      StubBuiltins.withCost(DefaultFun.AddInteger, () => StubBuiltins.endless(ExBudget.of(1000, 1)))
    )
    val program = app(builtin(DefaultFun.AddInteger), int(1), int(2))
    val report = restricting(ExBudget.of(10550, 1000), program, endless)
    assertEquals(errorOf(report), CekError.OutOfBudget(ExBudget.of(-500, 1000 - 50 - 11)))
    // A rest that fails when forced: the first element overspends, so it is never forced.
    val guarded = parameters.copy(builtins =
      StubBuiltins.withCost(
        DefaultFun.AddInteger,
        () =>
          CostStream.Cons(
            ExBudget.of(6000, 1),
            Eval.later(fail("the rest of the stream was forced"))
          )
      )
    )
    val stopped = restricting(ExBudget.of(5000, 1000), program, guarded)
    assertEquals(
      errorOf(stopped),
      CekError.OutOfBudget(ExBudget.of(5000 - 50 - 6000, 1000 - 50 - 1))
    )
  }

  test("a flush stops at the kind that overspends and spends no later kind") {
    // As above with 1500 CPU left after the builtin: Constant x2 leaves 1300, Variable and LamAbs
    // (none) 1300, Apply x2 (800) 500, Delay (none) 500, Force (600) -100: the flush stops at
    // Force, so the Builtin step (700, 7) is never spent. Memory: 1000 - 50 - 20 - 2 - 8 - 6.
    val program = app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("hi")), int(1))
    val report = restricting(ExBudget.of(6050 + 1500, 1000), program)
    assertEquals(
      errorOf(report),
      CekError.OutOfBudget(ExBudget.of(-100, 1000 - 50 - 20 - 2 - 8 - 6))
    )
    // With 2300 left, every kind is covered and nothing is left.
    val exact = restricting(ExBudget.of(6050 + 2300, 1000), program)
    assertEquals(exact.result, CekResult.SuccessConstant(Constant.Integer(1)))
    assertEquals(exact.budget.spent, ExBudget.of(8350, 50 + 20 + 23))
  }

  test("steps are spent in batches of the slippage, and an error spends none of the batch") {
    // n layers of (force (delay ..)) are 2n steps: Force, Delay, Force, Delay, ...
    // Around `error`, 99 layers are 198 steps, below the slippage: nothing but the start-up is
    // spent, so a budget of only the start-up sees the error, not an overspent budget.
    val belowSlippage = restricting(ExBudget.of(50, 50), forceDelay(99, error))
    assertEquals(errorOf(belowSlippage), CekError.ErrorTerm)
    assertEquals(errorOf(belowSlippage).errorClass, ErrorClass.Operational)
    assertEquals(belowSlippage.budget.spent, ExBudget.of(50, 50))
    // 100 layers are 200 steps: the 200th (the last Delay) spends the batch before it acts. Delay
    // comes before Force in StepKind order: 100 Delays (50000, 500) overspend first.
    val atSlippage = restricting(ExBudget.of(50, 50), forceDelay(100, error))
    assertEquals(errorOf(atSlippage), CekError.OutOfBudget(ExBudget.of(-50000, -500)))
    // Counting, the error reports the batch spent at step 200 and nothing after it: 100 layers
    // and one more are 202 steps, the last two counted and never spent.
    val counted = counting(forceDelay(101, error))
    assertEquals(errorOf(counted), CekError.ErrorTerm)
    assertEquals(
      counted.budget.spent,
      ExBudget.of(50 + 100 * 500 + 100 * 600, 50 + 100 * 5 + 100 * 6)
    )
  }

  test("counting and restricting agree on the budget of a program that fits") {
    // 150 layers around (con unit ()): 150 Force (90000, 900), 150 Delay (75000, 750), one
    // Constant (100, 1) and the start-up: (165150, 1701), in a batch at step 200 and one at the end.
    val program = forceDelay(150, unit)
    val spent = ExBudget.of(165150, 1701)
    assertEquals(counting(program).budget, BudgetState.Counted(spent))
    val exact = restricting(spent, program)
    assertEquals(exact.result, CekResult.SuccessConstant(Constant.Unit))
    assertEquals(exact.budget, BudgetState.Restricted(spent, ExBudget.zero))
    // One CPU unit less: the batch at step 200 (100 Delay, 100 Force) fits, leaving 55099 CPU and
    // 551 memory; the final batch spends Constant (100, 1), Delay x50 (25000, 250), then Force x50
    // (30000, 300) overspends by one.
    val short = restricting(ExBudget.of(165149, 1701), program)
    assertEquals(errorOf(short), CekError.OutOfBudget(ExBudget.of(-1, 0)))
    assertEquals(short.budget.spent, ExBudget.of(165150, 1701))
  }

  test("the Case counter is spent and reset with the others") {
    // 150 nested (case (constr 0 []) [..]) around (con unit ()): 150 Case (135000, 1350), 150
    // Constr (120000, 1200), one Constant (100, 1) and the start-up: (255150, 2601). The batch at
    // step 200 has 100 of each; the final one 50 of each and the Constant.
    val program = nest(150, unit)(t => caseOf(constr(0), t))
    val spent = ExBudget.of(255150, 2601)
    assertEquals(counting(program).budget, BudgetState.Counted(spent))
    assertEquals(restricting(spent, program).result, CekResult.SuccessConstant(Constant.Unit))
    // One CPU unit less: the final batch spends Constant, then Constr x50, and Case x50, the last
    // kind, overspends by one.
    val short = restricting(ExBudget.of(255149, 2601), program)
    assertEquals(errorOf(short), CekError.OutOfBudget(ExBudget.of(-1, 0)))
  }

  test("the start-up cost goes through the spender and can overspend") {
    val report = restricting(ExBudget.of(49, 50), unit)
    assertEquals(errorOf(report), CekError.OutOfBudget(ExBudget.of(-1, 0)))
    report.result match
      case CekResult.Failure(failure) => assertEquals(failure.cause, None, "no cause")
      case other                      => fail(s"expected a failure, got $other")
  }

  test("case on a constr applies the branch of its tag to its fields, in order") {
    // case (constr 1 [7, 8]) [(lam a (lam b a)), (lam a (lam b b))]: Case, Constr, Constant x2,
    // LamAbs x2, Variable: CPU 50 + 900 + 800 + 200 + 600 + 200, memory 50 + 9 + 8 + 2 + 6 + 2.
    val program = caseOf(constr(1, int(7), int(8)), lam(lam(v(2))), lam(lam(v(1))))
    val report = counting(program)
    assertEquals(constantOf(report), Constant.Integer(8))
    assertEquals(report.budget.spent, ExBudget.of(2750, 77))
    assertEquals(
      constantOf(counting(caseOf(constr(0, int(7), int(8)), lam(lam(v(2))), lam(lam(v(1)))))),
      Constant.Integer(7)
    )
    // A constr's value is discharged with its fields.
    assertEquals(termOf(counting(constr(3, int(1), lam(v(1))))), constr(3, int(1), lam(v(1))))
    assertEquals(termOf(counting(constr(0))), constr(0))
  }

  test("a builtin as a branch takes the fields as its arguments") {
    // case (constr 0 [1, 2]) [(builtin addInteger)]: Case (900, 9), Constr (800, 8), Constant x2
    // (200, 2), Builtin (700, 7); the fields are applied from the frame without a step; the stub
    // addInteger (1000, 10) and the start-up (50, 50): (3650, 86).
    val report = counting(caseOf(constr(0, int(1), int(2)), builtin(DefaultFun.AddInteger)))
    assertEquals(constantOf(report), Constant.Integer(3))
    assertEquals(report.budget.spent, ExBudget.of(3650, 86))
    // A builtin that expects a force refuses a field: structural.
    val unforced = counting(caseOf(constr(0, bool(true)), builtin(DefaultFun.IfThenElse)))
    assertEquals(errorOf(unforced), CekError.UnexpectedBuiltinTermArgument)
  }

  test("case on a constr without a branch for its tag, or on a function, is structural") {
    val missing = counting(caseOf(constr(2), int(0), int(1)))
    assertEquals(errorOf(missing), CekError.MissingCaseBranch(Word64.fromBits(2)))
    assertEquals(errorOf(missing).errorClass, ErrorClass.Structural)
    // A tag of 2^64 - 1 is past every branch, not a negative index.
    assertEquals(
      errorOf(counting(caseOf(constr(-1), int(0)))),
      CekError.MissingCaseBranch(Word64.MaxValue)
    )
    val lambda = counting(caseOf(lam(v(1)), int(0)))
    assertEquals(errorOf(lambda), CekError.NonConstrScrutinized)
    assertEquals(errorOf(lambda).errorClass, ErrorClass.Structural)
  }

  test("case on a constant goes to the caser, which refuses every constant before protocol 11") {
    val before = StubBuiltins.parameters(
      StubBuiltins.distinctCosts,
      CaserBuiltin.forProtocol(ProtocolMajor(10))
    )
    val refused = counting(caseOf(int(0), int(10)), before)
    assertEquals(
      errorOf(refused),
      CekError.CaseBuiltin(CaseBuiltinError.Unavailable(ProtocolMajor(10)))
    )
    assertEquals(errorOf(refused).errorClass, ErrorClass.Operational)
    assertEquals(CaserBuiltin.forProtocol(ProtocolMajor(11)), CaserBuiltin.WithoutData)
    assertEquals(CaserBuiltin.forProtocol(ProtocolMajor(12)), CaserBuiltin.WithData)
  }

  test("the caser selects by integer, bool, unit, list and pair") {
    assertEquals(constantOf(counting(caseOf(int(1), int(10), int(11)))), Constant.Integer(11))
    assertEquals(
      errorOf(counting(caseOf(int(2), int(10), int(11)))),
      CekError.CaseBuiltin(CaseBuiltinError.OutOfBounds(Constant.Integer(2), 2))
    )
    assertEquals(
      errorOf(counting(caseOf(int(-1), int(10)))),
      CekError.CaseBuiltin(CaseBuiltinError.OutOfBounds(Constant.Integer(-1), 1))
    )
    // One branch is enough for False, not for True (`Universe.hs:979-985`).
    assertEquals(constantOf(counting(caseOf(bool(false), int(10)))), Constant.Integer(10))
    assertEquals(constantOf(counting(caseOf(bool(true), int(10), int(11)))), Constant.Integer(11))
    assertEquals(
      errorOf(counting(caseOf(bool(true), int(10)))),
      CekError.CaseBuiltin(CaseBuiltinError.OutOfBounds(Constant.Bool(true), 1))
    )
    assertEquals(constantOf(counting(caseOf(unit, int(10)))), Constant.Integer(10))
    val list = Constant.List
      .of(DefaultUni.Integer, Vector(Constant.Integer(4), Constant.Integer(5)))
      .fold(e => fail(e.toString), identity)
    // A non-empty list: the first branch, applied to the head and the tail.
    assertEquals(constantOf(counting(caseOf(con(list), lam(lam(v(2)))))), Constant.Integer(4))
    assertEquals(constantOf(counting(caseOf(con(list), lam(lam(v(1))), int(0)))), list.drop(1))
    val empty = Constant.List.empty(DefaultUni.Integer)
    assertEquals(
      constantOf(counting(caseOf(con(empty), lam(lam(v(1))), int(0)))),
      Constant.Integer(0)
    )
    assertEquals(
      errorOf(counting(caseOf(con(empty), lam(lam(v(1)))))),
      CekError.CaseBuiltin(CaseBuiltinError.EmptyList)
    )
    val pair = Constant.Pair(Constant.Integer(1), Constant.Bool(true))
    assertEquals(constantOf(counting(caseOf(con(pair), lam(lam(v(1)))))), Constant.Bool(true))
    assertEquals(
      errorOf(counting(caseOf(con(StubBuiltins.string("s")), int(0)))),
      CekError.CaseBuiltin(CaseBuiltinError.UnsupportedType(DefaultUni.String))
    )
  }

  test("case on Data needs the caser with Data, and a Constr") {
    val d = PlutusData.Constr(1, Vector(PlutusData.I(9)))
    val program = caseOf(data(d), int(0), lam(v(1)))
    assertEquals(errorOf(counting(program)), CekError.CaseBuiltin(CaseBuiltinError.DataUnsupported))
    val withData = StubBuiltins.parameters(StubBuiltins.distinctCosts, CaserBuiltin.WithData)
    val fields = Constant.List
      .of(DefaultUni.Data, Vector(Constant.Data(PlutusData.I(9))))
      .fold(e => fail(e.toString), identity)
    assertEquals(constantOf(counting(program, withData)), fields)
    assertEquals(
      errorOf(counting(caseOf(data(PlutusData.I(1)), int(0)), withData)),
      CekError.CaseBuiltin(CaseBuiltinError.DataNotConstr)
    )
    assertEquals(
      errorOf(counting(caseOf(data(d), int(0)), withData)),
      CekError.CaseBuiltin(CaseBuiltinError.OutOfBounds(Constant.Data(d), 1))
    )
  }

  test("the machine's errors and their classes") {
    def check(term: Term, expected: CekError, errorClass: ErrorClass)(using munit.Location): Unit =
      val error = errorOf(counting(term))
      assertEquals(error, expected)
      assertEquals(error.errorClass, errorClass)
    check(app(int(1), int(2)), CekError.NonFunctionalApplication, ErrorClass.Structural)
    check(force(int(1)), CekError.NonPolymorphicInstantiation, ErrorClass.Structural)
    check(force(lam(v(1))), CekError.NonPolymorphicInstantiation, ErrorClass.Structural)
    check(
      v(1),
      CekError.OpenTermEvaluated(xyz.kd5ujc.scarab.uplc.DeBruijn(Word64.fromBits(1))),
      ErrorClass.Structural
    )
    check(
      app(lam(v(0)), unit),
      CekError.OpenTermEvaluated(xyz.kd5ujc.scarab.uplc.DeBruijn(Word64.Zero)),
      ErrorClass.Structural
    )
    check(
      app(lam(v(2)), unit),
      CekError.OpenTermEvaluated(xyz.kd5ujc.scarab.uplc.DeBruijn(Word64.fromBits(2))),
      ErrorClass.Structural
    )
    check(
      app(builtin(DefaultFun.IfThenElse), bool(true)),
      CekError.UnexpectedBuiltinTermArgument,
      ErrorClass.Structural
    )
    check(
      force(builtin(DefaultFun.AddInteger)),
      CekError.BuiltinTermArgumentExpected,
      ErrorClass.Structural
    )
    check(
      force(force(builtin(DefaultFun.IfThenElse))),
      CekError.BuiltinTermArgumentExpected,
      ErrorClass.Structural
    )
    check(error, CekError.ErrorTerm, ErrorClass.Operational)
    check(
      app(builtin(DefaultFun.AddInteger), lam(v(1)), int(1)),
      CekError.Builtin(
        DefaultFun.AddInteger,
        BuiltinFailure.Unlifting(
          UnliftingFailure(ErrorClass.Structural, UnliftingCause.NotAConstant(0))
        )
      ),
      ErrorClass.Structural
    )
    check(
      app(builtin(DefaultFun.DivideInteger), int(1), int(0)),
      CekError.Builtin(
        DefaultFun.DivideInteger,
        BuiltinFailure.Evaluation(EvaluationFailure.DivisionByZero)
      ),
      ErrorClass.Operational
    )
  }

  test("an operational unlifting failure is operational, and costs nothing") {
    // An unlifting that refuses every argument as out of its bounds, as the variants' bounds do
    // (`Int`, `Word64`, ...): `CekEvaluationFailure`, not `UnliftingMachineError`
    // (`builtinErrorToEvaluationError`, `Cek/Internal.hs:519-523`).
    val outOfBounds = UnliftingFailure(
      ErrorClass.Operational,
      UnliftingCause.OutOfBounds(0, xyz.kd5ujc.scarab.uplc.builtin.UnliftingBound.Int64)
    )
    val refusing = new xyz.kd5ujc.scarab.uplc.builtin.Unlifting:
      def apply[V](function: DefaultFun, arguments: Vector[V])(using
          xyz.kd5ujc.scarab.uplc.builtin.MachineValue[V]
      ) = Left(outOfBounds)
    val p = parameters.copy(builtins = StubBuiltins.builtins.copy(unlifting = refusing))
    val report = counting(app(builtin(DefaultFun.AddInteger), int(1), int(2)), p)
    assertEquals(
      errorOf(report),
      CekError.Builtin(DefaultFun.AddInteger, BuiltinFailure.Unlifting(outOfBounds))
    )
    assertEquals(errorOf(report).errorClass, ErrorClass.Operational)
    assertEquals(report.budget.spent, ExBudget.of(50, 50))
  }

  test("an error's cause is the reference's term, discharged when read") {
    def causeOf(report: CekReport): Option[Term] = report.result match
      case CekResult.Failure(failure) => failure.cause.map(_.term)
      case _                          => None
    assertEquals(causeOf(counting(app(int(1), int(2)))), Some(int(1)))
    assertEquals(causeOf(counting(error)), Some(error))
    assertEquals(
      causeOf(counting(app(builtin(DefaultFun.IfThenElse), bool(true)))),
      Some(app(builtin(DefaultFun.IfThenElse), bool(true)))
    )
    assertEquals(
      causeOf(counting(force(builtin(DefaultFun.AddInteger)))),
      Some(force(builtin(DefaultFun.AddInteger)))
    )
    val division = app(builtin(DefaultFun.DivideInteger), int(1), int(0))
    assertEquals(causeOf(counting(division)), Some(division))
  }

  test("a failing builtin's logs are emitted before it fails") {
    // The division costs (4000, 10) and emits its message (the stub puts it in its logs).
    val report = counting(app(builtin(DefaultFun.DivideInteger), int(1), int(0)))
    assertEquals(report.logs, Vector("Cannot divide by zero"))
    // Apply x2, Builtin, Constant x2 are counted and not spent: start-up and the builtin only.
    assertEquals(report.budget.spent, ExBudget.of(4050, 60))
  }

  test("trace logs in order") {
    val traced = app(
      force(builtin(DefaultFun.Trace)),
      con(StubBuiltins.string("one")),
      app(force(builtin(DefaultFun.Trace)), con(StubBuiltins.string("two")), unit)
    )
    // The argument is evaluated first: "two" is logged before "one".
    assertEquals(counting(traced).logs, Vector("two", "one"))
  }

  test("the loops of the throughput tool compute what they should") {
    val countdown = counting(Terms.countdown(100), StubBuiltins.parameters(StubBuiltins.chainCosts))
    assertEquals(constantOf(countdown), Constant.Integer(0))
    val sum = counting(Terms.constrSum(100), StubBuiltins.parameters(StubBuiltins.chainCosts))
    assertEquals(constantOf(sum), Constant.Integer(5050))
  }
