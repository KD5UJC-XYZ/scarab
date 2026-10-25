// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data
import xyz.kd5ujc.scarab.blst.Blst

/** `equalsData` against the executed reference (plutus 1.70.0.0, `PlutusCore/Data.hs`, derived
  * `Eq`: the entries of a `Map` are an ordered list; recipe in `scripts/oracle/plutus/README.md`):
  * the reference's verdict and, on success, its exact budget for every probe of
  * [[EqualsDataCorpus]] in V1 to V3 at protocols 9 to 11.
  */
class DataEqualityReferenceSuite extends FunSuite:
  private def i(n: Int): Data = Data.I(BigInt(n))
  private def map(entries: (Data, Data)*): Data = Data.Map(PList.from(entries.toList))
  private def list(items: Data*): Data = Data.List(PList.from(items.toList))

  private val ab = map(i(1) -> i(1), i(2) -> i(2))
  private val ba = map(i(2) -> i(2), i(1) -> i(1))

  test("reproducer: Scalus compares a Data.Map as a set of its entries") {
    // The reference says False for both pairs; the machine's builtin must not use `==`.
    assertEquals(ab, ba, "fixed upstream? Scalus now compares the entries in order")
    assertEquals(ab, map(i(1) -> i(1), i(2) -> i(2), i(1) -> i(1)), "fixed upstream?")
  }

  test("DataEquality compares the entries of a map in order, and repeated entries") {
    assert(!DataEquality.equal(ab, ba))
    assert(!DataEquality.equal(ab, map(i(1) -> i(1), i(2) -> i(2), i(1) -> i(1))))
    assert(!DataEquality.equal(map(i(1) -> i(1), i(1) -> i(1)), map(i(1) -> i(1))))
    assert(!DataEquality.equal(list(ab), list(ba)))
    assert(DataEquality.equal(ab, map(i(1) -> i(1), i(2) -> i(2))))
    assert(DataEquality.equal(map(), map()))
    assert(!DataEquality.equal(map(), list()))
    assert(!DataEquality.equal(map(i(1) -> i(2)), map(i(2) -> i(1))))
  }

  test("DataEquality reads a constructor through its accessor and compares the field count") {
    def constr(tag: Int, fields: Data*): Data = Data.Constr(BigInt(tag), PList.from(fields.toList))
    assert(DataEquality.equal(constr(3, i(1)), constr(3, i(1))))
    assert(!DataEquality.equal(constr(3, i(1)), constr(4, i(1))))
    assert(!DataEquality.equal(constr(3, i(1)), constr(3, i(1), i(1))))
    assert(!DataEquality.equal(constr(0), list()))
  }

  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val worker = Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case e: Throwable => Left(e)
        ),
      "small-stack",
      256L * 1024
    )
    worker.start()
    worker.join()
    result.getOrElse(fail("worker did not finish")).fold(throw _, identity)

  private def nested(levels: Int, bottom: Data)(wrap: Data => Data): Data =
    (1 to levels).foldLeft(bottom)((inner, _) => wrap(inner))

  test("DataEquality is stack-safe on data 200,000 levels deep (small-stack thread)") {
    val levels = 200000
    val (listA, listB) =
      (nested(levels, ab)(x => list(x)), nested(levels, ab)(x => list(x)))
    val listDifferent = nested(levels, ba)(x => list(x))
    val mapA = nested(levels, ab)(x => map(i(0) -> x))
    val mapB = nested(levels, ab)(x => map(i(0) -> x))
    val mapDifferent = nested(levels, ba)(x => map(i(0) -> x))
    val results = onSmallStack(
      Vector(
        DataEquality.equal(listA, listB),
        DataEquality.equal(listA, listDifferent),
        DataEquality.equal(mapA, mapB),
        DataEquality.equal(mapA, mapDifferent)
      )
    )
    assertEquals(results, Vector(true, false, true, false))
  }

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

  test("the reference accepts every script and disagrees with Scalus's equality on the probes") {
    assert(rows.forall(row => field[Boolean](row, "admitted")))
    val success = rows.filter(row => field[String](row, "stage") == "success")
    assert(success.nonEmpty && success.size < rows.size)
  }

  private val evaluator = ScalusEvaluator.pure

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
    assertEquals(Blst.sha256Hex(script.toArray), field[String](row, "scriptSha256"))
    assertEquals(Blst.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
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
