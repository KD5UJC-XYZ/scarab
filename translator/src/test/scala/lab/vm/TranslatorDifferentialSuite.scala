// SPDX-License-Identifier: Apache-2.0
package lab.vm

import lab.cbor.Bytes
import java.nio.file.{Files, Path}
import scalus.uplc.builtin.Data
import scalus.uplc.builtin.Data.toCbor

class TranslatorDifferentialSuite extends munit.FunSuite:
  private val root = Path.of("").toAbsolutePath.normalize()
  private def text(p: String) = Files.readString(root.resolve(p))
  private def hex(s: String) = Bytes.fromHex(s).fold(fail(_), identity)
  private val rows =
    ujson.read(text("fixtures/plutus-pv9-translator/vectors.json"))("vectors").arr.toVector
  private val expectedNames = Set(
    "base",
    "beneficiary-minimum-payment",
    "input-id-index-amount",
    "fee-payment",
    "key-before-7f-80",
    "key-after-7f-80",
    "same-id-key-index-before",
    "same-id-key-index-after",
    "output-pair",
    "output-pair-reversed",
    "redeemer-eight",
    "interval-lower",
    "interval-upper",
    "interval-both",
    "interval-equal",
    "interval-domain-endpoints",
    "interval-lower-domain-end",
    "interval-upper-domain-start"
  )
  private val model = text("vm/src/main/resources/plutus-pv9/cost-model.json")
  private val source = text("vm/src/main/resources/plutus-pv9/spend.uplc")
  private def tree(data: Data): ujson.Value = data match
    case Data.Constr(n, xs) =>
      ujson.Obj("constructor" -> n.toString, "fields" -> ujson.Arr.from(xs.toScalaList.map(tree)))
    case Data.List(xs) => ujson.Obj("list" -> ujson.Arr.from(xs.toScalaList.map(tree)))
    case Data.Map(xs) =>
      ujson.Obj("map" -> ujson.Arr.from(xs.toScalaList.map((k, v) => ujson.Arr(tree(k), tree(v)))))
    case Data.I(n) => ujson.Obj("integer" -> n.toString)
    case Data.B(x) => ujson.Obj("bytes" -> x.toHex)
  test("exact reviewed variant inventory") {
    assertEquals(rows.map(_("name").str).toSet, expectedNames)
    assertEquals(rows.size, 18)
  }
  rows.foreach { row =>
    val name = row("name").str
    test(s"$name: complete translation parity precedes independent evaluation result") {
      val packet = row("packet"); val reference = row("reference")
      val tx = hex(packet("transactionCborHex").str)
      val pp = hex(packet("parametersCborHex").str)
      val entries = packet("utxo").arr.toVector.map(x =>
        hex(x("inputCborHex").str) -> hex(x("outputCborHex").str)
      )
      val actual = ProfileTranslator.translate(tx, pp, entries).fold(fail(_), identity)
      val expected = hex(reference("contextDataCborHex").str)
      assertEquals(tree(actual), reference("contextDataTree"))
      assertEquals(actual, Data.fromCbor(expected.toArray))
      assertEquals(Bytes.fromArray(actual.toCbor), expected)
      assertEquals(ProfileTranslator.translate(tx, pp, entries.reverse), Right(actual))
      val observed = TranslatedEvaluation.evaluate(actual, model, source)
      val evaluation = reference("evaluation")
      evaluation("status").str match
        case "v3-validation-success" =>
          assertEquals(
            observed,
            Right(Budget(evaluation("cpuSteps").str.toLong, evaluation("memoryUnits").str.toLong))
          )
        case "evaluation-error" =>
          assertEquals(evaluation("category").str, "cek-error-unclassified")
          assertEquals(evaluation("consumedBudget"), ujson.Null)
          assertEquals(observed, Left("explicit-error"))
        case other => fail(s"unexpected reference outcome: $other")
    }
  }
