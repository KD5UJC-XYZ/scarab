// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.ntc

import io.circe.parser.parse
import io.circe.{Decoder, DecodingFailure}
import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx
import xyz.kd5ujc.scarab.plutus.{EvalRequest, ExUnits, PlutusData, PlutusLanguage}

class PlutusContextTextSuite extends FunSuite:
  private enum FixtureKind:
    case ArgumentsV1, ArgumentsV2, ContextV1, ContextV2, ContextV3, RawData

  private given Decoder[FixtureKind] = Decoder.instance { cursor =>
    cursor
      .as[String]
      .flatMap(value =>
        FixtureKind.values
          .find(_.toString == value)
          .toRight(DecodingFailure(s"unknown oracle kind: $value", cursor.history))
      )
  }

  private def arguments(
      language: PlutusLanguage,
      data: PlutusData
  ): Either[PlutusContextText.Error, String] = data match
    case PlutusData.List(values) =>
      PlutusContextText
        .arguments(
          EvalRequest(language, 10, Bytes.empty, values, Vector.empty, ExUnits.zero)
        )
        .map(_.render)
    case other =>
      Left(
        PlutusContextText.Error.Shape(
          PlutusContextText.Kind.Sequence(PlutusContextText.Kind.RawData),
          other
        )
      )

  private val corpus =
    parse(Files.readString(Path.of("fixtures/reference/plutus-diagnostics/contexts.json")))
      .fold(e => fail(e.message), identity)
      .asArray
      .getOrElse(fail("expected oracle cases"))

  corpus.foreach { entry =>
    val cursor = entry.hcursor
    def field(name: String) = cursor.get[String](name).fold(e => fail(e.message), identity)
    val kind = cursor.get[FixtureKind]("kind").fold(e => fail(e.message), identity)
    test(field("name")) {
      val bytes = Bytes.fromHex(field("dataCbor")).fold(e => fail(e.toString), identity)
      val data = PlutusData.decode(bytes).fold(e => fail(e.toString), identity)
      val rendered = kind match
        case FixtureKind.ArgumentsV1 => arguments(PlutusLanguage.V1, data)
        case FixtureKind.ArgumentsV2 => arguments(PlutusLanguage.V2, data)
        case FixtureKind.ContextV1 =>
          PlutusContextText.render(PlutusContextText.Kind.ContextV1, data)
        case FixtureKind.ContextV2 =>
          PlutusContextText.render(PlutusContextText.Kind.ContextV2, data)
        case FixtureKind.ContextV3 =>
          PlutusContextText.render(PlutusContextText.Kind.ContextV3, data)
        case FixtureKind.RawData => PlutusContextText.render(PlutusContextText.Kind.RawData, data)
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
