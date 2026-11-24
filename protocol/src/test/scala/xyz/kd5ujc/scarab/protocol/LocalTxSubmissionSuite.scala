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
    assert(encode(State.Busy, Role.Client, Message.SubmitTx(tx)) match {
      case Left(
            ProtocolError
              .LocalTxSubmissionAgency(LocalTxSubmission.State.Busy, ChainSync.Role.Client)
          ) =>
        true
      case _ => false
    })
    assert(encode(State.Idle, Role.Server, Message.AcceptTx) match {
      case Left(
            ProtocolError
              .LocalTxSubmissionAgency(LocalTxSubmission.State.Idle, ChainSync.Role.Server)
          ) =>
        true
      case _ => false
    })
    assert(encode(State.Idle, Role.Client, Message.AcceptTx) match {
      case Left(
            ProtocolError
              .LocalTxSubmissionAgency(LocalTxSubmission.State.Idle, ChainSync.Role.Client)
          ) =>
        true
      case _ => false
    })
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
    assert(missingValue match {
      case Left(ProtocolError.UnexpectedBreakOrReservedSimpleValue) => true
      case _                                                        => false
    })
    assertEquals(completePair, Right(true))
  }

  private def hex(s: String): Bytes = Bytes.fromHex(s).fold(e => fail(e.toString), identity)

  /** A reader that refuses a transaction starting with `82 08` and waits on anything else. */
  private val cause = ProtocolError.ExpectedUint64
  private val refusing: TxReader = input =>
    if input.take(2) == hex("8208") then TxPrefix.Refused(cause) else TxPrefix.Short

  test("a SubmitTx whose transaction the reader refuses before it has all arrived ends there") {
    // the reader is given the transaction's bytes alone, and a prefix it does not refuse waits
    List(
      "82008208" -> DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause)),
      "82008208d818" -> DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause)),
      "82008206" -> DecodeResult.NeedMore,
      "8200" -> DecodeResult.NeedMore
    ).foreach { (wire, judged) =>
      assertEquals(decodePrefix(State.Idle, Role.Client, hex(wire), refusing), judged, wire)
      val decoder = incremental(State.Idle, Role.Client, refusing)
      assertEquals(decoder.decodePrefix(hex(wire)), judged, s"incremental $wire")
    }
    // without a reader that judges prefixes, the transaction is read whole
    assertEquals(decodePrefix(State.Idle, Role.Client, hex("82008208")), DecodeResult.NeedMore)
    assertEquals(
      incremental(State.Idle, Role.Client).decodePrefix(hex("82008208")),
      DecodeResult.NeedMore
    )
  }

  test("a whole SubmitTx the reader refuses ends as its prefix does; another is read as before") {
    // the reader is given the whole transaction too, and the message ends with the refusal its
    // prefix gets, whether it is read at once or in parts
    val refused = hex("82008208d81841aa")
    val read = hex("82008206d81841aa")
    val failed = DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause))
    assertEquals(decodePrefix(State.Idle, Role.Client, refused, refusing), failed)
    assertEquals(incremental(State.Idle, Role.Client, refusing).decodePrefix(refused), failed)
    assertEquals(
      decodePrefix(State.Idle, Role.Client, read, refusing),
      DecodeResult.Decoded(Message.SubmitTx(hex("8206d81841aa")), read.size)
    )
    assertEquals(
      incremental(State.Idle, Role.Client, refusing).decodePrefix(read),
      DecodeResult.Decoded(Message.SubmitTx(hex("8206d81841aa")), read.size)
    )
    // without a reader that judges transactions, both are read
    assertEquals(
      decodePrefix(State.Idle, Role.Client, refused),
      DecodeResult.Decoded(Message.SubmitTx(hex("8208d81841aa")), refused.size)
    )
  }

  test("only a client's SubmitTx in Idle is judged before it has all arrived") {
    val always: TxReader = _ => TxPrefix.Refused(cause)
    // the same bytes as a RejectTx carrying an item, in Busy
    assertEquals(
      decodePrefix(State.Busy, Role.Server, hex("82028208"), always),
      DecodeResult.NeedMore
    )
    val messages = List(
      (State.Idle, Role.Client, Message.SubmitTx(tx)),
      (State.Busy, Role.Server, Message.AcceptTx),
      (State.Busy, Role.Server, Message.RejectTx(reason)),
      (State.Idle, Role.Client, Message.Done)
    )
    messages.foreach { (state, sender, message) =>
      val wire = encode(state, sender, message).fold(e => fail(e.toString), identity)
      (0 until wire.size).foreach { n =>
        val judged = message match
          case Message.SubmitTx(_) if n >= 2 =>
            DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause))
          case _ => DecodeResult.NeedMore
        val prefix = wire.take(n)
        assertEquals(decodePrefix(state, sender, prefix, always), judged, s"$message cut at $n")
        assertEquals(
          incremental(state, sender, always).decodePrefix(prefix),
          judged,
          s"incremental $message cut at $n"
        )
      }
    }
  }

  test("the reader is asked only of a client's SubmitTx in Idle, whatever the message's tag") {
    val always: TxReader = _ => TxPrefix.Refused(cause)
    // SubmitTx's key from the server in Busy, and RejectTx's from a client in Idle: each waits until
    // it has all arrived and is then refused for its agency, as without a reader
    List(
      (State.Busy, Role.Server, "82008208", "00"),
      (State.Busy, Role.Server, "820083", "000000"),
      (State.Idle, Role.Client, "82028208", "00")
    ).foreach { (state, sender, prefix, rest) =>
      assertEquals(decodePrefix(state, sender, hex(prefix), always), DecodeResult.NeedMore, prefix)
      assertEquals(
        incremental(state, sender, always).decodePrefix(hex(prefix)),
        DecodeResult.NeedMore,
        s"incremental $prefix"
      )
      val whole = hex(prefix + rest)
      assertEquals(decodePrefix(state, sender, whole, always), decodePrefix(state, sender, whole))
      assert(
        decodePrefix(state, sender, whole).isInstanceOf[DecodeResult.Failed[?]],
        s"whole $prefix$rest"
      )
    }
  }

  test("a message the framing fails on ends with the reader's refusal when the reader refuses") {
    // `82 08` then a break where an item is due: the framing fails at the break, the reader at the
    // index before it, and the message ends with the reader's refusal, as it does in parts
    val broken = hex("82008208ff")
    val failed = DecodeResult.Failed(ProtocolError.UndecodableTransaction(cause))
    assertEquals(decodePrefix(State.Idle, Role.Client, broken, refusing), failed)
    assertEquals(incremental(State.Idle, Role.Client, refusing).decodePrefix(broken), failed)
    assertEquals(decodePrefix(State.Idle, Role.Client, broken.take(4), refusing), failed)
    // a reader that does not refuse leaves the framing's failure as it was
    val framing = decodePrefix(State.Idle, Role.Client, broken)
    assert(framing.isInstanceOf[DecodeResult.Failed[?]], framing.toString)
    assertEquals(
      decodePrefix(State.Idle, Role.Client, hex("82008206ff"), refusing),
      decodePrefix(State.Idle, Role.Client, hex("82008206ff"))
    )
    // and so does a failure of another message, or of a SubmitTx out of its agency
    assertEquals(
      decodePrefix(State.Busy, Role.Server, broken, refusing),
      decodePrefix(State.Busy, Role.Server, broken)
    )
  }
