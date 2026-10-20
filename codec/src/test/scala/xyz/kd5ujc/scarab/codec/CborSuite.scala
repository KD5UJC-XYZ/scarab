// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

class CborSuite extends munit.FunSuite:
  def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  def decoded(hex: String): Node = Cbor.decode(bytes(hex)).fold(e => fail(e.toString), identity)
  def encoded(value: Value): String = Cbor.encode(value).fold(e => fail(e.toString), _.hex)
  def n(value: Value): Node = Node(value, Bytes.empty)

  test("bytes validate strict hex and isolate mutable arrays") {
    assertEquals(bytes("aB00").hex, "ab00")
    assertEquals(Bytes.fromHex("0"), Left(Bytes.HexError.OddLength(1)))
    assertEquals(Bytes.fromHex(" 0"), Left(Bytes.HexError.InvalidCharacter(0, ' ')))
    assertEquals(Bytes.fromHex("zz"), Left(Bytes.HexError.InvalidCharacter(0, 'z')))
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
    assertEquals(Cbor.encode(Value.UInt(max + 1)), Left(Cbor.Error.ArgumentOutOfRange(max + 1)))
    assertEquals(Cbor.encode(Value.UInt(-1)), Left(Cbor.Error.ArgumentOutOfRange(-1)))
    assertEquals(Cbor.encode(Value.NInt(0)), Left(Cbor.Error.NonNegativeNInt(0)))
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
    assert(Cbor.decode(bytes("81" * 2049 + "00"), Cbor.Limits(maxDepth = 2048)).isLeft)
  }
  test("encoder honors caller-selected output, item, depth and invalid limits") {
    val text = Value.Text("ab")
    assertEquals(
      Cbor.encode(Value.UInt(0), Cbor.Limits(maxInputBytes = 0)),
      Left(Cbor.Error.OutputLimitExceeded)
    )
    assertEquals(
      Cbor.encode(text, Cbor.Limits(maxStringBytes = 1)),
      Left(Cbor.Error.StringLimitExceeded)
    )
    assertEquals(
      Cbor.encode(Value.Arr(Vector(n(Value.UInt(0)))), Cbor.Limits(maxItems = 1)),
      Left(Cbor.Error.ItemLimitExceeded)
    )
    assertEquals(
      Cbor.encode(Value.Arr(Vector(n(Value.UInt(0)))), Cbor.Limits(maxDepth = 0)),
      Left(Cbor.Error.DepthLimitExceeded)
    )
    assertEquals(
      Cbor.encode(Value.UInt(0), Cbor.Limits(maxItems = -1)),
      Left(Cbor.Error.InvalidLimits)
    )
    // `allowFloats` governs decoder acceptance; the encoder retains its prior float behavior.
    assertEquals(
      Cbor.encode(Value.Float(1.0, FloatWidth.Single), Cbor.Limits()),
      Right(bytes("fa3f800000"))
    )
  }
  test("encoder walks deeply nested values on a small-stack thread") {
    val deep =
      (0 until 30000).foldLeft[Value](Value.UInt(0))((value, _) => Value.Arr(Vector(n(value))))
    onSmallStack {
      val encoded =
        Cbor.encode(deep, Cbor.Limits(maxInputBytes = 100000, maxDepth = 30000, maxItems = 30001))
      assert(encoded.isRight)
    }
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
    val result = Cbor.decode(Bytes.fromArray(input)).fold(e => fail(e.toString), identity)
    input(2) = 9
    val exported = result.original.toArray
    exported(2) = 7
    assertEquals(result.original.hex, "811801")
    result.value match
      case Value.Arr(values) => assertEquals(values.head.original.hex, "1801")
      case _                 => fail("array expected")
  }

  /** Run `body` on a thread with a small stack, so that a deep item overflows it for certain. */
  private def onSmallStack[A](body: => A): A =
    var result: Option[Either[Throwable, A]] = None
    val thread = new Thread(
      null,
      () =>
        result = Some(
          try Right(body)
          catch case t: Throwable => Left(t)
        ),
      "small",
      256 * 1024
    )
    thread.start()
    thread.join()
    result.get.fold(throw _, identity)

  private def repeat(unit: String, times: Int): String = unit * times

  private val Wide = Cbor.Limits(maxInputBytes = 1 << 20, maxItems = 1 << 20)

  test("nesting is not limited by the stack: a block's worth of levels decodes and encodes") {
    // regression (fuzz K3, preprod block 5,183,974): 90,000 nested arrays on a 256 KiB stack,
    // where the recursive decoder overflowed at about 900 and gave up at 2048 levels
    val nested = bytes(repeat("81", 90000) + "00")
    val outcome = onSmallStack(
      Cbor.decode(nested, Wide).flatMap(node => Cbor.encode(node.value).map(node -> _))
    )
    val (node, again) = outcome.fold(e => fail(e.toString), identity)
    assertEquals(node.original, nested)
    assertEquals(again, nested)
  }

  test("a depth limit still applies when asked for, with the nesting on the heap") {
    val nested = bytes(repeat("81", 5000) + "00")
    assertEquals(
      Cbor.decode(nested, Wide.copy(maxDepth = 4000)),
      Left(Cbor.Error.DepthLimitExceeded)
    )
    assert(Cbor.decode(nested, Wide.copy(maxDepth = 5000)).isRight)
  }

  /** Random nestings of every container kind, `levels` deep, with an optional leaf at the bottom.
    */
  private def nesting(random: scala.util.Random, levels: Int): (String, Boolean) =
    val open = new StringBuilder
    val close = new java.util.ArrayDeque[String]
    var definiteOnly = true
    var i = 0
    while i < levels do
      random.nextInt(6) match
        case 0 => open ++= "81"
        case 1 => open ++= "a100"; // map {0: <next>}
        case 2 => open ++= "c1" // tag 1
        case 3 =>
          open ++= "9f"
          close.push("ff")
          definiteOnly = false
        case 4 =>
          open ++= "bf00"
          close.push("ff")
          definiteOnly = false
        case _ => open ++= "d90102" // tag 258
      i += 1
    (open.toString + "00" + close.toArray.mkString, definiteOnly)

  test("property: random nestings up to a block's depth round-trip on a small stack") {
    val random = new scala.util.Random(2026)
    (1 to 25).foreach { round =>
      val levels = if round <= 5 then 30000 + random.nextInt(15000) else random.nextInt(45000)
      val (text, definiteOnly) = nesting(random, levels)
      val wire = bytes(text)
      onSmallStack {
        val node = Cbor.decode(wire, Wide).fold(e => fail(e.toString), identity)
        assertEquals(node.original, wire)
        val once = Cbor.encode(node.value).fold(e => fail(e.toString), identity)
        if definiteOnly then
          // shortest widths: only a non-shortest input differs; this generator writes shortest
          assertEquals(once, wire)
        val reread = Cbor.decode(once, Wide).fold(e => fail(e.toString), identity)
        assertEquals(reread.original, once)
        assertEquals(Cbor.encode(reread.value), Right(once))
      }
    }
  }

  test("a map or tag missing its second half, deep down, is an error") {
    onSmallStack {
      assertEquals(
        Cbor.decode(bytes(repeat("9f", 20000) + "bf00ff" + repeat("ff", 20000)), Wide),
        Left(Cbor.Error.UnmatchedMapKey)
      )
      assert(Cbor.decode(bytes(repeat("c1", 50000)), Wide).isLeft)
      assert(Cbor.decode(bytes(repeat("81", 50000) + "00" + "00"), Wide).isLeft)
      assert(Cbor.decode(bytes(repeat("9f", 50000) + "00"), Wide).isLeft)
    }
  }

  test("a few bytes that claim huge lengths fail at once, without allocating for the claim") {
    def timed(hex: String, limits: Cbor.Limits = Cbor.Limits()): Either[Cbor.Error, Node] =
      val started = System.nanoTime()
      val result = Cbor.decode(bytes(hex), limits)
      // A sanity guard (a refused claim must not allocate or hang), not a performance contract:
      // the exact bound is owned by the benchmarks, so it is generous for a loaded runner.
      assert((System.nanoTime() - started) / 1000000 < 5000)
      result
    // arrays and maps claiming 2^64 - 1, 2^32 - 1 and 90,000 elements in at most 10 bytes
    assertEquals(timed("9bffffffffffffffff"), Left(Cbor.Error.ItemLimitExceeded))
    assertEquals(timed("ba7fffffff"), Left(Cbor.Error.MapPairLimitExceeded))
    assertEquals(timed("9a00015f90" + "00"), Left(Cbor.Error.UnexpectedEnd))
    assertEquals(timed("9a00015f90", Wide), Left(Cbor.Error.UnexpectedEnd))
    assertEquals(timed("5bffffffffffffffff"), Left(Cbor.Error.StringLimitExceeded))
    assertEquals(timed("7a7fffffff" + "00"), Left(Cbor.Error.StringLimitExceeded))
    assertEquals(timed("5a000fffff00"), Left(Cbor.Error.TruncatedString))
    // nested claims: each level asks for the whole remaining budget
    assertEquals(timed("9a0001869f" * 2), Left(Cbor.Error.ItemLimitExceeded))
  }

  test("regression (fuzz K3): the guard that turns a stack overflow into an error value") {
    assertEquals(
      onSmallStack(Cbor.stackSafe[Int](throw new StackOverflowError())),
      Left(Cbor.TooDeep)
    )
  }

  private val Floats = Cbor.Limits(allowFloats = true)
  private def floatDecoded(hex: String): Value =
    Cbor.decode(bytes(hex), Floats).fold(e => fail(e.toString), _.value)

  test("floats are refused by default and read in all three widths when allowed") {
    // RFC 8949 appendix A: 1.0 and 1.5 as halves, 100000.0 as a single, 1.1 as a double
    assertEquals(floatDecoded("f93c00"), Value.Float(1.0, FloatWidth.Half))
    assertEquals(floatDecoded("f93e00"), Value.Float(1.5, FloatWidth.Half))
    assertEquals(floatDecoded("fa47c35000"), Value.Float(100000.0, FloatWidth.Single))
    assertEquals(floatDecoded("fb3ff199999999999a"), Value.Float(1.1, FloatWidth.Double))
    assertEquals(floatDecoded("f90001"), Value.Float(math.pow(2, -24), FloatWidth.Half))
    assertEquals(floatDecoded("f97c00"), Value.Float(Double.PositiveInfinity, FloatWidth.Half))
    assertEquals(floatDecoded("f9fc00"), Value.Float(Double.NegativeInfinity, FloatWidth.Half))
    assertEquals(floatDecoded("f98000"), Value.Float(-0.0, FloatWidth.Half))
    assertNotEquals(floatDecoded("f98000"), floatDecoded("f90000"))
    assert(Cbor.decode(bytes("fa47c35000")).isLeft)
  }

  test("a float keeps the width it was read in, and floats nest in indefinite containers") {
    assertEquals(
      Cbor.encode(floatDecoded("9ffa3c816216fa3c801386ff")).map(_.hex),
      Right("82fa3c816216fa3c801386")
    )
    Vector("f93c00", "fa47c35000", "fb3ff199999999999a", "f97bff", "f90400", "f98001")
      .foreach(h => assertEquals(Cbor.encode(floatDecoded(h)).map(_.hex), Right(h)))
  }

  test("binary16: every half round trips through a float, and rounding is nearest-even") {
    (0 to 0xffff).filterNot(b => (b & 0x7c00) == 0x7c00 && (b & 0x3ff) != 0).foreach { b =>
      assertEquals(Float16.fromFloat(Float16.toFloat(b)), b)
    }
    assertEquals(Float16.fromFloat(65504f), 0x7bff)
    assertEquals(Float16.fromFloat(65520f), 0x7c00) // rounds up to infinity
    assertEquals(Float16.fromFloat(1.0f + math.pow(2, -11).toFloat), 0x3c00) // a tie goes to even
    assertEquals(Float16.fromFloat(1.0f + 3 * math.pow(2, -11).toFloat), 0x3c02)
    assertEquals(
      Float16.fromFloat(math.pow(2, -25).toFloat),
      0
    ) // the tie below the least subnormal
    assertEquals(Float16.fromFloat(Float.NaN), 0x7e00)
  }
