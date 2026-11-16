// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

/** `equalsData` against the executed reference (plutus 1.70.0.0, `PlutusCore/Data.hs`, derived
  * `Eq`: the entries of a `Map` are an ordered list; recipe in `scripts/oracle/plutus/README.md`):
  * the reference's verdict and, on success, its exact budget for every probe of
  * [[EqualsDataCorpus]] in V1 to V3 at protocols 9 to 11.
  */
class DataEqualityReferenceSuite extends FunSuite:
  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-equals-data/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded verdicts cover every probe in V1 to V3 at protocols 9 to 11") {
    val expected = (for
      probe <- EqualsDataCorpus.probes
      language <- EqualsDataCorpus.languages
      protocol <- EqualsDataCorpus.protocols
    yield (probe.id, language.id + 1, protocol)).toSet
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
      .toSet
    assertEquals(rows.size, expected.size)
    assertEquals(actual, expected)
  }

  test("the reference accepts every script, and some probes are unequal") {
    assert(rows.forall(row => field[Boolean](row, "admitted")))
    val success = rows.filter(row => field[String](row, "stage") == "success")
    assert(success.nonEmpty && success.size < rows.size)
  }

  private val evaluator = ScarabEvaluator.pure

  /** Scarab's outcome as the reference's row records it: the stage and, on success, the budget. */
  private def outcome(probe: EqualsDataCorpus.Probe, row: Json): (String, Option[ExUnits]) =
    val languageId = field[Int](row, "language")
    val language = EqualsDataCorpus.languages
      .find(_.id + 1 == languageId)
      .getOrElse(fail(s"unexpected fixture language $languageId"))
    val protocol = field[Int](row, "protocol")
    val model = PlutusBoundaryCorpus.costModel(language, protocol)
    val modelBytes = Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
    val script = probe.script
    assertEquals(UplcScripts.sha256Hex(script.toArray), field[String](row, "scriptSha256"))
    assertEquals(UplcScripts.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
    evaluator.evaluate(
      EvalRequest(language, protocol, script, Vector.empty, model, PlutusBoundaryCorpus.budget)
    ) match
      case EvalResult.Success(used, _)                     => ("success", Some(used))
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => ("evaluation", None)
      case other => fail(s"$language/$protocol: unexpected outcome $other")

  private def reference(row: Json): (String, Option[ExUnits]) =
    val stage = field[String](row, "stage")
    val budget = Option.when(stage == "success")(
      ExUnits(BigInt(field[Long](row, "memory")), BigInt(field[Long](row, "cpu")))
    )
    (stage, budget)

  for probe <- EqualsDataCorpus.probes do
    test(s"${probe.id}: verdict and budget equal the reference's at protocols 9 to 11") {
      val recorded = rows.filter(row => field[String](row, "id") == probe.id)
      assertEquals(recorded.size, 9)
      recorded.foreach { row =>
        val where = s"${probe.id} V${field[Int](row, "language")}/${field[Int](row, "protocol")}"
        assertEquals(outcome(probe, row), reference(row), where)
      }
    }

  test("data 90,000 levels deep passed as arguments compares in order through the machine") {
    val levels = 90000
    def deep(bottom: PlutusData): PlutusData =
      (1 to levels).foldLeft(bottom)((inner, _) => PlutusData.List(Vector(inner)))
    def pair(a: Int, b: Int) = PlutusData.Map(Vector(PlutusData.I(a) -> PlutusData.I(b)))
    val (ab, ba) = (
      PlutusData.Map(
        Vector(PlutusData.I(1) -> PlutusData.I(1), PlutusData.I(2) -> PlutusData.I(2))
      ),
      PlutusData.Map(Vector(PlutusData.I(2) -> PlutusData.I(2), PlutusData.I(1) -> PlutusData.I(1)))
    )
    val model = PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, 10)
    def run(a: PlutusData, b: PlutusData) =
      evaluator.evaluate(
        EvalRequest(
          PlutusLanguage.V3,
          10,
          EqualsDataCorpus.withArguments,
          Vector(a, b),
          model,
          PlutusBoundaryCorpus.budget
        )
      )
    assert(run(deep(ab), deep(ab)).isInstanceOf[EvalResult.Success], run(deep(ab), deep(ab)))
    assert(
      run(deep(ab), deep(ba)) match
        case EvalResult.Failure(EvalFailure.Script(_), _, _) => true
        case _                                               => false
    )
    assert(pair(1, 2) != pair(2, 1))
  }
