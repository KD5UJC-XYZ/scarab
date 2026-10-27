// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import munit.FunSuite
import scala.annotation.tailrec
import scala.util.Using
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx
import xyz.kd5ujc.scarab.ledger.json.{JsonValue, Scientific}

class InjectionJsonSuite extends FunSuite:
  private def field[A: io.circe.Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)

  private val cases = Using.resource(
    new GZIPInputStream(
      Files.newInputStream(Path.of("fixtures/reference/injection-json/cases.jsonl.gz"))
    )
  ) { in =>
    new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
      .split("\n")
      .toVector
      .filter(_.nonEmpty)
      .map(s => parser.parse(s).fold(e => fail(e.message), identity))
  }

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

  private final case class Trace(
      status: String,
      feeds: Vector[Int],
      pairs: Vector[(String, JsonValue)],
      remaining: Option[Int],
      remainingHex: Option[String],
      error: Option[InjectionJson.Error]
  )

  private def trace(raw: Bytes, chunkSize: Int): Trace =
    @tailrec def loop(
        step: InjectionJson.Step,
        rest: Bytes,
        emptyFeeds: Int,
        feeds: Vector[Int],
        pairs: Vector[(String, JsonValue)]
    ): Trace = step match
      case InjectionJson.Step.Entry(key, value, next) =>
        loop(next.pull, rest, emptyFeeds, feeds, pairs :+ (key -> value))
      case InjectionJson.Step.Done(remaining) =>
        Trace("done", feeds, pairs, Some(remaining.size), Some(remaining.hex), None)
      case InjectionJson.Step.Failed(error) =>
        Trace("failed", feeds, pairs, None, None, Some(error))
      case InjectionJson.Step.NeedData(input) =>
        if rest.isEmpty && emptyFeeds == 0 then
          Trace("needDataAfterThreeEmptyFeeds", feeds, pairs, None, None, None)
        else
          val chunk = rest.take(chunkSize)
          loop(
            input.feed(chunk).pull,
            rest.drop(chunkSize),
            emptyFeeds - (if rest.isEmpty then 1 else 0),
            feeds :+ chunk.size,
            pairs
          )
    loop(InjectionJson.initial.pull, raw, 3, Vector.empty, Vector.empty)

  cases.foreach { c =>
    test(field[String](c, "name")) {
      val raw = Bytes.fromHex(field[String](c, "inputHex")).fold(e => fail(e.toString), identity)
      val actual = trace(raw, field[Int](c, "chunkSize"))
      val native = field[Json](c, "native")
      assertEquals(actual.status, field[String](native, "status"))
      assertEquals(actual.feeds, field[Vector[Int]](native, "feeds"))
      // Observe native Value directly. Re-parsing its JSON encoding can normalize Scientific
      // or overflow the formatter's exponent; serialized pairs remain in the portable replay.
      val observed = actual.pairs.map { (key, value) =>
        Json.obj("key" -> Json.fromString(key), "events" -> Json.fromValues(valueEvents(value)))
      }
      assertEquals(observed, field[Vector[Json]](native, "pairEvents"))
      assertEquals(actual.remaining, field[Option[Int]](native, "remaining"))
      assertEquals(actual.remainingHex, field[Option[String]](native, "remainingHex"))
      assertEquals(actual.error.nonEmpty, native.hcursor.get[String]("error").isRight)
    }
  }

  test("a yielded entry precedes a later syntax refusal") {
    val raw =
      Bytes.fromArray("{\"bad\":1,\"later\":}".getBytes(java.nio.charset.StandardCharsets.UTF_8))
    val first = InjectionJson.initial.pull match
      case InjectionJson.Step.NeedData(input) => input.feed(raw).pull
      case other                              => fail(s"expected initial NeedData: $other")
    first match
      case InjectionJson.Step.Entry(key, value, next) =>
        assertEquals(key, "bad")
        assertEquals(value, JsonValue.Number(Scientific(1, 0)))
        assertEquals(
          next.pull,
          InjectionJson.Step.Failed(
            InjectionJson.Error
              .UnexpectedToken(InjectionJson.Expected.Value, InjectionJson.TokenKind.ObjectEnd)
          )
        )
      case other => fail(s"expected first completed entry: $other")
  }

  test("outside-data nesting uses explicit frames on a small stack") {
    val depth = 20000
    val raw = Bytes.fromArray(
      ("{\"key\":" + "[" * depth + "0" + "]" * depth + "}")
        .getBytes(java.nio.charset.StandardCharsets.UTF_8)
    )
    val result = PreprodDeepTx.onSmallStack(trace(raw, 65536))
    assertEquals(result.status, "done")
    assertEquals(result.pairs.size, 1)
    val bottom = (0 until depth).foldLeft(result.pairs.head._2) {
      case (JsonValue.Array(Vector(inner)), _) => inner
      case (other, _)                          => fail(s"missing nested array: $other")
    }
    assertEquals(bottom, JsonValue.Number(Scientific(0, 0)))
  }
