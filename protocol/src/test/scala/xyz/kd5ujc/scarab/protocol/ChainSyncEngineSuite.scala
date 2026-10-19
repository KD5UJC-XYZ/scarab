// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.{DecodeResult, Limits, Role, State}
import ChainSyncEngine.{Message, PayloadCodec, PointCodec, TipCodec}

class ChainSyncEngineSuite extends munit.FunSuite:
  private final case class MockPoint(item: Bytes)
  private final case class MockTip(item: Bytes)
  private final case class MockHeader(item: Bytes)

  private def bytes(hex: String): Bytes =
    Bytes.fromHex(hex).fold(error => fail(error.toString), identity)

  private val point: PointCodec[MockPoint] = ChainSyncEngine.pointCodec(
    value => Right(value.item),
    item => Right(MockPoint(item))
  )
  private val tip: TipCodec[MockTip] = ChainSyncEngine.tipCodec(
    value => Right(value.item),
    item => Right(MockTip(item))
  )
  private val payload: PayloadCodec[MockHeader] = ChainSyncEngine.payloadCodec(
    value => Right(value.item),
    item => Right(MockHeader(item))
  )

  test("generic point, tip, and header codecs preserve exact CBOR items") {
    val message = Message.RollForward(MockHeader(bytes("1800")), MockTip(bytes("828000")))
    val encoded =
      ChainSyncEngine.encode(State.NextCanAwait, Role.Server, message, point, tip, payload)
    assertEquals(encoded, Right(bytes("83021800828000")))
    assertEquals(
      ChainSyncEngine.decodePrefix(
        State.NextCanAwait,
        Role.Server,
        bytes("83021800828000"),
        point,
        tip,
        payload
      ),
      DecodeResult.Decoded(message, 7)
    )
  }

  test("generic intersection reader accepts both definite and indefinite arrays") {
    val candidates = Vector(MockPoint(bytes("80")), MockPoint(bytes("82180040")))
    val expected = Message.FindIntersect(candidates)
    Vector("82048180", "82049f8082180040ff").zipWithIndex.foreach { case (wire, index) =>
      val result = ChainSyncEngine.decodePrefix(
        State.Idle,
        Role.Client,
        bytes(wire),
        point,
        tip,
        payload
      )
      if index == 0 then
        assertEquals(
          result,
          DecodeResult.Decoded(Message.FindIntersect(Vector(candidates.head)), 4)
        )
      else assertEquals(result, DecodeResult.Decoded(expected, bytes(wire).size))
    }
  }

  test("generic decoder checks agency before decoding untrusted payload fields") {
    val result = ChainSyncEngine.decodePrefix(
      State.Idle,
      Role.Server,
      bytes("8302ff"),
      point,
      tip,
      payload
    )
    assertEquals(
      result,
      DecodeResult.Failed(ProtocolError.ChainSyncAgency(State.Idle, Role.Server))
    )
  }

  test("generic intersection encoding enforces the configured candidate count") {
    val limits = Limits(maxCandidates = 1)
    val result = ChainSyncEngine.encode(
      State.Idle,
      Role.Client,
      Message.FindIntersect(Vector(MockPoint(bytes("80")), MockPoint(bytes("80")))),
      point,
      tip,
      payload,
      limits
    )
    assertEquals(result, Left(ProtocolError.CandidateLimitExceeded))
  }
