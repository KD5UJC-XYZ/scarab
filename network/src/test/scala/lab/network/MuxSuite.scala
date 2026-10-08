// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import lab.network.Mux.*

class MuxSuite extends munit.FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(fail(_), identity)
  private def right[A](value: Either[String, A]): A = value.fold(fail(_), identity)
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
      valid.copy(timestamp = -1),
      valid.copy(timestamp = 0x100000000L),
      valid.copy(protocol = -1),
      valid.copy(protocol = 0x8000),
      valid.copy(payloadLength = 0),
      valid.copy(payloadLength = -1),
      valid.copy(payloadLength = 65536)
    ).foreach(h => assert(encodeHeader(h).isLeft))
    assert(encode(Sdu(0, 0, Direction.Initiator, Bytes.empty)).isLeft)
    assert(decodeHeader(bytes("0000000000000000")).isLeft)
    assert(decodeHeader(bytes("00000000000001")).isLeft)
    assert(decodeHeader(bytes("000000000000000100")).isLeft)
    assert(decode(Bytes(proposal.value.dropRight(1))).isLeft)
    assert(decode(join(proposal, bytes("00"))).isLeft)
    assert(decode(proposal, maxPayloadBytes = 8).isLeft)
    assert(decode(proposal, maxPayloadBytes = 0).isLeft)
    assert(decode(proposal, maxPayloadBytes = 65536).isLeft)
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
    assertEquals(partial.finish, Left("EOF in mux payload"))
    val (done, last) = right(partial.feed(Bytes(accept.value.drop(10))))
    assertEquals(last, Vector(acceptSdu))
    assertEquals(done.finish, Right(()))
  }

  test("EOF rejects every truncated header and payload, but allows an empty stream") {
    assertEquals(decoder().finish, Right(()))
    for length <- 1 until proposal.size do
      val (partial, frames) = right(decoder().feed(Bytes(proposal.value.take(length))))
      assertEquals(frames, Vector.empty)
      assert(partial.finish.isLeft, s"length $length")
      assertEquals(partial.pendingBytes, length)
  }

  test("local resource limits reject a header before payload retention and bound batches") {
    Vector(
      Limits(maxPayloadBytes = 0),
      Limits(maxPayloadBytes = 65536),
      Limits(maxInputBytes = 0),
      Limits(maxFramesPerFeed = 0)
    ).foreach(l => assert(Decoder.create(l).isLeft))
    val limited = decoder(Limits(maxPayloadBytes = 8))
    assert(limited.feed(Bytes(proposal.value.take(8))).isLeft)
    assert(limited.feed(bytes("0000000000000000")).isLeft)
    assertEquals(limited.pendingBytes, 0)
    assert(decoder(Limits(maxInputBytes = 16)).feed(proposal).isLeft)
    assert(decoder(Limits(maxFramesPerFeed = 1)).feed(join(proposal, accept)).isLeft)
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
    assert(Mux.segment(payload, 0L, Mux.Direction.Responder, 256, 5).isLeft)
    val frames =
      Mux.segmentBounded(payload, 0L, Mux.Direction.Responder, 256, 5, 65535).toOption.get
    assertEquals(Bytes(frames.flatMap(_.payload.value)), payload)
    assert(frames.forall(s => s.protocol == 5 && s.payload.size <= 256))
    assert(Mux.segmentBounded(payload, 0L, Mux.Direction.Responder, 256, 5, 7806).isLeft)
  }
