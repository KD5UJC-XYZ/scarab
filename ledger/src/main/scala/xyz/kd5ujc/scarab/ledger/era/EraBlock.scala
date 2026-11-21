// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger.era

import scala.annotation.unused
import xyz.kd5ujc.scarab.ledger.{LeiosBlock, Nonce}
import xyz.kd5ujc.scarab.ledger.state.{BlockIssuer, Dijkstra, EraTag, Hash32, KeyHash, SlotNo}

// A block and the classes of its body and header as the reference declares them (cardano-ledger
// 9df9f98c `libs/cardano-ledger-core/src/Cardano/Ledger/Core.hs:644-669`,
// `libs/cardano-ledger-core/src/Cardano/Ledger/Block.hs:51-158`,
// `eras/dijkstra/impl/src/Cardano/Ledger/Dijkstra/BlockBody/Internal.hs:100-246`), with the
// members BBODY reads and Dijkstra's instances. The reference's lenses are getters here: the rules
// only read a block. The members the rules do not read (`mkBasicBlockBody`,
// `blockHeaderSizeBlockHeaderG`, `ebReferencesAnnouncementBlockHeaderL`) come with their readers.

/** The block body of era `E` (`type BlockBody era`, `Core.hs:654`, cardano-ledger 9df9f98c). */
type BlockBody[E <: EraTag] = E match
  case Dijkstra => dijkstra.DijkstraBlockBody

/** `data Block h era = Block {blockHeader :: h, blockBody :: BlockBody era}` (`Block.hs:51-55`,
  * cardano-ledger 9df9f98c): a header of type `H` and the era's body.
  */
final case class Block[H, E <: EraTag](blockHeader: H, blockBody: BlockBody[E])

/** `data BlockHeaderVersionInfo` (`Block.hs:151-158`, cardano-ledger 9df9f98c): what the block's
  * producer reports of its version, two `Word32`, neither validated when decoded.
  *
  * @param bhviHighestSupportedMajorVersion
  *   the highest major protocol version the producer can hard fork into
  * @param bhviSelfReportedSoftwareTag
  *   a value of the producer's software, which the ledger does not interpret
  */
final case class BlockHeaderVersionInfo private (
    bhviHighestSupportedMajorVersion: Long,
    bhviSelfReportedSoftwareTag: Long
)

object BlockHeaderVersionInfo:
  private val Word32Max: BigInt = BigInt(0xffffffffL)

  /** The two words, or none where one is outside `Word32`. */
  def of(highest: BigInt, tag: BigInt): Option[BlockHeaderVersionInfo] =
    Option.when(Seq(highest, tag).forall(w => w >= 0 && w <= Word32Max))(
      BlockHeaderVersionInfo(highest.toLong, tag.toLong)
    )

  given CanEqual[BlockHeaderVersionInfo, BlockHeaderVersionInfo] = CanEqual.derived

/** `PerasCert` (`Dijkstra/BlockBody/Internal.hs:221-226`, cardano-ledger 9df9f98c): a placeholder
  * of the build, a byte array, which its block decoder reads as a byte string.
  */
final case class PerasCert(bytes: xyz.kd5ujc.scarab.codec.Bytes)

object PerasCert:
  given CanEqual[PerasCert, PerasCert] = CanEqual.derived

/** `class EraTx era => EraBlockBody era` (`Core.hs:644-669`, cardano-ledger 9df9f98c): the
  * transactions of a body, its hash and its size. The superclass is a member ([[txOf]]).
  */
trait EraBlockBody[E <: EraTag]:
  /** The transactions' class. */
  def txOf: EraTx[E]

  /** `txSeqBlockBodyL`: the body's top-level transactions, in order. */
  def txSeqBlockBody(body: BlockBody[E]): Vector[Tx[TxLevel.Top, E]]

  /** `hashBlockBody`: the hash the body's header commits to. */
  def hashBlockBody(body: BlockBody[E]): Hash32

  /** `blockBodySize`: the body's size in bytes at the protocol version, the reference's `Int`. */
  def blockBodySize(protVer: ProtVer, body: BlockBody[E]): Long

/** `class EraBlockBody era => DijkstraEraBlockBody era` (`Dijkstra/BlockBody/Internal.hs:130-135`,
  * cardano-ledger 9df9f98c): the body's Leios and Peras certificates.
  */
trait DijkstraEraBlockBody[E <: EraTag] extends EraBlockBody[E]:
  /** `leiosCertBlockBodyL`. */
  def leiosCertBlockBody(body: BlockBody[E]): Option[LeiosBlock.CertificateBytes]

  /** `perasCertBlockBodyL`. */
  def perasCertBlockBody(body: BlockBody[E]): Option[PerasCert]

object EraBlockBody:
  given dijkstra: DijkstraEraBlockBody[Dijkstra] =
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockBody.instance

object DijkstraEraBlockBody:
  given dijkstra: DijkstraEraBlockBody[Dijkstra] = EraBlockBody.dijkstra

/** `class Era era => EraBlockHeader h era` (`Block.hs:120-125`, cardano-ledger 9df9f98c): what the
  * header `H` of a block of era `E` gives.
  */
trait EraBlockHeader[H, E <: EraTag]:
  /** `blockIssuerBlockHeaderG`: the hash of the issuer's cold key. */
  def blockIssuerBlockHeader(block: Block[H, E]): KeyHash[BlockIssuer]

  /** `blockBodySizeBlockHeaderL`: the body size the header declares, a `Word32`. */
  def blockBodySizeBlockHeader(block: Block[H, E]): Long

  /** `blockBodyHashBlockHeaderL`: the body hash the header declares. */
  def blockBodyHashBlockHeader(block: Block[H, E]): Hash32

  /** `slotNoBlockHeaderL`. */
  def slotNoBlockHeader(block: Block[H, E]): SlotNo

/** `class Era era => LeiosEraBlockHeader h era` (`Block.hs:132-147`, cardano-ledger 9df9f98c). */
trait LeiosEraBlockHeader[H, E <: EraTag]:
  /** `prevNonceBlockHeaderL`: the class's default, the neutral nonce, which no header of the build
    * overrides (`Block.hs:140-141`, cardano-ledger 9df9f98c).
    */
  def prevNonceBlockHeader(@unused block: Block[H, E]): Nonce = Nonce.Neutral

  /** `versionInfoBlockHeaderL`. */
  def versionInfoBlockHeader(block: Block[H, E]): BlockHeaderVersionInfo

object EraBlockHeader:
  given dijkstra: EraBlockHeader[
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockHeader,
    Dijkstra
  ] = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockHeader.instance

object LeiosEraBlockHeader:
  given dijkstra: LeiosEraBlockHeader[
    xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockHeader,
    Dijkstra
  ] = xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraBlockHeader.instance
