// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.data.NonEmptyChain
import cats.syntax.all.*
import io.circe.Json
import io.circe.syntax.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.node.mempool.{MempoolState, Rejection}
import xyz.kd5ujc.scarab.plutus.PlutusBackend

/** Why Scarab gave a fixture no verdict. */
enum NoVerdict extends DomainError:
  /** The fixture cannot be read or run as it states. */
  case Fixture(cause: FixtureError)

  /** The rules ended without a verdict ([[LedgerDefect]]). */
  case Defect(cause: LedgerDefect)

  /** The mempool's decoding refused the bytes for a reason other than their form. */
  case Rejected(cause: Rejection)

  /** The decoder or the rules threw. */
  case Threw(cause: Throwable)

  def message: String = this match
    case Fixture(cause)  => cause.message
    case Defect(cause)   => cause.message
    case Rejected(cause) => cause.message
    case Threw(cause)    => s"threw: $cause"

/** Scarab's verdict on a fixture's transaction. */
enum LedgerRulesVerdict:
  /** The rules accepted the transaction. */
  case Accepted

  /** The mempool's decoder refused the transaction's bytes. */
  case Malformed(cause: TransactionDecodeProblem)

  /** The transaction holds what the reference's decoder refuses, which the rules meet
    * ([[LedgerFailure.Undecodable]]).
    */
  case Inadmissible(defect: TxDefect)

  /** The rules refused the transaction: its failures in the reference's order, and those dropped
    * because only the mempool refuses them ([[LedgerRulesNames.mempoolOnly]]).
    */
  case Refused(
      failures: Vector[PredicateFailure],
      dropped: Vector[PredicateFailure],
      hasInputs: Boolean
  )

  case Unjudged(cause: NoVerdict)

/** The outcome of one fixture: its id (the first five digits of its file name), its file and
  * Scarab's verdict.
  */
final case class LedgerRulesCase(id: String, file: String, verdict: LedgerRulesVerdict):
  import LedgerRulesVerdict.*

  /** The report's result: `pass`, or the name of the first failure, in the reference's order, that
    * the schema has a name for, its first name; none (the fixture left out of the report, so
    * counted as failed) when Scarab has no verdict or the schema no name for any of its failures.
    * The fixture's expectation is never read.
    */
  def result: Option[String] = verdict match
    case Accepted                        => Some(LedgerRulesCase.Pass)
    case Malformed(_) | Inadmissible(_)  => Some(ReportName.DecodingFailure.toString)
    case Refused(fs, _, _) if fs.isEmpty => Some(LedgerRulesCase.Pass)
    case Refused(fs, _, inputs) =>
      fs.iterator.map(LedgerRulesNames.of(_, inputs)).collectFirst { case n +: _ => n.toString }
    case Unjudged(_) => None

  /** Every name of every failure, in order: what the sidecar keeps for the reader of the report. */
  def names: Vector[ReportName] = verdict match
    case Refused(fs, _, inputs)         => fs.flatMap(LedgerRulesNames.of(_, inputs)).distinct
    case Malformed(_) | Inadmissible(_) => Vector(ReportName.DecodingFailure)
    case _                              => Vector.empty

object LedgerRulesCase:
  val Pass = "pass"

  private val Id = """^([0-9]{5})-.*\.json$""".r

  /** The id of a fixture file, or none for a file that is not one. */
  def id(file: String): Option[String] = file match
    case Id(n) => Some(n)
    case _     => None

  /** The report entry of a case that has a result. */
  def entry(c: LedgerRulesCase): Option[Json] =
    c.result.map(r => Json.obj("id" -> c.id.asJson, "result" -> r.asJson))

  /** What the sidecar records of a case: the verdict, the result and each failure with its names.
    */
  def detail(c: LedgerRulesCase): Json =
    def failure(f: PredicateFailure, inputs: Boolean) = Json.obj(
      "rule" -> f.ruleName.asJson,
      "names" -> LedgerRulesNames.of(f, inputs).map(_.toString).asJson,
      "detail" -> f.detail.asJson
    )
    val (kind, extra) = c.verdict match
      case LedgerRulesVerdict.Accepted     => ("accepted", Vector.empty)
      case LedgerRulesVerdict.Malformed(d) => ("undecodable", Vector("cause" -> d.message.asJson))
      case LedgerRulesVerdict.Inadmissible(d) =>
        ("undecodable", Vector("cause" -> d.message.asJson))
      case LedgerRulesVerdict.Refused(fs, dropped, inputs) =>
        (
          "refused",
          Vector(
            "failures" -> fs.map(failure(_, inputs)).asJson,
            "dropped" -> dropped.map(failure(_, inputs)).asJson
          )
        )
      case LedgerRulesVerdict.Unjudged(cause) =>
        ("unjudged", Vector("cause" -> cause.message.asJson))
    Json.fromFields(
      Vector(
        "id" -> c.id.asJson,
        "file" -> c.file.asJson,
        "verdict" -> kind.asJson,
        "result" -> c.result.asJson,
        "names" -> c.names.map(_.toString).asJson
      ) ++ extra
    )

/** One fixture judged by Scarab's production paths: the transaction decoded as the mempool decodes
  * a submitted one ([[MempoolState.parse]]), then applied by the MEMPOOL rule with every failure
  * recorded ([[xyz.kd5ujc.scarab.node.mempool.MempoolLedger.submit]], the reference's `applyTx`
  * that the corpus's reference harness calls), the scripts evaluated by `backend`.
  */
object LedgerRulesRun:
  def judge(
      file: String,
      text: String,
      document: String => Option[String],
      backend: PlutusBackend
  ): LedgerRulesVerdict =
    Either
      .catchNonFatal(verdict(file, text, document, backend))
      .valueOr(t => LedgerRulesVerdict.Unjudged(NoVerdict.Threw(t)))

  private def verdict(
      file: String,
      text: String,
      document: String => Option[String],
      backend: PlutusBackend
  ): LedgerRulesVerdict =
    val judged = for
      fixture <- LedgerRulesFixture.read(file, text, document).leftMap(NoVerdict.Fixture(_))
      context <- LedgerRulesState.build(fixture, backend).leftMap(NoVerdict.Fixture(_))
    yield MempoolState.parse(fixture.transaction) match
      case Left(Rejection.Malformed(cause))   => LedgerRulesVerdict.Malformed(cause)
      case Left(Rejection.InputDefect(cause)) => LedgerRulesVerdict.Unjudged(NoVerdict.Threw(cause))
      case Left(other)    => LedgerRulesVerdict.Unjudged(NoVerdict.Rejected(other))
      case Right((_, tx)) => applied(tx, context)
    judged.valueOr(LedgerRulesVerdict.Unjudged(_))

  private def applied(tx: Tx, context: LedgerRulesContext): LedgerRulesVerdict =
    context.ledger.submit(tx, context.env, context.globals) match
      case Right(_) => LedgerRulesVerdict.Accepted
      case Left(LedgerFailure.Rejected(_, failures)) =>
        refused(failures, tx.body.inputs.nonEmpty)
      case Left(LedgerFailure.Undecodable(_, defect)) =>
        LedgerRulesVerdict.Inadmissible(defect)
      case Left(defect: LedgerDefect) => LedgerRulesVerdict.Unjudged(NoVerdict.Defect(defect))

  private def refused(
      failures: NonEmptyChain[PredicateFailure],
      inputs: Boolean
  ): LedgerRulesVerdict =
    val (dropped, kept) = failures.toChain.toVector.partition(LedgerRulesNames.mempoolOnly)
    LedgerRulesVerdict.Refused(kept, dropped, inputs)
