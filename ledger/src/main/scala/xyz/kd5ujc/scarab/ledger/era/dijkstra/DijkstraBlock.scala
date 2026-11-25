// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era.dijkstra

import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.{Bytes, Node}
import xyz.kd5ujc.scarab.crypto.Blake2b
import xyz.kd5ujc.scarab.ledger.{ChainError, LeiosBlock, LeiosHeader}
import xyz.kd5ujc.scarab.ledger.era.{
  Block,
  BlockBody,
  BlockHeaderVersionInfo,
  DijkstraEraBlockBody,
  EraBlockHeader,
  EraTx,
  EraTxError,
  LeiosEraBlockHeader,
  PerasCert,
  ProtVer,
  Tx,
  TxLevel
}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, Dijkstra, Hash32, KeyHash, SlotNo, VocabError}

/** `DijkstraBlockBody era` (`Dijkstra/BlockBody/Internal.hs:100-176`, cardano-ledger 9df9f98c): the
  * top-level transactions, the optional Leios and Peras certificates, and, as the memoized body
  * keeps them, the size and hash of the bytes it was read from (`MemoBytes`, `Internal.hs:148`).
  * Made only by [[DijkstraBlock.decode]], from a block's bytes, so its contents, size and hash are
  * those of one encoding: no body has a commitment its contents do not have.
  */
final class DijkstraBlockBody private (
    val txs: Vector[DijkstraTx[TxLevel.Top]],
    val leiosCert: Option[LeiosBlock.CertificateBytes],
    val perasCert: Option[PerasCert],
    val size: Long,
    val hash: Hash32
):
  override def toString: String =
    s"DijkstraBlockBody(${txs.size} transactions, $leiosCert, $perasCert, $size, $hash)"

object DijkstraBlockBody:
  /** The body of a block the block decoder read from its bytes (`LeiosBlock.decode`, the build's
    * `DecCBOR (Annotator (DijkstraBlockBody era))`), each transaction read in Dijkstra's types
    * ([[DijkstraTx.inBlock]]), the first that Dijkstra's types do not hold
    * ([[LeiosBlock.firstUnheld]]) its failure: the hash is the Blake2b-256 digest of the body's
    * bytes (`hashBlockBody = extractHash . getMemoBytesHash`, `Internal.hs:120`, cardano-ledger
    * 9df9f98c), which the decoder computed, and the size their length. Only
    * [[DijkstraBlock.decode]] holds the evidence ([[DijkstraBlock.Decoded]]), on the block it
    * decoded, so no copied block, whose contents differ from its size and hash, reaches it.
    */
  private[dijkstra] def ofDecoded(block: LeiosBlock)(using
      DijkstraBlock.Decoded
  ): Either[EraTxError, DijkstraBlockBody] =
    block.firstUnheld
      .toLeft(())
      .flatMap(_ => Hash32.fromBytes(block.bodyHash).leftMap(EraTxError.Vocab(_)))
      .map(hash =>
        new DijkstraBlockBody(
          block.transactions,
          block.leiosCertificate,
          block.perasCertificate.map(PerasCert(_)),
          block.bodySize,
          hash
        )
      )

  /** `instance EraBlockBody DijkstraEra` and `instance DijkstraEraBlockBody DijkstraEra`
    * (`Internal.hs:114-140`, cardano-ledger 9df9f98c). `blockBodySize` encodes the memoized body
    * again at the version, which writes the bytes it was read from (`encCBOR` of `MemoBytes`), so
    * it is their length at every version.
    */
  val instance: DijkstraEraBlockBody[Dijkstra] = new DijkstraEraBlockBody[Dijkstra]:
    def txOf: EraTx[Dijkstra] = EraTx.dijkstra
    def txSeqBlockBody(body: BlockBody[Dijkstra]): Vector[Tx[TxLevel.Top, Dijkstra]] = body.txs
    def hashBlockBody(body: BlockBody[Dijkstra]): Hash32 = body.hash
    def blockBodySize(protVer: ProtVer, body: BlockBody[Dijkstra]): Long = body.size
    def leiosCertBlockBody(body: BlockBody[Dijkstra]): Option[LeiosBlock.CertificateBytes] =
      body.leiosCert
    def perasCertBlockBody(body: BlockBody[Dijkstra]): Option[PerasCert] = body.perasCert

/** A Dijkstra header as the ledger reads it (`EraBlockHeader (Header c) DijkstraEra` and
  * `LeiosEraBlockHeader (Header c) DijkstraEra` of the build's Praos2 header): the header the block
  * decoder read, with the fields the classes give in their types, read once by [[fromHeader]].
  */
final class DijkstraBlockHeader private (
    val header: LeiosHeader,
    val issuer: KeyHash[BlockIssuer],
    val slot: SlotNo,
    val bodySize: Long,
    val bodyHash: Hash32,
    val versionInfo: BlockHeaderVersionInfo
):
  // Not a case class: its derived `Mirror` would make one whose fields are not its header's.
  override def toString: String =
    s"DijkstraBlockHeader($issuer, $slot, $bodySize, $bodyHash, $versionInfo)"

