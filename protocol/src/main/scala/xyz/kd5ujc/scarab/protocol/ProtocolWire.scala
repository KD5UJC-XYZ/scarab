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

  /** No indefinite string is open in [[Reader.skip]]. */
  private val NoChunks = -1
  def bad(error: ProtocolError)(using Scope): Nothing = boundary.break(Left(error))
  def get[A](value: Either[ProtocolError, A])(using Scope): A = value.fold(bad, identity)
  def attempt[A](body: Scope ?=> A): Either[ProtocolError, A] = boundary { Right(body) }
  def prefix[A](body: Scope ?=> ChainSync.DecodeResult[A]): ChainSync.DecodeResult[A] =
    attempt(body) match
      case Right(value)                    => value
      case Left(error) if error.incomplete => ChainSync.DecodeResult.NeedMore
      case Left(error)                     => ChainSync.DecodeResult.Failed(error)
  def validateItem(bytes: Bytes, limits: Limits): Either[ProtocolError, Unit] = attempt {
    val reader = Reader(bytes, limits)
    reader.skip(0)
    if reader.position != bytes.size then bad(ProtocolError.TrailingPayloadBytes)
  }

  /** `bytes` as whole items one after another, with the checks of [[validateItem]]: one item as a
    * decoder of the ledger reads it, which a tree of items reads as several ([[Reader.spanning]]).
    */
  def validateItems(bytes: Bytes, limits: Limits): Either[ProtocolError, Unit] = attempt {
    Reader(bytes, limits).span(bytes.size)
  }

  /** Where a reader is in its input: the position of the next byte, and the items it has counted
    * there (the count [[Limits.maxItems]] bounds).
    */
  final case class Mark(position: Int, items: Int)

  /** An item [[Reader.skip]] had not read whole when its input ran out, as it stood at the head it
    * stopped at: the skip that began at `start`, `depth` levels down, had reached `at`, with
    * `remaining` items still due in the innermost open container, `pending` those due in the ones
    * around it (innermost first, `open` of them), and `chunks` the major type of an indefinite
    * string whose chunks it was reading ([[NoChunks]] when none), `total` bytes of them so far.
    */
  final case class Skipping(
      start: Mark,
      depth: Int,
      at: Mark,
      remaining: Int,
      pending: Levels,
      open: Int,
      chunks: Int,
      total: Int
  )

  /** The children the open containers around the innermost one still wait for, innermost first: a
    * stack of unboxed counts, shared by the steps of a skip and the progress it leaves.
    */
  enum Levels:
    case Outermost
    case Within(remaining: Int, outer: Levels)

  /** The items [[Reader.spanning]] had read whole, from `start` to `at`, when the input ran out in
    * the span or after it.
    */
  final case class Spanning(start: Mark, at: Mark)

  /** What a reader whose input ran out leaves for a longer prefix of the same input, so that no
    * head it read is read again: the reference's decoders keep their progress in a continuation
    * (`deserialiseIncremental` runs the saved decode action on each new chunk only, cborg 0.2.10.0
    * `src/Codec/CBOR/Read.hs:169-172, 1254-1260`), and a codec's driver feeds the continuation each
    * chunk as it arrives (`runDecoderWithLimit`,
    * `ouroboros-network/framework/lib/Ouroboros/Network/Driver/Limits.hs:281-331` at c45735a5 and
    * 4646039f).
    */
  final case class Progress(skipping: Option[Skipping], spanning: Option[Spanning])

  object Progress:
    val none: Progress = Progress(None, None)

  /** The elements a list reader had read whole when its input ran out: the list whose first element
    * began at `start`, its elements up to `at`, and how many more it declares (`-1` for an
    * indefinite list).
    */
  final case class Listing[+A](start: Mark, at: Mark, collected: Vector[A], left: Long)

  /** A value read whole from `start` to `end`, kept so that a longer prefix does not read it again.
    */
  final case class Settled[+A](start: Mark, end: Mark, value: A)

  /** One attempt at a message on a prefix of it: what the attempt kept for a longer prefix (the
    * decoder's own `kept` and its reader's [[Progress]]), or its verdict.
    */
  enum Attempt[+C, +A]:
    case Short(kept: Option[C], reader: Progress)
    case Done(result: ChainSync.DecodeResult[A])

  /** Where [[keep]] ends an attempt. */
  type Keep[-C] = boundary.Label[Attempt[C, Nothing]]

  /** Run `body` on `reader` as one attempt: a failure that more input could cure ends it short,
    * with the reader's progress; any other failure is its verdict.
    */
  def resumed[C, A](reader: Reader)(
      body: Keep[C] ?=> Scope ?=> ChainSync.DecodeResult[A]
  ): Attempt[C, A] =
    boundary[Attempt[C, A]] {
      attempt(body) match
        case Right(result)                   => Attempt.Done(result)
        case Left(error) if error.incomplete => Attempt.Short(None, reader.progress)
        case Left(error)                     => Attempt.Done(ChainSync.DecodeResult.Failed(error))
    }

  /** End the attempt short of input, keeping `kept` and the progress of `reader`. */
  def keep[C](kept: C, reader: Reader)(using Keep[C]): Nothing =
    boundary.break(Attempt.Short(Some(kept), reader.progress))

  /** The decoder of a message read with one [[Reader]], resumed on each longer prefix of it: the
    * reader goes on with the item and the span it stopped in ([[Progress]]), and `body` with what
    * it kept ([[keep]]). `open` makes the reader of an attempt: its checks run first, on every
    * attempt, as they ran before any byte was read.
    */
  def incremental[C, A](open: (Bytes, Progress) => Scope ?=> Reader)(
      body: (Bytes, Option[C], Reader) => Keep[C] ?=> Scope ?=> ChainSync.DecodeResult[A]
  ): IncrementalDecoder[A] = new IncrementalDecoder[A]:
    type Cursor = (Option[C], Progress)
    val initial: Cursor = (None, Progress.none)
    def resume(cursor: Cursor, input: Bytes): (Cursor, ChainSync.DecodeResult[A]) =
      attempt(open(input, cursor._2)) match
        case Left(error) if error.incomplete => (initial, ChainSync.DecodeResult.NeedMore)
        case Left(error)                     => (initial, ChainSync.DecodeResult.Failed(error))
        case Right(reader) =>
          resumed[C, A](reader)(body(input, cursor._1, reader)) match
            case Attempt.Short(kept, progress) =>
              ((kept, progress), ChainSync.DecodeResult.NeedMore)
            case Attempt.Done(result) => (initial, result)

  /** `read` on its own, so that a failure more input could cure reaches the caller as a value, and
    * any other failure ends the enclosing scope as it would have.
    */
  def short[A](read: Scope ?=> A)(using Scope): Option[A] =
    attempt(read) match
      case Right(value)                    => Some(value)
      case Left(error) if error.incomplete => None
      case Left(error)                     => bad(error)

  /** The CBOR reader of every mini-protocol codec: a cursor over the input, failing through the
    * [[Scope]] of the call. It keeps its imperative form on measurement: a functional walk of the
    * same checks took 2.4 to 3.2 times as long and allocated 4 to 11 times as much on real blocks
    * (`docs/performance.md` section 45, issue 657; `WireSkipFunctional` in the tests).
    *
    * A reader resumes from `from`, what a reader of a shorter prefix of the same input left
    * ([[progress]]): the skip and the span it stopped in go on where they stopped, so that a
    * message that arrives in many parts has each of its heads read once.
    */
  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Var",
      "org.wartremover.warts.While"
    )
  )
  final class Reader private (input: Bytes, limits: Limits, from: Progress):
    private var pos = 0
    private var items = 0
    private var resumeSkip = from.skipping
    private var resumeSpan = from.spanning
    // The skip in progress: where it began, and its state at the head it is reading.
    private var skipping = false
    private var skipStart = Mark(0, 0)
    private var skipDepth = 0
    private var headAt = 0
    private var headItems = 0
    private var headRemaining = 0
    private var remaining = 0
    private var pending: Levels = Levels.Outermost
    private var open = 0
    private var chunks = NoChunks
    private var total = 0
    // The last span read: where it began, and where the item it was reading began, or, once it
    // has read every item of the span, where they end: the items before are whole either way.
    private var spanned = false
    private var spanStart = Mark(0, 0)
    private var spanAt = Mark(0, 0)

    def position: Int = pos
    def mark: Mark = Mark(pos, items)

    /** Go on from `at`, a mark this reader or one of a shorter prefix of its input reached. */
    def resumeAt(at: Mark): Unit =
      pos = at.position
      items = at.items

    /** What a reader of a longer prefix of the input resumes from: the skip and the span this one
      * was in when its input ran out.
      */
    def progress: Progress =
      Progress(
        Option.when(skipping)(
          Skipping(
            skipStart,
            skipDepth,
            Mark(headAt, headItems),
            headRemaining,
            pending,
            open,
            chunks,
            total
          )
        ),
        Option.when(spanned)(Spanning(spanStart, spanAt))
      )

    private def need(n: Int)(using Scope): Unit =
      if n < 0 || n > limits.maxMessageBytes - pos then bad(ProtocolError.MessageByteLimitExceeded)
      if n > input.size - pos then bad(ProtocolError.TruncatedCbor)
    def read()(using Scope): Int =
      val b = peek
      pos += 1
      b
    private def peek(using Scope): Int =
      need(1)
      input.byteOr(pos)(bad(ProtocolError.TruncatedCbor))(_ & 255)
    private def argument(ai: Int)(using Scope): BigInt =
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
    private def bounded(value: BigInt, max: Int, what: ProtocolError)(using Scope): Int =
      if value > max then bad(what)
      value.toInt
    def arrayLength()(using Scope): Int =
      val h = read()
      if h / 32 != 4 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteArray)
      bounded(argument(h & 31), limits.maxItems, ProtocolError.ArrayLimitExceeded)
    def uint()(using Scope): UInt64 =
      val h = read()
      if h / 32 != 0 then bad(ProtocolError.ExpectedUint64)
      get(UInt64.from(argument(h & 31)))

    /** The major type of the next item, which is not read. */
    def major(using Scope): Int = peek / 32
    def tag()(using Scope): BigInt =
      val h = read()
      if h / 32 != 6 then bad(ProtocolError.ExpectedTag)
      argument(h & 31)
    def bytes(max: Int)(using Scope): Bytes =
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
    def mapLength()(using Scope): Int =
      val h = read()
      if h / 32 != 5 || (h & 31) == 31 then bad(ProtocolError.ExpectedDefiniteMap)
      bounded(argument(h & 31), limits.maxItems, ProtocolError.MapLimitExceeded)
    def bool()(using Scope): Boolean = read() match
      case 0xf4 => false
      case 0xf5 => true
      case _    => bad(ProtocolError.ExpectedBoolean)
    def text(max: Int)(using Scope): String =
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
    def point()(using Scope): Point = arrayLength() match
      case 0 => Point.Origin
      case 2 => Point.Block(uint(), bytes(limits.maxHashBytes))
      case _ => bad(ProtocolError.PointArity)
    def tip()(using Scope): Tip =
      if arrayLength() != 2 then bad(ProtocolError.TipArity)
      Tip(point(), uint())
    def points()(using Scope): Vector[Point] =
      items(limits.maxCandidates)(_.point())

    /** Read a definite or indefinite CBOR array with the candidate-list limits used by ChainSync.
      * The element reader runs inline, so specialized domain codecs retain the protocol's
      * validation order without first materializing opaque CBOR items.
      */
    def items[A](maxCandidates: Int)(readOne: Reader => Scope ?=> A)(using Scope): Vector[A] =
      listing(maxCandidates, None)(readOne)(_ => bad(ProtocolError.TruncatedCbor))

    /** [[items]], resumed from `kept` when it is this list's: a list whose input runs out before it
      * ends calls `stop` with the elements read whole so far, instead of failing; an element that
      * has not all arrived is read again from its start.
      */
    def listing[A](maxCandidates: Int, kept: Option[Listing[A]])(readOne: Reader => Scope ?=> A)(
        stop: Listing[A] => Nothing
    )(using Scope): Vector[A] =
      val h = read()
      if h / 32 != 4 then bad(ProtocolError.ExpectedCandidateArray)
      val declared =
        if (h & 31) == 31 then -1L
        else bounded(argument(h & 31), maxCandidates, ProtocolError.CandidateLimitExceeded).toLong
      val start = mark
      val resumed = kept.filter(_.start == start)
      resumed.foreach(k => resumeAt(k.at))
      @scala.annotation.tailrec
      def loop(collected: Vector[A], left: Long): Vector[A] =
        val at = mark
        if left == 0 then collected
        else
          val ended =
            left < 0 && short(peek).fold(stop(Listing(start, at, collected, left)))(_ == 255)
          if ended then
            read()
            collected
          else
            if left < 0 && collected.sizeIs >= maxCandidates then
              bad(ProtocolError.CandidateLimitExceeded)
            short(readOne(this)) match
              case Some(value) => loop(collected :+ value, if left > 0 then left - 1 else left)
              case None        => stop(Listing(start, at, collected, left))
      resumed.fold(loop(Vector.empty, declared))(k => loop(k.collected, k.left))

    private def count()(using Scope): Unit =
      if items >= limits.maxItems then bad(ProtocolError.CBORItemLimitExceeded)
      items += 1
    private def stringChunk(major: Int, ai: Int, remaining: Int)(using Scope): Int =
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
      * [[IndefiniteArray]], [[IndefiniteKey]] or [[IndefiniteValue]]. The chunks of an indefinite
      * string are read one a step, as the items are.
      *
      * Each step records where it began ([[progress]]), and a skip that begins where a reader of a
      * shorter prefix stopped in one goes on from there: the heads before it were read then.
      */
    def skip(depth: Int)(using Scope): Unit =
      val start = mark
      resumeSkip match
        case Some(kept) if kept.start == start && kept.depth == depth =>
          resumeSkip = None
          resumeAt(kept.at)
          remaining = kept.remaining
          pending = kept.pending
          open = kept.open
          chunks = kept.chunks
          total = kept.total
        case Some(_) | None =>
          remaining = 1
          pending = Levels.Outermost
          open = 0
          chunks = NoChunks
          total = 0
      skipping = true
      skipStart = start
      skipDepth = depth
      while chunks != NoChunks || remaining != 0 || open > 0 do
        headAt = pos
        headItems = items
        headRemaining = remaining
        if chunks != NoChunks then
          if peek == 255 then
            read()
            chunks = NoChunks
          else
            count()
            val chunk = read()
            if chunk / 32 != chunks || (chunk & 31) == 31 then bad(ProtocolError.InvalidStringChunk)
            total += stringChunk(chunks, chunk & 31, limits.maxStringBytes - total)
        else if remaining == 0 then
          pending match
            case Levels.Within(count, outer) =>
              remaining = count
              pending = outer
            case Levels.Outermost => ()
          open -= 1
        else if (remaining == IndefiniteArray || remaining == IndefiniteKey) && peek == 255 then
          read(); remaining = 0
        else
          if remaining > 0 then remaining -= 1
          else if remaining == IndefiniteKey then remaining = IndefiniteValue
          else if remaining == IndefiniteValue then remaining = IndefiniteKey
          if depth + open > limits.maxDepth then bad(ProtocolError.CBORDepthLimitExceeded)
          opened() match
            case Opened.Scalar => ()
            case Opened.Chunks(major) =>
              chunks = major
              total = 0
            case Opened.Container(children) =>
              pending = Levels.Within(remaining, pending)
              open += 1
              remaining = children
      end while
      skipping = false

    private enum Opened:
      case Scalar
      case Chunks(major: Int)
      case Container(children: Int)

    /** Read one item's head and its scalar payload; a container, a tag or an indefinite string is
      * left open.
      */
    private def opened()(using Scope): Opened =
      count()
      val h = read()
      val major = h / 32
      val ai = h & 31
      major match
        case 0 | 1 => argument(ai); Opened.Scalar
        case 2 | 3 =>
          if ai != 31 then
            stringChunk(major, ai, limits.maxStringBytes)
            Opened.Scalar
          else Opened.Chunks(major)
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
    def item()(using Scope): Bytes =
      val start = pos
      skip(0)
      input.slice(start, pos).compact

    /** The next `size` bytes, read as whole items one after another with the checks of [[item]]:
      * one item as a decoder of the ledger reads it, which a tree of items reads as several
      * ([[LocalStateQuery.QuerySpan.Prefix]]). Refused where an item runs past them.
      */
    def spanning(size: Int)(using Scope): Bytes =
      val start = pos
      span(size)
      input.slice(start, pos).compact

    /** [[spanning]] without the bytes: the items are read and checked, and none is copied. A span
      * that begins where a reader of a shorter prefix stopped in one goes on after the items it
      * read whole, when they lie within `size`.
      */
    def span(size: Int)(using Scope): Unit =
      val start = pos
      val first = mark
      need(size)
      resumeSpan
        .filter(kept => kept.start == first && kept.at.position - start <= size)
        .foreach { kept =>
          resumeSpan = None
          resumeAt(kept.at)
        }
      spanned = true
      spanStart = first
      while pos - start < size do
        spanAt = mark
        skip(0)
      spanAt = mark
      if pos - start != size then bad(ProtocolError.ItemRunsPastSpan)

  object Reader:
    /** A reader of `input` from its start, resumed from `from` ([[Reader.progress]] of a reader of
      * a shorter prefix of it).
      */
    def apply(input: Bytes, limits: Limits, from: Progress = Progress.none)(using
        Scope
    ): Reader =
      if !limits.valid then bad(ProtocolError.InvalidLocalLimits)
      new Reader(input, limits, from)

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
