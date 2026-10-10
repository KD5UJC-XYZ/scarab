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

  test("only repeated terminal observations use the larger bounded component export") {
    assertEquals(PlutusServiceRuntime.terminalObservationLimit(None), 1048576)
    assertEquals(
      PlutusServiceRuntime.terminalObservationLimit(Some(PlutusServiceCommand.Repeated.Jvm)),
      2097152
    )
    val native = PlutusServiceCommand.options(base ++ mode).toOption.get.repeated
    assertEquals(
      PlutusServiceRuntime.terminalObservationLimit(native),
      RepeatedPlutusTerminal.MaxBytes
    )
    assertEquals(PlutusServiceRuntime.MaxTerminalBytes, 1048576)
    assertEquals(PlutusServiceRuntime.MaxPublicationBytes, 131072)
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

  test("720 second budget requires the exact pure JVM soak opt-in and preserves other caps") {
    val jvm = base ++ List("--epoch-mode", PlutusServiceCommand.JvmModeId)
    val soak = changed(jvm, "--duration-seconds", "720") ++ List(
      "--soak-profile",
      PlutusServiceCommand.SoakProfileId
    )
    val config = PlutusServiceCommand.options(soak).toOption.get
    assertEquals(config.repeatedBudget, EphemeralStreaming.RepeatedBudget.Soak)
    assertEquals(config.durationSeconds, 720)
    assertEquals(PlutusServiceRuntime.operationTimeout(config), 880.seconds)
    assertEquals(
      PlutusServiceRuntime.operationTimeout(PlutusServiceCommand.options(jvm).toOption.get),
      760.seconds
    )
    assert(PlutusServiceCommand.options(changed(jvm, "--duration-seconds", "601")).isLeft)
    assert(PlutusServiceCommand.options(changed(soak, "--duration-seconds", "721")).isLeft)
    assert(PlutusServiceCommand.options(changed(soak, "--max-blocks", "513")).isLeft)
    assert(PlutusServiceCommand.options(changed(soak, "--soak-profile", "unknown")).isLeft)
    assert(
      PlutusServiceCommand
        .options(base ++ mode ++ List("--soak-profile", PlutusServiceCommand.SoakProfileId))
        .isLeft
    )
    assert(
      PlutusServiceCommand
        .options(
          changed(changed(base, "--duration-seconds", "30"), "--max-blocks", "128") ++ List(
            "--soak-profile",
            PlutusServiceCommand.SoakProfileId
          )
        )
        .isLeft
    )
    assert(
      PlutusServiceCommand
        .options(soak ++ List("--soak-profile", PlutusServiceCommand.SoakProfileId))
        .isLeft
    )
    assertEquals(
      PlutusServiceRuntime.repeatedDeadline(90000, 720, config.repeatedBudget),
      (810000L, PlutusRunPolicy.WindowEnd.Duration)
    )
    intercept[IllegalArgumentException](
      PlutusServiceRuntime.repeatedDeadline(0, 721, config.repeatedBudget)
    )
    val limits =
      EphemeralStreaming.Limits(maxEvents = 4096, maxBlocks = 512, duration = 720.seconds)
    assert(!EphemeralStreaming.repeatedLimits(limits))
    assert(EphemeralStreaming.repeatedLimits(limits, config.repeatedBudget))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(retained = 9), config.repeatedBudget))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(maxEvents = 4097), config.repeatedBudget))
    assert(!EphemeralStreaming.repeatedLimits(limits.copy(maxBlocks = 513), config.repeatedBudget))
    assert(
      !EphemeralStreaming.repeatedLimits(
        limits.copy(maxBytes = 256L * 1024 * 1024 + 1),
        config.repeatedBudget
      )
    )
  }

  test("soak streaming waits beyond 600 and stops at its explicit 720 second deadline") {
    TestControl
      .executeEmbed(for
        runtime <- EphemeralStreamingFixture.runtime
        before <- runtime.snapshot
        result <- EphemeralStreaming.runRepeated(
          runtime,
          EphemeralStreaming.Limits(duration = 720.seconds),
          EphemeralStreaming.RepeatedBudget.Soak
        )(IO.never[Option[EphemeralStreaming.Event]])
        _ = assertEquals(result.elapsed, 720.seconds)
        _ = assertEquals(result.stop, EphemeralStreaming.Stop.Deadline)
        _ = assertEquals(result.snapshot.state.id, before.state.id)
      yield ())
      .unsafeToFuture()
  }

  test("absent follow timing preserves legacy JSON exactly") {
    val original = ReferenceJson.Json.Obj(Map("schema" -> ReferenceJson.Json.Str("legacy")))
    assertEquals(PlutusServiceRuntime.withFollowWindow(original, None), original)
    val window = PlutusRepeatedEpochFollow.FollowWindow(1000, 2200, 1200000000L)
    val timed = PlutusServiceRuntime.withFollowWindow(original, Some(window))
    assertEquals(ReferenceJson.field(timed, "schema"), ReferenceJson.Json.Str("legacy"))
    val measured = ReferenceJson.field(timed, "followWindow")
    assertEquals(
      ReferenceJson.field(measured, "schema"),
      ReferenceJson.Json.Str("plutus-service-follow-window-v1")
    )
    assertEquals(
      ReferenceJson.field(measured, "elapsedMonotonicNanos"),
      ReferenceJson.Json.Num("1200000000")
    )
  }
