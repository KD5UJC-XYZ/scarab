// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ProtocolMajor}
import xyz.kd5ujc.scarab.uplc.cek.BudgetMode
import xyz.kd5ujc.scarab.uplc.cost.{EvaluationContext, ExBudget, ScriptEvaluation}
import xyz.kd5ujc.scarab.uplc.flat.SerialisedScript

/** The ledger's evaluation in the profile of the Haskell oracles (`scripts/oracle/plutus/Main.hs`,
  * `scripts/oracle/plutus/costing/Main.hs`): `deserialiseScript`, then `evaluateScriptRestricting`
  * with no arguments, `Quiet`, within the given limit. No verdict is a stage of its own, which no
  * recorded stage equals.
  */
object LedgerScriptEvaluation extends ScriptEvaluation:
  def admits(language: PlutusLanguage, protocol: ProtocolMajor, script: Bytes): Boolean =
    SerialisedScript.deserialiseScript(language, protocol, script).isRight

  def evaluate(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes,
      context: EvaluationContext,
      limit: ExBudget
  ): ScriptEvaluation.Stage =
    LedgerContext.of(context) match
      case Left(reason) => ScriptEvaluation.Stage.NoVerdict(reason)
      case Right(machines) =>
        stage(
          LedgerEval.evaluateSerialised(
            language,
            protocol,
            VerboseMode.Quiet,
            machines,
            BudgetMode.Restricting(limit),
            script,
            Vector.empty
          )
        )

  def stage(outcome: LedgerOutcome): ScriptEvaluation.Stage = outcome match
    case LedgerOutcome.Refused(_)         => ScriptEvaluation.Stage.Decode
    case ScriptResult.Success(spent, _)   => ScriptEvaluation.Stage.Success(spent)
    case ScriptResult.Unavailable(reason) => ScriptEvaluation.Stage.NoVerdict(reason)
    case ScriptResult.Failure(error, _, _) =>
      error.stage match
        case Stage.Decode     => ScriptEvaluation.Stage.Decode
        case Stage.Setup      => ScriptEvaluation.Stage.Setup
        case Stage.Scope      => ScriptEvaluation.Stage.Scope
        case Stage.Budget     => ScriptEvaluation.Stage.Budget
        case Stage.Evaluation => ScriptEvaluation.Stage.Evaluation
