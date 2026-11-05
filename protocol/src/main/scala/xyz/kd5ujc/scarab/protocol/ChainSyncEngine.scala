// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, ChunkedBytes}
import cats.data.Chain
import cats.syntax.all.*

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
    case FindIntersect(points: cats.data.Chain[Q])
    case IntersectFound(point: Q, tip: T)
    case IntersectNotFound(tip: T)
    case Done

  object Message:
    object FindIntersect:
      def apply[Q, T, P](points: Vector[Q]): Message[Q, T, P] =
        FindIntersect(cats.data.Chain.fromSeq(points))

  /** A value occupies one complete CBOR item. Chunked encoding and streaming are the live local
    * operations; their defaults adapt a bounded contiguous codec. Wide codecs override them.
    * Specialized readers preserve validation order without reparsing an opaque inner payload.
    */
  trait ItemCodec[A]:
    def encode(value: A): Either[ProtocolError, Bytes]
    def decode(item: Bytes): Either[ProtocolError, A]
    def encodeChunked(value: A): Either[ProtocolError, ChunkedBytes] = attempt {
      val bounds = ChainSyncWire.encoding(Limits.NodeToClient)
      val writer = new ProtocolWire.Writer(bounds)
      writeTo(writer, value, bounds)
      ChunkedBytes.fromBytes(writer.result)
    }
    def streaming: StreamingDecoder[A] = StreamingDecoder.bounded { input =>
      ProtocolWire.prefix {
        val reader = new ChainSyncWire.Reader(input, Limits.NodeToClient)
        DecodeResult.Decoded(readFrom(reader), reader.position)
      }
    }
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

  /** The primary encoder retains logical chunks, including every offered point. */
  def encodeChunked[Q, T, P](
      state: State,
      sender: Role,
      message: Message[Q, T, P],
      point: PointCodec[Q],
      tip: TipCodec[T],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, ChunkedBytes] =
    def joined(parts: Either[ProtocolError, ChunkedBytes]*): Either[ProtocolError, ChunkedBytes] =
      parts.toList.sequence.flatMap(_.foldM(ChunkedBytes.empty) { (bytes, next) =>
        bytes.concat(next).leftMap(ProtocolError.ChunkedRepresentation(_))
      })
    def prefix(arity: Int, tag: Int): Either[ProtocolError, ChunkedBytes] = attempt {
      val writer = new ChainSyncWire.Writer(limits)
      writer.head(4, arity)
      writer.head(0, tag)
      ChunkedBytes.fromBytes(writer.result)
    }
    def literal(bytes: Int*): Either[ProtocolError, ChunkedBytes] =
      Right(ChunkedBytes.fromBytes(Bytes.wrap(bytes.map(_.toByte).toArray)))
    transition(state, sender, message).flatMap { _ =>
      message match
        case Message.RequestNext => prefix(1, 0)
        case Message.AwaitReply  => prefix(1, 1)
        case Message.RollForward(value, serverTip) =>
          joined(prefix(3, 2), payload.encodeChunked(value), tip.encodeChunked(serverTip))
        case Message.RollBackward(serverPoint, serverTip) =>
          joined(prefix(3, 3), point.encodeChunked(serverPoint), tip.encodeChunked(serverTip))
        case Message.FindIntersect(points) =>
          if points.isEmpty then joined(prefix(2, 4), literal(0x80))
          else
            val offered = points.foldM(ChunkedBytes.empty) { (bytes, value) =>
              point
                .encodeChunked(value)
                .flatMap(bytes.concat(_).leftMap(ProtocolError.ChunkedRepresentation(_)))
            }
            joined(prefix(2, 4), literal(0x9f), offered, literal(0xff))
        case Message.IntersectFound(serverPoint, serverTip) =>
          joined(prefix(3, 5), point.encodeChunked(serverPoint), tip.encodeChunked(serverTip))
        case Message.IntersectNotFound(serverTip) =>
          joined(prefix(2, 6), tip.encodeChunked(serverTip))
        case Message.Done => prefix(1, 7)
    }

  /** Checked contiguous convenience. Live transport uses [[encodeChunked]]. */
  def encode[Q, T, P](
      state: State,
      sender: Role,
      message: Message[Q, T, P],
      point: PointCodec[Q],
      tip: TipCodec[T],
      payload: PayloadCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] =
    encodeChunked(state, sender, message, point, tip, payload, limits)
      .flatMap(_.toBytes(Int.MaxValue).leftMap(ProtocolError.ChunkedRepresentation(_)))

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
    val arity = reader.arrayArity()
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
      // as many points as the message's bytes hold (`decodeList`, #755)
      case 4 => Message.FindIntersect(cats.data.Chain.fromSeq(reader.items(point.readFrom)))
      case 5 => Message.IntersectFound(point.readFrom(reader), tip.readFrom(reader))
      case 6 => Message.IntersectNotFound(tip.readFrom(reader))
      case 7 => Message.Done
      case _ => bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
    get(transition(state, sender, message))
    get(ProtocolWire.validateItem(bytes.take(reader.position), bounds))
    DecodeResult.Decoded(message, reader.position)
  }

  /** Server responses decoded without collecting their serialized payload into a whole array. The
    * supplied codecs determine their own representation; bounded adapters remain bounded.
    */
  def responseDecoder[Q, T, P](
      state: State,
      point: PointCodec[Q],
      tip: TipCodec[T],
      payload: PayloadCodec[P]
  ): StreamingDecoder[Message[Q, T, P]] =
    ChainSyncAtoms
      .scalar { reader =>
        val arity = reader.arrayArity()
        val tag = reader.uint().value
        val expected = tag match
          case n if n == 0 || n == 1 || n == 7 => 1
          case n if n == 4 || n == 6           => 2
          case n if n == 2 || n == 3 || n == 5 => 3
          case _                               => 0
        if expected == 0 || arity != expected then
          bad(ProtocolError.UnknownTagOrWrongExactOuterArity)
        if !legalAgency(state, Role.Server, tag.toInt) then
          bad(ProtocolError.ChainSyncAgency(state, Role.Server))
        tag.toInt
      }
      .flatMap {
        case 1 => StreamingDecoder.pure(Message.AwaitReply)
        case 2 => payload.streaming.flatMap(p => tip.streaming.map(t => Message.RollForward(p, t)))
        case 3 => point.streaming.flatMap(p => tip.streaming.map(t => Message.RollBackward(p, t)))
        case 5 => point.streaming.flatMap(p => tip.streaming.map(t => Message.IntersectFound(p, t)))
        case 6 => tip.streaming.map(Message.IntersectNotFound(_))
        case _ => StreamingDecoder.failed(ProtocolError.ChainSyncAgency(state, Role.Server))
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
