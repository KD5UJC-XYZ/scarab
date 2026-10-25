// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.ExUnits

/** Plutus language versions. */
enum PlutusLanguage(val id: Int):
  case V1 extends PlutusLanguage(0)
  case V2 extends PlutusLanguage(1)
  case V3 extends PlutusLanguage(2)

/** One script execution: everything the verdict depends on, and nothing else.
  *
  * @param protocolMajor
  *   protocol version in force; selects the builtin semantics
  * @param script
  *   the script as the ledger stores it (a CBOR byte string of the flat-encoded program, itself
  *   wrapped in a CBOR byte string)
  * @param args
  *   the data arguments applied to the script, in order (V1/V2 `[datum?, redeemer, context]`, V3
  *   `[context]`)
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
  def message: String = this match
    case CborByteStringExpected => "script is not a CBOR byte string"
    case Flat(cause)            => cause.message
    case Availability(cause)    => cause.message
    case TrailingBytes(count)   => s"$count bytes remain after the script (RemainderError)"

/** Why this implementation supplies no verdict for a script: never the reference's verdict. */
enum BackendFailure:
  case NoResult, StackExhausted, BinaryDstUnavailable, NativeHashToGroupUnavailable
  case BudgetOutOfRange(budget: ExUnits)
  case Defect(cause: Throwable)
  case BuiltinUnavailable(builtin: scalus.uplc.DefaultFun)

  /** Whether the failure is the implementation's or this input's ([[BackendFailure.Reach]]). */
  def reach: BackendFailure.Reach = this match
    case NoResult | BinaryDstUnavailable | NativeHashToGroupUnavailable =>
      BackendFailure.Reach.Implementation
    case BuiltinUnavailable(_)                => BackendFailure.Reach.Implementation
    case StackExhausted | BudgetOutOfRange(_) => BackendFailure.Reach.Input
    case Defect(cause)                        => BackendFailure.reachOf(cause)
  def message: String = this match
    case BinaryDstUnavailable =>
      "hashToGroup binary DST has no byte-array implementation on this platform"
    case NativeHashToGroupUnavailable => "byte-array hashToGroup JNI entry point unavailable"
    case NoResult                     => "backend did not return a result"
    case StackExhausted               => "backend stack exhausted"
    case BudgetOutOfRange(_)          => "execution budget exceeds 64 bits"
    case Defect(cause)                => Option(cause.getMessage).getOrElse(cause.getClass.getName)
    case BuiltinUnavailable(builtin)  => s"builtin $builtin unavailable"

object BackendFailure:
  /** What a failure of the implementation says about the inputs it has no verdict for.
    *
    * A script failure the reference also reports is a verdict ([[EvalFailure.Script]]) and never
    * one of these. Of the rest, a missing part of the implementation (a native library, a builtin,
    * the memory or the classes of the process) leaves every script without a verdict, while a
    * defect that one input triggers (Scalus throwing on bytes it was given) leaves only that input
    * without one. ADR 0017 (amendment of 2026-10-23) gives each its policy.
    */
  enum Reach:
    /** The implementation is missing or its runtime is broken: no input can be trusted to get a
      * verdict, so the node stops.
      */
    case Implementation

    /** The implementation failed on this input: the block or transaction that carries it gets no
      * verdict here and is not adopted; it is no evidence against whoever sent it.
      */
    case Input

  /** A linkage error is a missing native or class, a builtin the machine does not know
    * (`UnknownBuiltin`) is a missing builtin, and a virtual machine error other than a stack
    * overflow (out of memory, an internal error) is the runtime's, wherever it is in the chain of
    * causes (Scalus wraps what a builtin throws in a `BuiltinError`); any other exception was
    * thrown on this input. A stack overflow is the depth of this input.
    */
  def reachOf(cause: Throwable): Reach =
    val chain = Iterator
      .iterate(Option(cause))(_.flatMap(c => causeOf(c).filterNot(_ eq c)))
      .takeWhile(_.isDefined)
      .flatten
      .take(MaxCauses)
    if chain.exists(runtime) then Reach.Implementation else Reach.Input

  /** Causes followed at most (a chain of causes can loop through more than one exception). */
  private val MaxCauses = 64

  // Scalus's BuiltinError keeps what the builtin threw in a field of its own, not as its cause.
  private def causeOf(error: Throwable): Option[Throwable] = error match
    case builtin: scalus.uplc.eval.BuiltinError => Option(builtin.cause)
    case other                                  => Option(other.getCause)

  private def runtime(error: Throwable): Boolean = error match
    case _: StackOverflowError                    => false
    case _: scalus.uplc.eval.UnknownBuiltin       => true
    case _: LinkageError | _: VirtualMachineError => true
    case _                                        => false

final case class ScriptFailure(cause: Throwable | Secp256k1.Error, logs: Vector[String]):
  def message: String =
    val detail = cause match
      case error: Throwable       => Option(error.getMessage).getOrElse(error.getClass.getName)
      case error: Secp256k1.Error => error.message
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
