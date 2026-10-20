// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.DecodeResult
import PeerSharing.{Error, Expectation, Limits, Role, State}
import PeerSharingEngine.Message

class PeerSharingEngineSuite extends FunSuite:
  private def bytes(hex: String): Bytes = Bytes.fromHex(hex).fold(e => fail(e.message), identity)
  private val opaque = PeerSharingEngine.peerCodec[Bytes](Right(_), Right(_))

  test(
    "generic peer items retain exact bytes and every prefix without weakening the address facade"
  ) {
    val wire = bytes("82019f2000171818ff")
    val peers = Vector("20", "00", "17", "1818").map(bytes)
    val message = Message.SharePeers(peers)
    assertEquals(PeerSharingEngine.encode(State.Busy, Role.Server, message, opaque), Right(wire))
    assertEquals(
      PeerSharingEngine.decodePrefix(State.Busy, Role.Server, wire, opaque),
      DecodeResult.Decoded(message, wire.size)
    )
    for cut <- 0 until wire.size do
      assertEquals(
        PeerSharingEngine.decodePrefix(State.Busy, Role.Server, wire.take(cut), opaque),
        DecodeResult.NeedMore,
        s"cut $cut"
      )
    assertEquals(
      PeerSharingEngine.decodePrefix(State.Busy, Role.Server, wire ++ bytes("8102"), opaque),
      DecodeResult.Decoded(message, wire.size)
    )
    assertEquals(
      PeerSharing.decode(State.Busy, Role.Server, wire),
      Left(Error.Unexpected(Expectation.DefiniteArray, 0x20))
    )
  }

  test("generic item encoding validates a complete CBOR item before emitting it") {
    val message = Message.SharePeers(Vector(bytes("0000")))
    assertEquals(
      PeerSharingEngine.encode(State.Busy, Role.Server, message, opaque),
      Left(ProtocolError.PeerSharing(Error.PeerItem(ProtocolError.TrailingPayloadBytes)))
    )
  }

  test("generic peer refusals retain their typed cause") {
    val refusing =
      PeerSharingEngine.peerCodec[Bytes](Right(_), _ => Left(ProtocolError.ExpectedUint64))
    assertEquals(
      PeerSharingEngine.decodePrefix(State.Busy, Role.Server, bytes("82018100"), refusing),
      DecodeResult.Failed(ProtocolError.PeerSharing(Error.PeerItem(ProtocolError.ExpectedUint64)))
    )
  }

  test("agency is checked before item framing and a generic reply keeps the message byte ceiling") {
    assertEquals(
      PeerSharingEngine.decodePrefix(State.Busy, Role.Client, Bytes.empty, opaque),
      DecodeResult.Failed(ProtocolError.PeerSharing(Error.WrongAgency(State.Busy, Role.Client)))
    )
    val message = Message.SharePeers(Vector(bytes("00")))
    val limit = Limits(maxMessageBytes = 4)
    assertEquals(
      PeerSharingEngine.encode(State.Busy, Role.Server, message, opaque, limit),
      Left(ProtocolError.PeerSharing(Error.TooLarge(4)))
    )
    assertEquals(
      PeerSharingEngine.decodePrefix(State.Busy, Role.Server, bytes("82019f00ff"), opaque, limit),
      DecodeResult.Failed(ProtocolError.PeerSharing(Error.TooLarge(4)))
    )
  }
