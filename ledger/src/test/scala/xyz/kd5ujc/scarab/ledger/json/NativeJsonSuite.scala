// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import munit.FunSuite
import scala.annotation.tailrec
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

class NativeJsonSuite extends FunSuite:
  private def field[A: io.circe.Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private enum Work:
    case Visit(value: JsonValue)
    case Key(value: String)

  private def valueEvents(value: JsonValue): Vector[Json] =
    def row(tag: String, values: Json*): Json = Json.arr((Json.fromString(tag) +: values)*)
    @tailrec def walk(todo: List[Work], acc: List[Json]): Vector[Json] = todo match
      case Nil                   => acc.reverse.toVector
      case Work.Key(key) :: rest => walk(rest, row("key", Json.fromString(key)) :: acc)
      case Work.Visit(value) :: rest =>
        val (event, children) = value match
          case JsonValue.Null    => (row("null"), Nil)
          case JsonValue.Bool(b) => (row("bool", Json.fromBoolean(b)), Nil)
          // The stored representation, neither wrapped nor normalized here.
          case JsonValue.Number(n) =>
            (
              row(
                "number",
                Json.fromString(n.coefficient.toString),
                Json.fromString(n.base10Exponent.toString)
              ),
              Nil
            )
          case JsonValue.String(text) => (row("string", Json.fromString(text)), Nil)
          case JsonValue.Array(items) =>
            (row("array", Json.fromInt(items.size)), items.toList.map(Work.Visit.apply))
          case JsonValue.Object(fields) =>
            // Iteration is observed directly; sorting here would conceal a product ordering error.
            (
              row("object", Json.fromInt(fields.size)),
              fields.toVector.toList.flatMap((key, v) => List(Work.Key(key), Work.Visit(v)))
            )
        walk(children ::: rest, event :: acc)
    walk(List(Work.Visit(value)), Nil)

  private val cases = Using.resource(
    new GZIPInputStream(
      Files.newInputStream(Path.of("fixtures/reference/native-json/cases.jsonl.gz"))
    )
  ) { in =>
    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      .split("\n")
      .toVector
      .filter(_.nonEmpty)
      .map(line => parser.parse(line).fold(e => fail(e.message), identity))
  }

  cases.foreach { c =>
    test(field[String](c, "name")) {
      val raw = Bytes.fromHex(field[String](c, "inputHex")).fold(e => fail(e.toString), identity)
      val native = field[Json](c, "native")
      val result = PreprodDeepTx.onSmallStack(NativeJson.parse(raw).map(valueEvents))
      assertEquals(result.isRight, field[Boolean](native, "accepted"))
      result.foreach(events => assertEquals(events, field[Vector[Json]](native, "events")))
    }
  }
