// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.forge

import cats.Applicative
import cats.effect.{Ref, Sync}
import cats.mtl.Raise
import cats.syntax.all.*
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.{PosixClock, StorageError}
import xyz.kd5ujc.scarab.ledger.era.{Tx, TxLevel}
import xyz.kd5ujc.scarab.ledger.state.{Dijkstra, NewEpochState}
import xyz.kd5ujc.scarab.network.LocalTxMonitorServer.TxMeasure
import xyz.kd5ujc.scarab.node.mempool.{
  Capacity,
  CertifyingView,
  DijkstraAdmitted,
  DijkstraPoolEnv,
  DijkstraState,
  Entry,
  Held,
  Mempool,
  MempoolEnv,
  MempoolState,
  Rejection
}
import xyz.kd5ujc.scarab.protocol.EbHash
import xyz.kd5ujc.scarab.storage.{UtxoReadView, UtxoStore}

/** A transaction of a Dijkstra forging snapshot: held by the pool, applied again on the ledger of
  * the slot being forged, and the measure it was admitted with, which the pool keeps through
  * revalidation as the reference keeps a ticket's (`snapshotTxs`, ouroboros-consensus 2416493b
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/API.hs:447-450`). The
  * transaction is the era's (`Tx TopTx DijkstraEra`) as it was validated, with the flag of its
  * verdict (`extractTx` of the snapshot's `Validated` transaction,
  * `Shelley/Ledger/Forge.hs:162-163` of ouroboros-consensus-cardano at 2416493b), which the ranking
  * block and the endorser block write in its block form
  * ([[xyz.kd5ujc.scarab.ledger.era.dijkstra.DijkstraTx.forBlockInclusion]]). Unlike [[ConwayHeld]]
  * it keeps the transaction, not what it was admitted with: the era's transaction holds the bytes
  * of its parts, which both blocks are made of, and the forger reads nothing else of it.
  */
final case class DijkstraHeld(tx: Tx[TxLevel.Top, Dijkstra], measure: TxMeasure)

/** `getSnapshotFor` of the pool under Dijkstra (`Mempool/API.hs:187-203`, ouroboros-consensus
  * 2416493b, as the forge loop takes it, `NodeKernel/Forge.hs:795-797`): the held transactions,
  * oldest first, that still apply on the ticked ledger of the slot, each with its measure, and the
  * held ones the rules have no verdict on, by id with the reason, which the forger withdraws from
  * the pool. Only a snapshot is partitioned into a ranking block ([[BlockBuilder.ranking]]), so a
  * block is built only of transactions the pool revalidated. The constructor is the pool's: the
  * snapshot is made where the transactions were revalidated.
  */
final case class DijkstraSnapshot private[node] (
    held: Vector[DijkstraHeld],
    withheld: Map[Bytes, Rejection]
)

object DijkstraSnapshot:
  /** A snapshot that holds no transaction. */
  val empty: DijkstraSnapshot = DijkstraSnapshot(Vector.empty, Map.empty)

  /** A snapshot of `held`, with none withheld. */
  private[node] def apply(held: Vector[DijkstraHeld]): DijkstraSnapshot =
    DijkstraSnapshot(held, Map.empty)

/** `SnapshotCachePolicy` (ouroboros-consensus d91b3557
  * `ouroboros-consensus/src/ouroboros-consensus/Ouroboros/Consensus/Mempool/Query.hs:25-38`):
  * whether a snapshot may be the pool's own revalidated transactions.
  */
enum SnapshotCachePolicy:
  /** `UseCache`: the state is the chain's at the tip whose hash is `tip`, so the pool's own
    * snapshot is the snapshot when the pool is at that tip and the slot allows it
    * ([[xyz.kd5ujc.scarab.node.mempool.MempoolState.dijkstraCached]]).
    */
  case UseCache(tip: Option[Bytes])

  /** `AlwaysRevalidate`: the state is not the tip's though the tip is the pool's, as when a
    * certified closure is applied to it (`getSnapshotForNoCache`).
    */
  case AlwaysRevalidate

