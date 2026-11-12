// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ProtocolMajor, SemanticsVariant}

/** What the whole-script oracles need of an evaluator: whether the ledger admits a script
  * (`deserialiseScript`) and the stage, and on success the budget, of evaluating it within a limit
  * (`evaluateScriptRestricting`).
  */
trait ScriptEvaluation:
  def admits(language: PlutusLanguage, protocol: ProtocolMajor, script: Bytes): Boolean
  def evaluate(
      language: PlutusLanguage,
      protocol: ProtocolMajor,
      script: Bytes,
      context: EvaluationContext,
      limit: ExBudget
  ): ScriptEvaluation.Stage

object ScriptEvaluation:
  /** The stages the oracles record (`BuiltinCostingOracleSuite`, `CostModelLengthSuite`). */
  enum Stage:
    case Success(spent: ExBudget)
    case Decode, Setup, Scope, Budget, Evaluation

    def name: String = this match
      case Success(_) => "success"
      case Decode     => "decode"
      case Setup      => "setup"
      case Scope      => "scope"
      case Budget     => "budget"
      case Evaluation => "evaluation"

  /** The evaluator the replays run through: the machine (work package 4), the builtins (5 and 6)
    * and the decoder (2) of issue 43 plug in here once they exist. Until then the replays are
    * reported as skipped, not passed.
    */
  val instance: Option[ScriptEvaluation] = None

/** The whole-script costing oracles of plutus 1.70.0.0 (`fixtures/reference/plutus-costing`: 55,740
  * observations of 1,858 scripts under 30 parameter lists, and 111,552 budget boundaries;
  * `fixtures/reference/genesis-cost-models/evaluation.jsonl`: 5,661 observations under lists of
  * every length and of extreme values). The cost model is checked against them now (every list
  * makes a context with the profile's variant); the replays need the machine and the builtins and
  * stay an explicit incomplete gate until [[ScriptEvaluation.instance]] is defined.
  */
