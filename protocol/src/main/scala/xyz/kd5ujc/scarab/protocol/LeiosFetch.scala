// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Node, Value}

import LeiosWire.*

/** Pure codec of the LeiosFetch mini-protocol (number 19), after the Blueprint's
  * `network/node-to-node/leios-fetch/messages.cddl` (cardano-blueprint `0460c5a6`, 2026-10-16:
  * "LeiosFetch transactions are CBOR-in-CBOR, endorser blocks non-empty") and the consensus
  * `leiosFetch.cddl` of ouroboros-consensus#2416 (merged to `main` as `e71a27fc`, 2026-10-20),
  * whose codec is `Ouroboros.Consensus.MiniProtocol.LeiosFetch.Type`.
  *
  * The current wire form:
  *
  *   - `MsgLeiosBlock` is `[2, { + hash => word32 }]`: at least one and at most [[MaxTxsPerEb]]
  *     entries, a definite or an indefinite map;
  *   - `MsgLeiosBlockTxs` is `[4, [* tx]]`, only the transactions: the client checks them against
  *     its own request ([[answers]]), the message echoes neither the point nor the bitmaps. The
  *     list is definite or indefinite and holds at most [[MaxTxsPerEb]] transactions;
  *   - a transaction is `#6.24(bytes .cbor dijkstra.block_transaction)`. The codec keeps the bytes
  *     inside the tag exactly as they came (a transaction that is hashed is never re-encoded) and
  *     does not interpret them, as the reference decoder does not (it checks the framing only).
  *
  * Compatibility path, [[Message.LegacyBlockTxs]]: the Blueprint of `fe4cceb9` (2026-10-13) had
  * `MsgLeiosBlockTxs = [4, point, bitmaps, tx_list]` with each transaction any CBOR item. Nobody
  * has captured a LeiosFetch message from the public testnet relay (docs/leios.md 11.7), whose
  * release `11.1.0.164-prototype-2026w38a` predates the change, so the decoder reads that form too,
  * and the encoder writes it only for a [[Message.LegacyBlockTxs]]. Removal condition: a capture,
  * or a testnet release that includes ouroboros-consensus#2416, shows which form a relay speaks;
  * once the new form is confirmed, delete [[Message.LegacyBlockTxs]], its decode branch and its
  * tests (issue #174). Only `MsgLeiosBlockTxs` has two forms; an empty endorser block is refused in
  * both.
  *
  * The Blueprint draws `bitmaps` as an indefinite-length map. The reference decoder
  * (`decodeBitmaps`, `decodeMapLenOrIndef`) accepts both, and its encoder always writes the
  * indefinite one; this codec reads both and writes a definite one.
  *
  * Agency: the client in `Idle`, the server in `Block` and `BlockTxs`.
  */
