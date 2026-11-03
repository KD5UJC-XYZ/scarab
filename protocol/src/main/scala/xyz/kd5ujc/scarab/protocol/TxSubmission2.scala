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

  /** How the transactions of the era a codec carries are read: the reference's TxSubmission2 codec
    * takes the transaction decoder of the block type (`codecTxSubmission2` over `decodeNodeToNode`,
    * `Ouroboros/Consensus/Network/NodeToNode.hs`, ouroboros-consensus 82ecba32), which for a
    * `GenTx` is the era's own after the hard-fork combinator's era index (`dispatchDecoder`,
    * `HardFork/Combinator/Serialisation/SerialiseNodeToNode.hs:80-109,156-159`), and for a
    * Shelley-based era is the ledger's decoder of the transaction inside CBOR-in-CBOR
    * (`unwrapCBORinCBOR (eraDecoder @era decCBOR)`, `Shelley/Ledger/Mempool.hs:261-266`). This
    * codec reads only Conway's index, so the reader is that of Conway's transactions; the ledger
    * that knows their structure supplies it, as the protocols here do not.
    */
  trait TransactionReader:
    /** The bytes of the body of the transaction `original`, as the era's decoder reads them: the
      * transaction id is their hash. A refusal where `original` is not a transaction those decoders
      * frame.
      */
    def body(original: Bytes): Either[ProtocolError, Bytes]

  private def profile(reader: TransactionReader) = new TxSubmission2Engine.Profile[Bytes, Bytes]:
    def maxBatch: Int = MaxBatch
    def maxOriginalBytes: Int = MaxOriginalBytes
    def maxAdvertisedSize: Long = MaxOriginalBytes.toLong + 16L
    def maxMessageBytes(state: State): Int = TxSubmission2.maxMessageBytes(state)
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
      original(value, reader)
      writer.head(4, 2); writer.head(0, 6); writer.head(6, 24)
      writer.head(2, value.size); writer.raw(value)
    def readBody(in: ProtocolWire.Reader)(using ProtocolWire.Scope): Bytes =
      if in.arrayLength() != 2 || in.uint().value != 6 || in.tag() != 24 then
        bad(ProtocolError.ExpectedConwayGenTx)
      val value = in.bytes(MaxOriginalBytes); original(value, reader); value

  /** The codec of Conway's `GenTx`, its transactions read by `reader`. */
  private[scarab] def codec(reader: TransactionReader): TxSubmission2Engine.Codec[Bytes, Bytes] =
    TxSubmission2Engine.codec(profile(reader), advertisedSize, bodyId(_)(using reader))

  /** TxSubmission2/Codec.hs:50-63: Init and Idle use smallByteLimit. The remaining active states
    * retain the stricter local eight-transaction profile rather than upstream's 2500000 bytes.
    */
  def maxMessageBytes(state: State): Int = state match
    case State.Init | State.Idle  => 65535
    case State.Ids(_) | State.Txs => MaxMessageBytes
    case State.Done               => 0

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

  /** The transaction id of `original`, the hash of its body as `reader` reads it, within the size
    * this codec carries; admission remains the caller's responsibility.
    */
  def bodyId(original: Bytes)(using reader: TransactionReader): Either[ProtocolError, Bytes] =
    advertisedSize(original.size).flatMap(_ => reader.body(original)).map(Blake2b.hash256.hash)

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
  private def original(bytes: Bytes, reader: TransactionReader)(using ProtocolWire.Scope): Unit =
    get(advertisedSize(bytes.size))
    get(reader.body(bytes))

  def encode(state: State, sender: Role, message: Message)(using
      TransactionReader
  ): Either[ProtocolError, Bytes] =
    codec(summon[TransactionReader]).encode(state, sender, toEngine(message))

  def decodePrefix(state: State, sender: Role, bytes: Bytes)(using
      TransactionReader
  ): ChainSync.DecodeResult[Message] =
    codec(summon[TransactionReader]).decodePrefix(state, sender, bytes) match
      case ChainSync.DecodeResult.NeedMore      => ChainSync.DecodeResult.NeedMore
      case ChainSync.DecodeResult.Failed(error) => ChainSync.DecodeResult.Failed(error)
      case ChainSync.DecodeResult.Decoded(message, consumed) =>
        ChainSync.DecodeResult.Decoded(fromEngine(message), consumed)

  def decode(state: State, sender: Role, bytes: Bytes)(using
      TransactionReader
  ): Either[ProtocolError, Message] =
    codec(summon[TransactionReader]).decodePrefix(state, sender, bytes) match
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
