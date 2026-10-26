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

  private final case class Trace(
      status: String,
      feeds: Vector[Int],
      pairs: Vector[Json],
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
        pairs: Vector[Json]
    ): Trace = step match
      case InjectionJson.Step.Entry(key, value, next) =>
        loop(next.pull, rest, emptyFeeds, feeds, pairs :+ Json.arr(Json.fromString(key), value))
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
      val observed = actual.pairs.map { pair =>
        val items = pair.asArray.fold(fail("pair is not an array"))(identity)
        val key = items.headOption.fold(fail("missing pair key"))(identity)
        val value = items.lift(1).fold(fail("missing pair value"))(identity)
        Json.obj("key" -> key, "events" -> Json.fromValues(valueEvents(value)))
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
        assertEquals(value, Json.fromInt(1))
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
    val payload = result.pairs.head.asArray.flatMap(_.lift(1)).fold(fail("missing value"))(identity)
    val bottom = (0 until depth).foldLeft(payload) { (j, _) =>
      j.asArray.flatMap(_.headOption).fold(fail("missing nested array"))(identity)
    }
    assertEquals(bottom, Json.fromInt(0))
  }
