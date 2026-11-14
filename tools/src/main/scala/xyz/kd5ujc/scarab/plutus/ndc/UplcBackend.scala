// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.syntax.all.*
import scalus.cardano.ledger.ExUnits as ScalusExUnits
import scalus.uplc.Term as STerm
import scalus.uplc.eval.{Log, OutOfExBudgetError}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{
  BackendFailure,
  FlatScan,
  NativeCrypto,
  PlutusAvailability,
  PlutusLanguage,
  PlutusScriptBytes,
  PlutusVm,
  ReferenceBudget,
  ScalusEvaluator,
  ScriptDecodeFailure,
  UplcText
}
import xyz.kd5ujc.scarab.uplc.{Program, ProgramVersion, ProtocolMajor, Term}
import xyz.kd5ujc.scarab.uplc.cek.{BudgetMode, CekError, CekResult, MachineParameters}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.text.ProgramError
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  LedgerContext,
  LedgerEval,
  LedgerOutcome,
  Scope,
  ScriptResult,
  Unavailability
}

/** The three outcomes of a program that the scenarios name besides a result. */
enum Verdict(val text: String):
  case ParseDecode extends Verdict("parse/decode error")
  case OutOfBudget extends Verdict("out of budget")
  case EvaluationFailure extends Verdict("evaluation failure")

/** What one evaluation, or the preparation before it, gave. */
enum Run:
  /** The machine returned a value having spent `spent`; `result` is the value as a term of the
    * `uplc` module, made only when it is asked for (for the Scalus backend a conversion).
    */
  case Evaluated(spent: ExBudget, result: () => Either[Unanswered, Term])

  /** The reference's verdict on the program, with the backend's typed detail as text. */
  case Failed(verdict: Verdict, detail: String)

  /** The backend gives no verdict: never one of the [[Verdict]]s. */
  case NoVerdict(cause: Unanswered)

/** Why a backend gives no verdict on a program: a defect, a missing library or a construct the
  * harness cannot hand it.
  */
enum Unanswered:
  /** The own evaluator's no-verdict ([[Unavailability]]). */
  case Own(reason: Unavailability)

  /** The Scalus backend's no-verdict ([[BackendFailure]]). */
  case Scalus(reason: BackendFailure)

  /** `FlatScan` refused a script the Scalus decoder admitted. */
  case Undecodable(failure: FlatScan.Failure)

  /** A script the Scalus decoder admitted has no Flat content. */
  case NoFlatContent

  /** A term or constant has no counterpart in the other representation. */
  case Conversion(error: ConversionError)

  /** The Scalus machine threw what is not the reference's failure. */
  case Thrown(error: Throwable)

  /** The `uplc` module's text parser, test code, refused the program: no production code judged it.
    */
  case TextRefused(error: ProgramError)

  /** The native library through which Scalus evaluates the BLS12-381, secp256k1 and Ed25519
    * builtins did not load ([[NativeCrypto]]). `ScalusEvaluator` reports such a builtin as no
    * verdict through its own log; a machine run outside it would read the refusal as the script's
    * failure.
    */
  case NativeCryptoMissing

  def message: String = this match
    case Own(reason)          => UplcText.unavailable(reason)
    case Scalus(reason)       => reason.message
    case Undecodable(failure) => failure.message
    case NoFlatContent        => "an admitted script has no Flat content"
    case Conversion(error)    => error.message
    case Thrown(error)        => error.toString
    case NativeCryptoMissing  => "the native crypto library did not load"
    case TextRefused(error)   => s"the test-tree text parser refused it: $error"

/** A program decoded, admitted and closed for its language and protocol, with the machine of its
  * evaluation context: what remains is the pure CEK evaluation.
  */
final case class Prepared(version: ProgramVersion, evaluate: ExBudget => Run)

/** One of the node's Plutus evaluators (`--plutus-vm`, [[PlutusVm]]), taken apart where the
  * scenarios need it: the ledger's admission of a script, the check of `mkTermToEvaluate`, and the
  * machine of the protocol with a restricting budget, whose result term the ledger never looks at
  * but the scenarios report. Nothing is applied to the program: the corpora's programs are closed.
  */
