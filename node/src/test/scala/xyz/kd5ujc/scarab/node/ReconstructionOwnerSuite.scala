// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{Deferred, IO}
import cats.mtl.syntax.all.*
import scala.concurrent.duration.*
import xyz.kd5ujc.scarab.codec.DomainError
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.FailsOnUncaught

class ReconstructionOwnerSuite extends FailsOnUncaught:
  private enum Problem extends DomainError:
    case MissingRuntime

  private val original = new IllegalStateException("original decoder")
  private val failures = Vector(
    (StorageError.DecoderDefect("snapshot", original), Fatal.Defect(original)),
    (
      StorageError.Reconstruction("snapshot", Problem.MissingRuntime),
      Fatal.Internal(StorageError.Reconstruction("snapshot", Problem.MissingRuntime))
    )
  )

  failures.zipWithIndex.foreach { case ((error, expected), index) =>
    test(s"storage reconstruction owner retains exact failure $index and awaits shutdown") {
      for
        observed <- Deferred[IO, Fatal]
        finished <- Deferred[IO, Unit]
        _ <- StorageBoundary
          .run[IO, Unit](fatal => observed.complete(fatal).void) {
            error.raise[IO, Unit]
          }
          .guarantee(finished.complete(()).void)
          .background
          .use { _ =>
            for
              actual <- observed.get.timeout(3.seconds)
              beforeCancel <- finished.tryGet
            yield
              assertEquals(actual, expected)
              assertEquals(beforeCancel, None)
          }
        _ <- finished.get.timeout(3.seconds)
      yield ()
    }
  }
