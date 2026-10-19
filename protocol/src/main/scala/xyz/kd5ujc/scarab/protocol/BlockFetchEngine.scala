// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import scala.concurrent.duration.*

import ChainSync.DecodeResult
import ProtocolWire.{bad, get, attempt}

/** Shared typed BlockFetch state machine and wire framing. Domain-specific point and payload values
  * enter only through codecs; the Cardano facade retains its stricter point profile.
  */
object BlockFetchEngine:
  enum State:
    case Idle, Busy, Streaming, Done
  enum Role:
    case Client, Server
  enum Message[+P, +B]:
    case RequestRange(from: P, to: P)
    case ClientDone
    case StartBatch
    case NoBlocks
    case Block(payload: B)
    case BatchDone

  /** A point codec operates on one complete CBOR item. Specialized codecs may override the internal
    * Reader/Writer hooks to preserve validation order and avoid a second parser pass.
    */
  trait PointCodec[P]:
    def encode(point: P): Either[ProtocolError, Bytes]
    def decode(item: Bytes): Either[ProtocolError, P]
    private[protocol] def readFrom(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): P =
      get(this.decode(reader.item()))
    private[protocol] def writeTo(
        writer: ProtocolWire.Writer,
        point: P,
        bounds: ProtocolWire.Limits
    )(using ProtocolWire.Scope): Unit =
      val encoded = get(this.encode(point))
      get(ProtocolWire.validateItem(encoded, bounds))
      writer.raw(encoded)

  /** Payloads are opaque to the protocol state machine; codecs own one complete CBOR item. */
  trait PayloadCodec[B] extends ChainSync.PayloadCodec[B]:
    private[protocol] def readFrom(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): B =
      get(this.decode(reader.item()))

  def pointCodec[P](
      encodeItem: P => Either[ProtocolError, Bytes],
      decodeItem: Bytes => Either[ProtocolError, P]
  ): PointCodec[P] = new PointCodec[P]:
    def encode(point: P): Either[ProtocolError, Bytes] = encodeItem(point)
    def decode(item: Bytes): Either[ProtocolError, P] = decodeItem(item)

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
    private[protocol] def wire(state: State)(using ProtocolWire.Scope): ProtocolWire.Limits =
      if !valid then bad(ProtocolError.InvalidBlockFetchLimits)
      if state == State.Done then bad(ProtocolError.BlockFetchIsTerminal)
      val size = messageBytes(state)
      val strings = math.min(maxStringBytes, size)
      ProtocolWire.Limits(size, strings, math.min(maxHashBytes, strings), maxDepth, maxItems)

  /** The runtime client's deadlines. Idle unlimited and 60s state waits are upstream values; the
    * whole-request deadline is additional local policy.
    */
  final case class TimeLimits(
      idle: Option[FiniteDuration] = None,
      busy: FiniteDuration = 60.seconds,
      streaming: FiniteDuration = 60.seconds,
      wholeRequest: FiniteDuration = 120.seconds
  ):
    def valid: Boolean = idle.forall(_ > Duration.Zero) && busy > Duration.Zero &&
      streaming > Duration.Zero && wholeRequest > Duration.Zero

  /** The runtime client's local aggregate range budget, separate from codec/transport limits. */
  final case class RequestLimits(maxBlocks: Int = 4, maxRawBytes: Long = 10000000L):
    def valid: Boolean = maxBlocks > 0 && maxBlocks <= 1000000 && maxRawBytes > 0
  final class Received private (val blocks: Int, val rawBytes: Long)
  object Received:
    val Empty: Received = new Received(0, 0L)
    def add(
        previous: Received,
        bytes: Int,
        limits: RequestLimits
    ): Either[ProtocolError, Received] =
      if !limits.valid || bytes < 0 then Left(ProtocolError.InvalidRequestAccountingLimitsBytes)
      else if previous.blocks >= limits.maxBlocks then Left(ProtocolError.RequestBlockLimitExceeded)
      else if previous.rawBytes > limits.maxRawBytes || bytes.toLong > limits.maxRawBytes - previous.rawBytes
      then Left(ProtocolError.RequestAggregateRawByteLimitExceeded)
      else Right(new Received(previous.blocks + 1, previous.rawBytes + bytes.toLong))

  def direction(sender: Role): Mux.Direction = sender match
    case Role.Client => Mux.Direction.Initiator
    case Role.Server => Mux.Direction.Responder

  def transition[P, B](
      state: State,
      sender: Role,
      message: Message[P, B]
  ): Either[ProtocolError, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.RequestRange(_, _)) => Right(State.Busy)
      case (State.Idle, Role.Client, Message.ClientDone)         => Right(State.Done)
      case (State.Busy, Role.Server, Message.StartBatch)         => Right(State.Streaming)
      case (State.Busy, Role.Server, Message.NoBlocks)           => Right(State.Idle)
      case (State.Streaming, Role.Server, Message.Block(_))      => Right(State.Streaming)
      case (State.Streaming, Role.Server, Message.BatchDone)     => Right(State.Idle)
      case _ => Left(ProtocolError.BlockFetchAgency(state, sender))

  def encode[P, B](
      state: State,
      sender: Role,
      message: Message[P, B],
      point: PointCodec[P],
      payload: PayloadCodec[B],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] = attempt {
    get(transition(state, sender, message))
    val bounds = limits.wire(state)
    val w = new ProtocolWire.Writer(bounds)
    def outer(n: Int, tag: Int): Unit = { w.head(4, n); w.head(0, tag) }
    message match
      case Message.RequestRange(from, to) =>
        outer(3, 0)
        point.writeTo(w, from, bounds)
        point.writeTo(w, to, bounds)
      case Message.ClientDone => outer(1, 1)
      case Message.StartBatch => outer(1, 2)
      case Message.NoBlocks   => outer(1, 3)
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
  def decodePrefix[P, B](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: PointCodec[P],
      payload: PayloadCodec[B],
      limits: Limits = Limits()
  ): DecodeResult[Message[P, B]] =
    ProtocolWire.prefix {
      val bounds = limits.wire(state)
      // Reject impossible agency even with empty input rather than waiting for bytes.
      if (state == State.Idle) != (sender == Role.Client) then
        bad(ProtocolError.WrongBlockFetchAgency)
      val r = new ProtocolWire.Reader(bytes, bounds)
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
      val message: Message[P, B] = tag.toInt match
        case 0 => Message.RequestRange(point.readFrom(r), point.readFrom(r))
        case 1 => Message.ClientDone
        case 2 => Message.StartBatch
        case 3 => Message.NoBlocks
        case 4 => Message.Block(payload.readFrom(r))
        case 5 => Message.BatchDone
        case _ => bad(ProtocolError.UnknownBlockFetchTag)
      get(transition(state, sender, message))
      get(ProtocolWire.validateItem(bytes.take(r.position), bounds))
      DecodeResult.Decoded(message, r.position)
    }

  /** End-of-input/exact-message boundary: incomplete input is an explicit failure, never success.
    */
  def decode[P, B](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: PointCodec[P],
      payload: PayloadCodec[B],
      limits: Limits = Limits()
  ): Either[ProtocolError, Message[P, B]] =
    decodePrefix(state, sender, bytes, point, payload, limits) match
      case DecodeResult.NeedMore      => Left(ProtocolError.TruncatedBlockFetchMessage)
      case DecodeResult.Failed(error) => Left(error)
      case DecodeResult.Decoded(value, consumed) =>
        if consumed == bytes.size then Right(value) else Left(ProtocolError.TrailingBlockFetchBytes)
