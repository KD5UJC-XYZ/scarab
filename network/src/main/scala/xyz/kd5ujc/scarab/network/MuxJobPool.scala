// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Async, Deferred, Fiber, Ref, Resource}
import cats.effect.std.Queue
import cats.effect.syntax.all.*
import cats.mtl.Handle
import cats.syntax.all.*

/** Resource-owned mux jobs and their completion queue. Cancellation deregisters a job without
  * calling its error handler or publishing a result. Mirrors Control/Concurrent/JobPool.hs:65–173,
  * 192–222 (ouroboros-network 2fb69829); the mini-protocol and transport groups are Mux.hs:185–187.
  * This primitive does not own connection status or interpret a job's result.
  */
private[network] final class MuxJobPool[F[_], A] private (
    state: Ref[F, MuxJobPool.State[F]],
    completions: Queue[F, A]
)(using F: Async[F]):
  import MuxJobPool.*

  /** The body creates its own typed error scope. A foreign application exception is a defect,
    * including an IOException from application code; transport jobs type OS errors at the bearer.
    * The handler is not called on cancellation, as native forkJob's notAsyncExceptions requires.
    */
  def fork(group: Group)(body: Handle[F, NetworkError] ?=> F[A])(
      failed: NetworkError => F[A]
  ): F[Unit] =
    F.uncancelable { _ =>
      for
        registered <- Deferred[F, Unit]
        id <- state.modify(s => (s.copy(next = s.next + 1), s.next))
        job = F
          .uncancelable { poll =>
            poll(Fibers.scoped[F, NetworkError, A](NetworkError.Defect(_))(body))
              .flatMap(_.fold(failed, F.pure))
              .flatMap(completions.offer)
          }
          .guarantee(registered.get *> state.update(s => s.copy(jobs = s.jobs - id)))
        fiber <- job.start
        _ <- state.update(s => s.copy(jobs = s.jobs.updated(id, (group, fiber))))
        _ <- registered.complete(())
      yield ()
    }

  def next: F[A] = completions.take

  def tryNext: F[Option[A]] = completions.tryTake

  def size(group: Group): F[Int] = state.get.map(_.jobs.values.count(_._1 == group))

  /** Cancellation waits for each selected job's finalizers and deregistration. Other groups keep
    * running, and results that were already published remain in the completion queue.
    */
  def cancel(group: Group): F[Unit] =
    state.get.flatMap(_.jobs.toVector.sortBy(_._1).traverse_ { case (_, (owner, fiber)) =>
      fiber.cancel.whenA(owner == group)
    })

  private def close: F[Unit] =
    state.get.flatMap(
      _.jobs.toVector
        .sortBy { case (id, (group, _)) => (group.ordinal, id) }
        .traverse_(_._2._2.cancel)
    )

private[network] object MuxJobPool:
  enum Group:
    case Transport, MiniProtocol

  private final case class State[F[_]](
      next: BigInt,
      jobs: Map[BigInt, (Group, Fiber[F, Throwable, Unit])]
  )

  def resource[F[_]: Async, A]: Resource[F, MuxJobPool[F, A]] =
    Resource.make(
      (
        Ref.of[F, State[F]](State(BigInt(0), Map.empty)),
        Queue.unbounded[F, A]
      ).mapN(new MuxJobPool(_, _))
    )(_.close)