/** Where the forger takes its Dijkstra snapshot from: the pool's `getSnapshotFor` of the slot, on
  * the state ticked into it, with the clock of a transaction in a block of that slot, by `policy`.
  */
trait DijkstraSnapshots[F[_]]:
  def forSlot(
      ticked: NewEpochState[Dijkstra],
      slot: BigInt,
      clock: PosixClock,
      policy: SnapshotCachePolicy
  )(using Raise[F, StorageError]): F[DijkstraSnapshot]

  /** The pool's certifying view ([[CertifyingView]]) of the endorser block `eb` the tip whose hash
    * is `tip` announces, kept: built on `base`, the tip's state with the closure of `eb` applied,
    * ticked to the slot after the tip, once the pool is at that tip (`syncCertifyingView`,
    * `Mempool/Update.hs:647-667` at ouroboros-consensus d91b3557), then brought up to date with
    * each change of the pool (`certifyingViewCatchUp`, `Mempool/CertifyingView.hs:101-136`), until
    * it is cancelled.
    */
  def certifying(eb: EbHash, tip: Option[Bytes], base: NewEpochState[Dijkstra])(using
      Raise[F, StorageError]
  ): F[Unit]

  /** `getCertifyingSnapshotFor` (`Mempool/API.hs`, `CertifyingView.hs:217-236` at
    * ouroboros-consensus d91b3557) of the block forged in `slot` that certifies `eb` on the chain
    * whose tip's hash is `tip`: the certifying view's transactions, when it is of that endorser
    * block on that tip and holds for the slot ([[CertifyingView.snapshotFor]]); none otherwise.
    */
  def certifyingFor(eb: EbHash, tip: Option[Bytes], slot: BigInt): F[Option[DijkstraSnapshot]]

