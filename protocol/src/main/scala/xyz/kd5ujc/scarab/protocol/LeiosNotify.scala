// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.crypto.bls.Vote
import xyz.kd5ujc.scarab.codec.{Bytes, Value}

import LeiosWire.*

/** Pure codec of the LeiosNotify mini-protocol (number 18), after the Blueprint's
  * `network/node-to-node/leios-notify/messages.cddl` (cardano-blueprint `fe4cceb9`). The Blueprint
  * marks parts of this as under discussion; nothing here is more settled than that.
  *
  * Agency: the client in `Idle`, the server in `Busy` and `Quit`.
  */
object LeiosNotify:
  val MiniProtocolId: Int = 18

  /** At most this many votes in one `MsgLeiosVotes`: `[1*16 vote]` in the consensus prototype's
    * `ouroboros-consensus-cardano/cddl/node-to-node/leiosnotify/leiosNotify.cddl`, which says the
    * Blueprint does not bound them ("TODO Bound the votes in the blueprint too").
    */
  val MaxVotes: Int = 16

  enum State:
    case Idle, Busy, Quit, Done

  enum Message:
    /** `[0]`, the server's closing reply to `Quit`. */
    case Done

    /** `[1]`, the client leaving. */
    case Quit
    case Canceled
    case RequestNext

    /** The announcing ranking-block header, exactly as ChainSync diffuses it (original bytes). */
    case BlockAnnouncement(header: Bytes)
    case BlockOffer(point: EbPoint, ebSize: Long)
    case BlockTxsOffer(point: EbPoint)

    /** `[1* vote]`, each strictly decoded; `raw` keeps the original bytes of every vote. */
    case Votes(votes: Vector[Vote], raw: Vector[Bytes])

  /** The state after `message` was sent in `state`, or `None` if the message is not admitted. */
  def next(state: State, message: Message): Option[State] = (state, message) match
    case (State.Idle, Message.RequestNext)          => Some(State.Busy)
    case (State.Idle, Message.Quit)                 => Some(State.Quit)
    case (State.Busy, Message.Canceled)             => Some(State.Idle)
    case (State.Busy, _: Message.BlockAnnouncement) => Some(State.Idle)
    case (State.Busy, _: Message.BlockOffer)        => Some(State.Idle)
    case (State.Busy, _: Message.BlockTxsOffer)     => Some(State.Idle)
    case (State.Busy, _: Message.Votes)             => Some(State.Idle)
    case (State.Quit, Message.Done)                 => Some(State.Done)
    case _                                          => None

  def encode(message: Message): Either[LeiosWireError, Bytes] = message match
    case Message.Done        => LeiosWire.encode(tagged(0))
    case Message.Quit        => LeiosWire.encode(tagged(1))
    case Message.Canceled    => LeiosWire.encode(tagged(2))
    case Message.RequestNext => LeiosWire.encode(tagged(3))
    case Message.BlockAnnouncement(h) =>
      embed(h).flatMap(n => LeiosWire.encode(tagged(4, n.value)))
    case Message.BlockOffer(p, size) =>
      LeiosWire.encode(tagged(5, pointValue(p), Value.UInt(BigInt(size))))
    case Message.BlockTxsOffer(p) => LeiosWire.encode(tagged(6, pointValue(p)))
    case Message.Votes(votes, _) =>
      all(votes)(v => embed(v.encode)).flatMap(ns => LeiosWire.encode(tagged(7, Value.Arr(ns))))

  /** Decode the first message of `bytes` admitted in `state`; returns the message and the number of
    * bytes consumed. `Left(Incomplete)` means more bytes are needed.
    */
  def decodePrefix(state: State, bytes: Bytes): Either[LeiosWireError, (Message, Int)] =
    for
      parts <- split(bytes)
      (tag, fields, consumed) = parts
      message <- (state, tag) match
        case (State.Idle, 1) => arity(tag, fields, 0).map(_ => Message.Quit)
        case (State.Idle, 3) => arity(tag, fields, 0).map(_ => Message.RequestNext)
        case (State.Busy, 2) => arity(tag, fields, 0).map(_ => Message.Canceled)
        case (State.Busy, 4) =>
          arity(tag, fields, 1).map(_ => Message.BlockAnnouncement(fields(0).original))
        case (State.Busy, 5) =>
          for
            _ <- arity(tag, fields, 2)
            p <- point(tag, fields(0))
            size <- uint(tag, fields(1), BigInt(0xffffffffL), LeiosField.Word32)
          yield Message.BlockOffer(p, size.toLong)
        case (State.Busy, 6) =>
          arity(tag, fields, 1).flatMap(_ => point(tag, fields(0))).map(Message.BlockTxsOffer(_))
        case (State.Busy, 7) =>
          for
            _ <- arity(tag, fields, 1)
            items <- fields(0).value match
              case Value.Arr(xs) if xs.nonEmpty && xs.size <= MaxVotes => Right(xs)
              case _ => Left(LeiosWireError.BadField(tag, LeiosField.Votes))
            decoded <- items.zipWithIndex.foldLeft[Either[LeiosWireError, Vector[Vote]]](
              Right(Vector.empty)
            ) { case (acc, (item, i)) =>
              acc.flatMap(vs =>
                Vote.decode(item.original).left.map(LeiosWireError.BadVote(i, _)).map(vs :+ _)
              )
            }
          yield Message.Votes(decoded, items.map(_.original))
        case (State.Quit, 0)            => arity(tag, fields, 0).map(_ => Message.Done)
        case (_, t) if t >= 0 && t <= 7 => Left(LeiosWireError.NotAdmitted(t))
        case (_, t)                     => Left(LeiosWireError.UnknownTag(BigInt(t)))
    yield (message, consumed)