trait UplcBackend:
  def vm: PlutusVm

  /** The evaluation context of a language at a protocol made from a cost-model list. */
  type Context

  /** The context of `language` at `protocol` from `costModel`, the list the ledger would pass. */
  def context(
      language: PlutusLanguage,
      protocol: Int,
      costModel: Vector[Long]
  ): Either[Unanswered, Context]

  /** A script as the ledger holds it (a CBOR byte string around the Flat program), admitted as the
    * ledger's `deserialiseScript` admits it, then checked as `mkTermToEvaluate` checks it.
    */
  def script(context: Context, script: Bytes): Either[Run, Prepared]

  /** A program read from text by the shared text front end that has no Flat encoding (a BLS12-381
    * constant), so that no ledger could hold it: checked as `mkTermToEvaluate` checks it, in the
    * backend's order, with no Flat admission.
    */
  def text(context: Context, program: Program): Either[Run, Prepared]

object UplcBackend:
  def of(vm: PlutusVm): UplcBackend = vm match
    case PlutusVm.Scalus => ScalusBackend
    case PlutusVm.Scarab => OwnBackend

/** Scarab's own evaluator, the `uplc` module (ADR 0027): `LedgerEval` as `ScarabEvaluator` runs it.
  */
object OwnBackend extends UplcBackend:
  val vm: PlutusVm = PlutusVm.Scarab

  final case class Context(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      ledger: LedgerContext,
      machine: MachineParameters
  )

  def context(
      language: PlutusLanguage,
      protocol: Int,
      costModel: Vector[Long]
  ): Either[Unanswered, Context] =
    val major = ProtocolMajor(protocol)
    for
      ledger <- LedgerContext.make(language, costModel)._2.leftMap(Unanswered.Own(_))
      machine <- ledger.at(major).leftMap(Unanswered.Own(_))
    yield Context(language, major, ledger, machine)

  def script(context: Context, script: Bytes): Either[Run, Prepared] =
    LedgerEval.deserialiseScript(context.language, context.protocol, script) match
      case Left(LedgerOutcome.Refused(error)) =>
        Left(Run.Failed(Verdict.ParseDecode, UplcText.decode(error)))
      case Left(ScriptResult.Unavailable(reason)) => Left(Run.NoVerdict(Unanswered.Own(reason)))
      case Right(program)                         => text(context, program)

  def text(context: Context, program: Program): Either[Run, Prepared] =
    LedgerEval.mkTermToEvaluate(context.language, context.protocol, program, Vector.empty) match
      case Left(error @ EvaluationError.Codec(_)) =>
        Left(Run.Failed(Verdict.ParseDecode, UplcText.evaluation(error)))
      case Left(error) => Left(Run.Failed(Verdict.EvaluationFailure, UplcText.evaluation(error)))
      case Right(term) => Right(Prepared(program.version, evaluate(context, term)))

  /** `evaluateTerm` (`LedgerEval.evaluateTerm`) within `limit`; a failure the ledger reads as a
    * defect of this implementation ([[LedgerEval.defect]]) is no verdict.
    */
  private def evaluate(context: Context, term: Term)(limit: ExBudget): Run =
    LedgerEval.evaluateTerm(
      BudgetMode.Restricting(limit),
      context.protocol,
      context.ledger,
      term
    ) match
      case Left(reason) => Run.NoVerdict(Unanswered.Own(reason))
      case Right(report) =>
        report.result match
          case success: CekResult.Success =>
            Run.Evaluated(report.budget.spent, () => Right(success.term))
          case CekResult.Failure(failure) =>
            LedgerEval
              .defect(failure)
              .fold(failure.error match
                case CekError.OutOfBudget(_) =>
                  Run.Failed(Verdict.OutOfBudget, UplcText.machine(failure.error))
                case error => Run.Failed(Verdict.EvaluationFailure, UplcText.machine(error)))(
                reason => Run.NoVerdict(Unanswered.Own(reason))
              )

/** The Scalus backend, the node's default: the production decoder (`ScalusEvaluator`'s
  * `ScriptDecoder`, which runs `FlatScan` and `PlutusAvailability`), then the checks
  * `ScalusEvaluator` makes before the machine (the program's version, the open variable `FlatScan`
  * found), then the corrected machine it builds (`ScalusEvaluator.corrected`) with the reference's
  * restricting budget (`ReferenceBudget.Restricting`).
  */
