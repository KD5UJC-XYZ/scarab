// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.Blake2b
import lab.cbor.Bytes
import ChainSync.{DecodeResult, Role}
import ProtocolWire.{attempt, bad, get}

/** Conway-only NtN14 publisher profile. Source pins and local policy: docs/txsubmission2.md. */
object TxSubmission2:
  val MiniProtocolId = 4
  val MaxOriginalBytes = 65536
  val MaxBatch = 8
  val MaxMessageBytes = 524400
  private val wire = ProtocolWire.Limits(MaxMessageBytes, MaxOriginalBytes, 32, 32, 65535)

  enum State:
    case Init, Idle, Txs, Done
    case Ids(blocking: Boolean)
  enum Message:
    case Init
    case RequestIds(blocking: Boolean, acknowledge: Int, request: Int)
    case ReplyIds(offers: Vector[RelayOffer])
    case RequestTxs(ids: Vector[Bytes])
    case ReplyTxs(originals: Vector[Bytes])
    case Done

  def transition(state: State, sender: Role, message: Message): Either[String, State] =
    (state, sender, message) match
      case (State.Init, Role.Client, Message.Init)                => Right(State.Idle)
      case (State.Idle, Role.Server, Message.RequestIds(b, _, _)) => Right(State.Ids(b))
      case (State.Idle, Role.Server, Message.RequestTxs(_))       => Right(State.Txs)
      case (State.Ids(false), Role.Client, Message.ReplyIds(_))   => Right(State.Idle)
      case (State.Ids(true), Role.Client, Message.ReplyIds(xs)) if xs.nonEmpty => Right(State.Idle)
      case (State.Ids(true), Role.Client, Message.Done)                        => Right(State.Done)
      case (State.Txs, Role.Client, Message.ReplyTxs(_))                       => Right(State.Idle)
      case _ => Left("illegal TxSubmission2 message/agency")

  /** Size of the actual Conway GenTx encoding, excluding ReplyTxs list/message/mux framing. */
  def advertisedSize(originalBytes: Int): Either[String, Long] =
    if originalBytes <= 0 || originalBytes > MaxOriginalBytes then
      Left("original size outside policy")
    else
      Right(
        originalBytes.toLong + 4 + (if originalBytes < 24 then 1
                                    else if originalBytes <= 255 then 2
                                    else if originalBytes <= 65535 then 3
                                    else 5)
      )

  /** Structural identity only; admission remains the caller's responsibility. */
  def bodyId(original: Bytes): Either[String, Bytes] = attempt {
    get(advertisedSize(original.size))
    val r = new ProtocolWire.Reader(original, wire.copy(maxMessageBytes = MaxOriginalBytes))
    val indefinite = (original.value.head & 255) == 0x9f
    if indefinite then r.read()
    else if r.arrayLength() != 4 then bad("expected four-member Conway transaction")
    val body = r.item()
    if body.size == 0 || (body.value.head & 0xe0) != 0xa0 then bad("transaction body is not a map")
    r.item()
    val validity = r.read()
    if validity != 0xf4 && validity != 0xf5 then bad("transaction validity is not a Boolean")
    r.item()
    if indefinite && r.read() != 0xff then bad("expected break after fourth transaction member")
    if r.position != original.size then bad("trailing original transaction bytes")
    Blake2b.hash256.hash(body)
  }

  private def checkId(id: Bytes): Unit =
    if id.size != 32 then bad("transaction ID must be 32 bytes")
  private def checkOffer(o: RelayOffer): Unit =
    checkId(o.transactionId)
    if o.advertisedSize <= 0 || o.advertisedSize > 0xffffffffL then bad("size outside uint32")
  private def count(n: Int): Unit = if n < 0 || n > 65535 then bad("count outside uint16")
  private def batch(n: Int): Unit = if n > MaxBatch then bad("batch limit exceeded")
  private def original(bytes: Bytes): Unit =
    get(advertisedSize(bytes.size))
    get(ProtocolWire.validateItem(bytes, wire.copy(maxMessageBytes = MaxOriginalBytes)))

  def encode(state: State, sender: Role, message: Message): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val w = new ProtocolWire.Writer(wire)
    def list[A](xs: Vector[A])(f: A => Unit): Unit =
      batch(xs.size); w.byte(0x9f); xs.foreach(f); w.byte(0xff)
    def id(bytes: Bytes): Unit =
      checkId(bytes); w.head(4, 2); w.head(0, 6); w.head(2, 32); w.raw(bytes)
    def head(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    message match
      case Message.Init => head(1, 6)
      case Message.Done => head(1, 4)
      case Message.RequestIds(b, ack, req) =>
        count(ack); count(req); head(4, 0); w.byte(if b then 0xf5 else 0xf4); w.head(0, ack);
        w.head(0, req)
      case Message.ReplyIds(xs) =>
        head(2, 1)
        list(xs) { o =>
          checkOffer(o); w.head(4, 2); id(o.transactionId); w.head(0, o.advertisedSize)
        }
      case Message.RequestTxs(xs) => head(2, 2); list(xs)(id)
      case Message.ReplyTxs(xs) =>
        head(2, 3)
        list(xs) { bytes =>
          original(bytes); w.head(4, 2); w.head(0, 6); w.head(6, 24); w.head(2, bytes.size);
          w.raw(bytes)
        }
    w.result
  }

  def decodePrefix(state: State, sender: Role, bytes: Bytes): DecodeResult[Message] =
    try
      if state == State.Done || (state == State.Idle) != (sender == Role.Server) then
        bad("wrong TxSubmission2 agency")
      val r = new ProtocolWire.Reader(bytes, wire)
      def id(): Bytes =
        if r.arrayLength() != 2 || r.uint().value != 6 then bad("expected Conway transaction ID")
        val result = r.bytes(32); checkId(result); result
      def list[A](f: () => A): Vector[A] =
        if r.read() != 0x9f then bad("expected indefinite protocol list")
        val out = Vector.newBuilder[A]
        var n = 0
        def ended: Boolean =
          if r.position >= bytes.size then throw ProtocolWire.Failure("truncated list", true)
          (bytes.value(r.position) & 255) == 0xff
        while !ended do
          if n >= MaxBatch then bad("batch limit exceeded")
          out += f(); n += 1
        r.read(); out.result()
      def uint16(): Int =
        val n = r.uint().value
        if n > 65535 then bad("count outside uint16")
        n.toInt
      val arity = r.arrayLength()
      val tag = r.uint().value
      val message = (state, arity, tag.toInt) match
        case (State.Init, 1, 6) if tag == 6 => Message.Init
        case (State.Idle, 4, 0) if tag == 0 =>
          val b = r.read()
          if b != 0xf4 && b != 0xf5 then bad("expected blocking Boolean")
          Message.RequestIds(b == 0xf5, uint16(), uint16())
        case (State.Ids(_), 2, 1) if tag == 1 =>
          Message.ReplyIds(list(() =>
            if r.arrayLength() != 2 then bad("offer arity")
            val txid = id()
            val size = r.uint().value
            if size <= 0 || size > 0xffffffffL then bad("size outside uint32")
            RelayOffer(txid, size.toLong)
          ))
        case (State.Idle, 2, 2) if tag == 2 => Message.RequestTxs(list(() => id()))
        case (State.Txs, 2, 3) if tag == 3 =>
          Message.ReplyTxs(list(() =>
            if r.arrayLength() != 2 || r.uint().value != 6 || r.tag() != 24 then
              bad("expected Conway GenTx")
            val value = r.bytes(MaxOriginalBytes)
            original(value); value
          ))
        case (State.Ids(true), 1, 4) if tag == 4 => Message.Done
        case _                                   => bad("unknown TxSubmission2 tag/state/arity")
      get(transition(state, sender, message))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)

  def decode(state: State, sender: Role, bytes: Bytes): Either[String, Message] =
    decodePrefix(state, sender, bytes) match
      case DecodeResult.NeedMore      => Left("truncated TxSubmission2 message")
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(message, n) =>
        if n == bytes.size then Right(message) else Left("trailing TxSubmission2 bytes")

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
    ): Either[String, (Inventory, Message, Vector[Bytes])] = attempt {
      count(ack); count(request)
      if ack > outstanding.size then bad("ack exceeds outstanding inventory")
      val retained = outstanding.drop(ack)
      if blocking && (request == 0 || retained.nonEmpty) then
        bad("invalid blocking inventory request")
      if !blocking && (retained.isEmpty || (ack == 0 && request == 0)) then
        bad("invalid nonblocking inventory request")
      val offered = remaining.take(request)
      val reply = if blocking && offered.isEmpty then Message.Done else Message.ReplyIds(offered)
      (
        copy(remaining = remaining.drop(offered.size), outstanding = retained ++ offered),
        reply,
        outstanding.take(ack).map(_.transactionId)
      )
    }
    def requestTxs(ids: Vector[Bytes]): Either[String, Inventory] = attempt {
      batch(ids.size)
      if ids.distinct.size != ids.size then bad("duplicate body request")
      val known = outstanding.map(_.transactionId).toSet
      if ids.exists(id => !known(id) || requested(id)) then
        bad("unadvertised, acknowledged or repeated body request")
      copy(requested = requested ++ ids)
    }
  object Inventory:
    def create(offers: Vector[RelayOffer]): Either[String, Inventory] = attempt {
      batch(offers.size); offers.foreach(checkOffer)
      if offers.map(_.transactionId).distinct.size != offers.size then bad("duplicate offer ID")
      Inventory(offers, Vector.empty, Set.empty)
    }

/** Wire size includes the Conway era and CBOR-in-CBOR envelope, not mux/message overhead. */
final case class RelayOffer(transactionId: Bytes, advertisedSize: Long)
