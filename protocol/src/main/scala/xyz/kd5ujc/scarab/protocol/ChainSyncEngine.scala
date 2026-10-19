// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Limits, Role, State}
import ProtocolWire.{bad, get, attempt}

/** Generic ChainSync wire and agency rules. Point, tip, and header domains are supplied by typed
  * codecs; the Cardano facade and the upstream mock-domain adapter share this engine.
  */
object ChainSyncEngine:
  enum Message[+Q, +T, +P]:
    case RequestNext
    case AwaitReply
    case RollForward(payload: P, tip: T)
    case RollBackward(point: Q, tip: T)
    case FindIntersect(points: Vector[Q])
    case IntersectFound(point: Q, tip: T)
    case IntersectNotFound(tip: T)
    case Done

  /** A value occupies one complete CBOR item on the wire. Specialized codecs can read directly from
    * the bounded protocol reader to preserve validation order and avoid reparsing.
    */
  trait ItemCodec[A]:
    def encode(value: A): Either[ProtocolError, Bytes]
    def decode(item: Bytes): Either[ProtocolError, A]
    private[protocol] def readFrom(reader: ProtocolWire.Reader)(using ProtocolWire.Scope): A =
      get(this.decode(reader.item()))
    private[protocol] def writeTo(
        writer: ProtocolWire.Writer,
        value: A,
        bounds: ProtocolWire.Limits
    )(using ProtocolWire.Scope): Unit =
      val encoded = get(this.encode(value))
      get(ProtocolWire.validateItem(encoded, bounds))
      writer.raw(encoded)

  trait PointCodec[Q] extends ItemCodec[Q]
  trait TipCodec[T] extends ItemCodec[T]
  trait PayloadCodec[P] extends ItemCodec[P]

  def pointCodec[Q](
      encodeItem: Q => Either[ProtocolError, Bytes],
      decodeItem: Bytes => Either[ProtocolError, Q]
  ): PointCodec[Q] = new PointCodec[Q]:
    def encode(value: Q): Either[ProtocolError, Bytes] = encodeItem(value)
    def decode(item: Bytes): Either[ProtocolError, Q] = decodeItem(item)

  def tipCodec[T](
      encodeItem: T => Either[ProtocolError, Bytes],
      decodeItem: Bytes => Either[ProtocolError, T]
  ): TipCodec[T] = new TipCodec[T]:
    def encode(value: T): Either[ProtocolError, Bytes] = encodeItem(value)
    def decode(item: Bytes): Either[ProtocolError, T] = decodeItem(item)

  def payloadCodec[P](
      encodeItem: P => Either[ProtocolError, Bytes],
      decodeItem: Bytes => Either[ProtocolError, P]
  ): PayloadCodec[P] = new PayloadCodec[P]:
    def encode(value: P): Either[ProtocolError, Bytes] = encodeItem(value)
    def decode(item: Bytes): Either[ProtocolError, P] = decodeItem(item)

  def transition[Q, T, P](
      state: State,
      sender: Role,
      message: Message[Q, T, P]
  ): Either[ProtocolError, State] =
    import State.*
    import Role.*
    (state, sender, message) match
      case (Idle, Client, Message.RequestNext)        => Right(NextCanAwait)
      case (Idle, Client, Message.FindIntersect(_))   => Right(Intersect)
      case (Idle, Client, Message.Done)               => Right(Done)
      case (NextCanAwait, Server, Message.AwaitReply) => Right(NextMustReply)
      case (
            NextCanAwait | NextMustReply,
            Server,
            Message.RollForward(_, _) | Message.RollBackward(_, _)
          ) =>
        Right(Idle)
      case (Intersect, Server, Message.IntersectFound(_, _) | Message.IntersectNotFound(_)) =>
        Right(Idle)
      case _ => Left(ProtocolError.ChainSyncAgency(state, sender))

  def encode[Q, T, P](
      state: State,
      sender: Role,
      message: Message[Q, T, P],
      point: PointCodec[Q],
      tip: TipCodec[T],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] = attempt {
    get(transition(state, sender, message))
    val bounds = ChainSyncWire.checked(limits)
    val writer = new ChainSyncWire.Writer(limits)
    def outer(arity: Int, tag: Int): Unit =
      writer.head(4, arity)
      writer.head(0, tag)
    message match
      case Message.RequestNext => outer(1, 0)
      case Message.AwaitReply  => outer(1, 1)
      case Message.RollForward(value, serverTip) =>
        outer(3, 2)
        payload.writeTo(writer, value, bounds)
        tip.writeTo(writer, serverTip, bounds)
      case Message.RollBackward(serverPoint, serverTip) =>
        outer(3, 3)
        point.writeTo(writer, serverPoint, bounds)
        tip.writeTo(writer, serverTip, bounds)
      case Message.FindIntersect(points) =>
        if points.size > limits.maxCandidates then bad(ProtocolError.CandidateLimitExceeded)
        outer(2, 4)
        if points.isEmpty then writer.head(4, 0)
        else
          writer.byte(159)
          points.foreach(point.writeTo(writer, _, bounds))
          writer.byte(255)
      case Message.IntersectFound(serverPoint, serverTip) =>
        outer(3, 5)
        point.writeTo(writer, serverPoint, bounds)
        tip.writeTo(writer, serverTip, bounds)
      case Message.IntersectNotFound(serverTip) =>
        outer(2, 6)
        tip.writeTo(writer, serverTip, bounds)
      case Message.Done => outer(1, 7)
    val result = writer.result
    get(ProtocolWire.validateItem(result, bounds))
    result
  }

  def decodePrefix[Q, T, P](
      state: State,
      sender: Role,
      bytes: Bytes,
      point: PointCodec[Q],
      tip: TipCodec[T],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[Q, T, P]] = ProtocolWire.prefix {
    val bounds = ChainSyncWire.checked(limits)
    val reader = new ChainSyncWire.Reader(bytes, limits)
    val arity = reader.arrayLength()
    val tag = reader.uint().value
    val expectedArity = tag match
      case n if n == 0 || n == 1 || n == 7 => 1
      case n if n == 4 || n == 6           => 2
      case n if n == 2 || n == 3 || n == 5 => 3
      case _                               => bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    if arity != expectedArity then bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    val tagNumber = tag.toInt
    if !legalAgency(state, sender, tagNumber) then bad(ProtocolError.ChainSyncAgency(state, sender))
    val message: Message[Q, T, P] = tagNumber match
      case 0 => Message.RequestNext
      case 1 => Message.AwaitReply
      case 2 => Message.RollForward(payload.readFrom(reader), tip.readFrom(reader))
      case 3 => Message.RollBackward(point.readFrom(reader), tip.readFrom(reader))
      case 4 => Message.FindIntersect(reader.items(limits.maxCandidates)(point.readFrom))
      case 5 => Message.IntersectFound(point.readFrom(reader), tip.readFrom(reader))
      case 6 => Message.IntersectNotFound(tip.readFrom(reader))
      case 7 => Message.Done
      case _ => bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    get(transition(state, sender, message))
    get(ProtocolWire.validateItem(bytes.take(reader.position), bounds))
    DecodeResult.Decoded(message, reader.position)
  }

  private def legalAgency(state: State, sender: Role, tag: Int): Boolean =
    import State.*
    import Role.*
    (state, sender, tag) match
      case (Idle, Client, 0 | 4 | 7)         => true
      case (NextCanAwait, Server, 1 | 2 | 3) => true
      case (NextMustReply, Server, 2 | 3)    => true
      case (Intersect, Server, 5 | 6)        => true
      case _                                 => false
