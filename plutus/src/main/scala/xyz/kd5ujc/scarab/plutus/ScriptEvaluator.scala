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

/** Why a script did not validate. */
enum EvalFailure:
  case Decode(reason: String)
  case Unsupported(reason: String)
  case OutOfBudget
  case Script(reason: String)

  /** A variable the ledger's scope check refuses, found when the evaluation is set up (a failure of
    * phase 2, as in the reference's `mkTermToEvaluate`, not of deserialisation).
    */
  case OpenTerm(index: BigInt)

  def message: String = this match
    case Decode(r)      => s"script does not decode: $r"
    case Unsupported(r) => s"unsupported: $r"
    case OutOfBudget    => "execution budget exceeded"
    case Script(r)      => r
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
  /** `Right(())` when the script deserialises, else the reason it does not. */
  def validate(language: PlutusLanguage, protocolMajor: Int, script: Bytes): F[Either[String, Unit]]

object ScriptDecoder:
  /** Accepts every script; for tests whose scripts are placeholders. */
  def lenient[F[_]](using F: cats.Applicative[F]): ScriptDecoder[F] =
    (_, _, _) => F.pure(Right(()))
