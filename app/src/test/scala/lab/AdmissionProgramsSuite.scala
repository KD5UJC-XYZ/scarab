// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{Deferred, IO, Outcome, Ref}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import lab.submission.*
import AdmissionPrograms.*
import AdmissionPrograms.syntax.*
import scala.compiletime.testing.{typeCheckErrors, typeChecks}

class AdmissionProgramsSuite extends munit.FunSuite:
  private def withView[A](use: AdmissionView => IO[A]): IO[A] =
    SubmissionOwner.resource[IO](EphemeralStreamingFixture.runtime).use(_.current.flatMap(use))

  private val observer = new AdmissionStateObserver[IO]:
    def changed(value: AdmissionStateChange) = IO.unit
    def closed = IO.unit

  test("read capability permits observation but cannot supply commit authority") {
    assert(
      typeChecks(
        """import lab.AdmissionPrograms; def observe[F[_]](using AdmissionPrograms.Read[F]) = AdmissionPrograms.current[F]"""
      )
    )
    val errors = typeCheckErrors(
      """import lab.AdmissionPrograms; import lab.AdmissionPrograms.syntax.*; import lab.submission.StatePin; def wrong[F[_],A](pin: StatePin, action: F[A])(using AdmissionPrograms.Read[F]) = pin.commitIfCurrent(action)"""
    )
    assert(errors.nonEmpty)
    assert(errors.exists(_.message.contains("Fence")))
    assert(
      typeCheckErrors(
        """import lab.AdmissionPrograms; import lab.submission.StatePin; def forge[F[_]](pin: StatePin): AdmissionPrograms.Fence[F] = pin"""
      ).nonEmpty
    )
  }

  test("guarded program preserves legacy trace and exactly four maximum attempts") {
    withView { view =>
      def run(stale: Int, modern: Boolean): IO[(Guarded[Int], Vector[String])] = for
        trace <- Ref.of[IO, Vector[String]](Vector.empty)
        attempts <- Ref.of[IO, Int](0)
        owner = new AdmissionState[IO]:
          def current = trace.update(_ :+ "view").as(view)
          def withCurrent[A](pin: StatePin)(action: IO[A]): IO[Either[StatePin, A]] =
            trace.update(_ :+ "fence") *> attempts.getAndUpdate(_ + 1).flatMap { n =>
              if n < stale then IO.pure(Left(view.pin)) else action.map(Right(_))
            }
        action = trace.update(_ :+ "action").as(7)
        result <- {
          val capabilities = fromOwner(owner)
          given Read[IO] = capabilities
          given Fence[IO] = capabilities
          def legacy(retries: Int): IO[Guarded[Int]] =
            owner.current.flatMap(v => owner.withCurrent(v.pin)(action)).flatMap {
              case Right(value)           => IO.pure(Guarded.Read(value))
              case Left(_) if retries > 0 => legacy(retries - 1)
              case Left(pin)              => IO.pure(Guarded.Exhausted(pin))
            }
          if modern then guarded(action) else legacy(3)
        }
        events <- trace.get
      yield (result, events)
      (0 to 5).toList.traverse_ { stale =>
        (run(stale, false), run(stale, true)).tupled.flatMap { (old, current) =>
          IO {
            assertEquals(current, old)
            assertEquals(current._2.count(_ == "fence"), math.min(stale + 1, 4))
            assertEquals(current._2.count(_ == "action"), if stale < 4 then 1 else 0)
          }
        }
      }
    }.unsafeToFuture()
  }

  test("failed action retains the original error and is never retried") {
    withView { view =>
      val error = new IllegalStateException("test sentinel")
      for
        calls <- Ref.of[IO, Int](0)
        owner = new AdmissionState[IO]:
          def current = IO.pure(view)
          def withCurrent[A](pin: StatePin)(action: IO[A]) =
            calls.update(_ + 1) *> action.map(Right(_))
        result <- {
          val capabilities = fromOwner(owner)
          given Read[IO] = capabilities
          given Fence[IO] = capabilities
          guarded(IO.raiseError[Int](error)).attempt
        }
        count <- calls.get
        _ = assert(result.swap.toOption.exists(_ eq error))
        _ = assertEquals(count, 1)
      yield ()
    }.unsafeToFuture()
  }

  test("cancellation during observation never reaches the fence") {
    withView { view =>
      for
        entered <- Deferred[IO, Unit]
        calls <- Ref.of[IO, Int](0)
        result <- {
          given Read[IO] with
            def current = entered.complete(()).void *> IO.never[AdmissionView]
          given Fence[IO] with
            def commit[A](pin: StatePin)(action: IO[A]) =
              calls.update(_ + 1) *> action.map(Fenced.Applied(_))
          for
            fiber <- guarded(IO.pure(1)).start
            _ <- entered.get
            _ <- fiber.cancel
            outcome <- fiber.join
          yield outcome
        }
        count <- calls.get
        _ = assert(result.isCanceled)
        _ = assertEquals(count, 0)
      yield ()
    }.unsafeToFuture()
  }

  test("owner interpreter preserves masked commit completion before cancellation returns") {
    SubmissionOwner
      .resource[IO](EphemeralStreamingFixture.runtime)
      .use { owner =>
        val capabilities = fromOwner(owner)
        given Fence[IO] = capabilities
        for
          _ <- owner.attach(observer)
          view <- owner.current
          entered <- Deferred[IO, Unit]
          release <- Deferred[IO, Unit]
          canceled <- Deferred[IO, Unit]
          committed <- Ref.of[IO, Boolean](false)
          fiber <- view.pin
            .commitIfCurrent(entered.complete(()).void *> release.get *> committed.set(true))
            .start
          _ <- entered.get
          canceler <- (fiber.cancel *> canceled.complete(())).start
          _ <- IO.cede
          pending <- canceled.tryGet
          _ = assertEquals(pending, None)
          _ <- release.complete(())
          _ <- canceler.joinWithNever
          done <- committed.get
          _ = assert(done)
          stillCurrent <- owner.current
          _ = assertEquals(stillCurrent.pin, view.pin)
        yield ()
      }
      .unsafeToFuture()
  }

  test("stale full pin never evaluates action through concrete owner interpreter") {
    SubmissionOwner
      .resource[IO](EphemeralStreamingFixture.runtime)
      .use { owner =>
        val capabilities = fromOwner(owner)
        given Fence[IO] = capabilities
        for
          _ <- owner.attach(observer)
          view <- owner.current
          p = view.pin
          wrong = StatePin
            .checked(
              p.ownerId,
              p.generation + 1,
              p.point,
              p.coherentStateId,
              p.ledgerStateId,
              p.environmentId,
              p.validationSlot,
              p.profileId
            )
            .toOption
            .get
          result <- wrong.commitIfCurrent(
            IO.raiseError[Int](new AssertionError("stale action evaluated"))
          )
          _ = assertEquals(result, Fenced.Stale(p))
        yield ()
      }
      .unsafeToFuture()
  }
