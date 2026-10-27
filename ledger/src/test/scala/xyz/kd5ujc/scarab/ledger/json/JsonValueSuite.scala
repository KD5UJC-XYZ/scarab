// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.json

import munit.FunSuite
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx

class JsonValueSuite extends FunSuite:
  private def number(c: BigInt, e: Long) = JsonValue.Number(Scientific(c, e))

  // `normalizePositive` as the reference writes it: one division per trailing zero.
  @tailrec private def byOneZero(c: BigInt, e: Long): Scientific =
    if c == 0 then Scientific(0, 0)
    else
      val (q, r) = c /% 10
      if r == 0 then byOneZero(q, e + 1) else Scientific(c, e)

  test("Scientific equality and hashing compare normalized values, as the reference's Eq does") {
    val equal = List(
      Scientific(1, 1) -> Scientific(10, 0),
      Scientific(0, 5) -> Scientific(0, -3),
      Scientific(-100, -2) -> Scientific(-1, 0),
      Scientific(150, -2) -> Scientific(15, -1)
    )
    equal.foreach { (a, b) =>
      assertEquals(a, b)
      assertEquals(a.hashCode, b.hashCode)
    }
    assertNotEquals(Scientific(15, 0), Scientific(150, 0))
    assertNotEquals(Scientific(1, 0), Scientific(-1, 0))
  }

  test("normalization keeps the stored representation and agrees with one division per zero") {
    assertEquals(Scientific(150, -2).coefficient, BigInt(150))
    for
      base <- List(BigInt(1), BigInt(-7), BigInt(2).pow(70), BigInt(-12345), BigInt(10).pow(40) + 1)
      zeros <- (0 to 70) ++ List(127, 128, 129, 255, 256, 1000)
    do
      val c = base * BigInt(10).pow(zeros)
      val n = Scientific(c, -3).normalize
      val expected = if c < 0 then byOneZero(-c, -3) else byOneZero(c, -3)
      assertEquals(n.coefficient, if c < 0 then -expected.coefficient else expected.coefficient)
      assertEquals(n.base10Exponent, expected.base10Exponent)
  }

  test("the normalized exponent wraps as the reference's machine Int does") {
    val n = Scientific(100, Long.MaxValue).normalize
    assertEquals(n.coefficient, BigInt(1))
    assertEquals(n.base10Exponent, Long.MinValue + 1)
  }

  test("an object keeps the last pair of a key and orders keys by code point") {
    val supplementary = "\uD83D\uDE00"
    val o = JsonObject.fromList(
      List("b" -> JsonValue.Null, supplementary -> JsonValue.Null, "\uFFFF" -> JsonValue.Null)
        :+ ("b" -> JsonValue.Bool(true))
    )
    // UTF-16 code units would put the surrogate pair (0xD83D) before U+FFFF.
    assertEquals(o.toVector.map(_._1), Vector("b", "\uFFFF", supplementary))
    assertEquals(o.get("b"), Some(JsonValue.Bool(true)))
  }

  test("values are equal as the derived Eq says, with numbers compared normalized") {
    val a = JsonValue.Array(Vector(number(1, 1), JsonValue.String("x")))
    assertEquals(a, JsonValue.Array(Vector(number(10, 0), JsonValue.String("x"))))
    assertEquals(a.hashCode, JsonValue.Array(Vector(number(10, 0), JsonValue.String("x"))).hashCode)
    assertNotEquals(a, JsonValue.Array(Vector(JsonValue.String("x"), number(10, 0))))
    val o = JsonValue.Object(JsonObject.fromList(List("k" -> number(5, 0), "j" -> JsonValue.Null)))
    assertEquals(
      o,
      JsonValue.Object(JsonObject.fromList(List("j" -> JsonValue.Null, "k" -> number(50, -1))))
    )
    assertNotEquals(o, JsonValue.Object(JsonObject.fromList(List("k" -> number(5, 0)))))
    val pairs = List("z" -> number(1, 0), "a" -> JsonValue.Null, "m" -> number(10, -1))
    assertEquals(
      JsonValue.Object(JsonObject.fromList(pairs)).hashCode,
      JsonValue.Object(JsonObject.fromList(pairs.reverse)).hashCode
    )
  }

  test("parsed numbers keep their representation and compare by value") {
    def parse(text: String) =
      NativeJson.parse(Bytes.wrap(text.getBytes("UTF-8"))).fold(e => fail(e.toString), identity)
    parse("1.50") match
      case JsonValue.Number(n) =>
        assertEquals((n.coefficient, n.base10Exponent), (BigInt(150), -2L))
      case other => fail(s"not a number: $other")
    assertEquals(parse("1.50"), parse("15e-1"))
  }

  test("equality, hashing and rendering of 20,000 nested levels run on a small stack") {
    val depth = 20000
    def nested(leaf: JsonValue) = (1 to depth).foldLeft(leaf) { (v, i) =>
      if i % 2 == 0 then JsonValue.Array(Vector(v))
      else JsonValue.Object(JsonObject.fromList(List("k" -> v)))
    }
    val (a, b, c) = (nested(number(1, 1)), nested(number(10, 0)), nested(number(2, 0)))
    val (same, different, hashes, rendered) = PreprodDeepTx.onSmallStack(
      (a == b, a == c, a.hashCode == b.hashCode, a.toString.length)
    )
    assert(same)
    assert(!different)
    assert(hashes)
    assertEquals(rendered, depth / 2 * ("[]".length + "{\"k\":}".length) + "1e1".length)
  }
