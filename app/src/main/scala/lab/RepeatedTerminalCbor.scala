// SPDX-License-Identifier: Apache-2.0
package lab

import java.nio.{ByteBuffer, CharBuffer}
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import lab.cbor.Bytes
import scala.util.boundary

/** Observational terminal-state decoder only. It does not expand the admitted ledger CBOR subset.
  * Every node retains its exact wire span, including indefinite and non-shortest encodings. Float
  * words retain their original width and sign; conversion to raw32 is explicitly exact or fails.
  * These fixed bounds are local research limits, not Cardano consensus limits.
  */
private[lab] object RepeatedTerminalCbor:
  val MaxInputBytes = 2 * 1024 * 1024
  val MaxDepth = 48
  val MaxNodes = 300000
  val MaxContainerEntries = 4096
  val MaxRetainedOriginalBytes = 16 * 1024 * 1024
  private val MaxUInt = (BigInt(1) << 64) - 1

  final case class Node(value: Value, original: Bytes)
  enum Value:
    case UInt(value: BigInt)
    case NInt(value: BigInt)
    case ByteString(value: Bytes)
    case Text(value: String)
    case Arr(value: Vector[Node])
    case Map(value: Vector[(Node, Node)])
    case Tag(value: BigInt, inner: Node)
    case Bool(value: Boolean)
    case Null
    case Float16(raw: Int)
    case Float32(raw: Int)
    case Float64(raw: Long)

  enum LimitKind:
    case InputBytes, Depth, Nodes, StringBytes, ArrayEntries, MapEntries, RetainedOriginalBytes

  enum Failure:
    case Malformed(reason: String)
    case Limit(kind: LimitKind)
    case Unsupported(feature: String)
    case Shape(expected: String)
    case DuplicateKey
    case NonFiniteFloat(width: Int)
    case InexactFloat32

  private def fail(error: Failure)(using boundary.Label[Either[Failure, Node]]): Nothing =
    boundary.break(Left(error))

  /** Root depth is zero. String chunks count towards the node and depth limits. Duplicate map
    * entries remain available for typed, semantic duplicate rejection by `mapping`.
    */
  def decode(input: Bytes): Either[Failure, Node] =
    if input == null || input.value == null then Left(Failure.Malformed("CBOR input required"))
    else if input.size > MaxInputBytes then Left(Failure.Limit(LimitKind.InputBytes))
    else boundary[Either[Failure, Node]] { Right(new Decoder(input).run()) }

  private final class Decoder(input: Bytes)(using boundary.Label[Either[Failure, Node]]):
    private val data = input.toArray
    private var pos = 0
    private var nodes = 0
    private var retainedOriginalBytes = 0
    private def read(): Int =
      if pos >= data.length then fail(Failure.Malformed("unexpected end of CBOR input"))
      val value = data(pos) & 255
      pos += 1
      value
    private def isBreak: Boolean = pos < data.length && (data(pos) & 255) == 255
    private def consume(depth: Int): Unit =
      if depth > MaxDepth then fail(Failure.Limit(LimitKind.Depth))
      if nodes >= MaxNodes then fail(Failure.Limit(LimitKind.Nodes))
      nodes += 1
    private def argument(ai: Int): BigInt =
      if ai < 24 then BigInt(ai)
      else
        val width = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _ =>
            fail(
              Failure.Malformed("reserved CBOR additional information or invalid indefinite item")
            )
        if width > data.length - pos then fail(Failure.Malformed("truncated CBOR argument"))
        var result = BigInt(0)
        var i = 0
        while i < width do
          result = (result << 8) | BigInt(read())
          i += 1
        result
    private def bounded(n: BigInt, bound: Int, kind: LimitKind): Int =
      if n > BigInt(bound) then fail(Failure.Limit(kind))
      n.toInt
    private def payload(length: Int): Array[Byte] =
      if length > data.length - pos then fail(Failure.Malformed("truncated CBOR string payload"))
      val result = java.util.Arrays.copyOfRange(data, pos, pos + length)
      pos += length
      result
    private def utf8(raw: Array[Byte]): String =
      val decoder = StandardCharsets.UTF_8
        .newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT)
        .onUnmappableCharacter(CodingErrorAction.REPORT)
      val text = CharBuffer.allocate(raw.length)
      val result = decoder.decode(ByteBuffer.wrap(raw), text, true)
      if result.isError || result.isOverflow then fail(Failure.Malformed("invalid UTF-8 CBOR text"))
      val flushed = decoder.flush(text)
      if flushed.isError || flushed.isOverflow then
        fail(Failure.Malformed("invalid UTF-8 CBOR text"))
      text.flip().toString
    private def string(major: Int, ai: Int, depth: Int): Value =
      if ai != 31 then
        val raw = payload(bounded(argument(ai), MaxInputBytes, LimitKind.StringBytes))
        if major == 2 then Value.ByteString(Bytes.fromArray(raw)) else Value.Text(utf8(raw))
      else
        val chunks = Vector.newBuilder[Byte]
        val text = new java.lang.StringBuilder
        var total = 0
        while !isBreak do
          consume(depth + 1)
          val h = read()
          if h / 32 != major || (h & 31) == 31 then
            fail(
              Failure.Malformed("indefinite CBOR strings require definite chunks of the same type")
            )
          val length = bounded(argument(h & 31), MaxInputBytes - total, LimitKind.StringBytes)
          val raw = payload(length)
          total += length
          if major == 2 then chunks ++= raw else text.append(utf8(raw))
        read()
        if major == 2 then Value.ByteString(Bytes(chunks.result())) else Value.Text(text.toString)
    private def node(depth: Int): Node =
      consume(depth)
      val start = pos
      val h = read()
      val major = h / 32
      val ai = h & 31
      val value = major match
        case 0     => Value.UInt(argument(ai))
        case 1     => Value.NInt(-1 - argument(ai))
        case 2 | 3 => string(major, ai, depth)
        case 4 =>
          val out = Vector.newBuilder[Node]
          if ai == 31 then
            var count = 0
            while !isBreak do
              if count >= MaxContainerEntries then fail(Failure.Limit(LimitKind.ArrayEntries))
              out += node(depth + 1)
              count += 1
            read()
          else
            val count = bounded(argument(ai), MaxContainerEntries, LimitKind.ArrayEntries)
            if count > MaxNodes - nodes then fail(Failure.Limit(LimitKind.Nodes))
            if count > data.length - pos then fail(Failure.Malformed("truncated CBOR array"))
            var i = 0
            while i < count do
              out += node(depth + 1)
              i += 1
          Value.Arr(out.result())
        case 5 =>
          val out = Vector.newBuilder[(Node, Node)]
          if ai == 31 then
            var count = 0
            while !isBreak do
              if count >= MaxContainerEntries then fail(Failure.Limit(LimitKind.MapEntries))
              val key = node(depth + 1)
              if isBreak then fail(Failure.Malformed("CBOR map has an unmatched key"))
              out += ((key, node(depth + 1)))
              count += 1
            read()
          else
            val count = bounded(argument(ai), MaxContainerEntries, LimitKind.MapEntries)
            if count > (MaxNodes - nodes) / 2 then fail(Failure.Limit(LimitKind.Nodes))
            if count > (data.length - pos) / 2 then fail(Failure.Malformed("truncated CBOR map"))
            var i = 0
            while i < count do
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
            case 25 =>
              val raw = argument(ai).toInt
              if (raw & 0x7c00) == 0x7c00 then fail(Failure.NonFiniteFloat(16))
              Value.Float16(raw)
            case 26 =>
              val raw = argument(ai).toInt
              if (raw & 0x7f800000) == 0x7f800000 then fail(Failure.NonFiniteFloat(32))
              Value.Float32(raw)
            case 27 =>
              val raw = argument(ai).toLong
              if (raw & 0x7ff0000000000000L) == 0x7ff0000000000000L then
                fail(Failure.NonFiniteFloat(64))
              Value.Float64(raw)
            case 28 | 29 | 30 =>
              fail(Failure.Malformed("reserved CBOR additional information"))
            case 31 => fail(Failure.Malformed("unexpected CBOR break"))
            case _  => fail(Failure.Unsupported("CBOR simple value"))
      // Bound cumulative retained spans before copying. A legal-depth tree must not multiply a
      // nearly maximum-size string by every ancestor without a separate memory bound.
      val spanLength = pos - start
      if spanLength > MaxRetainedOriginalBytes - retainedOriginalBytes then
        fail(Failure.Limit(LimitKind.RetainedOriginalBytes))
      retainedOriginalBytes += spanLength
      Node(value, Bytes.fromArray(java.util.Arrays.copyOfRange(data, start, pos)))
    def run(): Node =
      val result = node(0)
      if pos != data.length then fail(Failure.Malformed("trailing bytes after CBOR item"))
      result

  def unsigned(n: Node): Either[Failure, BigInt] = n match
    case Node(Value.UInt(value), _) if value >= 0 && value <= MaxUInt => Right(value)
    case _ => Left(Failure.Shape("CBOR uint64"))

  def signed(n: Node): Either[Failure, BigInt] = n match
    case Node(Value.NInt(value), _) if value < 0 && value >= -1 - MaxUInt => Right(value)
    case _                                                                => unsigned(n)

  def rows(n: Node): Either[Failure, Vector[Node]] = n match
    case Node(Value.Arr(value), _) if value.size <= MaxContainerEntries => Right(value)
    case _ => Left(Failure.Shape("bounded CBOR array"))

  def array(n: Node, width: Int): Either[Failure, Vector[Node]] =
    rows(n).flatMap(value =>
      if value.size == width then Right(value) else Left(Failure.Shape(s"CBOR array width $width"))
    )

  /** Key conversion precedes duplicate detection, so wire-width and chunking differences cannot
    * disguise duplicates of typed values. The decoder itself never silently drops a map entry.
    */
  def mapping[K, V](n: Node)(
      key: Node => Either[Failure, K],
      value: Node => Either[Failure, V]
  ): Either[Failure, Map[K, V]] = n match
    case Node(Value.Map(pairs), _) if pairs.size <= MaxContainerEntries =>
      pairs.foldLeft[Either[Failure, Map[K, V]]](Right(Map.empty)) { case (acc, (k, v)) =>
        for
          previous <- acc
          decodedKey <- key(k)
          _ <-
            if previous.contains(decodedKey) then Left(Failure.DuplicateKey)
            else Right(())
          decodedValue <- value(v)
        yield previous.updated(decodedKey, decodedValue)
      }
    case _ => Left(Failure.Shape("bounded CBOR map"))

  def bytes(n: Node, width: Int): Either[Failure, Bytes] = n match
    case Node(Value.ByteString(value), _) if value.size == width => Right(value)
    case _ => Left(Failure.Shape(s"CBOR byte string width $width"))

  /** Pinned native decodeFloat accepts binary16 and binary32, but not binary64. Mathematical
    * exactness alone is therefore insufficient for accepting a serialized native Float field.
    */
  def nativeFloat32(n: Node): Either[Failure, Int] = n match
    case Node(Value.Float64(_), _) => Left(Failure.Unsupported("binary64 native Float field"))
    case _                         => float32(n)

  /** Return IEEE binary32 bits only when the finite source value converts exactly. The raw-bit
    * round trip for binary64 also checks the sign of zero; tiny and overflowing values reject.
    */
  def float32(n: Node): Either[Failure, Int] = n match
    case Node(Value.Float16(raw), _) if raw >= 0 && raw <= 65535 && (raw & 0x7c00) != 0x7c00 =>
      val sign = (raw & 0x8000) << 16
      val exponent = (raw >>> 10) & 31
      var fraction = raw & 1023
      if exponent != 0 then Right(sign | ((exponent + 112) << 23) | (fraction << 13))
      else if fraction == 0 then Right(sign)
      else
        var e = -14
        while (fraction & 1024) == 0 do
          fraction <<= 1
          e -= 1
        Right(sign | ((e + 127) << 23) | ((fraction & 1023) << 13))
    case Node(Value.Float32(raw), _) if (raw & 0x7f800000) != 0x7f800000 => Right(raw)
    case Node(Value.Float64(raw), _) if (raw & 0x7ff0000000000000L) != 0x7ff0000000000000L =>
      val value = java.lang.Double.longBitsToDouble(raw)
      val narrowed = value.toFloat
      if java.lang.Float.isFinite(narrowed) &&
        java.lang.Double.doubleToRawLongBits(narrowed.toDouble) == raw
      then Right(java.lang.Float.floatToRawIntBits(narrowed))
      else Left(Failure.InexactFloat32)
    case _ => Left(Failure.Shape("finite CBOR float"))
