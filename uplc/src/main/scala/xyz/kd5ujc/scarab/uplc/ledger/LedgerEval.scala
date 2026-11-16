// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{
  Constant,
  PlutusData,
  PlutusLanguage,
  Program,
  ProgramVersion,
  ProtocolMajor,
  Term
}
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
import xyz.kd5ujc.scarab.uplc.flat.SerialisedScript

/** The ledger's evaluation of a script, a transcription of `PlutusLedgerApi.Common.Eval`
  * (`plutus-ledger-api/src/PlutusLedgerApi/Common/Eval.hs`, plutus 1.71.0.0 `261766ad`): the
  * deserialised script applied to its arguments, checked (`mkTermToEvaluate`), run on the CEK
  * machine with the context's machine for the protocol and the program's version (`evaluateTerm`),
  * and the result judged (`processLogsAndErrors`). The per-language entry points are [[PlutusV1]],
  * [[PlutusV2]] and [[PlutusV3]]; a V4 script is evaluated through V3's
  * ([[PlutusLanguage.evaluatedAs]]). Like the reference's, every function is total in the protocol
  * version: from protocol 12 on it evaluates as at 12. Below 12 its verdicts are those of 1.70.0.0
  * (`507cab8c`); `docs/compatibility/plutus-v4-dijkstra.md` lists what the two releases change.
  *
  * One rule is this evaluator's, not the reference's, and gives no verdict: a defect of this
  * implementation ([[Unavailability]]) is reported as one, never as a failure of the script. Every
  * public function is a boundary where a non-fatal exception becomes [[Unavailability.Thrown]]; the
  * code below them signals no failure by exception, so one that escapes is a defect. Fatal errors
  * (`VirtualMachineError`, among them a stack overflow) are not caught.
  */
object LedgerEval:

  /** Why a script is not admitted: the reference refuses it, or this evaluator gives no verdict. */
  type NotAdmitted = LedgerOutcome.Refused | ScriptResult.Unavailable

  /** `mkTermToEvaluate` (`Eval.hs:107-130`, plutus 1.70.0.0 and 1.71.0.0 alike), in its order: the
    * term applied to the arguments as `data` constants, first argument innermost (`mkIterAppNoAnn`,
    * `Eval.hs:120-121`); then the program's version, which must be one the language has at the
    * protocol (`Eval.hs:123-127`, [[SerialisedScript.forEvaluation]]); then the scope of the
    * applied term (`Eval.hs:129-130`, [[Scope.check]]).
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

  /** `evaluateTerm` (`Eval.hs:228-245`, plutus 1.71.0.0): the machine of the protocol and the
    * program's version (`toMachineParameters`) on a term, with every log kept (`logEmitter`);
    * [[VerboseMode.Quiet]] drops them afterwards, which changes nothing else.
    *
    * @param version
    *   the version of the program the term comes from, which 1.71.0.0's caser reads
    *   ([[xyz.kd5ujc.scarab.uplc.cek.CaserBuiltin.forProtocol]])
    */
  def evaluateTerm(
      mode: BudgetMode,
      protocol: ProtocolMajor,
      version: ProgramVersion,
      context: LedgerContext,
      term: Term
  ): Either[Unavailability, CekReport] =
    guarded(context.at(protocol, version).map(CekMachine.run(_, mode, term)))(Left(_))

  /** `evaluateScriptRestricting` (`Eval.hs:254-276`, plutus 1.71.0.0): the script applied to `args`
    * and run within `budget`, with the caser of its version (`Eval.hs:271-273`); on success the
    * budget spent, `budget` less what is left (`Eval.hs:276`).
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

  /** `evaluateScriptCounting` (`Eval.hs:284-304`, plutus 1.71.0.0): the script applied to `args`
    * and run with no limit; on success the budget it spent.
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
    * (`Common/SerialisedScript.hs:244-278`, [[SerialisedScript.deserialiseScript]], plutus 1.70.0.0
    * and 1.71.0.0 alike), which the ledger runs in phase 1, then the evaluation in `mode`
    * ([[evaluateScriptRestricting]], [[evaluateScriptCounting]]).
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
      admitting(language, protocol, script).fold[LedgerOutcome](
        identity,
        run(language, protocol, verbose, context, mode, _, args)
      )
    )(ScriptResult.Unavailable(_))

  /** The ledger's admission of a script, `deserialiseScript` (`Common/SerialisedScript.hs:244-278`,
    * [[SerialisedScript.deserialiseScript]], plutus 1.70.0.0 and 1.71.0.0 alike), which the ledger
    * runs in phase 1 for the scripts of a transaction: the program, or the reference's refusal, or
    * no verdict (a defect).
    */
  def deserialiseScript(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[NotAdmitted, Program] =
    guarded(admitting(language, protocol, script))(reason => Left(ScriptResult.Unavailable(reason)))

  private def admitting(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes
  ): Either[NotAdmitted, Program] =
    SerialisedScript
      .deserialiseScript(language, protocol, script)
      .leftMap(LedgerOutcome.Refused(_))

  /** The evaluation behind the public functions, which catch what it throws. The reference looks
    * the machine up only once the term is checked (`evaluateTerm` is forced after
    * `mkTermToEvaluate`, `Eval.hs:270-274`, plutus 1.71.0.0), so a missing machine comes after a
    * refused term. The caser is the one of the deserialised program's version (`Eval.hs:271`).
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
      term <- mkTermToEvaluate(language, protocol, script, args).leftMap(
        ScriptResult.Failure(_, ExBudget.zero, Vector.empty)
      )
      machine <- context.at(protocol, script.version).leftMap(ScriptResult.Unavailable(_))
    yield processLogsAndErrors(language, verbose, CekMachine.run(machine, mode, term))
    result.merge

  /** `processLogsAndErrors` (`Eval.hs:306-324`, plutus 1.71.0.0): the logs are kept whatever the
    * result; a failure of the machine fails the script, unless it is a defect here; `()` succeeds
    * in every language, and any other result only in V1 and V2 (`Eval.hs:314-320`). The result is
    * never discharged to a term: the rule needs only whether it is the constant `()`.
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
  ): Either[LedgerEval.NotAdmitted, Program] =
    LedgerEval.deserialiseScript(language, protocol, script)

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

/** `PlutusLedgerApi.V2` (`V2.hs:167-218`, plutus 1.70.0.0): the common functions at PlutusV2,
  * arguments `[datum, redeemer, context]`.
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
  ): Either[LedgerEval.NotAdmitted, Program] =
    LedgerEval.deserialiseScript(language, protocol, script)

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

/** `PlutusLedgerApi.V3` (`V3.hs:200-253`, plutus 1.70.0.0): the common functions at PlutusV3, with
  * the script context as the one argument (`V3.hs:229-230,252-253`). The node evaluates a V4 script
  * through these ([[PlutusLanguage.evaluatedAs]]).
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
  ): Either[LedgerEval.NotAdmitted, Program] =
    LedgerEval.deserialiseScript(language, protocol, script)

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
