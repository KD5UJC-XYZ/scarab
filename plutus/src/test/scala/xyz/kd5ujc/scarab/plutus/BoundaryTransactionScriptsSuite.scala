// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.Json
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import munit.FunSuite
import scala.jdk.CollectionConverters.*

class BoundaryTransactionScriptsSuite extends FunSuite:
  private val directory = Path.of("fixtures/reference/plutus-boundary-transactions")
  private def rows(name: String): Vector[Json] =
    Files.readAllLines(directory.resolve(name)).asScala.toVector.map { line =>
      io.circe.parser.parse(line).fold(error => fail(error.message), identity)
    }
  private def field[A: io.circe.Decoder](row: Json, name: String): A =
    row.hcursor.get[A](name).fold(error => fail(error.message), identity)

  private val scripts = rows("scripts.jsonl")
  private val verdicts = rows("api-verdicts.jsonl")
  private val version = (1, 1, 0)

  test("all selected original ASTs retain their bytes, full indices and ignored context wrapper") {
    val probes = BoundaryTransactionScripts.checked
    assertEquals(scripts.size, probes.size)
    probes.foreach { probe =>
      val row = scripts.find(j => field[String](j, "id") == probe.id).getOrElse(fail(probe.id))
      assertEquals(field[String](row, "sourceScript"), FlatProgram.script(version, probe.term).hex)
      assertEquals(
        field[String](row, "script"),
        FlatProgram.script(version, FlatProgram.Lam(probe.term)).hex
      )
      assertEquals(field[Vector[String]](row, "sourceIndices"), probe.indices.map(_.toString))
    }
  }

  BoundaryTransactionScripts.checked.foreach { probe =>
    test(s"${probe.id}: exact applied-context program agrees with the executed pinned oracle") {
      val cases = verdicts.filter(j => field[String](j, "id") == probe.id)
      assertEquals(cases.map(j => field[Int](j, "protocol")).toSet, Set(10, 11))
      cases.foreach { row =>
        val protocol = field[Int](row, "protocol")
        val script =
          FlatProgram.script(version, FlatProgram.Ap(FlatProgram.Lam(probe.term), FlatProgram.unit))
        val model = PlutusBoundaryCorpus.costModel(PlutusLanguage.V3, protocol)
        val modelBytes =
          Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
        assertEquals(NativeCrypto.sha256Hex(script.toArray), field[String](row, "scriptSha256"))
        assertEquals(NativeCrypto.sha256Hex(modelBytes), field[String](row, "costModelSha256"))
        val admitted =
          ScalusEvaluator.pureDecoder.validate(PlutusLanguage.V3, protocol, script).isRight
        assertEquals(admitted, field[Boolean](row, "admitted"))
        val outcome = ScalusEvaluator.pure.evaluate(
          EvalRequest(
            PlutusLanguage.V3,
            protocol,
            script,
            Vector.empty,
            model,
            PlutusBoundaryCorpus.budget
          )
        )
        val stage = outcome match
          case EvalResult.Success(_, _)                                     => "success"
          case EvalResult.Failure(EvalFailure.Decode(_), _, _) if !admitted => "decode"
          case EvalResult.Failure(EvalFailure.OpenTerm(_), _, _)            => "scope"
          case EvalResult.Failure(EvalFailure.Script(_), _, _)              => "evaluation"
          case other => fail(s"unexpected boundary outcome: $other")
        assertEquals(stage, field[String](row, "stage"))
      }
    }
  }
