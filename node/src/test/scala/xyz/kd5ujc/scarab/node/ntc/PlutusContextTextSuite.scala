// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser.parse
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ExUnits, PlutusData, PlutusLanguage}

class PlutusContextTextSuite extends FunSuite:
  private val corpus =
    parse(Files.readString(Path.of("fixtures/reference/plutus-diagnostics/contexts.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("expected oracle cases"))

  corpus.foreach { entry =>
    val cursor = entry.hcursor
    def field(name: String) = cursor.get[String](name).fold(e => fail(e.message), identity)
    test(field("name")) {
      val bytes = Bytes.fromHex(field("dataCbor")).fold(e => fail(e.toString), identity)
      val data = PlutusData.decode(bytes).fold(e => fail(e.toString), identity)
      val rendered = field("kind") match
        case "ArgumentsV1" | "ArgumentsV2" =>
          val language =
            if field("kind") == "ArgumentsV1" then PlutusLanguage.V1 else PlutusLanguage.V2
          val args = data match
            case PlutusData.List(values) => values
            case other                   => fail(s"not an argument list: $other")
          PlutusContextText
            .arguments(EvalRequest(language, 10, Bytes.empty, args, Vector.empty, ExUnits.zero))
            .map(_.render)
        case kind =>
          val schema = kind match
            case "ContextV1" => PlutusContextText.Kind.ContextV1
            case "ContextV2" => PlutusContextText.Kind.ContextV2
            case "ContextV3" => PlutusContextText.Kind.ContextV3
            case "RawData"   => PlutusContextText.Kind.RawData
            case other       => fail(s"unknown oracle type $other")
          PlutusContextText.render(schema, data)
      assertEquals(rendered, Right(field("text")))
    }
  }

  test("deep datum nesting renders on a small stack") {
    val depth = 20000
    val data =
      (0 until depth).foldLeft(PlutusData.int(7))((inner, _) => PlutusData.constr(0, inner))
    val rendered =
      PreprodDeepTx.onSmallStack(PlutusContextText.render(PlutusContextText.Kind.RawData, data))
    assertEquals(rendered, Right("<" * depth + "7" + ">" * depth))
  }

  test("invalid argument shapes retain a typed presentation error") {
    val request =
      EvalRequest(PlutusLanguage.V1, 10, Bytes.empty, Vector.empty, Vector.empty, ExUnits.zero)
    assertEquals(
      PlutusContextText.arguments(request),
      Left(PlutusContextText.Error.Arguments(PlutusLanguage.V1, 0))
    )
    assertEquals(
      PlutusContextText.render(PlutusContextText.Kind.ContextV3, PlutusData.int(0)),
      Left(PlutusContextText.Error.Shape(PlutusContextText.Kind.ContextV3, PlutusData.int(0)))
    )
  }
