// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cats.effect.syntax.all.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.Phase2

/** One node-wide admission bound, shared by all callers. Only immutable evaluator requests enter
  * workers; ledger changes and observations land in transaction order in the caller's fiber. Any
  * body program ([[Phase2.Program]]) runs here: the flat rules' (`EraBlock.Body`) and an era block
  * run's (`ConwayBlockRun.Body`, `DijkstraBlockRun.Body`).
  */
final class ParallelPlutus[F[_]: Async] private (
    workers: ParallelPlutus.Workers,
    permits: Semaphore[F]
):
  private def serial[A](body: Phase2.Program[A]): F[A] =
    if body.hasScripts then permits.permit.use(_ => Async[F].blocking(body.serial))
    else Async[F].delay(body.serial)

  def apply[A](body: Phase2.Program[A]): F[A] =
    if workers.value == 1 || !body.worthwhile then serial(body)
    else
      Async[F].delay(body.prepare).flatMap { plan =>
        plan.batches
          .foldLeftM(Vector.empty[plan.Completed]) { (done, batch) =>
            batch
              .parTraverseN(workers.value) { job =>
                // blocking, rather than interruptible: cancellation joins the VM before giving
                // back the permit, so a cancelled validation cannot leave unbounded machines.
                permits.permit.use(_ => Async[F].blocking(plan.execute(job)))
              }
              .map(done ++ _)
          }
          .flatMap { completed =>
            Async[F].delay(plan.resolve(completed)).flatMap {
              case Left(defect)   => Async[F].raiseError(defect.cause)
              case Right(outcome) => outcome.pure[F]
            }
          }
      }

object ParallelPlutus:
  import Workers.*
  final case class InvalidWorkers(value: Int) extends DomainError

  opaque type Workers = Int
  object Workers:
    val sequential: Workers = 1
    def default: Workers = Runtime.getRuntime.availableProcessors().max(1).min(4)
    def from(value: Int): Either[InvalidWorkers, Workers] =
      Either.cond(value > 0, value, InvalidWorkers(value))
    extension (workers: Workers) def value: Int = workers

  def create[F[_]: Async](workers: Workers): F[ParallelPlutus[F]] =
    Semaphore[F](workers.value.toLong).map(new ParallelPlutus(workers, _))
