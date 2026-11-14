// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc.builtin.extended

import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.uplc.DefaultFun.*
import xyz.kd5ujc.scarab.uplc.builtin.{BuiltinFailure, ErrorClass, EvaluationFailure, Outcome}
import xyz.kd5ujc.scarab.uplc.builtin.core.CoreVariant

import ExtendedTesting.*

/** The conversions and bitwise builtins against `PlutusCore/Bitwise.hs` (plutus 1.70.0.0, cited by
  * line) and the examples of CIP-121, CIP-122 and CIP-123.
  */
final class BitwiseSuite extends ScalaCheckSuite:
  private def hex(s: String): Bytes = hexBytes(s)
  private def bitwise(f: BitwiseFailure) = EvaluationFailure.Bitwise(f)

  test(
    "integerToByteString: the fewest bytes for length 0, padded otherwise (Bitwise.hs:157-201)"
  ) {
    assertEquals(bytesOf(e(IntegerToByteString, bool(true), int(0), int(0))), Bytes.empty)
    assertEquals(bytesOf(e(IntegerToByteString, bool(true), int(3), int(0))), hex("000000"))
    assertEquals(bytesOf(e(IntegerToByteString, bool(true), int(0), int(0x0102))), hex("0102"))
    assertEquals(bytesOf(e(IntegerToByteString, bool(false), int(0), int(0x0102))), hex("0201"))
    assertEquals(bytesOf(e(IntegerToByteString, bool(true), int(4), int(0x0102))), hex("00000102"))
    assertEquals(bytesOf(e(IntegerToByteString, bool(false), int(4), int(0x0102))), hex("02010000"))
    val largest = (BigInt(1) << 65536) - 1
    assertEquals(bytesOf(e(IntegerToByteString, bool(true), int(0), int(largest))).size, 8192)
  }

  test("integerToByteString: each failure with the lines it emits, in the order checked (90-140)") {
    assertEquals(
      failureOf(e(IntegerToByteString, bool(true), int(-1), int(-5))),
      bitwise(BitwiseFailure.NegativeLength(-1)) ->
        Vector("integerToByteString: negative length argument", "Length requested: -1")
    )
    assertEquals(
      failureOf(e(IntegerToByteString, bool(true), int(8193), int(1))),
      bitwise(BitwiseFailure.LengthTooLong(8193)) -> Vector(
        "integerToByteString: requested length is too long (maximum is 8192 bytes)",
        "Length requested: 8193"
      )
    )
    assertEquals(
      failureOf(e(IntegerToByteString, bool(true), int(0), int(BigInt(1) << 65536))),
      bitwise(BitwiseFailure.InputTooLong(8193)) -> Vector(
        "integerToByteString: input too long (maximum is 2^65536-1)",
        "Length required: 8193"
      )
    )
    // `integerLog2` of a negative integer is 0, so a huge negative input is a negative input
    assertEquals(
      failureOf(e(IntegerToByteString, bool(true), int(0), int(-(BigInt(1) << 70000))))._1,
      bitwise(BitwiseFailure.NegativeInput(-(BigInt(1) << 70000)))
    )
    assertEquals(
      failureOf(e(IntegerToByteString, bool(false), int(2), int(-1))),
      bitwise(BitwiseFailure.NegativeInput(-1)) ->
        Vector("integerToByteString: cannot convert negative Integer", "Input: -1")
    )
    assertEquals(
      failureOf(e(IntegerToByteString, bool(true), int(1), int(256))),
      bitwise(BitwiseFailure.NotEnoughDigits(256, 1)) -> Vector(
        "integerToByteString: cannot represent Integer in given number of bytes",
        "Input: 256",
        "Bytes requested: 1"
      )
    )
  }

  test("byteStringToInteger: unsigned, in either byte order (Bitwise.hs:312-332)") {
    assertEquals(integerOf(e(ByteStringToInteger, bool(true), bytes("0102"))), BigInt(258))
    assertEquals(integerOf(e(ByteStringToInteger, bool(false), bytes("0102"))), BigInt(513))
    assertEquals(integerOf(e(ByteStringToInteger, bool(true), bytes(""))), BigInt(0))
    assertEquals(integerOf(e(ByteStringToInteger, bool(true), bytes("ff"))), BigInt(255))
  }

  property("integerToByteString and byteStringToInteger are inverse on the fewest bytes") {
    Prop.forAll(Gen.chooseNum(0L, Long.MaxValue), Gen.oneOf(true, false)) { (n, be) =>
      val b = bytesOf(e(IntegerToByteString, bool(be), int(0), int(n)))
      assertEquals(integerOf(e(ByteStringToInteger, bool(be), ExtendedTesting.bytes(b))), BigInt(n))
    }
  }

  test("and, or, xor: from the first byte; padding keeps the longer one's tail (406-490)") {
    def op(f: xyz.kd5ujc.scarab.uplc.DefaultFun, pad: Boolean, x: String, y: String) =
      bytesOf(e(f, bool(pad), bytes(x), bytes(y)))
    assertEquals(op(AndByteString, false, "ff00", "0f"), hex("0f"))
    assertEquals(op(AndByteString, true, "ff00", "0f"), hex("0f00"))
    assertEquals(op(OrByteString, true, "0f", "f000ab"), hex("ff00ab"))
    assertEquals(op(OrByteString, false, "0f", "f000ab"), hex("ff"))
    assertEquals(op(XorByteString, true, "ff", "0f0f"), hex("f00f"))
    assertEquals(op(XorByteString, false, "", "0f0f"), Bytes.empty)
    assertEquals(bytesOf(e(ComplementByteString, bytes("0f00"))), hex("f0ff"))
  }

  test("readBit: bit i is bit i mod 8 of byte n - 1 - i / 8; out of range fails (512-529)") {
    assert(boolOf(e(ReadBit, bytes("0001"), int(0))))
    assert(!boolOf(e(ReadBit, bytes("0001"), int(1))))
    assert(boolOf(e(ReadBit, bytes("0100"), int(8))))
    assertEquals(
      failureOf(e(ReadBit, bytes("0100"), int(16))),
      bitwise(BitwiseFailure.ReadBitOutOfBounds(16)) ->
        Vector("readBit: index out of bounds", "Index: 16")
    )
    assertEquals(
      failureOf(e(ReadBit, bytes("0100"), int(-1)))._1,
      bitwise(BitwiseFailure.ReadBitOutOfBounds(-1))
    )
  }

  test("writeBits: every index, the first out of range failing (532-571)") {
    assertEquals(bytesOf(e(WriteBits, bytes("0000"), integers(0, 9), bool(true))), hex("0201"))
    assertEquals(bytesOf(e(WriteBits, bytes("ffff"), integers(0, 15, 0), bool(false))), hex("7ffe"))
    assertEquals(
      failureOf(e(WriteBits, bytes("0000"), integers(1, 16, -1), bool(true))),
      bitwise(BitwiseFailure.WriteBitsOutOfBounds(16)) ->
        Vector("writeBits: index out of bounds", "Index: 16")
    )
  }

  test(
    "writeBits: D and E refuse more than 4096 bytes, before the indices (Builtins.hs:2199-2229)"
  ) {
    val long = bytes(Bytes.wrap(Array.fill(4097)(0.toByte)))
    for v <- Vector(CoreVariant.D, CoreVariant.E) do
      assertEquals(
        failureOf(ExtendedTesting(v, WriteBits, long, integers(), bool(true))),
        bitwise(BitwiseFailure.WriteBitsInputTooLong(4097)) ->
          Vector("writeBits: input too long (maximum is 4096 bytes)", "Length: 4097"),
        s"$v"
      )
    for v <- Vector(CoreVariant.A, CoreVariant.B, CoreVariant.C) do
      assertEquals(bytesOf(ExtendedTesting(v, WriteBits, long, integers(0), bool(true))).size, 4097)
    val most = bytes(Bytes.wrap(Array.fill(4096)(0.toByte)))
    assertEquals(bytesOf(e(WriteBits, most, integers(), bool(true))).size, 4096)
  }

  test("replicateByte: 0 to 8192 bytes; the byte is a Word8 of the unlifting (576-590)") {
    assertEquals(bytesOf(e(ReplicateByte, int(3), int(0xab))), hex("ababab"))
    assertEquals(bytesOf(e(ReplicateByte, int(0), int(0))), Bytes.empty)
    assertEquals(bytesOf(e(ReplicateByte, int(8192), int(1))).size, 8192)
    assertEquals(
      failureOf(e(ReplicateByte, int(-1), int(0))),
      bitwise(BitwiseFailure.ReplicateNegativeLength(-1)) ->
        Vector("replicateByte: negative length requested")
    )
    assertEquals(
      failureOf(e(ReplicateByte, int(8193), int(0))),
      bitwise(BitwiseFailure.ReplicateLengthTooLong(8193)) -> Vector(
        "replicateByte: requested length is too long (maximum is 8192 bytes)",
        "Length requested: 8193"
      )
    )
    e(ReplicateByte, int(1), int(256)) match
      case Outcome.Failure(BuiltinFailure.Unlifting(u), _) =>
        assertEquals(u.errorClass, ErrorClass.Operational)
      case other => fail(s"expected the unlifting's failure, got $other")
  }

  test("shiftByteString and rotateByteString: the examples of CIP-123 (596-638)") {
    def shift(b: String, n: BigInt) = bytesOf(e(ShiftByteString, bytes(b), int(n)))
    def rotate(b: String, n: BigInt) = bytesOf(e(RotateByteString, bytes(b), int(n)))
    assertEquals(shift("000f", 4), hex("00f0"))
    assertEquals(shift("000f", 16), hex("0000"))
    assertEquals(shift("000f", -4), hex("0000"))
    assertEquals(shift("f000", -4), hex("0f00"))
    assertEquals(shift("0f0f", 12), hex("f000"))
    assertEquals(shift("", 3), Bytes.empty)
    assertEquals(rotate("000f", 4), hex("00f0"))
    assertEquals(rotate("000f", -4), hex("f000"))
    assertEquals(rotate("000f", 16), hex("000f"))
    assertEquals(rotate("8001", 1), hex("0003"))
    assertEquals(rotate("8001", -1), hex("c000"))
    // A to C read the move as any integer (`IntegerCostedLiterally`)
    val huge = BigInt(1) << 70
    assertEquals(
      bytesOf(ExtendedTesting(CoreVariant.C, ShiftByteString, bytes("ff"), int(huge))),
      hex("00")
    )
    assertEquals(
      bytesOf(ExtendedTesting(CoreVariant.C, RotateByteString, bytes("0f"), int(huge + 4))),
      hex("f0")
    )
  }

  property("a rotation by n is undone by -n; a shift by n agrees with n single shifts") {
    val gen = for
      b <- Gen.listOfN(4, Gen.choose(0, 255)).map(xs => Bytes.wrap(xs.map(_.toByte).toArray))
      n <- Gen.choose(-40, 40)
    yield (b, n)
    Prop.forAll(gen) { (b, n) =>
      val there = bytesOf(e(RotateByteString, ExtendedTesting.bytes(b), int(n)))
      assertEquals(bytesOf(e(RotateByteString, ExtendedTesting.bytes(there), int(-n))), b)
      val step = if n >= 0 then 1 else -1
      val stepped = (0 until n.abs).foldLeft(b)((acc, _) =>
        bytesOf(e(ShiftByteString, ExtendedTesting.bytes(acc), int(step)))
      )
      assertEquals(bytesOf(e(ShiftByteString, ExtendedTesting.bytes(b), int(n))), stepped)
    }
  }

  test("countSetBits and findFirstSetBit (804-917)") {
    assertEquals(integerOf(e(CountSetBits, bytes("ffff"))), BigInt(16))
    assertEquals(integerOf(e(CountSetBits, bytes("0101"))), BigInt(2))
    assertEquals(integerOf(e(CountSetBits, bytes(""))), BigInt(0))
    assertEquals(integerOf(e(FindFirstSetBit, bytes("0000"))), BigInt(-1))
    assertEquals(integerOf(e(FindFirstSetBit, bytes(""))), BigInt(-1))
    assertEquals(integerOf(e(FindFirstSetBit, bytes("0002"))), BigInt(1))
    assertEquals(integerOf(e(FindFirstSetBit, bytes("8000"))), BigInt(15))
  }
