// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, DecodingFailure, Json}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.blst.Blst
import xyz.kd5ujc.scarab.codec.Bytes

/** The new upstream inputs use the released ledger API as their oracle. The separately executed
  * bounds-only backport records an upgrade difference, never an expectation for current decoding.
  */
class UpstreamPlutusBoundarySuite extends FunSuite:
  private enum Stage:
    case Decode, Setup, Scope, Budget, Evaluation, Success

  private given Decoder[Stage] = Decoder.instance { cursor =>
    cursor.as[String].flatMap {
      case "decode"     => Right(Stage.Decode)
      case "setup"      => Right(Stage.Setup)
      case "scope"      => Right(Stage.Scope)
      case "budget"     => Right(Stage.Budget)
      case "evaluation" => Right(Stage.Evaluation)
      case "success"    => Right(Stage.Success)
      case other        => Left(DecodingFailure(s"unknown oracle stage $other", cursor.history))
    }
  }

  private val root = Path.of("fixtures/reference/plutus-upstream-boundaries")
  private def rows(path: Path): Vector[Json] =
    Files.readAllLines(path).asScala.toVector.map { line =>
      io.circe.parser.parse(line).fold(error => fail(error.message), identity)
    }
  private def field[A: Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)
  private def bytes(row: Json): Bytes =
    Bytes.fromHex(field[String](row, "script")).fold(error => fail(error.message), identity)
  private val scripts = rows(root.resolve("scripts.jsonl"))
  private val observations = rows(root.resolve("verdicts.jsonl"))
  private val decoder = ScalusEvaluator.pureDecoder
  private val evaluator = ScalusEvaluator.pure

  private def observed(stage: Json): (Boolean, Stage) =
    (field[Boolean](stage, "admitted"), field[Stage](stage, "stage"))

  private def stage(admitted: Boolean, result: EvalResult): Stage = result match
    case EvalResult.Success(_, _) => Stage.Success
    case EvalResult.Failure(EvalFailure.Decode(_), _, _) =>
      if admitted then Stage.Setup else Stage.Decode
    case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _) => Stage.Scope
    case EvalResult.Failure(EvalFailure.OutOfBudget, _, _) => Stage.Budget
    case EvalResult.Failure(EvalFailure.Script(_), _, _)   => Stage.Evaluation
    case EvalResult.Failure(EvalFailure.Unsupported(cause), _, _) =>
      fail(s"reference comparison unavailable: $cause")

  test("all six original-byte inputs have complete language/PV9–11 observations") {
    assertEquals(scripts.size, 6)
    assertEquals(observations.size, 54)
    val expected = for
      script <- scripts
      language <- 1 to 3
      protocol <- 9 to 11
    yield (field[String](script, "id"), language, protocol)
    assertEquals(
      observations
        .map(row =>
          (field[String](row, "id"), field[Int](row, "language"), field[Int](row, "protocol"))
        )
        .sorted,
      expected.sorted
    )
  }

  test("the two new upstream Flat files are wrapped unchanged, and old boundary bytes are reused") {
    val prior = rows(Path.of("fixtures/reference/plutus-boundaries/scripts.jsonl"))
    scripts.foreach { row =>
      val id = field[String](row, "id")
      val script = bytes(row)
      field[String](row, "source") match
        case "upstream-flat" =>
          val flat = Files.readAllBytes(root.resolve(s"upstream/$id/$id.flat"))
          assert(flat.length < 24)
          assertEquals(script, Bytes.fromArray(Array((0x40 + flat.length).toByte) ++ flat))
        case "existing-boundary-corpus" =>
          val original = prior
            .find(row => field[String](row, "id") == id)
            .getOrElse(fail(s"missing original boundary $id"))
          assertEquals(script, bytes(original))
        case other => fail(s"unknown script provenance $other")
    }
  }

  test("published input files and the executed native patch retain their provenance hashes") {
    val manifest = io.circe.parser
      .parse(Files.readString(root.resolve("manifest.json")))
      .fold(error => fail(error.message), identity)
    field[Map[String, String]](manifest, "fixtureSha256").foreach { case (path, expected) =>
      assertEquals(Blst.sha256Hex(Files.readAllBytes(root.resolve(path))), expected, path)
    }
    val patch = field[Json](manifest, "boundsBackport")
    assertEquals(
      Blst.sha256Hex(Files.readAllBytes(root.resolve(field[String](patch, "patch")))),
      field[String](patch, "patchSha256")
    )
  }

  scripts.foreach { script =>
    val id = field[String](script, "id")
    test(s"$id agrees with released native admission and failure stage") {
      observations.filter(row => field[String](row, "id") == id).foreach { row =>
        val languageId = field[Int](row, "language")
        val language = PlutusLanguage.values
          .find(_.id + 1 == languageId)
          .getOrElse(fail(s"unknown fixture language $languageId"))
        val protocol = field[Int](row, "protocol")
        val model = PlutusBoundaryCorpus.costModel(language, protocol)
        val modelBytes = Json
          .arr(model.map(Json.fromLong)*)
          .noSpaces
          .getBytes(StandardCharsets.UTF_8)
        val original = bytes(script)
        assertEquals(Blst.sha256Hex(original.toArray), field[String](row, "scriptSha256"))
        assertEquals(Blst.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
        val admitted = decoder.validate(language, protocol, original).isRight
        val result = evaluator.evaluate(
          EvalRequest(
            language,
            protocol,
            original,
            Vector.empty,
            model,
            PlutusBoundaryCorpus.budget
          )
        )
        assertEquals(
          (admitted, stage(admitted, result)),
          observed(field[Json](row, "released")),
          s"$language/$protocol"
        )
      }
    }
  }

  test("the native bounds backport changes exactly the twelve pre-PV11 oversized admissions") {
    val differences = observations.filter { row =>
      observed(field[Json](row, "released")) != observed(field[Json](row, "boundsBackport"))
    }
    assertEquals(differences.size, 12)
    differences.foreach { row =>
      assert(Set("header-33", "constr-1025").contains(field[String](row, "id")))
      assert(Set(9, 10).contains(field[Int](row, "protocol")))
      assert(field[Boolean](field[Json](row, "released"), "admitted"))
      assertEquals(observed(field[Json](row, "boundsBackport")), (false, Stage.Decode))
    }
  }