object ScalusBackend extends UplcBackend:
  val vm: PlutusVm = PlutusVm.Scalus

  private val decoder = ScalusEvaluator.pureDecoder

  /** The context: the machine of each caser the program's version may select
    * (`PlutusAvailability.caseOnData`).
    */
  final case class Context(
      language: PlutusLanguage,
      protocol: Int,
      machines: Boolean => Either[Unanswered, ScalusEvaluator.CorrectedVm]
  )

  def context(
      language: PlutusLanguage,
      protocol: Int,
      costModel: Vector[Long]
  ): Either[Unanswered, Context] =
    contextWith(NativeCrypto.available)(language, protocol, costModel)

  /** The context, which there is none of without the native crypto library: the harness runs the
    * machine without `ScalusEvaluator` 's log, which is where a missing library's refusal is kept.
    */
  private[ndc] def contextWith(nativeCrypto: Boolean)(
      language: PlutusLanguage,
      protocol: Int,
      costModel: Vector[Long]
  ): Either[Unanswered, Context] =
    def made(caseOnData: Boolean) =
      ScalusEvaluator
        .corrected(language, protocol, costModel, caseOnData)
        .leftMap(Unanswered.Scalus(_))
    val withoutData = made(false)
    val withData = made(true)
    Either
      .cond(nativeCrypto, withoutData, Unanswered.NativeCryptoMissing)
      .flatten
      .as(Context(language, protocol, if _ then withData else withoutData))

  def script(context: Context, script: Bytes): Either[Run, Prepared] =
    decoder.validate(context.language, context.protocol, script) match
      case Left(ScriptDecodeFailure.Malformed(problem)) =>
        Left(Run.Failed(Verdict.ParseDecode, problem.message))
      case Left(ScriptDecodeFailure.Unavailable(reason)) =>
        Left(Run.NoVerdict(Unanswered.Scalus(reason)))
      case Right(()) =>
        PlutusScriptBytes
          .flatOf(script.toArray)
          .toRight(Run.NoVerdict(Unanswered.NoFlatContent))
          .flatMap(flat =>
            FlatScan.decode(flat).leftMap(f => Run.NoVerdict(Unanswered.Undecodable(f)))
          )
          .flatMap { decoded =>
            val open = decoded.scan.openVariable.map(index => s"free variable $index")
            prepared(context, decoded.program.version, open, decoded.program.term)
          }

  def text(context: Context, program: Program): Either[Run, Prepared] =
    // the text front end is the `uplc` module's, so is the scope check of its program
    val open = Scope.check(program.term).left.toOption.map(_.toString)
    for
      version <- ScalusTerms.version(program.version).leftMap(unconverted)
      term <- ScalusTerms.toScalus(program.term).leftMap(unconverted)
      prepared <- prepared(context, version, open, term)
    yield prepared

  private def unconverted(error: ConversionError): Run =
    Run.NoVerdict(Unanswered.Conversion(error))

  /** The checks `ScalusEvaluator` makes before its machine, in its order: the program's version
    * (`admitted`), then the open variable the scope check found, then the machine of the program's
    * caser.
    */
  private def prepared(
      context: Context,
      version: (Int, Int, Int),
      open: Option[String],
      term: STerm
  ): Either[Run, Prepared] =
    (PlutusAvailability.versionFailure(context.language, context.protocol, version), open) match
      case (Some(failure), _) => Left(Run.Failed(Verdict.ParseDecode, failure.message))
      case (None, Some(free)) => Left(Run.Failed(Verdict.EvaluationFailure, free))
      case (None, None) =>
        val caseOnData = PlutusAvailability.caseOnData(context.protocol, version)
        for
          machine <- context.machines(caseOnData).leftMap(Run.NoVerdict(_))
          own <- ScalusTerms.ownVersion(version).leftMap(unconverted)
        yield Prepared(own, evaluate(machine, caseOnData, term))

  /** The machine of `ScalusEvaluator`, which recurses on the term: the caller runs it on a thread
    * with a large stack, as `ScalusEvaluator` does.
    */
  private def evaluate(machine: ScalusEvaluator.CorrectedVm, caseOnData: Boolean, term: STerm)(
      limit: ExBudget
  ): Run =
    val spender = ReferenceBudget.Restricting(ScalusExUnits(limit.memory.toLong, limit.cpu.toLong))
    def spent =
      val used = spender.getSpentBudget
      ExBudget.of(used.steps, used.memory)
    Either
      .catchOnly[Throwable](
        machine.cek(spender, Log(), debug = false, caseOnData).evaluateTerm(term)
      )
      .fold(
        {
          case _: OutOfExBudgetError => Run.Failed(Verdict.OutOfBudget, "out of budget")
          case _: StackOverflowError =>
            Run.NoVerdict(Unanswered.Scalus(BackendFailure.StackExhausted))
          case error if ScalusEvaluator.referenceFailure(error) =>
            Run
              .Failed(Verdict.EvaluationFailure, Option(error.getMessage).getOrElse(error.toString))
          case error => Run.NoVerdict(Unanswered.Thrown(error))
        },
        result =>
          Run.Evaluated(
            spent,
            () => ScalusTerms.fromScalus(result).leftMap(Unanswered.Conversion(_))
          )
      )
