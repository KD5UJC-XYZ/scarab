// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import ChainSync.{Point, DecodeResult}
import ProtocolWire.{bad, get, attempt}
import scala.concurrent.duration.*

/** Pure, non-pipelined BlockFetch. Wire contract derived from pinned upstream sources; see
  * provenance.md. No transport, negotiated capability or ledger-validity claim.
  */
object BlockFetch:
  val MiniProtocolId: Int = 3
  enum State:
    case Idle, Busy, Streaming, Done
  enum Role:
    case Client, Server
  enum Message[+P]:
    case RequestRange(from: Point, to: Point)
    case ClientDone
    case StartBatch
    case NoBlocks
    case Block(payload: P)
    case BatchDone

  /** Extends the existing pure adapter seam. Specialized adapters may check framing and declared
    * lengths before waiting for the body. Generic adapters receive one CBOR item.
    */
  trait PayloadCodec[P] extends ChainSync.PayloadCodec[P]:
    private[network] def readFrom(reader: ProtocolWire.Reader): P =
      get(this.decode(reader.item()))

  /** Per-state receive bounds, independent of ChainSync and mux SDU bounds. */
  final case class Limits(
      idleMessageBytes: Int = 65535,
      busyMessageBytes: Int = 65535,
      streamingMessageBytes: Int = 2500000,
      maxStringBytes: Int = 2499991,
      maxHashBytes: Int = 64,
      maxDepth: Int = 24,
      maxItems: Int = 8192
  ):
    def valid: Boolean =
      idleMessageBytes > 0 && idleMessageBytes <= 65535 &&
        busyMessageBytes > 0 && busyMessageBytes <= 65535 &&
        streamingMessageBytes > 0 && streamingMessageBytes <= 2500000 &&
        maxStringBytes >= 0 && maxStringBytes <= 2500000 &&
        maxHashBytes >= 0 && maxHashBytes <= 65535 &&
        maxDepth >= 0 && maxDepth <= 64 && maxItems > 0 && maxItems <= 65535
    def messageBytes(state: State): Int = state match
      case State.Idle      => idleMessageBytes
      case State.Busy      => busyMessageBytes
      case State.Streaming => streamingMessageBytes
      case State.Done      => 0
    private[network] def wire(state: State): ProtocolWire.Limits =
      if !valid then bad("invalid BlockFetch limits")
      if state == State.Done then bad("BlockFetch is terminal")
      val size = messageBytes(state)
      val strings = math.min(maxStringBytes, size)
      ProtocolWire.Limits(size, strings, math.min(maxHashBytes, strings), maxDepth, maxItems)

  /** Metadata for a later interpreter, not an implemented timer. Idle unlimited and 60s state waits
    * are upstream values. A whole-request deadline is additional local policy.
    */
  final case class TimeLimits(
      idle: Option[FiniteDuration] = None,
      busy: FiniteDuration = 60.seconds,
      streaming: FiniteDuration = 60.seconds,
      wholeRequest: FiniteDuration = 120.seconds
  ):
    def valid: Boolean = idle.forall(_ > Duration.Zero) && busy > Duration.Zero &&
      streaming > Duration.Zero && wholeRequest > Duration.Zero

  /** Local accounting policy for a future range interpreter. Not codec/transport limits. */
  final case class RequestLimits(maxBlocks: Int = 4, maxRawBytes: Long = 10000000L):
    def valid: Boolean = maxBlocks > 0 && maxBlocks <= 1000000 && maxRawBytes > 0
  final class Received private (val blocks: Int, val rawBytes: Long)
  object Received:
    val Empty: Received = new Received(0, 0L)
    def add(previous: Received, bytes: Int, limits: RequestLimits): Either[String, Received] =
      if !limits.valid || bytes < 0 then Left("invalid request accounting limits/bytes")
      else if previous.blocks >= limits.maxBlocks then Left("request block limit exceeded")
      else if previous.rawBytes > limits.maxRawBytes || bytes.toLong > limits.maxRawBytes - previous.rawBytes
      then Left("request aggregate raw byte limit exceeded")
      else Right(new Received(previous.blocks + 1, previous.rawBytes + bytes.toLong))

  def direction(sender: Role): Mux.Direction = sender match
    case Role.Client => Mux.Direction.Initiator
    case Role.Server => Mux.Direction.Responder

  def transition[P](state: State, sender: Role, message: Message[P]): Either[String, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.RequestRange(_, _)) => Right(State.Busy)
      case (State.Idle, Role.Client, Message.ClientDone)         => Right(State.Done)
      case (State.Busy, Role.Server, Message.StartBatch)         => Right(State.Streaming)
      case (State.Busy, Role.Server, Message.NoBlocks)           => Right(State.Idle)
      case (State.Streaming, Role.Server, Message.Block(_))      => Right(State.Streaming)
      case (State.Streaming, Role.Server, Message.BatchDone)     => Right(State.Idle)
      case _ => Left(s"illegal BlockFetch message/agency in $state from $sender")

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[String, Bytes] = attempt {
    get(transition(state, sender, message))
    val bounds = limits.wire(state)
    val w = new ProtocolWire.Writer(bounds)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    message match
      case Message.RequestRange(from, to) => outer(3, 0); w.point(from); w.point(to)
      case Message.ClientDone             => outer(1, 1)
      case Message.StartBatch             => outer(1, 2)
      case Message.NoBlocks               => outer(1, 3)
      case Message.Block(value) =>
        outer(2, 4)
        val encoded = get(payload.encode(value))
        get(ProtocolWire.validateItem(encoded, bounds))
        w.raw(encoded)
      case Message.BatchDone => outer(1, 5)
    val result = w.result
    get(ProtocolWire.validateItem(result, bounds))
    result
  }

  /** Decodes one message without consuming a suffix. Input is caller-owned and must itself be
    * ingress-bounded. This stateless prefix API does not buffer or accumulate chunks.
    */
  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[P]] =
    try
      val bounds = limits.wire(state)
      // Reject impossible agency even with empty input rather than waiting for bytes.
      if (state == State.Idle) != (sender == Role.Client) then bad("wrong BlockFetch agency")
      val r = new ProtocolWire.Reader(bytes, bounds)
      val arity = r.arrayLength()
      val possibleArity = state match
        case State.Idle      => arity == 1 || arity == 3
        case State.Busy      => arity == 1
        case State.Streaming => arity == 1 || arity == 2
        case State.Done      => false
      if !possibleArity then bad("wrong BlockFetch outer arity for state")
      val tag = r.uint().value
      val expected = tag match
        case n if n == 0                               => 3
        case n if n == 4                               => 2
        case n if n == 1 || n == 2 || n == 3 || n == 5 => 1
        case _                                         => bad("unknown BlockFetch tag")
      if arity != expected then bad("wrong exact BlockFetch outer arity")
      // Header-only state validation happens before any untrusted payload decode.
      val legal = (state, tag.toInt) match
        case (State.Idle, 0 | 1) | (State.Busy, 2 | 3) | (State.Streaming, 4 | 5) => true
        case _                                                                    => false
      if !legal then bad("wrong BlockFetch state")
      val message: Message[P] = tag.toInt match
        case 0 => Message.RequestRange(r.point(), r.point())
        case 1 => Message.ClientDone
        case 2 => Message.StartBatch
        case 3 => Message.NoBlocks
        case 4 => Message.Block(payload.readFrom(r))
        case 5 => Message.BatchDone
        case _ => bad("unknown BlockFetch tag")
      get(transition(state, sender, message))
      get(ProtocolWire.validateItem(Bytes(bytes.value.take(r.position)), bounds))
      DecodeResult.Decoded(message, r.position)
    catch
      case ProtocolWire.Failure(_, true)      => DecodeResult.NeedMore
      case ProtocolWire.Failure(error, false) => DecodeResult.Failed(error)

  /** End-of-input/exact-message boundary: incomplete input is an explicit failure, never success.
    */
  def decode[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[String, Message[P]] =
    decodePrefix(state, sender, bytes, payload, limits) match
      case DecodeResult.NeedMore      => Left("truncated BlockFetch message")
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(value, consumed) =>
        if consumed == bytes.size then Right(value) else Left("trailing BlockFetch bytes")
