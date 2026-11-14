// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.protocol.Era

/** A block as fetched over node-to-node BlockFetch (`[tag, block]`). Every component keeps its
  * original encoding: hashes and ledger rules are computed over these bytes, never over a
  * re-encoding.
  */
sealed trait Block:
  def era: Era
  def header: Header

object Block:
  /** Shelley..Conway block: header, transactions and the body commitment recomputed from the
    * original bytes. `bodySize` /`bodyHash` are what the header's `block_body_size`
    * /`block_body_hash` must equal.
    */
  final case class Shelley(
      era: Era,
      header: Header.Shelley,
      transactions: Vector[Transaction],
      bodySize: Long,
      bodyHash: Bytes
  ) extends Block

  /** A Dijkstra block, tag 8: the Leios header and the body `[transactions, leios_cert / nil,
    * peras_cert / nil]` of the build the Leios testnet runs ([[LeiosBlock]]), each transaction
    * decoded whole ([[dijkstra.DijkstraTx]]). The header is its [[LeiosHeader.dijkstraView]]. No
    * ledger rule of Dijkstra consumes it yet (#54).
    */
  final case class Dijkstra(block: LeiosBlock) extends Block:
    def era: Era = Era.Dijkstra
    def header: Header.Shelley = block.header.dijkstraView
    def bodySize: Long = block.bodySize
    def bodyHash: Bytes = block.bodyHash

  /** Byron main or epoch-boundary block: the header is decoded, the body stays raw (the ledger
    * decodes it, see `ByronBody`). `size` is the block's encoded size.
    */
  final case class Byron(header: Header.Byron, body: Bytes, extra: Bytes, size: Int) extends Block:
    def era: Era = Era.Byron

  /** One transaction: original body/witness/auxiliary-data bytes and phase-2 validity flag. */
  final case class Transaction(
      body: Bytes,
      witnesses: Bytes,
      auxiliaryData: Option[Bytes],
      isValid: Boolean
  ):
    lazy val id: Bytes = Blake2b.hash256.hash(body)

  /** The most bytes a block takes: BlockFetch's 2500000 bytes a message (`largeByteLimit`,
    * `Ouroboros/Network/Protocol/BlockFetch/Codec.hs:42-45`, ouroboros-network c45735a5).
    */
  val MaxInputBytes: Int = 2500000

  /** A block is read with no budget of items, string bytes or nesting, as the reference reads it:
    * BlockFetch's codec bounds its bytes ([[MaxInputBytes]]), and the rules its body (`maxBBSize`)
    * and transactions (`maxTxSize`) after decoding (#749).
    */
  private val limits = Cbor.Limits.Unbounded

  /** Decode a block; `byronEpochSlots` (10k of the Byron genesis) only matters for Byron blocks. */
  def decode(raw: Bytes, byronEpochSlots: BigInt): Either[ChainError, Block] =
    decodeWith(raw, Some(byronEpochSlots))

  /** Decode a block of a Shelley-based era; a Byron block is an error. */
  def decodeShelley(raw: Bytes): Either[ChainError, Block] = decodeWith(raw, None)

  /** A block whose transactions hold a `RecD` record written as an empty indefinite list is read as
    * the reference reads it, its parts spanning the fields after the list ([[DetachedRecords]]).
    */
  private def decodeWith(raw: Bytes, byronEpochSlots: Option[BigInt]): Either[ChainError, Block] =
    DetachedRecords.read(raw, limits, DetachedRecords.Shapes.block)(e =>
      fail(LedgerDecodeError.Syntax(e))
    )(read(_, byronEpochSlots))

  private def read(root: Node, byronEpochSlots: Option[BigInt]): Either[ChainError, Block] =
    for
      outer <- array(root, 2)
      tag <- outer(0).value match
        case Value.UInt(t) => Right(t)
        case _ =>
          Left(
            fail(
              LedgerDecodeError.Expected(
                DecodeField.BlockEra,
                LedgerDecodeError.Shape.Unsigned,
                outer(0).value
              )
            )
          )
      era <- Era
        .fromBlockTag(tag)
        .toRight(fail(LedgerDecodeError.UnknownTag(DecodeField.BlockEra, tag)))
      block <- era match
        case Era.Byron =>
          byronEpochSlots
            .toRight(fail(LedgerDecodeError.ByronBlockUnexpected))
            .flatMap(byron(tag == 0, outer(1), _))
        // `[header, [txs, leios_cert / nil, peras_cert / nil]]`, read as the Leios testnet's build
        // reads it: the envelope again (a definite list), then the block at decoder version 12
        case Era.Dijkstra => LeiosBlock.read(root).map(Dijkstra(_))
        case _            => shelley(era, outer(1))
    yield block

  private def byron(
      boundary: Boolean,
      n: Node,
      epochSlots: BigInt
  ): Either[ChainError, Block] =
    for
      parts <- array(n, 3)
      header <- Header.byronFromRaw(parts(0).original, boundary, epochSlots)
      hint <- Header.byronSizeHint(BigInt(n.original.size) + 9)
    // GetHeader ByronBlock includes the CBOR-in-CBOR and EBB tag overhead (7 + 2),
    // consensus 82ecba329 Byron/Ledger/Block.hs:154–175.
    yield Byron(
      header.withSizeHint(hint),
      parts(1).original,
      parts(2).original,
      n.original.size
    )

  private def shelley(era: Era, n: Node): Either[ChainError, Block] =
    for
      parts <- array(n, if era.hasInvalidTransactions then 5 else 4)
      header <- Header.fromRaw(era, parts(0).original)
      body <- segregated(parts, era)
    yield Shelley(era, header, body.transactions, body.size, body.hash)

  /** The transactions of a block body in the segregated layout of Shelley to Conway (`[header,
    * bodies, witnesses, auxiliary data, invalid]`, the header first and so ignored here) with the
    * size and the digest the header's `block_body_size` and `block_body_hash` commit to.
    */
  private[ledger] final case class SegregatedBody(
      transactions: Vector[Transaction],
      size: Long,
      hash: Bytes
  )

  private[ledger] def segregated(
      parts: Vector[Node],
      era: Era
  ): Either[ChainError, SegregatedBody] =
    for
      bodies <- list(parts(1))
      witnesses <- list(parts(2))
      _ <- Either.cond(
        bodies.size == witnesses.size,
        (),
        fail(LedgerDecodeError.BodyWitnessCount(bodies.size, witnesses.size))
      )
      aux <- parts(3).value match
        case Value.Map(entries) =>
          for
            _ <- auxiliaryKeys(entries.map(_._1), DecoderVersion.of(era))
            kvs <- entries.traverse { case (k, v) => index(k, bodies.size).map(_ -> v.original) }
          yield kvs.toMap
        case _ =>
          Left(
            fail(
              LedgerDecodeError.Expected(
                DecodeField.AuxiliaryData,
                LedgerDecodeError.Shape.Map,
                parts(3).value
              )
            )
          )
      invalid <-
        if era.hasInvalidTransactions then
          list(parts(4)).flatMap(_.traverse(index(_, bodies.size))).flatMap(increasing)
        else Right(Set.empty[Int])
      components = parts.drop(1).map(_.original)
    yield SegregatedBody(
      bodies.indices.toVector.map(i =>
        Transaction(bodies(i).original, witnesses(i).original, aux.get(i), !invalid.contains(i))
      ),
      components.map(_.size.toLong).sum,
      Blake2b.hash256.hash(Bytes(components.flatMap(c => Blake2b.hash256.hash(c).value)))
    )

  /** The keys of a block body's auxiliary data map, its transactions' indices, read by
    * `decodeIntMap` (`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Decoder.hs:
    * 858-878`, cardano-ledger f649f975; `Shelley/BlockBody/Internal.hs:262-264`,
    * `Alonzo/BlockBody/Internal.hs:227-229`). From decoder version 9 it is
    * `decodeMapLikeEnforceNoDuplicatesInternal`, which refuses a map that names an index twice once
    * every entry is read, before the indices are checked against the bodies (`auxDataSeqDecoder`);
    * below 9 `IntMap.fromList` keeps the last entry of an index, as `toMap` does.
    */
  private def auxiliaryKeys(keys: Vector[Node], version: DecoderVersion): Either[ChainError, Unit] =
    Either.cond(
      !version.atLeast(9) || keys.map(_.value).distinct.size == keys.size,
      (),
      fail(LedgerDecodeError.DuplicateKey(DecodeField.AuxiliaryData))
    )

  /** The invalid transactions of a block body, Alonzo to Conway: indices in range
    * ([[LedgerDecodeError.TransactionIndex]], the reference's `fail` at
    * `Alonzo/BlockBody/Internal.hs:240-245`, cardano-ledger f649f975), each greater than the one
    * before it. `alignedValidFlags` (`:278-286`) takes `Seq.replicate (index - previous - 1)` for
    * each, which throws for a repeated or a decreasing index
    * ([[LedgerDecodeError.InvalidTransactionOrder]]).
    */
  private def increasing(indices: Vector[Int]): Either[ChainError, Set[Int]] =
    indices
      .zip(indices.drop(1))
      .collectFirst {
        case (previous, index) if index <= previous =>
          fail(LedgerDecodeError.InvalidTransactionOrder(previous, index))
      }
      .toLeft(indices.toSet)

  private def fail(reason: LedgerDecodeError): ChainError =
    ChainError.Decode(DecodeField.Block, reason)
  private def array(n: Node, size: Int): Either[ChainError, Vector[Node]] =
    CborRead.arrayN(n, size, DecodeField.Block).leftMap(fail)
  private def list(n: Node): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _ =>
      Left(
        fail(LedgerDecodeError.Expected(DecodeField.Block, LedgerDecodeError.Shape.Array, n.value))
      )
  private def index(n: Node, bound: Int): Either[ChainError, Int] = n.value match
    case Value.UInt(i) if i < bound => Right(i.toInt)
    case _ => Left(fail(LedgerDecodeError.TransactionIndex(n.value, bound)))
