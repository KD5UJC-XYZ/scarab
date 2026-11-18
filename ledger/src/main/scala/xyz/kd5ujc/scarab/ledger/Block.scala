// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

import cats.syntax.all.*
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.codec.{Bytes, Cbor, Node, Value}
import xyz.kd5ujc.scarab.ledger.state.Word64
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
    *
    * @param segments
    *   the parts of the body after the header (the bodies, the witness sets, the auxiliary data
    *   and, from Alonzo, the invalid transactions), each as the bytes it was read from: the
    *   reference keeps them and writes them again (`AlonzoBlockBodyInternal`,
    *   `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/Internal.hs:181-186, 222-258`,
    *   cardano-ledger f649f975). Empty for a block not read from bytes.
    */
  final case class Shelley(
      era: Era,
      header: Header.Shelley,
      transactions: Vector[Transaction],
      bodySize: Long,
      bodyHash: Bytes,
      segments: Vector[Bytes] = Vector.empty
  ) extends Block:

    /** `before` with the bytes of every transaction of the body added, valid or not: the ledger
      * state's `shelleyCumulativeTxBytes` after the block (`applyHelper`, ouroboros-consensus
      * 2416493b `Shelley/Ledger/Ledger.hs:749-756`), each transaction's `sizeTxF`
      * ([[Transaction.size]]) widened to `Word64` and summed modulo 2^64, as `Word64` 's `sum` and
      * `+` are. A block's bytes are bounded by [[MaxInputBytes]], so neither the widening nor the
      * sum of one block can wrap; the running total is the reference's `Word64` all the same.
      */
    def addTxBytes(before: Word64): Word64 =
      transactions.foldLeft(before)((total, t) =>
        Word64.wrappingAdd(total, Word64.fromUnsigned(t.size))
      )

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

    /** `sizeTxF` (cardano-ledger 9df9f98c: Shelley to Mary
      * `eras/shelley/impl/src/Cardano/Ledger/Shelley/Tx.hs:154-176`, Alonzo to Conway
      * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/Tx.hs:377-384,485-496`): the length of
      * `toCBORForSizeComputation`, the list `[body, witnesses, auxiliary data or null]` whose parts
      * keep the bytes they were read from. The `is_valid` flag is not counted.
      */
    lazy val size: Long = 1L + body.size + witnesses.size + auxiliaryData.fold(1L)(_.size.toLong)

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
    decodeWith(raw, Some(byronEpochSlots), DecoderVersion.of, DetachedRecords.Shapes.block)

  /** [[decode]] with the block read at the decoder version `version` names for its era, as given.
    */
  def decode(
      raw: Bytes,
      byronEpochSlots: BigInt,
      version: Era => DecoderVersion
  ): Either[ChainError, Block] =
    decodeWith(raw, Some(byronEpochSlots), version, DetachedRecords.Shapes.blockAt(version))

  /** Decode a block of a Shelley-based era; a Byron block is an error. */
  def decodeShelley(raw: Bytes): Either[ChainError, Block] =
    decodeWith(raw, None, DecoderVersion.of, DetachedRecords.Shapes.block)

  /** [[decodeShelley]] with the block read at the decoder version `version` names for its era, as
    * given.
    */
  def decodeShelley(raw: Bytes, version: Era => DecoderVersion): Either[ChainError, Block] =
    decodeWith(raw, None, version, DetachedRecords.Shapes.blockAt(version))

  /** The header of a block, read as [[decode]] reads it: the whole block parsed as CBOR, the
    * envelope, then the header of the era its tag names; the body's contents (transactions,
    * auxiliary data, its hash) are not decoded. A block whose CBOR, envelope or header [[decode]]
    * refuses is refused with the same error, as the two share that code; a well-formed block whose
    * body's contents do not decode still gives its header. The node's ChainSync server sends a
    * block's header from it: the reference reads a stored block's header bytes alone, located by
    * the offsets its secondary index keeps (`readHeader`, `Storage/ImmutableDB/Impl/Iterator.hs`
    * 694-704, ouroboros-consensus 82ecba32), which Scarab's index does not keep.
    */
  def decodeHeader(raw: Bytes, byronEpochSlots: BigInt): Either[ChainError, Header] =
    decodeWith(raw, DetachedRecords.Shapes.block)(
      readHeader(_, Some(byronEpochSlots), DecoderVersion.of)
    )

  /** A block whose transactions hold a `RecD` record written as an empty indefinite list is read as
    * the reference reads it, its parts spanning the fields after the list ([[DetachedRecords]]).
    */
  private def decodeWith(
      raw: Bytes,
      byronEpochSlots: Option[BigInt],
      version: Era => DecoderVersion,
      shape: DetachedRecords.Shape
  ): Either[ChainError, Block] =
    decodeWith(raw, shape)(read(_, byronEpochSlots, version))

  private def decodeWith[A](raw: Bytes, shape: DetachedRecords.Shape)(
      reader: Node => Either[ChainError, A]
  ): Either[ChainError, A] =
    DetachedRecords.read(raw, limits, shape)(e => fail(LedgerDecodeError.Syntax(e)))(reader)

  /** What a block's envelope holds, by its tag: the block of that era, not read yet. */
  private enum Enveloped:
    case Byron(boundary: Boolean, block: Node, epochSlots: BigInt)
    case Shelley(era: Era, block: Node)

    /** A Dijkstra block, which [[LeiosBlock]] reads from its envelope again. */
    case Dijkstra

  /** The envelope `[tag, block]`, read before the block of any era: `enforceSize "CardanoBlock" 2`
    * (ouroboros-consensus 82ecba329 `Ouroboros/Consensus/Cardano/Node.hs:184`), then the era its
    * tag names. A Byron block needs `byronEpochSlots`.
    */
  private def envelope(
      root: Node,
      byronEpochSlots: Option[BigInt]
  ): Either[ChainError, Enveloped] =
    for
      outer <- enforceSize(root, 2)
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
      inside <- era match
        case Era.Byron =>
          byronEpochSlots
            .toRight(fail(LedgerDecodeError.ByronBlockUnexpected))
            .map(Enveloped.Byron(tag == 0, outer(1), _))
        // `[header, [txs, leios_cert / nil, peras_cert / nil]]`, read as the Leios testnet's build
        // reads it: the envelope again, then the block at decoder version 12
        case Era.Dijkstra => Right(Enveloped.Dijkstra)
        case _            => Right(Enveloped.Shelley(era, outer(1)))
    yield inside

  private def read(
      root: Node,
      byronEpochSlots: Option[BigInt],
      at: Era => DecoderVersion
  ): Either[ChainError, Block] =
    envelope(root, byronEpochSlots).flatMap {
      case Enveloped.Byron(boundary, n, epochSlots) =>
        byron(boundary, n, epochSlots).map((header, parts) =>
          Byron(header, parts(1).original, parts(2).original, n.original.size)
        )
      case Enveloped.Shelley(era, n) =>
        for
          (header, parts) <- shelley(era, at(era), n)
          body <- segregated(parts, era, at(era))
        yield Shelley(era, header, body.transactions, body.size, body.hash, body.segments)
      case Enveloped.Dijkstra => LeiosBlock.read(root).map(Dijkstra(_))
    }

  /** [[read]] up to the header: the same envelope and the same header, and nothing after it. */
  private def readHeader(
      root: Node,
      byronEpochSlots: Option[BigInt],
      at: Era => DecoderVersion
  ): Either[ChainError, Header] =
    envelope(root, byronEpochSlots).flatMap {
      case Enveloped.Byron(boundary, n, epochSlots) => byron(boundary, n, epochSlots).map(_._1)
      case Enveloped.Shelley(era, n)                => shelley(era, at(era), n).map(_._1)
      case Enveloped.Dijkstra                       => LeiosBlock.header(root).map(_.dijkstraView)
    }

  /** A Byron block's header, with its size hint, and its three parts. */
  private def byron(
      boundary: Boolean,
      n: Node,
      epochSlots: BigInt
  ): Either[ChainError, (Header.Byron, Vector[Node])] =
    for
      // `enforceSize "Block" 3` and `enforceSize "BoundaryBlock" 3` (cardano-ledger f649f975
      // `eras/byron/ledger/impl/src/Cardano/Chain/Block/Block.hs:329,486`)
      parts <- enforceSize(n, 3)
      header <- Header.byronFromRaw(parts(0).original, boundary, epochSlots)
      hint <- Header.byronSizeHint(BigInt(n.original.size) + 9)
    // GetHeader ByronBlock includes the CBOR-in-CBOR and EBB tag overhead (7 + 2),
    // consensus 82ecba329 Byron/Ledger/Block.hs:154–175.
    yield (header.withSizeHint(hint), parts)

  /** A Shelley-based block's header and its parts. */
  private def shelley(
      era: Era,
      version: DecoderVersion,
      n: Node
  ): Either[ChainError, (Header.Shelley, Vector[Node])] =
    for
      // `decodeRecordNamed "Block"` (`libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:106`,
      // cardano-ledger f649f975): a definite list or an indefinite one, unlike the envelope
      parts <- array(n, if era.hasInvalidTransactions then 5 else 4)
      header <- Header.fromRaw(era, parts(0).original, version)
    yield (header, parts)

  /** The transactions of a block body in the segregated layout of Shelley to Conway (`[header,
    * bodies, witnesses, auxiliary data, invalid]`, the header first and so ignored here) with the
    * size and the digest the header's `block_body_size` and `block_body_hash` commit to.
    */
  private[ledger] final case class SegregatedBody(
      transactions: Vector[Transaction],
      size: Long,
      hash: Bytes,
      segments: Vector[Bytes]
  )

  private[ledger] def segregated(
      parts: Vector[Node],
      era: Era,
      version: DecoderVersion
  ): Either[ChainError, SegregatedBody] =
    for
      bodies <- list(parts(1))
      witnesses <- list(parts(2))
      _ <- Either.cond(
        bodies.sizeIs == witnesses.size,
        (),
        fail(LedgerDecodeError.BodyWitnessCount(bodies.size, witnesses.size))
      )
      aux <- parts(3).value match
        case Value.Map(entries) =>
          for
            _ <- auxiliaryKeys(entries.map(_._1), version)
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
      Blake2b.hash256.hash(Bytes(components.flatMap(c => Blake2b.hash256.hash(c).value))),
      components
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
      !version.atLeast(9) || keys.map(_.value).distinct.sizeIs == keys.size,
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
  private def enforceSize(n: Node, size: Int): Either[ChainError, Vector[Node]] =
    CborRead.enforceSize(n, size, DecodeField.Block).leftMap(fail)
  private def list(n: Node): Either[ChainError, Vector[Node]] = n.value match
    case Value.Arr(xs) => Right(xs)
    case _ =>
      Left(
        fail(LedgerDecodeError.Expected(DecodeField.Block, LedgerDecodeError.Shape.Array, n.value))
      )
  private def index(n: Node, bound: Int): Either[ChainError, Int] = n.value match
    case Value.UInt(i) if i < bound => Right(i.toInt)
    case _ => Left(fail(LedgerDecodeError.TransactionIndex(n.value, bound)))
