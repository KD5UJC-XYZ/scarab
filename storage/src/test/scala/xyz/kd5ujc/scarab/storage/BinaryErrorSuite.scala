// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import java.io.ByteArrayInputStream
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.{BinaryError, Bytes, Rational}
import xyz.kd5ujc.scarab.ledger.NativeScript

class BinaryErrorSuite extends FunSuite:
  private def rejected[A](bytes: Array[Byte], expected: BinaryError)(using
      Codec[A]
  ): Unit =
    assertEquals(Codec.decode[A](bytes), Left(expected))

  test("current primitive readers retain the rejected field and value") {
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

  test("current readers distinguish truncation, trailing bytes and oversized counts") {
    rejected[Boolean](Array.emptyByteArray, BinaryError.UnexpectedEnd(0))
    rejected[Long](Array(0, 0), BinaryError.TrailingBytes(1))
    rejected[Vector[Int]](Array(2), BinaryError.CountExceedsRemaining(2, 0))
    rejected[Long](Array.fill[Byte](10)(0x80.toByte) :+ 0.toByte, BinaryError.VarintTooLong(10))
  }

  test("buffered current readers cannot consume a byte beyond their declared limit") {
    Vector(0, 1, 65534, 65536, 65537).foreach { size =>
      val value = Bytes.wrap(Array.tabulate[Byte](size)(_.toByte))
      val encoded = Codec.encode(value)
      val tail = Array.fill[Byte](40)(0x5a)
      val currentSource = ByteArrayInputStream(encoded ++ tail)
      assertEquals(Codec[Bytes].read(In(currentSource, encoded.length)), Right(value))
      assertEquals(currentSource.available(), tail.length)
    }
  }

  test("large declared collection counts reject the first invalid element incrementally") {
    // The synthetic stream models a large file without allocating its body. Its declared bound
    // admits the count, so only the invalid first Boolean can reject it. Eager count-sized
    // placeholder collections would attempt billions of allocations before reaching that byte.
    val prefix = Codec.encode(Int.MaxValue.toLong)
    val body = prefix :+ 2.toByte
    val limit = prefix.length.toLong + Int.MaxValue
    given cats.Order[Boolean] = cats.Order.fromLessThan((a, b) => !a && b)
    val current: Vector[Codec[?]] = Vector(
      Codec[Vector[Boolean]],
      Codec[List[Boolean]],
      Codec[Set[Boolean]],
      Codec[Map[Boolean, Int]],
      NodeCodecs.sortedMap[Boolean, Int]
    )
    current.foreach { codec =>
      val in = In(ByteArrayInputStream(body), limit)
      assertEquals(codec.read(in).left.toOption, Some(BinaryError.InvalidBoolean(2)))
      assertEquals(in.position, prefix.length.toLong + 1)
    }
  }

  test("current counts reject Int overflow before narrowing or reading elements") {
    val count = Int.MaxValue.toLong + 1
    val prefix = Codec.encode(count)
    val limit = prefix.length.toLong + count
    val current = In(ByteArrayInputStream(prefix), limit)
    assertEquals(current.count(), Left(BinaryError.IntOutOfRange(count)))
    assertEquals(current.position, prefix.length.toLong)
  }
