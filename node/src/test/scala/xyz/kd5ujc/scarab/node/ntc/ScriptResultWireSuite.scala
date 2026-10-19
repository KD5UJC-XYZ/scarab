// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.Json
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{Cbor, Value}
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
      s"${recorded.name}: every original script/context, declared budget and model reconstruct exactly"
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
      val encoded = CborOut.build(w =>
        PredicateFailureCbor.utxos(w, failure)(using
          xyz.kd5ujc.scarab.storage.haskell.state.Versions
            .eraLow(xyz.kd5ujc.scarab.ledger.state.ShelleyBasedEra.SConway)
        )
      )
      val oracleKey = if valid then "validTagFailureCbor" else "invalidTagFailureCbor"
      val reference = hex(field[String](recorded.oracle, oracleKey))
      // Diagnostic prose is presentation; compare all structural fields and exact reconstruction bytes.
      val actualNode = Cbor.decode(encoded).fold(e => fail(e.toString), identity)
      val expectedNode = Cbor.decode(reference).fold(e => fail(e.toString), identity)
      if outcome.passed then assertEquals(encoded, reference)
      else
        def reconstructed(
            node: xyz.kd5ujc.scarab.codec.Node
        ): Vector[xyz.kd5ujc.scarab.codec.Bytes] = node.value match
          case Value.Arr(Vector(_, _, desc)) =>
            desc.value match
              case Value.Arr(Vector(_, failures)) =>
                failures.value match
                  case Value.Arr(items) =>
                    items.map(_.value match
                      case Value.Arr(Vector(_, _, payload)) =>
                        payload.value match
                          case Value.ByteString(bytes) => bytes
                          case other                   => fail(s"not reconstruction bytes: $other")
                      case other => fail(s"not a failure description: $other"))
                  case other => fail(s"not failures: $other")
              case other => fail(s"not failed unexpectedly: $other")
          case other => fail(s"not a mismatch: $other")
        assertEquals(reconstructed(actualNode), reconstructed(expectedNode))
    }
  }
