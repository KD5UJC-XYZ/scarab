// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal

/** Small byte-preserving CBOR subset. Undefined and unassigned simple values are deliberately
  * unsupported, and so are floats unless [[Limits.allowFloats]] says otherwise (Cardano's wire
  * formats hold none; only the ledger state's pool-ranking likelihoods do). Duplicate map keys and
  * map order are retained.
  *
  * Decoding and encoding keep their stack on the heap, so nesting is not limited by the thread's
  * stack. The reference has no nesting limit either; only the size of the block bounds it, and a
  * level takes at least one byte. `Limits.maxDepth` therefore defaults to no limit of its own:
  * `maxInputBytes` and `maxItems` (every level is an item) bound the depth and the memory.
  */
object Cbor:
  final case class Limits(
      maxInputBytes: Int = 1048576,
      maxDepth: Int = Int.MaxValue,
      maxItems: Int = 100000,
      maxStringBytes: Int = 1048576,
      /** Accept half, single and double floats (as [[Value.Float]]). Off: they are refused. */
      allowFloats: Boolean = false
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
    /** The next `count` bytes as a big-endian number (the bits of a float). */
    private def floatBits(count: Int): Long =
      var n = 0L
      var i = 0
      while i < count do
        n = (n << 8) | read().toLong
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

    /** An open container: its children so far, and how many are still to come (-1 for an indefinite
      * container, which ends at a break).
      */
    private final class Frame(val major: Int, val start: Int, val tag: BigInt, var remaining: Long):
      // Children are collected in a small array that doubles: a claimed length is a limit, not
      // an allocation, and a one-element array (a level of deep nesting) stays small.
      private var kids: Array[Node] = null
      var count = 0
      def add(n: Node): Unit =
        if kids == null then kids = new Array[Node](2)
        else if count == kids.length then kids = java.util.Arrays.copyOf(kids, count * 2)
        kids(count) = n
        count += 1
      def items: Vector[Node] =
        if count == 0 then Vector.empty
        else Vector.from(scala.collection.immutable.ArraySeq.unsafeWrapArray(kids.take(count)))
      def pairs: Vector[(Node, Node)] =
        val out = new Array[(Node, Node)](count / 2)
        var i = 0
        while i < out.length do
          out(i) = (kids(2 * i), kids(2 * i + 1))
          i += 1
        Vector.from(scala.collection.immutable.ArraySeq.unsafeWrapArray(out))
      def only: Node = kids(0)

    private def window(start: Int): Bytes = Bytes.wrap(data, start, pos - start)

    private def finish(f: Frame): Node =
      val value = f.major match
        case 4 => Value.Arr(f.items)
        case 5 => Value.Map(f.pairs)
        case _ => Value.Tag(f.tag, f.only)
      Node(value, window(f.start))

    /** Decode the item at the cursor. Containers are frames on an explicit stack, not recursive
      * calls, so the nesting an input can have is bounded by `maxDepth`, `maxItems` and its size
      * (every level takes a byte), not by the thread's stack.
      */
    def run(): Node =
      val stack = new java.util.ArrayDeque[Frame]
      var result: Node = null
      while result == null do
        val top = stack.peek()
        var produced: Node = null
        if top != null && top.remaining == 0 then
          stack.pop()
          produced = finish(top)
        else if top != null && top.remaining < 0 && isBreak then
          if top.major == 5 && top.count % 2 == 1 then fail("map has an unmatched key")
          read()
          stack.pop()
          produced = finish(top)
        else
          if top != null && top.remaining > 0 then top.remaining -= 1
          if stack.size > limits.maxDepth then fail("depth limit exceeded")
          consumeItem()
          val start = pos
          val h = read()
          val major = h / 32
          val ai = h & 31
          major match
            case 0     => produced = Node(Value.UInt(argument(ai)), window(start))
            case 1     => produced = Node(Value.NInt(-1 - argument(ai)), window(start))
            case 2 | 3 => produced = Node(string(major, ai), window(start))
            case 4 =>
              val remaining =
                if ai == 31 then -1L
                else bounded(argument(ai), limits.maxItems - items, "item").toLong
              stack.push(new Frame(4, start, null, remaining))
            case 5 =>
              val remaining =
                if ai == 31 then -1L
                else 2L * bounded(argument(ai), (limits.maxItems - items) / 2, "map pair")
              stack.push(new Frame(5, start, null, remaining))
            case 6 => stack.push(new Frame(6, start, argument(ai), 1L))
            case _ =>
              val value = ai match
                case 20 => Value.Bool(false)
                case 21 => Value.Bool(true)
                case 22 => Value.Null
                case 25 if limits.allowFloats =>
                  Value.Float(Float16.toFloat(floatBits(2).toInt).toDouble, FloatWidth.Half)
                case 26 if limits.allowFloats =>
                  Value.Float(
                    java.lang.Float.intBitsToFloat(floatBits(4).toInt).toDouble,
                    FloatWidth.Single
                  )
                case 27 if limits.allowFloats =>
                  Value.Float(java.lang.Double.longBitsToDouble(floatBits(8)), FloatWidth.Double)
                case _ => fail("unsupported simple value, float, or unexpected break")
              produced = Node(value, window(start))
        if produced != null then
          if stack.isEmpty then result = produced else stack.peek().add(produced)
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
      // An open container: the values still to write, by index (a map's keys and values
      // alternate), without an iterator per level.
      final class Open(val nodes: Vector[Node], val pairs: Vector[(Node, Node)], val node: Node):
        var next = 0
        val size: Int =
          if nodes != null then nodes.size else if pairs != null then 2 * pairs.size else 1
        def hasNext: Boolean = next < size
        def take(): Value =
          val i = next
          next += 1
          if nodes != null then nodes(i).value
          else if pairs != null then (if i % 2 == 0 then pairs(i / 2)._1 else pairs(i / 2)._2).value
          else node.value
      val stack = new java.util.ArrayDeque[Open]
      def emit(v: Value, depth: Int): Unit =
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
            stack.push(new Open(nodes, null, null))
          case Value.Map(pairs) =>
            head(5, BigInt(pairs.size))
            stack.push(new Open(null, pairs, null))
          case Value.Tag(tag, node) =>
            head(6, tag)
            stack.push(new Open(null, null, node))
          case Value.Bool(b) => write(if b then 245 else 244)
          case Value.Null    => write(246)
          case Value.Float(x, FloatWidth.Half) =>
            val bits = Float16.fromFloat(x.toFloat)
            write(0xf9); write(bits >> 8); write(bits & 0xff)
          case Value.Float(x, FloatWidth.Single) =>
            val bits = java.lang.Float.floatToRawIntBits(x.toFloat)
            write(0xfa)
            var shift = 24
            while shift >= 0 do { write((bits >>> shift) & 0xff); shift -= 8 }
          case Value.Float(x, FloatWidth.Double) =>
            val bits = java.lang.Double.doubleToRawLongBits(x)
            write(0xfb)
            var shift = 56
            while shift >= 0 do { write(((bits >>> shift) & 0xff).toInt); shift -= 8 }
      emit(value, 0)
      while !stack.isEmpty do
        val top = stack.peek()
        if top.hasNext then emit(top.take(), stack.size)
        else stack.pop()
      Right(Bytes.fromArray(out.toByteArray))
    catch
      case Failure(message)      => Left(message)
      case _: StackOverflowError => Left(TooDeep)
