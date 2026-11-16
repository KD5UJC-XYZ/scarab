// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.uplc.Availability as UplcAvailability
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError
import xyz.kd5ujc.scarab.uplc.ledger.{EvaluationError, Unavailability}

/** One script execution: everything the verdict depends on, and nothing else.
  *
  * @param protocolMajor
  *   protocol version in force; selects the builtin semantics
  * @param script
  *   the script as the ledger stores it (a CBOR byte string of the flat-encoded program, itself
  *   wrapped in a CBOR byte string)
  * @param args
  *   the data arguments applied to the script, in order (V1/V2 `[datum?, redeemer, context]`, V3
  *   and V4 `[context]`)
  * @param costModel
  *   the cost model parameters of the language in the canonical order
  * @param budget
  *   the redeemer's declared execution units
  */
final case class EvalRequest(
    language: PlutusLanguage,
    protocolMajor: Int,
    script: Bytes,
    args: Vector[PlutusData],
    costModel: Vector[Long],
    budget: ExUnits
)

/** Why the own evaluator ([[ScarabEvaluator]]) refuses a script before it runs it, with the typed
  * cause of the `uplc` module.
  */
enum ScriptProblem:
  /** `deserialiseScript` refused the script. */
  case Uplc(cause: ScriptDecodeError)

  /** The program's version is not one the language has at the protocol: the check of
    * `mkTermToEvaluate`.
    */
  case UplcVersion(cause: UplcAvailability.Refusal)

  def message: String = this match
    case Uplc(cause)        => s"deserialiseScript refused it: ${UplcText.decode(cause)}"
    case UplcVersion(cause) => s"the program's version is not available: ${cause.productPrefix}"

/** Why this implementation supplies no verdict for a script: never the reference's verdict. */
enum BackendFailure:
  case StackExhausted
  case BudgetOutOfRange(budget: ExUnits)
  case Defect(cause: Throwable)

  /** The own evaluator gives no verdict ([[Unavailability]]): a protocol, cost model or builtin
    * this build lacks, or a defect of the implementation.
    */
  case Uplc(reason: Unavailability)

  def message: String = this match
    case StackExhausted      => "backend stack exhausted"
    case BudgetOutOfRange(_) => "execution budget exceeds 64 bits"
    case Defect(cause)       => Option(cause.getMessage).getOrElse(cause.getClass.getName)
    case Uplc(reason) => s"the own evaluator gives no verdict: ${UplcText.unavailable(reason)}"

/** Why a script failed. */
sealed trait ScriptFailure:
  def logs: Vector[String]
  def message: String

object ScriptFailure:
  /** The reference's `EvaluationError` and the `trace` output. The error's detail names its cases
    * only ([[UplcText]]): never a constant or a term of the script, which can be as large as a
    * transaction. `logs` is the script's own `trace` output, which the text includes.
    */
  final case class Uplc(error: EvaluationError, logs: Vector[String]) extends ScriptFailure:
    override def toString: String = s"ScriptFailure.Uplc(${UplcText.evaluation(error)},$logs)"
    def message: String =
      val detail = UplcText.evaluation(error)
      if logs.isEmpty then detail else s"$detail; trace: ${logs.mkString(" | ")}"

/** Why a script did not validate. */
enum EvalFailure:
  case Decode(reason: ScriptProblem)
  case Unsupported(reason: BackendFailure)
  case OutOfBudget
  case Script(reason: ScriptFailure)

  /** A variable the ledger's scope check refuses, found when the evaluation is set up (a failure of
    * phase 2, as in the reference's `mkTermToEvaluate`, not of deserialisation).
    */
  case OpenTerm(index: BigInt)

  def message: String = this match
    case Decode(r)      => s"script does not decode: ${r.message}"
    case Unsupported(r) => s"unsupported: ${r.message}"
    case OutOfBudget    => "execution budget exceeded"
    case Script(r)      => r.message
    case OpenTerm(i)    => s"free variable $i in the script"

/** Outcome of one execution; `used` is what the machine spent, `logs` the `trace` output. */
enum EvalResult:
  case Success(used: ExUnits, logs: Vector[String])
  case Failure(reason: EvalFailure, used: ExUnits, logs: Vector[String])

  def isSuccess: Boolean = this match
    case Success(_, _) => true
    case _             => false

  def spent: ExUnits = this match
    case Success(u, _)    => u
    case Failure(_, u, _) => u

/** Phase-2 evaluation of one Plutus script. The algebra is effect-polymorphic: evaluation is
  * deterministic, so [[ScarabEvaluator.pure]] interprets it in `cats.Id` and the ledger rules
  * (which are pure functions) take that interpreter; a node that wants to move evaluation off the
  * main thread or onto a pool uses [[ScarabEvaluator.suspended]] in its effect type instead.
  */
trait ScriptEvaluator[F[_]]:
  def evaluate(request: EvalRequest): F[EvalResult]

/** Whether a Plutus script deserialises for a ledger language and protocol version: the flat
  * program decodes and uses only builtins the pair allows (`deserialiseScript`). The ledger runs
  * this for the scripts of a transaction before it accepts them (Babbage onward), so a script that
  * cannot be decoded makes the transaction invalid rather than failing phase 2. The result is a
  * pure function of the arguments.
  */
trait ScriptDecoder[F[_]]:
  /** An unavailable backend supplies no script-admission verdict. */
  def validate(
      language: PlutusLanguage,
      protocolMajor: Int,
      script: Bytes
  ): F[Either[ScriptDecodeFailure, Unit]]

/** A proven admission failure is distinct from an implementation that could not answer. */
enum ScriptDecodeFailure:
  case Malformed(cause: ScriptProblem)
  case Unavailable(cause: BackendFailure)

  def message: String = this match
    case Malformed(cause)   => cause.message
    case Unavailable(cause) => cause.message

object ScriptDecoder:
  /** Accepts every script; for tests whose scripts are placeholders. */
  def lenient[F[_]](using F: cats.Applicative[F]): ScriptDecoder[F] =
    (_, _, _) => F.pure(Right(()))
