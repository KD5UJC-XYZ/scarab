// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import xyz.kd5ujc.scarab.codec.HaskellShow
import java.util.Base64
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.PlutusFailure
import xyz.kd5ujc.scarab.plutus.{
  DataSerialiser,
  EvalFailure,
  EvalRequest,
  PlutusLanguage,
  ReferenceDiagnostic,
  ScalusEvaluator
}

/** Wire presentation only: ledger-core/Plutus/Evaluate.hs `ToCBOR PlutusWithContext` and
  * Alonzo/Rules/Utxos.hs `scriptFailureToFailureDescription`, pinned at f649f975.
  */
private[ntc] object PlutusFailureCbor:
  /** The machine evaluated `error` (`CekInternal.hs:874-875`, Plutus 507cab8c). */
  private val ExplicitError = ReferenceDiagnostic.EvaluationError.Cek(
    ReferenceDiagnostic.ErrorWithCause(
      ReferenceDiagnostic.CekError.Operational(ReferenceDiagnostic.CekUserError.EvaluationFailure),
      Some(ReferenceDiagnostic.Term.Error)
    )
  )

  def context(request: EvalRequest): Bytes = CborOut.build { w =>
    w.array(5).uint(request.protocolMajor)
    w.array(2).uint(request.language.id).bytes(request.script)
    if request.language != PlutusLanguage.V3 then w.array(request.args.size)
    request.args.foreach(arg =>
      w.raw(Bytes.fromArray(DataSerialiser.serialise(ScalusEvaluator.toData(arg))))
    )
    w.array(2).uint(request.budget.mem).uint(request.budget.steps)
    w.array(request.costModel.size)
    request.costModel.foreach(value => w.int(BigInt(value)))
  }

  /** `explainPlutusEvaluationError` for an explicit UPLC error. Other CEK failure kinds retain
    * their provider description until their required typed cause printers are ported (#796). This
    * function retains an invalid context as a typed presentation defect: it has no rejection bytes.
    * Production contexts come from the ledger's typed builders, so a malformed context indicates a
    * builder defect or a synthetic request.
    */
  def description(failure: PlutusFailure): Either[PlutusContextText.Error, Option[String]] =
    (failure.request, failure.cause) match
      case (Some(request), EvalFailure.Refused(ExplicitError, _)) =>
        PlutusContextText.arguments(request).map { arguments =>
          val language = s"PlutusV${request.language.id + 1}"
          val binary = Base64.getEncoder.encodeToString(request.script.toArray)
          val hash =
            Blake2b.hash224.hash(Bytes((request.language.id + 1).toByte +: request.script.value))
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
      case _ => Right(None)

  def reconstruction(failure: PlutusFailure): Bytes =
    failure.request.fold(failure.reconstructionDetail) { request =>
      Bytes.fromArray(Base64.getEncoder.encode(context(request).toArray))
    }
