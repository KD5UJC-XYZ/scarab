// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Async, Deferred, Ref, Resource}
import cats.effect.std.{Mutex, Queue, Semaphore}
import cats.syntax.all.*
import scala.concurrent.duration.FiniteDuration

/** The native ChainDB queue has ten waiting messages (Args.hs:122), with a separate running
  * message. Receipt owns only bounded enqueue; a node-owned worker owns processing (ChainSel.hs:
  * 270–300). Reserving capacity remains cancelable, while publication and enqueue cannot separate.
  */
private[node] final class BlockAdmissions[F[_]: Async] private (
    queue: Queue[F, BlockAdmissions.Work[F]],
    capacity: Semaphore[F],
    publishLock: Mutex[F],
    enabled: Ref[F, Boolean],
    stopped: Deferred[F, Unit],
    admitted: Ref[F, BlockPool.Admission => F[Unit]],
    working: Ref[F, Option[BlockAdmissions.Started]]
):
  import BlockAdmissions.*
  private val F = Async[F]

  def active: F[Boolean] =
    (enabled.get, stopped.tryGet).mapN((running, closed) => running || closed.isDefined)

  def close: F[Unit] =
    stopped.complete(()).void *> enabled.set(false) *>
      publishLock.lock.surround(
        F.tailRecM(())(_ =>
          queue.tryTake.flatMap {
            case None                       => F.pure(Right(()))
            case Some(Work.Add(_))          => capacity.release.as(Left(()))
            case Some(Work.Reprocess(done)) => capacity.release *> done.complete(()).as(Left(()))
          }
        )
      )

  def resource(onAdmission: BlockPool.Admission => F[Unit]): Resource[F, Unit] =
    Resource.make(admitted.set(onAdmission) *> enabled.set(true))(_ => close)

  private def enqueue(publish: F[Option[Work[F]]]): F[Unit] =
    F.uncancelable { poll =>
      poll(F.race(stopped.get, capacity.acquire)).flatMap {
        // Closing the owning ChainDB cancels blocked submitters; their connection resource owns
        // cleanup. This is cancellation, never an unavailable peer or a forged semantic verdict.
        case Left(_) => poll(F.canceled *> F.never[Unit])
        case Right(_) =>
          publishLock.lock
            .surround(stopped.tryGet.flatMap {
              case Some(_) => capacity.release *> poll(F.canceled *> F.never[Unit])
              case None =>
                publish.flatMap {
                  case Some(work) => queue.offer(work)
                  case None       => capacity.release
                }
            })
            .onError(_ => capacity.release)
      }
    }

  def submit(publish: F[Option[BlockPool.Admission]]): F[Unit] =
    enqueue(
      publish.flatMap(
        _.traverse(taken => admitted.get.flatMap(_(taken)).as(Work.Add[F](taken): Work[F]))
      )
    )

  def reprocess: F[Unit] =
    Deferred[F, Unit].flatMap { complete =>
      enqueue(F.pure(Some(Work.Reprocess(complete)))) *> complete.get
    }

  /** Three non-blocking reads for a stall report: the permits taken (a submitter holds one from
    * reservation until the worker takes its work), the work waiting in the queue and the work the
    * worker is on. `next` releases the permit when the worker takes a work, so a worker stuck
    * inside one looks idle in the first two.
    */
  def occupancy: F[Occupancy] =
    (capacity.available, queue.size, working.get, F.monotonic).mapN { (free, queued, on, now) =>
      Occupancy(
        Capacity,
        (Capacity - free).toInt.max(0),
        queued,
        on.map(started => Worker(started.work, now - started.at))
      )
    }

  /** Run `body`, the processing of one work of kind `work` that [[next]] gave, noting it for
    * [[occupancy]] until it ends.
    */
  def processing[A](work: String)(body: F[A]): F[A] =
    F.monotonic.flatMap(at => working.set(Some(Started(work, at)))) *>
      F.guarantee(body, working.set(None))

  def next: F[Work[F]] = queue.take.flatTap(_ => capacity.release)

object BlockAdmissions:
  /** The ChainDB queue's waiting messages (Args.hs:122). */
  private[node] val Capacity = 10

  /** `held` of `capacity` permits are taken; `queued` works wait for the worker, which is on
    * `worker` (none while idle).
    */
  private[node] final case class Occupancy(
      capacity: Int,
      held: Int,
      queued: Int,
      worker: Option[Worker] = None
  )

  /** The kind of work the worker is on (`add` or `reprocess`) and for how long. */
  private[node] final case class Worker(work: String, running: FiniteDuration)

  private final case class Started(work: String, at: FiniteDuration)

  private[node] enum Work[F[_]]:
    case Add(taken: BlockPool.Admission)
    case Reprocess(complete: Deferred[F, Unit])

  private[node] def create[F[_]: Async]: F[BlockAdmissions[F]] =
    (
      Queue.unbounded[F, Work[F]],
      Semaphore[F](Capacity.toLong),
      Mutex[F],
      Ref.of[F, Boolean](false),
      Deferred[F, Unit],
      Ref.of[F, BlockPool.Admission => F[Unit]](_ => Async[F].unit),
      Ref.of[F, Option[Started]](None)
    )
      .mapN(new BlockAdmissions(_, _, _, _, _, _, _))
