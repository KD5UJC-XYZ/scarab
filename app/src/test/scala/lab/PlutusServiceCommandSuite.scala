// SPDX-License-Identifier: Apache-2.0
package lab

import scala.concurrent.duration.*
import lab.submission.AdmissionProfile

class PlutusServiceCommandSuite extends munit.FunSuite:
  private val valid = List(
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
    "60",
    "--max-blocks",
    "128"
  )
  private def changed(key: String, value: String) = valid.updated(valid.indexOf(key) + 1, value)
  test("service configuration is explicit and contains no expected transaction or endpoint") {
    val c = PlutusServiceCommand.options(valid).toOption.get
    assertEquals(c.durationSeconds, 60)
    assertEquals(c.maxBlocks, 128)
    assertEquals(c.base.exchange.toString, "/private/service")
    assert(PlutusServiceCommand.options(valid ++ List("--expected-tx", "ab" * 32)).isLeft)
    assert(PlutusServiceCommand.options(valid ++ List("--endpoint", "/private/endpoint")).isLeft)
  }
  test("service bounds cannot be missing, duplicated, zero, negative or noncanonical") {
    val invalid = List(
      Nil,
      valid.drop(2),
      valid.updated(0, "--port"),
      changed("--duration-seconds", "0"),
      changed("--duration-seconds", "61"),
      changed("--duration-seconds", "+1"),
      changed("--max-blocks", "0"),
      changed("--max-blocks", "129"),
      changed("--max-blocks", "01"),
      changed("--profile", AdmissionProfile.NativeScript.id),
      changed("--magic", "764824073")
    )
    invalid.foreach(args => assert(PlutusServiceCommand.options(args).isLeft))
  }
  test("effective deadline preserves epoch safety margin and explains truncation") {
    assertEquals(PlutusServiceRuntime.deadline(1000, 100000, 60), (61000L, "durationLimit"))
    assertEquals(PlutusServiceRuntime.deadline(90000, 100000, 60), (98000L, "epochLimit"))
    intercept[IllegalArgumentException](PlutusServiceRuntime.deadline(98000, 100000, 1))
    intercept[IllegalArgumentException](PlutusServiceRuntime.deadline(0, 100000, 61))
  }
  test("relay retries have a global cap and bounded useful backoff") {
    assertEquals(PlutusServiceRuntime.MaxRelaySessions, 16)
    assertEquals(PlutusServiceRuntime.relayBackoff(1), 1.second)
    assertEquals(PlutusServiceRuntime.relayBackoff(2), 2.seconds)
    (3 to 16).foreach(n => assertEquals(PlutusServiceRuntime.relayBackoff(n), 4.seconds))
    intercept[IllegalArgumentException](PlutusServiceRuntime.relayBackoff(17))
  }
