// SPDX-License-Identifier: Apache-2.0
package lab.cbor

class CborSuite extends munit.FunSuite:
  def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  def decoded(hex: String): Node = Cbor.decode(bytes(hex)).fold(fail(_), identity)
  def encoded(value: Value): String = Cbor.encode(value).fold(fail(_), _.hex)
  def n(value: Value): Node = Node(value, Bytes.empty)

  test("bytes validate strict hex and isolate mutable arrays") {
    assertEquals(bytes("aB00").hex, "ab00")
    assert(Bytes.fromHex("0").isLeft)
    assert(Bytes.fromHex(" 0").isLeft)
    assert(Bytes.fromHex("zz").isLeft)
    val a = Array[Byte](1, 2)
    val b = Bytes.fromArray(a)
    a(0) = 9
    val c = b.toArray
    c(1) = 9
    assertEquals(b.hex, "0102")
  }
  test("full unsigned 64-bit and negative ranges remain exact") {
    val max = (BigInt(1) << 64) - 1
    assertEquals(decoded("1bffffffffffffffff").value, Value.UInt(max))
    assertEquals(decoded("3bffffffffffffffff").value, Value.NInt(-max - 1))
    assertEquals(encoded(Value.UInt(max)), "1bffffffffffffffff")
    assertEquals(encoded(Value.NInt(-max - 1)), "3bffffffffffffffff")
    assert(Cbor.encode(Value.UInt(max + 1)).isLeft)
    assert(Cbor.encode(Value.UInt(-1)).isLeft)
    assert(Cbor.encode(Value.NInt(0)).isLeft)
    assert(Cbor.encode(Value.NInt(-max - 2)).isLeft)
  }
  test("shortest integer widths at boundaries") {
    Vector(
      (BigInt(23), "17"),
      (BigInt(24), "1818"),
      (BigInt(255), "18ff"),
      (BigInt(256), "190100"),
      (BigInt(65535), "19ffff"),
      (BigInt(65536), "1a00010000"),
      (BigInt(1) << 32, "1b0000000100000000")
    )
      .foreach { case (value, hex) =>
        assertEquals(encoded(Value.UInt(value)), hex)
        assertEquals(decoded(hex).value, Value.UInt(value))
      }
  }
  test("original bytes preserve nonminimal and indefinite encodings recursively") {
    val node = decoded("9f18015f41024103ffff")
    assertEquals(node.original.hex, "9f18015f41024103ffff")
    node.value match
      case Value.Arr(values) =>
        assertEquals(values(0).original.hex, "1801")
        assertEquals(values(1).original.hex, "5f41024103ff")
        assertEquals(values(1).value, Value.ByteString(bytes("0203")))
      case _ => fail("array expected")
    assertEquals(encoded(node.value), "8201420203")
  }
  test("indefinite text, arrays, maps and tags") {
    assertEquals(decoded("7f6268696121ff").value, Value.Text("hi!"))
    assertEquals(decoded("5fff").value, Value.ByteString(Bytes.empty))
    assertEquals(decoded("7fff").value, Value.Text(""))
    assertEquals(encoded(decoded("bf61619f01f5ffff").value), "a161618201f5")
    assertEquals(encoded(decoded("d8184100").value), "d8184100")
    assertEquals(encoded(decoded("9fff").value), "80")
  }
  test("map order and duplicate keys are retained rather than silently canonicalized") {
    val hex = "a3010002000101"
    assertEquals(encoded(decoded(hex).value), hex)
  }
  test("malformed structures, floats and unsupported simple values rejected") {
    Vector(
      "",
      "ff",
      "0000",
      "18",
      "1bffffffff",
      "1c",
      "1f",
      "3f",
      "41",
      "42aa",
      "5f6100ff",
      "5f5fffff",
      "7f4100ff",
      "7f7fffff",
      "9f00",
      "bf01ff",
      "a101",
      "c0",
      "f0",
      "f7",
      "f814",
      "f9c000",
      "fa00000000",
      "fb0000000000000000",
      "fc",
      "df00"
    )
      .foreach(hex => assert(Cbor.decode(bytes(hex)).isLeft, s"unexpected acceptance: $hex"))
  }
  test("UTF-8 is strict, including each indefinite text chunk") {
    Vector("61ff", "62c0af", "63eda080", "64f4908080", "7f61c361a9ff")
      .foreach(hex => assert(Cbor.decode(bytes(hex)).isLeft, hex))
    assertEquals(decoded("62c3a9").value, Value.Text("é"))
    assertEquals(encoded(Value.Text("é")), "62c3a9")
    assert(Cbor.encode(Value.Text("\ud800")).isLeft)
  }
  test(
    "bounds apply to total input, depth, item count, definite and cumulative indefinite lengths"
  ) {
    assert(Cbor.decode(bytes("00"), Cbor.Limits(maxInputBytes = 0)).isLeft)
    assert(Cbor.decode(bytes("8100"), Cbor.Limits(maxDepth = 0)).isLeft)
    assert(Cbor.decode(bytes("00"), Cbor.Limits(maxDepth = 0)).isRight)
    assert(Cbor.decode(bytes("8100"), Cbor.Limits(maxItems = 1)).isLeft)
    assert(Cbor.decode(bytes("9f00ff"), Cbor.Limits(maxItems = 1)).isLeft)
    assert(Cbor.decode(bytes("5f41004100ff"), Cbor.Limits(maxStringBytes = 1)).isLeft)
    assert(Cbor.decode(bytes("420000"), Cbor.Limits(maxStringBytes = 1)).isLeft)
    assert(Cbor.decode(bytes("7f61616162ff"), Cbor.Limits(maxStringBytes = 1)).isLeft)
    assert(Cbor.decode(bytes("00"), Cbor.Limits(maxItems = -1)).isLeft)
    assert(Cbor.decode(bytes("5bffffffffffffffff")).isLeft)
    assert(Cbor.decode(bytes("9bffffffffffffffff")).isLeft)
    assert(Cbor.decode(bytes("bbffffffffffffffff")).isLeft)
    assert(Cbor.decode(bytes("81" * 65 + "00")).isLeft)
  }
  test("empty strings, null, booleans and selected values roundtrip") {
    val values = Vector(
      Value.Null,
      Value.Bool(false),
      Value.Bool(true),
      Value.Text(""),
      Value.ByteString(Bytes.empty),
      Value.Arr(Vector.empty),
      Value.Map(Vector.empty),
      Value.Tag(42, n(Value.UInt(7))),
      Value.Arr(Vector(n(Value.NInt(-2)), n(Value.Text("x"))))
    )
    values.foreach { value =>
      val hex = encoded(value)
      assertEquals(encoded(decoded(hex).value), hex)
    }
  }

  test("every strict prefix of a compound item is rejected") {
    val full = bytes("9f1bffffffffffffffffa16178c24201027f626f6bff5f4201024103ffff")
    assert(Cbor.decode(full).isRight)
    (0 until full.size).foreach { length =>
      assert(Cbor.decode(Bytes(full.value.take(length))).isLeft, s"prefix $length")
    }
  }
  test("all one-byte inputs return a result without throwing") {
    (0 to 255).foreach { h =>
      Cbor.decode(Bytes(Vector(h.toByte)))
    }
  }
  test("wire bytes are independent of source and exported arrays") {
    val input = Array[Byte](0x81.toByte, 0x18.toByte, 0x01.toByte)
    val result = Cbor.decode(Bytes.fromArray(input)).fold(fail(_), identity)
    input(2) = 9
    val exported = result.original.toArray
    exported(2) = 7
    assertEquals(result.original.hex, "811801")
    result.value match
      case Value.Arr(values) => assertEquals(values.head.original.hex, "1801")
      case _                 => fail("array expected")
  }
