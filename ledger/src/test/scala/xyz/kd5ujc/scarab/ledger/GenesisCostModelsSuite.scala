// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import io.circe.{Decoder, Json, JsonObject, parser}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.CostModelProblem as P
import xyz.kd5ujc.scarab.ledger.GenesisCostModels.Models

/** The genesis cost models against the unchanged reference libraries (issue #369).
  *
  * `fixtures/reference/genesis-cost-models/genesis.jsonl` holds one measurement per line: the JSON
  * the unchanged `FromJSON AlonzoGenesis`, `FromJSON ConwayGenesis`, `parseCostModels` and
  * `mkCostModel` of cardano-ledger `f649f975` were given (the genesis rows are a tracked genesis
  * file with a few keys replaced or removed), the typed refusal Scarab must give for it and the
  * reference's verdict: the cost models it kept, or its error. Each case is read by
  * [[GenesisCostModels]] and its verdict compared with the reference's, to the values.
  */
class GenesisCostModelsSuite extends FunSuite:
  private val directory = Path.of("fixtures/reference/genesis-cost-models")
  private def jsonOf(path: String): Json =
    parser.parse(Files.readString(Path.of(path))).fold(throw _, identity)
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)

  private val rows: Vector[Json] =
    Files
      .readString(directory.resolve("genesis.jsonl"))
      .linesIterator
      .map(parser.parse(_).fold(throw _, identity))
      .toVector

  private val templates = Map(
    "alonzo-preview" -> "fixtures/preview/genesis/alonzo-genesis.json",
    "alonzo-eras" -> "fixtures/eras/genesis/alonzo-genesis.json",
    "conway-preview" -> "fixtures/preview/genesis/conway-genesis.json"
  )

  /** A tracked genesis file with the keys of `delete` removed and those of `set` replaced. */
  private def expand(spec: Json): Json =
    val template = field[String](spec, "template")
    val base = jsonOf(templates.getOrElse(template, fail(s"template $template"))).asObject
      .getOrElse(fail(s"template $template is not an object"))
    val deleted = field[Option[Vector[String]]](spec, "delete").getOrElse(Vector.empty)
    val set = field[Option[JsonObject]](spec, "set").getOrElse(JsonObject.empty)
    Json.fromJsonObject(
      set.toIterable.foldLeft(deleted.foldLeft(base)(_.remove(_)))((o, entry) =>
        o.add(entry._1, entry._2)
      )
    )

  /** What a row expects of Scarab: the cost models, or the typed refusal. */
  private enum Category:
    case Accepted, NotObject, NotObjectOrArray, NotArray, NotInt64, MissingField, MissingLanguage,
      MissingParameters, ParameterCount, UnknownKey

  /** The category a row names, written as the case in lower camel case. */
  private def category(row: Json): Category =
    val name = field[String](row, "category")
    Category.values
      .find(c => c.toString.take(1).toLowerCase + c.toString.drop(1) == name)
      .getOrElse(fail(s"category $name"))

  private def category(problem: CostModelProblem): Category = problem match
    case _: P.NotObject         => Category.NotObject
    case _: P.NotObjectOrArray  => Category.NotObjectOrArray
    case _: P.NotArray          => Category.NotArray
    case _: P.NotInt64          => Category.NotInt64
    case _: P.MissingField      => Category.MissingField
    case _: P.MissingLanguage   => Category.MissingLanguage
    case _: P.MissingParameters => Category.MissingParameters
    case _: P.ParameterCount    => Category.ParameterCount
    case _: P.UnknownKey        => Category.UnknownKey

  /** The reference's `{known, unknown}` of `CostModels`, as [[Models]]. */
  private def models(reference: Json): Models =
    val known = field[Vector[Json]](reference, "known").map { entry =>
      val name = field[String](entry, "language")
      CostModelLanguage.values.find(_.key == name).getOrElse(fail(s"language $name")) ->
        field[Vector[Long]](entry, "values")
    }
    val unknown = field[Vector[Json]](reference, "unknown").map(entry =>
      field[Int](entry, "language") -> field[Vector[Long]](entry, "values")
    )
    Models(known.toMap, unknown.toMap)

  /** The reference's verdict on a row: the value it read, or its refusal (whose text is shown). */
  private enum Verdict:
    case Accepted(result: Json)
    case Refused(failure: String)

  private def expectation(row: Json): Verdict =
    val verdict = field[Json](row, "reference")
    if field[Boolean](verdict, "accepted") then Verdict.Accepted(field[Json](verdict, "result"))
    else Verdict.Refused(field[String](verdict, "failure"))

  private def agree[A](row: Json, actual: Either[CostModelProblem, A])(
      same: (A, Json) => Unit
  ): Unit =
    val name = field[String](row, "name")
    val wanted = category(row)
    (actual, expectation(row)) match
      case (Right(value), Verdict.Accepted(result)) =>
        assertEquals(wanted, Category.Accepted, name)
        same(value, result)
      case (Left(problem), Verdict.Refused(_)) => assertEquals(category(problem), wanted, name)
      case (Right(_), Verdict.Refused(failure)) =>
        fail(s"$name: accepted, the reference refused: $failure")
      case (Left(problem), Verdict.Accepted(_)) =>
        fail(s"$name: refused (${problem.message}), the reference accepted")

  private def rowsOf(kind: String): Vector[Json] = rows.filter(field[String](_, "kind") == kind)

  test("the Alonzo genesis cost models, base and extra, are read as the reference reads them") {
    val alonzo = rowsOf("alonzoGenesis")
    assert(alonzo.size > 50)
    alonzo.foreach { row =>
      agree(row, GenesisCostModels.alonzo(expand(field[Json](row, "input")))) { (actual, result) =>
        assertEquals(
          actual.plutusV1,
          field[Vector[Long]](result, "plutusV1"),
          field[String](row, "name")
        )
        assertEquals(
          actual.extra,
          field[Option[Json]](result, "extra").map(models),
          field[String](row, "name")
        )
      }
    }
  }

  test("the Conway genesis PlutusV3 cost model is read as the reference reads it") {
    val conway = rowsOf("conwayGenesis")
    assert(conway.size > 10)
    conway.foreach { row =>
      agree(row, GenesisCostModels.conwayPlutusV3(expand(field[Json](row, "input")))) {
        (actual, result) =>
          assertEquals(actual, field[Vector[Long]](result, "plutusV3"), field[String](row, "name"))
      }
    }
  }

  test("parseCostModels is read in its two modes, over all languages and restricted") {
    val parsed = rowsOf("parseCostModels")
    assert(parsed.size > 20)
    parsed.foreach { row =>
      val input = row.hcursor.downField("input")
      val languages =
        input.get[Vector[String]]("languages").getOrElse(fail("languages")).map { key =>
          CostModelLanguage.values.find(_.key == key).getOrElse(fail(s"language $key"))
        }
      val lenient = input.get[Boolean]("lenient").getOrElse(fail("lenient"))
      val value = input.get[Json]("value").getOrElse(fail("value"))
      agree(row, GenesisCostModels.parse(lenient, languages, value)) { (actual, result) =>
        assertEquals(actual, models(result), field[String](row, "name"))
      }
    }
  }

  test("every mkCostModel measurement of the reference was accepted") {
    val made = rowsOf("mkCostModel")
    assert(made.size > 20)
    made.foreach { row =>
      val name = field[String](row, "name")
      val verdict = field[Json](row, "reference")
      // the request ran (`accepted`) and `mkCostModel` returned a model (`result.valid`)
      assertEquals(field[Boolean](verdict, "accepted"), true, name)
      assertEquals(field[Boolean](field[Json](verdict, "result"), "valid"), true, name)
    }
  }

  test("the categories of the fixture are all produced") {
    assertEquals(rows.map(category(_)).toSet, Category.values.toSet)
  }

  test("the sweep of the reference refused no list") {
    val sweep = jsonOf(directory.resolve("sweep.json").toString)
    sweep.asObject.getOrElse(fail("sweep")).values.foreach { entry =>
      assertEquals(field[Vector[String]](entry, "refused"), Vector.empty[String])
    }
  }
