// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Availability,
  Constant,
  DefaultFun,
  PlutusData,
  PlutusLanguage,
  Program,
  ProgramVersion,
  ProtocolMajor,
  SemanticsVariant,
  SmallStack,
  Term,
  Text,
  Word64
}
import xyz.kd5ujc.scarab.uplc.builtin.{
  Argument,
  Denotation,
  ErrorClass,
  EvaluationFailure,
  MachineValue,
  Outcome,
  UnliftingCause,
  UnliftingFailure,
  Unlifting
}
import xyz.kd5ujc.scarab.uplc.builtin.extended.LibraryDefect
import xyz.kd5ujc.scarab.uplc.cek.{BudgetMode, CaseBuiltinError, CekError}
import xyz.kd5ujc.scarab.uplc.cek.Terms.*
import xyz.kd5ujc.scarab.uplc.cost.{
  CostModelApplyError,
  DefaultCostModels,
  EvaluationContext,
  ExBudget,
  SatInt
}
import xyz.kd5ujc.scarab.uplc.flat.{FlatWriter, ScriptDecodeError}

/** The rules of the ledger's evaluation of a script (`PlutusLedgerApi/Common/Eval.hs`, plutus
  * 1.70.0.0 `507cab8c`), one by one, and the evaluator's own: protocol 12 and later and its defects
  * give no verdict.
  */
