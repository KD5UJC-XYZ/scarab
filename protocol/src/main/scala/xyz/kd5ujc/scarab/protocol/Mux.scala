// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

/** Pure SDU framing derived from Network.Mux.Codec at ouroboros-network
  * c45735a56c567fa977969173d18943bac6bb3821 (Apache-2.0). The network module uses this codec for
  * its multiplexer; protocol scheduling, transport and interoperability evidence belong there.
  */
object Mux:
  val HeaderBytes = 8
  val WireMaxPayloadBytes = 65535
  val HandshakeMaxMessageBytes = 5760

  /** Framing and local limit failures. Payloads retain the values needed by callers; messages are
    * rendered only for presentation.
    */
  enum Error:
    case InvalidPayloadLimit(actual: Int)
    case TimestampOutOfRange(actual: Long)
    case ProtocolOutOfRange(actual: Int)
    case PayloadLengthOutOfRange(actual: Int, limit: Int)
    case HeaderLength(actual: Int)
    case FrameLength(expected: Int, actual: Int)
    case HandshakeLength(actual: Int)
    case InvalidMessageLimit(actual: Int, maximum: Int)
    case MessageLength(actual: Int, limit: Int)
    case FeedInputLimit(actual: Int, limit: Int)
    case FeedFrameLimit(actual: Int, limit: Int)
    case TruncatedHeader(received: Int)
    case TruncatedPayload(expected: Int, received: Int)
    case InvalidInputLimit(actual: Int)
    case InvalidFrameLimit(actual: Int)

    def message: String = this match
      case InvalidPayloadLimit(_)        => "invalid local mux payload limit"
      case TimestampOutOfRange(_)        => "mux timestamp outside unsigned 32-bit range"
      case ProtocolOutOfRange(_)         => "mux protocol outside unsigned 15-bit range"
      case PayloadLengthOutOfRange(_, _) => "mux payload length is zero or exceeds local/wire limit"
      case HeaderLength(_)               => "mux header must contain exactly 8 bytes"
      case FrameLength(_, _)             => "mux payload is truncated or has trailing bytes"
      case HandshakeLength(_)            => "handshake message is empty or exceeds 5760 bytes"
      case InvalidMessageLimit(_, _) | MessageLength(_, _) => "message byte limit exceeded"
      case FeedInputLimit(_, _)   => "mux feed exceeds local input byte limit"
      case FeedFrameLimit(_, _)   => "mux feed exceeds local frame count limit"
      case TruncatedHeader(_)     => "EOF in mux header"
      case TruncatedPayload(_, _) => "EOF in mux payload"
      case InvalidInputLimit(_)   => "invalid mux input byte limit"
      case InvalidFrameLimit(_)   => "invalid mux frame count limit"

  enum Direction:
    case Initiator, Responder

  final case class Header(timestamp: Long, protocol: Int, direction: Direction, payloadLength: Int)
  final case class Sdu(timestamp: Long, protocol: Int, direction: Direction, payload: Bytes):
    def header: Header = Header(timestamp, protocol, direction, payload.size)

  /** Local policy, separate from the wire limit. Input/output per feed is bounded as well as
    * retained partial-frame state. Callers should split large reads before feeding this decoder.
    */
  final case class Limits(
      maxPayloadBytes: Int = WireMaxPayloadBytes,
      maxInputBytes: Int = 1048576,
      maxFramesPerFeed: Int = 4096
  )

  private def validPayloadLimit(limit: Int): Either[Error, Unit] =
    Either.cond(limit >= 1 && limit <= WireMaxPayloadBytes, (), Error.InvalidPayloadLimit(limit))

  private def validate(
      header: Header,
      limit: Int,
      allowEmpty: Boolean = false
  ): Either[Error, Unit] =
    for
      _ <- validPayloadLimit(limit)
      _ <- Either.cond(
        header.timestamp >= 0 && header.timestamp <= 0xffffffffL,
        (),
        Error.TimestampOutOfRange(header.timestamp)
      )
      _ <- Either.cond(
        header.protocol >= 0 && header.protocol <= 0x7fff,
        (),
        Error.ProtocolOutOfRange(header.protocol)
      )
      _ <- Either.cond(
        header.payloadLength >= (if allowEmpty then 0 else 1) && header.payloadLength <= limit,
        (),
        Error.PayloadLengthOutOfRange(header.payloadLength, limit)
      )
    yield ()

  /** The native wire encoder permits empty SDUs; admission and the native decoder reject them. */
  def encodeHeader(header: Header): Either[Error, Bytes] =
    validate(header, WireMaxPayloadBytes, allowEmpty = true).map { _ =>
      // Follow putNumAndMode/getDir, not the reversed prose comment in the pinned source.
      val protocol =
        header.protocol | (if header.direction == Direction.Responder then 0x8000 else 0)
      Bytes(
        Vector(
          (header.timestamp >>> 24).toByte,
          (header.timestamp >>> 16).toByte,
          (header.timestamp >>> 8).toByte,
          header.timestamp.toByte,
          (protocol >>> 8).toByte,
          protocol.toByte,
          (header.payloadLength >>> 8).toByte,
          header.payloadLength.toByte
        )
      )
    }

  /** Requires exactly one eight-byte header. No payload allocation follows an unchecked peer
    * length.
    */
  def decodeHeader(
      bytes: Bytes,
      maxPayloadBytes: Int = WireMaxPayloadBytes
  ): Either[Error, Header] =
    if bytes.size != HeaderBytes then Left(Error.HeaderLength(bytes.size))
    else
      def u(index: Int): Int = bytes(index) & 0xff
      val timestamp = (u(0).toLong << 24) | (u(1).toLong << 16) | (u(2).toLong << 8) | u(3).toLong
      val protocol = (u(4) << 8) | u(5)
      val header = Header(
        timestamp,
        protocol & 0x7fff,
        if (protocol & 0x8000) == 0 then Direction.Initiator else Direction.Responder,
        (u(6) << 8) | u(7)
      )
      validate(header, maxPayloadBytes).map(_ => header)

  def encode(sdu: Sdu): Either[Error, Bytes] =
    encodeHeader(sdu.header).map(header => header ++ sdu.payload)

  /** Exact single-frame decode; trailing bytes are an error. For a stream use Decoder. */
  def decode(
      bytes: Bytes,
      maxPayloadBytes: Int = WireMaxPayloadBytes
  ): Either[Error, Sdu] =
    for
      header <- decodeHeader(bytes.take(HeaderBytes), maxPayloadBytes)
      _ <- Either.cond(
        bytes.size == HeaderBytes + header.payloadLength,
        (),
        Error.FrameLength(HeaderBytes + header.payloadLength, bytes.size)
      )
    yield Sdu(
      header.timestamp,
      header.protocol,
      header.direction,
      bytes.drop(HeaderBytes).compact
    )

  /** Segment one bounded handshake message. Timestamp is supplied explicitly; it is a remote wire
    * clock, not a trusted timer. Segmentation does not imply CBOR message boundaries on receipt.
    */
  def segment(
      payload: Bytes,
      timestamp: Long,
      direction: Direction,
      maxPayloadBytes: Int,
      protocol: Int = 0
  ): Either[Error, Vector[Sdu]] =
    for
      _ <- validPayloadLimit(maxPayloadBytes)
      _ <- Either.cond(
        payload.size > 0 && payload.size <= HandshakeMaxMessageBytes,
        (),
        Error.HandshakeLength(payload.size)
      )
      _ <- validate(
        Header(timestamp, protocol, direction, math.min(payload.size, maxPayloadBytes)),
        maxPayloadBytes
      )
    yield (0 until payload.size by maxPayloadBytes).toVector
      .map(from =>
        Sdu(
          timestamp,
          protocol,
          direction,
          payload.slice(from, from + math.min(maxPayloadBytes, payload.size - from))
        )
      )

  /** General bounded segmentation; message and SDU limits are independent. */
  def segmentBounded(
      payload: Bytes,
      timestamp: Long,
      direction: Direction,
      maxPayloadBytes: Int,
      protocol: Int,
      maxMessageBytes: Int
  ): Either[Error, Vector[Sdu]] =
    if maxMessageBytes > 65535 then Left(Error.InvalidMessageLimit(maxMessageBytes, 65535))
    else
      segmentProtocolMessage(
        payload,
        timestamp,
        direction,
        maxPayloadBytes,
        protocol,
        maxMessageBytes
      ).left.map {
        case Error.InvalidMessageLimit(actual, _) =>
          Error.InvalidMessageLimit(actual, WireMaxPayloadBytes)
        case error => error
      }

  /** Protocol messages may span SDUs. The SDU limit is still uint16; this ceiling bounds the
    * aggregate payload before allocating the finite segment vector.
    */
  def segmentProtocolMessage(
      payload: Bytes,
      timestamp: Long,
      direction: Direction,
      maxPayloadBytes: Int,
      protocol: Int,
      maxMessageBytes: Int
  ): Either[Error, Vector[Sdu]] =
    for
      _ <- validPayloadLimit(maxPayloadBytes)
      _ <- Either.cond(
        maxMessageBytes > 0,
        (),
        Error.InvalidMessageLimit(maxMessageBytes, Int.MaxValue)
      )
      _ <- Either.cond(
        payload.size > 0 && payload.size <= maxMessageBytes,
        (),
        Error.MessageLength(payload.size, maxMessageBytes)
      )
      _ <- validate(
        Header(timestamp, protocol, direction, math.min(payload.size, maxPayloadBytes)),
        maxPayloadBytes
      )
    yield (0 until payload.size by maxPayloadBytes).toVector
      .map(from =>
        Sdu(
          timestamp,
          protocol,
          direction,
          payload.slice(from, from + math.min(maxPayloadBytes, payload.size - from))
        )
      )

  /** Immutable partial-frame state. Failed feeds publish neither state nor partially decoded
    * output. An empty feed is a no-op, not EOF; finish explicitly verifies EOF. Retained data is at
    * most one incomplete frame, bounded by eight header bytes plus maxPayloadBytes - 1 payload
    * bytes.
    *
    * The payload of a frame that spans feeds is kept as its parts, newest first, and joined once
    * when the frame completes, as the reference's socket bearer collects the chunks of one read and
    * concatenates them at the end (network-mux 0.11.0.0, `Network/Mux/Bearer/Socket.hs`,
    * `recvLen'`). Appending each feed to a joined payload instead copied the frame so far on every
    * feed: a frame of `n` bytes fed a byte at a time cost about `n * n / 2` bytes. A part carried
    * to the next feed is compacted so that it does not pin the caller's larger read buffer; every
    * payload byte is therefore copied at most twice. The bound above counts payload bytes; each
    * carried part also holds about 80 bytes of objects (its list cell, `Bytes` and array header),
    * so a frame of the largest payload fed a byte at a time holds about 5 MB until it completes, as
    * the reference's chunk list does for one SDU.
    *
    * @param partialParts
    *   the received parts of the active frame's payload, newest first; empty without one
    * @param partialSize
    *   the total size of `partialParts`
    */
  final class Decoder private (
      val limits: Limits,
      private val partialHeader: Bytes,
      private val activeHeader: Option[Header],
      private val partialParts: List[Bytes],
      private val partialSize: Int
  ):
    def pendingBytes: Int =
      partialHeader.size + activeHeader.fold(0)(_ => HeaderBytes) + partialSize

    def feed(bytes: Bytes): Either[Error, (Decoder, Vector[Sdu])] =
      if bytes.size > limits.maxInputBytes then
        Left(Error.FeedInputLimit(bytes.size, limits.maxInputBytes))
      else if bytes.size == 0 then Right((this, Vector.empty))
      else
        var headerBytes = partialHeader
        var header = activeHeader
        var parts = partialParts
        var received = partialSize
        var offset = 0
        var emitted = 0
        var error: Option[Error] = None
        val output = Vector.newBuilder[Sdu]
        while offset < bytes.size && error.isEmpty do
          header match
            case None =>
              val count = math.min(HeaderBytes - headerBytes.size, bytes.size - offset)
              headerBytes = headerBytes ++ bytes.slice(offset, offset + count)
              offset += count
              if headerBytes.size == HeaderBytes then
                decodeHeader(headerBytes, limits.maxPayloadBytes) match
                  case Left(reason) => error = Some(reason)
                  case Right(decoded) =>
                    header = Some(decoded)
                    headerBytes = Bytes.empty
            case Some(current) =>
              val count = math.min(current.payloadLength - received, bytes.size - offset)
              parts = bytes.slice(offset, offset + count) :: parts
              received += count
              offset += count
              if received == current.payloadLength then
                if emitted >= limits.maxFramesPerFeed then
                  error = Some(Error.FeedFrameLimit(emitted + 1, limits.maxFramesPerFeed))
                else
                  output += Sdu(
                    current.timestamp,
                    current.protocol,
                    current.direction,
                    Decoder.joined(parts, received)
                  )
                  emitted += 1
                  parts = Nil
                  received = 0
                  header = None
        error match
          case Some(reason) => Left(reason)
          case None         =>
            // only the newest part can come from this feed; the older ones are compact already
            val carried = parts match
              case newest :: older => newest.compact :: older
              case Nil             => Nil
            Right((new Decoder(limits, headerBytes, header, carried, received), output.result()))

    def finish: Either[Error, Unit] =
      activeHeader match
        case Some(header) => Left(Error.TruncatedPayload(header.payloadLength, partialSize))
        case None if partialHeader.nonEmpty => Left(Error.TruncatedHeader(partialHeader.size))
        case None                           => Right(())

  object Decoder:
    def create(limits: Limits = Limits()): Either[Error, Decoder] =
      for
        _ <- validPayloadLimit(limits.maxPayloadBytes)
        _ <- Either.cond(
          limits.maxInputBytes > 0,
          (),
          Error.InvalidInputLimit(limits.maxInputBytes)
        )
        _ <- Either.cond(
          limits.maxFramesPerFeed > 0,
          (),
          Error.InvalidFrameLimit(limits.maxFramesPerFeed)
        )
      yield new Decoder(limits, Bytes.empty, None, Nil, 0)

    /** The payload whose parts, newest first, total `size` bytes; a single part is used as it is.
      */
    private def joined(parts: List[Bytes], size: Int): Bytes = parts match
      case only :: Nil => only
      case _ =>
        val out = new Array[Byte](size)
        var end = size
        parts.foreach { part =>
          end -= part.size
          part.copyTo(out, end)
        }
        Bytes.wrap(out)
