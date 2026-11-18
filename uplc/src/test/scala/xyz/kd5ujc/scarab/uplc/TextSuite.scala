// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import xyz.kd5ujc.scarab.codec.Bytes

/** [[Text.fromUtf8]] decodes from the bytes' own array; it agrees with the decoding through a
  * read-only buffer it replaced, on slices that start anywhere in a larger array.
  */
class TextSuite extends ScalaCheckSuite:
  /** The decoding before: the same decoder, through a read-only view of the bytes. */
  private def previous(bytes: Bytes): Either[Text.Invalid, String] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val in = ByteBuffer.wrap(bytes.toArray).asReadOnlyBuffer()
    val out = CharBuffer.allocate(bytes.size)
    val decoded = decoder.decode(in, out, true)
    if decoded.isError || decoder.flush(out).isError then Left(Text.Invalid.InvalidUtf8)
    else Right(out.flip().toString)

  /** Mostly well-formed UTF-8 of every width, with a bad or truncated sequence now and then. */
  private val encoded: Gen[Array[Byte]] =
    val piece = Gen.frequency(
      6 -> Gen.oneOf("a", "\u0000", "é", "中", "😀").map(_.getBytes(StandardCharsets.UTF_8)),
      1 -> Gen
        .oneOf(
          Array(0xc0, 0x80),
          Array(0xed, 0xa0, 0x80),
          Array(0xf4, 0x90, 0x80, 0x80),
          Array(0xe2, 0x82),
          Array(0xff)
        )
        .map(_.map(_.toByte))
    )
    Gen
      .frequency(1 -> Gen.choose(0, 8), 1 -> Gen.choose(9, 400))
      .flatMap(n => Gen.listOfN(n, piece).map(_.toArray.flatten))

  property("fromUtf8 agrees with the read-only decoding, on a slice of a larger array") {
    Prop.forAll(encoded, Gen.choose(0, 5), Gen.choose(0, 5)) { (body, before, after) =>
      val array = Array.fill(before)(0x61.toByte) ++ body ++ Array.fill(after)(0xff.toByte)
      val slice = Bytes.wrap(array, before, body.length)
      assertEquals(Text.fromUtf8(slice).map(_.string), previous(slice))
      Text.fromUtf8(slice).foreach(t => assertEquals(t.utf8, Bytes.wrap(body)))
    }
  }
