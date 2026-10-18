// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.Mux.*

class MuxSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.toString), identity)
  private def right[A](value: Either[Error, A]): A = value.fold(e => fail(e.message), identity)
  private def decoder(limits: Limits = Limits()): Decoder = right(Decoder.create(limits))
  private def join(left: Bytes, right: Bytes): Bytes = Bytes(left.value ++ right.value)

  // Source-derived literals from pinned executable Haskell expressions, NOT reference-emitted bytes.
  // Proposal payload is the upstream Scalus manually constructed CDDL golden at 79a056d0db66eae034d0aa0120f4566bce7d298b.
  private val proposal = bytes("00000000000000098200a10e8402f500f4")
  private val accept = bytes("000000008000000883010e8402f500f4")
  private val proposalSdu = Sdu(0, 0, Direction.Initiator, bytes("8200a10e8402f500f4"))
  private val acceptSdu = Sdu(0, 0, Direction.Responder, bytes("83010e8402f500f4"))

  test("source-derived literal headers use executable direction bits") {
    assertEquals(right(encode(proposalSdu)), proposal)
    assertEquals(right(encode(acceptSdu)), accept)
    assertEquals(right(decode(proposal)), proposalSdu)
    assertEquals(right(decode(accept)), acceptSdu)
    assertEquals(
      right(encodeHeader(Header(0xffffffffL, 0x7fff, Direction.Responder, 65535))).hex,
      "ffffffffffffffff"
    )
    assertEquals(
      right(decodeHeader(bytes("ffffffff7fffffff"))),
      Header(0xffffffffL, 0x7fff, Direction.Initiator, 65535)
    )
    assertEquals(
      right(decodeHeader(bytes("0102030481230001"))),
      Header(0x01020304L, 0x123, Direction.Responder, 1)
    )
  }

  test("wire ranges and exact single-frame boundaries are checked") {
    val valid = Header(0, 0, Direction.Initiator, 1)
    Vector(
      valid.copy(timestamp = -1) -> Error.TimestampOutOfRange(-1),
      valid.copy(timestamp = 0x100000000L) -> Error.TimestampOutOfRange(0x100000000L),
      valid.copy(protocol = -1) -> Error.ProtocolOutOfRange(-1),
      valid.copy(protocol = 0x8000) -> Error.ProtocolOutOfRange(0x8000),
      valid.copy(payloadLength = 0) -> Error.PayloadLengthOutOfRange(0, WireMaxPayloadBytes),
      valid.copy(payloadLength = -1) -> Error.PayloadLengthOutOfRange(-1, WireMaxPayloadBytes),
      valid.copy(payloadLength = 65536) -> Error.PayloadLengthOutOfRange(65536, WireMaxPayloadBytes)
    ).foreach { case (header, error) => assertEquals(encodeHeader(header), Left(error)) }
    assertEquals(
      encode(Sdu(0, 0, Direction.Initiator, Bytes.empty)),
      Left(Error.PayloadLengthOutOfRange(0, WireMaxPayloadBytes))
    )
    assertEquals(
      decodeHeader(bytes("0000000000000000")),
      Left(Error.PayloadLengthOutOfRange(0, WireMaxPayloadBytes))
    )
    assertEquals(decodeHeader(bytes("00000000000001")), Left(Error.HeaderLength(7)))
    assertEquals(decodeHeader(bytes("000000000000000100")), Left(Error.HeaderLength(9)))
    assertEquals(decode(proposal.take(proposal.size - 1)), Left(Error.FrameLength(17, 16)))
    assertEquals(decode(join(proposal, bytes("00"))), Left(Error.FrameLength(17, 18)))
    assertEquals(decode(proposal, maxPayloadBytes = 8), Left(Error.PayloadLengthOutOfRange(9, 8)))
    assertEquals(decode(proposal, maxPayloadBytes = 0), Left(Error.InvalidPayloadLimit(0)))
    assertEquals(decode(proposal, maxPayloadBytes = 65536), Left(Error.InvalidPayloadLimit(65536)))
  }

  test("every split of two coalesced literal SDUs preserves frames and EOF") {
    val wire = join(proposal, accept)
    for split <- 0 to wire.size do
      val start = decoder()
      val (partial, first) = right(start.feed(Bytes(wire.value.take(split))))
      val (done, second) = right(partial.feed(Bytes(wire.value.drop(split))))
      assertEquals(first ++ second, Vector(proposalSdu, acceptSdu), s"split $split")
      assertEquals(done.pendingBytes, 0)
      assertEquals(done.finish, Right(()))
      // A feed never changes the previous state.
      assertEquals(start.pendingBytes, 0)
      assertEquals(start.finish, Right(()))
  }

  test("byte-at-a-time delivery, empty reads and preserved next-frame leftovers") {
    var state = decoder()
    var result = Vector.empty[Sdu]
    for byte <- join(proposal, accept).value do
      val (same, empty) = right(state.feed(Bytes.empty))
      assert(same eq state)
      assertEquals(empty, Vector.empty)
      val (next, frames) = right(state.feed(Bytes(Vector(byte))))
      state = next
      result ++= frames
    assertEquals(result, Vector(proposalSdu, acceptSdu))
    assertEquals(state.finish, Right(()))
    val (partial, frames) = right(decoder().feed(join(proposal, Bytes(accept.value.take(10)))))
    assertEquals(frames, Vector(proposalSdu))
    assertEquals(partial.pendingBytes, 10)
    assertEquals(partial.finish, Left(Error.TruncatedPayload(8, 2)))
    val (done, last) = right(partial.feed(Bytes(accept.value.drop(10))))
    assertEquals(last, Vector(acceptSdu))
    assertEquals(done.finish, Right(()))
  }

  test("EOF rejects every truncated header and payload, but allows an empty stream") {
    assertEquals(decoder().finish, Right(()))
    for length <- 1 until proposal.size do
      val (partial, frames) = right(decoder().feed(Bytes(proposal.value.take(length))))
      assertEquals(frames, Vector.empty)
      val expected =
        if length < HeaderBytes then Error.TruncatedHeader(length)
        else Error.TruncatedPayload(proposalSdu.payload.size, length - HeaderBytes)
      assertEquals(partial.finish, Left(expected), s"length $length")
      assertEquals(partial.pendingBytes, length)
  }

  test("local resource limits reject a header before payload retention and bound batches") {
    Vector(
      Limits(maxPayloadBytes = 0) -> Error.InvalidPayloadLimit(0),
      Limits(maxPayloadBytes = 65536) -> Error.InvalidPayloadLimit(65536),
      Limits(maxInputBytes = 0) -> Error.InvalidInputLimit(0),
      Limits(maxFramesPerFeed = 0) -> Error.InvalidFrameLimit(0)
    ).foreach { case (limits, error) => assertEquals(Decoder.create(limits), Left(error)) }
    val limited = decoder(Limits(maxPayloadBytes = 8))
    assertEquals(limited.feed(proposal.take(8)), Left(Error.PayloadLengthOutOfRange(9, 8)))
    assertEquals(limited.feed(bytes("0000000000000000")), Left(Error.PayloadLengthOutOfRange(0, 8)))
    assertEquals(limited.pendingBytes, 0)
    assertEquals(
      decoder(Limits(maxInputBytes = 16)).feed(proposal),
      Left(Error.FeedInputLimit(17, 16))
    )
    val single = decoder(Limits(maxFramesPerFeed = 1))
    assertEquals(single.feed(join(proposal, accept)), Left(Error.FeedFrameLimit(2, 1)))
    assertEquals(single.pendingBytes, 0)
    assertEquals(right(single.feed(accept))._2, Vector(acceptSdu))
    val (one, first) = right(decoder(Limits(maxFramesPerFeed = 1)).feed(proposal))
    val (two, second) = right(one.feed(accept))
    assertEquals(first ++ second, Vector(proposalSdu, acceptSdu))
    assertEquals(two.finish, Right(()))
  }

  test("full u16 payload capacity is accepted and never conflated with bearer defaults") {
    val payload = Bytes(Vector.fill(65535)(0x42.toByte))
    val frame = Sdu(0xffffffffL, 0x7fff, Direction.Responder, payload)
    val wire = right(encode(frame))
    assertEquals(wire.value.take(8), bytes("ffffffffffffffff").value)
    assertEquals(right(decode(wire)), frame)
    val (partial, none) = right(decoder().feed(Bytes(wire.value.dropRight(1))))
    assertEquals(none, Vector.empty)
    assertEquals(partial.pendingBytes, 65542)
    val (done, frames) = right(partial.feed(Bytes(wire.value.takeRight(1))))
    assertEquals(frames, Vector(frame))
    assertEquals(done.pendingBytes, 0)
    assertEquals(done.finish, Right(()))
    assert(encode(frame.copy(payload = Bytes(payload.value :+ 0.toByte))).isLeft)
  }

  test("configurable segmentation obeys local SDU size and bounded handshake message size") {
    for size <- Vector(1, 2, 3, 8, 9, 12228, 65535) do
      val parts = right(segment(proposalSdu.payload, 42, Direction.Initiator, size))
      assert(parts.forall(s => s.payload.size > 0 && s.payload.size <= size))
      assert(parts.forall(s => s.timestamp == 42 && s.protocol == 0))
      assertEquals(Bytes(parts.flatMap(_.payload.value)), proposalSdu.payload)
      val encoded = Bytes(parts.flatMap(p => right(encode(p)).value))
      val (done, received) = right(decoder().feed(encoded))
      assertEquals(received, parts)
      assertEquals(done.finish, Right(()))
    assert(segment(Bytes.empty, 0, Direction.Initiator, 1).isLeft)
    assert(segment(proposalSdu.payload, 0, Direction.Initiator, 0).isLeft)
    assert(segment(proposalSdu.payload, 0, Direction.Initiator, 65536).isLeft)
    assert(segment(proposalSdu.payload, -1, Direction.Initiator, 1).isLeft)
    assert(segment(proposalSdu.payload, 0, Direction.Initiator, 1, protocol = 32768).isLeft)
    assert(segment(Bytes(Vector.fill(5761)(0.toByte)), 0, Direction.Initiator, 10).isLeft)
    assertEquals(
      right(segment(Bytes(Vector.fill(5760)(0.toByte)), 0, Direction.Initiator, 5760)).size,
      1
    )
  }

  test("general bounded segmentation separates large application and handshake limits") {
    val payload = Bytes(Vector.fill(7807)(0.toByte))
    assertEquals(
      Mux.segment(payload, 0L, Mux.Direction.Responder, 256, 5),
      Left(Error.HandshakeLength(7807))
    )
    val frames =
      Mux.segmentBounded(payload, 0L, Mux.Direction.Responder, 256, 5, 65535).toOption.get
    assertEquals(Bytes(frames.flatMap(_.payload.value)), payload)
    assert(frames.forall(s => s.protocol == 5 && s.payload.size <= 256))
    assertEquals(
      Mux.segmentBounded(payload, 0L, Mux.Direction.Responder, 256, 5, 7806),
      Left(Error.MessageLength(7807, 7806))
    )
    assertEquals(
      Mux.segmentBounded(payload, 0L, Mux.Direction.Responder, 256, 5, 65536),
      Left(Error.InvalidMessageLimit(65536, 65535))
    )
    Vector(0, -1, 2500001).foreach { limit =>
      assertEquals(
        segmentProtocolMessage(payload, 0, Direction.Responder, 256, 5, limit),
        Left(Error.InvalidMessageLimit(limit, 2500000))
      )
    }
    assertEquals(
      segmentProtocolMessage(Bytes.empty, 0, Direction.Responder, 256, 5, 100),
      Left(Error.MessageLength(0, 100))
    )
  }

  test("bounded message-limit errors retain their own ceiling and validation precedence") {
    val payload = bytes("00")
    Vector(0, -1, Int.MinValue).foreach { messageLimit =>
      assertEquals(
        segmentBounded(payload, -1, Direction.Initiator, 256, -1, messageLimit),
        Left(Error.InvalidMessageLimit(messageLimit, WireMaxPayloadBytes))
      )
      Vector(0, -1, WireMaxPayloadBytes + 1).foreach { payloadLimit =>
        assertEquals(
          segmentBounded(payload, -1, Direction.Initiator, payloadLimit, -1, messageLimit),
          Left(Error.InvalidPayloadLimit(payloadLimit))
        )
      }
    }
    Vector(WireMaxPayloadBytes + 1, Int.MaxValue).foreach { messageLimit =>
      assertEquals(
        segmentBounded(Bytes.empty, -1, Direction.Initiator, 0, -1, messageLimit),
        Left(Error.InvalidMessageLimit(messageLimit, WireMaxPayloadBytes))
      )
    }
  }

  test("header length and local payload limit retain their validation precedence") {
    assertEquals(decodeHeader(proposal.take(7), 0), Left(Error.HeaderLength(7)))
    assertEquals(decodeHeader(proposal.take(8), 0), Left(Error.InvalidPayloadLimit(0)))
    assertEquals(
      segmentProtocolMessage(Bytes.empty, -1, Direction.Initiator, 0, -1, 0),
      Left(Error.InvalidPayloadLimit(0))
    )
  }
