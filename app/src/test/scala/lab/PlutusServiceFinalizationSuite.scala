// SPDX-License-Identifier: Apache-2.0
package lab

import cats.effect.{IO, Resource, Ref, ExitCode}
import cats.effect.unsafe.implicits.global
import cats.syntax.all.*
import java.nio.file.Files
import lab.submission.AdmissionProfile
import ReferenceJson.{Json as J}

class PlutusServiceFinalizationSuite extends munit.FunSuite:
  private def config(path: java.nio.file.Path) = PlutusServiceCommand
    .options(
      List(
        "--profile",
        AdmissionProfile.PlutusV3.id,
        "--initial",
        "/private/initial",
        "--manifest-sha256",
        "ab" * 32,
        "--port",
        "12345",
        "--magic",
        "991122",
        "--output",
        path.toString,
        "--duration-seconds",
        "1",
        "--max-blocks",
        "1"
      )
    )
    .toOption
    .get
  private val success =
    J.Obj(Map("status" -> J.Str("stopped"), "resourcesFinalized" -> J.Lit("true")))

  test("generation release failure emits failure status without a successful result") {
    (for
      root <- IO.blocking(Files.createTempDirectory("plutus-generation-release-"))
      finalized <- Ref.of[IO, Int](0)
      resource = Resource.make(IO.unit)(_ =>
        finalized.update(_ + 1) *> IO.raiseError[Unit](
          new IllegalStateException("generation release failed")
        )
      )
      code <- PlutusServiceRuntime.runOperation(config(root))(
        PlutusServiceRuntime.publishAfterFinalization(root, resource)(_ => IO.pure((success, true)))
      )
      count <- finalized.get
      _ = assertEquals(code, ExitCode(2))
      _ = assertEquals(count, 1)
      _ <- IO.blocking {
        assert(!Files.exists(root.resolve("result.json")))
        val failure = Files.readString(root.resolve("failure.json"))
        assert(failure.contains("failed"))
        assert(failure.contains("IllegalStateException"))
      }
    yield ()).unsafeToFuture()
  }

  test("successful result is absent during release and published only after release") {
    (for
      root <- IO.blocking(Files.createTempDirectory("plutus-generation-success-"))
      finalized <- Ref.of[IO, Boolean](false)
      resource = Resource.make(IO.unit)(_ =>
        IO.blocking(assert(!Files.exists(root.resolve("result.json")))) *> finalized.set(true)
      )
      ok <- PlutusServiceRuntime.publishAfterFinalization(root, resource)(_ =>
        IO.pure((success, true))
      )
      done <- finalized.get
      _ = assert(ok && done)
      _ <- IO.blocking(assert(Files.exists(root.resolve("result.json"))))
    yield ()).unsafeToFuture()
  }
