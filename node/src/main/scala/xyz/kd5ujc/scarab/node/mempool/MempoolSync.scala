// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node.mempool

import cats.Eq
import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.{Handle, Raise}
import cats.syntax.all.*
import fs2.concurrent.Signal
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.ledger.NodeError
import xyz.kd5ujc.scarab.network.Fibers
import xyz.kd5ujc.scarab.node.{Fatal, Peer}
import xyz.kd5ujc.scarab.protocol.ChainSync

/** The mempool's synchronisation with the selected chain, from a fiber of its own that watches the
  * tip, as the reference's mempool runs it from the watcher `openMempool` forks
  * (`forkSyncStateOnTipPointChange`, ouroboros-consensus 82ecba32 and 808e4005 `Mempool/Init.hs`
  * 47-82, over `runWatcher`, `Util/STM.hs` 132-152). Chain selection never waits for it: the
  * follower only publishes its chain ([[xyz.kd5ujc.scarab.node.Follower.committedChain]]), and the
  * watcher reads it.
  *
  * As the reference's watcher, it reads the tip only as a fingerprint: once at start, and then
  * whenever the tip is not the one it saw last, taking the latest, so that the tips published while
  * a revalidation runs or waits are passed over (`blockUntilChanged`, `Util/STM.hs` 47-56). Each
  * time, the pool reads the chain again once its lock is held and decides there where to revalidate
  * ([[Mempool.synchronise]]), as the reference's `implSyncWithLedger` does: a revalidation that
  * waited behind an admission applies the chain as it is then, never a tip the chain has left
  * meanwhile. The pool may lag the chain for as long as a revalidation waits, as the reference's
  * does; the forger does not depend on it, since it validates the held transactions again at the
  * slot it forges in.
  */
object MempoolSync:
  /** Where to revalidate a pool in the state `pool`, given `chain` as it is published once the
    * pool's lock is held: none when the pool is at its tip already.
    */
  trait Target[F[_], A]:
    def apply(pool: MempoolState, chain: A)(using Raise[F, NodeError]): F[Option[Mempool.Target]]

  /** The watcher of `mempool` over the published `chains`, whose tips `tipOf` gives, for as long as
    * the resource is used. It ends only with a failure, and every failure stops the node through
    * `fatal`, as the reference's watcher thread is linked to the node's registry
    * (`forkLinkedThread`): a failure of storage or of the tick to the new tip, or an exception.
    */
  def resource[F[_]: Async, A](
      mempool: Mempool[F],
      chains: Signal[F, A],
      tipOf: A => ChainSync.Point,
      target: Target[F, A],
      fatal: Fatal => F[Unit]
  ): Resource[F, Unit] =
    watch(mempool, chains, tipOf, target).flatMap(_.fold(fatal, never => never)).background.void

  /** The watcher's loop in an error scope of its own: its failure is the value it ends with. The
    * first tip `discrete` gives is the chain's at start, so the pool is looked at once at start, as
    * the reference's watcher notifies once before it waits (`wInitial = Nothing`).
    */
  private[mempool] def watch[F[_]: Async, A](
      mempool: Mempool[F],
      chains: Signal[F, A],
      tipOf: A => ChainSync.Point,
      target: Target[F, A]
  ): F[Either[Fatal, Nothing]] =
    Fibers.scoped[F, Fatal, Nothing](Fatal.Defect(_)) {
      given Raise[F, NodeError] = Peer.contramapped(summon[Handle[F, Fatal]])(Fatal.of)
      chains.discrete
        .map(tipOf)
        .changes(using Eq.fromUniversalEquals[ChainSync.Point])
        .evalMap(_ => mempool.synchronise(pool => chains.get.flatMap(target(pool, _))))
        .compile
        .drain *> Async[F].never[Nothing]
    }

  /** How many of the newest blocks of a chain the pool has not been revalidated past. `current` is
    * the chain's anchor and blocks, oldest first, each by its hash (the anchor none at Origin);
    * `previous` the same of the chain the pool was last revalidated at; `pool` the pool's tip.
    *   - When `previous` ends at the pool's tip, the blocks after the last one the two chains
    *     share, or all of them when they share none (the chains part before the window `current`
    *     keeps).
    *   - Otherwise (the pool was moved by other means), the blocks after the pool's tip when the
    *     chain holds it, and none when it does not.
    *
    * It reads at most the blocks the chain keeps (`k`), never their history.
    */
  def unseen(
      previous: Vector[Option[Bytes]],
      pool: Option[Bytes],
      current: Vector[Option[Bytes]]
  ): Int =
    val blocks = (current.size - 1).max(0)
    if previous.lastOption.contains(pool) then
      val shared = previous.toSet
      current.lastIndexWhere(shared) match
        case -1    => blocks
        case index => current.size - 1 - index
    else
      current.lastIndexOf(pool) match
        case -1    => 0
        case index => current.size - 1 - index
