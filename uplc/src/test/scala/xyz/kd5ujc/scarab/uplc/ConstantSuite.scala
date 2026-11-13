// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import xyz.kd5ujc.scarab.codec.Bytes

/** Constants: typed lists and arrays, their types, `Text`, and stack-safe equality of deep types.
  */
final class ConstantSuite extends munit.FunSuite:

  test("lists and arrays hold only elements of their type") {
    val ints =
      Constant.List.of(DefaultUni.Integer, Vector(Constant.Integer(1), Constant.Integer(2)))
    assertEquals(ints.map(_.typeOf), Right(DefaultUni.List(DefaultUni.Integer)))
    assertEquals(
      Constant.List.of(DefaultUni.Integer, Vector(Constant.Integer(1), Constant.Bool(true))),
      Left(Constant.ElementTypeMismatch(DefaultUni.Integer, DefaultUni.Bool, 1))
    )
    val empty = Constant.List.empty(DefaultUni.Data)
    assertEquals(
      empty.cons(Constant.Unit),
      Left(Constant.ElementTypeMismatch(DefaultUni.Data, DefaultUni.Unit, 0))
    )
    assertEquals(empty.tail, None)
    assertNotEquals[Constant, Constant](empty, Constant.List.empty(DefaultUni.Integer))
    val pair = Constant.Pair(Constant.Integer(1), empty)
    assertEquals(pair.typeOf, DefaultUni.Pair(DefaultUni.Integer, DefaultUni.List(DefaultUni.Data)))
    assertEquals(ints.map(_.toArray.typeOf), Right(DefaultUni.Array(DefaultUni.Integer)))
    assertEquals(ints.map(_.drop(1).items), Right(Vector(Constant.Integer(2))))
  }

  test("text: surrogates become U+FFFD, both lengths are kept, UTF-8 is decoded strictly") {
    val t = Text.fromCodePoints(Seq(0x61, 0xd83d, 0xde00, 0x1f600)).getOrElse(fail("text"))
    assertEquals(t.string, "a��😀")
    assertEquals((t.codePoints, t.utf8Length), (4, 1 + 3 + 3 + 4))
    assertEquals(Text.fromString("x\ud800y").string, "x�y")
    assertEquals(Text.fromString("😀").codePoints, 1)
    assertEquals(
      Text.fromCodePoints(Seq(0x110000)),
      Left(Text.Invalid.CodePointOutOfRange(0, 0x110000))
    )
    assertEquals(Text.fromUtf8(t.utf8), Right(t))
    def bytes(xs: Int*): Bytes = Bytes.wrap(xs.map(_.toByte).toArray)
    assertEquals(Text.fromUtf8(bytes(0xc0, 0x80)), Left(Text.Invalid.InvalidUtf8), "overlong")
    assertEquals(
      Text.fromUtf8(bytes(0xed, 0xa0, 0x80)),
      Left(Text.Invalid.InvalidUtf8),
      "surrogate"
    )
    assertEquals(
      Text.fromUtf8(bytes(0xf4, 0x90, 0x80, 0x80)),
      Left(Text.Invalid.InvalidUtf8),
      "beyond U+10FFFF"
    )
    assertEquals(Text.fromUtf8(bytes(0xe2, 0x82)), Left(Text.Invalid.InvalidUtf8), "truncated")
    assertEquals(Text.fromUtf8(Bytes.empty), Right(Text.empty))
  }

  test("text: the first code point out of range refuses at once, even before an endless rest") {
    assertEquals(
      Text.fromCodePoints(Iterator.single(-1) ++ Iterator.continually(0x61)),
      Left(Text.Invalid.CodePointOutOfRange(0, -1))
    )
    assertEquals(
      Text.fromCodePoints(Iterator(0x61, 0x62, 0x110000) ++ Iterator.continually(0x61)),
      Left(Text.Invalid.CodePointOutOfRange(2, 0x110000))
    )
  }

  test("deep types and constants compare, hash and render on a small stack") {
    SmallStack.run {
      val depth = 200000
      def deepType: DefaultUni =
        (1 to depth).foldLeft(DefaultUni.Data: DefaultUni)((t, _) => DefaultUni.List(t))
      val (x, y) = (deepType, deepType)
      val same = x == y
      assert(same)
      assertEquals(x.hashCode, y.hashCode)
      assertEquals(x.size, 2 * depth + 1)
      val other =
        (1 to depth).foldLeft(DefaultUni.Integer: DefaultUni)((t, _) => DefaultUni.List(t))
      val differ = x == other
      assert(!differ)
      def nested(leaf: Constant): Constant = (1 to depth).foldLeft(leaf) { (c, _) =>
        Constant.List.of(c.typeOf, Vector(c)).getOrElse(fail("nesting"))
      }
      val equalConstants = nested(Constant.Unit) == nested(Constant.Unit)
      assert(equalConstants)
      assert(x.toString.length <= 1024 + 3)
    }
  }
