// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import scala.annotation.tailrec
import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.DecodeResult
import PeerSharing.{Amount, Decoding, Error, Expectation, Field, Limits, Role, State}

/** PeerSharing agency and wire framing, parameterized by the peer item's codec. The Cardano address
  * facade and native mock-domain conversations share this production implementation.
  */
object PeerSharingEngine:
  enum Message[+P]:
    case ShareRequest(amount: Amount)
    case SharePeers(peers: Vector[P])
    case Done

  /** One complete CBOR peer item. Internal streaming hooks preserve the Cardano address parser's
    * validation order; ordinary codecs receive the exact original item bytes.
    */
  trait PeerCodec[P]:
    def encode(peer: P): Either[ProtocolError, Bytes]
    def decode(item: Bytes): Either[ProtocolError, P]
    private[protocol] def readFrom(cursor: Cursor): Read[P] =
      cursor.item().flatMap(item => decode(item).left.map(e => Stop.Fail(Error.PeerItem(e))))
    private[protocol] def writeTo(writer: Writer, peer: P, limits: Limits): Either[Error, Unit] =
      for
        item <- encode(peer).left.map(Error.PeerItem.apply)
        _ <- ProtocolWire
          .validateItem(item, itemLimits(limits.maxMessageBytes))
          .left
          .map(Error.PeerItem.apply)
      yield writer.raw(item)

  def peerCodec[P](
      encodeItem: P => Either[ProtocolError, Bytes],
      decodeItem: Bytes => Either[ProtocolError, P]
  ): PeerCodec[P] = new PeerCodec[P]:
    def encode(peer: P): Either[ProtocolError, Bytes] = encodeItem(peer)
    def decode(item: Bytes): Either[ProtocolError, P] = decodeItem(item)

  private def itemLimits(maximum: Int): ProtocolWire.Limits =
    ProtocolWire.Limits(
      maxMessageBytes = maximum,
      maxStringBytes = maximum,
      maxDepth = Int.MaxValue,
      maxItems = ProtocolWire.MaxItems
    )

  private[protocol] def transitionTyped[P](
      state: State,
      sender: Role,
      message: Message[P]
  ): Either[Error, State] =
    (state, sender, message) match
      case (State.Idle, Role.Client, Message.ShareRequest(_)) => Right(State.Busy)
      case (State.Busy, Role.Server, Message.SharePeers(_))   => Right(State.Idle)
      case (State.Idle, Role.Client, Message.Done)            => Right(State.Done)
      case (State.Done, _, _)                                 => Left(Error.Terminal)
      case _ => Left(Error.WrongAgency(state, sender))

  def transition[P](state: State, sender: Role, message: Message[P]): Either[ProtocolError, State] =
    transitionTyped(state, sender, message).left.map(ProtocolError.PeerSharing.apply)

  /** CBOR integer heads and opaque item bytes for PeerSharing envelopes. */
  private[protocol] final class Writer:
    private val out = new java.io.ByteArrayOutputStream()
    def head(major: Int, value: Long): Unit =
      if value < 24 then out.write(major * 32 + value.toInt)
      else if value <= 0xff then { out.write(major * 32 + 24); out.write(value.toInt) }
      else if value <= 0xffff then
        out.write(major * 32 + 25); out.write((value >> 8).toInt); out.write(value.toInt & 255)
      else
        out.write(major * 32 + 26)
        (3 to 0 by -1).foreach(i => out.write(((value >> (i * 8)) & 255).toInt))
    def byte(value: Int): Unit = out.write(value)
    def raw(value: Bytes): Unit = out.write(value.toArray)
    def result: Bytes = Bytes.fromArray(out.toByteArray)

  private[protocol] def encodeTyped[P](
      state: State,
      sender: Role,
      message: Message[P],
      peer: PeerCodec[P],
      limits: Limits
  ): Either[Error, Bytes] =
    transitionTyped(state, sender, message).flatMap { _ =>
      val writer = new Writer
      val written: Either[Error, Unit] = message match
        case Message.ShareRequest(amount) =>
          writer.head(4, 2); writer.head(0, 0); writer.head(0, amount.value.toLong)
          Right(())
        case Message.SharePeers(peers) =>
          writer.head(4, 2); writer.head(0, 1)
          if peers.isEmpty then
            writer.head(4, 0)
            Right(())
          else
            writer.byte(0x9f)
            peers
              .foldLeft[Either[Error, Unit]](Right(()))((done, value) =>
                done.flatMap(_ => peer.writeTo(writer, value, limits))
              )
              .map(_ => writer.byte(0xff))
        case Message.Done =>
          writer.head(4, 1); writer.head(0, 2)
          Right(())
      written.flatMap { _ =>
        val bytes = writer.result
        Either.cond(
          bytes.size <= limits.maxMessageBytes,
          bytes,
          Error.TooLarge(limits.maxMessageBytes)
        )
      }
    }

  def encode[P](
      state: State,
      sender: Role,
      message: Message[P],
      peer: PeerCodec[P],
      limits: Limits = Limits()
  ): Either[ProtocolError, Bytes] =
    encodeTyped(state, sender, message, peer, limits).left.map(ProtocolError.PeerSharing.apply)

  /** Why a read stopped: the input ended, or the bytes are wrong. */
  private[protocol] enum Stop:
    case Short
    case Fail(error: Error)
  private[protocol] type Read[A] = Either[Stop, A]

  private[protocol] def fail[A](error: Error): Read[A] = Left(Stop.Fail(error))

  /** The head of a CBOR item: its initial byte, major type, and the argument (absent when
    * indefinite).
    */
  private[protocol] final case class Head(initial: Int, major: Int, argument: Option[BigInt])

  /** A cursor over a prefix; the bytes read so far never pass `limit`. */
  private[protocol] final class Cursor(input: Bytes, limit: Int):
    private var at = 0
    def position: Int = at

    private def need(n: Int): Read[Unit] =
      if n > limit - at then fail(Error.TooLarge(limit))
      else if n > input.size - at then Left(Stop.Short)
      else Right(())

    private def byte(): Read[Int] =
      need(1).map { _ =>
        val b = input(at) & 255
        at += 1
        b
      }

    def head(): Read[Head] =
      byte().flatMap { initial =>
        val major = initial >> 5
        val info = initial & 31
        if info < 24 then Right(Head(initial, major, Some(BigInt(info))))
        else if info == 31 then Right(Head(initial, major, None))
        else if info > 27 then fail(Error.ReservedHead(initial))
        else
          val width = 1 << (info - 24)
          need(width).map { _ =>
            val value = (0 until width).foldLeft(BigInt(0))((acc, i) =>
              (acc << 8) | BigInt(input(at + i) & 255)
            )
            at += width
            Head(initial, major, Some(value))
          }
      }

    /** An unsigned integer in any definite width. */
    def uint(): Read[BigInt] =
      head().flatMap {
        case Head(_, 0, Some(value)) => Right(value)
        case Head(initial, 0, None)  => fail(Error.ReservedHead(initial))
        case Head(initial, _, _)     => fail(Error.Unexpected(Expectation.UnsignedInteger, initial))
      }

    def ranged(field: Field, max: BigInt): Read[Long] =
      uint().flatMap(v => if v > max then fail(Error.OutOfRange(field, v)) else Right(v.toLong))

    def definiteArray(): Read[BigInt] =
      head().flatMap {
        case Head(_, 4, Some(n)) => Right(n)
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.DefiniteArray, initial))
      }

    /** The length of an array, or `None` for an indefinite one. */
    def arrayOrIndefinite(): Read[Option[BigInt]] =
      head().flatMap {
        case Head(_, 4, length) => Right(length)
        case Head(initial, _, _) =>
          fail(Error.Unexpected(Expectation.ArrayOrIndefiniteArray, initial))
      }

    def peekBreak(): Read[Boolean] =
      need(1).map(_ => (input(at) & 255) == 0xff)

    def break(): Read[Unit] =
      head().flatMap {
        case Head(_, 7, None)    => Right(())
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.Break, initial))
      }

    /** One complete opaque peer item, bounded by this message's remaining byte budget. */
    def item(): Read[Bytes] =
      need(1).flatMap { _ =>
        val remaining = limit - at
        val bounds = itemLimits(remaining)
        ProtocolWire.prefix {
          val reader = new ProtocolWire.Reader(input.drop(at), bounds)
          val item = reader.item()
          DecodeResult.Decoded(item, reader.position)
        } match
          case DecodeResult.NeedMore => Left(Stop.Short)
          case DecodeResult.Failed(ProtocolError.MessageByteLimitExceeded) =>
            fail(Error.TooLarge(limit))
          case DecodeResult.Failed(error) => fail(Error.PeerItem(error))
          case DecodeResult.Decoded(value, consumed) =>
            at += consumed
            Right(value)
      }

  private[protocol] def arity(found: BigInt, expected: Int): Read[Unit] =
    if found == BigInt(expected) then Right(()) else fail(Error.Arity(expected, found))

  /** The peers of a `MsgSharePeers`: a definite or an indefinite list (`Codec.hs:89-93`). */
  private def peers[P](c: Cursor, peer: PeerCodec[P]): Read[Vector[P]] =
    @tailrec def definite(left: BigInt, acc: Vector[P]): Read[Vector[P]] =
      if left == 0 then Right(acc)
      else
        peer.readFrom(c) match
          case Right(a) => definite(left - 1, acc :+ a)
          case Left(s)  => Left(s)
    @tailrec def indefinite(acc: Vector[P]): Read[Vector[P]] =
      c.peekBreak() match
        case Left(s)     => Left(s)
        case Right(true) => c.break().map(_ => acc)
        case Right(false) =>
          peer.readFrom(c) match
            case Right(a) => indefinite(acc :+ a)
            case Left(s)  => Left(s)
    c.arrayOrIndefinite().flatMap {
      case Some(n) => definite(n, Vector.empty)
      case None    => indefinite(Vector.empty)
    }

  /** One message from the front of `bytes`; surplus bytes are left to the caller. The ceiling is
    * checked as fields are read; the caller bounds bytes retained while a message is incomplete.
    */
  private[protocol] def decodeTyped[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      peer: PeerCodec[P],
      limits: Limits
  ): Decoding[Message[P]] =
    val cursor = new Cursor(bytes, limits.maxMessageBytes)
    val agency = state match
      case State.Done => Left(Error.Terminal)
      case State.Idle => Either.cond(sender == Role.Client, (), Error.WrongAgency(state, sender))
      case State.Busy => Either.cond(sender == Role.Server, (), Error.WrongAgency(state, sender))
    val decoded: Read[Message[P]] = agency.left.map(Stop.Fail(_)).flatMap { _ =>
      for
        n <- cursor.definiteArray()
        key <- cursor.uint()
        message <- (state, key) match
          case (State.Idle, k) if k == 0 =>
            for
              _ <- arity(n, 2)
              amount <- cursor.ranged(Field.Amount, BigInt(Amount.Max))
            yield Message.ShareRequest(Amount.trusted(amount))
          case (State.Busy, k) if k == 1 =>
            for
              _ <- arity(n, 2)
              found <- peers(cursor, peer)
            yield Message.SharePeers(found)
          case (State.Idle, k) if k == 2 => arity(n, 1).map(_ => Message.Done)
          case _                         => fail(Error.UnexpectedKey(state, key))
      yield message
    }
    decoded match
      case Right(message)         => Decoding.Decoded(message, cursor.position)
      case Left(Stop.Short)       => Decoding.NeedMore
      case Left(Stop.Fail(error)) => Decoding.Failed(error)

  def decodePrefix[P](
      state: State,
      sender: Role,
      bytes: Bytes,
      peer: PeerCodec[P],
      limits: Limits = Limits()
  ): DecodeResult[Message[P]] =
    decodeTyped(state, sender, bytes, peer, limits) match
      case Decoding.NeedMore             => DecodeResult.NeedMore
      case Decoding.Failed(error)        => DecodeResult.Failed(ProtocolError.PeerSharing(error))
      case Decoding.Decoded(value, used) => DecodeResult.Decoded(value, used)

  private[protocol] def decodePeer[P](bytes: Bytes, peer: PeerCodec[P]): Either[Error, P] =
    val cursor = new Cursor(bytes, PeerSharing.UpstreamMessageBytes)
    peer.readFrom(cursor) match
      case Left(Stop.Short)       => Left(Error.Truncated)
      case Left(Stop.Fail(error)) => Left(error)
      case Right(value) =>
        Either.cond(
          cursor.position == bytes.size,
          value,
          Error.Trailing(bytes.size - cursor.position)
        )
