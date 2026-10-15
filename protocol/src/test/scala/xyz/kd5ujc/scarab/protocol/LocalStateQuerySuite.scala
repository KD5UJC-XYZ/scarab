// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.protocol.ChainSync.{DecodeResult, Point, Role, UInt64}
import xyz.kd5ujc.scarab.protocol.LocalStateQuery.*
import munit.ScalaCheckSuite
import org.scalacheck.{Gen, Prop}
import org.scalacheck.Prop.forAll

class LocalStateQuerySuite extends ScalaCheckSuite:
  private val query = Bytes(Vector(0x82, 0x00, 0x81, 0x03).map(_.toByte)) // [0, [3]]
  private val result = Bytes(Vector(0x81, 0x01).map(_.toByte)) // [1]
  private val point = Point.Block(UInt64.saturating(705), Bytes(Vector.fill(32)(0xab.toByte)))

  private def who(state: State): Role =
    if state == State.Idle || state == State.Acquired then Role.Client else Role.Server

  private val targets: Gen[Target] = Gen.oneOf(
    Gen.const(Target.VolatileTip),
    Gen.const(Target.ImmutableTip),
    Gen.const(Target.Specific(Point.Origin)),
    Gen.const(Target.Specific(point))
  )

  /** Messages legal in each state. */
  private def messages(state: State): Gen[Message] = state match
    case State.Idle => Gen.oneOf(targets.map(Message.Acquire(_)), Gen.const(Message.Done))
    case State.Acquiring =>
      Gen.oneOf(
        Gen.const(Message.Acquired),
        Gen.oneOf(Failure.PointTooOld, Failure.PointNotOnChain).map(Message.Failed(_))
      )
    case State.Acquired =>
      Gen.oneOf(
        Gen.const(Message.Query(query)),
        targets.map(Message.ReAcquire(_)),
        Gen.const(Message.Release)
      )
    case State.Querying => Gen.const(Message.Result(result))
    case State.Done     => Gen.fail

  /** A walk of the state machine: every message the generator picks is legal where it is sent. */
  private val walks: Gen[Vector[(State, Message)]] =
    def go(state: State, left: Int, acc: Vector[(State, Message)]): Gen[Vector[(State, Message)]] =
      if left == 0 || state == State.Done then Gen.const(acc)
      else
        messages(state).flatMap { m =>
          transition(state, who(state), m) match
            case Right(next) => go(next, left - 1, acc :+ (state -> m))
            case Left(_)     => Gen.fail
        }
    go(State.Idle, 40, Vector.empty)

  property("every message of every walk round-trips and the decoder consumes exactly it") {
    forAll(walks) { walk =>
      walk.forall { (state, message) =>
        encode(state, who(state), message) match
          case Right(bytes) =>
            decodePrefix(state, who(state), bytes) == DecodeResult.Decoded(message, bytes.size)
          case Left(_) => false
      }
    }
  }

  property("a message cut short needs more input and never decodes") {
    forAll(walks.suchThat(_.nonEmpty)) { walk =>
      walk.forall { (state, message) =>
        encode(state, who(state), message).toOption.exists { bytes =>
          (0 until bytes.size).forall(n =>
            decodePrefix(state, who(state), Bytes(bytes.value.take(n))) == DecodeResult.NeedMore
          )
        }
      }
    }
  }

  test("the exact wire shapes of every message") {
    def wire(state: State, m: Message) = encode(state, who(state), m).map(_.hex)
    assertEquals(wire(State.Idle, Message.Acquire(Target.VolatileTip)), Right("8108"))
    assertEquals(wire(State.Idle, Message.Acquire(Target.ImmutableTip)), Right("810a"))
    assertEquals(
      wire(State.Idle, Message.Acquire(Target.Specific(point))),
      Right("8200" + "82" + "1902c1" + "5820" + "ab" * 32)
    )
    assertEquals(wire(State.Idle, Message.Acquire(Target.Specific(Point.Origin))), Right("820080"))
    assertEquals(wire(State.Idle, Message.Done), Right("8107"))
    assertEquals(wire(State.Acquiring, Message.Acquired), Right("8101"))
    assertEquals(wire(State.Acquiring, Message.Failed(Failure.PointTooOld)), Right("820200"))
    assertEquals(wire(State.Acquiring, Message.Failed(Failure.PointNotOnChain)), Right("820201"))
    assertEquals(wire(State.Acquired, Message.Query(query)), Right("8203" + "82008103"))
    assertEquals(wire(State.Querying, Message.Result(result)), Right("8204" + "8101"))
    assertEquals(wire(State.Acquired, Message.Release), Right("8105"))
    assertEquals(wire(State.Acquired, Message.ReAcquire(Target.VolatileTip)), Right("8109"))
    assertEquals(wire(State.Acquired, Message.ReAcquire(Target.ImmutableTip)), Right("810b"))
    assertEquals(
      wire(State.Acquired, Message.ReAcquire(Target.Specific(Point.Origin))),
      Right("820680")
    )
  }

  test("a message in the wrong state or from the wrong side is refused") {
    assert(encode(State.Idle, Role.Server, Message.Acquire(Target.VolatileTip)).isLeft)
    assert(encode(State.Acquired, Role.Client, Message.Acquire(Target.VolatileTip)).isLeft)
    assert(encode(State.Idle, Role.Client, Message.Query(query)).isLeft)
    assert(encode(State.Acquired, Role.Client, Message.Done).isLeft)
    assert(encode(State.Querying, Role.Client, Message.Release).isLeft)
    assert(encode(State.Done, Role.Client, Message.Done).isLeft)
    val release = encode(State.Acquired, Role.Client, Message.Release).toOption.get
    assert(decodePrefix(State.Idle, Role.Client, release).isInstanceOf[DecodeResult.Failed[?]])
    assert(decodePrefix(State.Acquired, Role.Server, release).isInstanceOf[DecodeResult.Failed[?]])
    assert(decodePrefix(State.Done, Role.Client, release).isInstanceOf[DecodeResult.Failed[?]])
  }

  test("unknown tags, failure codes and arities fail; a suffix is left for the next message") {
    def decoded(hex: String, state: State, sender: Role) =
      decodePrefix(state, sender, Bytes.fromHex(hex).toOption.get)
    assert(decoded("8199", State.Idle, Role.Client).isInstanceOf[DecodeResult.Failed[?]])
    assert(decoded("820209", State.Acquiring, Role.Server).isInstanceOf[DecodeResult.Failed[?]])
    assert(decoded("8208", State.Idle, Role.Client).isInstanceOf[DecodeResult.Failed[?]])
    assert(
      decoded("8108" + "8107", State.Idle, Role.Client) == DecodeResult.Decoded(
        Message.Acquire(Target.VolatileTip),
        2
      )
    )
  }
