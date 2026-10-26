// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus

import io.circe.{Decoder, Json, parser}
import java.lang.reflect.Modifier
import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import munit.FunSuite
import scala.deriving.Mirror
import scala.util.Try
import scalus.uplc.{Constant, DefaultUni, Program, Term}
import scalus.uplc.Constant.asConstant
import scalus.uplc.builtin.{Builtins, ByteString}

/** Exact native scalar values and parser acceptance; CEK budgeting remains a separate contract. */
class NativeTextReferenceSuite extends FunSuite:
  private val directory = Path.of("fixtures/reference/plutus-text")
  private def json(value: String): Json = parser.parse(value).fold(e => fail(e.message), identity)
  private def field[A: Decoder](row: Json, key: String): A =
    row.hcursor.get[A](key).fold(e => fail(e.message), identity)
  private val rows =
    Files.readString(directory.resolve("observations.jsonl")).linesIterator.map(json).toVector
  private def points(value: String): Vector[Int] = value.codePoints().toArray.toVector
  private def parsed(literal: String): Option[Constant.String] =
    Program.parseUplc(s"(program 1.1.0 (con string $literal))").toOption.map(_.term).map {
      case Term.Const(value: Constant.String, _) => value
      case other                                 => fail(s"unexpected native text term: $other")
    }

  test("all native text observations and their producer are retained exactly") {
    assertEquals(
      rows.groupMapReduce(field[String](_, "kind"))(_ => 1)(_ + _),
      Map("pack" -> 12, "decode" -> 5, "parse" -> 9)
    )
    assertEquals(
      rows.map(r => (field[String](r, "kind"), field[String](r, "id"))).distinct.size,
      26
    )
    val manifest = json(Files.readString(directory.resolve("manifest.json")))
    for (name, expected) <- field[Map[String, String]](manifest, "sha256") do
      assertEquals(NativeCrypto.sha256Hex(Files.readAllBytes(directory.resolve(name))), expected)
    for (name, expected) <- field[Map[String, String]](manifest, "toolSha256") do
      assertEquals(NativeCrypto.sha256Hex(Files.readAllBytes(Path.of(name))), expected)
  }

  for row <- rows do
    val kind = field[String](row, "kind")
    val id = field[String](row, "id")
    test(s"$kind/$id: exact native value, acceptance and stored measure") {
      kind match
        case "pack" =>
          // Separate scalar escapes preserve two invalid surrogate codepoints as two inputs.
          val literal = field[Vector[Int]](row, "inputCodePoints")
            .map(n => s"\\x${Integer.toHexString(n)}")
            .mkString("\"", "", "\"")
          val value = parsed(literal).getOrElse(fail("native pack input was rejected"))
          assertEquals(points(value.value), field[Vector[Int]](row, "codePoints"))
          assertEquals(
            Builtins.encodeUtf8(value.value).bytes.map(_ & 0xff).toVector,
            field[Vector[Int]](row, "utf8")
          )
          assertEquals(value.utf8ByteLength, field[Long](row, "bytes"))
          assertEquals(value.utf8ByteLength / 4, field[Long](row, "measure"))
        case "decode" =>
          val bytes = ByteString.fromArray(field[Vector[Int]](row, "utf8").map(_.toByte).toArray)
          field[Option[Vector[Int]]](row, "codePoints") match
            case Some(expected) => assertEquals(points(Builtins.decodeUtf8(bytes)), expected)
            case None           => intercept[IllegalArgumentException](Builtins.decodeUtf8(bytes))
        case "parse" =>
          assertEquals(
            parsed(field[String](row, "literal")).map(v => points(v.value)),
            field[Option[Vector[Int]]](row, "codePoints")
          )
        case other => fail(s"unknown native observation kind: $other")
    }

  test("every public constructor path normalizes scalars and retains the same immutable measure") {
    val inputs = Vector(
      "" -> 0L,
      "A" -> 1L,
      "¢" -> 2L,
      "日" -> 3L,
      "😀" -> 4L,
      "\ud800" -> 3L,
      "\udc00" -> 3L,
      "X\ud800Z" -> 5L
    )
    inputs.foreach { (raw, bytes) =>
      val original = Constant.String(value = raw)
      val values = Vector(
        original,
        new Constant.String(value = raw),
        original.copy(),
        original.copy(value = raw),
        summon[Mirror.ProductOf[Constant.String]].fromProduct(Tuple1(raw)),
        Constant.fromValue(DefaultUni.String, raw),
        raw.asConstant
      )
      values.foreach {
        case value: Constant.String =>
          assertEquals(value.utf8ByteLength, bytes)
          assert(!points(value.value).exists(n => n >= 0xd800 && n <= 0xdfff))
          assertEquals(value.productArity, 1)
          assertEquals(value.productElementName(0), "value")
          assertEquals(Tuple.fromProductTyped(value), Tuple1(value.value))
          val Constant.String(extracted) = value
          assertEquals(extracted, value.value)
        case other => fail(s"string constructor produced $other")
      }
    }
    assertEquals(
      Constant.toValue(
        Constant.fromValue(DefaultUni.List(DefaultUni.String), List("\ud800", "😀"))
      ),
      List("�", "😀")
    )
    assertEquals(Constant.String.toString, "String")
    assertEquals(summon[Mirror.SumOf[Constant]].ordinal(Constant.String("A")), 2)
    assertEquals(scala.compiletime.constValue[Tuple.Size[Constant.MirroredElemTypes]], 13)
    assert(classOf[Constant.String].getConstructors.forall(_.getParameterCount == 1))
    assert(
      Modifier.isFinal(classOf[Constant.String].getDeclaredField("utf8ByteLength").getModifiers)
    )
  }

  test("builtin-produced strings initialize the same retained representation") {
    val appended = Builtins.appendString("😀", "日").asConstant
    val decoded = Builtins.decodeUtf8(ByteString.fromArray("😀".getBytes("UTF-8"))).asConstant
    val measures = Vector(appended, decoded).map {
      case value: Constant.String => value.utf8ByteLength
      case other                  => fail(s"text builtin produced $other")
    }
    assertEquals(measures, Vector(7L, 4L))
  }

  test("wide text construction is stack-safe and retained measurement is reusable") {
    val result = new AtomicReference[Option[Try[Long]]](None)
    val wide = "😀日A".repeat(100000)
    val worker = new Thread(
      null,
      () =>
        result.set(Some(Try {
          val value = Constant.String(wide)
          Vector.fill(10000)(value.utf8ByteLength).sum
        })),
      "native-text-small-stack",
      256L * 1024L
    )
    worker.start()
    try
      worker.join()
      val observed = result
        .get()
        .getOrElse(fail("text worker did not publish a result"))
        .fold(e => fail(e.toString), identity)
      assertEquals(observed, 8000000000L)
    finally
      worker.interrupt()
      worker.join()
  }
