// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ProtocolWire.{attempt, bad, get}
import ChainSync.{DecodeResult, Role}

/** Shared typed TxSubmission2 agency and envelope codec. Profiles own the protocol-specific ID and
  * transaction representations; the state machine and outer wire format are common.
  */
private[scarab] object TxSubmission2Engine:
  enum State:
    case Init, Idle, Txs, Done
    case Ids(blocking: Boolean)

  enum Message[+I, +B]:
    case Init
    case RequestIds(blocking: Boolean, acknowledge: Int, request: Int)
    case ReplyIds(offers: Vector[Offer[I]])
    case RequestTxs(ids: Vector[I])
    case ReplyTxs(originals: Vector[B])
    case Done

  final case class Offer[+I](transactionId: I, advertisedSize: Long)

  trait Profile[I, B]:
    def maxBatch: Int
    def maxOriginalBytes: Int
    def maxAdvertisedSize: Long
    def maxMessageBytes(state: State): Int
    def writeId(value: I, state: State, writer: ProtocolWire.Writer)(using ProtocolWire.Scope): Unit
    def readId(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): I
    def validateOffer(offer: Offer[I])(using ProtocolWire.Scope): Unit
    def writeBody(value: B, state: State, writer: ProtocolWire.Writer)(using
        ProtocolWire.Scope
    ): Unit
    def readBody(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): B

  trait Codec[I, B]:
    def maxBatch: Int
    def maxOriginalBytes: Int
    def maxAdvertisedSize: Long
    def maxMessageBytes(state: State): Int
    def advertisedSize(originalBytes: Int): Either[ProtocolError, Long]
    def bodyId(original: B): Either[ProtocolError, I]
    def encode(state: State, sender: Role, message: Message[I, B]): Either[ProtocolError, Bytes]
    def decodePrefix(
        state: State,
        sender: Role,
        bytes: Bytes
    ): DecodeResult[Message[I, B]]

  private[protocol] def codec[I, B](
      profile: Profile[I, B],
      size: Int => Either[ProtocolError, Long],
      identity: B => Either[ProtocolError, I]
  ): Codec[I, B] = new Codec[I, B]:
    def maxBatch: Int = profile.maxBatch
    def maxOriginalBytes: Int = profile.maxOriginalBytes
    def maxAdvertisedSize: Long = profile.maxAdvertisedSize
    def maxMessageBytes(state: State): Int = profile.maxMessageBytes(state)
    def advertisedSize(originalBytes: Int): Either[ProtocolError, Long] = size(originalBytes)
    def bodyId(original: B): Either[ProtocolError, I] = identity(original)
    def encode(state: State, sender: Role, message: Message[I, B]) =
      TxSubmission2Engine.encode(state, sender, message, profile)
    def decodePrefix(state: State, sender: Role, bytes: Bytes) =
      TxSubmission2Engine.decodePrefix(state, sender, bytes, profile)

  /** Construct a typed profile from exact single-item codecs. The protocol engine owns the CBOR
    * envelope and enforces the current protocol state's byte bounds around each encoded item.
    */
  def fromCodecs[I, B](
      maxBatch: Int,
      maxOriginalBytes: Int,
      maxAdvertisedSize: Long,
      maxMessageBytes: State => Int,
      encodeId: I => Either[ProtocolError, Bytes],
      decodeId: Bytes => Either[ProtocolError, I],
      encodeBody: B => Either[ProtocolError, Bytes],
      decodeBody: Bytes => Either[ProtocolError, B],
      checkOffer: Offer[I] => Either[ProtocolError, Unit],
      advertisedSize: Int => Either[ProtocolError, Long],
      bodyId: B => Either[ProtocolError, I]
  ): Either[ProtocolError, Codec[I, B]] =
    val states =
      Vector(State.Init, State.Idle, State.Ids(false), State.Ids(true), State.Txs, State.Done)
    val stateLimits = states.map(state => state -> maxMessageBytes(state)).toMap
    val validMessageLimits = stateLimits.forall { case (state, limit) =>
      if state == State.Done then limit >= 0 else limit > 0
    }
    if maxBatch <= 0 || maxBatch > 65535 || maxOriginalBytes <= 0 || maxAdvertisedSize <= 0 ||
      maxAdvertisedSize > 0xffffffffL || !validMessageLimits
    then Left(ProtocolError.InvalidTxSubmission2Limits)
    else
      val batchLimit = maxBatch
      val originalLimit = maxOriginalBytes
      val relayLimit = maxAdvertisedSize
      val messageLimit = maxMessageBytes
      val profile = new Profile[I, B]:
        val maxBatch = batchLimit
        val maxOriginalBytes = originalLimit
        val maxAdvertisedSize = relayLimit
        def maxMessageBytes(state: State): Int = messageLimit(state)
        private def limits(state: State) = ProtocolWire.Limits(
          maxMessageBytes(state),
          maxOriginalBytes,
          64,
          ProtocolWire.MaxItems,
          ProtocolWire.MaxItems
        )
        private def item(bytes: Bytes, state: State)(using ProtocolWire.Scope): Unit =
          get(ProtocolWire.validateItem(bytes, limits(state)))
        def writeId(value: I, state: State, writer: ProtocolWire.Writer)(using
            ProtocolWire.Scope
        ): Unit =
          val bytes = get(encodeId(value)); item(bytes, state); writer.raw(bytes)
        def readId(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): I = get(
          decodeId(reader.item())
        )
        def validateOffer(offer: Offer[I])(using ProtocolWire.Scope): Unit = get(checkOffer(offer))
        def writeBody(value: B, state: State, writer: ProtocolWire.Writer)(using
            ProtocolWire.Scope
        ): Unit =
          val bytes = get(encodeBody(value)); item(bytes, state); writer.raw(bytes)
        def readBody(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): B = get(
          decodeBody(reader.item())
        )
      Right(codec(profile, advertisedSize, bodyId))

  def transition[I, B](
      state: State,
      sender: Role,
      message: Message[I, B]
  ): Either[ProtocolError, State] =
    (state, sender, message) match
      case (State.Init, Role.Client, Message.Init)                => Right(State.Idle)
      case (State.Idle, Role.Server, Message.RequestIds(b, _, _)) => Right(State.Ids(b))
      case (State.Idle, Role.Server, Message.RequestTxs(_))       => Right(State.Txs)
      case (State.Ids(false), Role.Client, Message.ReplyIds(_))   => Right(State.Idle)
      case (State.Ids(true), Role.Client, Message.ReplyIds(xs)) if xs.nonEmpty => Right(State.Idle)
      case (State.Ids(true), Role.Client, Message.Done)                        => Right(State.Done)
      case (State.Txs, Role.Client, Message.ReplyTxs(_))                       => Right(State.Idle)
      case _ => Left(ProtocolError.TxSubmission2Agency(state, sender))

  def encode[I, B](
      state: State,
      sender: Role,
      message: Message[I, B],
      profile: Profile[I, B]
  ): Either[ProtocolError, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ProtocolWire.Writer(
      ProtocolWire.Limits(
        profile.maxMessageBytes(state),
        profile.maxOriginalBytes,
        32,
        ProtocolWire.MaxItems,
        ProtocolWire.MaxItems
      )
    )
    def count(n: Int): Unit =
      if n < 0 || n > 65535 then bad(ProtocolError.CountOutsideUint16(BigInt(n)))
    def batch(n: Int): Unit =
      if n > profile.maxBatch then bad(ProtocolError.BatchLimitExceeded(n, profile.maxBatch))
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    def list[A](items: Vector[A])(f: A => Unit): Unit =
      batch(items.size); w.byte(0x9f); items.foreach(f); w.byte(0xff)
    message match
      case Message.Init => outer(1, 6)
      case Message.Done => outer(1, 4)
      case Message.RequestIds(blocking, ack, req) =>
        count(ack); count(req); outer(4, 0); w.bool(blocking); w.head(0, ack); w.head(0, req)
      case Message.ReplyIds(offers) =>
        outer(2, 1)
        list(offers) { offer =>
          profile.validateOffer(offer)
          w.head(4, 2); profile.writeId(offer.transactionId, state, w);
          w.head(0, offer.advertisedSize)
        }
      case Message.RequestTxs(ids) => outer(2, 2); list(ids)(profile.writeId(_, state, w))
      case Message.ReplyTxs(originals) =>
        outer(2, 3); list(originals)(profile.writeBody(_, state, w))
    w.result
  }

  def decodePrefix[I, B](
      state: State,
      sender: Role,
      bytes: Bytes,
      profile: Profile[I, B]
  ): DecodeResult[Message[I, B]] = ProtocolWire.prefix {
    if state == State.Done || (state == State.Idle) != (sender == Role.Server) then
      bad(ProtocolError.WrongTxSubmission2Agency)
    val limits = ProtocolWire.Limits(
      profile.maxMessageBytes(state),
      profile.maxOriginalBytes,
      32,
      ProtocolWire.MaxItems,
      ProtocolWire.MaxItems
    )
    val r = new ProtocolWire.Reader(bytes, limits)
    def list[A](f: () => A): Vector[A] =
      if r.read() != 0x9f then bad(ProtocolError.ExpectedIndefiniteProtocolList)
      @scala.annotation.tailrec
      def loop(collected: Vector[A]): Vector[A] =
        bytes.lift(r.position) match
          case None => bad(ProtocolError.TruncatedList)
          case Some(b) if b == 0xff.toByte =>
            r.read(); collected
          case Some(_) if collected.size >= profile.maxBatch =>
            bad(ProtocolError.BatchLimitExceeded(collected.size, profile.maxBatch))
          case Some(_) => loop(collected :+ f())
      loop(Vector.empty)
    def uint16(): Int =
      val n = r.uint().value
      if n > 65535 then bad(ProtocolError.CountOutsideUint16(n))
      n.toInt
    val arity = r.arrayLength()
    val tag = r.uint().value
    val message: Message[I, B] = (state, arity, tag.toInt) match
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
          val id = profile.readId(r)
          val size = r.uint().value
          if size <= 0 || size > 0xffffffffL then bad(ProtocolError.SizeOutsideUint32)
          val offer = Offer(id, size.toLong)
          profile.validateOffer(offer)
          offer
        ))
      case (State.Idle, 2, 2) if tag == 2      => Message.RequestTxs(list(() => profile.readId(r)))
      case (State.Txs, 2, 3) if tag == 3       => Message.ReplyTxs(list(() => profile.readBody(r)))
      case (State.Ids(true), 1, 4) if tag == 4 => Message.Done
      case _ => bad(ProtocolError.UnknownTxSubmission2TagStateArity)
    get(transition(state, sender, message))
    DecodeResult.Decoded(message, r.position)
  }

  def decode[I, B](
      state: State,
      sender: Role,
      bytes: Bytes,
      profile: Profile[I, B]
  ): Either[ProtocolError, Message[I, B]] =
    decodePrefix(state, sender, bytes, profile) match
      case DecodeResult.NeedMore      => Left(ProtocolError.TruncatedTxSubmission2Message)
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(message, n) =>
        if n == bytes.size then Right(message) else Left(ProtocolError.TrailingTxSubmission2Bytes)
