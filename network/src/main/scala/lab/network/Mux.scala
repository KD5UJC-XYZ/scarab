// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes

/** Handshake-slice framing derived from Network.Mux.Codec at ouroboros-network
  * c45735a56c567fa977969173d18943bac6bb3821 (Apache-2.0). This is an independent implementation,
  * not a general-purpose multiplexer or a reference-runtime interoperability claim.
  */
object Mux:
  val HeaderBytes = 8
  val WireMaxPayloadBytes = 65535
  val HandshakeMaxMessageBytes = 5760

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

  private def validPayloadLimit(limit: Int): Either[String, Unit] =
    Either.cond(limit >= 1 && limit <= WireMaxPayloadBytes, (), "invalid local mux payload limit")

  private def validate(header: Header, limit: Int): Either[String, Unit] =
    for
      _ <- validPayloadLimit(limit)
      _ <- Either.cond(
        header.timestamp >= 0 && header.timestamp <= 0xffffffffL,
        (),
        "mux timestamp outside unsigned 32-bit range"
      )
      _ <- Either.cond(
        header.protocol >= 0 && header.protocol <= 0x7fff,
        (),
        "mux protocol outside unsigned 15-bit range"
      )
      _ <- Either.cond(
        header.payloadLength > 0 && header.payloadLength <= limit,
        (),
        "mux payload length is zero or exceeds local/wire limit"
      )
    yield ()

  def encodeHeader(header: Header): Either[String, Bytes] =
    validate(header, WireMaxPayloadBytes).map { _ =>
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
  ): Either[String, Header] =
    if bytes.size != HeaderBytes then Left("mux header must contain exactly 8 bytes")
    else
      def u(index: Int): Int = bytes.value(index) & 0xff
      val timestamp = (u(0).toLong << 24) | (u(1).toLong << 16) | (u(2).toLong << 8) | u(3).toLong
      val protocol = (u(4) << 8) | u(5)
      val header = Header(
        timestamp,
        protocol & 0x7fff,
        if (protocol & 0x8000) == 0 then Direction.Initiator else Direction.Responder,
        (u(6) << 8) | u(7)
      )
      validate(header, maxPayloadBytes).map(_ => header)

  def encode(sdu: Sdu): Either[String, Bytes] =
    encodeHeader(sdu.header).map(header => Bytes(header.value ++ sdu.payload.value))

  /** Exact single-frame decode; trailing bytes are an error. For a stream use Decoder. */
  def decode(
      bytes: Bytes,
      maxPayloadBytes: Int = WireMaxPayloadBytes
  ): Either[String, Sdu] =
    for
      header <- decodeHeader(Bytes(bytes.value.take(HeaderBytes)), maxPayloadBytes)
      _ <- Either.cond(
        bytes.size == HeaderBytes + header.payloadLength,
        (),
        "mux payload is truncated or has trailing bytes"
      )
    yield Sdu(
      header.timestamp,
      header.protocol,
      header.direction,
      Bytes(bytes.value.drop(HeaderBytes))
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
  ): Either[String, Vector[Sdu]] =
    for
      _ <- validPayloadLimit(maxPayloadBytes)
      _ <- Either.cond(
        payload.size > 0 && payload.size <= HandshakeMaxMessageBytes,
        (),
        "handshake message is empty or exceeds 5760 bytes"
      )
      _ <- validate(
        Header(timestamp, protocol, direction, math.min(payload.size, maxPayloadBytes)),
        maxPayloadBytes
      )
    yield payload.value
      .grouped(maxPayloadBytes)
      .map(part => Sdu(timestamp, protocol, direction, Bytes(part)))
      .toVector

  /** General bounded segmentation; message and SDU limits are independent. */
  def segmentBounded(
      payload: Bytes,
      timestamp: Long,
      direction: Direction,
      maxPayloadBytes: Int,
      protocol: Int,
      maxMessageBytes: Int
  ): Either[String, Vector[Sdu]] =
    for
      _ <- validPayloadLimit(maxPayloadBytes)
      _ <- Either.cond(
        maxMessageBytes > 0 && maxMessageBytes <= 65535 &&
          payload.size > 0 && payload.size <= maxMessageBytes,
        (),
        "message byte limit exceeded"
      )
      _ <- validate(
        Header(timestamp, protocol, direction, math.min(payload.size, maxPayloadBytes)),
        maxPayloadBytes
      )
    yield payload.value
      .grouped(maxPayloadBytes)
      .map(part => Sdu(timestamp, protocol, direction, Bytes(part)))
      .toVector

  /** Immutable partial-frame state. Failed feeds publish neither state nor partially decoded
    * output. An empty feed is a no-op, not EOF; finish explicitly verifies EOF. Retained data is at
    * most one incomplete frame, bounded by eight header bytes plus maxPayloadBytes - 1 payload
    * bytes.
    */
  final class Decoder private (
      val limits: Limits,
      private val partialHeader: Vector[Byte],
      private val activeHeader: Option[Header],
      private val partialPayload: Vector[Byte]
  ):
    def pendingBytes: Int =
      partialHeader.size + activeHeader.fold(0)(_ => HeaderBytes) + partialPayload.size

    def feed(bytes: Bytes): Either[String, (Decoder, Vector[Sdu])] =
      if bytes.size > limits.maxInputBytes then Left("mux feed exceeds local input byte limit")
      else if bytes.size == 0 then Right((this, Vector.empty))
      else
        var headerBytes = partialHeader
        var header = activeHeader
        var payload = partialPayload
        var offset = 0
        var emitted = 0
        var error: Option[String] = None
        val output = Vector.newBuilder[Sdu]
        while offset < bytes.size && error.isEmpty do
          header match
            case None =>
              val count = math.min(HeaderBytes - headerBytes.size, bytes.size - offset)
              headerBytes = headerBytes ++ bytes.value.slice(offset, offset + count)
              offset += count
              if headerBytes.size == HeaderBytes then
                decodeHeader(Bytes(headerBytes), limits.maxPayloadBytes) match
                  case Left(reason) => error = Some(reason)
                  case Right(decoded) =>
                    header = Some(decoded)
                    headerBytes = Vector.empty
            case Some(current) =>
              val count = math.min(current.payloadLength - payload.size, bytes.size - offset)
              payload = payload ++ bytes.value.slice(offset, offset + count)
              offset += count
              if payload.size == current.payloadLength then
                if emitted >= limits.maxFramesPerFeed then
                  error = Some("mux feed exceeds local frame count limit")
                else
                  output += Sdu(
                    current.timestamp,
                    current.protocol,
                    current.direction,
                    Bytes(payload)
                  )
                  emitted += 1
                  payload = Vector.empty
                  header = None
        error match
          case Some(reason) => Left(reason)
          case None => Right((new Decoder(limits, headerBytes, header, payload), output.result()))

    def finish: Either[String, Unit] =
      if activeHeader.nonEmpty then Left("EOF in mux payload")
      else if partialHeader.nonEmpty then Left("EOF in mux header")
      else Right(())

  object Decoder:
    def create(limits: Limits = Limits()): Either[String, Decoder] =
      for
        _ <- validPayloadLimit(limits.maxPayloadBytes)
        _ <- Either.cond(limits.maxInputBytes > 0, (), "invalid mux input byte limit")
        _ <- Either.cond(limits.maxFramesPerFeed > 0, (), "invalid mux frame count limit")
      yield new Decoder(limits, Vector.empty, None, Vector.empty)
