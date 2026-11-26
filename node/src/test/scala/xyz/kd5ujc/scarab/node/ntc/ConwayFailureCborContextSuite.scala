// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PlutusFailure
import xyz.kd5ujc.scarab.plutus.{EvalFailure, EvalRequest, ExUnits, PlutusLanguage, ScriptFailure}
import xyz.kd5ujc.scarab.uplc.cek.{CekError, CekFailure}
import xyz.kd5ujc.scarab.uplc.ledger.EvaluationError

/** Conway's era encoder where the refusal's printer fails ([[ConwayFailureCbor.described]]): a
  * script's failure whose request the printer of the script context cannot read is Scarab's own
  * defect, `InvalidPlutusContext`, with the failure and the printer's typed cause, never bytes. The
  * reference has no such refusal, so no recorded bytes bind it; a typed context always prints, so
  * only a request built by hand reaches it (#85,
  * https://github.com/KD5UJC-XYZ/scarab/issues/85#issuecomment-7079191550).
  */
class ConwayFailureCborContextSuite extends FunSuite:
  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.message), identity)

  test(
    "a Plutus V1 request with no arguments is InvalidPlutusContext, its cause and failure kept"
  ) {
    val original = EvaluationError.Cek(CekFailure(CekError.ErrorTerm, None))
    val request =
      EvalRequest(PlutusLanguage.V1, 10, hex("0102"), Vector.empty, Vector.empty, ExUnits.zero)
    val failure = PlutusFailure(
      EvalFailure.Script(ScriptFailure.Uplc(original, Vector("trace"))),
      hex("0304"),
      Some(request)
    )
    ConwayFailureCbor.described(failure) match
      case Left(RefusalPresentationError.InvalidPlutusContext(retained, cause)) =>
        assertEquals(cause, PlutusContextText.Error.Arguments(PlutusLanguage.V1, 0))
        assert(retained eq failure)
        retained.cause match
          case EvalFailure.Script(ScriptFailure.Uplc(error, logs)) =>
            assertEquals(error, original)
            assertEquals(logs, Vector("trace"))
          case other => fail(s"changed evaluator cause: $other")
      case other => fail(s"the era encoder described a malformed context: $other")
  }
