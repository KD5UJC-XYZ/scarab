// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.crypto.secret

import java.lang.foreign.{Arena, MemorySegment, ValueLayout}
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.{MalformedInputException, StandardCharsets}
import java.nio.file.{Path, StandardOpenOption}
import scala.annotation.tailrec
import scala.util.Using
import scala.util.Using.Releasable

/** The cardano-cli text envelope of a signing key, `{"type": ..., "description": ..., "cborHex":
  * ...}` whose `cborHex` is the hex of the CBOR byte string of the key, read and written as bytes
  * in storage that is zeroed: the file's bytes and the decoded key in a [[Scratch]], and the bytes
  * that pass between the file and the heap in native memory that is zeroed before it is freed. A
  * read or a write through the JDK from or to a heap array goes through a temporary direct buffer
  * that the JDK keeps for the thread and does not clear, so the key would stay in it until the
  * thread did other I/O.
  *
  * A read takes the envelope in place when it is flat (an object of string members without escapes
  * or control characters, `type` and `cborHex` once each, the form cardano-cli and this node write)
  * and its `cborHex` is the canonical CBOR byte string of a key of the expected size: no `String`
  * holds the hex, and no array but the scratch's holds the key. Anything else is handed back as
  * text ([[Read.Other]]), for the text reader to say what it is, as it said before: the envelope's
  * text is then a `String` that nothing erases.
  */
