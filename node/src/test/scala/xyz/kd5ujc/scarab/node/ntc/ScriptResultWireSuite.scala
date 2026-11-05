// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.*
import xyz.kd5ujc.scarab.plutus.ScalusEvaluator

class ScriptResultWireSuite extends FunSuite:
  import ContextReferenceCases.{field, hex, Recorded}
  private val cases =
    parse(Files.readString(Path.of("fixtures/reference/script-results/aggregation.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("not an array"))
      .map(Recorded(_))

  cases.foreach { recorded =>
    test(
      s"${recorded.name}: the complete validity-tag refusal and reconstruction match the native oracle"
    ) {
      val requests = recorded.collect.fold(e => fail(e.toString), identity)
      val expected = field[Vector[Json]](recorded.oracle, "scripts")
      requests.zip(expected).foreach { case ((_, request), reference) =>
        assertEquals(
          PlutusFailureCbor.context(request),
          hex(field[String](reference, "contextCbor"))
        )
      }
      val outcome = Phase2.run(requests, ScalusEvaluator.default)
      val valid = !outcome.passed
      val failure = outcome match
        case Phase2.Outcome.Passed(_) => UtxosFailure.ValidationTagMismatch(false, None)
        case Phase2.Outcome.Failed(_, failures) =>
          UtxosFailure.ValidationTagMismatch(
            true,
            Some(failures.toVector.map(f => PlutusFailure(f.reason, hex(""), Some(f.request))))
          )
        case other => fail(s"no verdict: $other")
      val encoded = PredicateFailureCbor
        .utxos(failure)(using
          xyz.kd5ujc.scarab.storage.haskell.state.Versions
            .eraLow(xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
        )
        .fold(error => fail(error.message), identity)
      val oracleKey = if valid then "validTagFailureCbor" else "invalidTagFailureCbor"
      val reference = hex(field[String](recorded.oracle, oracleKey))
      assertEquals(encoded, reference)
    }
  }
