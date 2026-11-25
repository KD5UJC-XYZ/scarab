// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import cats.mtl.Handle.handleForApplicativeError
import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.era.{
  IsPhase2Valid,
  PlutusEvaluation,
  PlutusWithContext,
  ScriptResult,
  ScriptVerdictUnavailable
}
import xyz.kd5ujc.scarab.ledger.era.rules.{
  ConwayUtxosPredFailure,
  FailureDescription,
  TagMismatchDescription
}
import xyz.kd5ujc.scarab.ledger.state.Conway
import xyz.kd5ujc.scarab.plutus.{ScarabEvaluator, ScriptEvaluator}

/** The reference's script results (`fixtures/reference/script-results`) against Conway's era
  * collection (`collectPlutusScriptsWithContext`) and evaluation (`evalPlutusScripts`): each
  * request's context as the reference serialises it, and the validity-tag refusal of either flag,
  * written by [[ConwayFailureCbor]], byte for byte.
  */
class ScriptResultWireSuite extends FunSuite:
  import xyz.kd5ujc.scarab.ledger.ContextReferenceCases.{field, hex, Recorded}
  private val cases =
    parse(Files.readString(Path.of("fixtures/reference/script-results/aggregation.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))

  private type Verdict[A] = Either[ScriptVerdictUnavailable, A]
  private given ScriptEvaluator[Verdict] = request =>
    Right(ScarabEvaluator.default.evaluate(request))

  cases.foreach { recorded =>
    test(
      s"${recorded.name}: the complete validity-tag refusal and reconstruction match the native oracle"
    ) {
      val scripts = recorded.eraCollect.fold(e => fail(e.toString), identity)
      val expected = field[Vector[Json]](recorded.oracle, "scripts")
      assertEquals(scripts.size, expected.size)
      scripts.zip(expected).foreach { (pwc, reference) =>
        assertEquals(
          PlutusFailureCbor.context(PlutusWithContext.evalRequest(pwc)),
          hex(field[String](reference, "contextCbor"))
        )
      }
      // the transaction flagged as the scripts' verdict contradicts: `ValidationTagMismatch`
      // (`Babbage/Rules/Utxos.hs:130-155` and `:192-222`, cardano-ledger f649f975)
      val (valid, failure) = PlutusEvaluation
        .evalPlutusScripts[Verdict](scripts)
        .fold(e => fail(s"no verdict: $e"), identity) match
        case ScriptResult.Passes(_) =>
          false -> ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
            IsPhase2Valid.Phase2Invalid,
            TagMismatchDescription.PassedUnexpectedly
          )
        case ScriptResult.Fails(_, failures) =>
          true -> ConwayUtxosPredFailure.ValidationTagMismatch[Conway](
            IsPhase2Valid.Phase2Valid,
            TagMismatchDescription.FailedUnexpectedly(
              failures.map(FailureDescription.scriptFailureToFailureDescription)
            )
          )
      val encoded = ConwayFailureCbor.utxos(failure).fold(e => fail(s"$e"), identity)
      val oracleKey = if valid then "validTagFailureCbor" else "invalidTagFailureCbor"
      assertEquals(encoded, hex(field[String](recorded.oracle, oracleKey)))
    }
  }
