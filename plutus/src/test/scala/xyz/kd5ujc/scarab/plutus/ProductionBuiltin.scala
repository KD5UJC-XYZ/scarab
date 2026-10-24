// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import scalus.cardano.ledger.CardanoInfo
import scalus.uplc.Program
import xyz.kd5ujc.scarab.codec.Bytes

/** A builtin's answer as the node computes it: a Plutus V3 script run by [[ScalusEvaluator.pure]],
  * so the runtime is the one `CorrectedVm` installs (including its native-library guard).
  */
private[plutus] object ProductionBuiltin:
  enum Answer:
    case Verdict(value: Boolean)
    case Fails(reason: EvalFailure)

  private val model = CardanoInfo.mainnet.protocolParams.costModels.models(2).toVector

  private def hex(bytes: Array[Byte]): String = Bytes.fromArray(bytes).hex

  private def run(body: String): EvalResult =
    val program = Program
      .parseUplc(s"(program 1.0.0 (lam ctx $body))")
      .fold(e => sys.error(e.toString), identity)
    ScalusEvaluator.pure.evaluate(
      EvalRequest(
        PlutusLanguage.V3,
        9,
        Bytes.fromArray(program.cborEncoded),
        Vector(PlutusData.unit),
        model,
        ExUnits(10000000, 10000000000L)
      )
    )

  /** Runs `call` (a term of type bool) twice: once succeeding on `True`, once on `False`. */
  private def answer(call: String): Answer =
    def expecting(verdict: Boolean) =
      val (onTrue, onFalse) =
        if verdict then ("(con unit ())", "(error)") else ("(error)", "(con unit ())")
      run(s"(force [ (force (builtin ifThenElse)) $call (delay $onTrue) (delay $onFalse) ])")
    (expecting(true), expecting(false)) match
      case (_: EvalResult.Success, _)                                      => Answer.Verdict(true)
      case (_, _: EvalResult.Success)                                      => Answer.Verdict(false)
      case (EvalResult.Failure(reason, _, _), EvalResult.Failure(_, _, _)) => Answer.Fails(reason)

  def verifyEcdsa(pk: Array[Byte], msg: Array[Byte], sig: Array[Byte]): Answer =
    answer(
      s"[ (builtin verifyEcdsaSecp256k1Signature) (con bytestring #${hex(pk)}) " +
        s"(con bytestring #${hex(msg)}) (con bytestring #${hex(sig)}) ]"
    )
