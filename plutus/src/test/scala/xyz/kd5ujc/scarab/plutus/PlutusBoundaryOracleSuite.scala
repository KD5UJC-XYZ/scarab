// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Executed plutus-1.70.0.0 verdicts with its real crypto backend, not source-only predictions. */
class PlutusBoundaryOracleSuite extends FunSuite:
  private val evaluator = ScalusEvaluator.pure
  private val decoder = ScalusEvaluator.pureDecoder
  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-boundaries/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded matrix covers every probe in all three languages and protocol versions") {
    assertEquals(rows.size, PlutusBoundaryCorpus.probes.size * 9)
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
      .toSet
    val expected = (for
      probe <- PlutusBoundaryCorpus.probes
      language <- PlutusLanguage.values
      protocol <- 9 to 11
    yield (probe.id, language.id + 1, protocol)).toSet
    assertEquals(actual, expected)
  }

  PlutusBoundaryCorpus.probes.foreach { probe =>
    test(s"${probe.id}: original-byte decoder admission and evaluation stage match the reference") {
      val cases = rows.filter(row => field[String](row, "id") == probe.id)
      cases.foreach { row =>
        val languageId = field[Int](row, "language")
        val language = PlutusLanguage.values
          .find(_.id + 1 == languageId)
          .getOrElse(fail(s"unexpected fixture language $languageId"))
        val protocol = field[Int](row, "protocol")
        val model = PlutusBoundaryCorpus.costModel(language, protocol)
        val modelBytes =
          Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
        assertEquals(
          NativeCrypto.sha256Hex(probe.script.toArray),
          field[String](row, "scriptSha256")
        )
        assertEquals(NativeCrypto.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
        val admitted = decoder.validate(language, protocol, probe.script).isRight
        assertEquals(admitted, field[Boolean](row, "admitted"), s"$language/$protocol")
        val result = evaluator.evaluate(
          EvalRequest(
            language,
            protocol,
            probe.script,
            Vector.empty,
            model,
            PlutusBoundaryCorpus.budget
          )
        )
        val stage = result match
          case EvalResult.Success(_, _) => "success"
          case EvalResult.Failure(EvalFailure.Decode(_), _, _) =>
            if admitted then "setup" else "decode"
          case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _) => "scope"
          case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => "budget"
          case EvalResult.Failure(EvalFailure.Unsupported(why), _, _) =>
            fail(s"implementation unavailable at $language/$protocol: $why")
          case EvalResult.Failure(EvalFailure.Script(_), _, _) => "evaluation"
        assertEquals(stage, field[String](row, "stage"), s"$language/$protocol")
      }
    }
  }
