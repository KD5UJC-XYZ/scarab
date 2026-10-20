// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes

import ChainSync.Role
import TxSubmission2Engine.{Message, Offer, State}

class TxSubmission2EngineSuite extends munit.FunSuite:
  private def rawItemProfile: TxSubmission2Engine.Codec[Bytes, Bytes] =
    TxSubmission2Engine
      .fromCodecs[Bytes, Bytes](
        maxBatch = 4,
        maxOriginalBytes = 128,
        maxAdvertisedSize = 4096L,
        maxMessageBytes = {
          case State.Init | State.Idle  => 64
          case State.Ids(_) | State.Txs => 128
          case State.Done               => 0
        },
        encodeId = Right(_),
        decodeId = Right(_),
        encodeBody = Right(_),
        decodeBody = Right(_),
        checkOffer = offer =>
          if offer.advertisedSize > 0 && offer.advertisedSize <= 0xffffffffL then Right(())
          else Left(ProtocolError.SizeOutsideUint32),
        advertisedSize = size => Right(size.toLong),
        bodyId = Right(_)
      )
      .fold(error => fail(error.message), identity)

  test("generic typed profile keeps exact opaque item bytes through the shared envelope"):
    val codec = rawItemProfile
    val item = Bytes(Vector(0x18.toByte, 0x2a.toByte))
    val expected = Message.ReplyIds(Vector(Offer(item, 500L)))
    val encoded = codec.encode(State.Ids(true), Role.Client, expected)
    assert(encoded.isRight)
    assertEquals(
      codec.decodePrefix(State.Ids(true), Role.Client, encoded.toOption.get),
      ChainSync.DecodeResult.Decoded(expected, encoded.toOption.get.size)
    )

  test("typed item codec writes are bounded by the active outer protocol state"):
    val codec = rawItemProfile
    val item = Bytes(Vector(0x58.toByte, 100.toByte) ++ Vector.fill(100)(0x2a.toByte))
    val offer = Message.ReplyIds(Vector(Offer(item, 500L)))
    assert(codec.encode(State.Ids(true), Role.Client, offer).isRight)
    assertEquals(
      codec.encode(State.Idle, Role.Server, Message.RequestTxs(Vector(item))),
      Left(ProtocolError.MessageByteLimitExceeded)
    )

  test("generic profiles refuse nonpositive and out-of-range limits with typed errors"):
    val invalidBatch = TxSubmission2Engine.fromCodecs[Bytes, Bytes](
      maxBatch = 0,
      maxOriginalBytes = 128,
      maxAdvertisedSize = 4096L,
      maxMessageBytes = {
        case State.Init | State.Idle  => 64
        case State.Ids(_) | State.Txs => 128
        case State.Done               => 0
      },
      encodeId = Right(_),
      decodeId = Right(_),
      encodeBody = Right(_),
      decodeBody = Right(_),
      checkOffer = _ => Right(()),
      advertisedSize = _ => Right(1L),
      bodyId = Right(_)
    )
    assertEquals(invalidBatch, Left(ProtocolError.InvalidTxSubmission2Limits))
