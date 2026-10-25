// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Ref, Temporal}
import cats.syntax.all.*
import scala.concurrent.duration.*

/** Notices a follower that has stopped extending its chain while a peer says there is more of it
  * (issue 130: the process was up, idle, and said nothing for hours). The follower reports the
  * blocks it extends ([[extended]]) and the pipelines the tips peers announce ([[announced]]);
  * [[check]], called periodically, reports a stall once when no block has been extended for
  * `threshold` while an announced tip is ahead of ours, and the end of it as soon as a block is
  * extended again.
  *
  * The peers' tip is the highest block number any peer announced: a peer that rolls back to a
  * shorter chain leaves it a block or two too high, which cannot alarm for `threshold` unless the
  * network itself produces no block for that long.
  */
final class StallWatchdog[F[_]: Temporal] private (
    state: Ref[F, StallWatchdog.State],
    threshold: FiniteDuration,
    exitAfter: Option[FiniteDuration],
    report: StallWatchdog.Change => F[Unit]
):
  /** A block was extended. */
  def extended(block: BigInt): F[Unit] =
    Temporal[F].monotonic.flatMap { now =>
      state
        .modify(s => s.progressed(block, now))
        .flatMap(_.traverse_(report))
    }

  /** A peer announced a chain whose tip is `block`. */
  def announced(block: BigInt): F[Unit] =
    state.update(s => s.copy(peer = s.peer.max(block)))

  /** One look at the state: reports the beginning of a stall, and that it has lasted `exitAfter`.
    */
  def check: F[Unit] =
    Temporal[F].monotonic
      .flatMap(now => state.modify(_.checked(now, threshold, exitAfter)))
      .flatMap(_.traverse_(report))

  /** [[check]] every `period`, for ever. */
  def run(period: FiniteDuration): F[Nothing] =
    (Temporal[F].sleep(period) *> check).foreverM[Nothing]

object StallWatchdog:
  /** What the watchdog tells: a stall began, or it ended. */
  enum Change:
    /** No block was extended for `idle`; we are at `ours`, a peer announced `peers`. */
    case Began(idle: FiniteDuration, ours: BigInt, peers: BigInt)
    case Ended

    /** The stall has lasted `exitAfter`: the node should end so that it restarts from its newest
      * snapshot. Reported once.
      */
    case ExitDue(idle: FiniteDuration, ours: BigInt, peers: BigInt)

  /** Our tip (`ours`), the highest announced tip (`peer`), when a block was last extended (or the
    * watchdog started) and whether a stall is being reported.
    */
  final case class State(
      ours: BigInt,
      peer: BigInt,
      progressAt: FiniteDuration,
      stalled: Boolean,
      exiting: Boolean = false
  ):
    private[StallWatchdog] def progressed(
        block: BigInt,
        now: FiniteDuration
    ): (State, Option[Change]) =
      (
        State(block, peer.max(block), now, stalled = false, exiting = false),
        Option.when(stalled)(Change.Ended)
      )

    /** The decision: a stall begins when a peer is ahead and no block was extended for `threshold`;
      * it ends the node when it has gone on for `exitAfter` (when given), whatever the warning did.
      * Nothing is decided while no peer is ahead of us, so a node that is at the tip of every peer,
      * or has no peer, is never stopped.
      */
    def checked(
        now: FiniteDuration,
        threshold: FiniteDuration,
        exitAfter: Option[FiniteDuration]
    ): (State, List[Change]) =
      val idle = now - progressAt
      if peer <= ours then (this, Nil)
      else
        val began = Option.when(!stalled && idle >= threshold)(Change.Began(idle, ours, peer))
        val due =
          Option.when(!exiting && exitAfter.exists(idle >= _))(Change.ExitDue(idle, ours, peer))
        (
          copy(stalled = stalled || began.isDefined, exiting = exiting || due.isDefined),
          began.toList ++ due.toList
        )

  /** Watching from now, with the chain at block `ours`. */
  def create[F[_]: Temporal](
      ours: BigInt,
      threshold: FiniteDuration,
      exitAfter: Option[FiniteDuration],
      report: Change => F[Unit]
  ): F[StallWatchdog[F]] =
    Temporal[F].monotonic.flatMap(now =>
      Ref
        .of[F, State](State(ours, ours, now, stalled = false))
        .map(new StallWatchdog(_, threshold, exitAfter, report))
    )
