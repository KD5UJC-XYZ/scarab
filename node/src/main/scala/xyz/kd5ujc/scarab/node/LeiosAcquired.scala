// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Ref}
import cats.effect.std.Queue
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import fs2.concurrent.SignallingRef
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.consensus.AcquiredLeiosEbs
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.protocol.{EbHash, EbPoint, LeiosTxHash}
import xyz.kd5ujc.scarab.protocol.LeiosWord.{Word32, Word64}
import xyz.kd5ujc.scarab.storage.{
  BodyWritten,
  LeiosEb,
  LeiosEbNotification,
  LeiosStore,
  LeiosStoreError,
  LocalFill,
  PointWritten
}

/** The endorser blocks whose closure is acquired, which chain selection reads
  * ([[xyz.kd5ujc.scarab.consensus.LeiosCertGate]]): the ChainDB's `cdbAcquiredLeiosEbs`
  * (ouroboros-consensus 2416493b `Storage/ChainDB/Impl/Types.hs:405-445`), held by the block pool,
  * the node's ChainDB. It is
  *
  *   - seeded when the chain database opens, before the volatile chain is replayed, from the
  *     store's complete closures announced no earlier than the immutable tip
  *     ([[LeiosAcquired.scanned]], [[seed]], `ChainDB/Impl.hs:236-247`);
  *   - grown from the store's completions ([[acquired]]), which the selection worker takes one at a
  *     time ([[next]]), as `leiosAcquiredEbsRunner` takes the store's `AcquiredEbTxs` notifications
  *     (`ChainDB/Impl/Background.hs:170-200`);
  *   - pruned when the collection is scheduled ([[pruned]]).
  *
  * The completions are the points each write of the store returns, which are the points the
  * reference's writes notify `AcquiredEbTxs` for (`Storage/LeiosDB/Impl/SQLite/Insert.hs:105-111,
  * 160-163, 318-322`). A writer offers them to an unbounded channel ([[notifying]]) rather than
  * updating the set and queueing a reselection itself, as the reference's store notifies a channel
  * its runner reads: so a write never waits for the chain-selection queue, whose capacity is
  * bounded. The same writer publishes what each write acquired to the store's other subscribers
  * ([[notifications]]), the LeiosNotify servers among them.
  */
