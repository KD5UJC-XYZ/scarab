// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import ChainSync.{DecodeResult, Limits, Message, Point, Role, State, Tip}

class ChainSyncLimitsSuite extends munit.FunSuite:
  private val payload = new ChainSync.PayloadCodec[Bytes]:
    def encode(value: Bytes) = Right(value)
    def decode(value: Bytes) = Right(value)

  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).fold(error => fail(error.toString), identity)

  test("local incoming intersections exceed the former item budget and preserve the next message") {
    val count = 65533
    val wire = bytes("82049f") ++ Bytes.fromArray(Array.fill(count)(0x80.toByte)) ++ bytes("ff")
    assertEquals(
      ChainSync
        .decodePrefix(State.Idle, Role.Client, wire ++ bytes("8107"), payload, Limits.NodeToClient),
      DecodeResult.Decoded(Message.FindIntersect(Vector.fill(count)(Point.Origin)), wire.size)
    )
    assertEquals(
      ChainSync.decodePrefix(State.Idle, Role.Client, wire, payload),
      DecodeResult.Failed(ProtocolError.MessageByteLimitExceeded)
    )
  }

  test("a full-size NtN message may have trailing messages without exceeding its receive bound") {
    val wire = bytes("82049f") ++ Bytes.fromArray(Array.fill(65531)(0x80.toByte)) ++ bytes("ff")
    assertEquals(wire.size, 65535)
    assertEquals(
      ChainSync.decodePrefix(State.Idle, Role.Client, wire ++ bytes("8107"), payload),
      DecodeResult.Decoded(Message.FindIntersect(Vector.fill(65531)(Point.Origin)), wire.size)
    )
  }

  test("native indefinite list encoding can grow beyond the NtN receive bound") {
    val point = ChainSyncEngine.pointCodec[Bytes](Right(_), Right(_))
    val tip = ChainSyncEngine.tipCodec[Bytes](Right(_), Right(_))
    val opaque = ChainSyncEngine.payloadCodec[Bytes](Right(_), Right(_))
    val pointBytes = bytes("59fff9") ++ Bytes.fromArray(Array.fill(65529)(0.toByte))
    val wire = bytes("820481") ++ pointBytes
    val expected = ChainSyncEngine.Message.FindIntersect(Vector(pointBytes))
    assertEquals(wire.size, 65535)
    assertEquals(
      ChainSyncEngine.decodePrefix(State.Idle, Role.Client, wire, point, tip, opaque),
      DecodeResult.Decoded(expected, wire.size)
    )
    assertEquals(
      ChainSyncEngine.encode(State.Idle, Role.Client, expected, point, tip, opaque),
      Right(bytes("82049f") ++ pointBytes ++ bytes("ff"))
    )
  }

  test("a local message traverses deeply nested payloads on a small stack") {
    val task = new java.util.concurrent.FutureTask[Either[ProtocolError, Boolean]](() =>
      val nested = Bytes.fromArray(Array.fill(100000)(0x81.toByte) :+ 0.toByte)
      val message = Message.RollForward(nested, Tip.Origin)
      ChainSync.encode(State.NextCanAwait, Role.Server, message, payload, Limits.NodeToClient).map {
        wire =>
          ChainSync
            .decodePrefix(State.NextCanAwait, Role.Server, wire, payload, Limits.NodeToClient) ==
            DecodeResult.Decoded(message, wire.size)
      }
    )
    val thread = new Thread(null, task, "chainsync-small-stack", 256 * 1024)
    thread.start()
    thread.join()
    val result = task.get()
    assertEquals(result, Right(true))
  }

  test(
    "local and NtN incomplete oversized strings await bytes, rather than rejecting their declaration"
  ) {
    assertEquals(Limits.NodeToClient.messageByteLimit, None)
    assertEquals(Limits().messageByteLimit, Some(65535))
    assertEquals(
      ChainSync.decodePrefix(
        State.NextCanAwait,
        Role.Server,
        bytes("83025a80000000"),
        payload,
        Limits.NodeToClient
      ),
      DecodeResult.NeedMore
    )
  }

  test(
    "huge candidate counts read available points before deciding whether more bytes are needed"
  ) {
    Vector(Limits(), Limits.NodeToClient).foreach { limits =>
      val prefix = bytes("82049a80000000")
      assertEquals(
        ChainSync.decodePrefix(State.Idle, Role.Client, prefix, payload, limits),
        DecodeResult.NeedMore
      )
      assertEquals(
        ChainSync.decodePrefix(State.Idle, Role.Client, prefix ++ bytes("f6"), payload, limits),
        DecodeResult.Failed(ProtocolError.ExpectedDefiniteArray)
      )
      assertEquals(
        ChainSync
          .decodePrefix(State.Idle, Role.Client, bytes("82048182005a80000000"), payload, limits),
        DecodeResult.NeedMore
      )
      assertEquals(
        ChainSync.decodePrefix(
          State.NextCanAwait,
          Role.Server,
          bytes("83029a80000000ff"),
          payload,
          limits
        ),
        DecodeResult.Failed(ProtocolError.UnexpectedBreakOrReservedSimpleValue)
      )
    }
  }

  test("large impossible point and outer arities are refused as arities, not allocation attempts") {
    assertEquals(
      ChainSync.decodePrefix(
        State.Idle,
        Role.Client,
        bytes("8204819a80000000"),
        payload,
        Limits.NodeToClient
      ),
      DecodeResult.Failed(ProtocolError.PointArity)
    )
    assertEquals(
      ChainSync
        .decodePrefix(State.Idle, Role.Client, bytes("9a8000000004"), payload, Limits.NodeToClient),
      DecodeResult.Failed(ProtocolError.UnknownTagOrWrongExactOuterArity)
    )
  }

  test("the native signed length range is checked without narrowing to the JVM array index") {
    Vector("8000000000000000", "ffffffffffffffff").foreach { length =>
      val declared = BigInt(length, 16)
      Vector("82049b" + length, "82048182005b" + length).foreach { wire =>
        assertEquals(
          ChainSync
            .decodePrefix(State.Idle, Role.Client, bytes(wire), payload, Limits.NodeToClient),
          DecodeResult.Failed(ProtocolError.CBORLengthOutsideInt64(declared))
        )
      }
    }
  }

  test("local point hashes are checked for Cardano width after reading their bytes") {
    val wire = bytes("82048182005841") ++ Bytes.fromArray(Array.fill(65)(0.toByte))
    assertEquals(
      ChainSync.decodePrefix(State.Idle, Role.Client, wire, payload, Limits.NodeToClient),
      DecodeResult.Failed(ProtocolError.CardanoPointHashMustContain32Bytes(65))
    )
  }
