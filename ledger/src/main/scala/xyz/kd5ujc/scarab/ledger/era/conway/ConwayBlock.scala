// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.conway

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{Block as FlatBlock, ChainError, Header, Unsigned}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  BlockBody,
  EraBlockBody,
  EraBlockHeader,
  EraTx,
  EraTxError,
  PraosEraBlockHeader,
  ProtVer,
  Tx,
  TxLevel,
  Version
}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, Conway, Hash32, KeyHash, SlotNo, VocabError}
import xyz.kd5ujc.scarab.protocol.{Era as ChainEra, StateLayout}

/** `AlonzoBlockBody ConwayEra`, Conway's block body (`Conway/BlockBody.hs:14-15`;
  * `eras/alonzo/impl/src/Cardano/Ledger/Alonzo/BlockBody/Internal.hs:78-92`, cardano-ledger
  * f649f975): the top-level transactions and, as the body keeps them, the hash of its four segments
  * (`abbHash`, `hashAlonzoSegWits`, `Internal.hs:188-210`) and their size. Made only by
  * [[ConwayBlock.decode]], from a block's bytes, so its contents, size and hash are those of one
  * encoding: no body has a commitment its contents do not have.
  */
final class ConwayBlockBody private (
    val txs: Vector[ConwayTx],
    val size: Long,
    val hash: Hash32
):
  override def toString: String = s"ConwayBlockBody(${txs.size} transactions, $size, $hash)"

object ConwayBlockBody:
  /** The body of a block the flat block decoder read from its bytes (`Block.decodeShelley`, the
    * reference's `DecCBOR (Annotator (AlonzoBlockBody era))`, `Internal.hs:212-256`, cardano-ledger
    * f649f975), each transaction read by Conway's decoders in the pool parameters' `layout`
    * ([[ConwayTx.decode]]): the hash is the Blake2b-256 digest of its segments' digests
    * (`hashAlonzoSegWits`), and the size their bytes' length, both of which the decoder computed
    * from the segments it read. Only [[ConwayBlock.decode]] holds the evidence
    * ([[ConwayBlock.Decoded]]), on the block it decoded, so no copied block, whose contents differ
    * from its size and hash, reaches it.
    */
  private[conway] def ofDecoded(block: FlatBlock.Shelley, layout: StateLayout)(using
      ConwayBlock.Decoded
  ): Either[EraTxError, ConwayBlockBody] =
    for
      txs <- block.transactions.traverse(ConwayTx.decode(_, layout))
      hash <- Hash32.fromBytes(block.bodyHash).leftMap(EraTxError.Vocab(_))
    yield new ConwayBlockBody(txs, block.bodySize, hash)

  /** `instance EraBlockBody ConwayEra` (`Conway/BlockBody.hs:14-20`, cardano-ledger f649f975), the
    * members of Alonzo's (`Internal.hs:97-103`). `blockBodySize` encodes the body's segments again
    * at the version (`encCBORGroup`, `Internal.hs:181-186`), which writes the bytes they were read
    * from, so it is their length at every version.
    */
  val instance: EraBlockBody[Conway] = new EraBlockBody[Conway]:
    def txOf: EraTx[Conway] = EraTx.conway
    def txSeqBlockBody(body: BlockBody[Conway]): Vector[Tx[TxLevel.Top, Conway]] = body.txs
    def hashBlockBody(body: BlockBody[Conway]): Hash32 = body.hash
    def blockBodySize(protVer: ProtVer, body: BlockBody[Conway]): Long = body.size

/** A Conway header as the ledger reads it (`EraBlockHeader (Header c) era` and
  * `PraosEraBlockHeader (Header c) era` of the Praos header): the header the block decoder read,
  * with the fields the classes give in their types, read once by [[fromHeader]].
  */
final case class ConwayBlockHeader private (
    header: Header.Shelley,
    issuer: KeyHash[BlockIssuer],
    slot: SlotNo,
    bodySize: Long,
    bodyHash: Hash32,
    protVer: ProtVer
)

