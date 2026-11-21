// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Applicative, Functor}
import cats.effect.{Fiber, MonadCancelThrow}
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
    * `fallback`, a value, is the completion of last resort. The reference's group and thread label
    * are not kept: the governor gives every job the group `()`, and a fiber has no label.
    */
  final case class Job[F[_], C](
      action: F[C],
      handler: Throwable => F[C],
      fallback: Throwable => C
  ):
    /** The job whose completion is `f` of this one's, whichever of its parts made it. */
    def map[D](f: C => D)(using Functor[F]): Job[F, D] =
      Job(action.map(f), cause => handler(cause).map(f), cause => f(fallback(cause)))

    /** Run the job in the fiber at hand, and give its completion to `complete` (`io`, `JobPool.hs`
      * 104-113 at c45735a5). As there, the action and the handler can be cancelled (the action runs
      * under `restore`; the handler runs masked, where its waits are interruptible), and the
      * handoff of the completion cannot. Used by the pool, and by a job that runs another next in
      * its own fiber.
      */
    def run(complete: C => F[Unit])(using F: MonadCancelThrow[F]): F[Unit] =
      F.uncancelable(poll =>
        poll(action)
          .handleErrorWith(cause => poll(handler(cause)).handleError(_ => fallback(cause)))
          .flatMap(complete)
      )

  object Job:
    /** A job whose handler is a value, so it cannot raise. */
    def pure[F[_]: Applicative, C](action: F[C], handler: Throwable => C): Job[F, C] =
      Job(action, cause => handler(cause).pure[F], handler)

  def apply[F[_]: MonadCancelThrow, C](
      supervisor: Supervisor[F],
      complete: C => F[Unit]
  ): JobPool[F, C] =
    new JobPool(supervisor, complete)
