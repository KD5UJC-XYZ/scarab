// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.codec

import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal
import scala.util.boundary
import scala.util.boundary.{Label, break}

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
  /** @param allowFloats
    *   Accept half, single and double floats (as [[Value.Float]]). Off: they are refused.
    */
  final case class Limits(
      maxInputBytes: Int = 1048576,
      maxDepth: Int = Int.MaxValue,
      maxItems: Int = 100000,
      maxStringBytes: Int = 1048576,
      allowFloats: Boolean = false
  )
  private val MaxUInt = (BigInt(1) << 64) - 1
  enum Error extends DomainError:
    case InvalidLimits
    case InputLimitExceeded
    case UnexpectedEnd
    case InvalidAdditionalInformation(value: Int)
    case TruncatedString
    case InvalidUtf8
    case ItemLimitExceeded
    case InvalidStringChunk
    case UnmatchedMapKey
    case DepthLimitExceeded
    case UnsupportedSimpleValue(value: Int)
    case TrailingBytes
    case OutputLimitExceeded
    case StringLimitExceeded
    case MapPairLimitExceeded
    case ArgumentOutOfRange(value: BigInt)
    case NonNegativeNInt(value: BigInt)
    case InvalidUnicode
    case StackExhausted
    def message: String = this match
      case InvalidLimits      => "limits must be nonnegative"
      case InputLimitExceeded => "input byte limit exceeded"
      case UnexpectedEnd      => "unexpected end of input"
      case InvalidAdditionalInformation(_) =>
        "reserved additional information or invalid indefinite item"
      case TruncatedString    => "truncated string payload"
      case InvalidUtf8        => "invalid UTF-8 text"
      case ItemLimitExceeded  => "item limit exceeded"
      case InvalidStringChunk => "indefinite strings require definite chunks of the same type"
      case UnmatchedMapKey    => "map has an unmatched key"
      case DepthLimitExceeded => "depth limit exceeded"
      case UnsupportedSimpleValue(_) => "unsupported simple value, float, or unexpected break"
      case TrailingBytes             => "trailing bytes after CBOR item"
      case OutputLimitExceeded       => "output byte limit exceeded"
      case StringLimitExceeded       => "string byte limit exceeded"
      case MapPairLimitExceeded      => "map pair limit exceeded"
      case ArgumentOutOfRange(_)     => "CBOR argument outside uint64 range"
      case NonNegativeNInt(_)        => "NInt must be negative"
      case InvalidUnicode            => "invalid Unicode text"
      case StackExhausted            => "nesting exceeds the stack"

  val TooDeep: Error = Error.StackExhausted

  def stackSafe[A](body: => Either[Error, A]): Either[Error, A] =
    try body
    catch case _: StackOverflowError => Left(TooDeep)

  def decode(bytes: Bytes, limits: Limits = Limits()): Either[Error, Node] = boundary:
    if limits.maxInputBytes < 0 || limits.maxDepth < 0 || limits.maxItems < 0 || limits.maxStringBytes < 0
    then Left(Error.InvalidLimits)
    else if bytes.size > limits.maxInputBytes then Left(Error.InputLimitExceeded)
    else
      try Right(new Decoder(bytes, limits).run())
      catch case _: StackOverflowError => Left(TooDeep)

  /** Reads the input once, with the checks of a decoder, and writes the [[Index]] of its items;
    * nothing is built per item. What it accepts and what it refuses (and says) is what the tree
    * builder of the first version did, which the tests keep as the oracle.
    */
  private final class Decoder(input: Bytes, limits: Limits)(using Label[Either[Error, Node]]):
    private def fail(error: Error): Nothing = break(Left(error))
    // Read-only view of the input: the document shares it, its nodes are windows onto it.
    private val data = input.unsafeArray
    private val base = input.unsafeOffset
    private val end = base + input.size
    private val index = new Index(input.size)
    private var pos = base
    private var items = 0
    private def read(): Int =
      if pos >= end then fail(Error.UnexpectedEnd)
      val result = data(pos) & 0xff
      pos += 1
      result
    private def isBreak: Boolean = pos < end && (data(pos) & 0xff) == 255

    /** The argument of a head with additional information `ai`, as an unsigned 64-bit number in a
      * `Long` (negative above `2^63`). The reserved additional information are refused.
      */
    private def argument(ai: Int): Long =
      if ai < 24 then ai.toLong
      else
        val count = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => fail(Error.InvalidAdditionalInformation(ai))
        var n = 0L
        var i = 0
        while i < count do
          n = (n << 8) | read().toLong
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

    /** `n`, an argument, as a count or length of at most `bound`. */
    private def bounded(n: Long, bound: Int, what: Error): Int =
      if n < 0 || n > bound.toLong then fail(what)
      n.toInt

    /** Step over a string payload of `size` bytes and return where it starts. */
    private def payload(size: Int): Int =
      if size > end - pos then fail(Error.TruncatedString)
      val from = pos
      pos += size
      from

    private var decoder: java.nio.charset.CharsetDecoder = null
    private var scratch: CharBuffer = null

    /** Check that `[from, from + size)` is UTF-8, strictly. ASCII, the usual case, is checked
      * without a decoder; the others are decoded into a scratch buffer and dropped.
      */
    private def utf8(from: Int, size: Int): Unit =
      var i = from
      val until = from + size
      while i < until && data(i) >= 0 do i += 1
      if i < until then
        if decoder == null then
          decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
          scratch = CharBuffer.allocate(1024)
        decoder.reset()
        val in = ByteBuffer.wrap(data, from, size)
        var open = true
        while open do
          scratch.clear()
          val result = decoder.decode(in, scratch, true)
          if result.isError then fail(Error.InvalidUtf8)
          else if result.isUnderflow then
            scratch.clear()
            if decoder.flush(scratch).isError then fail(Error.InvalidUtf8)
            open = false
    private def consumeItem(): Unit =
      if items >= limits.maxItems then fail(Error.ItemLimitExceeded)
      items += 1

    /** Check the string item whose head, with additional information `ai`, has been read. */
    private def string(major: Int, ai: Int): Unit =
      if ai != 31 then
        val from = payload(bounded(argument(ai), limits.maxStringBytes, Error.StringLimitExceeded))
        val size = pos - from
        if major == 3 then utf8(from, size)
      else
        var total = 0
        while !isBreak do
          consumeItem()
          val h = read()
          if h / 32 != major || (h & 31) == 31 then fail(Error.InvalidStringChunk)
          val length =
            bounded(argument(h & 31), limits.maxStringBytes - total, Error.StringLimitExceeded)
          val from = payload(length)
          total += length
          if major == 3 then utf8(from, length)
        read()

    // The open containers, innermost last: the item, its major type, the children still to come
    // (-1 for an indefinite container, which ends at a break) and the children so far.
    private var depth = 0
    private var openItem = new Array[Int](8)
    private var openMajor = new Array[Int](8)
    private var openLeft = new Array[Long](8)
    private var openCount = new Array[Int](8)
    private def push(item: Int, major: Int, left: Long): Unit =
      if depth == openItem.length then
        openItem = java.util.Arrays.copyOf(openItem, depth * 2)
        openMajor = java.util.Arrays.copyOf(openMajor, depth * 2)
        openLeft = java.util.Arrays.copyOf(openLeft, depth * 2)
        openCount = java.util.Arrays.copyOf(openCount, depth * 2)
      openItem(depth) = item
      openMajor(depth) = major
      openLeft(depth) = left
      openCount(depth) = 0
      depth += 1

    /** Decode the item at the cursor. Containers are frames on an explicit stack, not recursive
      * calls, so the nesting an input can have is bounded by `maxDepth`, `maxItems` and its size
      * (every level takes a byte), not by the thread's stack.
      */
    def run(): Node =
      var finished = false
      while !finished do
        val top = depth - 1
        // an item that is complete, to be counted as a child of the one open
        var produced = false
        if top >= 0 && openLeft(top) == 0 then
          depth -= 1
          index.finish(openItem(top), pos)
          produced = true
        else if top >= 0 && openLeft(top) < 0 && isBreak then
          if openMajor(top) == 5 && openCount(top) % 2 == 1 then fail(Error.UnmatchedMapKey)
          read()
          depth -= 1
          index.finish(openItem(top), pos)
          produced = true
        else
          if top >= 0 && openLeft(top) > 0 then openLeft(top) -= 1
          if depth > limits.maxDepth then fail(Error.DepthLimitExceeded)
          consumeItem()
          val start = pos
          val h = read()
          val major = h / 32
          val ai = h & 31
          val item = index.begin(start)
          major match
            case 0 | 1 =>
              argument(ai)
              produced = true
            case 2 | 3 =>
              string(major, ai)
              produced = true
            case 4 =>
              val remaining =
                if ai == 31 then -1L
                else bounded(argument(ai), limits.maxItems - items, Error.ItemLimitExceeded).toLong
              push(item, 4, remaining)
            case 5 =>
              val remaining =
                if ai == 31 then -1L
                else
                  2L * bounded(
                    argument(ai),
                    (limits.maxItems - items) / 2,
                    Error.MapPairLimitExceeded
                  )
              push(item, 5, remaining)
            case 6 =>
              argument(ai)
              push(item, 6, 1L)
            case _ =>
              ai match
                case 20 | 21 | 22             => ()
                case 25 if limits.allowFloats => floatBits(2)
                case 26 if limits.allowFloats => floatBits(4)
                case 27 if limits.allowFloats => floatBits(8)
                case _                        => fail(Error.UnsupportedSimpleValue(ai))
              produced = true
          // a leaf is complete as soon as it is read; a container that was pushed is not
          if produced && major != 4 && major != 5 && major != 6 then index.finish(item, pos)
        if produced then if depth == 0 then finished = true else openCount(depth - 1) += 1
      if pos != end then fail(Error.TrailingBytes)
      new Document(data, index).node(0)

  /** Uses shortest integer/length widths and definite containers, recursively re-encoding values
    * (not their original bytes). Map order is retained, so this is NOT RFC 8949 deterministic map
    * ordering. For hashing a decoded wire item, use Node.original, never a re-encoding. The same
    * caller-selected resource limits may be supplied to the encoder and decoder. `allowFloats`
    * controls decoding only; the encoder keeps its prior behavior of writing explicit float nodes.
    */
  def encode(value: Value, limits: Limits = Limits()): Either[Error, Bytes] = boundary:
    def fail(error: Error): Nothing = break(Left(error))
    if limits.maxInputBytes < 0 || limits.maxDepth < 0 || limits.maxItems < 0 || limits.maxStringBytes < 0
    then Left(Error.InvalidLimits)
    else
      try
        val out = new java.io.ByteArrayOutputStream()
        var items = 0
        def write(b: Int): Unit =
          if out.size() >= limits.maxInputBytes then fail(Error.OutputLimitExceeded)
          out.write(b)
        def head(major: Int, n: BigInt): Unit =
          if n < 0 || n > MaxUInt then fail(Error.ArgumentOutOfRange(n))
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
          if bytes.length > limits.maxStringBytes then fail(Error.StringLimitExceeded)
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
            else if pairs != null then
              (if i % 2 == 0 then pairs(i / 2)._1 else pairs(i / 2)._2).value
            else node.value
        val stack = new java.util.ArrayDeque[Open]
        def emit(v: Value, depth: Int): Unit =
          if depth > limits.maxDepth then fail(Error.DepthLimitExceeded)
          if items >= limits.maxItems then fail(Error.ItemLimitExceeded)
          items += 1
          v match
            case Value.UInt(n) => head(0, n)
            case Value.NInt(n) =>
              if n >= 0 then fail(Error.NonNegativeNInt(n))
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
                catch case NonFatal(_) => fail(Error.InvalidUnicode)
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
      catch case _: StackOverflowError => Left(TooDeep)
