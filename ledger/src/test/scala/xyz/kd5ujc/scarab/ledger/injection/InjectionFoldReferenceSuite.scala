// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.injection

import io.circe.{Json, parser}
import java.nio.file.{Files, Path}
import java.util.zip.GZIPInputStream
import munit.FunSuite
import scala.annotation.tailrec
import scala.util.Using
import xyz.kd5ujc.scarab.codec.{Bytes, DomainError}
import xyz.kd5ujc.scarab.ledger.GenesisState.InjectionData
import xyz.kd5ujc.scarab.ledger.state.Hash32

/** Original native outcomes remain intact. This compares the pure inner fold, not file ownership.
  * Fifty completed inner outcomes, six bounded EOF observations, and one pending filesystem case.
  */
class InjectionFoldReferenceSuite extends FunSuite:
  private enum DecodeError extends DomainError:
    case Integer(cause: io.circe.Error)
  private type Error = InjectionFold.Error[DecodeError, DecodeError, Nothing]
  private type Pairs = Vector[(Json, Json)]
  private def field[A: io.circe.Decoder](j: Json, name: String): A =
    j.hcursor.get[A](name).fold(e => fail(e.message), identity)
  private val cases = Using.resource(
    new GZIPInputStream(
      Files.newInputStream(Path.of("fixtures/reference/injection-fold/cases.jsonl.gz"))
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

  private final case class Trace(step: InjectionFold.Step[Pairs, Error], reads: Vector[(Int, Int)])

  private def trace(c: Json): Trace =
    val source = field[Json](c, "source")
    val integerExamples = field[String](c, "mode") == "integers"
    // Supplied test decoders for the original integer examples, not production Aeson/genesis decoders.
    def key(text: String): Either[DecodeError, Json] =
      if integerExamples then
        parser
          .parse(text)
          .flatMap(_.as[BigInt])
          .left
          .map(DecodeError.Integer(_))
          .map(Json.fromBigInt)
      else Right(Json.fromString(text))
    def value(json: Json): Either[DecodeError, Json] =
      if integerExamples then json.as[BigInt].left.map(DecodeError.Integer(_)).map(Json.fromBigInt)
      else Right(json)
    val decoded: InjectionData[(Json, Json)] =
      source.hcursor.get[Vector[String]]("file").toOption match
        case Some(segments) =>
          val hashBytes =
            Bytes.fromHex(field[String](source, "hash")).fold(e => fail(e.toString), identity)
          InjectionData.FromFile(
            segments,
            Hash32.fromBytes(hashBytes).fold(e => fail(e.toString), identity)
          )
        case None =>
          source.hcursor.get[Json]("data").toOption match
            case Some(embedded) =>
              val entries =
                embedded.asObject.fold(fail("embedded source is not an object"))(_.toVector)
              InjectionData.Embedded(entries.map { (text, json) =>
                (
                  key(text).fold(e => fail(e.toString), identity),
                  value(json).fold(e => fail(e.toString), identity)
                )
              })
            case None => InjectionData.NoInjection
    val start = InjectionFold.start[Json, Json, Pairs, DecodeError, DecodeError, Nothing](
      decoded,
      Vector.empty
    )(
      key,
      value,
      (acc, pair) => Right(acc :+ pair)
    )
    val raw = field[Option[String]](c, "inputHex").map(h =>
      Bytes.fromHex(h).fold(e => fail(e.toString), identity)
    )
    val chunkSize = field[Option[Int]](c, "shortRead").getOrElse(65536)
    @tailrec def loop(
        step: InjectionFold.Step[Pairs, Error],
        remaining: Option[Bytes],
        emptyFeeds: Int,
        reads: Vector[(Int, Int)]
    ): Trace = step match
      case InjectionFold.Step.NeedChunk(input) if emptyFeeds > 0 =>
        remaining match
          case None => Trace(step, reads)
          case Some(rest) =>
            val chunk = rest.take(chunkSize)
            loop(
              input.feed(chunk),
              Some(rest.drop(chunkSize)),
              emptyFeeds - (if chunk.isEmpty then 1 else 0),
              reads :+ (input.requestedBytes -> chunk.size)
            )
      case InjectionFold.Step.NeedEof(input) if emptyFeeds > 0 =>
        loop(input.resume, remaining, emptyFeeds - 1, reads)
      case other => Trace(other, reads)
    loop(start, raw, 3, Vector.empty)

  cases.foreach { c =>
    val name = field[String](c, "name")
    val scope = field[String](c, "observation")
    test(s"$name: $scope") {
      val native = field[Json](c, "native")
      val actual = trace(c)
      val original =
        parser.parse(field[String](c, "originalNativeLine")).fold(e => fail(e.message), identity)
      assertEquals(field[String](original, "name"), name)
      assertEquals(field[Vector[Json]](original, "events"), field[Vector[Json]](native, "events"))
      if scope == "filesystemBoundary" then
        // No filesystem interpreter exists: retain this native refusal as an incomplete boundary.
        assertEquals(field[String](native, "error"), "filesystem")
        assert(actual.step.isInstanceOf[InjectionFold.Step.NeedChunk[?, ?]])
        assertEquals(actual.reads, Vector.empty)
      else
        val nativeReads = field[Vector[Json]](native, "events").flatMap { event =>
          event.hcursor.get[Int]("requested").toOption.map(n => n -> field[Int](event, "returned"))
        }
        assertEquals(actual.reads, nativeReads)
        if scope == "needDataNonResult" then
          assert(actual.step.isInstanceOf[InjectionFold.Step.NeedEof[?, ?]])
        else
          native.hcursor.get[String]("error").toOption match
            case None =>
              actual.step match
                case InjectionFold.Step.Done(pairs) =>
                  val observed = Json.fromValues(pairs.map { (k, v) => Json.arr(k, v) })
                  assertEquals(valueEvents(observed), field[Vector[Json]](native, "resultEvents"))
                case other => fail(s"expected complete native accumulator, got $other")
            case Some("hash") =>
              actual.step match
                case InjectionFold.Step.Failed(
                      InjectionFold.Error.HashMismatch(_, expected, actualHash)
                    ) =>
                  assertEquals(expected.bytes.hex, field[String](native, "expected"))
                  assertEquals(actualHash.bytes.hex, field[String](native, "actual"))
                case other => fail(s"expected native demanded-chunk hash refusal, got $other")
            case Some("parse") =>
              actual.step match
                case InjectionFold.Step.Failed(InjectionFold.Error.Syntax(_, _))   => ()
                case InjectionFold.Step.Failed(InjectionFold.Error.Key(_, _, _))   => ()
                case InjectionFold.Step.Failed(InjectionFold.Error.Value(_, _, _)) => ()
                case other => fail(s"expected native parse/domain refusal, got $other")
            case other => fail(s"unexpected native completed outcome $other")
    }
  }
