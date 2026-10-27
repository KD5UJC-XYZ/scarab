// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Value}

import LeiosWire.*
import LeiosWord.Word32

/** Pure codec of the LeiosFetch mini-protocol (number 19), after the Blueprint's
  * `network/node-to-node/leios-fetch/messages.cddl` (cardano-blueprint `0460c5a6`, 2026-10-16:
  * "LeiosFetch transactions are CBOR-in-CBOR, endorser blocks non-empty") and the consensus
  * `leiosFetch.cddl` of ouroboros-consensus#2416 (merged to `main` as `e71a27fc`, 2026-10-20),
  * whose codec is `Ouroboros.Consensus.MiniProtocol.LeiosFetch.Type` (ouroboros-consensus `main`
  * `7b239a16`, 2026-10-22).
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
  * indefinite one; this codec reads both and writes the indefinite one, for byte parity (a definite
  * map would save one byte at most). The decoder applies the reference's checks, see
  * [[LeiosFetchDecoder]]: at most [[MaxBitmapEntries]] entries, no zero bitmap, windows below
  * [[MaxBitmapEntries]] and strictly ascending. As in the reference, the encoder writes the typed
  * fields as supplied; these are decoder checks, not additional constructor or encoder bounds.
  *
  * Agency: the client in `Idle`, the server in `Block` and `BlockTxs`.
  */
object LeiosFetch:
  val MiniProtocolId: Int = 19

  /** The consensus design ceiling on an endorser block's encoding (`maxLeiosEbBytesSize`). */
  private val MaxEbBytes = 600000

  /** The most transactions an endorser block may name: `maxTxsPerEb` of
    * `Ouroboros.Consensus.Leios.Types` (ouroboros-consensus `7b239a16`),
    * `(maxLeiosEbBytesSize - framing) / minItemSize`. The framing is the widest map head (5 bytes);
    * the least item is a 32-byte hash (34 bytes with its head) and a size of one byte.
    */
  val MaxTxsPerEb: Int = (MaxEbBytes - 5) / (34 + 1)

  /** The most windows of 64 transactions a request can name: `maxBitmapEntries maxTxsPerEb` of
    * `Type.hs` (ouroboros-consensus `7b239a16`, line 396), 268.
    */
  val MaxBitmapEntries: Int = (MaxTxsPerEb + 63) / 64

  /** The tag of a CBOR item embedded as bytes (RFC 8949 section 3.4.5.1). */
  private val EmbeddedCbor = BigInt(24)

  enum State:
    case Idle, Block, BlockTxs, Done

  /** byteLimitsLeiosFetch at consensus `7b239a16`: smallByteLimit in Idle, then a ten-percent
    * margin on the 600 kB endorser block and 500 kB transaction reply. Done has no receiver.
    */
  val BlockByteLimit: Int = 660000
  val BlockTxsByteLimit: Int = 550000

  def byteLimit(state: State): Option[Int] = state match
    case State.Idle     => Some(65535)
    case State.Block    => Some(BlockByteLimit)
    case State.BlockTxs => Some(BlockTxsByteLimit)
    case State.Done     => None

  enum Message:
    case Done
    case BlockRequest(point: EbPoint)

    /** `{* hash => word32}`: transaction hash to serialised size, in wire order. */
    case Block(transactions: Vector[(LeiosTxHash, Word32)])
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

  // The native pure encoder has no driver byte limit. Keep the existing CBOR implementation's
  // JVM-indexable representation bound; the receiving driver applies byteLimit separately.
  private val EncodingLimits = Cbor.Limits(
    maxInputBytes = Int.MaxValue,
    maxItems = Int.MaxValue,
    maxStringBytes = Int.MaxValue
  )

  def encode(message: Message): Either[LeiosWireError, Bytes] = message match
    case Message.Done            => LeiosWire.encode(tagged(0), EncodingLimits)
    case Message.BlockRequest(p) => LeiosWire.encode(tagged(1, pointValue(p)), EncodingLimits)
    case Message.Block(txs) =>
      LeiosWire.encode(
        tagged(
          2,
          Value.Map(txs.map { case (h, size) =>
            (nodeOf(Value.ByteString(h.bytes)), nodeOf(Value.UInt(BigInt(size.toLong))))
          })
        ),
        EncodingLimits
      )
    case Message.BlockTxsRequest(p, b) =>
      bitmapsBytes(b, EncodingLimits).flatMap(bytes =>
        arrayWithVerbatim(
          Vector(Value.UInt(BigInt(3)), pointValue(p)),
          Vector(bytes),
          EncodingLimits
        )
      )
    case Message.BlockTxs(txs) =>
      LeiosWire.encode(
        tagged(
          4,
          Value.Arr(txs.map(tx => nodeOf(Value.Tag(EmbeddedCbor, nodeOf(Value.ByteString(tx))))))
        ),
        EncodingLimits
      )
    case Message.LegacyBlockTxs(p, b, txs) =>
      for
        list <- arrayWithVerbatim(Vector.empty, txs, EncodingLimits)
        bm <- bitmapsBytes(b, EncodingLimits)
        out <- arrayWithVerbatim(
          Vector(Value.UInt(BigInt(4)), pointValue(p)),
          Vector(bm, list),
          EncodingLimits
        )
      yield out

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

  def decodePrefix(state: State, bytes: Bytes): Either[LeiosWireError, (Message, Int)] =
    LeiosFetchDecoder.decode(state, bytes)