final class CostingOracleSuite extends munit.FunSuite:
  private val costing = Path.of("fixtures/reference/plutus-costing")
  private val genesis = Path.of("fixtures/reference/genesis-cost-models")

  private def rows(path: Path): Vector[Json] =
    Files.readString(path).linesIterator.map(parser.parse(_).fold(throw _, identity)).toVector

  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(s"$key: ${e.message}"), identity)

  private lazy val scripts = rows(costing.resolve("scripts.jsonl"))
  private lazy val models = rows(costing.resolve("models.jsonl"))
  private lazy val verdicts = rows(costing.resolve("verdicts.jsonl"))

  private def languageOf(number: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == number).getOrElse(fail(s"language $number"))

  private def sha256(path: Path): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(Files.readAllBytes(path))
      .map(b => f"${b & 0xff}%02x")
      .mkString

  test("the costing oracle's files are the ones its manifest binds, and complete") {
    val manifest =
      parser.parse(Files.readString(costing.resolve("manifest.json"))).fold(throw _, identity)
    field[Map[String, String]](manifest, "sha256").foreach((name, hash) =>
      assertEquals(sha256(costing.resolve(name)), hash, name)
    )
    assertEquals(
      field[String](manifest, "plutusCommit"),
      "507cab8cf6192e5143f12be5b2d3005a2928dc6d"
    )
    assertEquals(scripts.size, field[Int](manifest, "scripts"))
    assertEquals(verdicts.size, field[Int](manifest, "observations"))
    assertEquals(verdicts.size, scripts.size * models.size)
    assertEquals(
      verdicts.count(field[String](_, "stage") == "success"),
      field[Int](manifest, "successes")
    )
  }

  test("every parameter list of the costing oracle makes a context with its profile's variant") {
    models.foreach { model =>
      val language = languageOf(field[Int](model, "language"))
      val protocol = ProtocolMajor(field[Int](model, "protocol"))
      val scale = field[Int](model, "scale")
      val values = field[Vector[Long]](model, "costModel")
      val variant = SemanticsVariant.of(language, protocol)
      assertEquals(
        values,
        DefaultCostModels.list(language, variant).map(_ * scale),
        s"$language $protocol x$scale"
      )
      val (warnings, context) = EvaluationContext.build(language, values)
      assertEquals(warnings, Vector.empty, s"$language $protocol x$scale")
      val costs = context
        .fold(e => fail(e.toString), identity)
        .at(protocol)
        .getOrElse(fail(s"no costs at $protocol"))
      assertEquals(costs.variant, variant)
      assertEquals(
        (costs.model.machine.leaves ++ costs.model.builtins.leaves)
          .map((n, v) => n -> v.toLong)
          .toMap,
        DefaultCostModels.parameters(variant).map((n, v) => n -> v * scale),
        s"$language $protocol x$scale"
      )
    }
  }

  test(
    "replay: the 55,740 observations and 111,552 budget boundaries (incomplete until the machine exists)"
  ) {
    val evaluation = ScriptEvaluation.instance
    assume(
      evaluation.isDefined,
      "incomplete: no machine, builtins or decoder in uplc yet (issue 43, WP2, WP4-WP6)"
    )
    evaluation.foreach { evaluator =>
      val unlimited = ExBudget.of(1L << 60, 1L << 60)
      models.zipWithIndex.foreach { (model, j) =>
        val language = languageOf(field[Int](model, "language"))
        val protocol = ProtocolMajor(field[Int](model, "protocol"))
        val context = EvaluationContext
          .build(language, field[Vector[Long]](model, "costModel"))
          ._2
          .fold(e => fail(e.toString), identity)
        scripts.zipWithIndex.foreach { (script, i) =>
          val id = field[String](script, "id")
          val expected = verdicts(i * models.size + j)
          assertEquals(field[String](expected, "id"), s"$i:$j")
          val code =
            Bytes.fromHex(field[String](script, "script")).fold(e => fail(e.toString), identity)
          assertEquals(
            evaluator.admits(language, protocol, code),
            field[Boolean](expected, "admitted"),
            id
          )
          val actual = evaluator.evaluate(language, protocol, code, context, unlimited)
          assertEquals(actual.name, field[String](expected, "stage"), id)
          if field[String](expected, "stage") == "success" then
            val units = ExBudget.of(field[Long](expected, "cpu"), field[Long](expected, "memory"))
            assertEquals(actual, ScriptEvaluation.Stage.Success(units), id)
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, units).name,
              "success",
              s"$id exact"
            )
            val lessCpu = units.copy(cpu = SatInt(units.cpu.toLong - 1))
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, lessCpu).name,
              "budget",
              s"$id CPU - 1"
            )
            val lessMemory = units.copy(memory = SatInt(units.memory.toLong - 1))
            assertEquals(
              evaluator.evaluate(language, protocol, code, context, lessMemory).name,
              "budget",
              s"$id memory - 1"
            )
        }
      }
    }
  }

  test(
    "replay: the genesis cost-model observations, lists of every length (incomplete until the machine exists)"
  ) {
    val observations = rows(genesis.resolve("evaluation.jsonl"))
    assertEquals(observations.size, 5661)
    val evaluation = ScriptEvaluation.instance
    assume(
      evaluation.isDefined,
      "incomplete: no machine, builtins or decoder in uplc yet (issue 43, WP2, WP4-WP6)"
    )
    evaluation.foreach { evaluator =>
      val unlimited = ExBudget.of(1L << 60, 1L << 60)
      val code = scripts.map(row => field[String](row, "id") -> field[String](row, "script")).toMap
      val lists = rows(genesis.resolve("evaluation-models.jsonl"))
        .map(model => field[String](model, "id") -> model)
        .toMap
      observations.foreach { row =>
        val id = field[String](row, "id")
        val (scriptId, modelId) = id.split('|') match
          case Array(s, m) => (s, m)
          case _           => fail(s"id $id")
        val model = lists.getOrElse(modelId, fail(s"no list $modelId"))
        val language = languageOf(field[Int](model, "language"))
        val protocol = ProtocolMajor(field[Int](model, "protocol"))
        // The list's values, one value repeated, or the profile's defaults cut or extended with
        // sevens (the rule of CostModelLengthSuite).
        val values =
          (field[Option[Vector[Long]]](model, "values"), field[Option[Long]](model, "fill")) match
            case (Some(values), _)  => values
            case (None, Some(fill)) => Vector.fill(field[Int](model, "length"))(fill)
            case (None, None) =>
              (DefaultCostModels.list(language, SemanticsVariant.of(language, protocol)) ++ Vector
                .fill(60)(7L))
                .take(field[Int](model, "length"))
        val context =
          EvaluationContext.build(language, values)._2.fold(e => fail(e.toString), identity)
        val script = Bytes
          .fromHex(code.getOrElse(scriptId, fail(s"no script $scriptId")))
          .fold(e => fail(e.toString), identity)
        assertEquals(
          evaluator.admits(language, protocol, script),
          field[Boolean](row, "admitted"),
          id
        )
        val actual = evaluator.evaluate(language, protocol, script, context, unlimited)
        assertEquals(actual.name, field[String](row, "stage"), id)
        if field[String](row, "stage") == "success" then
          assertEquals(
            actual,
            ScriptEvaluation.Stage.Success(
              ExBudget.of(field[Long](row, "cpu"), field[Long](row, "memory"))
            ),
            id
          )
      }
    }
  }
