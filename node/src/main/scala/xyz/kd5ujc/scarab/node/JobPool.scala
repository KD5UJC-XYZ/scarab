// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Applicative, Functor}
import cats.effect.{Fiber, MonadCancelThrow, Poll}
import cats.effect.std.Supervisor
import cats.syntax.all.*

/** The governor's jobs, each run in a fiber of a supervisor, and each ending by handing its
  * completion to its owner: the value its action returns, or, if the action raises, the value its
  * handler makes of the exception (the reference's `JobPool`, network-mux `c45735a5`
  * `Control/Concurrent/JobPool.hs`). A job is forked only with its handler, and the pool keeps its
  * supervisor to itself, so code that holds a pool and not the supervisor cannot start a fiber
  * without a handler for what it raises (FIB-2).
  *
  * Cancellation is not an exception here: a cancelled job hands nothing over and its handler does
  * not run, as `forkJob'` gives its handler no asynchronous exception (`notAsyncExceptions`,
  * 98-102).
  */
final class JobPool[F[_]: MonadCancelThrow, C] private (
    supervisor: Supervisor[F],
    complete: C => F[Unit]
):
  /** Fork `job`: its action under its handler, and the result to the completion (`forkJob'`,
    * `JobPool.hs` 85-113 at c45735a5: `handleJust notAsyncExceptions handler` 111-112, then
    * `writeTQueue completionQueue` 113). The fiber is returned for an owner that cancels it.
    */
  def forkJob(job: JobPool.Job[F, C]): F[Fiber[F, Throwable, Unit]] =
    supervisor.supervise(job.run(complete))

  /** The same supervisor, for jobs whose completion goes to `to`: a job's own requests hand their
    * results to the job that waits for them, as the reference's requests, each an `async`, hand
    * theirs to `waitAllCatchOrTimeout`.
    */
  def completingTo[D](to: D => F[Unit]): JobPool[F, D] = new JobPool(supervisor, to)

object JobPool:
  /** A job, the handler of what it raises, and the completion it ends with if the handler raises
    * too (`data Job`, `JobPool.hs` 43-49 at c45735a5). A reference handler builds a completion and
    * waits at most on STM, which does not raise; a handler here runs effects that might, so its
    * `fallback`, a value, is the completion of last resort. The handler runs masked, as the
    * reference's does, and is given the `Poll` that lets it be cancelled where it waits, as a
    * masked wait is interruptible there. The reference's group and thread label are not kept: the
    * governor gives every job the group `()`, and a fiber has no label.
    */
  final case class Job[F[_], C](
      action: F[C],
      handler: (Throwable, Poll[F]) => F[C],
      fallback: Throwable => C
  ):
    /** The job whose completion is `f` of this one's, whichever of its parts made it. */
    def map[D](f: C => D)(using Functor[F]): Job[F, D] =
      Job(
        action.map(f),
        (cause, poll) => handler(cause, poll).map(f),
        cause => f(fallback(cause))
      )

    /** Run the job in the fiber at hand, and give its completion to `complete` (`io`, `JobPool.hs`
      * 104-113 at c45735a5). As there, the action can be cancelled (it runs under `restore`), the
      * handler only where it waits, and the handoff not at all. The governor's sinks, an unbounded
      * queue and `Deferred` s, do not raise, as `writeTQueue` does not; a handoff that raises all
      * the same is tried once more, then gives way to the fallback for its exception, so that the
      * job's owner hears of it.
      */
    def run(complete: C => F[Unit])(using F: MonadCancelThrow[F]): F[Unit] =
      F.uncancelable(poll =>
        poll(action)
          .handleErrorWith(cause => handler(cause, poll).handleError(_ => fallback(cause)))
          .flatMap(completion =>
            complete(completion)
              .handleErrorWith(_ => complete(completion))
              .handleErrorWith(cause => complete(fallback(cause)))
          )
      )

  object Job:
    /** A job whose handler is a value, so it neither raises nor waits. */
    def pure[F[_]: Applicative, C](action: F[C], handler: Throwable => C): Job[F, C] =
      Job(action, (cause, _) => handler(cause).pure[F], handler)

  def apply[F[_]: MonadCancelThrow, C](
      supervisor: Supervisor[F],
      complete: C => F[Unit]
  ): JobPool[F, C] =
    new JobPool(supervisor, complete)
