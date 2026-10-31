// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import java.nio.ByteBuffer
import java.nio.charset.{CodingErrorAction, StandardCharsets}
import cats.syntax.all.*
import scala.util.boundary

import ChainSync.{Point, Tip, UInt64}

/** Bounded CBOR framing. It does not normalize or deserialize opaque payloads. */
private[protocol] object ProtocolWire:
  // Extraction of existing ChainSyncWire mechanics; independent protocol policy.
  // Mini-protocol limits are checked before entering this shared framing layer.

  /** The largest limits a protocol may configure. `Network` is the bound of every node-to-node
    * message. `Local` is that of a node-to-client message: the reference reads it with no byte or
    * item limit (`codecLocalStateQuery` is `mkCodecCborLazyBS`, run with `Stateful.runPeer`), so
    * only its mux ingress queue bounds it (`maximumMiniProtocolLimits`, `0xffffffff`), and here the
    * largest array the JVM can index.
    */
  enum Bound(val bytes: Int, val items: Int):
    case Network extends Bound(2500000, 65535)
    case Local extends Bound(Int.MaxValue, Int.MaxValue)

  /** @param bound
    *   the ceiling the other limits are checked against ([[Bound.Network]] unless a protocol says
    *   otherwise).
    */
  final case class Limits(
      maxMessageBytes: Int,
      maxStringBytes: Int,
      maxHashBytes: Int = 64,
      maxDepth: Int = 24,
      maxItems: Int = 8192,
      maxCandidates: Int = 64,
      bound: Bound = Bound.Network
  ):
    def valid: Boolean =
      maxMessageBytes > 0 && maxMessageBytes <= bound.bytes &&
        maxStringBytes >= 0 && maxStringBytes <= bound.bytes &&
        maxHashBytes >= 0 && maxHashBytes <= bound.bytes &&
        maxDepth >= 0 && maxItems > 0 && maxItems <= bound.items &&
        maxCandidates >= 0 && maxCandidates <= 1024
  type Scope = boundary.Label[Either[ProtocolError, Nothing]]

  /** The most items a message may hold. A level of nesting is an item, so this is also the deepest
    * a transaction-carrying message may nest (the reference limits neither; the message size does),
    * and the nesting is walked on the heap.
    */
  val MaxItems = 65535
  private val IndefiniteArray = -1
  private val IndefiniteKey = -2
  private val IndefiniteValue = -3
  def bad(error: ProtocolError)(using Scope): Nothing = boundary.break(Left(error))
  def get[A](value: Either[ProtocolError, A])(using Scope): A = value.fold(bad, identity)
  def attempt[A](body: Scope ?=> A): Either[ProtocolError, A] = boundary { Right(body) }
  def prefix[A](body: Scope ?=> ChainSync.DecodeResult[A]): ChainSync.DecodeResult[A] =
    attempt(body) match
      case Right(value)                    => value
      case Left(error) if error.incomplete => ChainSync.DecodeResult.NeedMore
      case Left(error)                     => ChainSync.DecodeResult.Failed(error)
  def validateItem(bytes: Bytes, limits: Limits): Either[ProtocolError, Unit] = attempt {
    val reader = new Reader(bytes, limits)
    reader.skip(0)
    if reader.position != bytes.size then bad(ProtocolError.TrailingPayloadBytes)
  }

  /** The CBOR reader of every mini-protocol codec: a cursor over the input, failing through the
    * [[Scope]]. It keeps its imperative form on measurement: a functional walk of the same checks
    * took 2.4 to 3.2 times as long and allocated 4 to 11 times as much on real blocks
    * (`docs/performance.md` section 45, issue 657; `WireSkipFunctional` in the tests).
    */
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Var",
      "org.wartremover.warts.While",
      "org.wartremover.warts.MutableDataStructures"
    )
  )
  class Reader(input: Bytes, limits: Limits)(using Scope):
    if !limits.valid then bad(ProtocolError.InvalidLocalLimits)
    private var pos = 0
    private var items = 0
    def position: Int = pos
    private def need(n: Int): Unit =
      if n < 0 || n > limits.maxMessageBytes - pos then bad(ProtocolError.MessageByteLimitExceeded)
      if n > input.size - pos then bad(ProtocolError.TruncatedCbor)
    def read(): Int =
      val b = peek
      pos += 1
      b
    private def peek: Int =
      need(1)
      input.lift(pos).fold(bad(ProtocolError.TruncatedCbor))(_ & 255)
    private def argument(ai: Int): BigInt =
      if ai < 24 then BigInt(ai)
      else
        val n = ai match
          case 24 => 1
          case 25 => 2
          case 26 => 4
          case 27 => 8
          case _  => bad(ProtocolError.ReservedOrIndefiniteArgument)
        need(n)
        var value = BigInt(0)
        var i = 0
        while i < n do
          value = (value << 8) | read()
          i += 1
        value
    private def bounded(value: BigInt, max: Int, what: ProtocolError): Int =
      if value > max then bad(what)
      value.toInt
    def arrayLength(): Int =
      val h = read()
      if h / 32 != 4 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteArray)
      bounded(argument(h & 31), limits.maxItems, ProtocolError.ArrayLimitExceeded)
    def uint(): UInt64 =
      val h = read()
      if h / 32 != 0 then bad(ProtocolError.ExpectedUint64)
      get(UInt64.from(argument(h & 31)))
    def tag(): BigInt =
      val h = read()
      if h / 32 != 6 then bad(ProtocolError.ExpectedTag)
      argument(h & 31)
    def bytes(max: Int): Bytes =
      val h = read()
      if h / 32 != 2 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteByteString)
      val n = bounded(
        argument(h & 31),
        math.min(max, limits.maxStringBytes),
        ProtocolError.StringLimitExceeded
      )
      need(n)
      val value = input.slice(pos, pos + n).compact
      pos += n
      value
    def mapLength(): Int =
      val h = read()
      if h / 32 != 5 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteMap)
      bounded(argument(h & 31), limits.maxItems, ProtocolError.MapLimitExceeded)
    def bool(): Boolean = read() match
      case 0xf4 => false
      case 0xf5 => true
      case _    => bad(ProtocolError.ExpectedBoolean)
    def text(max: Int): String =
      val h = read()
      if h / 32 != 3 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteTextString)
      val n = bounded(
        argument(h & 31),
        math.min(max, limits.maxStringBytes),
        ProtocolError.StringLimitExceeded
      )
      need(n)
      val decoded = Either
        .catchNonFatal(
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(input.slice(pos, pos + n).toArray))
            .toString
        )
        .fold(_ => bad(ProtocolError.InvalidUTF8), identity)
      pos += n
      decoded
    def point(): Point = arrayLength() match
      case 0 => Point.Origin
      case 2 => Point.Block(uint(), bytes(limits.maxHashBytes))
      case _ => bad(ProtocolError.PointArity)
    def tip(): Tip =
      if arrayLength() != 2 then bad(ProtocolError.TipArity)
      Tip(point(), uint())
    def points(): Vector[Point] =
      items(limits.maxCandidates)(_.point())

    /** Read a definite or indefinite CBOR array with the candidate-list limits used by ChainSync.
      * The element reader runs inline, so specialized domain codecs retain the protocol's
      * validation order without first materializing opaque CBOR items.
      */
    def items[A](maxCandidates: Int)(readOne: Reader => A): Vector[A] =
      val h = read()
      if h / 32 != 4 then bad(ProtocolError.ExpectedCandidateArray)
      val out = Vector.newBuilder[A]
      if (h & 31) == 31 then
        var n = 0
        while peek != 255 do
          if n >= maxCandidates then bad(ProtocolError.CandidateLimitExceeded)
          out += readOne(this)
          n += 1
        read()
      else
        val n =
          bounded(argument(h & 31), maxCandidates, ProtocolError.CandidateLimitExceeded)
        var i = 0
        while i < n do
          out += readOne(this)
          i += 1
      out.result()
    private def count(): Unit =
      if items >= limits.maxItems then bad(ProtocolError.CBORItemLimitExceeded)
      items += 1
    private def stringChunk(major: Int, ai: Int, remaining: Int): Int =
      val n = bounded(argument(ai), remaining, ProtocolError.StringLimitExceeded)
      need(n)
      if major == 3 then
        Either
          .catchNonFatal(
            StandardCharsets.UTF_8
              .newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT)
              .onUnmappableCharacter(CodingErrorAction.REPORT)
              .decode(ByteBuffer.wrap(input.slice(pos, pos + n).toArray))
          )
          .fold(_ => bad(ProtocolError.InvalidUTF8), _ => ())
      pos += n
      n

    /** Skip one item. The open containers are kept on the heap, not the stack, so a nesting of
      * `maxDepth` levels (a level takes a byte, so the message size bounds it) cannot overflow the
      * thread's stack. `pending` holds, for each open container, the children its parent still
      * waits for; `remaining` is that count for the innermost one: a number of children, or
      * [[IndefiniteArray]], [[IndefiniteKey]] or [[IndefiniteValue]].
      */
    def skip(depth: Int): Unit =
      val pending = scala.collection.mutable.ArrayBuffer.empty[Int]
      var remaining = 1
      while remaining != 0 || pending.nonEmpty do
        if remaining == 0 then remaining = pending.remove(pending.size - 1)
        else if (remaining == IndefiniteArray || remaining == IndefiniteKey) && peek == 255 then
          read(); remaining = 0
        else
          if remaining > 0 then remaining -= 1
          else if remaining == IndefiniteKey then remaining = IndefiniteValue
          else if remaining == IndefiniteValue then remaining = IndefiniteKey
          if depth + pending.size > limits.maxDepth then bad(ProtocolError.CBORDepthLimitExceeded)
          opened() match
            case Opened.Scalar => ()
            case Opened.Container(children) =>
              pending += remaining
              remaining = children
      end while

    private enum Opened:
      case Scalar
      case Container(children: Int)

    /** Read one item's head and its scalar payload; a container or tag is left open. */
    private def opened(): Opened =
      count()
      val h = read()
      val major = h / 32
      val ai = h & 31
      major match
        case 0 | 1 => argument(ai); Opened.Scalar
        case 2 | 3 =>
          if ai != 31 then { stringChunk(major, ai, limits.maxStringBytes); () }
          else
            var total = 0
            while peek != 255 do
              count()
              val chunk = read()
              if chunk / 32 != major || (chunk & 31) == 31 then
                bad(ProtocolError.InvalidStringChunk)
              total += stringChunk(major, chunk & 31, limits.maxStringBytes - total)
            read(); ()
          Opened.Scalar
        case 4 | 5 =>
          val multiplier = if major == 5 then 2 else 1
          if ai == 31 then Opened.Container(if major == 5 then IndefiniteKey else IndefiniteArray)
          else
            val n = bounded(
              argument(ai),
              (limits.maxItems - items) / multiplier,
              ProtocolError.ItemLimitExceeded
            )
            Opened.Container(n * multiplier)
        case 6 => argument(ai); Opened.Container(1)
        case _ =>
          ai match
            case 31 | 28 | 29 | 30 => bad(ProtocolError.UnexpectedBreakOrReservedSimpleValue)
            case 24           => if read() < 32 then bad(ProtocolError.InvalidExtendedSimpleValue)
            case 25 | 26 | 27 => argument(ai); ()
            case _            => ()
          Opened.Scalar
    def item(): Bytes =
      val start = pos
      skip(0)
      input.slice(start, pos).compact

  class Writer(limits: Limits)(using Scope):
    if !limits.valid then bad(ProtocolError.InvalidLocalLimits)
    private val out = new java.io.ByteArrayOutputStream()
    def byte(b: Int): Unit =
      if out.size() >= limits.maxMessageBytes then bad(ProtocolError.MessageByteLimitExceeded)
      out.write(b)
    def raw(bytes: Bytes): Unit =
      if bytes.size > limits.maxMessageBytes - out.size() then
        bad(ProtocolError.MessageByteLimitExceeded)
      bytes.withRange((array, offset, length) => out.write(array, offset, length))
    def head(major: Int, value: BigInt): Unit =
      if value < 0 || value > UInt64.Max then bad(ProtocolError.CBORArgumentOutsideUint64)
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
    def bool(value: Boolean): Unit = byte(if value then 0xf5 else 0xf4)
    def text(value: String): Unit =
      val encoded = value.getBytes(StandardCharsets.UTF_8)
      head(3, encoded.length)
      encoded.foreach(b => byte(b & 255))
    def point(point: Point): Unit = point match
      case Point.Origin => head(4, 0)
      case Point.Block(slot, hash) =>
        if hash.size > math.min(limits.maxHashBytes, limits.maxStringBytes) then
          bad(ProtocolError.HashByteLimitExceeded)
        head(4, 2); head(0, slot.value); head(2, hash.size); raw(hash)
    def tip(tip: Tip): Unit =
      head(4, 2); point(tip.point); head(0, tip.blockNo.value)
    def result: Bytes = Bytes.fromArray(out.toByteArray)
