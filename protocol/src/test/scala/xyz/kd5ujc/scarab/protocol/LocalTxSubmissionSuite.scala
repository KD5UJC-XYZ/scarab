// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}
import xyz.kd5ujc.scarab.protocol.LocalTxSubmission.*
import munit.FunSuite

class LocalTxSubmissionSuite extends FunSuite:
  private val tx = Bytes(Vector(0x82, 0x06, 0x41, 0xaa).map(_.toByte)) // [6, h'aa']
  private val reason = Bytes(Vector(0x63, 0x6e, 0x6f, 0x70).map(_.toByte)) // "nop"

  private def decode(state: State, sender: Role, message: Message) =
    encode(state, sender, message).map(b => decodePrefix(state, sender, b))

  test("every message round-trips in its state") {
    assertEquals(
      decode(State.Idle, Role.Client, Message.SubmitTx(tx))
        .map(_ == DecodeResult.Decoded(Message.SubmitTx(tx), 6)),
      Right(true)
    )
    List(
      (State.Busy, Role.Server, Message.AcceptTx),
      (State.Busy, Role.Server, Message.RejectTx(reason)),
      (State.Idle, Role.Client, Message.Done)
    ).foreach { (s, r, m) =>
      decode(s, r, m) match
        case Right(DecodeResult.Decoded(got, _)) => assertEquals(got, m)
        case other                               => fail(other.toString)
    }
  }

  test("the exact wire shapes: [0, tx], [1], [2, reason], [3]") {
    assertEquals(
      encode(State.Idle, Role.Client, Message.SubmitTx(tx)).map(_.hex),
      Right("8200" + "820641aa")
    )
    assertEquals(encode(State.Busy, Role.Server, Message.AcceptTx).map(_.hex), Right("8101"))
    assertEquals(
      encode(State.Busy, Role.Server, Message.RejectTx(reason)).map(_.hex),
      Right("8202636e6f70")
    )
    assertEquals(encode(State.Idle, Role.Client, Message.Done).map(_.hex), Right("8103"))
  }

  test("agency and states are enforced in both directions") {
    assert(encode(State.Busy, Role.Client, Message.SubmitTx(tx)).isLeft)
    assert(encode(State.Idle, Role.Server, Message.AcceptTx).isLeft)
    assert(encode(State.Idle, Role.Client, Message.AcceptTx).isLeft)
    val accept = encode(State.Busy, Role.Server, Message.AcceptTx).toOption.get
    assert(decodePrefix(State.Idle, Role.Client, accept).isInstanceOf[DecodeResult.Failed[?]])
    assert(decodePrefix(State.Busy, Role.Client, accept).isInstanceOf[DecodeResult.Failed[?]])
    assert(decodePrefix(State.Done, Role.Client, accept).isInstanceOf[DecodeResult.Failed[?]])
  }

  test("a truncated message needs more, a bad tag or arity fails, a suffix is left") {
    val whole = encode(State.Idle, Role.Client, Message.SubmitTx(tx)).toOption.get
    (0 until whole.size).foreach { n =>
      assertEquals(
        decodePrefix(State.Idle, Role.Client, Bytes(whole.value.take(n))),
        DecodeResult.NeedMore
      )
    }
    assert(
      decodePrefix(State.Idle, Role.Client, Bytes(Vector(0x82, 0x05, 0x00).map(_.toByte)))
        .isInstanceOf[DecodeResult.Failed[?]]
    )
    assert(
      decodePrefix(State.Idle, Role.Client, Bytes(Vector(0x81, 0x00).map(_.toByte)))
        .isInstanceOf[DecodeResult.Failed[?]]
    )
    val two = Bytes(whole.value ++ whole.value)
    assertEquals(
      decodePrefix(State.Idle, Role.Client, two),
      DecodeResult.Decoded(Message.SubmitTx(tx), whole.size)
    )
  }

  test("a transaction nested as deep as the message holds is carried, on a small stack") {
    // The reference limits nesting by the size of the message only, including beyond the former
    // 65,535-item budget. The framing cursor must also keep its stack on the heap.
    def nested(levels: Int) =
      Bytes.fromArray(Array.fill(levels)(0x81.toByte) :+ 0x00.toByte)
    def roundTrip(item: Bytes) =
      var result: Option[Either[ProtocolError, Boolean]] = None
      val thread = new Thread(
        null,
        () =>
          result = Some(
            encode(State.Idle, Role.Client, Message.SubmitTx(item)).map(b =>
              val decoder = incremental(State.Idle, Role.Client)
              val (cursor, _) = decoder.resume(decoder.initial, b.take(b.size - 1))
              decoder.resume(cursor, b)._2 match
                case DecodeResult.Decoded(Message.SubmitTx(got), _) => got == item
                case _                                              => false
            )
          ),
        "small",
        256 * 1024
      )
      thread.start()
      thread.join()
      result.get
    val deep = roundTrip(nested(100000))
    assertEquals(deep, Right(true))
    // an indefinite map still holds whole pairs
    val missingValue = roundTrip(Bytes(Vector(0xbf, 0x00, 0xff).map(_.toByte)))
    val completePair = roundTrip(Bytes(Vector(0xbf, 0x00, 0x01, 0xff).map(_.toByte)))
    assert(missingValue.isLeft)
    assertEquals(completePair, Right(true))
  }
