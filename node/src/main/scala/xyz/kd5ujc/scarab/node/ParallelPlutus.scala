// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.Async
import cats.effect.std.Semaphore
import cats.syntax.all.*
import cats.effect.syntax.all.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.rules.EraBlock

/** One node-wide admission bound, shared by all callers. Only immutable evaluator requests enter
  * workers; ledger changes and observations land in transaction order in the caller's fiber.
  */
final class ParallelPlutus[F[_]: Async] private (
    workers: ParallelPlutus.Workers,
    permits: Semaphore[F]
):
  private def serial(body: EraBlock.Body): F[EraBlock.Outcome] =
    if body.hasScripts then permits.permit.use(_ => Async[F].blocking(body.serial))
    else Async[F].delay(body.serial)

  def apply(body: EraBlock.Body): F[EraBlock.Outcome] =
    if workers.value == 1 || !body.worthwhile then serial(body)
    else
      Async[F].delay(body.prepare).attempt.flatMap {
        // Preparation is speculative. A defect in a later, unreachable ordinary rule must not
        // overtake the serial path's earlier script refusal (or its original defect).
        case Left(_) => serial(body)
        case Right(plan) =>
          plan.batches
            .foldLeftM(Vector.empty[xyz.kd5ujc.scarab.ledger.Phase2.Completed]) { (done, batch) =>
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
