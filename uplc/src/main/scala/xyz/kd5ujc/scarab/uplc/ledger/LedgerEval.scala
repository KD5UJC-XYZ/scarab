// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, PlutusData, PlutusLanguage, Program, ProtocolMajor, Term}
import xyz.kd5ujc.scarab.uplc.builtin.{
  BuiltinFailure,
  EvaluationFailure,
  UnliftingCause,
  UnliftingFailure
}
import xyz.kd5ujc.scarab.uplc.cek.{
  BudgetMode,
  CekError,
  CekFailure,
  CekMachine,
  CekReport,
  CekResult
}
import xyz.kd5ujc.scarab.uplc.cost.{CostModelApplyWarn, ExBudget}
import xyz.kd5ujc.scarab.uplc.flat.{ScriptDecodeError, SerialisedScript}

/** The ledger's evaluation of a script, a transcription of `PlutusLedgerApi.Common.Eval`
  * (`plutus-ledger-api/src/PlutusLedgerApi/Common/Eval.hs`, plutus 1.70.0.0 `507cab8c`): the
  * deserialised script applied to its arguments, checked (`mkTermToEvaluate`), run on the CEK
  * machine with the context's machine for the protocol (`evaluateTerm`), and the result judged
  * (`processLogsAndErrors`). The per-language entry points are [[PlutusV1]], [[PlutusV2]] and
  * [[PlutusV3]].
  *
  * Two rules are this evaluator's, not the reference's, and neither gives a verdict: protocol 12
  * and later is refused before anything runs (ADR 0027, its amendment of 2026-11-12), and a defect
  * of this implementation ([[Unavailability]]) is reported as one, never as a failure of the
  * script. Every public function is a boundary where a non-fatal exception becomes
  * [[Unavailability.Thrown]]; the code below them signals no failure by exception, so one that
  * escapes is a defect. Fatal errors (`VirtualMachineError`, among them a stack overflow) are not
  * caught.
  */
