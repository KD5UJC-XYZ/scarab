// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.Role
import ProtocolWire.{attempt, bad, get}

/** Conway-only publisher profile. Source pins and local policy: docs/design/network-limits.md. */
object TxSubmission2:
  val MiniProtocolId = 4
  val MaxOriginalBytes = 65536
  val MaxBatch = 8
  val MaxMessageBytes = 524400
  type State = TxSubmission2Engine.State
  val State = TxSubmission2Engine.State
  enum Message:
    case Init
    case RequestIds(blocking: Boolean, acknowledge: Int, request: Int)
    case ReplyIds(offers: Vector[RelayOffer])
    case RequestTxs(ids: Vector[Bytes])
    case ReplyTxs(originals: Vector[Bytes])
    case Done

  private[scarab] def toEngine(message: Message): TxSubmission2Engine.Message[Bytes, Bytes] =
    message match
      case Message.Init => TxSubmission2Engine.Message.Init
      case Message.RequestIds(blocking, acknowledge, request) =>
        TxSubmission2Engine.Message.RequestIds(blocking, acknowledge, request)
      case Message.ReplyIds(offers) =>
        TxSubmission2Engine.Message.ReplyIds(
          offers.map(o => TxSubmission2Engine.Offer(o.transactionId, o.advertisedSize))
        )
      case Message.RequestTxs(ids)     => TxSubmission2Engine.Message.RequestTxs(ids)
      case Message.ReplyTxs(originals) => TxSubmission2Engine.Message.ReplyTxs(originals)
      case Message.Done                => TxSubmission2Engine.Message.Done

  private[scarab] def fromEngine(message: TxSubmission2Engine.Message[Bytes, Bytes]): Message =
    message match
      case TxSubmission2Engine.Message.Init => Message.Init
      case TxSubmission2Engine.Message.RequestIds(blocking, acknowledge, request) =>
        Message.RequestIds(blocking, acknowledge, request)
      case TxSubmission2Engine.Message.ReplyIds(offers) =>
        Message.ReplyIds(offers.map(o => RelayOffer(o.transactionId, o.advertisedSize)))
      case TxSubmission2Engine.Message.RequestTxs(ids)     => Message.RequestTxs(ids)
      case TxSubmission2Engine.Message.ReplyTxs(originals) => Message.ReplyTxs(originals)
      case TxSubmission2Engine.Message.Done                => Message.Done
  private val wire = ProtocolWire.Limits(
    MaxMessageBytes,
    MaxOriginalBytes,
    32,
    ProtocolWire.MaxItems,
    ProtocolWire.MaxItems
  )

  private val conwayProfile = new TxSubmission2Engine.Profile[Bytes, Bytes]:
    def maxBatch: Int = MaxBatch
    def maxOriginalBytes: Int = MaxOriginalBytes
    def maxAdvertisedSize: Long = MaxOriginalBytes.toLong + 16L
    def maxMessageBytes(state: State): Int = state match
      case State.Init | State.Idle  => 65535
      case State.Ids(_) | State.Txs => MaxMessageBytes
      case State.Done               => 0
    def writeId(value: Bytes, state: State, writer: ProtocolWire.Writer)(using
        ProtocolWire.Scope
    ): Unit =
      checkId(value); writer.head(4, 2); writer.head(0, 6); writer.head(2, 32); writer.raw(value)
    def readId(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): Bytes =
      if reader.arrayLength() != 2 || reader.uint().value != 6 then
        bad(ProtocolError.ExpectedConwayTransactionID)
      val result = reader.bytes(32); checkId(result); result
    def validateOffer(offer: TxSubmission2Engine.Offer[Bytes])(using ProtocolWire.Scope): Unit =
      checkId(offer.transactionId)
      if offer.advertisedSize <= 0 || offer.advertisedSize > 0xffffffffL then
        bad(ProtocolError.SizeOutsideUint32)
    def writeBody(value: Bytes, state: State, writer: ProtocolWire.Writer)(using
        ProtocolWire.Scope
    ): Unit =
      original(value)
      writer.head(4, 2); writer.head(0, 6); writer.head(6, 24)
      writer.head(2, value.size); writer.raw(value)
    def readBody(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): Bytes =
      if reader.arrayLength() != 2 || reader.uint().value != 6 || reader.tag() != 24 then
        bad(ProtocolError.ExpectedConwayGenTx)
      val value = reader.bytes(MaxOriginalBytes); original(value); value

  private[scarab] val conwayCodec: TxSubmission2Engine.Codec[Bytes, Bytes] =
    TxSubmission2Engine.codec(conwayProfile, advertisedSize, bodyId)

  /** TxSubmission2/Codec.hs:50-63: Init and Idle use smallByteLimit. The remaining active states
    * retain the stricter local eight-transaction profile rather than upstream's 2500000 bytes.
    */
  def maxMessageBytes(state: State): Int = conwayProfile.maxMessageBytes(state)

  def transition(state: State, sender: Role, message: Message): Either[ProtocolError, State] =
    TxSubmission2Engine.transition(state, sender, toEngine(message))

  /** Size of the actual Conway GenTx encoding, excluding ReplyTxs list/message/mux framing. */
  def advertisedSize(originalBytes: Int): Either[ProtocolError, Long] =
    if originalBytes <= 0 || originalBytes > MaxOriginalBytes then
      Left(ProtocolError.OriginalSizeOutsidePolicy(originalBytes, MaxOriginalBytes))
    else
      Right(
        originalBytes.toLong + 4 + (if originalBytes < 24 then 1
                                    else if originalBytes <= 255 then 2
                                    else if originalBytes <= 65535 then 3
                                    else 5)
      )

  /** Structural identity only; admission remains the caller's responsibility. */
  def bodyId(original: Bytes): Either[ProtocolError, Bytes] = attempt {
    get(advertisedSize(original.size))
    val r = new ProtocolWire.Reader(original, wire.copy(maxMessageBytes = MaxOriginalBytes))
    val indefinite = (original(0) & 255) == 0x9f
    if indefinite then r.read()
    else if r.arrayLength() != 4 then bad(ProtocolError.ExpectedFourMemberConwayTransaction)
    val body = r.item()
    if body.size == 0 || (body(0) & 0xe0) != 0xa0 then bad(ProtocolError.TransactionBodyIsNotAMap)
    r.item()
    val validity = r.read()
    if validity != 0xf4 && validity != 0xf5 then bad(ProtocolError.TransactionValidityIsNotABoolean)
    r.item()
    if indefinite && r.read() != 0xff then
      bad(ProtocolError.ExpectedBreakAfterFourthTransactionMember)
    if r.position != original.size then bad(ProtocolError.TrailingOriginalTransactionBytes)
    Blake2b.hash256.hash(body)
  }

  private def checkId(id: Bytes)(using ProtocolWire.Scope): Unit =
    if id.size != 32 then bad(ProtocolError.TransactionIDMustBe32Bytes(id.size))
  private def checkOffer(o: RelayOffer)(using ProtocolWire.Scope): Unit =
    checkId(o.transactionId)
    if o.advertisedSize <= 0 || o.advertisedSize > 0xffffffffL then
      bad(ProtocolError.SizeOutsideUint32)
  private def count(n: Int)(using ProtocolWire.Scope): Unit =
    if n < 0 || n > 65535 then bad(ProtocolError.CountOutsideUint16(BigInt(n)))
  private def batch(n: Int)(using ProtocolWire.Scope): Unit =
    if n > MaxBatch then bad(ProtocolError.BatchLimitExceeded(n, MaxBatch))
  private def original(bytes: Bytes)(using ProtocolWire.Scope): Unit =
    get(advertisedSize(bytes.size))
    get(ProtocolWire.validateItem(bytes, wire.copy(maxMessageBytes = MaxOriginalBytes)))

  def encode(state: State, sender: Role, message: Message): Either[ProtocolError, Bytes] =
    conwayCodec.encode(state, sender, toEngine(message))

  def decodePrefix(state: State, sender: Role, bytes: Bytes): ChainSync.DecodeResult[Message] =
    conwayCodec.decodePrefix(state, sender, bytes) match
      case ChainSync.DecodeResult.NeedMore      => ChainSync.DecodeResult.NeedMore
      case ChainSync.DecodeResult.Failed(error) => ChainSync.DecodeResult.Failed(error)
      case ChainSync.DecodeResult.Decoded(message, consumed) =>
        ChainSync.DecodeResult.Decoded(fromEngine(message), consumed)

  def decode(state: State, sender: Role, bytes: Bytes): Either[ProtocolError, Message] =
    conwayCodec.decodePrefix(state, sender, bytes) match
      case ChainSync.DecodeResult.NeedMore      => Left(ProtocolError.TruncatedTxSubmission2Message)
      case ChainSync.DecodeResult.Failed(error) => Left(error)
      case ChainSync.DecodeResult.Decoded(message, n) =>
        if n == bytes.size then Right(fromEngine(message))
        else Left(ProtocolError.TrailingTxSubmission2Bytes)

  /** One finite lease. Ack removes oldest outstanding IDs, never implies acceptance. */
  final case class Inventory private (
      remaining: Vector[RelayOffer],
      outstanding: Vector[RelayOffer],
      requested: Set[Bytes]
  ):
    def requestIds(
        blocking: Boolean,
        ack: Int,
        request: Int
    ): Either[ProtocolError, (Inventory, Message, Vector[Bytes])] = attempt {
      count(ack); count(request)
      if ack > outstanding.size then
        bad(ProtocolError.AckExceedsOutstandingInventory(ack, outstanding.size))
      val retained = outstanding.drop(ack)
      if blocking && (request == 0 || retained.nonEmpty) then
        bad(ProtocolError.InvalidBlockingInventoryRequest)
      if !blocking && (retained.isEmpty || (ack == 0 && request == 0)) then
        bad(ProtocolError.InvalidNonblockingInventoryRequest)
      val offered = remaining.take(request)
      val reply = if blocking && offered.isEmpty then Message.Done else Message.ReplyIds(offered)
      (
        copy(remaining = remaining.drop(offered.size), outstanding = retained ++ offered),
        reply,
        outstanding.take(ack).map(_.transactionId)
      )
    }
    def requestTxs(ids: Vector[Bytes]): Either[ProtocolError, Inventory] = attempt {
      batch(ids.size)
      if ids.distinct.size != ids.size then bad(ProtocolError.DuplicateBodyRequest)
      val known = outstanding.map(_.transactionId).toSet
      if ids.exists(id => !known(id) || requested(id)) then
        bad(ProtocolError.UnadvertisedAcknowledgedOrRepeatedBodyRequest)
      copy(requested = requested ++ ids)
    }
  object Inventory:
    def create(offers: Vector[RelayOffer]): Either[ProtocolError, Inventory] = attempt {
      batch(offers.size); offers.foreach(checkOffer)
      if offers.map(_.transactionId).distinct.size != offers.size then
        bad(ProtocolError.DuplicateOfferID)
      Inventory(offers, Vector.empty, Set.empty)
    }

/** Wire size includes the Conway era and CBOR-in-CBOR envelope, not mux/message overhead. */
final case class RelayOffer(transactionId: Bytes, advertisedSize: Long)
