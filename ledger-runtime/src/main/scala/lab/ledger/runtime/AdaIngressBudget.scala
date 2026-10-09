// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.{Async, Resource}
import cats.effect.std.Semaphore
import cats.syntax.all.*
import java.util.concurrent.atomic.AtomicBoolean

/** Acquire request before allocating/reading HTTP body; acquire validation afterwards. Resource
  * finalizers release only permits, never an already-published pool entry.
  */
final class AdaIngressBudget[F[_]] private (
    requests: Semaphore[F],
    validations: Semaphore[F]
)(using F: Async[F]):
  final class Request private[AdaIngressBudget] ():
    private val used = new AtomicBoolean(false)
    private[AdaIngressBudget] def close(): Unit = used.set(true)
    def validate[A](work: F[A]): F[A] =
      F.delay(used.compareAndSet(false, true)).flatMap { fresh =>
        if fresh then validations.permit.use(_ => work)
        else F.raiseError(new IllegalStateException("request validation already used or released"))
      }
  def request: Resource[F, Option[Request]] =
    Resource
      .make(requests.tryAcquire)(held => if held then requests.release else F.unit)
      .flatMap(held =>
        Resource.make(F.delay(Option.when(held)(new Request)))(request =>
          F.delay(request.foreach(_.close()))
        )
      )

object AdaIngressBudget:
  final case class Limits(requests: Int = 8, validations: Int = 2):
    require(requests > 0 && requests <= 8)
    require(validations > 0 && validations <= 2 && validations <= requests)
    require(requests - validations <= 6)
  def create[F[_]: Async](limits: Limits = Limits()): F[AdaIngressBudget[F]] =
    (Semaphore[F](limits.requests.toLong), Semaphore[F](limits.validations.toLong))
      .mapN(new AdaIngressBudget(_, _))
