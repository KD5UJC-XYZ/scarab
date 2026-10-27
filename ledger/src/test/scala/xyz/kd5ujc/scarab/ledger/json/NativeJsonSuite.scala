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
    case Visit(value: Json)
    case Key(value: String)

  private val decimalToken = "(-?)([0-9]+)(?:\\.([0-9]+))?(?:[eE]([+-]?[0-9]+))?".r
  private def valueEvents(value: Json): Vector[Json] =
    def row(tag: String, values: Json*): Json = Json.arr((Json.fromString(tag) +: values)*)
    @tailrec def walk(todo: List[Work], acc: List[Json]): Vector[Json] = todo match
      case Nil                   => acc.reverse.toVector
      case Work.Key(key) :: rest => walk(rest, row("key", Json.fromString(key)) :: acc)
      case Work.Visit(value) :: rest =>
        val (event, children) = value.fold[(Json, List[Work])](
          (row("null"), Nil),
          b => (row("bool", Json.fromBoolean(b)), Nil),
          n =>
            n.toString match
              case decimalToken(sign, whole, fraction, power) =>
                val decimals = Option(fraction).getOrElse("")
                val coefficient = BigInt(sign + whole + decimals)
                // Observe the returned representation: do not wrap or normalize it in the test.
                val exponent = Option(power).fold(BigInt(0))(BigInt(_)) - decimals.length
                (
                  row(
                    "number",
                    Json.fromString(coefficient.toString),
                    Json.fromString(exponent.toString)
                  ),
                  Nil
                )
              case other => fail(s"not a decimal Json number: $other"),
          text => (row("string", Json.fromString(text)), Nil),
          array => (row("array", Json.fromInt(array.size)), array.toList.map(Work.Visit.apply)),
          obj =>
            // Iteration is observed directly; sorting here would conceal a product ordering error.
            (
              row("object", Json.fromInt(obj.size)),
              obj.toVector.toList.flatMap { (key, v) => List(Work.Key(key), Work.Visit(v)) }
            )
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
