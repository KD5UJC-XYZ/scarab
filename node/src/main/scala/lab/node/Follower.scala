// SPDX-License-Identifier: Apache-2.0
package lab.node

import cats.Monad
import cats.effect.{Resource, Temporal}
import cats.mtl.{Handle, Raise, Stateful}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import lab.cbor.Bytes
import lab.network.{ChainSync, Era}
import scala.concurrent.duration.*

/** The node's chain-extension rule: the state after `header`/`block`, or a [[ValidationError]]. */
trait BlockRules[F[_], S]:
  def apply(state: S, header: Header, block: Bytes): F[S]

/** Observable follower milestones. */
enum FollowerEvent:
  case Connected(intersection: ChainSync.Point)
  case Extended(block: BlockRef, epoch: BigInt, peerTip: ChainSync.Tip, era: Era)
  case RolledBack(from: ChainSync.Point, to: ChainSync.Point, depth: Int)
  case EpochEntered(epoch: BigInt, first: BlockRef)
  case Immutable(block: BlockRef)
  case Reconnecting(reason: PeerError, attempt: Int)

  /** The first block of a new era was applied (`from` is empty for the chain's first era). */
  case EraEntered(from: Option[Era], to: Era, first: BlockRef, epoch: BigInt)

  /** An epoch of bootstrap-mode headers is complete: how each one was established. */
  case EpochValidated(
      epoch: BigInt,
      era: Era,
      threshold: Long,
      overlay: Long,
      unchecked: Long
  )

  /** A Byron epoch is complete: its boundary and main blocks and how each main signature stood. */
  case ByronEpochValidated(
      epoch: BigInt,
      boundary: Long,
      verified: Long,
      unchecked: Long
  )

trait FollowerEvents[F[_]]:
  def emit(event: FollowerEvent): F[Unit]

/** Follows one upstream peer: intersect, then apply roll-forward/roll-backward instructions to the
  * volatile chain through [[BlockRules]]. Chain state lives in `Stateful`; rule and chain failures
  * are raised as typed errors and are fatal. Peer failures are handled by [[Follower.supervise]].
  */
final class Follower[F[_]: Monad, S](
    rules: BlockRules[F, S],
    events: FollowerEvents[F],
    geometry: EpochGeometry,
    /** Extra events derived from a state transition (era changes, epoch summaries). */
    observe: (S, S, BlockRef) => List[FollowerEvent] = (_: S, _: S, _: BlockRef) => Nil
)(using chain: Stateful[F, VolatileChain[S]], raise: Raise[F, ChainError]):

  /** Run one peer session until the peer fails (a raised [[PeerError]]) or forever. */
  def session(peer: Peer[F]): F[Unit] =
    for
      current <- chain.get
      found <- peer.intersect(current.candidates)
      point <- found.fold(ChainError.NoIntersection.raise[F, ChainSync.Point])(_.pure[F])
      _ <- rollbackTo(point, reportDepth = false)
      _ <- events.emit(FollowerEvent.Connected(point))
      _ <- Monad[F].foreverM(peer.next.flatMap(step(peer, _)))
    yield ()

  private def step(peer: Peer[F], event: SyncEvent): F[Unit] = event match
    case SyncEvent.RollBackward(point, _) => rollbackTo(point, reportDepth = true)
    case SyncEvent.RollForward(header, tip) =>
      for
        block <- peer.fetch(header)
        before <- chain.get
        next <- rules(before.state, header, block)
        extended <- before
          .extend(header, next)
          .fold(_.raise[F, (VolatileChain[S], Vector[BlockRef])], _.pure[F])
        (after, immutable) = extended
        _ <- chain.set(after)
        ref = BlockRef(header.slot, header.hash, header.blockNo, header.isBoundary)
        epoch = geometry.epochOf(header.slot)
        _ <- events.emit(FollowerEvent.Extended(ref, epoch, tip, header.era))
        enteredEpoch = before.tipRef.forall(t => geometry.epochOf(t.slot) < epoch)
        _ <- events.emit(FollowerEvent.EpochEntered(epoch, ref)).whenA(enteredEpoch)
        _ <- observe(before.state, next, ref).traverse_(events.emit)
        _ <- immutable.traverse_(b => events.emit(FollowerEvent.Immutable(b)))
      yield ()

  private def rollbackTo(point: ChainSync.Point, reportDepth: Boolean): F[Unit] =
    chain.get.flatMap { current =>
      current
        .rollback(point)
        .fold(
          _.raise[F, Unit],
          rolled =>
            val depth = current.length - rolled.length
            chain.set(rolled) *>
              events
                .emit(FollowerEvent.RolledBack(current.tip, point, depth))
                .whenA(reportDepth && depth > 0)
        )
    }

object Follower:
  final case class Retry(maxAttempts: Int, delay: FiniteDuration)

  /** Run sessions from `connect`, reconnecting after each [[PeerError]] up to `retry.maxAttempts`
    * consecutive failures. A session that applied at least one block resets the count.
    */
  def supervise[F[_]: Temporal, S](
      follower: Follower[F, S],
      connect: Resource[F, Peer[F]],
      events: FollowerEvents[F],
      retry: Retry
  )(using chain: Stateful[F, VolatileChain[S]], handle: Handle[F, PeerError]): F[Unit] =
    def loop(attempt: Int): F[Unit] =
      chain.get.map(_.tip).flatMap { before =>
        connect.use(follower.session).handleWith[PeerError] { error =>
          chain.get.map(_.tip).flatMap { after =>
            val next = if after != before then 1 else attempt + 1
            if next > retry.maxAttempts then error.raise[F, Unit]
            else
              events.emit(FollowerEvent.Reconnecting(error, next)) *> Temporal[F].sleep(
                retry.delay
              ) *> loop(next)
          }
        }
      }
    loop(1)