object ConwayBlockHeader:
  private val Word32Max: BigInt = BigInt(0xffffffffL)

  /** The fields of a decoded Conway header in the classes' types: the issuer the Blake2b-224 hash
    * of its cold key (`hashKey`), the declared body size a `Word32`, the declared body hash 32
    * bytes, and the protocol version a `Version` and a `Word32` (`decodeVersion`, as the header
    * decoder reads them, `Header.protocolVersion`). A header of another era is not Conway's; a
    * field outside its type, which the header decoder does not give, is a vocabulary error.
    */
  def fromHeader(header: Header.Shelley): Either[ConwayBlockError, ConwayBlockHeader] =
    val (major, minor) = header.protocolVersion
    if header.era != ChainEra.Conway then Left(ConwayBlockError.OtherEra(header.era))
    else
      (
        KeyHash.fromBytes[BlockIssuer](Blake2b.hash224.hash(header.issuerVkey)),
        SlotNo.fromBigInt(header.slot),
        Either.cond(
          header.bodySize >= 0 && header.bodySize <= Word32Max,
          header.bodySize.toLong,
          VocabError.OutOfRange(VocabError.Kind.Word32, header.bodySize)
        ),
        Hash32.fromBytes(header.bodyHash),
        (
          Version
            .mkVersion(major)
            .toRight(VocabError.OutOfRange(VocabError.Kind.ProtocolMajor, major)),
          Unsigned
            .from[Unsigned.Width.W32](minor)
            .toRight(VocabError.OutOfRange(VocabError.Kind.ProtocolMinor, minor))
        ).mapN(ProtVer(_, _))
      ).mapN(ConwayBlockHeader(header, _, _, _, _, _))
        .leftMap(e => ConwayBlockError.Projection(EraTxError.Vocab(e)))

  /** The Praos header's instances of the two classes (`EraBlockHeader (Header c) era`,
    * `libs/cardano-protocol/src/Cardano/Protocol/Praos/BlockHeader.hs:241-269`, cardano-ledger
    * f649f975, where the protocol version is the class's member).
    */
  val instance
      : EraBlockHeader[ConwayBlockHeader, Conway] & PraosEraBlockHeader[ConwayBlockHeader, Conway] =
    new EraBlockHeader[ConwayBlockHeader, Conway]
      with PraosEraBlockHeader[ConwayBlockHeader, Conway]:
      def blockIssuerBlockHeader(block: Block[ConwayBlockHeader, Conway]): KeyHash[BlockIssuer] =
        block.blockHeader.issuer
      def blockBodySizeBlockHeader(block: Block[ConwayBlockHeader, Conway]): Long =
        block.blockHeader.bodySize
      def blockBodyHashBlockHeader(block: Block[ConwayBlockHeader, Conway]): Hash32 =
        block.blockHeader.bodyHash
      def slotNoBlockHeader(block: Block[ConwayBlockHeader, Conway]): SlotNo =
        block.blockHeader.slot
      def protVerBlockHeader(block: Block[ConwayBlockHeader, Conway]): ProtVer =
        block.blockHeader.protVer

/** Why a Conway block's bytes are not a block in the era's types, by whose the failure is: the
  * block decoder refused them ([[Decode]]) or read a block of another era ([[OtherEra]]); the era's
  * types refuse what it read, a field the era does not have or bytes they do not decode ([[Era]]);
  * or the node's own two readings of the same bytes disagree, or a value the decoder's own bounds
  * rule out came of them ([[Projection]]), which is no fault of the bytes.
  */
enum ConwayBlockError:
  case Decode(cause: ChainError)
  case OtherEra(era: ChainEra)
  case Era(cause: EraTxError)
  case Projection(cause: EraTxError)

object ConwayBlockError:
  /** Whose a failure of the era's types on what the block decoder read is: the bytes' where the
    * era's types refuse them, the node's own where its readings disagree or a bound of its decoder
    * did not hold. A total match, so a new case of [[EraTxError]] must be placed here.
    */
  def of(cause: EraTxError): ConwayBlockError = cause match
    case EraTxError.Decode(_) | EraTxError.NotInEra(_) => Era(cause)
    case EraTxError.ScriptsReadAgain | EraTxError.WitnessDataReadAgain | EraTxError.OtherLevel |
        EraTxError.NoDelegatee | EraTxError.Vocab(_) | EraTxError.PParams(_) |
        EraTxError.StoredOutputDiffers | EraTxError.TranslationReadBack(_) =>
      Projection(cause)

/** A Conway block in the era's types. */
object ConwayBlock:
  /** The evidence that a flat block is one [[decode]] read from bytes: only this object makes one,
    * so the era's body is made from no other.
    */
  final class Decoded private[ConwayBlock] ()

  private given Decoded = Decoded()

  /** The block encoded as `raw` (`[7, [header, bodies, witnesses, auxiliary data, invalid]]`), as
    * the block decoder reads it (`Block.decodeShelley`): its header
    * ([[ConwayBlockHeader.fromHeader]]) and its body, made from the same bytes as its size and hash
    * ([[ConwayBlockBody.ofDecoded]]), each transaction in the pool parameters' `layout`.
    */
  def decode(
      raw: Bytes,
      layout: StateLayout
  ): Either[ConwayBlockError, Block[ConwayBlockHeader, Conway]] =
    FlatBlock
      .decodeShelley(raw)
      .leftMap(ConwayBlockError.Decode(_))
      .flatMap {
        case b: FlatBlock.Shelley if b.era == ChainEra.Conway =>
          for
            header <- ConwayBlockHeader.fromHeader(b.header)
            body <- ConwayBlockBody.ofDecoded(b, layout).leftMap(ConwayBlockError.of)
          yield Block[ConwayBlockHeader, Conway](header, body)
        case other => Left(ConwayBlockError.OtherEra(other.era))
      }
