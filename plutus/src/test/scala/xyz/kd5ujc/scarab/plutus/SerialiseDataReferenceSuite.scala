// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import scalus.cardano.onchain.plutus.prelude.List as PList
import scalus.uplc.builtin.{Builtins, ByteString}
import xyz.kd5ujc.scarab.blst.Blst
import xyz.kd5ujc.scarab.codec.Bytes

/** `serialiseData` of constructor data with a wide index against the executed reference (plutus
  * 1.70.0.0, `PlutusCore/Data.hs` `encodeData`; recipe in `scripts/oracle/plutus/README.md`): the
  * bytes and digests of `fixtures/reference/plutus-serialise-data/vectors.tsv`, and the phase-2
  * verdicts of `verdicts.jsonl`.
  */
class SerialiseDataReferenceSuite extends FunSuite:
  private def hex(h: String): Bytes = Bytes.fromHex(h).fold(e => fail(e.toString), identity)

  test("the retained vectors cover bit lengths 65, 72, 512, 513 and 600 with both signs") {
    val covered = SerialiseDataCorpus.vectors.map(v => (v.bits, v.negative)).toSet
    for bits <- Vector(65, 72, 512, 513, 600); negative <- Vector(false, true) do
      assert(covered.contains((bits, negative)), s"$bits/$negative")
  }

  for v <- SerialiseDataCorpus.vectors do
    test(s"constructor index ${v.name} bits: bytes and digest match the reference") {
      val bytes = DataSerialiser.serialise(ConstrIndex.constr(v.index, PList.Nil))
      assertEquals(Bytes.fromArray(bytes), hex(v.bytes))
      assertEquals(
        Bytes.fromArray(Builtins.sha2_256(ByteString.fromArray(bytes)).bytes),
        hex(v.sha)
      )
    }

  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-serialise-data/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  test("the recorded verdicts cover every probe in V2 and V3 at protocols 9 to 11") {
    val expected = (for
      probe <- SerialiseDataCorpus.probes
      language <- SerialiseDataCorpus.languages
      protocol <- SerialiseDataCorpus.protocols
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

  private def stageOf(probe: SerialiseDataCorpus.Probe, row: Json): String =
    val languageId = field[Int](row, "language")
    val language = SerialiseDataCorpus.languages
      .find(_.id + 1 == languageId)
      .getOrElse(fail(s"unexpected fixture language $languageId"))
    val protocol = field[Int](row, "protocol")
    val model = PlutusBoundaryCorpus.costModel(language, protocol)
    val modelBytes = Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
    assertEquals(Blst.sha256Hex(probe.script.toArray), field[String](row, "scriptSha256"))
    assertEquals(Blst.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
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
      case EvalResult.Success(_, _)                        => "success"
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => "evaluation"
      case other => fail(s"$language/$protocol: unexpected outcome $other")

  // From protocol 11 the reference takes the index of `constrData` as an unsigned 64-bit integer
  // (`Builtins.hs`, `ConstrData`, the `ensurable` variant), so every probe here fails there.
  for probe <- SerialiseDataCorpus.probes do
    test(s"${probe.id}: phase-2 verdict equals the reference's at protocols 9 to 11") {
      rows
        .filter(row => field[String](row, "id") == probe.id)
        .foreach { row =>
          assertEquals(stageOf(probe, row), field[String](row, "stage"), probe.id)
        }
    }
