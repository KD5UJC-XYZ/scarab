// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, UplcText}
import xyz.kd5ujc.scarab.uplc.{Program, ProgramVersion, ProtocolMajor, Term}
import xyz.kd5ujc.scarab.uplc.cek.{BudgetMode, CekError, CekResult}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.flat.{FlatEncodeError, FlatEncoder}
import xyz.kd5ujc.scarab.uplc.text.UplcParser
import xyz.kd5ujc.scarab.uplc.ledger.{
  EvaluationError,
  LedgerContext,
  LedgerEval,
  LedgerOutcome,
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
    * `uplc` module, made only when it is asked for.
    */
  case Evaluated(spent: ExBudget, result: () => Either[Unanswered, Term])

  /** The reference's verdict on the program, with the evaluator's typed detail as text. */
  case Failed(verdict: Verdict, detail: String)

  /** The evaluator gives no verdict: never one of the [[Verdict]]s. */
  case NoVerdict(cause: Unanswered)

/** Why the evaluator gives no verdict on a program: a defect, or a construct the harness cannot
  * hand it.
  */
enum Unanswered:
  /** The own evaluator's no-verdict ([[Unavailability]]). */
  case Own(reason: Unavailability)

  /** The program could not be written in Flat for a reason other than its constants. */
  case Encoding(error: FlatEncodeError)

  def message: String = this match
    case Own(reason)     => UplcText.unavailable(reason)
    case Encoding(error) => s"the program could not be written in Flat: $error"

/** A program decoded, admitted and closed for its language and protocol, with the machine of its
  * evaluation context: what remains is the pure CEK evaluation.
  */
final case class Prepared(version: ProgramVersion, evaluate: ExBudget => Run)

/** The node's Plutus evaluator, taken apart where the scenarios need it: the ledger's admission of
  * a script, the check of `mkTermToEvaluate`, and the machine of the protocol with a restricting
  * budget, whose result term the ledger never looks at but the scenarios report. Nothing is applied
  * to the program: the corpora's programs are closed.
  */
trait UplcBackend:
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

  /** A program read from text that has no Flat encoding (a BLS12-381 constant), so that no ledger
    * could hold it: checked as `mkTermToEvaluate` checks it, with no Flat admission.
    */
  def text(context: Context, program: Program): Either[Run, Prepared]

  /** A text-only vector: read by the text parser, whose refusal is a parse/decode error; written in
    * Flat and admitted by [[script]]; or, when Flat cannot write its constants, checked and run
    * without that admission ([[Route.Unencodable]]).
    */
  def source(context: Context, text: String): (Route, Either[Run, Prepared])

/** How a vector reached the evaluator. */
enum Route:
  /** Its `.flat`, through `deserialiseScript`. */
  case Flat

  /** Its `.uplc`, read by the text parser, written in Flat and admitted as [[Flat]]. */
  case Encoded

  /** Its `.uplc`, read by the text parser, with a constant Flat cannot write (BLS12-381): the
    * checks of `mkTermToEvaluate` and the machine only.
    */
  case Unencodable

  /** Its `.uplc`, which the text parser refused: a parse/decode error. */
  case Refused

/** Scarab's own evaluator, the `uplc` module (ADR 0027): `LedgerEval` as `ScarabEvaluator` runs it.
  */
object OwnBackend extends UplcBackend:
  final case class Context(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      ledger: LedgerContext
  )

  def context(
      language: PlutusLanguage,
      protocol: Int,
      costModel: Vector[Long]
  ): Either[Unanswered, Context] =
    LedgerContext
      .make(language, costModel)
      ._2
      .bimap(Unanswered.Own(_), Context(language, ProtocolMajor(protocol), _))

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
      case Right(term) => Right(Prepared(program.version, evaluate(context, program.version, term)))

  /** The `uplc` module's parser ([[UplcParser]], the grammar of plutus 1.70.0.0's) and its Flat
    * writer ([[FlatEncoder]]).
    */
  def source(context: Context, text: String): (Route, Either[Run, Prepared]) =
    UplcParser.program(text) match
      case Left(error) => (Route.Refused, Left(Run.Failed(Verdict.ParseDecode, error.toString)))
      case Right(program) =>
        FlatEncoder.script(program) match
          case Right(bytes) => (Route.Encoded, script(context, bytes))
          case Left(FlatEncodeError.Unencodable(_)) =>
            (Route.Unencodable, this.text(context, program))
          case Left(error) => (Route.Encoded, Left(Run.NoVerdict(Unanswered.Encoding(error))))

  /** `evaluateTerm` (`LedgerEval.evaluateTerm`) within `limit`; a failure the ledger reads as a
    * defect of this implementation ([[LedgerEval.defect]]) is no verdict.
    */
  private def evaluate(context: Context, version: ProgramVersion, term: Term)(
      limit: ExBudget
  ): Run =
    LedgerEval.evaluateTerm(
      BudgetMode.Restricting(limit),
      context.protocol,
      version,
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
