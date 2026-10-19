// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.effect.{IO, Ref, Resource}
import xyz.kd5ujc.scarab.ledger.StorageError
import xyz.kd5ujc.scarab.network.LocalTxSubmissionServer

class LocalBackendOutcomeSuite extends munit.CatsEffectSuite:
  test("storage backend failure is fatal after protocol resources are released") {
    val error = StorageError.VersionMismatch("UTxO store", "v4", "v5")
    for
      releases <- Ref.of[IO, Int](0)
      fatal <- Ref.of[IO, Option[Fatal]](None)
      acquired = Resource
        .make(IO.pure(Left(error): Either[StorageError, LocalTxSubmissionServer.End]))(_ =>
          releases.update(_ + 1)
        )
      result <- Main.landLocalBackend(
        acquired.use(IO.pure),
        fault => fatal.set(Some(fault)) *> releases.get.flatMap(count => IO(assertEquals(count, 1)))
      )
      observedFatal <- fatal.get
    yield
      assertEquals(result, InboundEnd.Storage(error))
      assertEquals(observedFatal, Some(Fatal.Storage(error)))
      assertEquals(result.message, s"storage: ${error.message}")
  }
