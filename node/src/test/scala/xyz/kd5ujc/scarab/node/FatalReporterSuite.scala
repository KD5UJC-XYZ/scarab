// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref}
import munit.CatsEffectSuite

class FatalReporterSuite extends CatsEffectSuite:
  private val first = Fatal.Defect(new IllegalStateException("first"))
  private val second = Fatal.Defect(new IllegalStateException("second"))

  test("racing client and mux terminal failures report once, before waking shutdown") {
    for
      logging <- Deferred[IO, Unit]
      finishLog <- Deferred[IO, Unit]
      halted <- Deferred[IO, Fatal]
      logged <- Ref.of[IO, Vector[Fatal]](Vector.empty)
      reporter <- FatalReporter.create[IO](
        fault => logged.update(_ :+ fault) *> logging.complete(()).void *> finishLog.get,
        fault => halted.complete(fault).void
      )
      client <- reporter.report(first).start
      _ <- logging.get
      mux <- reporter.report(second).start
      _ <- mux.joinWithNever
      before <- halted.tryGet
      _ <- finishLog.complete(())
      _ <- client.joinWithNever
      winner <- halted.get
      events <- logged.get
    yield
      assertEquals(before, None)
      assertEquals(winner, first)
      assertEquals(events, Vector(first))
  }

  test("a logger failure still signals the claimed terminal failure") {
    val broken = new IllegalArgumentException("logger failed")
    for
      halted <- Deferred[IO, Fatal]
      reporter <- FatalReporter.create[IO](_ => IO.raiseError(broken), f => halted.complete(f).void)
      result <- reporter.report(first).attempt
      winner <- halted.get
      _ <- reporter.report(second)
    yield
      assertEquals(result, Left(broken))
      assertEquals(winner, first)
  }

  test("cancellation during logging cannot leave a claimed failure without shutdown") {
    for
      logging <- Deferred[IO, Unit]
      halted <- Deferred[IO, Fatal]
      reporter <- FatalReporter.create[IO](
        _ => logging.complete(()).void *> IO.never,
        f => halted.complete(f).void
      )
      fiber <- reporter.report(first).start
      _ <- logging.get
      _ <- fiber.cancel
      winner <- halted.get
      _ <- reporter.report(second)
    yield assertEquals(winner, first)
  }
