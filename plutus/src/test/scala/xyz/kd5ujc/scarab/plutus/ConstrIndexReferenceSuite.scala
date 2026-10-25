// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.Data
import xyz.kd5ujc.scarab.codec.Bytes

/** `constrData` at the sign and Word64 boundaries of its index against the executed reference
  * (plutus 1.70.0.0, `Builtins.hs` `ConstrData`; recipe in `scripts/oracle/plutus/README.md`): the
  * reference's verdict and, on success, its exact budget for every probe of [[ConstrIndexCorpus]]
  * in V2 and V3 at protocols 9 to 11.
  */
class ConstrIndexReferenceSuite extends FunSuite:
  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.toString), identity)

  private val one = PList.from(List(Data.I(1)))

  test("a negative index builds and reads back through every Data.Constr reader") {
    val negative = ConstrIndex.constr(BigInt(-1), one)
    negative match
      case Data.Constr(i, fields) =>
        assertEquals(i, BigInt(-1))
        assertEquals(fields, one)
      case other => fail(s"not a constructor: $other")
    assertEquals(negative, ConstrIndex.constr(BigInt(-1), one))
    assertEquals(negative.hashCode, ConstrIndex.constr(BigInt(-1), one).hashCode)
    assertNotEquals(negative, Data.Constr(BigInt(0), one))
    assertNotEquals[Data, Data](Data.Constr(BigInt(0), one), negative)
    assertNotEquals(negative, ConstrIndex.constr(BigInt(-2), one))
  }

  test("a non-negative index is an ordinary Scalus Data.Constr") {
    assertEquals(ConstrIndex.constr(BigInt(3), one).getClass, classOf[Data.Constr])
  }

  for index <- ConstrIndexCorpus.indices do
    test(s"serialiseData of constructor ${index.value}: the reference's bytes") {
      val empty = DataSerialiser.serialise(ConstrIndex.constr(index.value, PList.Nil))
      val field = DataSerialiser.serialise(ConstrIndex.constr(index.value, one))
      assertEquals(Bytes.fromArray(empty), hex(index.empty))
      assertEquals(Bytes.fromArray(field), hex(index.oneField))
    }

  private val rows = Files
    .readAllLines(
      Path.of("fixtures/reference/plutus-serialise-data/constr-index-verdicts.jsonl")
    )
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded verdicts cover every probe in V2 and V3 at protocols 9 to 11") {
    val expected = (for
      probe <- ConstrIndexCorpus.probes
      language <- ConstrIndexCorpus.languages
      protocol <- ConstrIndexCorpus.protocols
    yield (probe.id, language.id + 1, protocol)).toSet
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
      .toSet
    assertEquals(rows.size, expected.size)
    assertEquals(actual, expected)
  }

  private val evaluator = ScalusEvaluator.pure

  /** Scarab's outcome as the reference's row records it: the stage and, on success, the budget. */
  private def outcome(probe: ConstrIndexCorpus.Probe, row: Json): (String, Option[ExUnits]) =
    val languageId = field[Int](row, "language")
    val language = ConstrIndexCorpus.languages
      .find(_.id + 1 == languageId)
      .getOrElse(fail(s"unexpected fixture language $languageId"))
    val protocol = field[Int](row, "protocol")
    val model = PlutusBoundaryCorpus.costModel(language, protocol)
    val modelBytes = Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
    assertEquals(NativeCrypto.sha256Hex(probe.script.toArray), field[String](row, "scriptSha256"))
    assertEquals(NativeCrypto.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
    evaluator.evaluate(
      EvalRequest(
        language,
        protocol,
        probe.script,
        Vector.empty,
        model,
        PlutusBoundaryCorpus.budget
      )
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

  for probe <- ConstrIndexCorpus.probes do
    test(s"${probe.id}: verdict and budget equal the reference's at protocols 9 to 11") {
      val recorded = rows.filter(row => field[String](row, "id") == probe.id)
      assertEquals(recorded.size, 6)
      recorded.foreach { row =>
        val where = s"${probe.id} V${field[Int](row, "language")}/${field[Int](row, "protocol")}"
        assertEquals(outcome(probe, row), reference(row), where)
      }
    }
