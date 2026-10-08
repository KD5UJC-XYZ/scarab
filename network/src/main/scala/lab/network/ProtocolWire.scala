// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import scala.util.control.NonFatal
import ChainSync.{Point, Tip, UInt64}

/** Bounded CBOR framing. It does not normalize or deserialize opaque payloads. */
private[network] object ProtocolWire:
  // Extraction of existing ChainSyncWire mechanics; independent protocol policy.
  // Mini-protocol limits are checked before entering this shared framing layer.
  final case class Limits(
      maxMessageBytes: Int,
      maxStringBytes: Int,
      maxHashBytes: Int = 64,
      maxDepth: Int = 24,
      maxItems: Int = 8192,
      maxCandidates: Int = 64
  ):
    def valid: Boolean =
      maxMessageBytes > 0 && maxMessageBytes <= 2500000 &&
        maxStringBytes >= 0 && maxStringBytes <= 2500000 &&
        maxHashBytes >= 0 && maxHashBytes <= 2500000 &&
        maxDepth >= 0 && maxDepth <= 64 && maxItems > 0 && maxItems <= 65535 &&
        maxCandidates >= 0 && maxCandidates <= 1024
  final case class Failure(message: String, incomplete: Boolean = false)
      extends RuntimeException(message)
  def bad(message: String): Nothing = throw Failure(message)
  def get[A](value: Either[String, A]): A = value.fold(bad, identity)
  def attempt[A](body: => A): Either[String, A] =
    try Right(body)
    catch case Failure(message, _) => Left(message)
  def validateItem(bytes: Bytes, limits: Limits): Either[String, Unit] = attempt {
    val reader = new Reader(bytes, limits)
    reader.skip(0)
    if reader.position != bytes.size then bad("trailing payload bytes")
  }

  class Reader(input: Bytes, limits: Limits):
    if !limits.valid then bad("invalid local limits")
    private var pos = 0
    private var items = 0
    def position: Int = pos
    private def need(n: Int): Unit =
      if n < 0 || n > limits.maxMessageBytes - pos then bad("message byte limit exceeded")
      if n > input.size - pos then throw Failure("truncated CBOR", true)
    def read(): Int =
      need(1)
      val b = input.value(pos) & 255
      pos += 1
      b
    private def peek: Int =
      need(1)
      input.value(pos) & 255
    private def argument(ai: Int): BigInt =
      if ai < 24 then BigInt(ai)
      else
        val n = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => bad("reserved or indefinite argument")
        need(n)
        var value = BigInt(0)
        var i = 0
        while i < n do
          value = (value << 8) | read()
          i += 1
        value
    private def bounded(value: BigInt, max: Int, what: String): Int =
      if value > max then bad(s"$what limit exceeded")
      value.toInt
    def arrayLength(): Int =
      val h = read()
      if h / 32 != 4 || (h & 31) == 31 then bad("expected definite array")
      bounded(argument(h & 31), limits.maxItems, "array")
    def uint(): UInt64 =
      val h = read()
      if h / 32 != 0 then bad("expected uint64")
      get(UInt64.from(argument(h & 31)))
    def tag(): BigInt =
      val h = read()
      if h / 32 != 6 then bad("expected tag")
      argument(h & 31)
    def bytes(max: Int): Bytes =
      val h = read()
      if h / 32 != 2 || (h & 31) == 31 then bad("expected definite byte string")
      val n = bounded(argument(h & 31), math.min(max, limits.maxStringBytes), "string")
      need(n)
      val value = Bytes(input.value.slice(pos, pos + n))
      pos += n
      value
    def point(): Point = arrayLength() match
      case 0 => Point.Origin
      case 2 => Point.Block(uint(), bytes(limits.maxHashBytes))
      case _ => bad("point arity")
    def tip(): Tip =
      if arrayLength() != 2 then bad("tip arity")
      Tip(point(), uint())
    def points(): Vector[Point] =
      val h = read()
      if h / 32 != 4 then bad("expected candidate array")
      val out = Vector.newBuilder[Point]
      if (h & 31) == 31 then
        var n = 0
        while peek != 255 do
          if n >= limits.maxCandidates then bad("candidate limit exceeded")
          out += point()
          n += 1
        read()
      else
        val n = bounded(argument(h & 31), limits.maxCandidates, "candidate")
        var i = 0
        while i < n do
          out += point()
          i += 1
      out.result()
    private def count(): Unit =
      if items >= limits.maxItems then bad("CBOR item limit exceeded")
      items += 1
    private def stringChunk(major: Int, ai: Int, remaining: Int): Int =
      val n = bounded(argument(ai), remaining, "string")
      need(n)
      if major == 3 then
        try
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(input.value.slice(pos, pos + n).toArray))
        catch case NonFatal(_) => bad("invalid UTF-8")
      pos += n
      n
    def skip(depth: Int): Unit =
      if depth > limits.maxDepth then bad("CBOR depth limit exceeded")
      count()
      val h = read()
      val major = h / 32
      val ai = h & 31
      major match
        case 0 | 1 => argument(ai); ()
        case 2 | 3 =>
          if ai != 31 then { stringChunk(major, ai, limits.maxStringBytes); () }
          else
            var total = 0
            while peek != 255 do
              count()
              val chunk = read()
              if chunk / 32 != major || (chunk & 31) == 31 then bad("invalid string chunk")
              total += stringChunk(major, chunk & 31, limits.maxStringBytes - total)
            read(); ()
        case 4 | 5 =>
          val multiplier = if major == 5 then 2 else 1
          if ai == 31 then
            while peek != 255 do
              skip(depth + 1)
              if multiplier == 2 then skip(depth + 1)
            read(); ()
          else
            val n = bounded(argument(ai), (limits.maxItems - items) / multiplier, "item")
            var i = 0
            while i < n do
              skip(depth + 1)
              if multiplier == 2 then skip(depth + 1)
              i += 1
        case 6 => argument(ai); skip(depth + 1)
        case 7 =>
          ai match
            case 31 | 28 | 29 | 30 => bad("unexpected break or reserved simple value")
            case 24                => if read() < 32 then bad("invalid extended simple value")
            case 25 | 26 | 27      => argument(ai); ()
            case _                 => ()
    def item(): Bytes =
      val start = pos
      skip(0)
      Bytes(input.value.slice(start, pos))

  class Writer(limits: Limits):
    if !limits.valid then bad("invalid local limits")
    private val out = new java.io.ByteArrayOutputStream()
    def byte(b: Int): Unit =
      if out.size() >= limits.maxMessageBytes then bad("message byte limit exceeded")
      out.write(b)
    def raw(bytes: Bytes): Unit =
      if bytes.size > limits.maxMessageBytes - out.size() then bad("message byte limit exceeded")
      bytes.value.foreach(b => byte(b & 255))
    def head(major: Int, value: BigInt): Unit =
      if value < 0 || value > UInt64.Max then bad("CBOR argument outside uint64")
      if value < 24 then byte(major * 32 + value.toInt)
      else
        val width =
          if value <= 255 then 1
          else if value <= 65535 then 2
          else if value <= 0xffffffffL then 4
          else 8
        byte(major * 32 + (width match
          case 1 => 24
          case 2 => 25
          case 4 => 26
          case _ => 27))
        (0 until width).reverse.foreach(i => byte(((value >> (i * 8)) & 255).toInt))
    def point(point: Point): Unit = point match
      case Point.Origin => head(4, 0)
      case Point.Block(slot, hash) =>
        if hash.size > math.min(limits.maxHashBytes, limits.maxStringBytes) then
          bad("hash byte limit exceeded")
        head(4, 2); head(0, slot.value); head(2, hash.size); raw(hash)
    def tip(tip: Tip): Unit =
      head(4, 2); point(tip.point); head(0, tip.blockNo.value)
    def result: Bytes = Bytes.fromArray(out.toByteArray)
