// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Role}
import xyz.kd5ujc.scarab.protocol.LocalTxMonitor.*
import munit.ScalaCheckSuite
import org.scalacheck.Gen
import org.scalacheck.Prop.forAll
import scala.collection.immutable.SortedMap

class LocalTxMonitorSuite extends ScalaCheckSuite:
  private def hex(s: String) = Bytes.fromHex(s).toOption.get
  private val txId = hex("8206" + "5820" + "11" * 32) // [6, h'11..']
  private val tx = hex("8206d81843a1a2a3") // [6, #6.24(h'a1a2a3')]

  // Literal vectors written from the reference codec (`Ouroboros.Network.Protocol.LocalTxMonitor.
  // Codec`, with the CDDL `local-tx-monitor.cddl`), not produced by this implementation.
  private val vectors: Vector[(State, Role, Message, String)] = Vector(
    (State.Idle, Role.Client, Message.Done, "8100"),
    (State.Idle, Role.Client, Message.Acquire, "8101"),
    (State.Acquiring, Role.Server, Message.Acquired(5), "820205"),
    (State.Acquiring, Role.Server, Message.Acquired(1000), "82021903e8"),
    (State.Acquired, Role.Client, Message.AwaitAcquire, "8101"),
    (State.Acquired, Role.Client, Message.Release, "8103"),
    (State.Acquired, Role.Client, Message.NextTx, "8105"),
    (State.Busy(Kind.NextTx), Role.Server, Message.ReplyNextTx(None), "8106"),
    (State.Busy(Kind.NextTx), Role.Server, Message.ReplyNextTx(Some(tx)), "82068206d81843a1a2a3"),
    (State.Acquired, Role.Client, Message.HasTx(txId), "82078206" + "5820" + "11" * 32),
    (State.Busy(Kind.HasTx), Role.Server, Message.ReplyHasTx(true), "8208f5"),
    (State.Busy(Kind.HasTx), Role.Server, Message.ReplyHasTx(false), "8208f4"),
    (State.Acquired, Role.Client, Message.GetSizes, "8109"),
    (
      State.Busy(Kind.GetSizes),
      Role.Server,
      Message.ReplyGetSizes(Sizes(178176, 266, 2)),
      "820a831a0002b80019010a02"
    ),
    (State.Acquired, Role.Client, Message.GetMeasures, "810b"),
    (
      State.Busy(Kind.GetMeasures),
      Role.Server,
      Message.ReplyGetMeasures(Measures(1, SortedMap("a" -> Measure(1, 2)))),
      "830c01a1616182" + "0102"
    ),
    (
      State.Busy(Kind.GetMeasures),
      Role.Server,
      Message.ReplyGetMeasures(Measures(0, SortedMap.empty)),
      "830c00a0"
    )
  )

  test("pinned reference vectors encode and decode exactly") {
    vectors.foreach { case (state, sender, message, wire) =>
      assertEquals(encode(state, sender, message).map(_.hex), Right(wire), message.toString)
      assertEquals(
        decodePrefix(state, sender, hex(wire)),
        DecodeResult.Decoded(message, wire.length / 2)
      )
    }
  }

  test("AwaitAcquire and Acquire share the wire form and are told apart by the state") {
    assertEquals(
      decodePrefix(State.Idle, Role.Client, hex("8101")),
      DecodeResult.Decoded(Message.Acquire, 2)
    )
    assertEquals(
      decodePrefix(State.Acquired, Role.Client, hex("8101")),
      DecodeResult.Decoded(Message.AwaitAcquire, 2)
    )
  }

  test("every truncated vector needs more, and a suffix is left for the next message") {
    vectors.foreach { case (state, sender, message, wire) =>
      val bytes = hex(wire)
      (0 until bytes.size).foreach(n =>
        assertEquals(
          decodePrefix(state, sender, Bytes(bytes.value.take(n))),
          DecodeResult.NeedMore,
          s"$message truncated to $n"
        )
      )
      assertEquals(
        decodePrefix(state, sender, Bytes(bytes.value ++ bytes.value)),
        DecodeResult.Decoded(message, bytes.size)
      )
    }
  }

  test("agency and states are enforced in both directions") {
    vectors.foreach { case (state, sender, message, wire) =>
      val other = if sender == Role.Client then Role.Server else Role.Client
      assert(encode(state, other, message).isLeft, s"$message by $other")
      assert(
        decodePrefix(state, other, hex(wire)).isInstanceOf[DecodeResult.Failed[?]],
        s"$message decoded for $other"
      )
      assert(encode(State.Done, sender, message).isLeft)
    }
    // a reply of the wrong kind, a client message while the server has agency
    assert(encode(State.Busy(Kind.NextTx), Role.Server, Message.ReplyHasTx(true)).isLeft)
    assert(encode(State.Acquiring, Role.Client, Message.Release).isLeft)
    assert(
      decodePrefix(State.Busy(Kind.HasTx), Role.Server, hex("8106"))
        .isInstanceOf[DecodeResult.Failed[?]]
    )
    // GetMeasures is only legal once a snapshot is held
    assert(decodePrefix(State.Idle, Role.Client, hex("810b")).isInstanceOf[DecodeResult.Failed[?]])
  }

  test("a Word32 field out of range is refused on both sides") {
    assert(
      encode(
        State.Busy(Kind.GetSizes),
        Role.Server,
        Message.ReplyGetSizes(Sizes(1L << 32, 0, 0))
      ).isLeft
    )
    assert(
      decodePrefix(State.Busy(Kind.GetSizes), Role.Server, hex("820a831b000000010000000000" + "00"))
        .isInstanceOf[DecodeResult.Failed[?]]
    )
  }

  private val genBytes: Gen[Bytes] =
    Gen.listOfN(32, Gen.choose(0, 255)).map(xs => Bytes(xs.map(_.toByte).toVector))
  private val genItem: Gen[Bytes] =
    genBytes.map(b => Bytes(hex("82065820").value ++ b.value))
  private val genWord32: Gen[Long] = Gen.choose(0L, 0xffffffffL)
  private val genMeasures: Gen[Measures] =
    for
      count <- genWord32
      entries <- Gen.listOf(
        for
          name <- Gen.alphaNumStr
          size <- Gen.long.map(n => BigInt(n).abs)
          capacity <- Gen.long.map(n => BigInt(n).abs)
        yield name -> Measure(size, capacity)
      )
    yield Measures(count, SortedMap.from(entries))

  /** Every message the protocol allows in `state`, by whoever has agency there. */
  private def messages(state: State): Gen[(Role, Message)] = state match
    case State.Idle => Gen.oneOf(Message.Acquire, Message.Done).map(Role.Client -> _)
    case State.Acquiring =>
      Gen.long.map(n => Role.Server -> Message.Acquired(BigInt(n).abs))
    case State.Acquired =>
      Gen
        .oneOf(
          Gen.const(Message.AwaitAcquire),
          Gen.const(Message.NextTx),
          genItem.map(Message.HasTx(_)),
          Gen.const(Message.GetSizes),
          Gen.const(Message.GetMeasures),
          Gen.const(Message.Release)
        )
        .map(Role.Client -> _)
    case State.Busy(Kind.NextTx) =>
      Gen.option(genItem).map(t => Role.Server -> Message.ReplyNextTx(t))
    case State.Busy(Kind.HasTx) =>
      Gen.oneOf(true, false).map(b => Role.Server -> Message.ReplyHasTx(b))
    case State.Busy(Kind.GetSizes) =>
      Gen
        .zip(genWord32, genWord32, genWord32)
        .map((a, b, c) => Role.Server -> Message.ReplyGetSizes(Sizes(a, b, c)))
    case State.Busy(Kind.GetMeasures) =>
      genMeasures.map(m => Role.Server -> Message.ReplyGetMeasures(m))
    case State.Done => Gen.fail

  private type Run = Vector[(State, Role, Message)]

  /** A random run of the protocol from Idle to Done (or a prefix of one). */
  private val genRun: Gen[Run] =
    def go(state: State, left: Int, acc: Run): Gen[Run] =
      if state == State.Done || left == 0 then Gen.const(acc)
      else
        messages(state).flatMap { case (role, message) =>
          transition(state, role, message) match
            case Right(next) => go(next, left - 1, acc :+ ((state, role, message)))
            case Left(error) => Gen.fail.label(error)
        }
    go(State.Idle, 60, Vector.empty)

  property("every message of every run round-trips") {
    forAll(genRun) { run =>
      run.forall { case (state, role, message) =>
        encode(state, role, message).map(b => decodePrefix(state, role, b)) match
          case Right(DecodeResult.Decoded(got, _)) => got == message
          case _                                   => false
      }
    }
  }

  property("a decoded message consumes exactly its bytes, whatever follows") {
    forAll(genRun, Gen.listOf(Gen.choose(0, 255))) { (run, junk) =>
      run.forall { case (state, role, message) =>
        val wire = encode(state, role, message).toOption.get
        decodePrefix(state, role, Bytes(wire.value ++ junk.map(_.toByte))) ==
          DecodeResult.Decoded(message, wire.size)
      }
    }
  }
