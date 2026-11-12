// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.ledger

import io.circe.{Decoder, Json}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path}
import java.security.MessageDigest
import scala.jdk.CollectionConverters.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{PlutusLanguage, ProtocolMajor, SemanticsVariant}
import xyz.kd5ujc.scarab.uplc.cek.BudgetMode
import xyz.kd5ujc.scarab.uplc.cost.{DefaultCostModels, ExBudget}
import xyz.kd5ujc.scarab.uplc.flat.SerialisedScript

/** The ledger's evaluation against the verdicts the pinned Haskell reference (plutus-ledger-api
  * 1.70.0.0, `scripts/oracle/plutus/Main.hs`) gave for the probes of `plutus-boundaries` (477 rows)
  * and `plutus-upstream-boundaries` (54 rows, the `released` build): every row, its admission by
  * `deserialiseScript` and the stage of `evaluateScriptRestricting` with no arguments, `Quiet`,
  * within 10,000,000,000 CPU and 14,000,000 memory units, under the default list of the profile's
  * variant (bound by the row's `costModelSha256`). The oracle records no budget for these rows.
  */
final class LedgerBoundaryOracleSuite extends munit.FunSuite:
  private val Root = Path.of("fixtures/reference")

  // `C.ExBudget (C.ExCPU 10000000000) (C.ExMemory 14000000)` (`scripts/oracle/plutus/Main.hs`)
  private val Limit = ExBudget.of(10000000000L, 14000000L)

  private def jsonl(file: String): Vector[Json] =
    Files
      .readAllLines(Root.resolve(file))
      .asScala
      .toVector
      .filter(_.nonEmpty)
      .map(line => io.circe.parser.parse(line).fold(e => fail(s"$file: ${e.message}"), identity))

  private def field[A: Decoder](row: Json, path: String*): A =
    path
      .foldLeft(row.hcursor: io.circe.ACursor)(_.downField(_))
      .as[A]
      .fold(e => fail(s"${path.mkString(".")}: ${e.message} in $row"), identity)

  private def sha256(bytes: Array[Byte]): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).map(x => f"${x & 0xff}%02x").mkString

  private def language(n: Int): PlutusLanguage =
    PlutusLanguage.values.find(_.id + 1 == n).getOrElse(fail(s"no language $n"))

  private def scripts(file: String): Map[String, Bytes] =
    jsonl(file)
      .map(row =>
        field[String](row, "id") ->
          Bytes.fromHex(field[String](row, "script")).fold(e => fail(e.toString), identity)
      )
      .toMap

  private final case class Verdict(
      id: String,
      language: Int,
      protocol: Int,
      scriptSha: String,
      modelSha: String,
      admitted: Boolean,
      stage: String
  )

  /** Every row where the ledger's evaluation disagrees with the reference. */
  private def disagreements(
      verdicts: Vector[Verdict],
      scripts: Map[String, Bytes]
  ): Vector[String] =
    verdicts.flatMap { v =>
      val where = s"${v.id} at V${v.language}/${v.protocol}"
      val lang = language(v.language)
      val protocol = ProtocolMajor(v.protocol)
      val model = DefaultCostModels.list(lang, SemanticsVariant.of(lang, protocol))
      val modelSha = sha256(
        Json.arr(model.map(Json.fromLong)*).noSpaces.getBytes(StandardCharsets.UTF_8)
      )
      scripts.get(v.id) match
        case None => Vector(s"$where: no script")
        case Some(script) if sha256(script.toArray) != v.scriptSha =>
          Vector(s"$where: the script's SHA-256 is not the verdict's")
        case Some(_) if modelSha != v.modelSha =>
          Vector(s"$where: the cost-model list's SHA-256 is $modelSha, the verdict's ${v.modelSha}")
        case Some(script) =>
          val admitted = SerialisedScript.deserialiseScript(lang, protocol, script).isRight
          val outcome = LedgerContext.make(lang, model)._2 match
            case Left(reason) => ScriptResult.Unavailable(reason)
            case Right(context) =>
              LedgerEval.evaluateSerialised(
                lang,
                protocol,
                VerboseMode.Quiet,
                context,
                BudgetMode.Restricting(Limit),
                script,
                Vector.empty
              )
          val stage = outcome match
            case ScriptResult.Success(_, _)       => "success"
            case ScriptResult.Unavailable(reason) => s"no verdict ($reason)"
            case failed                           => failed.failedAt.fold("none")(_.name)
          Vector(
            Option.when(admitted != v.admitted)(
              s"$where: admitted $admitted, oracle ${v.admitted}"
            ),
            Option.when(stage != v.stage)(s"$where: stage $stage, oracle ${v.stage}")
          ).flatten
    }

  test("plutus-boundaries: every verdict of the reference, admission and stage") {
    val verdicts = jsonl("plutus-boundaries/verdicts.jsonl").map { row =>
      Verdict(
        field[String](row, "id"),
        field[Int](row, "language"),
        field[Int](row, "protocol"),
        field[String](row, "scriptSha256"),
        field[String](row, "costModelSha256"),
        field[Boolean](row, "admitted"),
        field[String](row, "stage")
      )
    }
    assertEquals(verdicts.size, 477)
    assertEquals(
      disagreements(verdicts, scripts("plutus-boundaries/scripts.jsonl")),
      Vector.empty[String]
    )
  }

  // `released` is the unchanged 1.70.0.0 build; `boundsBackport` a patched one (its README)
  test("plutus-upstream-boundaries: every verdict of the released reference, admission and stage") {
    val verdicts = jsonl("plutus-upstream-boundaries/verdicts.jsonl").map { row =>
      Verdict(
        field[String](row, "id"),
        field[Int](row, "language"),
        field[Int](row, "protocol"),
        field[String](row, "scriptSha256"),
        field[String](row, "costModelSha256"),
        field[Boolean](row, "released", "admitted"),
        field[String](row, "released", "stage")
      )
    }
    assertEquals(verdicts.size, 54)
    assertEquals(
      disagreements(verdicts, scripts("plutus-upstream-boundaries/scripts.jsonl")),
      Vector.empty[String]
    )
  }
