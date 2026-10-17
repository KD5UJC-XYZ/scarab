// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Value}

import LeiosWire.*

/** Pure codec of the LeiosFetch mini-protocol (number 19), after the Blueprint's
  * `network/node-to-node/leios-fetch/messages.cddl` (cardano-blueprint `fe4cceb9`). The Blueprint
  * marks `endorser_block`, `bitmaps` and `tx` as underspecified; transactions are kept as their
  * original bytes and not interpreted.
  *
  * Agency: the client in `Idle`, the server in `Block` and `BlockTxs`.
  */
object LeiosFetch:
  val MiniProtocolId: Int = 19

  enum State:
    case Idle, Block, BlockTxs, Done

  enum Message:
    case Done
    case BlockRequest(point: EbPoint)

    /** `{* hash => word32}`: transaction hash to serialised size, in wire order. */
    case Block(transactions: Vector[(EbHash, Long)])
    case BlockTxsRequest(point: EbPoint, bitmaps: TxBitmaps)
    case BlockTxs(point: EbPoint, bitmaps: TxBitmaps, transactions: Vector[Bytes])

  def next(state: State, message: Message): Option[State] = (state, message) match
    case (State.Idle, Message.Done)               => Some(State.Done)
    case (State.Idle, _: Message.BlockRequest)    => Some(State.Block)
    case (State.Idle, _: Message.BlockTxsRequest) => Some(State.BlockTxs)
    case (State.Block, _: Message.Block)          => Some(State.Idle)
    case (State.BlockTxs, _: Message.BlockTxs)    => Some(State.Idle)
    case _                                        => None

  def encode(message: Message): Either[LeiosWireError, Bytes] = message match
    case Message.Done            => LeiosWire.encode(tagged(0))
    case Message.BlockRequest(p) => LeiosWire.encode(tagged(1, pointValue(p)))
    case Message.Block(txs) =>
      LeiosWire.encode(
        tagged(
          2,
          Value.Map(txs.map { case (h, size) =>
            (nodeOf(Value.ByteString(h.bytes)), nodeOf(Value.UInt(BigInt(size))))
          })
        )
      )
    case Message.BlockTxsRequest(p, b) =>
      LeiosWire.encode(tagged(3, pointValue(p), bitmapsValue(b)))
    case Message.BlockTxs(p, b, txs) =>
      // the transactions go out as the bytes they came with
      arrayWithVerbatim(Vector.empty, txs).flatMap(list =>
        arrayWithVerbatim(
          Vector(Value.UInt(BigInt(4)), pointValue(p), bitmapsValue(b)),
          Vector(list)
        )
      )

  def decodePrefix(state: State, bytes: Bytes): Either[LeiosWireError, (Message, Int)] =
    for
      parts <- split(bytes)
      (tag, fields, consumed) = parts
      message <- (state, tag) match
        case (State.Idle, 0) => arity(tag, fields, 0).map(_ => Message.Done)
        case (State.Idle, 1) =>
          arity(tag, fields, 1).flatMap(_ => point(tag, fields(0))).map(Message.BlockRequest(_))
        case (State.Idle, 3) =>
          for
            _ <- arity(tag, fields, 2)
            p <- point(tag, fields(0))
            b <- bitmaps(tag, fields(1))
          yield Message.BlockTxsRequest(p, b)
        case (State.Block, 2) =>
          for
            _ <- arity(tag, fields, 1)
            entries <- fields(0).value match
              case Value.Map(es) => Right(es)
              case _             => Left(LeiosWireError.BadField(tag, LeiosField.EndorserBlock))
            txs <- all(entries) { case (k, v) =>
              for
                h <- hash(tag, k)
                size <- uint(tag, v, BigInt(0xffffffffL), LeiosField.Word32)
              yield (h, size.toLong)
            }
          yield Message.Block(txs)
        case (State.BlockTxs, 4) =>
          for
            _ <- arity(tag, fields, 3)
            p <- point(tag, fields(0))
            b <- bitmaps(tag, fields(1))
            txs <- fields(2).value match
              case Value.Arr(xs) => Right(xs.map(_.original))
              case _             => Left(LeiosWireError.BadField(tag, LeiosField.Transactions))
          yield Message.BlockTxs(p, b, txs)
        case (_, t) if t >= 0 && t <= 4 => Left(LeiosWireError.NotAdmitted(t))
        case (_, t)                     => Left(LeiosWireError.UnknownTag(BigInt(t)))
    yield (message, consumed)