final class LedgerEvalSuite extends munit.FunSuite:
  private val V1 = PlutusLanguage.V1
  private val V2 = PlutusLanguage.V2
  private val V3 = PlutusLanguage.V3
  private val V4 = PlutusLanguage.V4
  private val pv10 = ProtocolMajor.Plomin
  private val pv11 = ProtocolMajor.VanRossem
  private val Limit = ExBudget.of(10000000000L, 14000000L)

  /** The context of `language` from the default list of its variant at protocol 11. */
  private def context(language: PlutusLanguage): LedgerContext =
    val list = DefaultCostModels.list(language, SemanticsVariant.of(language, pv11))
    LedgerContext.make(language, list)._2.fold(e => fail(e.toString), identity)

  private lazy val contexts: Map[PlutusLanguage, LedgerContext] =
    PlutusLanguage.values.map(l => l -> context(l)).toMap

  private def v100(term: Term): Program = Program(ProgramVersion.V1_0_0, term)
  private def v110(term: Term): Program = Program(ProgramVersion.V1_1_0, term)

  private def restricting(
      language: PlutusLanguage,
      script: Program,
      args: Vector[PlutusData] = Vector.empty,
      protocol: ProtocolMajor = pv11,
      verbose: VerboseMode = VerboseMode.Quiet,
      budget: ExBudget = Limit
  ): ScriptResult =
    LedgerEval.evaluateScriptRestricting(
      language,
      protocol,
      verbose,
      contexts(language),
      budget,
      script,
      args
    )

  private def failure(result: ScriptResult): EvaluationError = result match
    case ScriptResult.Failure(error, _, _) => error
    case other                             => fail(s"expected a failure of the script, got $other")

  private def cekError(result: ScriptResult): CekError = failure(result) match
    case EvaluationError.Cek(f) => f.error
    case other                  => fail(s"expected a failure of the machine, got $other")

  private def succeeded(result: LedgerOutcome): ExBudget = result match
    case ScriptResult.Success(spent, _) => spent
    case other                          => fail(s"expected a success, got $other")

  private def assertSucceeds(result: LedgerOutcome): Unit =
    val _ = succeeded(result)

  private val one = PlutusData.I(1)
  private val two = PlutusData.I(2)

  // `Eval.hs:118-121`: `mkIterAppNoAnn t (fmap (mkConstant ()) args)`
  test("mkTermToEvaluate applies the arguments as data constants, the first innermost") {
    val body = lam(lam(unit))
    assertEquals(
      LedgerEval.mkTermToEvaluate(V1, pv11, v100(body), Vector(one, two)),
      Right(app(body, data(one), data(two)))
    )
  }

  test("the arguments reach the script in order: datum, redeemer, context") {
    // the first argument must be (I 1): equalsData a (I 1), else error
    val script = v100(
      lam(
        lam(
          lam(
            force(
              app(
                force(builtin(DefaultFun.IfThenElse)),
                app(builtin(DefaultFun.EqualsData), v(3), data(one)),
                delay(unit),
                delay(error)
              )
            )
          )
        )
      )
    )
    assertSucceeds(restricting(V2, script, Vector(one, two, two)))
    assertEquals(
      cekError(restricting(V2, script, Vector(two, one, one))),
      CekError.ErrorTerm
    )
  }

  // `Eval.hs:123-130`: the version is checked first, then the scope
  test("the program's version is checked before the scope: setup, not scope") {
    val open = v110(v(1))
    // V1 has program version 1.1.0 from protocol 11 only (`plcVersionsAvailableIn`)
    failure(restricting(V1, open, protocol = pv10)) match
      case EvaluationError.Codec(Availability.Refusal.ProgramVersionNotAvailable(version, _, _)) =>
        assertEquals(version, ProgramVersion.V1_1_0)
      case other => fail(s"expected the version refusal, got $other")
    assertEquals(
      failure(restricting(V1, open, protocol = pv11)),
      EvaluationError.DeBruijn(FreeVariableError.FreeIndex(Word64.fromBits(1)))
    )
    assertEquals(failure(restricting(V1, open, protocol = pv10)).stage, Stage.Setup)
    assertEquals(failure(restricting(V1, open, protocol = pv11)).stage, Stage.Scope)
  }

  // `UntypedPlutusCore/Check/Scope.hs:37-55`
  test("the scope check: an index must be at least 1 and at most the number of lam around it") {
    def free(i: Long) = Left(FreeVariableError.FreeIndex(Word64.fromBits(i)))
    assertEquals(Scope.check(v(1)), free(1))
    assertEquals(Scope.check(lam(v(0))), free(0))
    assertEquals(Scope.check(lam(v(1))), Right(()))
    assertEquals(Scope.check(lam(v(2))), free(2))
    assertEquals(Scope.check(lam(lam(v(2)))), Right(()))
    assertEquals(Scope.check(lam(lam(v(-1)))), free(-1))
    // pre-order, the function before the argument
    assertEquals(Scope.check(app(v(5), v(7))), free(5))
    assertEquals(Scope.check(app(lam(v(1)), v(3))), free(3))
    // into delay and force, evaluated or not
    assertEquals(Scope.check(lam(delay(v(2)))), free(2))
    assertEquals(Scope.check(force(delay(v(1)))), free(1))
  }

  test("the scope check does not look into constr fields, nor into any part of case") {
    assertEquals(Scope.check(constr(0, v(1))), Right(()))
    assertEquals(Scope.check(caseOf(v(1), unit)), Right(()))
    assertEquals(Scope.check(caseOf(constr(0), unit, v(9))), Right(()))
    assertEquals(Scope.check(lam(caseOf(v(2), v(0)))), Right(()))
    // the machine finds the free variable only if it is evaluated
    val unevaluated = v110(lam(caseOf(constr(0), unit, v(9))))
    assertSucceeds(restricting(V3, unevaluated, Vector(one)))
    val evaluated = v110(lam(app(lam(unit), constr(0, v(9)))))
    assertEquals(
      cekError(restricting(V3, evaluated, Vector(one))).errorClass,
      ErrorClass.Structural
    )
    cekError(restricting(V3, evaluated, Vector(one))) match
      case CekError.OpenTermEvaluated(_) => ()
      case other                         => fail(s"expected an open term, got $other")
  }

  // `Eval.hs:314-320`
  test("V3: a result other than () fails the script; V1 and V2 accept any result") {
    val unitResult = v100(lam(unit))
    val integer = v100(lam(int(1)))
    val function = v100(lam(lam(v(1))))
    assertSucceeds(restricting(V3, unitResult, Vector(one)))
    assertEquals(failure(restricting(V3, integer, Vector(one))), EvaluationError.InvalidReturnValue)
    assertEquals(
      failure(restricting(V3, function, Vector(one))),
      EvaluationError.InvalidReturnValue
    )
    assertEquals(EvaluationError.InvalidReturnValue.stage, Stage.Evaluation)
    // a V4 script is evaluated as V3
    assertEquals(failure(restricting(V4, integer, Vector(one))), EvaluationError.InvalidReturnValue)
    val three = Vector(one, one, one)
    Vector(V1, V2).foreach { language =>
      Vector(lam(lam(unitResult.term)), lam(lam(integer.term)), lam(lam(function.term))).foreach {
        term => assertSucceeds(restricting(language, v100(term), three))
      }
    }
  }

  test("the per-language functions: V3 takes the context alone") {
    val script = v100(lam(unit))
    assertEquals(
      PlutusV3.evaluateScriptRestricting(pv11, VerboseMode.Quiet, contexts(V3), Limit, script, one),
      restricting(V3, script, Vector(one))
    )
    val three = v100(lam(lam(lam(int(1)))))
    assertEquals(
      PlutusV1.evaluateScriptCounting(
        pv11,
        VerboseMode.Quiet,
        contexts(V1),
        three,
        Vector(one, one, one)
      ),
      PlutusV2.evaluateScriptCounting(
        pv11,
        VerboseMode.Quiet,
        contexts(V2),
        three,
        Vector(one, one, one)
      )
    )
  }

  test("protocol 12 and later give no verdict, from a program or from bytes, whatever the script") {
    val script = v100(lam(unit))
    Vector(12, 13, Int.MaxValue).map(ProtocolMajor(_)).foreach { protocol =>
      Vector(V1, V2, V3, V4).foreach { language =>
        val expected = ScriptResult.Unavailable(Unavailability.UnsupportedProtocol(protocol))
        assertEquals(restricting(language, script, Vector(one), protocol), expected)
        assertEquals(
          LedgerEval.evaluateScriptCounting(
            language,
            protocol,
            VerboseMode.Verbose,
            contexts(language),
            script,
            Vector(one)
          ),
          expected
        )
        // bytes the decoder refuses are not judged either
        assertEquals(
          LedgerEval.evaluateSerialised(
            language,
            protocol,
            VerboseMode.Quiet,
            contexts(language),
            BudgetMode.Counting,
            Bytes.fromHex("ff").fold(e => fail(e.toString), identity),
            Vector.empty
          ),
          expected
        )
        assertEquals(expected.failedAt, None)
      }
    }
    assertEquals(LedgerEval.supported(pv11), Right(()))
  }

  test("protocol 12 and later: no admission verdict, and evaluateTerm refuses it too") {
    val good = FlatWriter.script(v100(lam(unit)))
    val pv12 = ProtocolMajor.Dijkstra
    val unsupported = ScriptResult.Unavailable(Unavailability.UnsupportedProtocol(pv12))
    assertEquals(LedgerEval.deserialiseScript(V3, pv12, good), Left(unsupported))
    assertEquals(PlutusV3.deserialiseScript(pv12, good), Left(unsupported))
    assertEquals(
      PlutusV1.deserialiseScript(pv12, Bytes.fromHex("ff").fold(e => fail(e.toString), identity)),
      Left(ScriptResult.Unavailable(Unavailability.UnsupportedProtocol(pv12)))
    )
    assertEquals(
      LedgerEval.evaluateTerm(BudgetMode.Counting, pv12, contexts(V3), unit).map(_.result),
      Left(Unavailability.UnsupportedProtocol(pv12))
    )
    // at protocol 11 the reference's verdicts
    assertEquals(PlutusV3.deserialiseScript(pv11, good), Right(v100(lam(unit))))
    val trailing = Bytes.wrap(good.toArray ++ Array[Byte](0))
    assertEquals(
      PlutusV3.deserialiseScript(pv11, trailing),
      Left(LedgerOutcome.Refused(ScriptDecodeError.Remainder(Bytes.wrap(Array[Byte](0)))))
    )
    assertEquals(
      PlutusV2.deserialiseScript(pv11, trailing).map(_.version),
      Right(ProgramVersion.V1_0_0)
    )
    assert(LedgerEval.evaluateTerm(BudgetMode.Counting, pv11, contexts(V3), unit).isRight)
  }

  test("from bytes: deserialiseScript's refusals are the decode stage, then the evaluation") {
    def serialised(language: PlutusLanguage, protocol: ProtocolMajor, bytes: Bytes) =
      LedgerEval.evaluateSerialised(
        language,
        protocol,
        VerboseMode.Quiet,
        contexts(language),
        BudgetMode.Restricting(Limit),
        bytes,
        Vector(one)
      )
    val good = FlatWriter.script(v100(lam(unit)))
    assertSucceeds(serialised(V3, pv11, good))
    val trailing = Bytes.wrap(good.toArray ++ Array[Byte](0))
    assertEquals(
      serialised(V3, pv11, trailing),
      LedgerOutcome.Refused(ScriptDecodeError.Remainder(Bytes.wrap(Array[Byte](0))))
    )
    assertEquals(serialised(V3, pv11, trailing).failedAt, Some(Stage.Decode))
    // a V3 script before Chang, a V4 script before Dijkstra
    serialised(V3, ProtocolMajor.Vasil, good) match
      case LedgerOutcome.Refused(ScriptDecodeError.LanguageNotAvailable(_, _, _)) => ()
      case other => fail(s"expected the language refusal, got $other")
    serialised(V4, pv11, good) match
      case LedgerOutcome.Refused(ScriptDecodeError.LanguageNotAvailable(_, _, _)) => ()
      case other => fail(s"expected the language refusal, got $other")
  }

  // `Eval.hs:239`: `Quiet` evaluates with `noEmitter`
  test(
    "Verbose keeps the logs, of a success and of a failure; Quiet keeps none and spends the same"
  ) {
    val hello = con(
      Constant.String(
        Text.fromCodePoints("hello".codePoints.toArray).fold(e => fail(e.toString), identity)
      )
    )
    val traced = v100(lam(app(force(builtin(DefaultFun.Trace)), hello, unit)))
    // the error is delayed, so that the trace is saturated before it fails
    val failing = v100(lam(force(app(force(builtin(DefaultFun.Trace)), hello, delay(error)))))
    restricting(V3, traced, Vector(one), verbose = VerboseMode.Verbose) match
      case ScriptResult.Success(spent, logs) =>
        assertEquals(logs, Vector("hello"))
        assertEquals(
          restricting(V3, traced, Vector(one)),
          ScriptResult.Success(spent, Vector.empty)
        )
      case other => fail(s"expected a success, got $other")
    restricting(V3, failing, Vector(one), verbose = VerboseMode.Verbose) match
      case ScriptResult.Failure(_, spent, logs) =>
        assertEquals(logs, Vector("hello"))
        restricting(V3, failing, Vector(one)) match
          case ScriptResult.Failure(_, quiet, Vector()) => assertEquals(quiet, spent)
          case other => fail(s"expected a quiet failure, got $other")
      case other => fail(s"expected a failure, got $other")
  }

  // `Eval.hs:269-274,296-301`
  test(
    "restricting reports the limit less what is left, counting what is spent; one unit less fails"
  ) {
    val script = v100(lam(app(builtin(DefaultFun.AddInteger), int(1), int(2))))
    val spent = succeeded(restricting(V1, script, Vector(one)))
    assertEquals(
      LedgerEval
        .evaluateScriptCounting(V1, pv11, VerboseMode.Quiet, contexts(V1), script, Vector(one)),
      ScriptResult.Success(spent, Vector.empty)
    )
    assertEquals(succeeded(restricting(V1, script, Vector(one), budget = spent)), spent)
    val lessCpu = spent.copy(cpu = SatInt(spent.cpu.toLong - 1))
    val lessMemory = spent.copy(memory = SatInt(spent.memory.toLong - 1))
    assertEquals(
      failure(restricting(V1, script, Vector(one), budget = lessCpu)).stage,
      Stage.Budget
    )
    assertEquals(
      failure(restricting(V1, script, Vector(one), budget = lessMemory)).stage,
      Stage.Budget
    )
  }

  // `defaultCaserBuiltinFor` (`Eval.hs:141-146`)
  test("case on a constant: refused before protocol 11, without Data before 12") {
    val onInteger = v110(lam(caseOf(int(0), unit)))
    cekError(restricting(V3, onInteger, Vector(one), protocol = pv10)) match
      case CekError.CaseBuiltin(CaseBuiltinError.Unavailable(p)) => assertEquals(p, pv10)
      case other => fail(s"expected the caser's refusal, got $other")
    assertSucceeds(restricting(V3, onInteger, Vector(one)))
    val onData = v110(lam(caseOf(data(PlutusData.Constr(0, Vector.empty)), unit)))
    assertEquals(
      cekError(restricting(V3, onData, Vector(one))),
      CekError.CaseBuiltin(CaseBuiltinError.DataUnsupported)
    )
  }

  test("a failure of a builtin that the reference has is a verdict") {
    val script = v100(lam(app(builtin(DefaultFun.DivideInteger), int(1), int(0))))
    restricting(V3, script, Vector(one), verbose = VerboseMode.Verbose) match
      case ScriptResult.Failure(EvaluationError.Cek(f), _, logs) =>
        assertEquals(LedgerEval.defect(f), None)
        assertEquals(logs, Vector("Cannot divide by zero"))
      case other => fail(s"expected a failure of the machine, got $other")
  }

  /** The context of `language` with the denotation of `function` replaced. */
  private def withDenotation(
      language: PlutusLanguage,
      function: DefaultFun,
      denotation: Denotation
  ): LedgerContext =
    val base = contexts(language)
    base.copy(machines = base.machines.map { m =>
      val original = m.builtins.denotations
      m.copy(builtins =
        m.builtins.copy(denotations = f => if f == function then denotation else original(f))
      )
    })

  private def evaluateWith(context: LedgerContext, script: Program): ScriptResult =
    LedgerEval.evaluateScriptRestricting(
      V3,
      pv11,
      VerboseMode.Verbose,
      context,
      Limit,
      script,
      Vector(one)
    )

  private val adding = v100(lam(app(builtin(DefaultFun.AddInteger), int(1), int(2))))

  test("a defect of a builtin's library is no verdict, never a failure of the script") {
    val defective = new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] =
        Outcome.failed(
          EvaluationFailure.Defect(DefaultFun.AddInteger, LibraryDefect.DetachedMlResult)
        )
    assertEquals(
      evaluateWith(withDenotation(V3, DefaultFun.AddInteger, defective), adding),
      ScriptResult.Unavailable(
        Unavailability.BuiltinDefect(DefaultFun.AddInteger, LibraryDefect.DetachedMlResult)
      )
    )
  }

  test("an exception thrown inside the evaluation is no verdict") {
    val boom = new IllegalStateException("boom")
    val throwing = new Denotation:
      def apply[V](arguments: Vector[Argument[V]])(using MachineValue[V]): Outcome[V] = throw boom
    val context = withDenotation(V3, DefaultFun.AddInteger, throwing)
    assertEquals(
      evaluateWith(context, adding),
      ScriptResult.Unavailable(Unavailability.Thrown(boom))
    )
    assertEquals(
      LedgerEval.evaluateSerialised(
        V3,
        pv11,
        VerboseMode.Quiet,
        context,
        BudgetMode.Counting,
        FlatWriter.script(adding),
        Vector(one)
      ),
      ScriptResult.Unavailable(Unavailability.Thrown(boom))
    )
  }

  test("an application with an arity the machine cannot make is no verdict") {
    val base = contexts(V3)
    val arity = new Unlifting:
      def apply[V](function: DefaultFun, arguments: Vector[V])(using
          MachineValue[V]
      ): Either[UnliftingFailure, Vector[Argument[V]]] =
        Left(UnliftingFailure(ErrorClass.Structural, UnliftingCause.Arity(3, arguments.size)))
    val context = base.copy(machines =
      base.machines.map(m => m.copy(builtins = m.builtins.copy(unlifting = arity)))
    )
    assertEquals(
      evaluateWith(context, adding),
      ScriptResult.Unavailable(Unavailability.BuiltinArity(DefaultFun.AddInteger, 3, 2))
    )
    // the other unlifting failures are the reference's verdicts
    val wrong = v100(lam(app(builtin(DefaultFun.AddInteger), int(1), unit)))
    assertEquals(cekError(evaluateWith(base, wrong)).errorClass, ErrorClass.Structural)
  }

  test(
    "a context without the protocol's machine is no verdict, but only once the term is checked"
  ) {
    val empty = LedgerContext(V3, Vector.empty)
    assertEquals(
      evaluateWith(empty, adding),
      ScriptResult.Unavailable(Unavailability.NoMachine(V3, pv11))
    )
    // `toMachineParameters` is forced after `mkTermToEvaluate` (`Eval.hs:270-272`)
    assertEquals(
      failure(evaluateWith(empty, v100(v(1)))),
      EvaluationError.DeBruijn(FreeVariableError.FreeIndex(Word64.fromBits(1)))
    )
    assertEquals(
      LedgerContext.of(EvaluationContext(V3, Vector.empty)).map(_.at(pv11)),
      Right(Left(Unavailability.NoMachine(V3, pv11)))
    )
  }

  test("a variant without builtins here, and a refused costing context, are no verdict") {
    val variantCosts = DefaultCostModels.costs(SemanticsVariant.E)
    assertEquals(
      LedgerContext.machine(variantCosts.copy(variant = SemanticsVariant.F)).map(_.variant),
      Left(Unavailability.Variant(SemanticsVariant.F))
    )
    assertEquals(LedgerContext.machine(variantCosts).map(_.variant), Right(SemanticsVariant.E))
    Vector(
      CostModelApplyError.UnsuppliedParameter("cekStartupCost-exBudgetCPU"),
      CostModelApplyError.NoModel(SemanticsVariant.F),
      CostModelApplyError.UnknownParameter("x")
    ).foreach { error =>
      assertEquals(
        LedgerContext.fromCosts(Left(error)).map(_.language),
        Left(Unavailability.CostModel(error))
      )
    }
  }

  test("every list of any length makes a context, with a machine and every builtin per variant") {
    Vector(V1, V2, V3, V4).foreach { language =>
      val list = DefaultCostModels.list(language, SemanticsVariant.of(language, pv11))
      (0 to list.size + 20).foreach { n =>
        val values = (list ++ Vector.fill(20)(7L)).take(n)
        LedgerContext.make(language, values)._2 match
          case Right(context) =>
            assertEquals(
              context.machines.map(_.variant),
              SemanticsVariant.ofContext(language),
              s"$language $n"
            )
          case Left(reason) => fail(s"$language with $n values: $reason")
      }
    }
  }

  test("deep arguments and deep scripts on a small stack") {
    val depth = 100000
    val deep =
      (1 to depth).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.List(Vector(d)))
    val other =
      (1 to depth).foldLeft(PlutusData.I(1): PlutusData)((d, _) => PlutusData.List(Vector(d)))
    // equalsData and serialiseData of deep values, in V1 (a Bool and a bytes result), within a
    // budget that their cost fits
    val large = ExBudget.of(1L << 60, 1L << 60)
    val equal = v100(lam(lam(lam(app(builtin(DefaultFun.EqualsData), v(3), v(2))))))
    val serialise = v100(lam(lam(lam(app(builtin(DefaultFun.SerialiseData), v(3))))))
    SmallStack.run {
      assertSucceeds(restricting(V1, equal, Vector(deep, deep, deep), budget = large))
      assertSucceeds(restricting(V1, equal, Vector(deep, other, deep), budget = large))
      assertSucceeds(restricting(V1, serialise, Vector(deep, one, one), budget = large))
      // the context ignored, in V3
      assertSucceeds(restricting(V3, v100(lam(unit)), Vector(deep)))
    }
    // a script as deep as its arguments: the scope check and the machine
    val nested = nest(depth, v(1))(t => app(lam(t), unit))
    SmallStack.run {
      assertEquals(Scope.check(lam(nested)), Right(()))
      val script = v100(lam(lam(lam(nested))))
      assertSucceeds(restricting(V1, script, Vector(one, one, one), budget = large))
      assertEquals(
        failure(restricting(V1, v100(nest(depth, v(depth.toLong + 1))(lam)), Vector(one))),
        EvaluationError.DeBruijn(FreeVariableError.FreeIndex(Word64.fromBits(depth.toLong + 1)))
      )
    }
  }
