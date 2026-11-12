// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.uplc

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes

/** The value of a `string` constant: the reference's `Text` (`DefaultUniString`,
  * `PlutusCore/Default/Universe.hs:139`, plutus 1.70.0.0 `507cab8c`), a sequence of Unicode scalar
  * values. A surrogate code point is not a scalar value: `Data.Text.pack` replaces each one with
  * U+FFFD, which is how the reference's text parser makes a string from separate surrogate escapes
  * (`docs/plutus-conformance.md`, `patches/scalus/README.md`).
  *
  * Both lengths the costing reads are kept with the value, computed once: [[codePoints]] is
  * `T.length`, the measure of `ExMemoryUsage Text` (variants A to C), and [[utf8Length]] the byte
  * length of the UTF-8 encoding that `TextCostedByByteLength` reads (variants D and E,
  * `PlutusCore/Evaluation/Machine/ExMemoryUsage.hs:326-341`).
  *
  * @param string
  *   the scalar values as a well-formed UTF-16 string (no unpaired surrogate)
  */
final class Text private (val string: String, val codePoints: Int, val utf8Length: Int):

  /** The UTF-8 encoding (`encodeUtf8`). */
  def utf8: Bytes = Bytes.wrap(string.getBytes(StandardCharsets.UTF_8))

  override def equals(that: Any): Boolean = that match
    case o: Text => string == o.string
    case _       => false
  override def hashCode(): Int = string.hashCode
  override def toString: String = s"Text(${xyz.kd5ujc.scarab.codec.Tree.shown(string)})"

object Text:
  val empty: Text = new Text("", 0, 0)

  /** The replacement character, U+FFFD. */
  val Replacement: Int = 0xfffd

  /** Why a sequence of code points or bytes is not text. */
  enum Invalid:
    /** A code point outside `[0, 0x10FFFF]`; a Haskell `Char` cannot hold it. */
    case CodePointOutOfRange(position: Int, codePoint: Int)

    /** Bytes that are not well-formed UTF-8 (overlong, surrogate, beyond U+10FFFF or truncated). */
    case InvalidUtf8

  /** The text of `codePoints`, each surrogate (`0xD800` to `0xDFFF`) replaced with U+FFFD as
    * `Data.Text.pack` does.
    */
  def fromCodePoints(codePoints: IterableOnce[Int]): Either[Invalid, Text] =
    val builder = new java.lang.StringBuilder
    @tailrec def go(it: Iterator[Int], position: Int): Either[Invalid, Text] =
      if !it.hasNext then Right(fromWellFormed(builder.toString))
      else
        val c = it.next()
        if c < 0 || c > Character.MAX_CODE_POINT then Left(Invalid.CodePointOutOfRange(position, c))
        else
          builder.appendCodePoint(if c >= 0xd800 && c <= 0xdfff then Replacement else c)
          go(it, position + 1)
    go(codePoints.iterator, 0)

  /** The text of a JVM string: a surrogate pair is the scalar value it encodes, and an unpaired
    * surrogate becomes U+FFFD.
    */
  def fromString(s: String): Text =
    val builder = new java.lang.StringBuilder(s.length)
    s.codePoints()
      .forEach(c => builder.appendCodePoint(if c >= 0xd800 && c <= 0xdfff then Replacement else c))
    fromWellFormed(builder.toString)

  /** The text that `bytes` encode as UTF-8, refused unless they are well-formed UTF-8 (the decoding
    * of `decodeUtf8'` in the reference). Not a replacement decoder: one bad sequence refuses all.
    */
  def fromUtf8(bytes: Bytes): Either[Invalid, Text] =
    val decoder = StandardCharsets.UTF_8
      .newDecoder()
      .onMalformedInput(CodingErrorAction.REPORT)
      .onUnmappableCharacter(CodingErrorAction.REPORT)
    val in = bytes.withRange((array, offset, size) =>
      ByteBuffer.wrap(array, offset, size).asReadOnlyBuffer()
    )
    val out = CharBuffer.allocate(bytes.size)
    val decoded = decoder.decode(in, out, true)
    if decoded.isError || decoder.flush(out).isError then Left(Invalid.InvalidUtf8)
    else Right(fromWellFormed(out.flip().toString))

  private def fromWellFormed(s: String): Text =
    @tailrec def go(i: Int, codePoints: Int, bytes: Int): Text =
      if i >= s.length then new Text(s, codePoints, bytes)
      else
        val c = s.codePointAt(i)
        val width = if c < 0x80 then 1 else if c < 0x800 then 2 else if c < 0x10000 then 3 else 4
        go(i + Character.charCount(c), codePoints + 1, bytes + width)
    go(0, 0, 0)
