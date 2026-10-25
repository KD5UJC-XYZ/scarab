// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, DecodingFailure, Json}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.plutus.SliceByteStringCorpus.Kind

/** `sliceByteString` with indices outside the 32-bit range and outside Int64 against the executed
  * reference (plutus 1.70.0.0, `PlutusCore/Default/Builtins.hs` `SliceByteString` and
  * `PlutusCore/Default/Universe.hs` `readKnownAsInteger`; recipe in
  * `scripts/oracle/plutus/README.md`): the reference's verdict and, on success, its exact budget
  * for every probe of [[SliceByteStringCorpus]] in every ledger profile of the costing matrix.
  */
class SliceByteStringReferenceSuite extends FunSuite:
  private enum Stage:
    case Success, Evaluation

  private given Decoder[Stage] = Decoder.instance { cursor =>
    cursor.as[String].flatMap {
      case "success"    => Right(Stage.Success)
      case "evaluation" => Right(Stage.Evaluation)
      case other        => Left(DecodingFailure(s"unexpected oracle stage $other", cursor.history))
    }
  }

  private val rows = Files
    .readAllLines(Path.of("fixtures/reference/plutus-slice-bytestring/verdicts.jsonl"))
    .asScala
    .toVector
    .map(line => io.circe.parser.parse(line).fold(throw _, identity))

  private def field[A: Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  private def recorded(probe: SliceByteStringCorpus.Probe): Vector[Json] =
    rows.filter(row => field[String](row, "id") == probe.id)

  test("the recorded verdicts cover every probe in every ledger profile") {
    val expected = (for
      probe <- SliceByteStringCorpus.probes
      (language, protocol) <- SliceByteStringCorpus.profiles
    yield (probe.id, language, protocol)).toSet
    val actual = rows
      .map(row =>
        (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
      )
      .toSet
    assertEquals(rows.size, expected.size)
    assertEquals(actual, expected)
    assert(rows.forall(row => field[Boolean](row, "admitted")))
  }

  test("the corpus has the 32-bit and Int64 edges as both indices, on both bytestrings") {
    val edges = SliceByteStringCorpus.wideInt64 ++ SliceByteStringCorpus.beyondInt64
    for
      edge <- edges
      bytes <- Vector(SliceByteStringCorpus.short, SliceByteStringCorpus.empty)
    do
      val probes = SliceByteStringCorpus.probes.filter(_.bytes == bytes)
      assert(probes.exists(_.from == edge), s"from $edge on ${bytes.size} bytes")
      assert(probes.exists(_.len == edge), s"len $edge on ${bytes.size} bytes")
    val wrapped = SliceByteStringCorpus.probes.filter(_.kind == Kind.Wrapped)
    assert(wrapped.exists(_.len == 2) && wrapped.exists(_.from == 1))
  }

  /** The reference agrees with the clamped bytes of every Int64 probe, rejects the bytes of a
    * 32-bit truncation, and fails every probe with an index outside Int64.
    */
  for kind <- Kind.values do
    test(s"the reference's verdict on every $kind probe") {
      val want = if kind == Kind.Result then Stage.Success else Stage.Evaluation
      SliceByteStringCorpus.probes.filter(_.kind == kind).foreach { probe =>
        val stages = recorded(probe).map(field[Stage](_, "stage")).distinct
        assertEquals(stages, Vector(want), probe.id)
      }
    }

  private val evaluator = ScalusEvaluator.pure

  /** Scarab's outcome as the reference's row records it: the stage and, on success, the budget. */
  private def outcome(probe: SliceByteStringCorpus.Probe, row: Json): (Stage, Option[ExUnits]) =
    val languageId = field[Int](row, "language")
    val language = PlutusLanguage.values
      .find(_.id + 1 == languageId)
      .getOrElse(fail(s"unexpected fixture language $languageId"))
    val protocol = field[Int](row, "protocol")
    val model = BuiltinCostingCorpus.parameters(languageId, protocol)
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
      case EvalResult.Success(used, _)                     => (Stage.Success, Some(used))
      case EvalResult.Failure(EvalFailure.Script(_), _, _) => (Stage.Evaluation, None)
      case other => fail(s"$language/$protocol: unexpected outcome $other")

  private def reference(row: Json): (Stage, Option[ExUnits]) =
    val stage = field[Stage](row, "stage")
    val budget = Option.when(stage == Stage.Success)(
      ExUnits(BigInt(field[Long](row, "memory")), BigInt(field[Long](row, "cpu")))
    )
    (stage, budget)

  for probe <- SliceByteStringCorpus.probes do
    test(s"${probe.id}: verdict and budget equal the reference's") {
      val rows = recorded(probe)
      assertEquals(rows.size, SliceByteStringCorpus.profiles.size)
      rows.foreach { row =>
        val where = s"${probe.id} V${field[Int](row, "language")}/${field[Int](row, "protocol")}"
        assertEquals(outcome(probe, row), reference(row), where)
      }
    }