object DijkstraBlockHeader:
  private val Word32Max: BigInt = BigInt(0xffffffffL)

  /** The fields of a decoded header in the classes' types: the issuer the Blake2b-224 hash of its
    * cold key (`hashKey`), the declared body size a `Word32` (the decoder reads it as one, as it
    * does the version info's two words), and the declared body hash 32 bytes. A field outside its
    * type, which the header decoder does not give, is a vocabulary error.
    */
  def fromHeader(header: LeiosHeader): Either[EraTxError, DijkstraBlockHeader] =
    val info = header.leios.versionInfo
    (
      KeyHash.fromBytes[BlockIssuer](Blake2b.hash224.hash(header.issuerVkey)),
      SlotNo.fromBigInt(header.slot),
      Either.cond(
        header.bodySize >= 0 && header.bodySize <= Word32Max,
        header.bodySize.toLong,
        VocabError.OutOfRange(VocabError.Kind.Word32, header.bodySize)
      ),
      Hash32.fromBytes(header.bodyHash),
      BlockHeaderVersionInfo
        .of(info.highestSupportedMajor, info.softwareTag)
        .toRight(
          VocabError.OutOfRange(
            VocabError.Kind.Word32,
            info.highestSupportedMajor.max(info.softwareTag)
          )
        )
    ).mapN(new DijkstraBlockHeader(header, _, _, _, _, _)).leftMap(EraTxError.Vocab(_))

  /** The build's Praos2 header's instances of the two classes (`EraBlockHeader (Header c) era` and
    * `LeiosEraBlockHeader (Header c) era`, `libs/cardano-protocol/src/Cardano/Protocol/Leios/
    * BlockHeader.hs:363-379`, cardano-ledger 9df9f98c), the previous nonce the class's default.
    */
  val instance: EraBlockHeader[DijkstraBlockHeader, Dijkstra] & LeiosEraBlockHeader[
    DijkstraBlockHeader,
    Dijkstra
  ] =
    new EraBlockHeader[DijkstraBlockHeader, Dijkstra]
      with LeiosEraBlockHeader[DijkstraBlockHeader, Dijkstra]:
      def blockIssuerBlockHeader(
          block: Block[DijkstraBlockHeader, Dijkstra]
      ): KeyHash[BlockIssuer] =
        block.blockHeader.issuer
      def blockBodySizeBlockHeader(block: Block[DijkstraBlockHeader, Dijkstra]): Long =
        block.blockHeader.bodySize
      def blockBodyHashBlockHeader(block: Block[DijkstraBlockHeader, Dijkstra]): Hash32 =
        block.blockHeader.bodyHash
      def slotNoBlockHeader(block: Block[DijkstraBlockHeader, Dijkstra]): SlotNo =
        block.blockHeader.slot
      def versionInfoBlockHeader(
          block: Block[DijkstraBlockHeader, Dijkstra]
      ): BlockHeaderVersionInfo = block.blockHeader.versionInfo

/** Why a Dijkstra block's bytes are not a block in the era's types, by whose the failure is: the
  * block decoder refused them ([[Decode]]); the era's types refuse what it read, a field the era
  * does not have or bytes they do not decode ([[Era]]); or a value the decoder's own bounds rule
  * out came of them, in a transaction ([[LeiosBlock.unread]]) or in the header ([[Projection]]),
  * which is no fault of the bytes. A transaction's field Dijkstra does not have
  * ([[LeiosBlock.outsideEra]]) is [[Era]].
  */
enum DijkstraBlockError:
  case Decode(cause: ChainError)
  case Era(cause: EraTxError)
  case Projection(cause: EraTxError)

object DijkstraBlockError:
  /** Whose a failure of the era's types on what the block decoder read is: the bytes' where the
    * era's types refuse them, the node's own where its readings disagree or a bound of its decoder
    * did not hold. A total match, so a new case of [[EraTxError]] must be placed here.
    */
  def of(cause: EraTxError): DijkstraBlockError = cause match
    case EraTxError.Decode(_) | EraTxError.NotInEra(_) => Era(cause)
    case EraTxError.ScriptsReadAgain | EraTxError.WitnessDataReadAgain | EraTxError.OtherLevel |
        EraTxError.NoDelegatee | EraTxError.Vocab(_) | EraTxError.PParams(_) |
        EraTxError.StoredOutputDiffers | EraTxError.TranslationReadBack(_) =>
      Projection(cause)

/** A Dijkstra block in the era's types. */
object DijkstraBlock:
  /** The evidence that a block is one [[decode]] read from bytes: only this object makes one, so
    * the era's body is made from no other.
    */
  final class Decoded private[DijkstraBlock] ()

  private given Decoded = Decoded()

  /** The block encoded as `raw` (`[8, [header, body]]`), as the block decoder reads it
    * (`LeiosBlock.decode`): its header ([[DijkstraBlockHeader.fromHeader]]) and its body, made from
    * the same bytes as its size and hash ([[DijkstraBlockBody.ofDecoded]]).
    */
  def decode(raw: Bytes): Either[DijkstraBlockError, Block[DijkstraBlockHeader, Dijkstra]] =
    decodeWith(raw, DijkstraTx.inBlock)

  /** [[decode]] with the transactions read by `reader`: the era's decoder, or a test's. */
  private[ledger] def decodeWith(
      raw: Bytes,
      reader: Node => Either[EraTxError, DijkstraTx[TxLevel.Top]]
  ): Either[DijkstraBlockError, Block[DijkstraBlockHeader, Dijkstra]] =
    LeiosBlock
      .decodeWith(raw, reader)
      .leftMap(DijkstraBlockError.Decode(_))
      .flatMap(block =>
        (DijkstraBlockHeader.fromHeader(block.header), DijkstraBlockBody.ofDecoded(block))
          .mapN(Block[DijkstraBlockHeader, Dijkstra](_, _))
          .leftMap(DijkstraBlockError.of)
      )
