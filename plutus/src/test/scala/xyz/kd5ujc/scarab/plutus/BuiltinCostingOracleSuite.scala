// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.blst.Blst
import xyz.kd5ujc.scarab.codec.Bytes

/** Exact ledger-API budgets produced by executing unchanged Plutus 1.70 with real crypto. */
class BuiltinCostingOracleSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")
  private val directory = Path.of("fixtures/reference/plutus-costing")
  private def rows(name: String): Vector[Json] =
    Files
      .readString(directory.resolve(name))
      .linesIterator
      .map(line => parser.parse(line).fold(throw _, identity))
      .toVector
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)
  private val scripts = rows("scripts.jsonl")
  private val models = rows("models.jsonl")
  private val verdicts = rows("verdicts.jsonl")
  private val evaluator = ScalusEvaluator.pure
  private val decoder = ScalusEvaluator.pureDecoder
  private val unlimited = ExUnits(BigInt(1) << 60, BigInt(1) << 60)

  private def stage(result: EvalResult, admitted: Boolean): String = result match
    case EvalResult.Success(_, _)                          => "success"
    case EvalResult.Failure(EvalFailure.Decode(_), _, _)   => if admitted then "setup" else "decode"
    case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _) => "scope"
    case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => "budget"
    case EvalResult.Failure(EvalFailure.Script(_), _, _)   => "evaluation"
    case EvalResult.Failure(EvalFailure.Unsupported(why), _, _) => fail(s"unavailable: $why")

  test("input hashes, original upstream seeds, profiles and cardinality are complete") {
    val manifest =
      parser.parse(Files.readString(directory.resolve("manifest.json"))).fold(throw _, identity)
    val hashes = field[Map[String, String]](manifest, "sha256")
    hashes.foreach { (name, hash) =>
      assertEquals(Blst.sha256Hex(Files.readAllBytes(directory.resolve(name))), hash, name)
    }
    assertEquals(field[Int](manifest, "scripts"), scripts.size)
    assertEquals(field[Int](manifest, "upstreamScripts"), 797)
    assertEquals(field[Int](manifest, "observations"), verdicts.size)
    assertEquals(verdicts.size, scripts.size * models.size)
    assertEquals(models.size, 30)
    assertEquals(
      scripts.map(row => field[String](row, "id")),
      BuiltinCostingCorpus.probes.map(_.id)
    )
    scripts.zip(BuiltinCostingCorpus.probes).foreach { (row, probe) =>
      assertEquals(field[String](row, "script"), probe.script.hex, probe.id)
    }
    val actualProfiles = models
      .map(row =>
        (field[Int](row, "language"), field[Int](row, "protocol"), field[Int](row, "scale"))
      )
      .toSet
    val expectedProfiles =
      (for (language, protocol) <- BuiltinCostingCorpus.profiles; scale <- Vector(1, 2)
      yield (language, protocol, scale)).toSet
    assertEquals(actualProfiles, expectedProfiles)
  }

  test("every released builtin has measured inputs; unreleased cost names remain explicit") {
    val names = BuiltinCostingCorpus.probes.flatMap(_.builtins).toSet
    val released = PlutusAvailability.builtinsAvailableIn(PlutusLanguage.V3, 11).map { builtin =>
      val name = builtin.toString
      name.head.toLower.toString + name.tail
    }
    assertEquals(names, released)
    assertEquals(released.size, 101)
    val costNames = PlutusConformanceSuite
      .json("builtinCostModelE.json")
      .asObject
      .fold(fail("cost model object"))(_.keys.toSet)
    assertEquals(costNames -- released, Set("assetCount", "multiIndexArray", "policies"))
  }

  test("negative control: replacing the requested parameters changes the measured budget") {
    val i = scripts.indexWhere(row => field[String](row, "id") == "bytes/sha2_256/1024")
    assert(i >= 0)
    val code =
      Bytes.fromHex(field[String](scripts(i), "script")).fold(e => fail(e.toString), identity)
    val model = BuiltinCostingCorpus.parameters(1, 5)
    val result = evaluator.evaluate(
      EvalRequest(PlutusLanguage.V1, 5, code, Vector.empty, model.map(_ * 2), unlimited)
    )
    val original = verdicts(i * models.size)
    val changed = verdicts(i * models.size + 1)
    val baselineUnits =
      ExUnits(BigInt(field[Long](original, "memory")), BigInt(field[Long](original, "cpu")))
    val changedUnits =
      ExUnits(BigInt(field[Long](changed, "memory")), BigInt(field[Long](changed, "cpu")))
    assert(result.isSuccess)
    assertEquals(result.spent, changedUnits)
    assertNotEquals(result.spent, baselineUnits)
  }

  models.zipWithIndex.foreach { (model, j) =>
    val languageId = field[Int](model, "language")
    val protocol = field[Int](model, "protocol")
    val scale = field[Int](model, "scale")
    test(
      s"Haskell exact costing and budget boundaries: V$languageId protocol $protocol costs x$scale"
    ) {
      val language =
        PlutusLanguage.values.find(_.id + 1 == languageId).getOrElse(fail(s"language $languageId"))
      val parameters = field[Vector[Long]](model, "costModel")
      assertEquals(parameters.size, if languageId == 3 then 361 else 343)
      assertEquals(parameters, BuiltinCostingCorpus.parameters(languageId, protocol).map(_ * scale))
      scripts.zipWithIndex.foreach { (script, i) =>
        val id = field[String](script, "id")
        val expected = verdicts(i * models.size + j)
        assertEquals(field[String](expected, "id"), s"$i:$j")
        val code =
          Bytes.fromHex(field[String](script, "script")).fold(e => fail(e.toString), identity)
        val admitted = decoder.validate(language, protocol, code).isRight
        assertEquals(admitted, field[Boolean](expected, "admitted"), id)
        val request = EvalRequest(language, protocol, code, Vector.empty, parameters, unlimited)
        val actual = evaluator.evaluate(request)
        assertEquals(stage(actual, admitted), field[String](expected, "stage"), id)
        if field[String](expected, "stage") == "success" then
          val units =
            ExUnits(BigInt(field[Long](expected, "memory")), BigInt(field[Long](expected, "cpu")))
          assertEquals(actual.spent, units, id)
          assertEquals(
            stage(evaluator.evaluate(request.copy(budget = units)), admitted),
            "success",
            s"$id exact"
          )
          assertEquals(
            stage(
              evaluator.evaluate(request.copy(budget = units.copy(steps = units.steps - 1))),
              admitted
            ),
            "budget",
            s"$id CPU - 1"
          )
          assertEquals(
            stage(
              evaluator.evaluate(request.copy(budget = units.copy(mem = units.mem - 1))),
              admitted
            ),
            "budget",
            s"$id memory - 1"
          )
      }
    }
  }