object DijkstraSnapshots:
  /** The forger's snapshot of the transactions `kept`, each with its measure, and of those
    * `withheld`, by id.
    */
  private[node] def snapshotOf(
      kept: Vector[(Entry, DijkstraAdmitted)],
      withheld: Vector[(Entry, Rejection)]
  ): DijkstraSnapshot =
    DijkstraSnapshot(
      kept.map((entry, admitted) => DijkstraHeld(admitted.read.tx, Capacity.measure(entry))),
      withheld.map((entry, reason) => entry.id -> reason).toMap
    )

  /** A source of empty snapshots, for forgers of tests that take no transactions: it keeps no
    * certifying view.
    */
  def unadmitted[F[_]: Applicative]: DijkstraSnapshots[F] = new DijkstraSnapshots[F]:
    def forSlot(
        ticked: NewEpochState[Dijkstra],
        slot: BigInt,
        clock: PosixClock,
        policy: SnapshotCachePolicy
    )(using Raise[F, StorageError]) = Applicative[F].pure(DijkstraSnapshot.empty)
    def certifying(eb: EbHash, tip: Option[Bytes], base: NewEpochState[Dijkstra])(using
        Raise[F, StorageError]
    ) = Applicative[F].unit
    def certifyingFor(eb: EbHash, tip: Option[Bytes], slot: BigInt) =
      Applicative[F].pure(None)

  /** The node's (`getSnapshotUsingPolicyFor`, ouroboros-consensus d91b3557
    * `Mempool/Query.hs:80-127`): under [[SnapshotCachePolicy.UseCache]], the pool's own revalidated
    * transactions when they are the snapshot of `slot` already ([[MempoolState.dijkstraCached]]),
    * with none withheld, as each was judged when it was held; otherwise the snapshot of `mempool`
    * 's held Dijkstra transactions on `ticked`, the stored outputs their rules read fetched from
    * `utxoStore` first, as the reference reads them from the forker of the unticked state
    * (`readUntickedTables`), revalidated at `slot` in the environment `environment` gives
    * ([[MempoolState.dijkstraSnapshot]]).
    */
  def ofPool[F[_]: Sync](
      mempool: Mempool[F],
      utxoStore: Option[UtxoReadView[F]],
      environment: F[MempoolEnv]
  ): F[DijkstraSnapshots[F]] = Ref.of[F, Option[CertifyingView]](None).map { views =>
    new DijkstraSnapshots[F]:
      /** `state` with the stored outputs the Dijkstra transactions of `entries` read. */
      private def completed(state: DijkstraState, entries: Vector[Entry])(using
          Raise[F, StorageError]
      ): F[DijkstraState] =
        val reads = entries.map(_.held).collect { case h @ Held.InDijkstra(_) => h.in }
        UtxoStore
          .complete(utxoStore, state.utxo, MempoolState.readSet(reads))
          .map(state.withUtxo)

      def forSlot(
          ticked: NewEpochState[Dijkstra],
          slot: BigInt,
          clock: PosixClock,
          policy: SnapshotCachePolicy
      )(using Raise[F, StorageError]) =
        for
          pool <- mempool.snapshot
          env <- environment
          cached = policy match
            case SnapshotCachePolicy.UseCache(tip) =>
              pool.dijkstraCached(tip, slot, env.geometry)
            case SnapshotCachePolicy.AlwaysRevalidate => None
          snapshot <- cached.fold {
            for
              state <- completed(DijkstraState.of(ticked), pool.entries)
              judged <- Sync[F].blocking(
                MempoolState.dijkstraSnapshot(
                  pool.entries,
                  state,
                  DijkstraPoolEnv.of(env, slot).copy(clock = clock)
                )
              )
            yield snapshotOf(judged._1, judged._2)
          }(kept => Sync[F].pure(snapshotOf(kept, Vector.empty)))
        yield snapshot

      def certifying(eb: EbHash, tip: Option[Bytes], base: NewEpochState[Dijkstra])(using
          Raise[F, StorageError]
      ): F[Unit] =
        val start = DijkstraState.of(base)
        val epoch = base.epoch.toBigInt
        // the view of `pool`, built on the base with the stored outputs the pool reads
        def built(pool: MempoolState): F[Option[CertifyingView]] =
          for
            env <- environment
            on <- completed(start, pool.entries)
            view <- Sync[F].blocking(CertifyingView.build(eb, tip, epoch, on, pool, env))
          yield view
        // `view` brought up to date with `pool`: built again after a removal of a transaction it
        // kept, else the pool's new transactions applied on its state
        def updated(view: CertifyingView, pool: MempoolState): F[CertifyingView] =
          if pool.tipHash != tip then Sync[F].pure(view)
          else if view.removedFrom(pool) then built(pool).map(_.getOrElse(view))
          else
            val pending = view.pending(pool)
            if pending.isEmpty then Sync[F].pure(view)
            else
              for
                env <- environment
                state <- completed(view.revalidation.state, pending)
                next <- Sync[F].blocking(view.caughtUp(pending, state, env))
              yield next
        def kept(view: CertifyingView, seen: MempoolState): F[Unit] =
          mempool
            .awaitChange(_ eq seen)
            .flatMap(pool => updated(view, pool).flatTap(v => views.set(Some(v))).tupleRight(pool))
            .flatMap((next, pool) => kept(next, pool))
        // the view is built once the pool is at the tip, waiting for its sync to it otherwise
        def building(pool: MempoolState): F[Unit] =
          built(pool).flatMap {
            case Some(view) => views.set(Some(view)) *> kept(view, pool)
            case None       => mempool.awaitChange(_ eq pool).flatMap(building)
          }
        mempool.snapshot.flatMap(building)

      def certifyingFor(eb: EbHash, tip: Option[Bytes], slot: BigInt) =
        (views.get, mempool.snapshot, environment).mapN { (view, pool, env) =>
          view
            .flatMap(_.snapshotFor(eb, tip, slot, pool, env.geometry))
            .map(r => snapshotOf(r.kept, r.withheld))
        }
  }