object LedgerEval:

  /** The first protocol this evaluator refuses: Dijkstra, which the plutus of the Leios testnet
    * evaluates (ADR 0027, amendment of 2026-11-12).
    */
  val FirstUnsupported: ProtocolMajor = ProtocolMajor.Dijkstra

  /** Protocols before [[FirstUnsupported]] are evaluated; the others give no verdict. */
  def supported(protocol: ProtocolMajor): Either[Unavailability, Unit] =
    Either.cond(
      protocol.value < FirstUnsupported.value,
      (),
      Unavailability.UnsupportedProtocol(protocol)
    )

  /** `mkTermToEvaluate` (`Eval.hs:107-130`), in its order: the term applied to the arguments as
    * `data` constants, first argument innermost (`mkIterAppNoAnn`, `Eval.hs:120-121`); then the
    * program's version, which must be one the language has at the protocol (`Eval.hs:123-127`,
    * [[SerialisedScript.forEvaluation]]); then the scope of the applied term (`Eval.hs:129-130`,
    * [[Scope.check]]).
    */
  def mkTermToEvaluate(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Program,
      args: Vector[PlutusData]
  ): Either[EvaluationError, Term] =
    val applied =
      args.foldLeft(script.term)((term, arg) => Term.app(term, Term.constant(Constant.Data(arg))))
    for
      _ <- SerialisedScript
        .forEvaluation(language, protocol, script)
        .leftMap(EvaluationError.Codec(_))
      _ <- Scope.check(applied).leftMap(EvaluationError.DeBruijn(_))
    yield applied

  /** `evaluateTerm` (`Eval.hs:228-241`): the machine of the protocol (`toMachineParameters`) on a
    * term, with every log kept (`logEmitter`); [[VerboseMode.Quiet]] drops them afterwards, which
    * changes nothing else. Protocol 12 and later gives no verdict here too.
    */
  def evaluateTerm(
      mode: BudgetMode,
      protocol: ProtocolMajor,
      context: LedgerContext,
      term: Term
  ): Either[Unavailability, CekReport] =
    guarded(
      supported(protocol).flatMap(_ => context.at(protocol)).map(CekMachine.run(_, mode, term))
    )(Left(_))

  /** `evaluateScriptRestricting` (`Eval.hs:253-274`): the script applied to `args` and run within
    * `budget`; on success the budget spent, `budget` less what is left (`Eval.hs:274`).
    *
    * @param language
    *   the script's ledger language; a V4 script is evaluated as V3
    *   ([[PlutusLanguage.evaluatedAs]])
    */
  def evaluateScriptRestricting(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      budget: ExBudget,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    guarded(
      run(language, protocol, verbose, context, BudgetMode.Restricting(budget), script, args)
    )(ScriptResult.Unavailable(_))

  /** `evaluateScriptCounting` (`Eval.hs:282-301`): the script applied to `args` and run with no
    * limit; on success the budget it spent.
    */
  def evaluateScriptCounting(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    guarded(run(language, protocol, verbose, context, BudgetMode.Counting, script, args))(
      ScriptResult.Unavailable(_)
    )

  /** A script as the ledger holds it, from its bytes: `deserialiseScript`
    * (`Common/SerialisedScript.hs:244-278`, [[SerialisedScript.deserialiseScript]]), which the
    * ledger runs in phase 1, then the evaluation in `mode` ([[evaluateScriptRestricting]],
    * [[evaluateScriptCounting]]). Protocol 12 and later gives no verdict, the decoding included.
    *
    * @param script
    *   the script's bytes: a CBOR byte string around the Flat program
    */
  def evaluateSerialised(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      mode: BudgetMode,
      script: Bytes,
      args: Vector[PlutusData]
  ): LedgerOutcome =
    guarded[LedgerOutcome](
      supported(protocol).fold(
        ScriptResult.Unavailable(_),
        _ =>
          SerialisedScript
            .deserialiseScript(language, protocol, script)
            .fold(
              LedgerOutcome.Refused(_),
              run(language, protocol, verbose, context, mode, _, args)
            )
      )
    )(ScriptResult.Unavailable(_))

  /** The evaluation behind the public functions, which catch what it throws. The reference looks
    * the machine up only once the term is checked (`evaluateTerm` is forced after
    * `mkTermToEvaluate`, `Eval.hs:270-272`), so a missing machine comes after a refused term.
    */
  private def run(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      mode: BudgetMode,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    val result = for
      _ <- supported(protocol).leftMap(ScriptResult.Unavailable(_))
      term <- mkTermToEvaluate(language, protocol, script, args).leftMap(
        ScriptResult.Failure(_, ExBudget.zero, Vector.empty)
      )
      machine <- context.at(protocol).leftMap(ScriptResult.Unavailable(_))
    yield processLogsAndErrors(language, verbose, CekMachine.run(machine, mode, term))
    result.merge

  /** `processLogsAndErrors` (`Eval.hs:303-321`): the logs are kept whatever the result; a failure
    * of the machine fails the script, unless it is a defect here; `()` succeeds in every language,
    * and any other result only in V1 and V2 (`Eval.hs:314-320`). The result is never discharged to
    * a term: the rule needs only whether it is the constant `()`.
    */
  private def processLogsAndErrors(
      language: PlutusLanguage,
      verbose: VerboseMode,
      report: CekReport
  ): ScriptResult =
    val logs = verbose match
      case VerboseMode.Verbose => report.logs
      case VerboseMode.Quiet   => Vector.empty
    val spent = report.budget.spent
    report.result match
      case CekResult.Failure(failure) =>
        defect(failure).fold(
          ScriptResult.Failure(EvaluationError.Cek(failure), spent, logs)
        )(ScriptResult.Unavailable(_))
      case CekResult.SuccessConstant(Constant.Unit) => ScriptResult.Success(spent, logs)
      case _: CekResult.Success =>
        language.evaluatedAs match
          case PlutusLanguage.V1 | PlutusLanguage.V2 => ScriptResult.Success(spent, logs)
          case PlutusLanguage.V3 | PlutusLanguage.V4 =>
            ScriptResult.Failure(EvaluationError.InvalidReturnValue, spent, logs)

  /** A failure of the machine that is a defect of this implementation, not the reference's verdict:
    * a builtin's library failed where the reference's cannot, or a builtin was given a number of
    * arguments the machine cannot give it.
    */
  def defect(failure: CekFailure): Option[Unavailability] = failure.error match
    case CekError.Builtin(_, BuiltinFailure.Evaluation(EvaluationFailure.Defect(function, d))) =>
      Some(Unavailability.BuiltinDefect(function, d))
    case CekError.Builtin(
          function,
          BuiltinFailure.Unlifting(UnliftingFailure(_, UnliftingCause.Arity(expected, actual)))
        ) =>
      Some(Unavailability.BuiltinArity(function, expected, actual))
    case _ => None

  /** The one place an exception becomes a value: `body`, or the non-fatal exception it threw as
    * [[Unavailability.Thrown]]. The machine, the builtins, the costing and the decoder report their
    * failures as values, so an exception from them is a defect, never a verdict on the script.
    */
  private def guarded[A](body: => A)(unavailable: Unavailability => A): A =
    Either.catchNonFatal(body).fold(e => unavailable(Unavailability.Thrown(e)), identity)

/** `PlutusLedgerApi.V1` (`plutus-ledger-api/src/PlutusLedgerApi/V1.hs:234-285`, plutus 1.70.0.0
  * `507cab8c`): the common functions at PlutusV1, arguments `[datum, redeemer, context]`.
  */
object PlutusV1:
  val language: PlutusLanguage = PlutusLanguage.V1

  def mkEvaluationContext(
      costModel: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[Unavailability, LedgerContext]) =
    LedgerContext.make(language, costModel)

  def deserialiseScript(
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[ScriptDecodeError, Program] =
    SerialisedScript.deserialiseScript(language, protocol, script)

  def evaluateScriptCounting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    LedgerEval.evaluateScriptCounting(language, protocol, verbose, context, script, args)

  def evaluateScriptRestricting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      budget: ExBudget,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    LedgerEval.evaluateScriptRestricting(language, protocol, verbose, context, budget, script, args)

/** `PlutusLedgerApi.V2` (`V2.hs:167-218`): the common functions at PlutusV2, arguments
  * `[datum, redeemer, context]`.
  */
object PlutusV2:
  val language: PlutusLanguage = PlutusLanguage.V2

  def mkEvaluationContext(
      costModel: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[Unavailability, LedgerContext]) =
    LedgerContext.make(language, costModel)

  def deserialiseScript(
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[ScriptDecodeError, Program] =
    SerialisedScript.deserialiseScript(language, protocol, script)

  def evaluateScriptCounting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    LedgerEval.evaluateScriptCounting(language, protocol, verbose, context, script, args)

  def evaluateScriptRestricting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      budget: ExBudget,
      script: Program,
      args: Vector[PlutusData]
  ): ScriptResult =
    LedgerEval.evaluateScriptRestricting(language, protocol, verbose, context, budget, script, args)

/** `PlutusLedgerApi.V3` (`V3.hs:200-253`): the common functions at PlutusV3, with the script
  * context as the one argument (`V3.hs:229-230,252-253`). The node evaluates a V4 script through
  * these ([[PlutusLanguage.evaluatedAs]]).
  */
object PlutusV3:
  val language: PlutusLanguage = PlutusLanguage.V3

  def mkEvaluationContext(
      costModel: Vector[Long]
  ): (Vector[CostModelApplyWarn], Either[Unavailability, LedgerContext]) =
    LedgerContext.make(language, costModel)

  def deserialiseScript(
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[ScriptDecodeError, Program] =
    SerialisedScript.deserialiseScript(language, protocol, script)

  def evaluateScriptCounting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      script: Program,
      arg: PlutusData
  ): ScriptResult =
    LedgerEval.evaluateScriptCounting(language, protocol, verbose, context, script, Vector(arg))

  def evaluateScriptRestricting(
      protocol: ProtocolMajor,
      verbose: VerboseMode,
      context: LedgerContext,
      budget: ExBudget,
      script: Program,
      arg: PlutusData
  ): ScriptResult =
    LedgerEval.evaluateScriptRestricting(
      language,
      protocol,
      verbose,
      context,
      budget,
      script,
      Vector(arg)
    )
