// SPDX-License-Identifier: Apache-2.0
package lab

import scala.concurrent.duration.*
import cats.effect.IO
import cats.effect.testkit.TestControl
import cats.effect.unsafe.implicits.global
import lab.submission.AdmissionProfile

class PlutusRepeatedServiceModeSuite extends munit.FunSuite:
  private val base = List(
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
    "/private/service",
    "--duration-seconds",
    "600",
    "--max-blocks",
    "512"
  )
  private val mode = List(
    "--epoch-mode",
    PlutusServiceCommand.RepeatedModeId,
    "--native-likelihood-executable",
    "/native/likelihood",
    "--native-likelihood-sha256",
    "cd" * 32
  )
  private def changed(args: List[String], key: String, value: String) =
    args.updated(args.indexOf(key) + 1, value)

  test("long service bounds require the complete explicit native checked mode") {
    assert(PlutusServiceCommand.options(base).isLeft)
    val c = PlutusServiceCommand.options(base ++ mode).toOption.get
    assertEquals(c.durationSeconds, 600)
    assertEquals(c.maxBlocks, 512)
    assertEquals(c.repeated.get.executable.get.toString, "/native/likelihood")
    assertEquals(c.repeated.get.executablePin.get.hex, "cd" * 32)
    mode.grouped(2).foreach(pair => assert(PlutusServiceCommand.options(base ++ pair).isLeft))
    assert(
      PlutusServiceCommand.options(changed(base ++ mode, "--epoch-mode", "native-assisted")).isLeft
    )
  }
  test("repeated mode rejects expanded bounds and ambiguous or mutable output executable paths") {
    val valid = base ++ mode
    Vector(
      "--duration-seconds" -> "601",
      "--max-blocks" -> "513",
      "--native-likelihood-executable" -> "native/likelihood",
      "--native-likelihood-executable" -> "/native/../likelihood",
      "--native-likelihood-executable" -> "/private/service/binary",
      "--native-likelihood-sha256" -> "CD" * 32
    ).foreach((key, value) =>
      assert(PlutusServiceCommand.options(changed(valid, key, value)).isLeft)
    )
    assert(PlutusServiceCommand.options(valid ++ mode).isLeft)
  }
  test("repeated continuation accepts early restore but refuses composed checkpoint export") {
    val identity = List("--store-id", "11" * 32, "--session-id", "22" * 32, "--generation", "1")
    val restore = List(
      "--restore-checkpoint",
      "/private/old/checkpoint.bin",
      "--restore-authority",
      "/private/authority.json",
      "--restore-authority-sha256",
      "33" * 32
    )
    assert(PlutusServiceCommand.options(base ++ mode ++ identity ++ restore).isRight)
    assert(
      PlutusServiceCommand
        .options(base ++ mode ++ identity ++ List("--checkpoint-after", "8"))
        .isLeft
    )
  }
  test("repeated deadline does not silently truncate at the first epoch and remains bounded") {
    assertEquals(
      PlutusServiceRuntime.repeatedDeadline(90000, 600),
      (690000L, PlutusRunPolicy.WindowEnd.Duration)
    )
    assertEquals(
      PlutusServiceRuntime.deadline(90000, 100000, 60),
      (98000L, PlutusRunPolicy.WindowEnd.Epoch)
    )
    intercept[IllegalArgumentException](PlutusServiceRuntime.repeatedDeadline(0, 601))
    intercept[IllegalArgumentException](PlutusServiceRuntime.repeatedDeadline(Long.MaxValue, 1))
  }
  test("streaming long limits require explicit repeated entry and preserve every other bound") {
    val limits =
      EphemeralStreaming.Limits(maxEvents = 4096, maxBlocks = 512, duration = 600.seconds)
    assert(!limits.valid)
    assert(EphemeralStreaming.repeatedLimits(limits))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(retained = 9)))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(maxBlocks = 513)))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(duration = 601.seconds)))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(maxBytes = 256L * 1024 * 1024 + 1)))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(maxEvents = 4097)))
  }

  test(
    "explicit repeated driver can wait beyond the legacy cap and still enforces its own deadline"
  ) {
    TestControl
      .executeEmbed(for
        runtime <- EphemeralStreamingFixture.runtime
        before <- runtime.snapshot
        ended <- EphemeralStreaming.runRepeated(
          runtime,
          EphemeralStreaming.Limits(duration = 600.seconds)
        )(IO.sleep(121.seconds).as(None))
        timed <- EphemeralStreaming.runRepeated(
          runtime,
          EphemeralStreaming.Limits(duration = 600.seconds)
        )(IO.never[Option[EphemeralStreaming.Event]])
        _ = assertEquals(ended.stop, EphemeralStreaming.Stop.End)
        _ = assertEquals(ended.elapsed, 121.seconds)
        _ = assertEquals(timed.stop, EphemeralStreaming.Stop.Deadline)
        _ = assertEquals(timed.elapsed, 600.seconds)
        _ = assertEquals(timed.snapshot.state.id, before.state.id)
      yield ())
      .unsafeToFuture()
  }

  test("pure JVM repeated mode has no native options, native fallback or executable requirement") {
    val args = base ++ List("--epoch-mode", PlutusServiceCommand.JvmModeId)
    val config = PlutusServiceCommand.options(args).toOption.get
    assertEquals(config.repeated, Some(PlutusServiceCommand.Repeated.Jvm))
    assertEquals(config.repeated.get.executable, None)
    assertEquals(config.repeated.get.executablePin, None)
    assert(
      PlutusServiceCommand
        .options(args ++ List("--native-likelihood-executable", "/native/tool"))
        .isLeft
    )
    assert(
      PlutusServiceCommand.options(args ++ List("--native-likelihood-sha256", "ab" * 32)).isLeft
    )
    assert(
      PlutusServiceCommand
        .options(changed(base ++ mode, "--epoch-mode", PlutusServiceCommand.JvmModeId))
        .isLeft
    )
  }
