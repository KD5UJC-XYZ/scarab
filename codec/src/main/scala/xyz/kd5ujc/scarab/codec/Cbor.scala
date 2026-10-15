// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal

/** Small byte-preserving CBOR subset. Floats, undefined and unassigned simple values are
  * deliberately unsupported. Duplicate map keys and map order are retained.
  */
object Cbor:
  final case class Limits(
      maxInputBytes: Int = 1048576,
      maxDepth: Int = 2048,
      maxItems: Int = 100000,
      maxStringBytes: Int = 1048576
  )
  private val MaxUInt = (BigInt(1) << 64) - 1
  private final case class Failure(message: String) extends RuntimeException(message)
  private def fail(message: String): Nothing = throw Failure(message)

  /** The message of the error for an item nested deeper than the thread's stack allows. */
  val TooDeep = "nesting exceeds the stack"

  /** Run a recursive function over decoded items; a nesting that exhausts the stack (an item of
    * `Limits.maxDepth` levels needs far more stack than a typical thread has) is an error value,
    * not a `StackOverflowError`.
    */
  def stackSafe[A](body: => Either[String, A]): Either[String, A] =
    try body
    catch case _: StackOverflowError => Left(TooDeep)

  def decode(bytes: Bytes, limits: Limits = Limits()): Either[String, Node] =
    if limits.maxInputBytes < 0 || limits.maxDepth < 0 || limits.maxItems < 0 || limits.maxStringBytes < 0
    then Left("limits must be nonnegative")
    else if bytes.size > limits.maxInputBytes then Left("input byte limit exceeded")
    else
      try Right(new Decoder(bytes, limits).run())
      catch
        case Failure(message)      => Left(message)
        case _: StackOverflowError => Left(TooDeep)

  private final class Decoder(input: Bytes, limits: Limits):
    // Read-only view of the input: nodes keep zero-copy windows onto it as their `original`.
    private val data = input.unsafeArray
    private val base = input.unsafeOffset
    private val end = base + input.size
    private var pos = base
    private var items = 0
    private def read(): Int =
      if pos >= end then fail("unexpected end of input")
      val result = data(pos) & 0xff
      pos += 1
      result
    private def isBreak: Boolean = pos < end && (data(pos) & 0xff) == 255
    private def argument(ai: Int): BigInt =
      if ai < 24 then BigInt(ai)
      else
        val count = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => fail("reserved additional information or invalid indefinite item")
        if count < 8 then
          var n = 0L
          var i = 0
          while i < count do
            n = (n << 8) | read().toLong
            i += 1
          BigInt(n)
        else
          var n = BigInt(0)
          var i = 0
          while i < count do
            n = (n << 8) | BigInt(read())
            i += 1
          n
    private def bounded(n: BigInt, bound: Int, what: String): Int =
      if n > BigInt(bound) then fail(s"$what limit exceeded")
      n.toInt
    private def payload(size: Int): Array[Byte] =
      if size > end - pos then fail("truncated string payload")
      val result = java.util.Arrays.copyOfRange(data, pos, pos + size)
      pos += size
      result
    private def utf8(bytes: Array[Byte]): String =
      try
        StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes))
          .toString
      catch case NonFatal(_) => fail("invalid UTF-8 text")
    private def consumeItem(): Unit =
      if items >= limits.maxItems then fail("item limit exceeded")
      items += 1
    private def string(major: Int, ai: Int): Value =
      if ai != 31 then
        val raw = payload(bounded(argument(ai), limits.maxStringBytes, "string byte"))
        if major == 2 then Value.ByteString(Bytes.wrap(raw)) else Value.Text(utf8(raw))
      else
        val chunks = new java.io.ByteArrayOutputStream()
        val text = new java.lang.StringBuilder
        var total = 0
        while !isBreak do
          consumeItem()
          val h = read()
          if h / 32 != major || (h & 31) == 31 then
            fail("indefinite strings require definite chunks of the same type")
          val length = bounded(argument(h & 31), limits.maxStringBytes - total, "string byte")
          val raw = payload(length)
          total += length
          if major == 2 then chunks.write(raw, 0, raw.length) else text.append(utf8(raw))
        read()
        if major == 2 then Value.ByteString(Bytes.wrap(chunks.toByteArray))
        else Value.Text(text.toString)
    private def node(depth: Int): Node =
      if depth > limits.maxDepth then fail("depth limit exceeded")
      consumeItem()
      val start = pos
      val h = read()
      val major = h / 32
      val ai = h & 31
      val value: Value = major match
        case 0     => Value.UInt(argument(ai))
        case 1     => Value.NInt(-1 - argument(ai))
        case 2 | 3 => string(major, ai)
        case 4 =>
          val out = Vector.newBuilder[Node]
          if ai == 31 then
            while !isBreak do out += node(depth + 1)
            read()
          else
            val size = bounded(argument(ai), limits.maxItems - items, "item")
            var i = 0
            while i < size do
              out += node(depth + 1)
              i += 1
          Value.Arr(out.result())
        case 5 =>
          val out = Vector.newBuilder[(Node, Node)]
          if ai == 31 then
            while !isBreak do
              val key = node(depth + 1)
              if isBreak then fail("map has an unmatched key")
              out += ((key, node(depth + 1)))
            read()
          else
            val size = bounded(argument(ai), (limits.maxItems - items) / 2, "map pair")
            var i = 0
            while i < size do
              val key = node(depth + 1)
              out += ((key, node(depth + 1)))
              i += 1
          Value.Map(out.result())
        case 6 => Value.Tag(argument(ai), node(depth + 1))
        case 7 =>
          ai match
            case 20 => Value.Bool(false)
            case 21 => Value.Bool(true)
            case 22 => Value.Null
            case _  => fail("unsupported simple value, float, or unexpected break")
      Node(value, Bytes.wrap(data, start, pos - start))
    def run(): Node =
      val result = node(0)
      if pos != end then fail("trailing bytes after CBOR item")
      result

  /** Uses shortest integer/length widths and definite containers, recursively re-encoding values
    * (not their original bytes). Map order is retained, so this is NOT RFC 8949 deterministic map
    * ordering. For hashing a decoded wire item, use Node.original, never a re-encoding. Encoder
    * uses default resource limits.
    */
  def encode(value: Value): Either[String, Bytes] =
    try
      val out = new java.io.ByteArrayOutputStream()
      val limits = Limits()
      var items = 0
      def write(b: Int): Unit =
        if out.size() >= limits.maxInputBytes then fail("output byte limit exceeded")
        out.write(b)
      def head(major: Int, n: BigInt): Unit =
        if n < 0 || n > MaxUInt then fail("CBOR argument outside uint64 range")
        if n < 24 then write((major << 5) | n.toInt)
        else
          val (ai, width) =
            if n <= 255 then (24, 1)
            else if n <= 65535 then (25, 2)
            else if n <= BigInt("ffffffff", 16) then (26, 4)
            else (27, 8)
          write((major << 5) | ai)
          var shift = (width - 1) * 8
          while shift >= 0 do
            write(((n >> shift) & 255).toInt)
            shift -= 8
      def raw(major: Int, bytes: Array[Byte]): Unit =
        if bytes.length > limits.maxStringBytes then fail("string byte limit exceeded")
        head(major, BigInt(bytes.length))
        bytes.foreach(b => write(b & 255))
      def go(v: Value, depth: Int): Unit =
        if depth > limits.maxDepth then fail("depth limit exceeded")
        if items >= limits.maxItems then fail("item limit exceeded")
        items += 1
        v match
          case Value.UInt(n) => head(0, n)
          case Value.NInt(n) =>
            if n >= 0 then fail("NInt must be negative")
            head(1, -1 - n)
          case Value.ByteString(b) => raw(2, b.toArray)
          case Value.Text(s) =>
            val bytes =
              try
                val buffer = StandardCharsets.UTF_8
                  .newEncoder()
                  .onMalformedInput(CodingErrorAction.REPORT)
                  .onUnmappableCharacter(CodingErrorAction.REPORT)
                  .encode(CharBuffer.wrap(s))
                val a = new Array[Byte](buffer.remaining())
                buffer.get(a)
                a
              catch case NonFatal(_) => fail("invalid Unicode text")
            raw(3, bytes)
          case Value.Arr(nodes) =>
            head(4, BigInt(nodes.size))
            nodes.foreach(n => go(n.value, depth + 1))
          case Value.Map(pairs) =>
            head(5, BigInt(pairs.size))
            pairs.foreach { case (k, v) => go(k.value, depth + 1); go(v.value, depth + 1) }
          case Value.Tag(tag, node) =>
            head(6, tag)
            go(node.value, depth + 1)
          case Value.Bool(b) => write(if b then 245 else 244)
          case Value.Null    => write(246)
      go(value, 0)
      Right(Bytes.fromArray(out.toByteArray))
    catch
      case Failure(message)      => Left(message)
      case _: StackOverflowError => Left(TooDeep)
