// SPDX-License-Identifier: Apache-2.0
package lab.ledger.runtime

import cats.effect.{Deferred, IO}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import scala.concurrent.duration.*

class AdaIngressBudgetSuite extends munit.FunSuite:
  test("request permit precedes body work, overflow is immediate, release permits reuse") {
    val program = for
      budget <- AdaIngressBudget.create[IO]()
      slots <- Vector.fill(8)(budget.request.allocated).sequence
      _ <- IO(assert(slots.forall(_._1.nonEmpty)))
      overflow <- budget.request.use(IO.pure)
      _ <- IO(assertEquals(overflow, None))
      _ <- slots.traverse_(_._2)
      next <- budget.request.use(IO.pure)
      _ <- IO(assert(next.nonEmpty))
      invalid <- next.get.validate(IO.unit).attempt
      _ <- IO(assert(invalid.isLeft))
    yield ()
    program.timeout(10.seconds).unsafeToFuture()
  }
  test("two jobs, six waiters; cancellation releases requests and validation slots") {
    val program = for
      budget <- AdaIngressBudget.create[IO]()
      started <- Deferred[IO, Unit]
      count <- cats.effect.Ref.of[IO, Int](0)
      work = count
        .updateAndGet(_ + 1)
        .flatMap(n => if n == 2 then started.complete(()).void else IO.unit) *> IO.never[Unit]
      fibers <- Vector
        .fill(8)(budget.request.use {
          case Some(request) => request.validate(work)
          case None          => IO.raiseError(new Exception("unexpected capacity"))
        }.start)
        .sequence
      _ <- started.get
      n <- count.get
      _ <- IO(assertEquals(n, 2))
      _ <- fibers.traverse_(_.cancel)
      result <- budget.request.use(_.get.validate(IO.pure(42)))
      _ <- IO(assertEquals(result, 42))
    yield ()
    program.timeout(10.seconds).unsafeToFuture()
  }
  test("one request cannot create unbounded concurrent validation waiters") {
    val program = for
      budget <- AdaIngressBudget.create[IO]()
      _ <- budget.request.use { request =>
        for
          _ <- request.get.validate(IO.unit)
          second <- request.get.validate(IO.unit).attempt
          _ <- IO(assert(second.isLeft))
        yield ()
      }
    yield ()
    intercept[IllegalArgumentException](AdaIngressBudget.Limits(requests = 8, validations = 1))
    intercept[IllegalArgumentException](AdaIngressBudget.Limits(requests = 9))
    program.timeout(10.seconds).unsafeToFuture()
  }
