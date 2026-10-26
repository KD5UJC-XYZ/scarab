// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network

import cats.effect.{Deferred, IO, Ref}
import cats.mtl.syntax.all.*
import cats.syntax.all.*
import MuxJobPool.Group

class MuxJobPoolSuite extends FailsOnUncaught:
  private val error = NetworkError.BearerClosed(NetworkError.Closure.PeerClosed)

  test("a job returns its typed error through its own scope and publishes once") {
    MuxJobPool.resource[IO, Either[NetworkError, Int]].use { pool =>
      for
        _ <- pool.fork(Group.MiniProtocol)(error.raise[IO, Either[NetworkError, Int]])(e =>
          IO.pure(Left(e))
        )
        result <- pool.next
        _ <- pool.cancel(Group.MiniProtocol)
        duplicate <- pool.tryNext
        size <- pool.size(Group.MiniProtocol)
      yield
        assertEquals(result, Left(error))
        assertEquals(duplicate, None)
        assertEquals(size, 0)
    }
  }

  test("a foreign application IOException retains the original defect instead of peer closure") {
    val cause = new java.io.IOException("application defect")
    MuxJobPool.resource[IO, Either[NetworkError, Unit]].use { pool =>
      for
        _ <- pool.fork(Group.MiniProtocol)(IO.raiseError(cause))(e => IO.pure(Left(e)))
        result <- pool.next
      yield assertEquals(result, Left(NetworkError.Defect(cause)))
    }
  }

  test("group cancellation joins cleanup without error handling or a completion") {
    MuxJobPool.resource[IO, Unit].use { pool =>
      for
        protocolStarted <- Deferred[IO, Unit]
        transportStarted <- Deferred[IO, Unit]
        protocolEnded <- Deferred[IO, Unit]
        transportEnded <- Deferred[IO, Unit]
        handled <- Ref.of[IO, Int](0)
        _ <- pool.fork(Group.MiniProtocol)(
          (protocolStarted.complete(()) *> IO.never[Unit])
            .guarantee(protocolEnded.complete(()).void)
        )(_ => handled.update(_ + 1))
        _ <- pool.fork(Group.Transport)(
          (transportStarted.complete(()) *> IO.never[Unit])
            .guarantee(transportEnded.complete(()).void)
        )(_ => handled.update(_ + 1))
        _ <- protocolStarted.get *> transportStarted.get
        _ <- pool.cancel(Group.MiniProtocol)
        _ <- protocolEnded.get
        remaining <- pool.size(Group.Transport)
        _ <- pool.cancel(Group.Transport)
        _ <- transportEnded.get
        errors <- handled.get
        completion <- pool.tryNext
      yield
        assertEquals(remaining, 1)
        assertEquals(errors, 0)
        assertEquals(completion, None)
    }
  }

  test("resource release joins every group and immediate completions cannot leak registration") {
    for
      cleaned <- Ref.of[IO, Set[Group]](Set.empty)
      _ <- MuxJobPool.resource[IO, Int].use { pool =>
        for
          _ <- (0 until 100).toVector
            .traverse_(i => pool.fork(Group.MiniProtocol)(IO.pure(i))(_ => IO.pure(-1)))
          values <- Vector.fill(100)(pool.next).sequence
          _ <- IO(assertEquals(values.toSet, (0 until 100).toSet))
          _ <- Group.values.toVector.traverse_ { group =>
            Deferred[IO, Unit].flatMap { started =>
              pool.fork(group)(
                (started.complete(()) *> IO.never[Int]).guarantee(cleaned.update(_ + group))
              )(_ => IO.pure(-1)) *> started.get
            }
          }
        yield ()
      }
      result <- cleaned.get
    yield assertEquals(result, Group.values.toSet)
  }

  test(
    "an application result wins over every mux terminal state; otherwise shutdown retains cause"
  ) {
    import MuxLifecycle.*
    val returned: Either[NetworkError, Int] = Right(42)
    val failed: Either[NetworkError, Int] = Left(error)
    val statuses = Vector(Status.Ready, Status.Stopping, Status.Stopped, Status.Failed(error))
    statuses.foreach { status =>
      assertEquals(completion(status, Some(returned)), Some(Right(42)))
      assertEquals(completion(status, Some(failed)), Some(Left(CompletionError.Application(error))))
    }
    assertEquals(completion[Int](Status.Ready, None), None)
    assertEquals(
      completion[Int](Status.Failed(error), None),
      Some(Left(CompletionError.Shutdown(ShutdownStatus.Failed(error))))
    )
    Vector(Status.Stopping -> ShutdownStatus.Stopping, Status.Stopped -> ShutdownStatus.Stopped)
      .foreach { (status, shutdown) =>
        assertEquals(completion[Int](status, None), Some(Left(CompletionError.Shutdown(shutdown))))
      }
  }

  test("resource close cancels in native group then job order, not global creation order") {
    for
      cleaned <- Ref.of[IO, Vector[Int]](Vector.empty)
      _ <- MuxJobPool.resource[IO, Unit].use { pool =>
        Vector(
          Group.MiniProtocol -> 1,
          Group.Transport -> 2,
          Group.MiniProtocol -> 3,
          Group.Transport -> 4
        ).traverse_ { (group, id) =>
          Deferred[IO, Unit].flatMap { started =>
            pool.fork(group)(
              (started.complete(()) *> IO.never[Unit]).guarantee(cleaned.update(_ :+ id))
            )(_ => IO.unit) *> started.get
          }
        }
      }
      result <- cleaned.get
    yield assertEquals(result, Vector(2, 4, 1, 3))
  }
