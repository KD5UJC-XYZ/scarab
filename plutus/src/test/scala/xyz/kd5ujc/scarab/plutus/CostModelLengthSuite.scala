// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, Json, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

/** The evaluator (Scalus 1.4.0's machine, with the reference's budget arithmetic of
  * [[ReferenceCosting]] and [[ReferenceBudget]]) against the unchanged Plutus 1.70.0.0 evaluator
  * under cost-model lists of every length and of extreme or mixed-sign values. The ledger stores
  * whatever length an update or a genesis gives from decoder version 9 and hands the list to
  * `mkEvaluationContext` (`CostModels.hs` `mkCostModel`, cardano-ledger `f649f975`), whose
  * `tagWithParamNames` pads a short list with `maxBound` and drops the values beyond the last name
  * (`PlutusLedgerApi/Common/ParamName.hs` lines 86-112 at plutus `507cab8c`), each with a warning
  * only. Every observation of `fixtures/reference/genesis-cost-models/evaluation.jsonl` is one
  * program under one list: the stage and the exact budget of the reference are the evaluator's,
  * with no observation excluded.
  *
  * The lists also cover the extremes of `Int64` (all `minBound`, `-1`, `0`, `maxBound`) and lists
  * whose values mix signs and magnitudes up to the bounds, where the reference's budget arithmetic
  * saturates and the order of its charges decides the verdict (issue 485); the last test
  * additionally builds a machine for every list that changes one parameter at a time to an extreme,
  * which the reference's `mkCostModel` accepted in each case (`sweep.json`).
  */
class CostModelLengthSuite extends FunSuite:
  override val munitTimeout = scala.concurrent.duration.Duration(10, "min")
  private val directory = Path.of("fixtures/reference/genesis-cost-models")
  private def jsonOf(path: Path): Json =
    parser.parse(Files.readString(path)).fold(throw _, identity)
  private def lines(path: Path): Vector[Json] =
    Files.readString(path).linesIterator.map(l => parser.parse(l).fold(throw _, identity)).toVector
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  private val scripts: Map[String, String] =
    lines(Path.of("fixtures/reference/plutus-costing/scripts.jsonl"))
      .map(row => field[String](row, "id") -> field[String](row, "script"))
      .toMap
  private val models: Map[String, Json] =
    jsonOf(directory.resolve("evaluation-models.json")).asArray.get
      .map(model => field[String](model, "id") -> model)
      .toMap
  private val observations = lines(directory.resolve("evaluation.jsonl"))
  private val unlimited = ExUnits(BigInt(1) << 60, BigInt(1) << 60)

  /** The list a model stands for: its values, one value repeated, or the pinned default of its
    * profile cut or extended with sevens.
    */
  private def values(model: Json): Vector[Long] =
    (field[Option[Vector[Long]]](model, "values"), field[Option[Long]](model, "fill")) match
      case (Some(values), _)  => values
      case (None, Some(fill)) => Vector.fill(field[Int](model, "length"))(fill)
      case (None, None) =>
        val defaults = BuiltinCostingCorpus.parameters(
          field[Int](model, "language"),
          field[Int](model, "protocol")
        )
        (defaults ++ Vector.fill(60)(7L)).take(field[Int](model, "length"))

  private def language(model: Json): PlutusLanguage =
    val id = field[Int](model, "language")
    PlutusLanguage.values.find(_.id + 1 == id).getOrElse(fail(s"language $id"))

  private def stage(result: EvalResult, admitted: Boolean): String = result match
    case EvalResult.Success(_, _)                          => "success"
    case EvalResult.Failure(EvalFailure.Decode(_), _, _)   => if admitted then "setup" else "decode"
    case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _) => "scope"
    case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => "budget"
    case EvalResult.Failure(EvalFailure.Script(_), _, _)   => "evaluation"
    case EvalResult.Failure(EvalFailure.Unsupported(why), _, _) => fail(s"unavailable: $why")

  test("the reference observations cover every model of the fixture") {
    assertEquals(
      observations.map(o => field[String](o, "id").split('|')(1)).toSet,
      models.keySet
    )
  }

  private def disagreements(observed: Vector[Json]): Vector[String] = observed.flatMap { row =>
    val Array(script, modelId) = field[String](row, "id").split('|'): @unchecked
    val model = models(modelId)
    val code = Bytes.fromHex(scripts(script)).fold(e => fail(e.toString), identity)
    val lang = language(model)
    val protocol = field[Int](model, "protocol")
    val admitted = ScalusEvaluator.pureDecoder.validate(lang, protocol, code).isRight
    assertEquals(admitted, field[Boolean](row, "admitted"), row.noSpaces)
    val actual = ScalusEvaluator.pure.evaluate(
      EvalRequest(lang, protocol, code, Vector.empty, values(model), unlimited)
    )
    val id = field[String](row, "id")
    val expectedStage = field[String](row, "stage")
    val stageMismatch =
      Option.when(stage(actual, admitted) != expectedStage)(
        s"$id: stage ${stage(actual, admitted)}, reference $expectedStage"
      )
    val budgetMismatch = Option
      .when(expectedStage == "success") {
        ExUnits(BigInt(field[Long](row, "memory")), BigInt(field[Long](row, "cpu")))
      }
      .filter(_ != actual.spent)
      .map(units => s"$id: spent ${actual.spent}, reference $units")
    stageMismatch.orElse(budgetMismatch)
  }

  test("the evaluator agrees with the reference on every program under every list") {
    assertEquals(disagreements(observations), Vector.empty[String])
  }

  test("Scalus builds a machine for every list the reference's mkCostModel accepted in the sweep") {
    val sweep = jsonOf(directory.resolve("sweep.json"))
    val extremes = Vector(Long.MinValue, -1L, 0L, Long.MaxValue)
    val code = Bytes
      .fromHex(scripts("upstream/addInteger/addInteger-01/addInteger-01.uplc"))
      .fold(e => fail(e.toString), identity)
    val refused = Vector((1, 5, "PlutusV1"), (2, 7, "PlutusV2"), (3, 11, "PlutusV3")).flatMap {
      (number, protocol, name) =>
        val base = BuiltinCostingCorpus.parameters(number, protocol)
        val lang =
          PlutusLanguage.values.find(_.id + 1 == number).getOrElse(fail(s"language $number"))
        assertEquals(
          field[Int](sweep.hcursor.downField(name).focus.get, "observations"),
          base.size * extremes.size
        )
        for
          index <- base.indices.toVector
          value <- extremes
          result = ScalusEvaluator.pure.evaluate(
            EvalRequest(lang, protocol, code, Vector.empty, base.updated(index, value), unlimited)
          )
          if result match
            case EvalResult.Failure(EvalFailure.Unsupported(_), _, _) => true
            case _                                                    => false
        yield s"$name[$index] = $value"
    }
    assertEquals(refused, Vector.empty[String])
  }
