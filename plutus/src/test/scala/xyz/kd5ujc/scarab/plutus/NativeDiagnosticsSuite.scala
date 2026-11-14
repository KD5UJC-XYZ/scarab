// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data
import scalus.uplc.{Constant, DefaultFun, NamedDeBruijn, Program, Term as UplcTerm}
import scalus.uplc.eval.{
  BuiltinError,
  BuiltinTermArgumentExpectedMachineError,
  CekValue,
  EvaluationFailure,
  InvalidReturnValue,
  MachineError,
  MissingCaseBranch,
  NonConstrScrutinized,
  NonFunctionalApplicationMachineError,
  NonPolymorphicInstantiationMachineError,
  UnexpectedBuiltinTermArgumentMachineError
}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.FlatProgram as F
import xyz.kd5ujc.scarab.plutus.ReferenceDiagnostic as N

/** The typed native error of each refusal the production evaluator reaches, against the error site
  * and cause of the reference's machine (`CekInternal.hs`, Plutus 507cab8c). Each expected value
  * follows from that source: no expectation is taken from Scalus's own output or messages.
  */
class NativeDiagnosticsSuite extends FunSuite:
  private val budget = ExUnits(BigInt(14000000), BigInt(10000000000L))
  private val v110 = (1, 1, 0)

  private def evaluate(script: Bytes, pv: Int = 11, units: ExUnits = budget): EvalResult =
    ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        pv,
        script,
        Vector.empty,
        PlutusConformanceSuite.costModel,
        units
      )
    )

  private def parsed(text: String): Bytes =
    Bytes.fromArray(Program.parseUplc(text).fold(e => fail(e.toString), identity).cborEncoded)

  private def program(body: String, pv: Int = 11): EvalResult =
    evaluate(parsed(s"(program 1.1.0 $body)"), pv)

  private def cek(error: N.CekError, cause: Option[N.Term]): N.EvaluationError =
    N.EvaluationError.Cek(N.ErrorWithCause(error, cause))

  private def structural(error: N.MachineError, cause: N.Term): N.EvaluationError =
    cek(N.CekError.Structural(error), Some(cause))

  private def failed(result: EvalResult): N.EvaluationError = result match
    case EvalResult.Failure(EvalFailure.Refused(error, _), _, _) => error
    case other                                                   => fail(s"not a refusal: $other")

  private def provider(result: EvalResult): Option[Throwable] = result match
    case EvalResult.Failure(EvalFailure.Refused(_, p), _, _)    => p
    case EvalResult.Failure(EvalFailure.Unresolved(_, p), _, _) => p
    case other                                                  => fail(s"not a refusal: $other")

  private def int(n: BigInt): N.Term = N.Term.Constant(N.Constant.Integer(n))
  private val unitConstant: N.Term = N.Term.Constant(N.Constant.UnitValue)

  test("`error` is an operational EvaluationFailure caused by the error term") {
    val result = program("(error)")
    assertEquals(failed(result), Refusals.explicitError)
    assert(provider(result).exists(_.isInstanceOf[EvaluationFailure]))
  }

  test("a failed builtin is an operational EvaluationFailure caused by the application term") {
    val result = program("[(builtin divideInteger) (con integer 1) (con integer 0)]")
    assertEquals(
      failed(result),
      cek(
        N.CekError.Operational(N.CekUserError.EvaluationFailure),
        Some(N.Term.Apply(N.Term.Apply(N.Term.Builtin(DefaultFun.DivideInteger), int(1)), int(0)))
      )
    )
    assert(provider(result).exists(_.isInstanceOf[BuiltinError]))
  }

  test("a data destructor given another data constructor fails operationally") {
    val result = program("[(builtin unIData) (con data (B #00))]")
    assertEquals(
      failed(result),
      cek(
        N.CekError.Operational(N.CekUserError.EvaluationFailure),
        Some(
          N.Term.Apply(
            N.Term.Builtin(DefaultFun.UnIData),
            N.Term.Constant(N.Constant.Data(PlutusData.B(Bytes.fromArray(Array(0.toByte)))))
          )
        )
      )
    )
  }

  test("a builtin type mismatch keeps its verdict and awaits its native unlifting text") {
    program("[(builtin addInteger) (con bytestring #00) (con integer 1)]") match
      case EvalResult.Failure(EvalFailure.Unresolved(_, Some(_)), _, _) => ()
      case other => fail(s"expected a proven refusal awaiting its text, got $other")
  }

  test("applying a non-function is caused by the discharged function value") {
    val result = program("[(con integer 1) (con integer 2)]")
    assertEquals(failed(result), structural(N.MachineError.NonFunctionalApplication, int(1)))
    assert(provider(result).exists(_.isInstanceOf[NonFunctionalApplicationMachineError]))
  }

  test("forcing a closure discharges its environment into the cause") {
    // `goValEnv`: the body's free `x` is the environment's value, the binder index is 0
    val result = program("[(lam x (force (lam y x))) (con integer 5)]")
    assertEquals(
      failed(result),
      structural(N.MachineError.NonPolymorphicInstantiation, N.Term.Lam("i", 0, int(5)))
    )
    assert(provider(result).exists(_.isInstanceOf[NonPolymorphicInstantiationMachineError]))
  }

  test("a builtin forced or applied out of turn is caused by the builtin's application term") {
    val forced = program("(force (builtin addInteger))")
    assertEquals(
      failed(forced),
      structural(
        N.MachineError.BuiltinTermArgumentExpected,
        N.Term.Force(N.Term.Builtin(DefaultFun.AddInteger))
      )
    )
    assert(provider(forced).exists(_.isInstanceOf[BuiltinTermArgumentExpectedMachineError]))
    val applied = program("[(builtin ifThenElse) (con bool True)]")
    assertEquals(
      failed(applied),
      structural(
        N.MachineError.UnexpectedBuiltinTermArgument,
        N.Term.Apply(N.Term.Builtin(DefaultFun.IfThenElse), N.Term.Constant(N.Constant.Bool(true)))
      )
    )
    assert(provider(applied).exists(_.isInstanceOf[UnexpectedBuiltinTermArgumentMachineError]))
  }

  test("a missing case branch is caused by the discharged scrutinee and keeps its tag") {
    val result = program("(case (constr 3 (con integer 1)) (con unit ()))")
    assertEquals(
      failed(result),
      structural(N.MachineError.MissingCaseBranch(3), N.Term.Constr(3, Vector(int(1))))
    )
    assert(provider(result).exists(_.isInstanceOf[MissingCaseBranch]))
  }

  test("a closure scrutinee is NonConstrScrutinized; a constant one goes to the caser") {
    assertEquals(
      failed(program("(case (lam x x) (con unit ()))")),
      structural(N.MachineError.NonConstrScrutinized, N.Term.Lam("i", 0, N.Term.Var("i", 1)))
    )
    // before van Rossem `unavailableCaserBuiltin` refuses it: `CekCaseBuiltinError`, text pending
    program("(case (con integer 0) (con unit ()))", pv = 10) match
      case EvalResult.Failure(
            EvalFailure.Unresolved(DiagnosticGap.CaseBuiltinText(Some(scrutinee)), Some(p)),
            _,
            _
          ) =>
        assertEquals(scrutinee, int(0))
        assert(p.isInstanceOf[NonConstrScrutinized])
      case other => fail(s"expected the caser's refusal, got $other")
  }

  test("a non-unit V3 result is InvalidReturnValue") {
    val result = program("(con integer 1)")
    assertEquals(failed(result), N.EvaluationError.InvalidReturnValue)
    assert(provider(result).exists(_.isInstanceOf[InvalidReturnValue]))
  }

  test("an exhausted budget reports the remainder after the overspending charge, uncaused") {
    evaluate(parsed("(program 1.1.0 (con unit ()))"), units = ExUnits(0, 0)) match
      case EvalResult.Failure(EvalFailure.Refused(error, Some(_)), used, _) =>
        // the startup charge overspends a zero budget: what remains is minus what was spent
        assert(used.steps > 0 && used.mem > 0, used)
        assertEquals(
          error,
          cek(
            N.CekError.Operational(N.CekUserError.OutOfBudget(N.Budget(-used.steps, -used.mem))),
            None
          )
        )
      case other => fail(s"expected a budget refusal, got $other")
  }

  test("distinct wide indices under constr keep their Word64 values beside substituted ones") {
    val first = (BigInt(1) << 32) + 2
    val second = BigInt(1) << 40
    // `[(lam x (force (constr 0 (delay [[w1 x] w2])))) ()]`: the scope check skips the field, and
    // forcing the constr discharges the delay under `x = ()`
    val term = F.Ap(
      F.Lam(
        F.Force(
          F.Constr(0, Vector(F.Delay(F.Ap(F.Ap(F.Var(first), F.Var(1)), F.Var(second)))))
        )
      ),
      F.unit
    )
    val result = evaluate(F.script(v110, term))
    assertEquals(
      failed(result),
      structural(
        N.MachineError.NonPolymorphicInstantiation,
        N.Term.Constr(
          0,
          Vector(
            N.Term.Delay(
              N.Term.Apply(
                N.Term.Apply(N.Term.Var("i", first), unitConstant),
                N.Term.Var("i", second)
              )
            )
          )
        )
      )
    )
  }

  test("an evaluated index 0 is an open term caused by that variable") {
    val result = evaluate(F.script(v110, F.Ap(F.Lam(F.unit), F.Constr(0, Vector(F.Var(0))))))
    assertEquals(
      failed(result),
      structural(N.MachineError.OpenTermEvaluated, N.Term.Var("i", 0))
    )
    assert(provider(result).exists(_.getClass == classOf[MachineError]))
  }

  test("admission refusals: ledger language first, remainder bytes, then the decoder's reason") {
    val valid = parsed("(program 1.1.0 (con unit ()))")
    val language = EvalFailure.Refused(
      N.EvaluationError.Codec(
        N.ScriptDecodeError.LedgerLanguageNotAvailable(PlutusLanguage.V3, 9, 8)
      ),
      None
    )
    def reason(r: EvalResult) = r match
      case EvalResult.Failure(f, _, _) => f
      case other                       => fail(s"not a failure: $other")
    val garbage = Bytes(Vector(0x43, 0x01, 0x02, 0x03).map(_.toByte))
    // Scalus builds no machine for V3 before protocol 9: the evaluator supplies no verdict there,
    // and that precedence is kept (a backend limit is never turned into a refusal)
    reason(evaluate(valid, pv = 8)) match
      case EvalFailure.Unsupported(BackendFailure.Defect(_)) => ()
      case other => fail(s"expected the backend's no-verdict, got $other")
    // `deserialiseScript` checks the language before it decodes, whatever the bytes hold
    for problem <- Vector(
        ScriptProblem.Availability(
          PlutusAvailability.Failure.LanguageUnavailable(PlutusLanguage.V3, 8)
        ),
        ScriptProblem.Flat(FlatScan.Failure.Truncated),
        ScriptProblem.TrailingBytes(1)
      )
    do
      assertEquals(
        NativeDiagnostics.admission(problem, PlutusLanguage.V3, 8, Bytes.empty),
        language
      )
    assertEquals(
      reason(evaluate(valid ++ Bytes(Vector(0x00.toByte, 0x01.toByte)))),
      EvalFailure.Refused(
        N.EvaluationError.Codec(N.ScriptDecodeError.Remainder(Bytes(Vector(0, 1).map(_.toByte)))),
        None
      )
    )
    reason(evaluate(garbage)) match
      case EvalFailure.Unresolved(DiagnosticGap.DecoderReason(ScriptProblem.Flat(_)), None) => ()
      case other => fail(s"expected the decoder's typed cause, got $other")
  }

  test("the patched error classes keep their released constructors beside the captured fields") {
    import scala.collection.immutable.Seq as ISeq
    import scalus.cardano.ledger.Word64
    import scalus.utils.ScalusSourcePos
    import scalus.uplc.eval.OpenTermEvaluatedMachineError
    val cekValue = classOf[CekValue]
    val env = classOf[ISeq[?]]
    val pos = classOf[ScalusSourcePos]
    val released = Vector(
      classOf[NonPolymorphicInstantiationMachineError] -> Vector(cekValue, env, pos),
      classOf[NonFunctionalApplicationMachineError] -> Vector(cekValue, env, pos, classOf[Seq[?]]),
      classOf[OpenTermEvaluatedMachineError] -> Vector(classOf[NamedDeBruijn], env, pos),
      classOf[BuiltinTermArgumentExpectedMachineError] -> Vector(classOf[UplcTerm], env, pos),
      classOf[UnexpectedBuiltinTermArgumentMachineError] -> Vector(classOf[UplcTerm], env, pos),
      classOf[MissingCaseBranch] -> Vector(classOf[Word64], env, pos)
    )
    for (cls, parameters) <- released do
      assert(scala.util.Try(cls.getConstructor(parameters*)).isSuccess, cls.getName)
    // the released three-argument form, with its default position, has no scrutinee
    assertEquals(new MissingCaseBranch(Word64(1L), Vector.empty).scrutinee, None)
    val name = NamedDeBruijn("i", 7)
    assert(new OpenTermEvaluatedMachineError(name, Vector.empty).name eq name)
  }

  private def onSmallStack[A](body: => A): A =
    val result = new AtomicReference[Either[Throwable, A]](Left(new IllegalStateException("unrun")))
    val worker = new Thread(
      null,
      () =>
        result.set(
          try Right(body)
          catch case error: Throwable => Left(error)
        ),
      "native-diagnostics-small-stack",
      256 * 1024
    )
    worker.start()
    worker.join()
    result.get().fold(error => fail(s"failed on a small stack: $error"), identity)

  test("discharge, term and constant conversion walk 20000 levels on a small stack") {
    val depth = 20000
    val deepTerm = (0 until depth).foldLeft[UplcTerm](UplcTerm.Var(NamedDeBruijn("i1", 1)))(
      (t, _) => UplcTerm.Delay(t)
    )
    val closure = CekValue.VLamAbs("x", deepTerm, Vector("y" -> CekValue.VCon(Constant.Integer(7))))
    val deepData =
      (0 until depth).foldLeft[Data](Data.I(1))((d, _) => Data.List(PList.from(Vector(d))))
    val (term, discharged, constant) = onSmallStack(
      (
        NativeDiagnostics.term(deepTerm, NativeDiagnostics.Origins.none),
        NativeDiagnostics.discharge(closure, NativeDiagnostics.Origins.none),
        NativeDiagnostics.constant(Constant.Data(deepData))
      )
    )
    def delays(t: N.Term): Int =
      @scala.annotation.tailrec
      def go(t: N.Term, n: Int): Int = t match
        case N.Term.Delay(b) => go(b, n + 1)
        case _               => n
      go(t, 0)
    assertEquals(term.map(delays), Right(depth))
    // under the lambda the variable is bound: it stays, the environment is not consulted
    discharged match
      case Right(N.Term.Lam("i", index, body)) =>
        assertEquals(index, BigInt(0))
        assertEquals(delays(body), depth)
      case other => fail(s"unexpected discharge: ${other.map(_.productPrefix)}")
    assert(constant.isRight)
  }
