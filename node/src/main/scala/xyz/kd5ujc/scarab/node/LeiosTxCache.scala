// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import xyz.kd5ujc.scarab.consensus.leios.{
  AnnouncingHeader,
  InsertBodySummary,
  LeiosTxCacheIndex,
  ProtVerParity,
  ReferencesTxsByHash
}
import xyz.kd5ujc.scarab.crypto.bls.RbHash
import xyz.kd5ujc.scarab.protocol.{EbHash, LeiosTxHash}
import xyz.kd5ujc.scarab.storage.LeiosEb

/** The node's LeiosTxCache (`LeiosTxCache`, `LeiosTxCache.hs` at ouroboros-consensus 2416493b, the
  * reference implementation's handle `newPureLeiosTxCache`, `:84-123`): the in-memory index of the
  * recent endorser blocks' announcements, bodies and transactions ([[LeiosTxCacheIndex]]) behind
  * one reference, each operation one atomic update of it. A transaction is recorded with nothing
  * when it is fetched (`()`) and with the [[ProtVerParity]] it was validated under when the mempool
  * or the voting thread applied it; a body is recorded as the store holds it ([[LeiosEb]]), which
  * lists its transactions.
  *
  * Its sites, as the reference's: an announcement is inserted where the node first takes it
  * (`recordAnnouncementInTxCache`, `LeiosDemoLogic.hs:179-187`, from
  * `processAnnouncementCentrally`, `:1830`); a novel body under the fetch logic's lock
  * (`insertBody`, `:1003-1012`); a peer's transactions unapplied, and the mempool's applied with
  * the mempool's parity (`processLeiosBlockTxs`, `:1561-1572`); the closure's transactions the
  * voting thread validated in full (`recordValidated`, `LeiosVoting.hs:558-565`); and the
  * announcements older than the slot the Leios store is collected at are evicted first
  * (`cdbsLeiosEvictTxCache`, `Background.hs:551-559`).
  */
final class LeiosTxCache[F[_]: Sync] private (index: Ref[F, LeiosTxCache.Index]):
  import LeiosTxCache.given

  /** `insertAnnouncement` of the header `rb` of `slot` announcing `eb`; what it evicts is dropped.
    */
  def insertAnnouncement(slot: BigInt, rb: RbHash, eb: EbHash): F[Unit] =
    index.update(_.insertAnnouncement(slot, rb, eb)._1)

  /** `recordAnnouncementInTxCache` (`LeiosDemoLogic.hs:179-187` at 2416493b): the announcement of
    * `anc`, keyed by its endorser block's slot, the announcing header's hash and the endorser
    * block's hash. A header's hash is a Blake2b-256 digest, so it is always an [[RbHash]].
    */
  def announced(anc: AnnouncingHeader): F[Unit] =
    val point = anc.fields.point
    RbHash
      .fromBytes(anc.headerHash)
      .traverse_(rb => insertAnnouncement(point.slot.toBigInt, rb, point.hash))

  /** `evictOlderThan` `slot`; what it evicts is dropped. */
  def evictOlderThan(slot: BigInt): F[Unit] = index.update(_.evictOlderThan(slot)._1)

  /** `insertBody` of `eb`'s body, with what its transactions were before. */
  def insertBody(eb: EbHash, body: LeiosEb): F[Option[InsertBodySummary]] =
    index.modify(_.insertBody(eb, body))

  /** `withLockedInsertUnappliedTx` of a peer's transactions, in one update. */
  def insertUnapplied(txs: Iterable[LeiosTxHash]): F[Unit] =
    index.update(i => txs.foldLeft(i)((acc, h) => acc.insertUnappliedTx(h, ())._1))

  /** `withLockedInsertAppliedTx` of transactions validated under `parity`, in one update. */
  def insertApplied(txs: Iterable[LeiosTxHash], parity: ProtVerParity): F[Unit] =
    index.update(i => txs.foldLeft(i)(_.insertAppliedTx(_, parity)))

  /** `withLookupTx`: the index as one read sees it, to look transactions up in. */
  def lookup: F[LeiosTxHash => Option[Either[Unit, ProtVerParity]]] =
    index.get.map(i => i.lookupTx)

  /** The index, for tests and introspection. */
  def snapshot: F[LeiosTxCache.Index] = index.get

object LeiosTxCache:
  type Index = LeiosTxCacheIndex[Unit, ProtVerParity, LeiosEb]

  /** A body's transactions are those its entries list, in its order. */
  given ReferencesTxsByHash[LeiosEb] = _.transactions.toVector.map(_._1)

  /** An empty cache (`emptyLeiosTxCacheIndex`). */
  def create[F[_]: Sync]: F[LeiosTxCache[F]] =
    Ref.of[F, Index](LeiosTxCacheIndex.empty).map(new LeiosTxCache(_))
