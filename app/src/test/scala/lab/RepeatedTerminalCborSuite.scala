// SPDX-License-Identifier: Apache-2.0
package lab

import lab.cbor.{Bytes, Cbor}
import RepeatedTerminalCbor.{Node, Value as V}

class RepeatedTerminalCborSuite extends munit.FunSuite:
  private val C = RepeatedTerminalCbor
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  private def decoded(hex: String): Node = C.decode(bytes(hex)).fold(error => fail(error.toString), identity)
  private def value(v: V): Node = Node(v, Bytes.empty)
  private def float64(raw: Long): String = "fb" + f"$raw%016x"
  private def head(major: Int, size: Int): Vector[Byte] =
    if size < 24 then Vector(((major << 5) | size).toByte)
    else if size < 256 then Vector(((major << 5) | 24).toByte, size.toByte)
    else if size < 65536 then
      Vector(((major << 5) | 25).toByte, (size >>> 8).toByte, size.toByte)
    else
      Vector(
        ((major << 5) | 26).toByte,
        (size >>> 24).toByte,
        (size >>> 16).toByte,
        (size >>> 8).toByte,
        size.toByte
      )

  test("exact uint64 and negative CBOR ranges without changing the core decoder") {
    val max = (BigInt(1) << 64) - 1
    assertEquals(C.unsigned(decoded("1bffffffffffffffff")), Right(max))
    assertEquals(C.signed(decoded("3bffffffffffffffff")), Right(-max - 1))
    assertEquals(C.signed(decoded("1801")), Right(BigInt(1)))
    assert(C.unsigned(decoded("20")).isLeft)
    assert(C.signed(value(V.NInt(0))).isLeft)
    assert(C.unsigned(value(V.UInt(max + 1))).isLeft)
    assert(C.signed(value(V.NInt(-max - 2))).isLeft)
    assert(Cbor.decode(bytes("fa3f800000")).isLeft)
    assertEquals(decoded("fa3f800000").value, V.Float32(0x3f800000))
  }

  test("every nested wire span preserves nonminimal headers and indefinite containers") {
    val raw = "9f18015f41024103ffbf61619ff4f5f6ffffff"
    val n = decoded(raw)
    assertEquals(n.original.hex, raw)
    val a = C.array(n, 3).toOption.get
    assertEquals(a(0).original.hex, "1801")
    assertEquals(a(1).original.hex, "5f41024103ff")
    assertEquals(C.bytes(a(1), 2), Right(bytes("0203")))
    a(2).value match
      case V.Map(pairs) =>
        assertEquals(pairs.head._1.original.hex, "6161")
        assertEquals(pairs.head._2.original.hex, "9ff4f5f6ff")
        assertEquals(C.rows(pairs.head._2).toOption.get.map(_.value), Vector(V.Bool(false), V.Bool(true), V.Null))
      case _ => fail("map expected")
  }

  test("tag arguments and definite and indefinite strings remain exact") {
    val tag = decoded("dbffffffffffffffff7f6268696121ff")
    tag.value match
      case V.Tag(number, inner) =>
        assertEquals(number, (BigInt(1) << 64) - 1)
        assertEquals(inner.value, V.Text("hi!"))
        assertEquals(inner.original.hex, "7f6268696121ff")
      case _ => fail("tag expected")
    assertEquals(decoded("5fff").value, V.ByteString(Bytes.empty))
    assertEquals(decoded("7fff").value, V.Text(""))
    assertEquals(decoded("63e282ac").value, V.Text("€"))
    assertEquals(decoded("7f63e282ac6121ff").value, V.Text("€!"))
  }

  test("strict UTF-8 is checked separately for every indefinite text chunk") {
    Vector("61ff", "62c080", "63eda080", "64f4908080", "7f61e26282acff").foreach { hex =>
      assert(C.decode(bytes(hex)).isLeft, hex)
    }
  }

  test("map entries remain ordered and semantic duplicates are refused after key conversion") {
    val n = decoded("a301001801010202")
    n.value match
      case V.Map(pairs) =>
        assertEquals(pairs.map(_._1.original.hex), Vector("01", "1801", "02"))
      case _ => fail("map expected")
    assertEquals(C.mapping(n)(C.unsigned, C.unsigned), Left(C.Failure.DuplicateKey))
    val chunked = decoded("bf4101005f4101ff01ff")
    assert(C.mapping(chunked)(C.bytes(_, 1), C.unsigned).isLeft)
    assertEquals(
      C.mapping(decoded("a202180a011814"))(C.unsigned, C.unsigned),
      Right(Map(BigInt(2) -> BigInt(10), BigInt(1) -> BigInt(20)))
    )
  }

  test("float wire width and signed zero are retained exactly") {
    Vector(
      "f90000" -> V.Float16(0),
      "f98000" -> V.Float16(0x8000),
      "f90001" -> V.Float16(1),
      "f97bff" -> V.Float16(0x7bff),
      "fa00000000" -> V.Float32(0),
      "fa80000000" -> V.Float32(0x80000000),
      "fa00000001" -> V.Float32(1),
      "fa7f7fffff" -> V.Float32(0x7f7fffff),
      "fb0000000000000000" -> V.Float64(0L),
      "fb8000000000000000" -> V.Float64(Long.MinValue),
      "fb0000000000000001" -> V.Float64(1L),
      "fb7fefffffffffffff" -> V.Float64(0x7fefffffffffffffL)
    ).foreach { case (hex, expected) =>
      val n = decoded(hex)
      assertEquals(n.value, expected)
      assertEquals(n.original.hex, hex)
    }
    Vector("f98000", "fa80000000", "fb8000000000000000").foreach { hex =>
      assertEquals(C.float32(decoded(hex)), Right(0x80000000))
    }
  }

  test("all finite binary16 words widen exactly, including every signed subnormal") {
    (0 to 65535).foreach { raw =>
      val exponent = (raw >>> 10) & 31
      if exponent != 31 then
        val fraction = raw & 1023
        val magnitude =
          if exponent == 0 then Math.scalb(fraction.toDouble, -24)
          else Math.scalb((1024 + fraction).toDouble, exponent - 25)
        val expected = (if (raw & 0x8000) != 0 then -magnitude else magnitude).toFloat
        assertEquals(C.float32(value(V.Float16(raw))), Right(java.lang.Float.floatToRawIntBits(expected)))
      else assert(C.float32(value(V.Float16(raw))).isLeft)
    }
  }

  test("finite binary32 words and exactly representable binary64 values give identical raw32") {
    Vector(0, Int.MinValue, 1, Int.MinValue | 1, 0x007fffff, 0x00800000, 0x3f800000, 0xbf800000, 0x7f7fffff, 0xff7fffff).foreach { raw =>
      val d = java.lang.Float.intBitsToFloat(raw).toDouble
      val source = java.lang.Double.doubleToRawLongBits(d)
      assertEquals(C.float32(value(V.Float32(raw))), Right(raw))
      assertEquals(C.float32(decoded(float64(source))), Right(raw))
    }
  }

  test("binary64 narrowing rejects rounding, underflow and overflow honestly") {
    Vector(0.1d, Math.nextUp(1.0d), java.lang.Double.MIN_VALUE, -java.lang.Double.MIN_VALUE, java.lang.Double.MAX_VALUE, -java.lang.Double.MAX_VALUE).foreach { d =>
      val n = decoded(float64(java.lang.Double.doubleToRawLongBits(d)))
      assert(C.float32(n).isLeft, d.toString)
    }
    assert(C.float32(decoded("01")).isLeft)
  }

  test("native Float accepts half and single but refuses even exact binary64 values") {
    assertEquals(C.nativeFloat32(decoded("f93c00")), Right(0x3f800000))
    assertEquals(C.nativeFloat32(decoded("fa3f800000")), Right(0x3f800000))
    Vector("fb0000000000000000", "fb8000000000000000", "fb3ff0000000000000").foreach { hex =>
      assert(C.float32(decoded(hex)).isRight)
      assertEquals(C.nativeFloat32(decoded(hex)), Left(C.Failure.Unsupported("binary64 native Float field")))
    }
  }

  test("infinities and signaling and quiet NaNs reject in every floating width") {
    Vector(
      "f97c00", "f9fc00", "f97c01", "f9fc01", "f97e00", "f9fe00",
      "fa7f800000", "faff800000", "fa7f800001", "fa7fc00000", "faffc00000",
      "fb7ff0000000000000", "fbfff0000000000000", "fb7ff0000000000001", "fb7ff8000000000000", "fbfff8000000000000"
    ).foreach(hex => assert(C.decode(bytes(hex)).isLeft, hex))
    assert(C.float32(value(V.Float16(65536))).isLeft)
    assert(C.float32(value(V.Float32(0x7f800000))).isLeft)
    assert(C.float32(value(V.Float64(0x7ff0000000000000L))).isLeft)
  }

  test("every scalar argument and float payload truncation is rejected") {
    Vector("1bffffffffffffffff", "3bffffffffffffffff", "d9ffff00", "f93c00", "fa3f800000", "fb3ff0000000000000").foreach { hex =>
      val full = bytes(hex)
      (0 until full.size).foreach { size =>
        assert(C.decode(Bytes(full.value.take(size))).isLeft, s"$hex truncated to $size")
      }
    }
  }

  test("malformed containers reserved values invalid chunks and trailing bytes reject") {
    Vector("", "ff", "00ff", "0000", "1c", "1d", "1e", "1f", "3f", "df00", "f7", "f0", "f814", "fc", "fd", "fe", "41", "42aa", "5f6100ff", "5f5fffff", "7f4100ff", "7f7fffff", "9f00", "bf01ff", "bf01", "a101", "8100ff", "a10000ff", "c0").foreach { hex =>
      assert(C.decode(bytes(hex)).isLeft, hex)
    }
    assert(C.decode(null).isLeft)
    assert(C.decode(Bytes(null)).isLeft)
  }

  test("all 64-bit announced lengths are bounded before integer conversion or allocation") {
    Vector("5bffffffffffffffff", "7bffffffffffffffff", "9bffffffffffffffff", "bbffffffffffffffff", "5b0000000100000000", "9b0000000100000000", "bb0000000100000000").foreach { hex =>
      assert(C.decode(bytes(hex)).isLeft, hex)
    }
  }

  test("exact input byte limit is accepted and the next byte is rejected") {
    val payloadSize = C.MaxInputBytes - 5
    val raw = Bytes(head(2, payloadSize) ++ Vector.fill(payloadSize)(0.toByte))
    val n = C.decode(raw).fold(error => fail(error.toString), identity)
    assertEquals(n.original.size, C.MaxInputBytes)
    assertEquals(C.bytes(n, payloadSize).toOption.get.size, payloadSize)
    assertEquals(C.decode(Bytes(raw.value :+ 0.toByte)), Left(C.Failure.Limit(C.LimitKind.InputBytes)))
  }

  test("nested large payloads cannot multiply retained originals beyond the cumulative bound") {
    val payloadSize = 1024 * 1024
    val leaf = head(2, payloadSize) ++ Vector.fill(payloadSize)(0.toByte)
    val below = Bytes(Vector.fill(14)(0x81.toByte) ++ leaf)
    assert(C.decode(below).isRight)
    val above = Bytes(Vector.fill(15)(0x81.toByte) ++ leaf)
    assert(above.size < C.MaxInputBytes)
    assertEquals(C.decode(above), Left(C.Failure.Limit(C.LimitKind.RetainedOriginalBytes)))
  }

  test("array and map entry bounds apply equally to definite and indefinite containers") {
    Vector(4 -> 1, 5 -> 2).foreach { case (major, width) =>
      val body = Vector.fill(C.MaxContainerEntries * width)(0.toByte)
      assert(C.decode(Bytes(head(major, C.MaxContainerEntries) ++ body)).isRight)
      assert(C.decode(Bytes(Vector(((major << 5) | 31).toByte) ++ body :+ 255.toByte)).isRight)
      val tooMany = body ++ Vector.fill(width)(0.toByte)
      assert(C.decode(Bytes(head(major, C.MaxContainerEntries + 1) ++ tooMany)).isLeft)
      assert(C.decode(Bytes(Vector(((major << 5) | 31).toByte) ++ tooMany :+ 255.toByte)).isLeft)
    }
  }

  test("depth zero is the root and tags arrays and indefinite string chunks share the limit") {
    assert(C.decode(bytes("81" * C.MaxDepth + "00")).isRight)
    assert(C.decode(bytes("81" * (C.MaxDepth + 1) + "00")).isLeft)
    assert(C.decode(bytes("c0" * C.MaxDepth + "00")).isRight)
    assert(C.decode(bytes("c0" * (C.MaxDepth + 1) + "00")).isLeft)
    assert(C.decode(bytes("81" * C.MaxDepth + "5fff")).isRight)
    assert(C.decode(bytes("81" * (C.MaxDepth - 1) + "5f40ff")).isRight)
    assert(C.decode(bytes("81" * C.MaxDepth + "5f40ff")).isLeft)
  }

  private def manyNodes(total: Int): Bytes =
    val fullRows = (total - 1) / (C.MaxContainerEntries + 1)
    val remainder = (total - 1) % (C.MaxContainerEntries + 1)
    val out = Vector.newBuilder[Byte]
    out ++= head(4, fullRows + (if remainder == 0 then 0 else 1))
    (0 until fullRows).foreach { _ =>
      out ++= head(4, C.MaxContainerEntries)
      out ++= Vector.fill(C.MaxContainerEntries)(0.toByte)
    }
    if remainder > 0 then
      out ++= head(4, remainder - 1)
      out ++= Vector.fill(remainder - 1)(0.toByte)
    Bytes(out.result())

  test("exact global node bound is accepted across individually bounded arrays") {
    assert(C.decode(manyNodes(C.MaxNodes)).isRight)
    assertEquals(C.decode(manyNodes(C.MaxNodes + 1)), Left(C.Failure.Limit(C.LimitKind.Nodes)))
  }

  test("even empty indefinite string chunks consume the global node budget") {
    val chunks = Vector.fill(C.MaxNodes - 1)(0x40.toByte)
    assert(C.decode(Bytes(Vector(0x5f.toByte) ++ chunks :+ 0xff.toByte)).isRight)
    assertEquals(
      C.decode(Bytes(Vector(0x5f.toByte) ++ chunks ++ Vector(0x40.toByte, 0xff.toByte))),
      Left(C.Failure.Limit(C.LimitKind.Nodes))
    )
  }

  test("typed helpers fail on shape width and numeric-kind confusion") {
    assert(C.rows(decoded("a0")).isLeft)
    assert(C.array(decoded("80"), 1).isLeft)
    assert(C.array(decoded("80"), -1).isLeft)
    assert(C.bytes(decoded("40"), 1).isLeft)
    assert(C.bytes(decoded("60"), 0).isLeft)
    assert(C.mapping(decoded("80"))(C.unsigned, C.unsigned).isLeft)
    assert(C.unsigned(decoded("f90000")).isLeft)
    assert(C.rows(null).isLeft)
    assert(C.signed(null).isLeft)
  }
