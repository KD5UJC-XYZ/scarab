// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** Executed plutus-1.70.0.0 verdicts for the flat decoder's reach ([[FlatDecodeCorpus]]):
  * admission, stage and the exact budget of every success.
  */
class FlatDecodeOracleSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(5, "min")

  private val evaluator = ScalusEvaluator.pure
  private val decoder = ScalusEvaluator.pureDecoder
  private val rows = Files
    .readAllLines(Path.of(s"${FlatDecodeCorpus.Directory}/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded verdicts cover every probe at every language and protocol it runs at") {
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
    val expected = for
      probe <- FlatDecodeCorpus.probes
      (language, protocol) <- FlatDecodeCorpus.cells(probe)
    yield (probe.id, language.id + 1, protocol)
    assertEquals(actual.toSet, expected.toSet)
    assertEquals(actual.size, expected.size)
  }

  FlatDecodeCorpus.probes.foreach { probe =>
    test(s"${probe.id}: admission, stage and budget match the reference") {
      rows.filter(row => field[String](row, "id") == probe.id).foreach { row =>
        val languageId = field[Int](row, "language")
        val language = PlutusLanguage.values
          .find(_.id + 1 == languageId)
          .getOrElse(fail(s"unexpected fixture language $languageId"))
        val protocol = field[Int](row, "protocol")
        val model = FlatDecodeCorpus.costModel(language, protocol)
        val modelBytes =
          Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
        assertEquals(
          NativeCrypto.sha256Hex(probe.script.toArray),
          field[String](row, "scriptSha256")
        )
        assertEquals(NativeCrypto.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
        val where = s"$language/$protocol"
        val admitted = decoder.validate(language, protocol, probe.script).isRight
        assertEquals(admitted, field[Boolean](row, "admitted"), where)
        val result = evaluator.evaluate(
          EvalRequest(
            language,
            protocol,
            probe.script,
            Vector.empty,
            model,
            FlatDecodeCorpus.budget
          )
        )
        val stage = result match
          case EvalResult.Success(_, _) => "success"
          case EvalResult.Failure(EvalFailure.Decode(_), _, _) =>
            if admitted then "setup" else "decode"
          case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _) => "scope"
          case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => "budget"
          case EvalResult.Failure(EvalFailure.Unsupported(why), _, _) =>
            fail(s"implementation unavailable at $where: $why")
          case EvalResult.Failure(EvalFailure.Script(_), _, _) => "evaluation"
        assertEquals(stage, field[String](row, "stage"), where)
        result match
          case EvalResult.Success(used, _) =>
            assertEquals(used.steps, BigInt(field[Long](row, "cpu")), where)
            assertEquals(used.mem, BigInt(field[Long](row, "memory")), where)
          case _ => ()
      }
    }
  }
