// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.storage

import cats.Functor
import cats.mtl.Raise
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.{LeiosBlockInfo, LeiosCert}
import xyz.kd5ujc.scarab.ledger.{Block, ChainError, StorageError}
import xyz.kd5ujc.scarab.protocol.EbPoint
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64

/** What the chain database's background reads to drive the Leios store, as ouroboros-consensus
  * 2416493b's does (`Storage/ChainDB/Impl/Background.hs`): the endorser blocks that a copy to the
  * immutable store certifies, which it promotes (`copyToImmutableDB`, 230-292), and the store's
  * errors as the chain database's. [[ChainStorage]] makes the calls: the promotion once the copied
  * blocks are appended, before the copy's collection is scheduled, and the store's collection after
  * the received log's, at the same slot (`garbageCollectBlocks`, 547-559).
  */
object LeiosBackground:
  /** The fields of a block that the promotion reads: those of the reference's VolatileDB
    * `BlockInfo` (`biHash`, `biSlotNo`, `biPrevHash`, `biHasLeiosCert`, `biLeiosAnnouncedEb`), as
    * `extractBlockInfo` takes them from the block (`Storage/VolatileDB/Impl/Parser.hs:119-140` at
    * 2416493b).
    */
  final case class Info(hash: Bytes, slot: BigInt, prevHash: Option[Bytes], leios: LeiosBlockInfo)

  object Info:
    /** The fields of the block `raw`, decoded in full: the certificate is the body's. */
    def of(byronEpochSlots: BigInt)(raw: Bytes): Either[ChainError, Info] =
      Block
        .decode(raw, byronEpochSlots)
        .map(b => Info(b.header.hash, b.header.slot, b.header.prevHash, LeiosBlockInfo.of(b)))

  /** The endorser block the block `hash` certifies, if it carries a certificate: the one its
    * predecessor announced, at the predecessor's slot (`certifiedEb`, `Background.hs:291-310`;
    * `announcementLeiosPoint`, `LeiosDemoTypes.hs:1768-1770`, whose slot is the announcing header's
    * election slot, `headerElId`, `Shelley/Ledger/Leios.hs:280-285` of ouroboros-consensus-cardano
    * at 2416493b). `lookup` reads the blocks as the reference reads its VolatileDB: none for a
    * block it does not hold, a block on genesis or a predecessor that announced nothing. A slot
    * outside a `Word64` is no block's, and names no point.
    */
  def certifiedEb(lookup: Bytes => Option[Info])(hash: Bytes): Option[EbPoint] =
    lookup(hash).flatMap {
      case Info(_, _, Some(previous), LeiosBlockInfo(LeiosCert.Carried, _)) =>
        lookup(previous).flatMap(announcer =>
          announcer.leios.announced.flatMap(eb =>
            Word64.from(announcer.slot).toOption.map(EbPoint(_, eb))
          )
        )
      case Info(_, _, None, LeiosBlockInfo(LeiosCert.Carried, _)) => None
      case Info(_, _, _, LeiosBlockInfo(LeiosCert.Absent, _))     => None
    }

  /** The store's errors raised as the chain database's ([[StorageError.LeiosStore]]). */
  def lifted[F[_]](using outer: Raise[F, StorageError]): Raise[F, LeiosStoreError] =
    new Raise[F, LeiosStoreError]:
      def functor: Functor[F] = outer.functor
      def raise[E2 <: LeiosStoreError, A](e: E2): F[A] = outer.raise(StorageError.LeiosStore(e))
