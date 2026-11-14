// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.{Decoder, Json}
import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx
import xyz.kd5ujc.scarab.plutus.ReferenceDiagnostic.Term

class ReferenceEvaluationTextSuite extends FunSuite:
  import ReferenceDocument.*
  import ReferencePresentationCases.{Kind, Outcome}

  private def field[A: Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).fold(error => fail(error.message), identity)

  private val observations =
    parse(
      Files.readString(Path.of("fixtures/reference/plutus-error-presentation/observations.json"))
    )
      .fold(error => fail(error.message), identity)
      .asArray
      .getOrElse(fail("native presentation observations must be an array"))

  private def compare(row: Json, document: Doc): Unit =
    val width = field[Int](row, "width")
    val ribbon = field[Int](row, "ribbonColumns")
    assertEquals(
      document.renderWith(Mode.Pretty, width, Some(ribbon)),
      field[String](row, "layoutPretty")
    )
    assertEquals(
      document.renderWith(Mode.Smart, width, Some(ribbon)),
      field[String](row, "layoutSmart")
    )
    assertEquals(document.render, field[String](row, "pretty"))

  private def errorCase(id: String, row: Json, input: String = "subject"): Unit =
    test(s"$id: complete native error Show and Pretty") {
      val value = ReferencePresentationCases
        .evaluation(field[Json](row, input))
        .fold(error => fail(error.toString), identity)
      compare(row, ReferenceEvaluationText.pretty(value))
      assertEquals(ReferenceEvaluationText.show(value), field[String](row, "show"))
    }

  observations.foreach { row =>
    val id = field[String](row, "id")
    ReferencePresentationCases.kind(row).fold(error => fail(error.toString), identity) match
      case Kind.Term =>
        test(s"$id: complete native classic/readable term text") {
          val value = ReferencePresentationCases
            .term(field[Json](row, "subject"))
            .fold(error => fail(error.toString), identity)
          compare(row, ReferenceUplc.classic(value))
          assertEquals(ReferenceUplc.readable(value).render, field[String](row, "readable"))
        }
      case Kind.Universe =>
        test(s"$id: complete native universe text") {
          val value = ReferencePresentationCases.universeInput(field[Json](row, "subject"))
            .fold(error => fail(error.toString), identity)
          compare(row, ReferenceUplc.universeArgument(value))
        }
      case Kind.EvaluationError => errorCase(id, row)
      case Kind.HelperError => errorCase(id, row, "diagnosticInput")
      case Kind.Document =>
        test(s"$id: complete native document layout") {
          val value = ReferencePresentationCases
            .document(field[Json](row, "subject"))
            .fold(error => fail(error.toString), identity)
          compare(row, value)
        }
      case Kind.Evaluate =>
        ReferencePresentationCases.outcome(row).fold(error => fail(error.toString), identity) match
          case Outcome.Failure => errorCase(id, row)
          // Successful evaluations and compilation have no error presentation. Their complete
          // observations are consumed by the evaluator/capture suites.
          case Outcome.Success => ()
      case Kind.Compile => ()
  }

  test("native presentation observations are nonempty") {
    assert(observations.nonEmpty)
  }

  test("deep input-controlled application spines render on a small stack") {
    val value = (0 until 30000).foldLeft[Term](Term.Var("i", 1)) { (head, _) =>
      Term.Apply(head, Term.Var("i", 1))
    }
    val result = PreprodDeepTx.onSmallStack(ReferenceUplc.readable(value).render)
    assertEquals(result.count(_ == '!'), 30001)
  }

  test("deep classic terms render on a small stack with an unbounded page") {
    val value = (0 until 10000).foldLeft[Term](Term.Error)((body, _) => Term.Delay(body))
    val result = PreprodDeepTx.onSmallStack(
      ReferenceUplc.classic(value).renderWith(Mode.Unbounded)
    )
    assertEquals(result.count(_ == '('), 10001)
  }
