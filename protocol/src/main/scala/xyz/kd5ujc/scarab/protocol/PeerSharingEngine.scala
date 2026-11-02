// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.data.StateT
import cats.syntax.all.*
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
    private[protocol] def readFrom(cursor: Cursor): Parse[P] =
      cursor.item().flatMapF(item => decode(item).left.map(e => Stop.Fail(Error.PeerItem(e))))
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

  /** A read from a position of the input: the position after it, and its value, or why it stopped.
    */
  private[protocol] type Parse[A] = StateT[Read, Int, A]

  private[protocol] def fail[A](error: Error): Read[A] = Left(Stop.Fail(error))
  private val TwoTo64 = BigInt(1) << 64
  private def stopped[A](read: Read[A]): Parse[A] = StateT.liftF(read)

  /** The head of a CBOR item: its initial byte, major type, and the argument (absent when
    * indefinite).
    */
  private[protocol] final case class Head(initial: Int, major: Int, argument: Option[BigInt])

  /** The reads of a prefix of `input`; the bytes read never pass `limit`. */
  private[protocol] final class Cursor(input: Bytes, limit: Int):
    private def need(n: Int): Parse[Unit] =
      StateT.inspectF(at =>
        if n > limit - at then fail(Error.TooLarge(limit))
        else if n > input.size - at then Left(Stop.Short)
        else Right(())
      )

    private def byte(): Parse[Int] =
      need(1) *> StateT(at => input.lift(at).toRight(Stop.Short).map(b => (at + 1, b & 255)))

    def head(): Parse[Head] =
      byte().flatMap { initial =>
        val major = initial >> 5
        val info = initial & 31
        if info < 24 then stopped(Right(Head(initial, major, Some(BigInt(info)))))
        else if info == 31 then stopped(Right(Head(initial, major, None)))
        else if info > 27 then stopped(fail(Error.ReservedHead(initial)))
        else
          val width = 1 << (info - 24)
          need(width) *> StateT { at =>
            // eight bytes above 2^63 read as a negative Long: the argument is unsigned
            input
              .bigEndian(at, width)
              .toRight(Stop.Short)
              .map(v =>
                (at + width, Head(initial, major, Some(BigInt(v) + (if v < 0 then TwoTo64 else 0))))
              )
          }
      }

    /** An unsigned integer in any definite width. */
    def uint(): Parse[BigInt] =
      head().flatMapF {
        case Head(_, 0, Some(value)) => Right(value)
        case Head(initial, 0, None)  => fail(Error.ReservedHead(initial))
        case Head(initial, _, _)     => fail(Error.Unexpected(Expectation.UnsignedInteger, initial))
      }

    def ranged(field: Field, max: BigInt): Parse[Long] =
      uint().flatMapF(v => if v > max then fail(Error.OutOfRange(field, v)) else Right(v.toLong))

    def definiteArray(): Parse[BigInt] =
      head().flatMapF {
        case Head(_, 4, Some(n)) => Right(n)
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.DefiniteArray, initial))
      }

    /** The length of an array, or `None` for an indefinite one. */
    def arrayOrIndefinite(): Parse[Option[BigInt]] =
      head().flatMapF {
        case Head(_, 4, length) => Right(length)
        case Head(initial, _, _) =>
          fail(Error.Unexpected(Expectation.ArrayOrIndefiniteArray, initial))
      }

    def peekBreak(): Parse[Boolean] =
      need(1) *> StateT.inspectF[Read, Int, Boolean](at =>
        input.lift(at).map(_ == 0xff.toByte).toRight(Stop.Short)
      )

    def break(): Parse[Unit] =
      head().flatMapF {
        case Head(_, 7, None)    => Right(())
        case Head(initial, _, _) => fail(Error.Unexpected(Expectation.Break, initial))
      }

    /** One complete opaque peer item, bounded by this message's remaining byte budget. */
    def item(): Parse[Bytes] =
      need(1) *> StateT { at =>
        val bounds = itemLimits(limit - at)
        ProtocolWire.prefix {
          val reader = new ProtocolWire.Reader(input.drop(at), bounds)
          val item = reader.item()
          DecodeResult.Decoded(item, reader.position)
        } match
          case DecodeResult.NeedMore => Left(Stop.Short)
          case DecodeResult.Failed(ProtocolError.MessageByteLimitExceeded) =>
            fail(Error.TooLarge(limit))
          case DecodeResult.Failed(error)            => fail(Error.PeerItem(error))
          case DecodeResult.Decoded(value, consumed) => Right((at + consumed, value))
      }

  private[protocol] def arity(found: BigInt, expected: Int): Parse[Unit] =
    stopped(if found == BigInt(expected) then Right(()) else fail(Error.Arity(expected, found)))

  /** The peers of a `MsgSharePeers`: a definite or an indefinite list (`Codec.hs:89-93`). */
  private def peers[P](c: Cursor, peer: PeerCodec[P]): Parse[Vector[P]] =
    c.arrayOrIndefinite().flatMap {
      case Some(n) =>
        (n, Vector.empty[P]).tailRecM { (left, acc) =>
          if left == 0 then stopped(Right(Right(acc)))
          else peer.readFrom(c).map(a => Left((left - 1, acc :+ a)))
        }
      case None =>
        Vector.empty[P].tailRecM { acc =>
          c.peekBreak().flatMap {
            case true  => c.break().as(Right(acc))
            case false => peer.readFrom(c).map(a => Left(acc :+ a))
          }
        }
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
    val message: Parse[Message[P]] =
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
          case (State.Idle, k) if k == 2 => arity(n, 1).as(Message.Done)
          case _                         => stopped(fail(Error.UnexpectedKey(state, key)))
      yield message
    agency.left.map(Stop.Fail(_)).flatMap(_ => message.run(0)) match
      case Right((used, message)) => Decoding.Decoded(message, used)
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
    peer.readFrom(new Cursor(bytes, PeerSharing.UpstreamMessageBytes)).run(0) match
      case Left(Stop.Short)       => Left(Error.Truncated)
      case Left(Stop.Fail(error)) => Left(error)
      case Right((used, value)) =>
        Either.cond(used == bytes.size, value, Error.Trailing(bytes.size - used))