private[scarab] object KeyText:
  /** Where the bytes of an envelope come from: they are put in a buffer of the scratch given. */
  type Source = (s: Scratch) => s.Buffer

  /** What a read found. */
  enum Read[+F, +K]:
    /** The bytes could not be read, or are not UTF-8, as `Files.readString` fails on them. */
    case Unreadable(cause: Throwable)

    /** A flat envelope of the expected type whose CBOR is a byte string of the expected size: what
      * the key's constructor made of those bytes.
      */
    case Payload(made: Either[F, K])

    /** A flat envelope whose hex is well formed, of another type. */
    case WrongType(found: String)

    /** Not such an envelope: its text, for the text reader. */
    case Other(text: String)

  /** The bytes of `file`, read as `Files.readString` reads them, with its failures: the file cannot
    * be opened or read, or it is not UTF-8 (`MalformedInputException`).
    */
  def file(path: Path): Source = s => readInto(s, path)

  /** Read the envelope of `source`: when it is flat, of type `expected`, and its CBOR the byte
    * string of `payloadBytes` bytes, `make` is given an array of those bytes, valid for the call
    * only, which the scratch zeroes once the read ends; `make` copies what it keeps.
    */
  def read[F: Public, K: Public](source: Source, expected: String, payloadBytes: Int)(
      make: Array[Byte] => Either[F, K]
  ): Read[F, K] =
    Scratch.within(s => readIn(s, source, expected, payloadBytes)(make))

  /** [[read]] in the scratch `s`. */
  private[scarab] def readIn[F, K](s: Scratch, source: Source, expected: String, payloadBytes: Int)(
      make: Array[Byte] => Either[F, K]
  ): Read[F, K] =
    scala.util
      .Try(source(s))
      .toEither
      .flatMap(utf8(s))
      .fold(Read.Unreadable(_), parsed(s, expected, payloadBytes, make))

  /** The text of an envelope of type `typ` and description `description` around the CBOR byte
    * string of the `payloadBytes` bytes `payload` puts in a scratch, laid out as circe's `spaces2`
    * prints it, handed to `use` in native memory that is zeroed and freed when `use` returns or
    * throws (a buffer kept past that refuses every access). `typ` and `description` are this node's
    * own constants: printable ASCII that JSON does not escape.
    */
  def written[A](typ: String, description: String, payloadBytes: Int, payload: Source)(
      use: ByteBuffer => A
  ): A =
    Using.resource(Arena.ofConfined())(writtenIn(_, typ, description, payloadBytes, payload)(use))

  /** [[written]] with the native memory allocated in `arena`, which a suite can keep open to see it
    * zeroed.
    */
  private[scarab] def writtenIn[A](
      arena: Arena,
      typ: String,
      description: String,
      payloadBytes: Int,
      payload: Source
  )(use: ByteBuffer => A): A =
    val head = ascii(
      s"{\n  \"type\" : \"$typ\",\n  \"description\" : \"$description\",\n  \"cborHex\" : \""
    )
    val tail = ascii("\"\n}")
    val header = headerOf(payloadBytes)
    val size = head.length + 2 * (header.length + payloadBytes) + tail.length
    nativeIn(arena, size.toLong) { segment =>
      Scratch.within { s =>
        val key = payload(s)
        written(s.size(key), payloadBytes)
        val at = head.length + 2 * header.length
        MemorySegment.copy(head, 0, segment, ValueLayout.JAVA_BYTE, 0L, head.length)
        header.indices.foreach(i => hexInto(segment, head.length + 2 * i, header(i)))
        (0 until s.size(key)).foreach(i => hexInto(segment, at + 2 * i, s.array(key)(i)))
        MemorySegment.copy(
          tail,
          0,
          segment,
          ValueLayout.JAVA_BYTE,
          (at + 2 * payloadBytes).toLong,
          tail.length
        )
      }
      use(segment.asByteBuffer())
    }

  /** A payload of another size than the caller said is a defect of the caller. */
  @SuppressWarnings(Array("org.wartremover.warts.Throw"))
  private def written(size: Int, expected: Int): Unit =
    if size != expected then
      throw new IllegalArgumentException(s"a payload of $size bytes, not $expected")

  // ---- reading

  private def parsed[F, K](
      s: Scratch,
      expected: String,
      payloadBytes: Int,
      make: Array[Byte] => Either[F, K]
  )(
      text: s.Buffer
  ): Read[F, K] =
    val t = s.array(text)
    flat(t).fold(Read.Other(new String(t, StandardCharsets.UTF_8))) { fields =>
      val hexLength = fields.hexEnd - fields.hexAt
      val header = headerOf(payloadBytes)
      if !(fields.hexAt until fields.hexEnd).forall(i => isHex(t(i))) || hexLength % 2 != 0 then
        Read.Other(new String(t, StandardCharsets.UTF_8))
      else if !sameBytes(t, fields.typeAt, fields.typeEnd, ascii(expected)) then
        Read.WrongType(
          new String(t, fields.typeAt, fields.typeEnd - fields.typeAt, StandardCharsets.UTF_8)
        )
      else if hexLength != 2 * (header.length + payloadBytes) ||
        !header.indices.forall(i => decoded(t, fields.hexAt + 2 * i) == header(i))
      then Read.Other(new String(t, StandardCharsets.UTF_8))
      else
        val key = s.buffer(payloadBytes)
        val from = fields.hexAt + 2 * header.length
        (0 until payloadBytes).foreach(i => s.array(key)(i) = decoded(t, from + 2 * i))
        Read.Payload(make(s.array(key)))
    }

  /** Where the `type` and `cborHex` strings of a flat envelope lie in `t`, the index of each first
    * character and of each closing quote: a JSON object, between JSON whitespace, whose every
    * member is a string and has a key that is a string, without escapes or control characters,
    * `type` and `cborHex` among them once each. Every such text is a JSON object circe reads with
    * those two strings; any other is none.
    */
  private final case class Fields(typeAt: Int, typeEnd: Int, hexAt: Int, hexEnd: Int)

  private val TypeKey = ascii("type")
  private val HexKey = ascii("cborHex")

  private def flat(t: Array[Byte]): Option[Fields] =
    val open = space(t, 0)
    Option
      .when(open < t.length && t(open) == '{')(space(t, open + 1))
      .flatMap(members(t, _, None, None))

  @tailrec private def members(
      t: Array[Byte],
      at: Int,
      typ: Option[(Int, Int)],
      hex: Option[(Int, Int)]
  ): Option[Fields] =
    val member =
      for
        key <- string(t, at)
        colon = space(t, key._2 + 1)
        if colon < t.length && t(colon) == ':'
        value <- string(t, space(t, colon + 1))
      yield (key, value)
    member match
      case None => None
      case Some((key, value)) =>
        val isType = sameBytes(t, key._1, key._2, TypeKey)
        val isHex = sameBytes(t, key._1, key._2, HexKey)
        if (isType && typ.isDefined) || (isHex && hex.isDefined) then None
        else
          val nextType = if isType then Some(value) else typ
          val nextHex = if isHex then Some(value) else hex
          val after = space(t, value._2 + 1)
          if after < t.length && t(after) == ',' then
            members(t, space(t, after + 1), nextType, nextHex)
          else if after < t.length && t(after) == '}' && space(t, after + 1) == t.length then
            for
              (ta, te) <- nextType
              (ha, he) <- nextHex
            yield Fields(ta, te, ha, he)
          else None

  /** The first character and the closing quote of the string that opens at `at`, if it is one
    * without escapes or control characters.
    */
  private def string(t: Array[Byte], at: Int): Option[(Int, Int)] =
    @tailrec def close(i: Int): Option[Int] =
      if i >= t.length || t(i) == '\\' || (t(i) & 0xff) < 0x20 then None
      else if t(i) == '"' then Some(i)
      else close(i + 1)
    Option
      .when(at < t.length && t(at) == '"')(at + 1)
      .flatMap(start => close(start).map((start, _)))

  /** The index of the first byte from `at` that is not JSON whitespace. */
  @tailrec private def space(t: Array[Byte], at: Int): Int =
    if at < t.length && (t(at) == ' ' || t(at) == '\t' || t(at) == '\n' || t(at) == '\r') then
      space(t, at + 1)
    else at

  private def sameBytes(t: Array[Byte], from: Int, until: Int, expected: Array[Byte]): Boolean =
    until - from == expected.length && expected.indices.forall(i => t(from + i) == expected(i))

  private def isHex(b: Byte): Boolean =
    (b >= '0' && b <= '9') || (b >= 'a' && b <= 'f') || (b >= 'A' && b <= 'F')

  private def nibble(b: Byte): Int = Character.digit(b.toInt, 16)

  /** The byte of the two hex digits at `t(at)`. */
  private def decoded(t: Array[Byte], at: Int): Byte =
    ((nibble(t(at)) << 4) | nibble(t(at + 1))).toByte

  /** The head of a definite CBOR byte string of `n` bytes, in its shortest form. */
  private def headerOf(n: Int): Array[Byte] =
    if n < 24 then Array((0x40 + n).toByte)
    else if n < 0x100 then Array(0x58.toByte, n.toByte)
    else if n < 0x10000 then Array(0x59.toByte, (n >> 8).toByte, n.toByte)
    else Array(0x5a.toByte, (n >> 24).toByte, (n >> 16).toByte, (n >> 8).toByte, n.toByte)

  private def ascii(text: String): Array[Byte] = text.getBytes(StandardCharsets.US_ASCII)

  private val Digits: Array[Byte] = ascii("0123456789abcdef")

  /** The two lower-case hex digits of `b`, at `segment(at)`. */
  private def hexInto(segment: MemorySegment, at: Int, b: Byte): Unit =
    segment.set(ValueLayout.JAVA_BYTE, at.toLong, Digits((b >> 4) & 0xf))
    segment.set(ValueLayout.JAVA_BYTE, at.toLong + 1, Digits(b & 0xf))

  // ---- UTF-8, as `Files.readString` decodes it

  /** `text`, if it is UTF-8; otherwise the failure `Files.readString` has on those bytes, the
    * `MalformedInputException` of the length its decoder gives the first malformed sequence. That
    * decoder (`String.decodeUTF8_UTF16` in the JDK) reports a sequence by its kind: one byte for a
    * byte that starts none, for a two-byte sequence and for one cut short; three for a three-byte
    * one that is malformed, overlong or a surrogate, two for one cut short after a byte that
    * already makes it so; four for a four-byte one, one when cut short.
    */
  private def utf8(s: Scratch)(text: s.Buffer): Either[Throwable, s.Buffer] =
    malformed(s.array(text), 0).map(new MalformedInputException(_)).toLeft(text)

  private def continuation(b: Int): Boolean = (b & 0xc0) == 0x80

  @tailrec private def malformed(t: Array[Byte], at: Int): Option[Int] =
    def b(i: Int): Int = t(i) & 0xff
    val left = t.length - at
    if left <= 0 then None
    else
      val b1 = b(at)
      if b1 < 0x80 then malformed(t, at + 1)
      else if b1 >= 0xc2 && b1 <= 0xdf then
        if left >= 2 && continuation(b(at + 1)) then malformed(t, at + 2) else Some(1)
      else if b1 >= 0xe0 && b1 <= 0xef then
        if left >= 3 then
          val (b2, b3) = (b(at + 1), b(at + 2))
          val c = ((b1 & 0x0f) << 12) | ((b2 & 0x3f) << 6) | (b3 & 0x3f)
          if (b1 == 0xe0 && (b2 & 0xe0) == 0x80) || !continuation(b2) || !continuation(b3) ||
            (c >= 0xd800 && c <= 0xdfff)
          then Some(3)
          else malformed(t, at + 3)
        else if left == 2 && ((b1 == 0xe0 && (b(at + 1) & 0xe0) == 0x80) || !continuation(
            b(at + 1)
          ))
        then Some(2)
        else Some(1)
      else if b1 >= 0xf0 && b1 <= 0xf7 then
        if left >= 4 then
          val (b2, b3, b4) = (b(at + 1), b(at + 2), b(at + 3))
          val c = ((b1 & 0x07) << 18) | ((b2 & 0x3f) << 12) | ((b3 & 0x3f) << 6) | (b4 & 0x3f)
          if !continuation(b2) || !continuation(b3) || !continuation(
              b4
            ) || c < 0x10000 || c > 0x10ffff
          then Some(4)
          else malformed(t, at + 4)
        else Some(1)
      else Some(1)

  // ---- native memory

  /** Native memory, zeroed when it is released, before its arena frees it. */
  private final class Wiped(val segment: MemorySegment)
  private given Releasable[Wiped] = _.segment.fill(0.toByte)

  /** `use` with `size` bytes of native memory of its own, zeroed and freed when it ends. */
  private def native[A](size: Long)(use: MemorySegment => A): A =
    Using.resource(Arena.ofConfined())(nativeIn(_, size)(use))

  /** `use` with `size` bytes of native memory allocated in `arena`, zeroed when it ends. */
  private[secret] def nativeIn[A](arena: Arena, size: Long)(use: MemorySegment => A): A =
    Using.resource(new Wiped(arena.allocate(math.max(1L, size))))(w => use(w.segment))

  private def readInto(s: Scratch, path: Path): s.Buffer =
    Using.resource(FileChannel.open(path, StandardOpenOption.READ))(channel =>
      filled(s, channel, Math.toIntExact(channel.size()))
    )

  /** The rest of `channel`, read into native memory of one byte more than `capacity` and copied
    * into a buffer of `s`; a file that grew past it is read again from its start with twice the
    * room.
    */
  private def filled(s: Scratch, channel: FileChannel, capacity: Int): s.Buffer =
    native(capacity.toLong + 1) { segment =>
      val into = segment.asByteBuffer()
      @tailrec def fill(): Unit = if into.hasRemaining && channel.read(into) >= 0 then fill()
      fill()
      val n = into.position()
      if n > capacity then
        channel.position(0L)
        filled(s, channel, Math.multiplyExact(capacity + 1, 2))
      else
        val bytes = s.buffer(n)
        MemorySegment.copy(segment, ValueLayout.JAVA_BYTE, 0L, s.array(bytes), 0, n)
        bytes
    }
