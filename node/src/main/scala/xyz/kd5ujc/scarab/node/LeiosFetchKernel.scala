// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.Order
import cats.effect.{Async, Resource}
import cats.effect.syntax.all.*
import cats.mtl.Raise
import cats.syntax.all.*
import fs2.Stream
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.consensus.leios.{
  BytesSize,
  LeiosFetchLogic,
  LeiosFetchStaticEnv,
  LeiosOutstanding,
  StdGen
}
import xyz.kd5ujc.scarab.protocol.LeiosWord.Word64
import xyz.kd5ujc.scarab.storage.{LeiosStore, LeiosStoreError}

/** The node's Leios fetch logic (`NodeKernel.hs` 543-653 and 711-736, `initInternalState` 938-951
  * of ouroboros-consensus 2416493b): the decision loop, which assigns the peers' offers to requests
  * whenever it is woken, and its pruning at the immutable tip.
  */
object LeiosFetchKernel:
  /** The loop's pace: at most one decision per 20 ms (`loopInterval`, `NodeKernel.hs:643` at
    * ouroboros-consensus d91b3557; half a second until rc-2026w47).
    */
  val LoopInterval: FiniteDuration = 20.millis

  /** The fetch logic's state at the start (`initializeLeiosOutstanding`, `NodeKernel.hs:938-951` at
    * 2416493b): every endorser block whose whole closure the store holds, announced no earlier than
    * the immutable tip (the origin's is slot 0), held with nothing to fetch; everything pruned
    * below the tip. It reads the store and writes nothing.
    */
  def initialized[F[_]: Async, P: Order](
      store: LeiosStore[F],
      prng: StdGen,
      immutableTip: Word64
  )(using Raise[F, LeiosStoreError]): F[LeiosOutstanding[P]] =
    store
      .scanCompleteEbClosuresNotOlderThanSlot(immutableTip)
      .map(LeiosOutstanding.initialized[P](prng, _, immutableTip))

  /** One decision, once woken (the body of `NodeKernel.leiosFetchLogic`): the live peers and their
    * offers are read, then, holding the state, the live peers again, so that a peer gone meanwhile
    * is assigned nothing whose answer would never come; the offers the decision finds dead are
    * dropped and its requests queued, each on the peers read first. `forecast` is the closure's
    * bound forecast from the immutable tip, read once for the pass; `currentSlot` is unknown while
    * the clock is.
    *
    * A peer is still live only with the vars read first: a connection demoted and activated again
    * meanwhile keeps its identity with fresh vars, and the decision leaves it to the next pass
    * rather than charge the new activation with requests queued on the old one's vars, which no
    * client reads. The reference re-reads the live peers by their keys alone (`Map.restrictKeys
    * offerings (Map.keysSet stillLivePeers)`, `NodeKernel.hs` 571-603 at 2416493b), so the race its
    * re-read closes stays open there for a reactivation.
    */
  def step[F[_], P: Order, E](
      shared: LeiosFetchShared[F, P],
      env: LeiosFetchStaticEnv,
      currentSlot: F[Option[Word64]],
      forecast: F[Word64 => Either[E, BytesSize]]
  )(using F: Async[F]): F[Unit] =
    for
      peers <- shared.peers.get
      offerings <- peers.toVector.traverse((p, vars) => vars.offerings.get.tupleLeft(p))
      decision <- shared.outstanding.evalModify { outstanding =>
        for
          stillLive <- shared.peers.get
          // the peers live with the vars the offers were read from
          same = peers.filter((p, vars) => stillLive.get(p).exists(_ eq vars))
          slot <- currentSlot
          bound <- forecast
          decided = LeiosFetchLogic.iteration(
            env,
            bound,
            slot,
            offerings.filter((p, _) => same.contains(p)).toMap,
            same.view.mapValues(_.whetherBigLedgerPeer).toMap,
            outstanding
          )
        yield (decided.outstanding, decided)
      }
      _ <- decision.drops.toVector.traverse_((p, dropped) =>
        peers.get(p).traverse_(_.offerings.update(_ -- dropped.toSortedSet))
      )
      _ <- decision.requests.toVector.traverse_((p, requests) =>
        peers.get(p).traverse_(_.requestsToSend.offer(requests))
      )
    yield ()

  /** The central announcements' dedup (`pruneCentralState`, `Announcements.hs` 286-293 at
    * 2416493b), then `pruneOutstandingToImmTip`, and the peers' offers and certification claims
    * below the immutable tip's slot: all are keyed slot first, so those are a prefix.
    */
  def pruned[F[_], P](
      shared: LeiosFetchShared[F, P],
      central: LeiosChainSync.Central[F],
      immutableTip: Word64
  )(using F: Async[F]): F[Unit] =
    val tip = immutableTip.toBigInt
    central.update(_.prunedToImmTip(immutableTip)) *>
      shared.outstanding.update(_.prunedToImmTip(immutableTip)._2) *>
      shared.peers.get.flatMap(
        _.values.toVector.traverse_(vars =>
          vars.offerings.update(_.dropWhile((point, _) => point.slot.toBigInt < tip)) *>
            vars.certificationClaims.update(_.dropWhile((el, _) => el.slot.toBigInt < tip))
        )
      )

  /** The decision loop and the pruning, for the life of the resource: the loop waits to be woken,
    * decides, and waits out the rest of [[LoopInterval]] since it was woken; the pruning follows
    * `immutableSlots`, a slot past every `Word64` pruning nothing. Neither raises; an exception in
    * either goes to `defect`, which stops the node, as the reference's threads are linked to it
    * (`forkLinkedThread`, `forkLinkedWatcher`).
    */
  def run[F[_], P: Order, E](
      shared: LeiosFetchShared[F, P],
      central: LeiosChainSync.Central[F],
      env: LeiosFetchStaticEnv,
      currentSlot: F[Option[Word64]],
      forecast: F[Word64 => Either[E, BytesSize]],
      immutableSlots: Stream[F, BigInt],
      defect: Throwable => F[Unit]
  )(using F: Async[F]): Resource[F, Unit] =
    val loop: F[Unit] =
      (shared.ready.take *> F.monotonic).flatMap { start =>
        step(shared, env, currentSlot, forecast) *> F.monotonic.flatMap(end =>
          F.sleep((LoopInterval - (end - start)).max(Duration.Zero))
        )
      }
    val pruning: F[Unit] =
      immutableSlots
        .evalMap(slot => Word64.from(slot).fold(_ => F.unit, pruned(shared, central, _)))
        .compile
        .drain
    (
      loop.foreverM[Unit].handleErrorWith(defect).background,
      pruning.handleErrorWith(defect).background
    ).tupled.void
