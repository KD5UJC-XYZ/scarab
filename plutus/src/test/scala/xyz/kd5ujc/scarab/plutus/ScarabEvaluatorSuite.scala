// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.FlatProgram.*
import xyz.kd5ujc.scarab.uplc.ProtocolMajor
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.{EvaluationError, ScriptResult, Unavailability}

/** How [[ScarabEvaluator]] reports each outcome of the own evaluator in the shared algebra, one row
  * of its mapping at a time, and that each row is the class the Scalus backend gives the same
  * request: a verdict means the same whichever backend gave it, and no verdict is never a failure
  * of the script.
  */
class ScarabEvaluatorSuite extends FunSuite:
  private val scarab = ScarabEvaluator.interpreter
  private val scalus = ScalusEvaluator.pure

  private def request(
      body: Tm,
      language: PlutusLanguage = PlutusLanguage.V3,
      protocol: Int = 10,
      version: (Int, Int, Int) = (1, 1, 0),
      budget: ExUnits = PlutusBoundaryCorpus.budget
  ): EvalRequest =
    val arguments = if language.evaluatedAs == PlutusLanguage.V3 then 1 else 3
    val script = (0 until arguments).foldLeft(body)((t, _) => Lam(t))
    EvalRequest(
      language,
      protocol,
      FlatProgram.script(version, script),
      Vector.fill(arguments)(PlutusData.unit),
      PlutusBoundaryCorpus.costModel(language, protocol.min(11)),
      budget
    )

  /** The class of a result, as the shared algebra's cases name it. */
  private def kind(result: EvalResult): String = result match
    case EvalResult.Success(_, _) => "success"
    case EvalResult.Failure(reason, _, _) =>
      reason match
        case EvalFailure.Decode(_)      => "decode"
        case EvalFailure.Unsupported(_) => "unsupported"
        case EvalFailure.OutOfBudget    => "budget"
        case EvalFailure.Script(_)      => "script"
        case EvalFailure.OpenTerm(_)    => "scope"

  private def both(request: EvalRequest): (EvalResult, EvalResult) =
    val ours = scarab.evaluate(request)
    val theirs = scalus.evaluate(request)
    assertEquals(kind(ours), kind(theirs), s"own $ours, Scalus $theirs")
    (ours, theirs)

  test("a script that returns () succeeds with the budget the Scalus backend spends") {
    both(request(unit)) match
      case (EvalResult.Success(ours, _), EvalResult.Success(theirs, _)) =>
        assertEquals(ours, theirs)
      case other => fail(other.toString)
  }

  test("deserialiseScript's refusal is a decoding failure, and Malformed from the decoder") {
    val notBytes = Bytes.fromArray(Array(0x01.toByte))
    both(request(unit).copy(script = notBytes)) match
      case (EvalResult.Failure(EvalFailure.Decode(ScriptProblem.Uplc(error)), spent, logs), _) =>
        assertEquals(error, ScriptDecodeError.ExpectedBytes)
        assertEquals(spent, ExUnits.zero)
        assertEquals(logs, Vector.empty)
      case other => fail(other.toString)
    scarab.validate(PlutusLanguage.V3, 10, notBytes) match
      case Left(
            ScriptDecodeFailure.Malformed(ScriptProblem.Uplc(ScriptDecodeError.ExpectedBytes))
          ) =>
        ()
      case other => fail(other.toString)
    assertEquals(scarab.validate(PlutusLanguage.V3, 10, request(unit).script), Right(()))
  }

  test("a program version the language lacks at the protocol is a decoding failure, as Scalus's") {
    // version 1.1.0 is not a PlutusV1 program's before protocol 11 (`Eval.hs:123-127`)
    both(request(unit, PlutusLanguage.V1, 7)) match
      case (EvalResult.Failure(EvalFailure.Decode(ScriptProblem.UplcVersion(_)), _, _), _) => ()
      case other => fail(other.toString)
    // the decoder does not check the version: it is the evaluation's check
    assertEquals(
      scarab.validate(PlutusLanguage.V1, 7, request(unit, PlutusLanguage.V1, 7).script),
      Right(())
    )
  }

  test("a free variable is OpenTerm with the index the Scalus backend reports") {
    both(request(Var(5))) match
      case (
            EvalResult.Failure(EvalFailure.OpenTerm(ours), _, _),
            EvalResult.Failure(EvalFailure.OpenTerm(theirs), _, _)
          ) =>
        assertEquals(ours, theirs)
      case other => fail(other.toString)
  }

  test("an overspent budget is OutOfBudget, having spent what the Scalus backend spent") {
    both(request(unit, budget = ExUnits(0, 0))) match
      case (
            EvalResult.Failure(EvalFailure.OutOfBudget, ours, _),
            EvalResult.Failure(_, theirs, _)
          ) =>
        assertEquals(ours, theirs)
      case other => fail(other.toString)
  }

  test("the error term fails the script with the reference's EvaluationError") {
    both(request(Error)) match
      case (EvalResult.Failure(EvalFailure.Script(ScriptFailure.Uplc(error, _)), _, _), _) =>
        assert(error.isInstanceOf[EvaluationError.Cek], error.toString)
      case other => fail(other.toString)
  }

  test("a V3 script that returns other than () fails the script") {
    both(request(integer(1))) match
      case (
            EvalResult.Failure(
              EvalFailure.Script(ScriptFailure.Uplc(EvaluationError.InvalidReturnValue, _)),
              _,
              _
            ),
            _
          ) =>
        ()
      case other => fail(other.toString)
    assertEquals(
      kind(scarab.evaluate(request(integer(1), PlutusLanguage.V2, 9, (1, 0, 0)))),
      "success"
    )
  }

  test("protocol 12 and later gives no verdict, from the evaluator and the decoder") {
    val dijkstra = request(unit, protocol = 12)
    scarab.evaluate(dijkstra) match
      case EvalResult.Failure(
            EvalFailure.Unsupported(
              BackendFailure.Uplc(Unavailability.UnsupportedProtocol(protocol))
            ),
            _,
            _
          ) =>
        assertEquals(protocol, ProtocolMajor.Dijkstra)
      case other => fail(other.toString)
    scarab.validate(PlutusLanguage.V3, 12, dijkstra.script) match
      case Left(ScriptDecodeFailure.Unavailable(BackendFailure.Uplc(_))) => ()
      case other                                                         => fail(other.toString)
  }

  test("a budget beyond 64 bits gives no verdict, as on the Scalus backend") {
    val huge = ExUnits(BigInt(Long.MaxValue) + 1, 1)
    both(request(unit, budget = huge)) match
      case (
            EvalResult.Failure(EvalFailure.Unsupported(BackendFailure.BudgetOutOfRange(b)), _, _),
            _
          ) =>
        assertEquals(b, huge)
      case other => fail(other.toString)
  }

  test("an exception inside the own evaluator gives no verdict, never a failure of the script") {
    val thrown = new IllegalStateException("injected")
    ScarabEvaluator.result(ScriptResult.Unavailable(Unavailability.Thrown(thrown))) match
      case EvalResult.Failure(
            EvalFailure.Unsupported(BackendFailure.Uplc(Unavailability.Thrown(cause))),
            _,
            _
          ) =>
        assert(cause eq thrown)
      case other => fail(other.toString)
  }

  test("whatever reaches the adapter is no verdict: a stack overflow, an exception, a JVM error") {
    def outcome(fault: Throwable): BackendFailure =
      ScarabEvaluator
        .guarded[Either[BackendFailure, Unit]](Left(_))(throw fault)
        .fold(identity, _ => fail(s"$fault: no failure"))
    assertEquals(outcome(new StackOverflowError("injected")), BackendFailure.StackExhausted)
    val exception = new IllegalStateException("injected")
    assertEquals(outcome(exception), BackendFailure.Uplc(Unavailability.Thrown(exception)))
    for fault <- Vector(
        new UnsatisfiedLinkError("injected"),
        new ExceptionInInitializerError("injected"),
        new OutOfMemoryError("injected")
      )
    do assertEquals(outcome(fault), BackendFailure.Defect(fault), fault.getClass.getName)
  }

  test("the text of a failure about a deep constant names its cases, on a small stack") {
    val deep = nestedEmptyList(5000)
    val scripts = Vector(
      // casing a list with three branches: the caser refuses the constant (protocol 11)
      request(Case(deep, Vector(unit, unit, unit)), protocol = 11),
      // a list where addInteger takes an integer: the unlifting refuses its type
      request(Ap(Ap(Builtin(0), deep), integer(1)))
    )
    for r <- scripts do
      val result = scarab.evaluate(r)
      val (text, rendered) = xyz.kd5ujc.scarab.uplc.SmallStack.run(
        (
          result match
            case EvalResult.Failure(reason, _, _) => reason.message
            case other                            => fail(s"not a failure: $other")
          ,
          result.toString
        )
      )
      assert(Set("script", "decode")(kind(result)), rendered.take(300))
      assert(text.length < 200, text.take(300))
  }

  test("the Scalus backend's script failure reads as it did before the own evaluator") {
    val cause = new IllegalStateException("x")
    assertEquals(
      EvalFailure.Script(ScriptFailure.Thrown(cause, Vector("a", "b"))).toString,
      s"Script(ScriptFailure($cause,Vector(a, b)))"
    )
    assertEquals(ScriptFailure.Thrown(cause, Vector("a")).message, "x; trace: a")
  }
