// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.preview

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{CollectError, Phase2, Redeemer}
import xyz.kd5ujc.scarab.plutus.*

/** Comparison values, never rendered diagnostics. Native exception prose is the separately accepted
  * Haskell presentation gap (#208). Same-backend replay additionally compares native diagnostic
  * text, concrete exception classes and trace output as test evidence. All other evaluator failures
  * retain their original typed payload.
  */
object ReplayTrace:
  enum ScriptCause:
    case Native(kind: Class[? <: Throwable], diagnostic: Option[String])
    case Secp(error: Secp256k1.Error)
    case SecpDefect(kind: Class[? <: Throwable], diagnostic: Option[String])

  enum Failure:
    case Evaluator(reason: EvalFailure)
    case Script(cause: ScriptCause, traces: Vector[String])
    case NativeDecode(kind: Class[? <: Throwable], diagnostic: Option[String])

  object Failure:
    def from(reason: EvalFailure): Failure = reason match
      case EvalFailure.Script(ScriptFailure(cause, traces)) =>
        val identity = cause match
          case error: Throwable => ScriptCause.Native(error.getClass, Option(error.getMessage))
          case Secp256k1.Error.Defect(error) =>
            ScriptCause.SecpDefect(error.getClass, Option(error.getMessage))
          case error: Secp256k1.Error => ScriptCause.Secp(error)
        Script(identity, traces)
      case EvalFailure.Decode(ScriptProblem.FlatDecoding(error)) =>
        NativeDecode(error.getClass, Option(error.getMessage))
      case other => Evaluator(other)

  enum Result:
    case Passed(spent: ExUnits, logs: Vector[String])
    case Failed(reason: Failure, spent: ExUnits, logs: Vector[String])

    def used: ExUnits = this match
      case Passed(used, _)    => used
      case Failed(_, used, _) => used

  object Result:
    def from(result: EvalResult): Result = result match
      case EvalResult.Success(used, logs)         => Passed(used, logs)
      case EvalResult.Failure(reason, used, logs) => Failed(Failure.from(reason), used, logs)

  final case class Evaluation(redeemer: Redeemer, request: EvalRequest, result: Result)
  final case class Refusal(redeemer: Redeemer, request: EvalRequest, reason: Failure)

  enum Outcome:
    case Passed(executions: Vector[Evaluation])
    case Failed(executions: Vector[Evaluation], failures: Vector[Refusal])
    case Unavailable(
        executions: Vector[Evaluation],
        redeemer: Redeemer,
        reason: EvalFailure.Unsupported
    )
    case Collection(errors: Vector[CollectError])

    def evaluations: Vector[Evaluation] = this match
      case Passed(es)            => es
      case Failed(es, _)         => es
      case Unavailable(es, _, _) => es
      case Collection(_)         => Vector.empty

  final case class Transaction(id: Bytes, slot: BigInt, isValid: Boolean, outcome: Outcome)

  def from(observed: Phase2.Observation): Transaction =
    def report(r: Phase2.Report): Vector[Evaluation] =
      r.evaluations.map(e => Evaluation(e.redeemer, e.request, Result.from(e.result)))
    val outcome = observed.outcome match
      case Left(errors)                    => Outcome.Collection(errors.toVector)
      case Right(Phase2.Outcome.Passed(r)) => Outcome.Passed(report(r))
      case Right(Phase2.Outcome.Failed(r, failures)) =>
        Outcome.Failed(
          report(r),
          failures.toVector.map(f => Refusal(f.redeemer, f.request, Failure.from(f.reason)))
        )
      case Right(Phase2.Outcome.Unavailable(r, redeemer, reason)) =>
        Outcome.Unavailable(report(r), redeemer, reason)
    Transaction(observed.tx.body.id, observed.slot, observed.tx.isValid, outcome)

  /** These are reached transaction observations and all executed scripts, rather than the old
    * short-circuiting machine's completed prefix. Invalid transactions and failed scripts have
    * separate counts: one invalid transaction may contain several failing scripts.
    */
  final case class Counts(
      reachedTransactions: Long = 0,
      evaluatedScripts: Long = 0,
      failedTransactions: Long = 0,
      failedScripts: Long = 0,
      unavailableTransactions: Long = 0,
      collectionFailures: Long = 0,
      used: ExUnits = ExUnits.zero
  ):
    def add(transaction: Transaction): Counts =
      val evaluations = transaction.outcome.evaluations
      copy(
        reachedTransactions = reachedTransactions + 1,
        evaluatedScripts = evaluatedScripts + evaluations.size,
        failedTransactions = failedTransactions + (transaction.outcome match
          case Outcome.Failed(_, _) => 1
          case _                    => 0),
        failedScripts = failedScripts + evaluations.count(_.result.isInstanceOf[Result.Failed]),
        unavailableTransactions = unavailableTransactions + (transaction.outcome match
          case Outcome.Unavailable(_, _, _) => 1
          case _                            => 0),
        collectionFailures = collectionFailures + (transaction.outcome match
          case Outcome.Collection(_) => 1
          case _                     => 0),
        used = evaluations.foldLeft(used)((total, e) => total + e.result.used)
      )

    def add(transactions: Vector[Transaction]): Counts = transactions.foldLeft(this)(_.add(_))

  enum Difference:
    case TransactionCount(expected: Int, actual: Int)
    case TransactionAt(index: Int, expected: Transaction, actual: Transaction)

  def compare(expected: Vector[Transaction], actual: Vector[Transaction]): Option[Difference] =
    if expected.size != actual.size then
      Some(Difference.TransactionCount(expected.size, actual.size))
    else
      expected.zip(actual).zipWithIndex.collectFirst {
        case ((left, right), index) if left != right =>
          Difference.TransactionAt(index, left, right)
      }
