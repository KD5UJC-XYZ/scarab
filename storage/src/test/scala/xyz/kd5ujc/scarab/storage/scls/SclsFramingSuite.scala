// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage.scls

import java.nio.file.{Files, Path}
import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.PreprodDeepTx.onSmallStack

class SclsFramingSuite extends FunSuite:
  import SclsFraming.*

  private def right[A](result: Either[Error, A]): A = result.fold(e => fail(e.toString), identity)
  private val limit = right(Limit.fromInt(16 * 1024 * 1024 + 4096))
  private val reference =
    Bytes.wrap(Files.readAllBytes(Path.of("fixtures/scls/conway-4593/reference.scls")))
  private val frames = right(decode(reference, limit))

  test("actual reference record boundaries preserve every original byte in both directions") {
    assertEquals(
      frames.map(_.kind),
      Vector(Kind.Header) ++ Vector.fill(8)(Kind.Chunk) :+ Kind.Manifest
    )
    val encoded = frames.foldLeft(Bytes.empty)((out, frame) => out ++ right(encode(frame, limit)))
    assertEquals(encoded, reference)
    assertEquals(frames.head.contents.hex, "53434c5300000001")
    assertEquals(frames.last.contents.take(8).hex, "00000000000011f1")
  }

  test("every two-chunk split and byte-at-a-time input preserve reference framing") {
    (0 to reference.size).foreach { split =>
      val (middle, first) = right(decoder(limit).feed(reference.take(split)))
      val (last, second) = right(middle.feed(reference.drop(split)))
      assertEquals(right(last.finish), ())
      assertEquals(first ++ second, frames)
    }
    val (last, result) = reference.value.foldLeft(decoder(limit) -> Vector.empty[Frame]) {
      case ((current, out), byte) =>
        val (next, emitted) = right(current.feed(Bytes.wrap(Array(byte))))
        next -> (out ++ emitted)
    }
    assertEquals(right(last.finish), ())
    assertEquals(result, frames)
  }

  test("unsigned oversized lengths and unknown tags fail before buffering their payload") {
    Vector("00000000", "80000000", "ffffffff").foreach { prefix =>
      val bytes = Bytes.fromHex(prefix).fold(e => fail(e.message), identity)
      assert(decoder(limit).feed(bytes).isLeft)
    }
    val unknown = Bytes.fromHex("00000001fe").fold(e => fail(e.message), identity)
    assertEquals(decoder(limit).feed(unknown), Left(Error.UnknownKind(4L, 0xfe)))
    assert(Limit.fromInt(0).isLeft)
    assert(Limit.fromInt(-1).isLeft)
  }

  test(
    "every interior record truncation is incomplete and an aligned prefix is only framing-valid"
  ) {
    val boundaries = frames.scanLeft(0)((offset, frame) => offset + frame.contents.size + 5).toSet
    (0 until reference.size).foreach { end =>
      val decoded = decode(reference.take(end), limit)
      assertEquals(decoded.isRight, boundaries(end), s"length $end")
    }
    val small = right(Limit.fromInt(8))
    assert(encode(frames.head, small).isLeft)
  }

  test("large record streams are stack safe, including zero-length record contents") {
    onSmallStack {
      val frame = Frame(Kind.Metadata, Bytes.empty)
      val bytes = right(encode(frame, limit))
      val input = Bytes.wrap(Array.fill(20000)(bytes.toArray).flatten)
      assertEquals(right(decode(input, limit)).size, 20000)
    }
  }

  test("large byte-at-a-time records retain bounded segments and decoder prefixes stay immutable") {
    onSmallStack {
      val payload = Bytes.wrap(Array.tabulate(256 * 1024 + 123)(_.toByte))
      val frame = Frame(Kind.Metadata, payload)
      val encoded = right(encode(frame, limit))
      val bodyStart = 5
      val (partial, emitted) = right(decoder(limit).feed(encoded.take(bodyStart)))
      assertEquals(emitted, Vector.empty[Frame])

      val (oneByteBranch, _) = right(partial.feed(encoded.slice(bodyStart, bodyStart + 1)))
      val (twoByteBranch, _) = right(partial.feed(encoded.slice(bodyStart, bodyStart + 2)))
      assertEquals(partial.retainedSegmentCount, 0)
      assertEquals(oneByteBranch.retainedSegmentCount, 1)
      assertEquals(twoByteBranch.retainedSegmentCount, 1)
      val (_, fromOne) = right(oneByteBranch.feed(encoded.drop(bodyStart + 1)))
      val (_, fromTwo) = right(twoByteBranch.feed(encoded.drop(bodyStart + 2)))
      assertEquals(fromOne, Vector(frame))
      assertEquals(fromTwo, Vector(frame))

      val (end, decoded, maximumSegments) = encoded.value.foldLeft(
        (decoder(limit), Vector.empty[Frame], 0)
      ) { case ((current, records, maximum), byte) =>
        val (next, output) = right(current.feed(Bytes.wrap(Array(byte))))
        (next, records ++ output, maximum.max(next.retainedSegmentCount))
      }
      assertEquals(right(end.finish), ())
      assertEquals(decoded, Vector(frame))
      assert(maximumSegments <= payload.size / 256 + 1)
      assertEquals(end.retainedSegmentCount, 0)
    }
  }
