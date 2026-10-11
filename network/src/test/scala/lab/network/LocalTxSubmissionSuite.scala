// SPDX-License-Identifier: Apache-2.0
package lab.network

import lab.cbor.Bytes
import lab.network.ChainSync.{DecodeResult, Role}
import lab.network.LocalTxSubmission.*
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
