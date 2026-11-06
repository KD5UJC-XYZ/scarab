// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import cats.data.Chain
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import java.util.concurrent.FutureTask
import scala.annotation.tailrec

class ChunkedBytesSuite extends ScalaCheckSuite:
  import ChunkedBytes.Error

  private def right[A](result: Either[Error, A]): A = result.fold(e => fail(e.toString), identity)
  private def bytes(values: Int*): Bytes = Bytes.fromArray(values.map(_.toByte).toArray)
  private def chunked(parts: Bytes*): ChunkedBytes = right(
    ChunkedBytes.fromChunks(Chain.fromSeq(parts))
  )
  private def split(value: Vector[Byte], width: Int): ChunkedBytes =
    right(ChunkedBytes.fromChunks(Chain.fromSeq(value.grouped(width).map(Bytes.apply).toVector)))

  private val content = Gen
    .choose(0, 300)
    .flatMap(n => Gen.containerOfN[Vector, Byte](n, Gen.choose(-128, 127).map(_.toByte)))

  property("chunking preserves content, unsigned order and hash") {
    Prop.forAll(content, content, Gen.choose(1, 25), Gen.choose(1, 25)) { (a, b, aw, bw) =>
      val left = split(a, aw)
      val same = split(a, bw)
      val other = split(b, bw)
      assertEquals(left, same)
      assertEquals(left.hashCode(), same.hashCode())
      assertEquals(left.hashCode(), Bytes(a).hashCode())
      assertEquals(math.signum(left.compareTo(other)), math.signum(Bytes(a).compareTo(Bytes(b))))
      assertEquals(right(left.toBytes(a.size)), Bytes(a))
      assertEquals(left.size, a.size.toLong)
      a.indices.foreach(i => assertEquals(left.byteAt(i.toLong), Some(a(i))))
    }
  }

  property("concat identity and associativity preserve the flat model") {
    Prop.forAll(content, content, content) { (a, b, c) =>
      val x = split(a, 3)
      val y = split(b, 5)
      val z = split(c, 7)
      assertEquals(right(x.concat(ChunkedBytes.empty)), x)
      assertEquals(right(ChunkedBytes.empty.concat(x)), x)
      val left = right(right(x.concat(y)).concat(z))
      val rightAssociated = right(x.concat(right(y.concat(z))))
      assertEquals(left, rightAssociated)
      assertEquals(right(left.toBytes(a.size + b.size + c.size)), Bytes(a ++ b ++ c))
    }
  }

  property("checked slices and cursors agree with the contiguous model") {
    Prop.forAll(content, Gen.choose(1, 13), Gen.choose(0, 300), Gen.choose(0, 300)) {
      (a, width, x, y) =>
        val source = split(a, width)
        val from = math.min(math.min(x, y), a.size)
        val until = math.min(math.max(x, y), a.size)
        val sliced = right(source.slice(from.toLong, until.toLong))
        assertEquals(right(sliced.toBytes(until - from)), Bytes(a.slice(from, until)))
        val cursor = right(source.cursorAt(from.toLong))
        assertEquals(cursor.position, from.toLong)
        assertEquals(cursor.remaining, (a.size - from).toLong)
        assertEquals(cursor.readByte.map(_._1), a.lift(from))
        assertEquals(right(cursor.skip((until - from).toLong)).position, until.toLong)
    }
  }

  test("empty chunks are removed and cursor reads always advance without changing their source") {
    val first = bytes(1, 2)
    val source = chunked(Bytes.empty, first, Bytes.empty, bytes(3, 255), Bytes.empty)
    assertEquals(source.chunks.toList, List(first, bytes(3, 255)))
    val initial = source.cursor
    val (a, afterA) = right(initial.takeChunk(1)).getOrElse(fail("missing first chunk"))
    val (b, afterB) = right(afterA.takeChunk(10)).getOrElse(fail("missing second chunk"))
    val (c, done) = right(afterB.takeChunk(10)).getOrElse(fail("missing third chunk"))
    assertEquals(List(a, b, c), List(bytes(1), bytes(2), bytes(3, 255)))
    assertEquals(
      List(initial.position, afterA.position, afterB.position, done.position),
      List(0L, 1L, 2L, 4L)
    )
    assertEquals(initial.remaining, 4L)
    assertEquals(initial.readByte.map(_._1), Some(1.toByte))
    assertEquals(done.remaining, 0L)
    assertEquals(done.readByte, None)
    assertEquals(right(done.takeChunk(1)), None)
    assert(right(source.cursorAt(source.size)).isEmpty)
    assertEquals(right(chunked(Bytes.empty, Bytes.empty).toBytes(0)), Bytes.empty)
  }

  test("ranges, advances and explicit contiguous bounds reject invalid values") {
    val source = chunked(bytes(1, 2, 3))
    assertEquals(source.cursorAt(-1), Left(Error.InvalidOffset(-1, 3)))
    assertEquals(source.cursorAt(Long.MaxValue), Left(Error.InvalidOffset(Long.MaxValue, 3)))
    assertEquals(source.slice(-1, 1), Left(Error.InvalidRange(-1, 1, 3)))
    assertEquals(source.slice(2, 1), Left(Error.InvalidRange(2, 1, 3)))
    assertEquals(source.slice(0, Long.MaxValue), Left(Error.InvalidRange(0, Long.MaxValue, 3)))
    assertEquals(source.cursor.skip(-1), Left(Error.InvalidAdvance(-1, 3)))
    assertEquals(source.cursor.skip(Long.MaxValue), Left(Error.InvalidAdvance(Long.MaxValue, 3)))
    assertEquals(source.cursor.takeChunk(0), Left(Error.InvalidChunkSize(0)))
    assertEquals(source.cursor.takeChunk(-1), Left(Error.InvalidChunkSize(-1)))
    assertEquals(source.toBytes(-1), Left(Error.InvalidContiguousLimit(-1)))
    assertEquals(source.toBytes(2), Left(Error.ContiguousLimitExceeded(3, 2)))
    assertEquals(source.byteAt(-1), None)
    assertEquals(source.byteAt(source.size), None)
    assertEquals(right(source.take(2)), chunked(bytes(1, 2)))
    assertEquals(right(source.drop(2)), chunked(bytes(3)))
    assertEquals(right(source.toBytes(3)), bytes(1, 2, 3))
  }

  test(
    "single-chunk conversion and slicing share immutable bytes, public array inputs are copied"
  ) {
    val original = Array[Byte](9, 1, 2, 3, 9)
    val copied = Bytes.fromArray(original)
    val source = ChunkedBytes.fromBytes(copied.slice(1, 4))
    original(2) = 99
    val same = right(source.toBytes(3))
    assertEquals(same, bytes(1, 2, 3))
    assert(same.unsafeArray eq copied.unsafeArray)
    val inner = right(right(source.slice(1, 3)).toBytes(2))
    assertEquals(inner, bytes(2, 3))
    assert(inner.unsafeArray eq copied.unsafeArray)
  }

  test("global byte offsets cross 2 GiB without allocating a giant backing array") {
    val chunk = Bytes.fromArray(Array.tabulate[Byte](65536)(i => (i % 251).toByte))
    val source = right(ChunkedBytes.fromChunks(Chain.fromSeq(Vector.fill(32769)(chunk))))
    assertEquals(source.size, 2147549184L)
    val start = Int.MaxValue.toLong - 2
    val end = start + 9
    val expected =
      Bytes.fromArray(Array.tabulate[Byte](9)(i => (((start + i) % 65536) % 251).toByte))
    assertEquals(right(right(source.slice(start, end)).toBytes(9)), expected)
    assertEquals(source.byteAt(Int.MaxValue.toLong + 3), Some(2.toByte))
    val cursor = right(source.cursorAt(start))
    val (part, advanced) = right(cursor.takeChunk(9)).getOrElse(fail("missing wide-offset chunk"))
    assertEquals(part.size, 3)
    assertEquals(advanced.position, 2147483648L)
    assertEquals(advanced.readByte.map(_._1), Some(0.toByte))
    assertEquals(
      source.toBytes(Int.MaxValue),
      Left(Error.ContiguousLimitExceeded(source.size, Int.MaxValue))
    )
  }

  test("concat checks Long overflow without expanding shared content") {
    val one = ChunkedBytes.fromBytes(bytes(7))
    val powers = (1 to 62).foldLeft(Vector(one)) { (all, _) =>
      val previous = all.lastOption.getOrElse(fail("missing previous power"))
      all :+ right(previous.concat(previous))
    }
    val high = powers.lastOption.getOrElse(fail("missing largest power"))
    assertEquals(high.size, 1L << 62)
    assertEquals(high.concat(high), Left(Error.LengthOverflow(1L << 62, 1L << 62)))
    val maximum = powers.foldLeft(ChunkedBytes.empty)((sum, part) => right(sum.concat(part)))
    assertEquals(maximum.size, Long.MaxValue)
    assertEquals(maximum.concat(one), Left(Error.LengthOverflow(Long.MaxValue, 1)))
  }

  private def onSmallStack[A](body: => A): A =
    val result = new FutureTask[A](() => body)
    val worker = new Thread(null, result, "chunked-bytes-small-stack", 256 * 1024)
    worker.start()
    try result.get()
    finally worker.join()

  test("partial reads retain a normalized chunk before a deep concatenation tail") {
    val result = onSmallStack {
      val first = Bytes.fromArray(Array.tabulate[Byte](65536)(i => (i % 251).toByte))
      val unit = bytes(128)
      val source = (0 until 100000).foldLeft(ChunkedBytes.fromBytes(first)) { (acc, _) =>
        right(acc.concat(ChunkedBytes.fromBytes(unit)))
      }
      @tailrec def read(at: ChunkedBytes.Cursor, left: Int, hash: Int): (Int, Long) =
        if left == 0 then (hash, at.position)
        else
          at.readByte match
            case None               => fail("cursor exhausted inside the large first chunk")
            case Some((byte, next)) => read(next, left - 1, 31 * hash + byte)
      val checksum = read(source.cursor, first.size, 1)
      val firstAsSingles = Chain.fromSeq(first.value.map(byte => Bytes.fromArray(Array(byte))))
      val other = right(
        ChunkedBytes.fromChunks(
          firstAsSingles ++ Chain.one(Bytes.fromArray(Array.fill[Byte](100000)(-128)))
        )
      )
      (checksum, source.compareTo(other))
    }
    val first = Bytes.fromArray(Array.tabulate[Byte](65536)(i => (i % 251).toByte))
    assertEquals(result, ((first.foldLeft(1)((h, byte) => 31 * h + byte), 65536L), 0))
  }

  test("deep concatenation, traversal, equality, hash and slicing are stack safe") {
    val result = onSmallStack {
      val unit = bytes(128)
      val source = (0 until 100000).foldLeft(ChunkedBytes.empty)((acc, _) =>
        right(acc.concat(ChunkedBytes.fromBytes(unit)))
      )
      val grouped = ChunkedBytes.fromBytes(Bytes.fromArray(Array.fill[Byte](100000)(-128)))
      @tailrec def consume(at: ChunkedBytes.Cursor, count: Long): Long =
        at.readByte match
          case None            => count
          case Some((_, next)) => consume(next, count + 1)
      val same = source == grouped
      val hash = source.hashCode() == grouped.hashCode()
      val sliced = right(right(source.slice(99998, 100000)).toBytes(2))
      val end = right(source.cursor.skip(source.size)).position
      (same, hash, consume(source.cursor, 0L), sliced, end)
    }
    assertEquals(result, (true, true, 100000L, bytes(128, 128), 100000L))
  }