object LeiosFetch:
  val MiniProtocolId: Int = 19

  /** The consensus design ceiling on an endorser block's encoding (`maxLeiosEbBytesSize`). */
  private val MaxEbBytes = 600000

  /** The most transactions an endorser block may name: `maxTxsPerEb` of
    * `Ouroboros.Consensus.Leios.Types`, `(maxLeiosEbBytesSize - framing) / minItemSize`. The
    * framing is the widest map head (5 bytes); the least item is a 32-byte hash (34 bytes with its
    * head) and a size of one byte.
    */
  val MaxTxsPerEb: Int = (MaxEbBytes - 5) / (34 + 1)

  /** The tag of a CBOR item embedded as bytes (RFC 8949 section 3.4.5.1). */
  private val EmbeddedCbor = BigInt(24)

  enum State:
    case Idle, Block, BlockTxs, Done

  enum Message:
    case Done
    case BlockRequest(point: EbPoint)

    /** `{* hash => word32}`: transaction hash to serialised size, in wire order. */
    case Block(transactions: Vector[(EbHash, Long)])
    case BlockTxsRequest(point: EbPoint, bitmaps: TxBitmaps)

    /** `[4, [* #6.24(bytes)]]`: the bytes inside each tag 24, as they came, in wire order. */
    case BlockTxs(transactions: Vector[Bytes])

    /** The form of `MsgLeiosBlockTxs` at Blueprint `fe4cceb9`, `[4, point, bitmaps, [* tx]]`, with
      * each transaction as the original bytes of its item. Read for compatibility only; see the
      * object's documentation for the removal condition.
      */
    case LegacyBlockTxs(point: EbPoint, bitmaps: TxBitmaps, transactions: Vector[Bytes])

  def next(state: State, message: Message): Option[State] = (state, message) match
    case (State.Idle, Message.Done)                  => Some(State.Done)
    case (State.Idle, _: Message.BlockRequest)       => Some(State.Block)
    case (State.Idle, _: Message.BlockTxsRequest)    => Some(State.BlockTxs)
    case (State.Block, _: Message.Block)             => Some(State.Idle)
    case (State.BlockTxs, _: Message.BlockTxs)       => Some(State.Idle)
    case (State.BlockTxs, _: Message.LegacyBlockTxs) => Some(State.Idle)
    case _                                           => None

  def encode(message: Message): Either[LeiosWireError, Bytes] = message match
    case Message.Done            => LeiosWire.encode(tagged(0))
    case Message.BlockRequest(p) => LeiosWire.encode(tagged(1, pointValue(p)))
    case Message.Block(txs) =>
      if txs.isEmpty || txs.size > MaxTxsPerEb then Left(LeiosWireError.Unencodable)
      else
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
    case Message.BlockTxs(txs) =>
      if txs.size > MaxTxsPerEb then Left(LeiosWireError.Unencodable)
      else
        LeiosWire.encode(
          tagged(
            4,
            Value.Arr(
              txs.map(tx => nodeOf(Value.Tag(EmbeddedCbor, nodeOf(Value.ByteString(tx)))))
            )
          )
        )
    case Message.LegacyBlockTxs(p, b, txs) =>
      // the transactions go out as the bytes they came with
      arrayWithVerbatim(Vector.empty, txs).flatMap(list =>
        arrayWithVerbatim(
          Vector(Value.UInt(BigInt(4)), pointValue(p), bitmapsValue(b)),
          Vector(list)
        )
      )

  /** The client's check of an answer against its own request: one transaction for each bit the
    * request's bitmaps set. (The answer does not say which; the order is the bitmaps'.)
    */
  def answers(
      request: TxBitmaps,
      transactions: Vector[Bytes]
  ): Either[LeiosWireError, Vector[Bytes]] =
    Either.cond(
      request.count == transactions.size.toLong,
      transactions,
      LeiosWireError.TransactionCountMismatch(request.count, transactions.size)
    )

  /** The bytes inside the tag 24 of transaction `index`. */
  private def transaction(index: Int, n: Node): Either[LeiosWireError, Bytes] = n.value match
    case Value.Tag(t, inner) if t == EmbeddedCbor =>
      inner.value match
        case Value.ByteString(b) => Right(b)
        case _                   => Left(LeiosWireError.BadTransaction(index))
    case _ => Left(LeiosWireError.BadTransaction(index))

  private def bounded(
      tag: Int,
      field: LeiosField,
      actual: Int
  ): Either[LeiosWireError, Unit] =
    Either.cond(actual <= MaxTxsPerEb, (), LeiosWireError.TooMany(tag, field, MaxTxsPerEb, actual))

  private def list(tag: Int, n: Node): Either[LeiosWireError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _             => Left(LeiosWireError.BadField(tag, LeiosField.Transactions))

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
            _ <- Either.cond(entries.nonEmpty, (), LeiosWireError.EmptyEndorserBlock)
            _ <- bounded(tag, LeiosField.EndorserBlock, entries.size)
            txs <- all(entries) { case (k, v) =>
              for
                h <- hash(tag, k)
                size <- uint(tag, v, BigInt(0xffffffffL), LeiosField.Word32)
              yield (h, size.toLong)
            }
          yield Message.Block(txs)
        case (State.BlockTxs, 4) if fields.size == 3 =>
          // the form of Blueprint `fe4cceb9`: [4, point, bitmaps, tx_list]
          for
            p <- point(tag, fields(0))
            b <- bitmaps(tag, fields(1))
            items <- list(tag, fields(2))
            _ <- bounded(tag, LeiosField.Transactions, items.size)
          yield Message.LegacyBlockTxs(p, b, items.map(_.original))
        case (State.BlockTxs, 4) =>
          for
            _ <- arity(tag, fields, 1)
            items <- list(tag, fields(0))
            _ <- bounded(tag, LeiosField.Transactions, items.size)
            txs <- all(items.zipWithIndex)((n, i) => transaction(i, n))
          yield Message.BlockTxs(txs)
        case (_, t) if t >= 0 && t <= 4 => Left(LeiosWireError.NotAdmitted(t))
        case (_, t)                     => Left(LeiosWireError.UnknownTag(BigInt(t)))
    yield (message, consumed)
