// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits
import xyz.kd5ujc.scarab.uplc.{Availability as UplcAvailability, DefaultFun}
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

/** Admission and VM failures retain the provider's typed cause at the interpreter boundary. */
enum ScriptProblem:
  case CborByteStringExpected
  case Flat(cause: FlatScan.Failure)
  case Availability(cause: PlutusAvailability.Failure)
  case TrailingBytes(count: Int)

  /** Scarab's own evaluator ([[ScarabEvaluator]]): `deserialiseScript` refused the script. */
  case Uplc(cause: ScriptDecodeError)

  /** Scarab's own evaluator ([[ScarabEvaluator]]): the program's version is not one the language
    * has at the protocol, the check of `mkTermToEvaluate` that the Scalus backend reports as an
    * [[Availability]] failure too.
    */
  case UplcVersion(cause: UplcAvailability.Refusal)

  def message: String = this match
    case CborByteStringExpected => "script is not a CBOR byte string"
    case Flat(cause)            => cause.message
    case Availability(cause)    => cause.message
    case TrailingBytes(count)   => s"$count bytes remain after the script (RemainderError)"
    case Uplc(cause)            => s"deserialiseScript refused it: $cause"
    case UplcVersion(cause)     => s"the program's version is not available: $cause"

/** Why this implementation supplies no verdict for a script: never the reference's verdict. The
  * builtins are named by the `uplc` module's [[DefaultFun]], whichever backend reports them (ADR
  * 0027): the Scalus backend maps its own by name ([[ScalusEvaluator.builtinOf]]).
  */
enum BackendFailure:
  case NoResult, StackExhausted
  case BudgetOutOfRange(budget: ExUnits)
  case Defect(cause: Throwable)
  case BuiltinUnavailable(builtin: DefaultFun)

  /** Scalus's cost model gives the builtin a costing function that [[ReferenceCosting]] does not
    * mirror, so the reference's budget for it is unknown.
    */
  case CostingUnmirrored(builtin: DefaultFun)

  /** Scarab's own evaluator ([[ScarabEvaluator]]) gives no verdict: protocol 12 and later, or a
    * defect of the implementation ([[Unavailability]]).
    */
  case Uplc(reason: Unavailability)

  def message: String = this match
    case NoResult                    => "backend did not return a result"
    case StackExhausted              => "backend stack exhausted"
    case BudgetOutOfRange(_)         => "execution budget exceeds 64 bits"
    case Defect(cause)               => Option(cause.getMessage).getOrElse(cause.getClass.getName)
    case BuiltinUnavailable(builtin) => s"builtin $builtin unavailable"
    case CostingUnmirrored(builtin)  => s"the costing function of $builtin is not mirrored"
    case Uplc(reason)                => s"the own evaluator gives no verdict: $reason"

/** Why a script failed, as the backend that evaluated it reports it. */
enum ScriptFailure:
  /** The Scalus backend: the machine's typed exception and the `trace` output. */
  case Thrown(cause: Throwable, logs: Vector[String])

  /** Scarab's own evaluator ([[ScarabEvaluator]]): the reference's `EvaluationError` and the
    * `trace` output.
    */
  case Uplc(error: EvaluationError, logs: Vector[String])

  def logs: Vector[String]

  def message: String = this match
    case Thrown(cause, logs) =>
      val detail = Option(cause.getMessage).getOrElse(cause.getClass.getName)
      if logs.isEmpty then detail else s"$detail; trace: ${logs.mkString(" | ")}"
    case Uplc(error, logs) =>
      if logs.isEmpty then error.toString else s"$error; trace: ${logs.mkString(" | ")}"

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
  * deterministic, so [[ScalusEvaluator.pure]] interprets it in `cats.Id` and the ledger rules
  * (which are pure functions) take that interpreter; a node that wants to move evaluation off the
  * main thread or onto a pool uses [[ScalusEvaluator.suspended]] in its effect type instead.
  */
trait ScriptEvaluator[F[_]]:
  def evaluate(request: EvalRequest): F[EvalResult]

/** Whether a Plutus script deserialises for a ledger language and protocol version: the flat
  * program decodes and uses only builtins the pair allows ([[PlutusAvailability]]). The ledger runs
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

object ScriptDecodeFailure:
  private[plutus] def cacheable[A](result: Either[ScriptDecodeFailure, A]): Boolean = result match
    case Left(_: ScriptDecodeFailure.Unavailable) => false
    case _                                        => true

object ScriptDecoder:
  /** Accepts every script; for tests whose scripts are placeholders. */
  def lenient[F[_]](using F: cats.Applicative[F]): ScriptDecoder[F] =
    (_, _, _) => F.pure(Right(()))
