// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import xyz.kd5ujc.scarab.uplc.{
  Availability,
  DefaultFun,
  PlutusLanguage,
  ProtocolMajor,
  SemanticsVariant
}
import xyz.kd5ujc.scarab.uplc.builtin.extended.LibraryDefect
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.cost.{CostModelApplyError, ExBudget}
import xyz.kd5ujc.scarab.uplc.flat.ScriptDecodeError

/** Whether an evaluation keeps the logs of the script: the reference's `VerboseMode`
  * (`PlutusLedgerApi/Common/Eval.hs:87-93`, plutus 1.70.0.0 `507cab8c`). `Quiet` evaluates with
  * `noEmitter` (`Eval.hs:239`), so its logs are empty; nothing else changes, the budget included.
  */
enum VerboseMode:
  case Verbose, Quiet

/** Why the reference fails a script it evaluates: its `EvaluationError` (`Common/Eval.hs:57-70`) as
  * `evaluateScriptRestricting` and `evaluateScriptCounting` raise it. Every case fails the script.
  * The reference's `CostModelParameterMismatch` is not here: nothing in plutus-ledger-api 1.70.0.0
  * raises it (it is declared and rendered only, `Eval.hs:67,81`).
  */
enum EvaluationError:
  /** `CekError`: the machine failed (`processLogsAndErrors`, `Eval.hs:313`). */
  case Cek(failure: CekFailure)

  /** `DeBruijnError`: the scope check of the applied term refused it (`Eval.hs:129-130`). */
  case DeBruijn(error: FreeVariableError)

  /** `CodecError` with `PlutusCoreLanguageNotAvailableError`: the program's version is not one the
    * language has at the protocol (`Eval.hs:123-127`), which the evaluation checks, not the decoder
    * (Note [Checking the Plutus Core language version], `Eval.hs:323-328`).
    */
  case Codec(refusal: Availability.Refusal)

  /** `InvalidReturnValue`: a script of a language after V2 returned something other than `()`
    * (`Eval.hs:314-320`).
    */
  case InvalidReturnValue

  /** The stage the reference fails at, as the oracles classify its errors
    * (`scripts/oracle/plutus/Main.hs` `stage`).
    */
  def stage: Stage = this match
    case Codec(_)    => Stage.Setup
    case DeBruijn(_) => Stage.Scope
    case Cek(failure) =>
      failure.error match
        case CekError.OutOfBudget(_) => Stage.Budget
        case _                       => Stage.Evaluation
    case InvalidReturnValue => Stage.Evaluation

/** Where the ledger's handling of a script stops, as the Haskell oracles record it
  * (`scripts/oracle/plutus/Main.hs`, `scripts/oracle/plutus/costing/Main.hs`): `deserialiseScript`
  * refuses it ([[Decode]]), or the evaluation fails at the version check ([[Setup]]), the scope
  * check ([[Scope]]), the budget ([[Budget]]) or anywhere else ([[Evaluation]]).
  */
enum Stage:
  case Decode, Setup, Scope, Budget, Evaluation

  /** The name the oracles' fixtures record. */
  def name: String = this match
    case Decode     => "decode"
    case Setup      => "setup"
    case Scope      => "scope"
    case Budget     => "budget"
    case Evaluation => "evaluation"

/** Why this evaluator gives no verdict on a script: never the reference's verdict, so a caller must
  * not count it as a failure of the script, nor as a success.
  */
enum Unavailability:
  /** Protocol 12 and later, which this evaluator does not run (ADR 0027, its amendment of
    * 2026-11-12).
    */
  case UnsupportedProtocol(protocol: ProtocolMajor)

  /** The cost-model list did not make an evaluation context here. The reference makes one from
    * every list of `Int64` values ([[xyz.kd5ujc.scarab.uplc.cost.CostModelApplyError]]), so this is
    * a defect of this evaluator.
    */
  case CostModel(error: CostModelApplyError)

  /** No builtins for a semantics variant plutus 1.70.0.0 does not have. */
  case Variant(variant: SemanticsVariant)

  /** The context has no machine for the variant of the protocol: the reference's internal error of
    * `toMachineParameters` (`Common/Eval.hs:132-139`).
    */
  case NoMachine(language: PlutusLanguage, protocol: ProtocolMajor)

  /** Builtins of a variant with no denotation here. */
  case NoDenotation(variant: SemanticsVariant, functions: Set[DefaultFun])

  /** A builtin's library failed where the reference's cannot
    * ([[xyz.kd5ujc.scarab.uplc.builtin.EvaluationFailure.Defect]]).
    */
  case BuiltinDefect(function: DefaultFun, defect: LibraryDefect)

  /** A builtin called with a number of arguments the reference cannot give it
    * ([[xyz.kd5ujc.scarab.uplc.builtin.UnliftingCause.Arity]]).
    */
  case BuiltinArity(function: DefaultFun, expected: Int, actual: Int)

  /** A non-fatal exception thrown inside the evaluation: none of its parts signals failure so. */
  case Thrown(cause: Throwable)

/** What the ledger's handling of one script gives: refused by `deserialiseScript`
  * ([[LedgerOutcome.Refused]]), or what the evaluation gives ([[ScriptResult]]).
  */
sealed trait LedgerOutcome:
  /** The stage the reference stops at, none when it succeeds or this evaluator gives no verdict. */
  def failedAt: Option[Stage]

object LedgerOutcome:
  /** `deserialiseScript` refused the script (`Common/SerialisedScript.hs:244-278`), which the
    * ledger does in phase 1.
    */
  final case class Refused(error: ScriptDecodeError) extends LedgerOutcome:
    def failedAt: Option[Stage] = Some(Stage.Decode)

/** What an evaluation gives: the reference's `(LogOutput, Either EvaluationError ExBudget)`
  * (`Common/Eval.hs:268,295`), or no verdict ([[ScriptResult.Unavailable]]).
  */
sealed trait ScriptResult extends LedgerOutcome

object ScriptResult:
  /** `Right`: the budget spent and the logs. */
  final case class Success(spent: ExBudget, logs: Vector[String]) extends ScriptResult:
    def failedAt: Option[Stage] = None

  /** `Left`: the script fails.
    *
    * @param spent
    *   what the machine spent before it stopped, zero when it did not start; the reference does not
    *   report it, and the oracles do not record it
    */
  final case class Failure(error: EvaluationError, spent: ExBudget, logs: Vector[String])
      extends ScriptResult:
    def failedAt: Option[Stage] = Some(error.stage)

  /** No verdict on the script. */
  final case class Unavailable(reason: Unavailability) extends ScriptResult:
    def failedAt: Option[Stage] = None
