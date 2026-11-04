// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.protocol

import xyz.kd5ujc.scarab.codec.{Bytes, Value}

import LeiosWire.*
import LeiosWord.Word32

/** Pure codec of the LeiosFetch mini-protocol (number 19) as the build of the public Leios testnet
  * runs it, the Leios reference (#711): `codecLeiosFetch` of ouroboros-consensus `2416493b`
  * (`ouroboros-consensus/src/ouroboros-consensus/LeiosDemoOnlyTestFetch.hs:255-391`), wired in
  * `Ouroboros/Consensus/Network/NodeToNode.hs:1082-1090` with `maxTxsPerEb` and the
  * `LeiosDemoTypes` point, endorser block and transaction codecs. The Blueprint's
  * `network/node-to-node/leios-fetch/messages.cddl` (cardano-blueprint `0460c5a6`) draws the same
  * messages.
  *
  * A message is a definite list (`decodeListLen`) whose length and key are checked together before
  * any field is read (`:279-282, 350-388`). The current wire form:
  *
  *   - `MsgLeiosBlock` is `[2, { + hash => word32 }]`: at least one and at most [[MaxTxsPerEb]]
  *     entries;
  *   - `MsgLeiosBlockTxs` is `[4, [* tx]]`, only the transactions: the client checks them against
  *     its own request ([[answers]]), the message echoes neither the point nor the bitmaps. The
  *     encoder writes a definite list (`:319-323`); the decoder reads a definite or an indefinite
  *     one and refuses more than [[MaxTxsPerEb]] transactions before it reads the one past the
  *     bound (`:364-379`);
  *   - a transaction is `#6.24(bytes)` (`encodeLeiosTx`, `decodeLeiosTx`: `encodeNestedCborBytes`,
  *     `decodeNestedCborBytes`, `LeiosDemoTypes.hs:1222-1229`). The codec keeps the bytes inside
  *     the tag exactly as they came and does not interpret them, as the reference does not.
  *
  * The older form of Blueprint `fe4cceb9`, `[4, point, bitmaps, tx_list]`, is not read: the build
  * neither writes nor reads it (#744).
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

  /** `byteLimitsLeiosFetch` (ouroboros-consensus `2416493b`, `LeiosDemoOnlyTestFetch.hs:231-245`):
    * `smallByteLimit` (65,535, ouroboros-network `2f1c154f` `Protocol/Limits.hs:131-132`) in Idle,
    * then `addSafetyMargin` (a tenth more,
    * `cardano-diffusion/lib/Cardano/Network/NodeToNode.hs:327-328`) of the 600 kB endorser block
    * and the 500 kB transaction reply. Done has no receiver. The receiving driver applies it; the
    * decoder has no other bound.
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

  def next(state: State, message: Message): Option[State] = (state, message) match
    case (State.Idle, Message.Done)               => Some(State.Done)
    case (State.Idle, _: Message.BlockRequest)    => Some(State.Block)
    case (State.Idle, _: Message.BlockTxsRequest) => Some(State.BlockTxs)
    case (State.Block, _: Message.Block)          => Some(State.Idle)
    case (State.BlockTxs, _: Message.BlockTxs)    => Some(State.Idle)
    case _                                        => None

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
