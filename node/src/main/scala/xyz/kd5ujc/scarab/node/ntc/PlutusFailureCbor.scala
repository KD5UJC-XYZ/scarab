// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.HaskellShow
import java.util.Base64
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.plutus.{EvalFailure, ScriptFailure}
import scalus.uplc.eval.EvaluationFailure
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError
import xyz.kd5ujc.scarab.ledger.PlutusFailure
import xyz.kd5ujc.scarab.plutus.{DataSerialiser, EvalRequest, PlutusLanguage, ScalusEvaluator}

/** Wire presentation only: ledger-core/Plutus/Evaluate.hs `ToCBOR PlutusWithContext` and
  * Alonzo/Rules/Utxos.hs `scriptFailureToFailureDescription`, pinned at f649f975.
  */
private[ntc] object PlutusFailureCbor:
  def context(request: EvalRequest): Bytes = CborOut.build { w =>
    w.array(5).uint(request.protocolMajor)
    w.array(2).uint(request.language.id).bytes(request.script)
    // PlutusV3 and PlutusV4 arguments are one script context, not a list
    if request.language.evaluatedAs != PlutusLanguage.V3 then w.array(request.args.size)
    request.args.foreach(arg =>
      w.raw(Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(arg))))
    )
    w.array(2).uint(request.budget.mem).uint(request.budget.steps)
    w.array(request.costModel.size)
    request.costModel.foreach(value => w.int(BigInt(value)))
  }

  /** `explainPlutusEvaluationError` for an explicit UPLC error. Other CEK failure kinds, and a
    * PlutusV4 script, whose context has no printer yet ([[PlutusContextText.Error.Unported]]),
    * retain their provider description until their printers are ported (#796). This function
    * retains an invalid context as a typed presentation defect: it has no rejection bytes.
    * Production contexts come from the ledger's typed builders, so a malformed context indicates a
    * builder defect or a synthetic request.
    */
  def description(failure: PlutusFailure): Either[PlutusContextText.Error, Option[String]] =
    failure.request.filter(_ => explicitError(failure.cause)) match
      case Some(request) =>
        PlutusContextText.arguments(request) match
          case Left(PlutusContextText.Error.Unported(_)) => Right(None)
          case described =>
            described.map { arguments =>
              val language = s"PlutusV${request.language.id + 1}"
              val binary = Base64.getEncoder.encodeToString(request.script.toArray)
              val hash =
                Blake2b.hash224.hash(
                  Bytes((request.language.id + 1).toByte +: request.script.value)
                )
              Some(
                Vector(
                  "",
                  s"The $language script failed:",
                  "Base64-encoded script bytes:",
                  HaskellShow.text(binary),
                  s"The script hash is:ScriptHash \"${hash.hex}\"",
                  "The plutus evaluation error is: CekError An error has occurred:\n" +
                    "The machine terminated because of an error, either from a built-in function or from an explicit use of 'error'.\n" +
                    "Caused by: error",
                  s"The protocol version is: Version ${request.protocolMajor}",
                  arguments.render
                ).mkString("", "\n", "\n")
              )
            }
      case None => Right(None)

  /** An evaluation that ended at an explicit `error` term, from either backend: Scalus's
    * `EvaluationFailure`, or the own evaluator's `CekError.ErrorTerm`.
    */
  private def explicitError(cause: EvalFailure): Boolean = cause match
    case EvalFailure.Script(ScriptFailure.Thrown(_: EvaluationFailure, _)) => true
    case EvalFailure.Script(
          ScriptFailure.Uplc(EvaluationError.Cek(CekFailure(CekError.ErrorTerm, _)), _)
        ) =>
      true
    case _ => false

  def reconstruction(failure: PlutusFailure): Bytes =
    failure.request.fold(failure.reconstructionDetail) { request =>
      Bytes.fromArray(Base64.getEncoder.encode(context(request).toArray))
    }
