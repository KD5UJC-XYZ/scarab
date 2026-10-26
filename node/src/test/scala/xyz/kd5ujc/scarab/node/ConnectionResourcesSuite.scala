// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import java.util.concurrent.Executors
import scala.concurrent.ExecutionContext
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class ConnectionResourcesSuite extends FailsOnUncaught:
  test("ordinary success and failure keep the release exit case and original acquisition failure") {
    for
      exits <- Ref.of[IO, Vector[Resource.ExitCase]](Vector.empty)
      defects <- Ref.of[IO, Vector[Fatal.Defect]](Vector.empty)
      useFailure = new IllegalStateException("original use failure")
      acquisitionFailure = new IllegalStateException("original acquisition failure")
      resource = Resource.makeCase(IO.unit)((_, exit) => exits.update(_ :+ exit))
      guarded = ConnectionResources.retainReleaseDefects(resource)(error =>
        defects.update(_ :+ error)
      )
      _ <- guarded.use_
      failed <- guarded.use(_ => IO.raiseError[Unit](useFailure)).attempt
      refused <- ConnectionResources
        .retainReleaseDefects(Resource.eval(IO.raiseError[Unit](acquisitionFailure)))(error =>
          defects.update(_ :+ error)
        )
        .use_
        .attempt
      observed <- exits.get
      retained <- defects.get
      _ <- IO {
        assertEquals(failed, Left(useFailure))
        assertEquals(refused, Left(acquisitionFailure))
        assertEquals(
          observed,
          Vector(Resource.ExitCase.Succeeded, Resource.ExitCase.Errored(useFailure))
        )
        assertEquals(retained, Vector.empty)
      }
    yield ()
  }

  test("ordinary cancellation keeps Canceled and releases exactly once") {
    for
      entered <- Deferred[IO, Unit]
      exits <- Ref.of[IO, Vector[Resource.ExitCase]](Vector.empty)
      defects <- Ref.of[IO, Vector[Fatal.Defect]](Vector.empty)
      resource = Resource.makeCase(IO.unit)((_, exit) => exits.update(_ :+ exit))
      _ <- Resource
        .make(
          ConnectionResources
            .retainReleaseDefects(resource)(error => defects.update(_ :+ error))
            .use(_ => entered.complete(()) *> IO.never[Unit])
            .start
        )(_.cancel)
        .use { running =>
          entered.get *> running.cancel
        }
      observed <- exits.get
      retained <- defects.get
      _ <- IO {
        assertEquals(observed, Vector(Resource.ExitCase.Canceled))
        assertEquals(retained, Vector.empty)
      }
    yield ()
  }

  test("a failing inner release keeps the outer Errored exit and every original cause") {
    for
      exits <- Ref.of[IO, Vector[Resource.ExitCase]](Vector.empty)
      defects <- Ref.of[IO, Vector[Fatal.Defect]](Vector.empty)
      inner = new IllegalStateException("inner release")
      outer = new IllegalStateException("outer release")
      resource = Resource.makeCase(IO.unit)((_, exit) =>
        exits.update(_ :+ exit) *> IO.raiseError(outer)
      ) *>
        Resource.make(IO.unit)(_ => IO.raiseError(inner))
      result <- ConnectionResources
        .retainReleaseDefects(resource)(error => defects.update(_ :+ error))
        .use_
        .attempt
      observed <- exits.get
      retained <- defects.get
      _ <- IO {
        assertEquals(result, Left(inner))
        assertEquals(observed, Vector(Resource.ExitCase.Errored(inner)))
        assertEquals(retained, Vector(Fatal.Defect(inner), Fatal.Defect(outer)))
      }
    yield ()
  }

  test("a foreign release function throwing before returning its effect is retained") {
    for
      defects <- Ref.of[IO, Vector[Fatal.Defect]](Vector.empty)
      cause = new IllegalStateException("foreign release invocation")
      resource = Resource.makeCase[IO, Unit](IO.unit)((_, _) => throw cause)
      result <- ConnectionResources
        .retainReleaseDefects(resource)(error => defects.update(_ :+ error))
        .use_
        .attempt
      retained <- defects.get
      _ <- IO {
        assertEquals(result, Left(cause))
        assertEquals(retained, Vector(Fatal.Defect(cause)))
      }
    yield ()
  }

  test("a deep resource bind chain releases without interpreter recursion") {
    Resource
      .make(
        IO.delay(
          Executors.newSingleThreadExecutor((run: Runnable) =>
            new Thread(null, run, "connection-resource-small-stack", 256L * 1024)
          )
        )
      )(executor => IO.delay(executor.shutdown()))
      .use { executor =>
        (for
          released <- Ref.of[IO, Int](0)
          defects <- Ref.of[IO, Vector[Fatal.Defect]](Vector.empty)
          resource = (0 until 50000).foldLeft(Resource.pure[IO, Unit](())) { (previous, _) =>
            previous *> Resource.make(IO.unit)(_ => released.update(_ + 1))
          }
          _ <- ConnectionResources
            .retainReleaseDefects(resource)(error => defects.update(_ :+ error))
            .use_
          count <- released.get
          retained <- defects.get
          _ <- IO {
            assertEquals(count, 50000)
            assertEquals(retained, Vector.empty)
          }
        yield ()).evalOn(ExecutionContext.fromExecutorService(executor))
      }
  }
