// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.io.ByteArrayInputStream
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{BinaryError, Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.NativeScript
import xyz.kd5ujc.scarab.legacy

class BinaryErrorSuite extends FunSuite:
  private def rejected[A](bytes: Array[Byte], expected: BinaryError)(using
      Codec[A],
      legacy.Codec[A]
  ): Unit =
    assertEquals(Codec.decode[A](bytes), Left(expected))
    assertEquals(legacy.Codec.decode[A](bytes), Left(expected))

  test("current and frozen primitive readers retain the rejected field and value") {
    rejected[Boolean](Array(2), BinaryError.InvalidBoolean(2))
    rejected[Option[Int]](Array(2), BinaryError.InvalidOptionTag(2))
    rejected[Int](
      Codec.encode(Int.MaxValue.toLong + 1),
      BinaryError.IntOutOfRange(Int.MaxValue.toLong + 1)
    )
    rejected[BigInt](Array(0), BinaryError.EmptyBigInt)
    rejected[Rational](
      Codec.encode(BigInt(1)) ++ Codec.encode(BigInt(0)),
      BinaryError.NonPositiveDenominator(0)
    )
    rejected[NativeScript](Array(6), BinaryError.InvalidNativeScriptTag(6))
  }

  test("current and frozen readers distinguish truncation, trailing bytes and oversized counts") {
    rejected[Boolean](Array.emptyByteArray, BinaryError.UnexpectedEnd(0))
    rejected[Long](Array(0, 0), BinaryError.TrailingBytes(1))
    rejected[Vector[Int]](Array(2), BinaryError.CountExceedsRemaining(2, 0))
    rejected[Long](Array.fill[Byte](10)(0x80.toByte) :+ 0.toByte, BinaryError.VarintTooLong(10))
  }

  test("buffered current and frozen readers cannot consume a byte beyond their declared limit") {
    Vector(0, 1, 65534, 65536, 65537).foreach { size =>
      val value = Bytes.wrap(Array.tabulate[Byte](size)(_.toByte))
      val encoded = Codec.encode(value)
      val tail = Array.fill[Byte](40)(0x5a)
      val currentSource = ByteArrayInputStream(encoded ++ tail)
      val frozenSource = ByteArrayInputStream(encoded ++ tail)
      assertEquals(Codec[Bytes].read(In(currentSource, encoded.length)), Right(value))
      assertEquals(legacy.Codec[Bytes].read(legacy.In(frozenSource, encoded.length)), Right(value))
      assertEquals(currentSource.available(), tail.length)
      assertEquals(frozenSource.available(), tail.length)
    }
  }
