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

  /** `e` as a 64-bit `Int` holds it: modulo 2^64, signed. */
  private def machineInt(e: BigInt): BigInt =
    (e + (BigInt(1) << 63)).mod(BigInt(1) << 64) - (BigInt(1) << 63)

  /** The exact exponents of the number lexemes of `input` with an exponent part: its digits, signed,
    * less the fraction's width, read here independently of the parser.
    */
  private def lexemeExponents(input: Bytes): Set[BigInt] =
    """-?\d+(?:\.(\d+))?[eE]([+-]?)(\d+)""".r
      .findAllMatchIn(new String(input.toArray, java.nio.charset.StandardCharsets.ISO_8859_1))
      .map(m =>
        BigInt(m.group(3)) * (if m.group(2) == "-" then -1 else 1) -
          Option(m.group(1)).fold(0)(_.length)
      )
      .toSet

  /** The exponent as the reference's machine `Int` holds it, once it is shown exact: only an
    * exponent outside `Int64` differs, and it must be one of the input's
    * (overflow-improvements.md#json-number-exponents).
    */
  private def asReference(e: BigInt, exact: Set[BigInt]): BigInt =
    if e.isValidLong then e
    else
      assert(exact.contains(e), s"exponent $e is none of the input's exact exponents $exact")
      machineInt(e)

  private def valueEvents(value: JsonValue, exact: Set[BigInt]): Vector[Json] =
    def row(tag: String, values: Json*): Json = Json.arr((Json.fromString(tag) +: values)*)
    @tailrec def walk(todo: List[Work], acc: List[Json]): Vector[Json] = todo match
      case Nil                   => acc.reverse.toVector
      case Work.Key(key) :: rest => walk(rest, row("key", Json.fromString(key)) :: acc)
      case Work.Visit(value) :: rest =>
        val (event, children) = value match
          case JsonValue.Null    => (row("null"), Nil)
          case JsonValue.Bool(b) => (row("bool", Json.fromBoolean(b)), Nil)
          // The stored representation, not normalized here. The node's exponent is exact; the
          // reference's machine `Int` holds it modulo 2^64, so it is compared as that `Int`
          // holds it: a case differs from the reference by the wrap alone
          // (overflow-improvements.md#json-number-exponents).
          case JsonValue.Number(n) =>
            (
              row(
                "number",
                Json.fromString(n.coefficient.toString),
                Json.fromString(asReference(n.base10Exponent, exact).toString)
              ),
              Nil
            )
          case JsonValue.String(text) => (row("string", Json.fromString(text.asString)), Nil)
          case JsonValue.Array(items) =>
            (row("array", Json.fromInt(items.size)), items.toList.map(Work.Visit.apply))
          case JsonValue.Object(fields) =>
            // Iteration is observed directly; sorting here would conceal a product ordering error.
            (
              row("object", Json.fromInt(fields.size)),
              fields.toVector.toList.flatMap((key, v) =>
                List(Work.Key(key.text.asString), Work.Visit(v))
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
      val result = PreprodDeepTx.onSmallStack(NativeJson.decodeStrict(raw).map(valueEvents(_, lexemeExponents(raw))))
      assertEquals(result.isRight, field[Boolean](native, "accepted"))
      result match
        case Right(events) => assertEquals(events, field[Vector[Json]](native, "events"))
        case Left(error)   => assertEquals(error.message, field[String](native, "error"))
    }
  }
