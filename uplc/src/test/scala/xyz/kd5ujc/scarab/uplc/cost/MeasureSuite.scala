// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.cost

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.{Constant, DefaultUni, PlutusData, SmallStack, Text, Value}
import xyz.kd5ujc.scarab.uplc.bls.{G1Element, G2Element, MlResult}
import xyz.kd5ujc.scarab.uplc.builtin.{Argument, MachineValue}

/** The argument measures of `ExMemoryUsage.hs` (plutus 1.70.0.0), worked by hand. */
final class MeasureSuite extends munit.FunSuite:
  /** A machine value: a constant, or something else (a lambda, a delay). */
  private enum Machine:
    case Con(c: Constant)
    case Other

  private given MachineValue[Machine] with
    def constantOf(value: Machine): Option[Constant] = value match
      case Machine.Con(c) => Some(c)
      case Machine.Other  => None
    def ofConstant(constant: Constant): Machine = Machine.Con(constant)

  private def of(measure: Measure, c: Constant): Vector[Long] =
    measure(Argument.Con[Machine](c)).toVector.map(_.toLong)

  private def universe(c: Constant): Vector[Long] = of(Measure.universe, c)
  private def integer(n: BigInt): Constant = Constant.Integer(n)
  private def bytes(size: Int): Constant = Constant.ByteString(Bytes.wrap(new Array[Byte](size)))
  private def text(s: String): Constant = Constant.String(Text.fromString(s))
  private def list(size: Int): Constant =
    Constant.List
      .of(DefaultUni.Integer, Vector.fill(size)(integer(1)))
      .fold(e => fail(e.toString), identity)
  private def value(outer: Seq[(String, Seq[(String, Int)])]): Value =
    Value
      .decoded(
        outer.map((c, ts) =>
          Bytes.wrap(c.getBytes) -> ts.map((t, q) => Bytes.wrap(t.getBytes) -> BigInt(q))
        )
      )
      .fold(e => fail(e.toString), identity)

  test("an integer is its number of 64-bit words, 1 for 0 (memoryUsageInteger)") {
    val two64 = BigInt(1) << 64
    Vector[(BigInt, Long)](
      BigInt(0) -> 1,
      BigInt(1) -> 1,
      BigInt(-1) -> 1,
      BigInt(Long.MaxValue) -> 1,
      // 2^63: integerLog2 63, 63 div 64 + 1 = 1
      (BigInt(1) << 63) -> 1,
      (two64 - 1) -> 1,
      // 2^64: integerLog2 64, 64 div 64 + 1 = 2; its negation too (the size of |n|)
      two64 -> 2,
      -two64 -> 2,
      // 2^128 - 1: integerLog2 127 -> 2; 2^128 -> 3
      ((BigInt(1) << 128) - 1) -> 2,
      (BigInt(1) << 128) -> 3
    ).foreach((n, words) => assertEquals(universe(integer(n)), Vector(words), n.toString))
  }

  test("a byte string is ((n - 1) quot 8) + 1, so 1 for the empty one") {
    Vector(0 -> 1L, 1 -> 1L, 8 -> 1L, 9 -> 2L, 16 -> 2L, 17 -> 3L, 64 -> 8L, 65 -> 9L).foreach(
      (n, words) => assertEquals(universe(bytes(n)), Vector(words), s"$n bytes")
    )
  }

  test("a text is its characters in pieces of 100, the last of 1 to 100") {
    assertEquals(universe(text("")), Vector(0L))
    assertEquals(universe(text("a" * 100)), Vector(100L))
    assertEquals(universe(text("a" * 101)), Vector(100L, 1))
    assertEquals(universe(text("a" * 200)), Vector(100L, 100))
    assertEquals(universe(text("a" * 250)), Vector(100L, 100, 50))
    // Characters, not UTF-16 units or bytes: two code points outside the BMP are 2.
    assertEquals(universe(text("😀😀")), Vector(2L))
  }

  test("TextCostedByByteLength is the UTF-8 length quot 4") {
    assertEquals(of(Measure.textBytes, text("")), Vector(0L))
    assertEquals(of(Measure.textBytes, text("abc")), Vector(0L))
    assertEquals(of(Measure.textBytes, text("abcd")), Vector(1L))
    // é is two bytes: 2 quot 4 = 0; two emoji are 8 bytes: 2.
    assertEquals(of(Measure.textBytes, text("é")), Vector(0L))
    assertEquals(of(Measure.textBytes, text("😀😀")), Vector(2L))
    // 150 ASCII characters: 37, one cost (no pieces).
    assertEquals(of(Measure.textBytes, text("a" * 150)), Vector(37L))
  }

  test("a list is its length in pieces of 100, the last of 0 to 99 (dropN @100)") {
    assertEquals(universe(list(0)), Vector(0L))
    assertEquals(universe(list(99)), Vector(99L))
    assertEquals(universe(list(100)), Vector(100L, 0))
    assertEquals(universe(list(200)), Vector(100L, 100, 0))
    assertEquals(universe(list(250)), Vector(100L, 100, 50))
  }

  test("Data is 4 per node plus its integers and byte strings, in pre-order, keys before values") {
    val d = PlutusData.Constr(
      0,
      Vector(
        PlutusData.I(0),
        PlutusData.B(Bytes.wrap(new Array[Byte](9))),
        PlutusData.Map(Vector(PlutusData.I(1) -> PlutusData.List(Vector.empty)))
      )
    )
    // Constr 4; I 0: 4 + 1; B (9 bytes): 4 + 2; Map 4; its key I 1: 5; its value List []: 4.
    assertEquals(universe(Constant.Data(d)), Vector(4L, 5, 6, 4, 5, 4))
    // DataNodeCount: 1 per node, same order.
    assertEquals(of(Measure.nodeCount, Constant.Data(d)), Vector.fill(6)(1L))
    assertEquals(universe(Constant.Data(PlutusData.I(BigInt(1) << 64))), Vector(6L))
  }

  test("NumBytesCostedAsNumWords rounds down, so 0 bytes are 0 words, and clamps at maxBound") {
    Vector[(BigInt, Long)](
      BigInt(0) -> 0,
      BigInt(1) -> 1,
      BigInt(8) -> 1,
      BigInt(9) -> 2,
      BigInt(-9) -> 2,
      BigInt(8192) -> 1024,
      (BigInt(1) << 70) -> Long.MaxValue
    ).foreach((n, words) => assertEquals(of(Measure.words, integer(n)), Vector(words), n.toString))
  }

  test("IntegerCostedLiterally is |n|, clamped at maxBound") {
    assertEquals(of(Measure.literal, integer(-5)), Vector(5L))
    assertEquals(of(Measure.literal, integer(0)), Vector(0L))
    assertEquals(of(Measure.literal, integer(BigInt(1) << 64)), Vector(Long.MaxValue))
    assertEquals(of(Measure.literal, integer(-(BigInt(1) << 63))), Vector(Long.MaxValue))
  }

  test("the Value measures: entries, currencies, and the sum of the depths") {
    val v = value(
      Vector(
        "a" -> Vector("x" -> 1),
        "b" -> Vector("w" -> 4, "x" -> 1, "y" -> 2, "z" -> 3),
        "c" -> Vector("x" -> 1)
      )
    )
    assertEquals(universe(Constant.Value(v)), Vector(6L))
    assertEquals(of(Measure.totalSize, Constant.Value(v)), Vector(6L))
    assertEquals(of(Measure.outerSize, Constant.Value(v)), Vector(3L))
    // integerLog2 3 + 1 = 2 (currencies) and integerLog2 4 + 1 = 3 (largest inner map)
    assertEquals(of(Measure.maxDepth, Constant.Value(v)), Vector(5L))
    assertEquals(of(Measure.maxDepth, Constant.Value(Value.empty)), Vector(0L))
  }

  test("the other types: unit, bool and the Int-like wrappers 1; BLS words; arrays by length") {
    assertEquals(universe(Constant.Unit), Vector(1L))
    assertEquals(universe(Constant.Bool(false)), Vector(1L))
    assertEquals(of(Measure.one, integer(BigInt(1) << 100)), Vector(1L))
    assertEquals(
      universe(Constant.Bls12_381_G1_Element(G1Element.fromCompressed(Bytes.empty))),
      Vector(18L)
    )
    assertEquals(
      universe(Constant.Bls12_381_G2_Element(G2Element.fromCompressed(Bytes.empty))),
      Vector(36L)
    )
    assertEquals(universe(Constant.Bls12_381_MlResult(MlResult.fromRaw(Bytes.empty))), Vector(72L))
    val array = Constant.Array
      .of(DefaultUni.Integer, Vector.fill(250)(integer(1)))
      .fold(e => fail(e.toString), identity)
    assertEquals(universe(array), Vector(250L))
    assertEquals(universe(Constant.Pair(integer(1), integer(2))), Vector(Long.MaxValue))
  }

  test("an opaque value is measured as its constant, and as 1 when it is not one") {
    assertEquals(
      Measure.universe(Argument.Opaque(Machine.Con(bytes(9)))).toVector.map(_.toLong),
      Vector(2L)
    )
    assertEquals(
      Measure.universe(Argument.Opaque[Machine](Machine.Other)).toVector.map(_.toLong),
      Vector(1L)
    )
  }

  test("a shared Data graph of exponential size is measured lazily, as far as it is forced") {
    // d(n + 1) = Constr 0 [d n, d n]: 2^61 - 1 nodes once unfolded at n = 60.
    val graph =
      (1 to 60).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.Constr(0, Vector(d, d)))
    val measured = Measure.constant(Constant.Data(graph))
    // Pre-order: the 60 constructors down the left spine, then the two I 0 under the last one.
    assertEquals(measured.take(62).map(_.toLong), Vector.fill(60)(4L) ++ Vector(5L, 5L))
    assertEquals(
      Measure.nodeCount(Argument.Con[Machine](Constant.Data(graph))).take(1000).size,
      1000
    )
  }

  test("a Data value a million levels deep is measured in constant stack") {
    val deep =
      (1 to 1000000).foldLeft(PlutusData.I(0): PlutusData)((d, _) => PlutusData.List(Vector(d)))
    SmallStack.run {
      // 1,000,000 lists of 4 and I 0 of 5
      assertEquals(Measure.constant(Constant.Data(deep)).sum, SatInt(4000005L))
      assertEquals(
        Measure.nodeCount(Argument.Con[Machine](Constant.Data(deep))).sum,
        SatInt(1000001L)
      )
    }
  }
