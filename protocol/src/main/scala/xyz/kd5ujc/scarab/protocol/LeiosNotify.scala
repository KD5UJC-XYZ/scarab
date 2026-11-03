// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.bls.Vote
import xyz.kd5ujc.scarab.codec.{Bytes, Value}

import LeiosWire.*
import LeiosWord.Word32

/** Pure codec of the LeiosNotify mini-protocol (number 18) as the build the public Leios testnet
  * runs it, the Leios reference (#711): `codecLeiosNotify` of ouroboros-consensus `2416493b`
  * (`LeiosDemoOnlyTestNotify.hs:259-396`). The Blueprint's
  * `network/node-to-node/leios-notify/messages.cddl` (cardano-blueprint `fe4cceb9`) draws the same
  * messages. A message is a definite list whose length, key and state are matched before any field;
  * a point is `enforceSize 2` of a `Word64` slot and a 32-byte hash; `MsgLeiosVotes` is a definite
  * list of any number of votes, each `enforceSize 3` of a 32-byte hash, a `Word16` seat and a
  * 48-byte signature.
  *
  * Agency: the client in `Idle`, the server in `Busy` and `Quit`.
  */
object LeiosNotify:
  val MiniProtocolId: Int = 18

  enum State:
    case Idle, Busy, Quit, Done

  /** `byteLimitsLeiosNotify` (`LeiosDemoOnlyTestNotify.hs:241-247`): `smallByteLimit`, 65,535 bytes
    * (ouroboros-network `2f1c154f` `Protocol/Limits.hs:131-132`), in Idle, Busy and Quit; Done has
    * no receiver. The receiving driver applies it; the decoder has no other bound.
    */
  def byteLimit(state: State): Option[Int] = state match
    case State.Done => None
    case _          => Some(SmallByteLimit)

  /** `smallByteLimit`: the limit of every state that has a receiver. */
  val SmallByteLimit: Int = 65535

  enum Message:
    /** `[0]`, the server's closing reply to `Quit`. */
    case Done

    /** `[1]`, the client leaving. */
    case Quit
    case Canceled
    case RequestNext

    /** The received header envelope. Encoding rebuilds its wrapper and retains the header bytes. */
    case BlockAnnouncement(header: Bytes)
    case BlockOffer(point: EbPoint, ebSize: Word32)
    case BlockTxsOffer(point: EbPoint)

    /** `[* vote]`, each decoded as the build decodes it; `raw` keeps the original bytes of every
      * vote.
      */
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
      for
        header <- HfcHeader
          .decode(h)
          .leftMap(e => LeiosWireError.Syntax(ProtocolError.HfcHeader(e)))
        encoded <- HfcHeader.encode(header).leftMap(LeiosWireError.Syntax(_))
        message <- arrayWithVerbatim(Vector(Value.UInt(BigInt(4))), Vector(encoded))
      yield message
    case Message.BlockOffer(p, size) =>
      LeiosWire.encode(tagged(5, pointValue(p), Value.UInt(BigInt(size.toLong))))
    case Message.BlockTxsOffer(p) => LeiosWire.encode(tagged(6, pointValue(p)))
    case Message.Votes(votes, _) =>
      all(votes)(v => embed(v.encode)).flatMap(ns => LeiosWire.encode(tagged(7, Value.Arr(ns))))

  /** Decode the first message of `bytes` admitted in `state`, as the Leios testnet's build does
    * ([[LeiosNotifyDecoder]]); returns the message and the number of bytes consumed.
    * `Left(Incomplete)` means more bytes are needed.
    */
  def decodePrefix(state: State, bytes: Bytes): Either[LeiosWireError, (Message, Int)] =
    LeiosNotifyDecoder.decode(state, bytes)