private[node] final class LeiosAcquired[F[_]] private (
    state: Ref[F, AcquiredLeiosEbs],
    completions: Queue[F, EbPoint],
    immutable: SignallingRef[F, Option[BigInt]],
    val notifications: LeiosEbChannel[F]
)(using F: Async[F]):
  def snapshot: F[AcquiredLeiosEbs] = state.get

  /** The set the chain database opened with ([[LeiosAcquired.scanned]]). */
  def seed(opened: AcquiredLeiosEbs): F[Unit] = state.set(opened)

  /** The next completion of the store, waiting for one. */
  def next: F[EbPoint] = completions.take

  /** `point`'s endorser block is acquired: true if it was not before (`insertAcquiredLeiosEb`). */
  def acquired(point: EbPoint): F[Boolean] =
    state.modify(held => held.inserted(point).fold((held, false))((novel, next) => (next, novel)))

  /** `pruneAcquiredLeiosEbs` at the immutable tip's slot, which [[immutableSlots]] then reports. */
  def pruned(immutableSlot: BigInt): F[Unit] =
    state.update(held => held.pruned(Some(immutableSlot)).getOrElse(held)) *>
      immutable.set(Some(immutableSlot))

  /** The immutable tip's slot each time the collection is scheduled at another one: what the fetch
    * logic is pruned at (`NodeKernel.leiosImmTipPrune`, a `Watcher` whose fingerprint is the slot,
    * `NodeKernel.hs:711-736` at ouroboros-consensus 2416493b).
    */
  def immutableSlots: Stream[F, BigInt] = immutable.discrete.unNone.changes

  /** `store`, its completions offered to [[next]] and what each write acquired published to
    * [[notifications]] (`notify`, `Storage/LeiosDB/Impl/SQLite/Insert.hs` at ouroboros-consensus
    * 2416493b). Each write, the offer of what it completed and the publication are one uncancelable
    * step, so that no completion or notification is lost to a cancelled writer.
    */
  def notifying(store: LeiosStore[F]): LeiosStore[F] = new LeiosStore[F]:
    private def offered(points: Vector[EbPoint]): F[Unit] = points.traverse_(completions.offer)

    def writeEbPoint(point: EbPoint, announced: Word32)(using
        Raise[F, LeiosStoreError]
    ): F[PointWritten] =
      F.uncancelable(_ =>
        store
          .writeEbPoint(point, announced)
          .flatTap(w =>
            offered(w.completed) *> notifications.publish(LeiosEbNotification.of(point, w))
          )
      )

    def writeEbBody(point: EbPoint, body: LeiosEb, fills: Vector[LocalFill])(using
        Raise[F, LeiosStoreError]
    ): F[BodyWritten] =
      F.uncancelable(_ =>
        store
          .writeEbBody(point, body, fills)
          .flatTap { w =>
            // `encodeLeiosEbSize eb` (`Insert.hs:161` at ouroboros-consensus 2416493b): the length
            // of the body's encoding
            val size = Word32.wrap(body.encoded.size.toLong)
            offered(w.completed) *> notifications.publish(LeiosEbNotification.of(size, w))
          }
      )

    def writeTxs(point: EbPoint, txs: Vector[(Int, Bytes)])(using
        Raise[F, LeiosStoreError]
    ): F[Vector[EbPoint]] =
      F.uncancelable(_ =>
        store
          .writeTxs(point, txs)
          .flatTap(completed =>
            offered(completed) *>
              notifications.publish(completed.map(LeiosEbNotification.AcquiredEbTxs(_)))
          )
      )

    def lookupEbBody(hash: EbHash)(using Raise[F, LeiosStoreError]): F[Option[Bytes]] =
      store.lookupEbBody(hash)

    def lookupEbClosure(hash: EbHash)(using
        Raise[F, LeiosStoreError]
    ): F[Option[Vector[(LeiosTxHash, Bytes)]]] = store.lookupEbClosure(hash)

    def batchRetrieveTxs(hash: EbHash, offsets: Vector[Int])(using
        Raise[F, LeiosStoreError]
    ): F[Vector[(Int, Bytes)]] = store.batchRetrieveTxs(hash, offsets)

    def scanEbPoints(using Raise[F, LeiosStoreError]): F[Vector[EbPoint]] = store.scanEbPoints

    def scanCompleteEbClosuresNotOlderThanSlot(slot: Word64)(using
        Raise[F, LeiosStoreError]
    ): F[Vector[EbPoint]] = store.scanCompleteEbClosuresNotOlderThanSlot(slot)

    def garbageCollect(slot: Word64)(using Raise[F, LeiosStoreError]): F[Unit] =
      store.garbageCollect(slot)

    def promoteToImmutable(points: Vector[EbPoint])(using Raise[F, LeiosStoreError]): F[Unit] =
      store.promoteToImmutable(points)

private[node] object LeiosAcquired:
  /** The set at the open of the chain database: the store's complete closures announced at or after
    * the immutable tip's slot (the origin's is 0), `scanCompleteEbClosuresNotOlderThanSlot`
    * (`ChainDB/Impl.hs:236-247` at ouroboros-consensus 2416493b).
    */
  def scanned[F[_]: Async](store: LeiosStore[F], immutableTip: Option[BigInt])(using
      Raise[F, StorageError]
  ): F[AcquiredLeiosEbs] =
    // a slot past every Word64 has no point at or after it
    Word64
      .from(immutableTip.getOrElse(BigInt(0)))
      .fold(
        _ => Async[F].pure(Vector.empty[EbPoint]),
        slot => store.scanCompleteEbClosuresNotOlderThanSlot(slot)(using LeiosStorage.lifted[F])
      )
      .map(AcquiredLeiosEbs.of)

  def create[F[_]: Async]: F[LeiosAcquired[F]] =
    (
      Ref.of[F, AcquiredLeiosEbs](AcquiredLeiosEbs.empty),
      Queue.unbounded[F, EbPoint],
      SignallingRef.of[F, Option[BigInt]](None),
      LeiosEbChannel.create[F]
    ).mapN(new LeiosAcquired(_, _, _, _))
