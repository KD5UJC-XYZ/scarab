// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import cats.data.StateT
import cats.syntax.all.*
import scala.annotation.tailrec
import scala.collection.immutable.SortedMap
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Point, Role, Tip, UInt64}
import ProtocolWire.{Limits, Scope, attempt, bad, get}

/** TEST-ONLY ORACLES: the mini-protocol decoders as they were at 3cad1f045 (the base of the change
  * that made every decoder resume on a longer prefix; origin/main 897310d97 has the same files),
  * each reading every prefix from its start. Never used by the main sources: the differential
  * suites compare each new decoder, resumed over the cuts of a message, with these on every prefix.
  * The reader and the handshake's parser are copied verbatim but for their names; each decoder is
  * its base `decodePrefix` with the codec it took inlined (the node's Cardano codecs).
  */
private[scarab] object BaseDecoders:
  private val IndefiniteArray = -1
  private val IndefiniteKey = -2
  private val IndefiniteValue = -3

  @SuppressWarnings(
    Array(
      "org.wartremover.warts.Var",
      "org.wartremover.warts.While",
      "org.wartremover.warts.MutableDataStructures"
    )
  )
  class BaseReader(input: Bytes, limits: Limits)(using Scope):
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
      input.byteOr(pos)(bad(ProtocolError.TruncatedCbor))(_ & 255)
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

    /** The major type of the next item, which is not read. */
    def major: Int = peek / 32
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
    def items[A](maxCandidates: Int)(readOne: BaseReader => A): Vector[A] =
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

    /** The next `size` bytes, read as whole items one after another with the checks of [[item]]:
      * one item as a decoder of the ledger reads it, which a tree of items reads as several
      * ([[LocalStateQuery.QuerySpan.Prefix]]). Refused where an item runs past them.
      */
    def spanning(size: Int): Bytes =
      val start = pos
      need(size)
      while pos - start < size do skip(0)
      if pos - start != size then bad(ProtocolError.ItemRunsPastSpan)
      input.slice(start, pos).compact

  def validateItem(bytes: Bytes, limits: Limits): Either[ProtocolError, Unit] = attempt {
    val reader = new BaseReader(bytes, limits)
    reader.skip(0)
    if reader.position != bytes.size then bad(ProtocolError.TrailingPayloadBytes)
  }

  /** `ChainSyncEngine.decodePrefix` at the base, with the Cardano point and tip and `payload` read
    * as `ChainSync.enginePayloadCodec` reads it.
    */
  def chainSync(
      state: ChainSync.State,
      sender: Role,
      bytes: Bytes,
      payload: ChainSync.PayloadCodec[Bytes],
      limits: ChainSync.Limits = ChainSync.Limits()
  ): DecodeResult[ChainSyncEngine.Message[Point, Tip, Bytes]] = ProtocolWire.prefix {
    import ChainSyncEngine.Message
    val bounds = ChainSyncWire.checked(limits)
    val reader = new BaseReader(bytes, ChainSyncWire.checked(limits))
    val arity = reader.arrayLength()
    val tag = reader.uint().value
    val expectedArity = tag match
      case n if n == 0 || n == 1 || n == 7 => 1
      case n if n == 4 || n == 6           => 2
      case n if n == 2 || n == 3 || n == 5 => 3
      case _                               => bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    if arity != expectedArity then bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    val tagNumber = tag.toInt
    if !chainSyncAgency(state, sender, tagNumber) then
      bad(ProtocolError.ChainSyncAgency(state, sender))
    def readPayload(): Bytes = get(payload.decode(reader.item()))
    val message: Message[Point, Tip, Bytes] = tagNumber match
      case 0 => Message.RequestNext
      case 1 => Message.AwaitReply
      case 2 => Message.RollForward(readPayload(), reader.tip())
      case 3 => Message.RollBackward(reader.point(), reader.tip())
      case 4 => Message.FindIntersect(reader.items(limits.maxCandidates)(_.point()))
      case 5 => Message.IntersectFound(reader.point(), reader.tip())
      case 6 => Message.IntersectNotFound(reader.tip())
      case 7 => Message.Done
      case _ => bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    get(ChainSyncEngine.transition(state, sender, message))
    get(validateItem(bytes.take(reader.position), bounds))
    DecodeResult.Decoded(message, reader.position)
  }

  private def chainSyncAgency(state: ChainSync.State, sender: Role, tag: Int): Boolean =
    import ChainSync.State.*
    import Role.*
    (state, sender, tag) match
      case (Idle, Client, 0 | 4 | 7)         => true
      case (NextCanAwait, Server, 1 | 2 | 3) => true
      case (NextMustReply, Server, 2 | 3)    => true
      case (Intersect, Server, 5 | 6)        => true
      case _                                 => false

  /** `TxSubmission2Engine.decodePrefix` at the base with the node's profile,
    * `TxSubmission2.eraCodec` of `reader` (`eraProfile`'s `readId`, `readBody` and `validateOffer`
    * inlined).
    */
  def txSubmission2(
      state: TxSubmission2Engine.State,
      sender: Role,
      bytes: Bytes,
      reader: TxSubmission2.TransactionReader
  ): DecodeResult[TxSubmission2Engine.Message[TxSubmission2.EraTxId, TxSubmission2.EraTx]] =
    ProtocolWire.prefix {
      import TxSubmission2Engine.{Message, Offer, State}
      import TxSubmission2.{EraTx, EraTxId}
      if state == State.Done || (state == State.Idle) != (sender == Role.Server) then
        bad(ProtocolError.WrongTxSubmission2Agency)
      val limits = ProtocolWire.Limits(
        TxSubmission2.maxMessageBytes(state),
        TxSubmission2.MaxOriginalBytes,
        32,
        ProtocolWire.MaxItems,
        ProtocolWire.MaxItems
      )
      val r = new BaseReader(bytes, limits)
      def shelleyBased(index: BigInt): Option[Era] = Era.fromIndex(index).filter(_ != Era.Byron)
      def checkId(id: Bytes): Unit =
        if id.size != 32 then bad(ProtocolError.TransactionIDMustBe32Bytes(id.size))
      def readId(): EraTxId =
        if r.arrayLength() != 2 then bad(ProtocolError.ExpectedShelleyBasedTransactionID)
        val era =
          shelleyBased(r.uint().value)
            .getOrElse(bad(ProtocolError.ExpectedShelleyBasedTransactionID))
        val result = r.bytes(32); checkId(result); EraTxId(era, result)
      def validateOffer(offer: Offer[EraTxId]): Unit =
        checkId(offer.transactionId.id)
        if offer.advertisedSize <= 0 || offer.advertisedSize > 0xffffffffL then
          bad(ProtocolError.SizeOutsideUint32)
      def readBody(): EraTx =
        if r.arrayLength() != 2 then bad(ProtocolError.ExpectedShelleyBasedGenTx)
        val era =
          shelleyBased(r.uint().value).getOrElse(bad(ProtocolError.ExpectedShelleyBasedGenTx))
        if r.tag() != 24 then bad(ProtocolError.ExpectedShelleyBasedGenTx)
        val value = r.bytes(TxSubmission2.MaxOriginalBytes)
        get(TxSubmission2.advertisedSize(value.size))
        get(reader.read(value, era))
        EraTx(era, value)
      def list[A](f: () => A): Vector[A] =
        if r.read() != 0x9f then bad(ProtocolError.ExpectedIndefiniteProtocolList)
        @tailrec
        def loop(collected: Vector[A]): Vector[A] =
          bytes.lift(r.position) match
            case None => bad(ProtocolError.TruncatedList)
            case Some(b) if b == 0xff.toByte =>
              r.read(); collected
            case Some(_) if collected.sizeIs >= TxSubmission2.MaxBatch =>
              bad(ProtocolError.BatchLimitExceeded(collected.size, TxSubmission2.MaxBatch))
            case Some(_) => loop(collected :+ f())
        loop(Vector.empty)
      def uint16(): Int =
        val n = r.uint().value
        if n > 65535 then bad(ProtocolError.CountOutsideUint16(n))
        n.toInt
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message: Message[EraTxId, EraTx] = (state, arity, tag.toInt) match
        case (State.Init, 1, 6) if tag == 6 => Message.Init
        case (State.Idle, 4, 0) if tag == 0 =>
          val blockingByte = r.read()
          if blockingByte != 0xf4 && blockingByte != 0xf5 then
            bad(ProtocolError.ExpectedBlockingBoolean)
          val blocking = blockingByte == 0xf5
          Message.RequestIds(blocking, uint16(), uint16())
        case (State.Ids(_), 2, 1) if tag == 1 =>
          Message.ReplyIds(list(() =>
            if r.arrayLength() != 2 then bad(ProtocolError.OfferArity)
            val id = readId()
            val size = r.uint().value
            if size <= 0 || size > 0xffffffffL then bad(ProtocolError.SizeOutsideUint32)
            val offer = Offer(id, size.toLong)
            validateOffer(offer)
            offer
          ))
        case (State.Idle, 2, 2) if tag == 2      => Message.RequestTxs(list(() => readId()))
        case (State.Txs, 2, 3) if tag == 3       => Message.ReplyTxs(list(() => readBody()))
        case (State.Ids(true), 1, 4) if tag == 4 => Message.Done
        case _ => bad(ProtocolError.UnknownTxSubmission2TagStateArity)
      get(TxSubmission2Engine.transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    }

  /** `KeepAlive.decodePrefix` at the base. */
  def keepAlive(
      state: KeepAlive.State,
      sender: KeepAlive.Role,
      bytes: Bytes,
      limits: KeepAlive.Limits = KeepAlive.Limits()
  ): DecodeResult[KeepAlive.Message] =
    ProtocolWire.prefix {
      import KeepAlive.{Cookie, Message, Role, State}
      val bounds = limits.wire
      if state == State.Done then bad(ProtocolError.KeepAliveIsTerminal)
      if (state == State.Client) != (sender == Role.Client) then
        bad(ProtocolError.WrongKeepAliveAgency)
      val r = new BaseReader(bytes, bounds)
      val arity = r.arrayLength()
      if (state == State.Client && arity != 1 && arity != 2) ||
        (state == State.Server && arity != 2)
      then bad(ProtocolError.WrongKeepAliveOuterArityForState)
      val tag = r.uint().value
      val request = state == State.Client && arity == 2 && tag == 0
      val response = state == State.Server && arity == 2 && tag == 1
      val done = state == State.Client && arity == 1 && tag == 2
      if !request && !response && !done then bad(ProtocolError.UnknownKeepAliveTagStateOrExactArity)
      val message =
        if done then Message.Done
        else
          val value = r.uint().value
          if value > Cookie.Max then bad(ProtocolError.KeepAliveCookieOutsideUint16(value))
          val cookie = get(Cookie.from(value.toInt))
          if request then Message.Request(cookie) else Message.Response(cookie)
      get(KeepAlive.transition(state, sender, message))
      get(validateItem(bytes.take(r.position), bounds))
      DecodeResult.Decoded(message, r.position)
    }

  /** `BlockFetchEngine.decodePrefix` at the base with the node's codecs, `CardanoBlockFetch` 's
    * point and its payload codec of the default raw limits.
    */
  def blockFetch(
      state: BlockFetchEngine.State,
      sender: BlockFetchEngine.Role,
      bytes: Bytes,
      limits: BlockFetchEngine.Limits = BlockFetchEngine.Limits()
  ): DecodeResult[BlockFetchEngine.Message[Point, CardanoBlockFetch.RawNtNBlock]] =
    ProtocolWire.prefix {
      import BlockFetchEngine.{Message, Role, State}
      val raw = CardanoBlockFetch.RawLimits()
      val bounds = limits.wire(state)
      // Reject impossible agency even with empty input rather than waiting for bytes.
      if (state == State.Idle) != (sender == Role.Client) then
        bad(ProtocolError.WrongBlockFetchAgency)
      val r = new BaseReader(bytes, bounds)
      val arity = r.arrayLength()
      val possibleArity = state match
        case State.Idle      => arity == 1 || arity == 3
        case State.Busy      => arity == 1
        case State.Streaming => arity == 1 || arity == 2
        case State.Done      => false
      if !possibleArity then bad(ProtocolError.WrongBlockFetchOuterArityForState)
      val tag = r.uint().value
      val expected = tag match
        case n if n == 0                               => 3
        case n if n == 4                               => 2
        case n if n == 1 || n == 2 || n == 3 || n == 5 => 1
        case _                                         => bad(ProtocolError.UnknownBlockFetchTag)
      if arity != expected then bad(ProtocolError.WrongExactBlockFetchOuterArity)
      // Header-only state validation happens before any untrusted payload decode.
      val legal = (state, tag.toInt) match
        case (State.Idle, 0 | 1) | (State.Busy, 2 | 3) | (State.Streaming, 4 | 5) => true
        case _                                                                    => false
      if !legal then bad(ProtocolError.WrongBlockFetchState)
      def block(): CardanoBlockFetch.RawNtNBlock =
        if !raw.valid then bad(ProtocolError.InvalidRawBlockLimit(raw.maxRawBytes))
        if r.tag() != 24 then bad(ProtocolError.ExpectedSerializedBlockTag24)
        get(CardanoBlockFetch.RawNtNBlock.from(r.bytes(raw.maxRawBytes), raw.maxRawBytes))
      val message: Message[Point, CardanoBlockFetch.RawNtNBlock] = tag.toInt match
        case 0 => Message.RequestRange(r.point(), r.point())
        case 1 => Message.ClientDone
        case 2 => Message.StartBatch
        case 3 => Message.NoBlocks
        case 4 => Message.Block(block())
        case 5 => Message.BatchDone
        case _ => bad(ProtocolError.UnknownBlockFetchTag)
      get(BlockFetchEngine.transition(state, sender, message))
      get(validateItem(bytes.take(r.position), bounds))
      DecodeResult.Decoded(message, r.position)
    }

  def localTxMonitor(
      state: LocalTxMonitor.State,
      sender: Role,
      bytes: Bytes,
      txIds: LocalTxMonitor.TxIdReader = LocalTxMonitor.TxIdReader.whole
  ): DecodeResult[LocalTxMonitor.Message] =
    ltmDecoded(state, sender, bytes) match
      case DecodeResult.NeedMore => ltmWaiting(state, sender, bytes, txIds)
      case other                 => other

  private def ltmWaiting(
      state: LocalTxMonitor.State,
      sender: Role,
      bytes: Bytes,
      txIds: LocalTxMonitor.TxIdReader
  ): DecodeResult[LocalTxMonitor.Message] =
    import LocalTxMonitor.*
    ProtocolWire.prefix {
      val r = new BaseReader(
        bytes,
        ProtocolWire.Limits(
          MaxMessageBytes,
          MaxMessageBytes,
          32,
          ProtocolWire.MaxItems,
          ProtocolWire.MaxItems
        )
      )
      val arity = r.arrayLength()
      val tag = r.uint().value
      (state, sender, arity, tag.toInt) match
        case (State.Acquired, Role.Client, 2, 7) if tag == 7 =>
          txIds.prefix(bytes.drop(r.position)) match
            case TxIdPrefix.Short          => DecodeResult.NeedMore
            case TxIdPrefix.Refused(cause) => bad(ProtocolError.UndecodableTxId(cause))
        case _ => DecodeResult.NeedMore
    }

  private def ltmDecoded(
      state: LocalTxMonitor.State,
      sender: Role,
      bytes: Bytes
  ): DecodeResult[LocalTxMonitor.Message] =
    import LocalTxMonitor.*
    val MaxWord32 = BigInt(0xffffffffL)
    def clientTurn(state: State): Boolean = state == State.Idle || state == State.Acquired
    ProtocolWire.prefix {
      if state == State.Done || clientTurn(state) != (sender == Role.Client) then
        bad(ProtocolError.WrongLocalTxMonitorAgency)
      val r = new BaseReader(
        bytes,
        ProtocolWire.Limits(
          MaxMessageBytes,
          MaxMessageBytes,
          32,
          ProtocolWire.MaxItems,
          ProtocolWire.MaxItems
        )
      )
      val arity = r.arrayLength()
      val tag = r.uint().value
      def integer(): BigInt =
        if r.major == 6 then
          if r.tag() != 2 then bad(ProtocolError.ExpectedUint64)
          BigInt(1, r.bytes(MaxMessageBytes).toArray)
        else r.uint().value
      def word32(name: ProtocolError.MonitorField): Long =
        val v = r.uint().value
        if v > MaxWord32 then bad(ProtocolError.Word32Range(name, v))
        v.toLong
      val message: Message = (state, arity, tag.toInt) match
        case (State.Idle, 1, 0) if tag == 0              => Message.Done
        case (State.Idle, 1, 1) if tag == 1              => Message.Acquire
        case (State.Acquired, 1, 1) if tag == 1          => Message.AwaitAcquire
        case (State.Acquired, 1, 3) if tag == 3          => Message.Release
        case (State.Acquired, 1, 5) if tag == 5          => Message.NextTx
        case (State.Acquired, 2, 7) if tag == 7          => Message.HasTx(r.item())
        case (State.Acquired, 1, 9) if tag == 9          => Message.GetSizes
        case (State.Acquired, 1, 11) if tag == 11        => Message.GetMeasures
        case (State.Acquiring, 2, 2) if tag == 2         => Message.Acquired(r.uint().value)
        case (State.Busy(Kind.NextTx), 1, 6) if tag == 6 => Message.ReplyNextTx(None)
        case (State.Busy(Kind.NextTx), 2, 6) if tag == 6 => Message.ReplyNextTx(Some(r.item()))
        case (State.Busy(Kind.HasTx), 2, 8) if tag == 8  => Message.ReplyHasTx(r.bool())
        case (State.Busy(Kind.GetSizes), 2, 10) if tag == 10 =>
          if r.arrayLength() != 3 then bad(ProtocolError.SizesArity)
          Message.ReplyGetSizes(
            Sizes(
              word32(ProtocolError.MonitorField.CapacityInBytes),
              word32(ProtocolError.MonitorField.SizeInBytes),
              word32(ProtocolError.MonitorField.NumberOfTxs)
            )
          )
        case (State.Busy(Kind.GetMeasures), 3, 12) if tag == 12 =>
          val count = word32(ProtocolError.MonitorField.TxCount)
          val n = r.mapLength()
          val entries = (0 until n).toVector.map { _ =>
            val name = r.text(MaxMessageBytes)
            if r.arrayLength() != 2 then bad(ProtocolError.MeasureArity)
            name -> Measure(integer(), integer())
          }
          if entries.map(_._1).distinct.sizeIs != entries.size then
            bad(ProtocolError.DuplicateMeasureName)
          Message.ReplyGetMeasures(Measures(count, SortedMap.from(entries)))
        case _ => bad(ProtocolError.UnknownLocalTxMonitorTagOrArity)
      get(LocalTxMonitor.transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    }

  /** `LocalStateQueryEngine.decodePrefix` at the base with Cardano points, and the query reader of
    * the base given as its two judgements of a query prefix.
    */
  def localStateQuery(
      state: LocalStateQuery.State,
      sender: Role,
      bytes: Bytes,
      span: Bytes => LocalStateQuery.QuerySpan,
      prefix: Bytes => LocalStateQuery.QueryPrefix
  ): DecodeResult[LocalStateQueryEngine.Message[Point]] =
    lsqDecoded(state, sender, bytes, span) match
      case DecodeResult.NeedMore => lsqWaiting(state, sender, bytes, prefix)
      case other                 => other

  private def lsqLimits(sender: Role): ProtocolWire.Limits = sender match
    case Role.Client =>
      ProtocolWire.Limits(
        LocalStateQuery.MaxQueryBytes,
        LocalStateQuery.MaxQueryBytes,
        32,
        LocalStateQuery.MaxQueryDepth,
        LocalStateQuery.MaxQueryBytes,
        bound = ProtocolWire.Bound.Local
      )
    case Role.Server =>
      ProtocolWire.Limits(
        LocalStateQuery.MaxResultBytes,
        LocalStateQuery.MaxResultBytes,
        32,
        ProtocolWire.MaxItems,
        LocalStateQuery.MaxResultBytes,
        bound = ProtocolWire.Bound.Local
      )

  private def lsqWaiting(
      state: LocalStateQuery.State,
      sender: Role,
      bytes: Bytes,
      prefix: Bytes => LocalStateQuery.QueryPrefix
  ): DecodeResult[LocalStateQueryEngine.Message[Point]] =
    import LocalStateQuery.State
    ProtocolWire.prefix {
      val r = new BaseReader(bytes, lsqLimits(sender))
      val arity = r.arrayLength()
      val tag = r.uint().value
      (state, sender, arity, tag.toInt) match
        case (State.Acquired, Role.Client, 2, 3) if tag == 3 =>
          prefix(bytes.drop(r.position)) match
            case LocalStateQuery.QueryPrefix.Short => DecodeResult.NeedMore
            case LocalStateQuery.QueryPrefix.Refused(cause) =>
              bad(ProtocolError.UndecodableQueryPrefix(cause))
        case _ => DecodeResult.NeedMore
    }

  private def lsqDecoded(
      state: LocalStateQuery.State,
      sender: Role,
      bytes: Bytes,
      span: Bytes => LocalStateQuery.QuerySpan
  ): DecodeResult[LocalStateQueryEngine.Message[Point]] =
    import LocalStateQuery.{Failure, State}
    import LocalStateQueryEngine.{Message, Target}
    ProtocolWire.prefix {
      val clientTurn = state == State.Idle || state == State.Acquired
      if state == State.Done || clientTurn != (sender == Role.Client) then
        bad(ProtocolError.WrongLocalStateQueryAgency)
      val wire = lsqLimits(sender)
      val r = new BaseReader(bytes, wire)
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message = (arity, tag.toInt) match
        case (2, 0) if tag == 0   => Message.Acquire(Target.Specific(r.point()))
        case (1, 8) if tag == 8   => Message.Acquire(Target.VolatileTip)
        case (1, 10) if tag == 10 => Message.Acquire(Target.ImmutableTip)
        case (1, 1) if tag == 1   => Message.Acquired
        case (2, 2) if tag == 2 =>
          r.uint().value.toInt match
            case 0 => Message.Failed(Failure.PointTooOld)
            case 1 => Message.Failed(Failure.PointNotOnChain)
            case _ => bad(ProtocolError.UnknownLocalStateQueryFailure)
        case (2, 3) if tag == 3 =>
          Message.Query(span(bytes.drop(r.position)) match
            case LocalStateQuery.QuerySpan.Item         => r.item()
            case LocalStateQuery.QuerySpan.Prefix(size) => r.spanning(size)
            case LocalStateQuery.QuerySpan.Short        =>
              // what has arrived still meets the reader's checks, its depth among them
              r.spanning(bytes.size - r.position)
              bad(ProtocolError.TruncatedCbor)
            case LocalStateQuery.QuerySpan.Refused => bad(ProtocolError.UndecodableQuery))
        case (2, 4) if tag == 4   => Message.Result(r.item())
        case (1, 5) if tag == 5   => Message.Release
        case (2, 6) if tag == 6   => Message.ReAcquire(Target.Specific(r.point()))
        case (1, 9) if tag == 9   => Message.ReAcquire(Target.VolatileTip)
        case (1, 11) if tag == 11 => Message.ReAcquire(Target.ImmutableTip)
        case (1, 7) if tag == 7   => Message.Done
        case _                    => bad(ProtocolError.UnknownLocalStateQueryTagOrArity)
      get(LocalStateQueryEngine.transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    }

  /** `PeerSharing.decodePrefix` at the base: `PeerSharingEngine.decodeTyped` with the address codec
    * (`readAddress`), its peers read by the `peers` of the base.
    */
  def peerSharing(
      state: PeerSharing.State,
      sender: PeerSharing.Role,
      bytes: Bytes,
      limits: PeerSharing.Limits = PeerSharing.Limits()
  ): PeerSharing.Decoding[PeerSharingEngine.Message[PeerSharing.Address]] =
    import PeerSharing.{Address, Amount, Decoding, Error, Field, Port, State, Role, Word32}
    import PeerSharingEngine.{Cursor, Message, Parse, Read, Stop}
    def stopped[A](read: Read[A]): Parse[A] = StateT.liftF(read)
    def fail[A](error: Error): Read[A] = Left(Stop.Fail(error))
    def readAddress(c: Cursor): Parse[Address] =
      def word() = c.ranged(Field.Word32, BigInt(Word32.Max)).map(Word32.trusted)
      def port() = c.ranged(Field.Port, BigInt(Port.Max)).map(Port.trusted)
      for
        _ <- c.definiteArray()
        tag <- c.ranged(Field.AddressTag, BigInt(1))
        decoded <- tag match
          case 0 =>
            for
              a <- word()
              p <- port()
            yield Address.V4(a, p)
          case _ =>
            for
              a <- word()
              b <- word()
              d <- word()
              e <- word()
              p <- port()
            yield Address.V6(a, b, d, e, p)
      yield decoded
    def peers(c: Cursor): Parse[Vector[Address]] =
      c.arrayOrIndefinite().flatMap {
        case Some(n) =>
          (n, Vector.empty[Address]).tailRecM { (left, acc) =>
            if left == 0 then stopped(Right(Right(acc)))
            else readAddress(c).map(a => Left((left - 1, acc :+ a)))
          }
        case None =>
          Vector.empty[Address].tailRecM { acc =>
            c.peekBreak().flatMap {
              case true  => c.break().as(Right(acc))
              case false => readAddress(c).map(a => Left(acc :+ a))
            }
          }
      }
    val cursor = new Cursor(bytes, limits.maxMessageBytes)
    val agency = state match
      case State.Done => Left(Error.Terminal)
      case State.Idle => Either.cond(sender == Role.Client, (), Error.WrongAgency(state, sender))
      case State.Busy => Either.cond(sender == Role.Server, (), Error.WrongAgency(state, sender))
    val message: Parse[Message[Address]] =
      for
        _ <- cursor.definiteArray()
        key <- cursor.uint()
        message <- (state, key) match
          case (State.Idle, k) if k == 0 =>
            cursor
              .ranged(Field.Amount, BigInt(Amount.Max))
              .map(amount => Message.ShareRequest(Amount.trusted(amount)))
          case (State.Busy, k) if k == 1 => peers(cursor).map(Message.SharePeers(_))
          case (State.Idle, k) if k == 2 => stopped(Right(Message.Done))
          case _                         => stopped(fail(Error.UnexpectedKey(state, key)))
      yield message
    agency.left.map(Stop.Fail(_)).flatMap(_ => message.run(0)) match
      case Right((used, message)) => Decoding.Decoded(message, used)
      case Left(Stop.Short)       => Decoding.NeedMore
      case Left(Stop.Fail(error)) => Decoding.Failed(error)

/** TEST-ONLY ORACLE: the handshake's decoder as it was at 3cad1f045 ([[BaseDecoders]]): its parser
  * verbatim but for its name, and the message read from the term by `Handshake.message`, which did
  * not change.
  */
private[scarab] object BaseHandshake:
  import Handshake.{
    DecodeError,
    MaxMessageBytes,
    State,
    Term,
    VersionCodec,
    WireMessage,
    WireReason
  }

  def decodePrefix[V: VersionCodec: Ordering](
      state: State,
      bytes: Bytes
  ): Either[DecodeError, (WireMessage[V], Bytes)] =
    new Parser(bytes)
      .message(state)
      .flatMap((end, term) => attempt((Handshake.message[V](state, term), bytes.drop(end))))
      .left
      .map(e => DecodeError(e, e.incomplete))

  def decodeReason[V: VersionCodec](bytes: Bytes): Either[DecodeError, WireReason[V]] =
    new Parser(bytes)
      .reason(0)
      .flatMap((end, term) =>
        if end != bytes.size then Left(ProtocolError.TrailingBytes)
        else attempt(Handshake.reason[V](term))
      )
      .left
      .map(e => DecodeError(e, e.incomplete))

  /** A container the parser has opened and not yet closed: the items read so far, and how many it
    * still needs (a definite one) or that a break closes it (an indefinite one).
    */
  private enum Open:
    case Items(remaining: Int, done: Vector[Term])
    case OpenItems(done: Vector[Term])
    case Entries(remaining: Int, done: Vector[(Term, Term)], key: Option[Term])
    case OpenEntries(done: Vector[(Term, Term)], key: Option[Term])
    case Tagged(number: BigInt)

  /** What one head starts: a whole term, or a container whose items follow it. */
  private enum Start:
    case Whole(term: Term)
    case Container(open: Open)

  /** A container with one more item: closed into its term, or still open. */
  private enum Added:
    case Closed(term: Term)
    case Still(open: Open)

  private def add(open: Open, item: Term): Added = open match
    case Open.Items(1, done)            => Added.Closed(Term.Arr(done :+ item))
    case Open.Items(remaining, done)    => Added.Still(Open.Items(remaining - 1, done :+ item))
    case Open.OpenItems(done)           => Added.Still(Open.OpenItems(done :+ item))
    case Open.Entries(n, done, None)    => Added.Still(Open.Entries(n, done, Some(item)))
    case Open.Entries(1, done, Some(k)) => Added.Closed(Term.Map(done :+ (k -> item)))
    case Open.Entries(n, done, Some(k)) =>
      Added.Still(Open.Entries(n - 1, done :+ (k -> item), None))
    case Open.OpenEntries(done, None)    => Added.Still(Open.OpenEntries(done, Some(item)))
    case Open.OpenEntries(done, Some(k)) => Added.Still(Open.OpenEntries(done :+ (k -> item), None))
    case Open.Tagged(number)             => Added.Closed(Term.Tag(number, item))

  /** Handshake negotiation is infrequent: the parser reads immutable input and keeps its open
    * containers in a list on the heap, so the depth of a term is bounded by the message's bytes (a
    * level takes one) and not by the thread's stack. A position at or past [[MaxMessageBytes]] is
    * refused as the driver refuses a message longer than its limit (`runDecoderWithLimit`,
    * `ouroboros-network/framework/lib/Ouroboros/Network/Driver/Limits.hs:295-331`, c45735a5).
    */
  private final class Parser(input: Bytes):
    private def byte(at: Int): Either[ProtocolError, Int] =
      if at >= MaxMessageBytes then Left(ProtocolError.HandshakeByteLimitExceeded)
      else input.lift(at).toRight(ProtocolError.IncompleteCbor).map(_ & 255)

    private def isBreak(at: Int): Boolean = input.lift(at).contains(0xff.toByte)

    /** The argument of a head whose additional information is `ai`, read from `at`: the position
      * after it and its value.
      */
    private def argument(ai: Int, at: Int): Either[ProtocolError, (Int, BigInt)] =
      if ai < 24 then Right((at, BigInt(ai)))
      else
        val width = ai match
          case 24 => Right(1)
          case 25 => Right(2)
          case 26 => Right(4)
          case 27 => Right(8)
          case _  => Left(ProtocolError.ReservedCBORArgument)
        @tailrec def go(width: Int, i: Int, value: BigInt): Either[ProtocolError, (Int, BigInt)] =
          if i == width then Right((at + width, value))
          else
            byte(at + i) match
              case Left(error) => Left(error)
              case Right(b)    => go(width, i + 1, (value << 8) | b)
        width.flatMap(go(_, 0, BigInt(0)))

    /** A declared count of items of at least `per` bytes each, from `at`: one the message cannot
      * hold could never be read within its bound, and is refused at once.
      */
    private def fits(count: BigInt, per: Int, at: Int): Either[ProtocolError, Int] =
      if count.isValidInt && count.toInt <= (MaxMessageBytes - at) / per then Right(count.toInt)
      else Left(ProtocolError.HandshakeByteLimitExceeded)

    private def utf8(bytes: Bytes): Either[ProtocolError, String] =
      Either
        .catchOnly[CharacterCodingException](
          StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toArray))
            .toString
        )
        .leftMap(_ => ProtocolError.InvalidUTF8)

    /** A definite string of `major` (each chunk of a text is UTF-8 itself). */
    private def chunk(major: Int, ai: Int, at: Int): Either[ProtocolError, (Int, Bytes)] =
      for
        (start, declared) <- argument(ai, at)
        length <- fits(declared, 1, start)
        _ <- Either.cond(length <= input.size - start, (), ProtocolError.IncompleteCbor)
        bytes = input.slice(start, start + length)
        _ <- if major == 3 then utf8(bytes).void else Right(())
      yield (start + length, bytes)

    /** A definite string, or an indefinite one as its chunks, the empty ones dropped as cborg's
      * `decodeBytesIndefLen` and `decodeStringIndefLen` drop them (`Term.hs:203-216`, cborg
      * 0.2.10.0).
      */
    private def string(major: Int, ai: Int, at: Int): Either[ProtocolError, (Int, Term)] =
      // the chunks of an indefinite string up to its break
      @tailrec def chunks(
          position: Int,
          collected: Vector[Bytes]
      ): Either[ProtocolError, (Int, Vector[Bytes])] =
        if isBreak(position) then byte(position).as((position + 1, collected))
        else
          val next = byte(position).flatMap { head =>
            if head / 32 != major || (head & 31) == 31 then
              Left(ProtocolError.InvalidIndefiniteStringChunk)
            else chunk(major, head & 31, position + 1)
          }
          next match
            case Left(error) => Left(error)
            case Right((after, bytes)) =>
              chunks(after, if bytes.isEmpty then collected else collected :+ bytes)
      if ai != 31 then
        chunk(major, ai, at).flatMap((end, bytes) =>
          if major == 2 then Right((end, Term.ByteString(bytes)))
          else utf8(bytes).map(text => (end, Term.Text(text)))
        )
      else
        chunks(at, Vector.empty).flatMap((end, parts) =>
          if major == 2 then Right((end, Term.ByteChunks(parts)))
          else parts.traverse(utf8).map(texts => (end, Term.TextChunks(texts)))
        )

    /** Major type 7 as cborg's `decodeTerm` reads it (`Term.hs:192-198`, cborg 0.2.10.0, with the
      * token types of `Read.hs:1492-1501`): `0xf8` and the byte after it are a simple value of any
      * value, those below 32 included (`tryConsumeSimple`, `Read.hs:2457`), and `0xf4` and `0xf5`
      * the booleans.
      */
    private def simple(ai: Int, at: Int): Either[ProtocolError, (Int, Term)] = ai match
      case 20 => Right((at, Term.Bool(false)))
      case 21 => Right((at, Term.Bool(true)))
      case 25 | 26 | 27 =>
        argument(ai, at).map((next, bits) =>
          (next, Term.FloatBits(if ai == 25 then 2 else if ai == 26 then 4 else 8, bits))
        )
      case 31           => Left(ProtocolError.UnexpectedBreak)
      case 28 | 29 | 30 => Left(ProtocolError.ReservedCBORSimpleValue)
      case 24           => byte(at).map(n => (at + 1, Term.Simple(n)))
      case n            => Right((at, Term.Simple(n)))

    /** The head at `at` and what it starts. */
    private def start(at: Int): Either[ProtocolError, (Int, Start)] =
      byte(at).flatMap { head =>
        val major = head / 32
        val ai = head & 31
        val after = at + 1
        def whole(read: Either[ProtocolError, (Int, Term)]) =
          read.map((next, term) => (next, Start.Whole(term)))
        major match
          case 0     => whole(argument(ai, after).map((next, n) => (next, Term.Integer(n))))
          case 1     => whole(argument(ai, after).map((next, n) => (next, Term.Integer(-1 - n))))
          case 2 | 3 => whole(string(major, ai, after))
          case 4 if ai == 31 => Right((after, Start.Container(Open.OpenItems(Vector.empty))))
          case 4 =>
            for
              (next, declared) <- argument(ai, after)
              n <- fits(declared, 1, next)
            yield
              if n == 0 then (next, Start.Whole(Term.Arr(Vector.empty)))
              else (next, Start.Container(Open.Items(n, Vector.empty)))
          case 5 if ai == 31 =>
            Right((after, Start.Container(Open.OpenEntries(Vector.empty, None))))
          case 5 =>
            for
              (next, declared) <- argument(ai, after)
              n <- fits(declared, 2, next)
            yield
              if n == 0 then (next, Start.Whole(Term.Map(Vector.empty)))
              else (next, Start.Container(Open.Entries(n, Vector.empty, None)))
          case 6 => argument(ai, after).map((next, n) => (next, Start.Container(Open.Tagged(n))))
          case _ => whole(simple(ai, after))
      }

    /** One term from `at`: the position after it, and the term. */
    def term(at: Int): Either[ProtocolError, (Int, Term)] =
      // `open`: the containers open around the position, innermost first; `read`: a term just
      // read, for the innermost to take
      @tailrec def go(
          position: Int,
          open: List[Open],
          read: Option[Term]
      ): Either[ProtocolError, (Int, Term)] = read match
        case Some(term) =>
          open match
            case Nil => Right((position, term))
            case inner :: outer =>
              add(inner, term) match
                case Added.Closed(closed) => go(position, outer, Some(closed))
                case Added.Still(still)   => go(position, still :: outer, None)
        case None =>
          open match
            case Open.OpenItems(done) :: outer if isBreak(position) =>
              byte(position) match
                case Left(error) => Left(error)
                case Right(_)    => go(position + 1, outer, Some(Term.Arr(done, false)))
            case Open.OpenEntries(done, None) :: outer if isBreak(position) =>
              byte(position) match
                case Left(error) => Left(error)
                case Right(_)    => go(position + 1, outer, Some(Term.Map(done, false)))
            case _ =>
              start(position) match
                case Left(error)                             => Left(error)
                case Right((next, Start.Whole(term)))        => go(next, open, Some(term))
                case Right((next, Start.Container(started))) => go(next, started :: open, None)
      go(at, Nil, None)

    /** `count` terms from `at`, in order. */
    @tailrec private def terms(
        at: Int,
        count: Int,
        done: Vector[Term]
    ): Either[ProtocolError, (Int, Vector[Term])] =
      if done.sizeIs == count then Right((at, done))
      else
        term(at) match
          case Left(error)         => Left(error)
          case Right((next, item)) => terms(next, count, done :+ item)

    /** The length of the definite array whose head is at `at`, if the item there is one: the
      * position after its head and the length, read as cborg's `decodeListLen` reads it, into an
      * `Int` (`tryConsumeListLen`, `cborg/src/Codec/CBOR/Read.hs:1925-1964`, with `word64ToInt`,
      * `Codec/CBOR/Magic.hs:506-519`, cborg 0.2.10.0, on a 64-bit machine).
      */
    private def listLength(at: Int): Either[ProtocolError, Option[(Int, Long)]] =
      byte(at).flatMap { head =>
        if head / 32 != 4 || (head & 31) == 31 then Right(None)
        else
          argument(head & 31, at + 1).map((next, n) => Option.when(n.isValidLong)((next, n.toLong)))
      }

    /** A refusal's reason as `decodeRefuseReason` reads it (`Codec.hs:264-288`, ouroboros-network
      * c45735a5): the length of its array is read and ignored (268), then its tag (269), then the
      * items the tag names: the list of versions for 0 (271-277), a version and a text for 1 and 2
      * (278-287). An item the array declares beyond them is left to whatever follows. Any other tag
      * is refused (288) once the items its array declares are read, and an item that is no definite
      * array is read as a term and refused when the reason is checked.
      */
    def reason(at: Int): Either[ProtocolError, (Int, Term)] =
      listLength(at).flatMap {
        case None => term(at)
        case Some((next, declared)) =>
          def named(tag: Term, after: Int): Either[ProtocolError, Int] = tag match
            case Term.Integer(t) if t == 0           => Right(1)
            case Term.Integer(t) if t == 1 || t == 2 => Right(2)
            case _ => fits(BigInt(math.max(declared - 1, 0L)), 1, after)
          for
            (afterTag, tag) <- term(next)
            count <- named(tag, afterTag)
            (end, items) <- terms(afterTag, count, Vector.empty)
          yield (end, Term.Arr(tag +: items))
      }

    /** The message from the start of the input. In `StConfirm` a message of two items whose key is
      * 2 is a refusal (`Codec.hs:177-178`, ouroboros-network c45735a5), and its reason is read as
      * [[reason]] reads it; anything else is one term, checked when it is read as a message.
      */
    def message(state: State): Either[ProtocolError, (Int, Term)] =
      if state != State.Confirm then term(0)
      else
        listLength(0).flatMap {
          case Some((next, length)) if length == 2 =>
            term(next).flatMap {
              case (afterKey, key @ Term.Integer(k)) if k == 2 =>
                reason(afterKey).map((end, r) => (end, Term.Arr(Vector(key, r))))
              case (afterKey, key) =>
                term(afterKey).map((end, item) => (end, Term.Arr(Vector(key, item))))
            }
          case _ => term(0)
        }
