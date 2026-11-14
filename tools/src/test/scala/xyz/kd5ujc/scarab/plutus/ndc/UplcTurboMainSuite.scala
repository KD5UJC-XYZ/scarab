// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.plutus.ndc

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Bytes
import xyz.kd5ujc.scarab.plutus.{PlutusLanguage, PlutusVm}
import xyz.kd5ujc.scarab.uplc.cost.ExBudget
import xyz.kd5ujc.scarab.uplc.text.UplcParser

/** The harness of the `uplc-turbo` scenario: the README's epochs and names, the measurement of one
  * program and the report's item. Times are not asserted, only that they are measured.
  */
class UplcTurboMainSuite extends FunSuite:
  import UplcTurboMain.*

  test("the protocol of an epoch is the README's table, at each of its boundaries") {
    val table = Vector(290 -> 8, 506 -> 8, 507 -> 9, 536 -> 9, 537 -> 10, 643 -> 10, 644 -> 11)
    table.foreach((epoch, protocol) => assertEquals(protocolOf(epoch), protocol, s"epoch $epoch"))
  }

  test("the language of a program is the one its name gives") {
    assertEquals(languageOf("00AB-0-CD-v1.flat"), Some(PlutusLanguage.V1))
    assertEquals(languageOf("00AB-1-CD-v3.flat"), Some(PlutusLanguage.V3))
    assertEquals(languageOf("00AB-1-CD-v4.flat"), None)
    assertEquals(languageOf("00AB-1-CD-v2.uplc"), None)
  }

  PlutusVm.values.foreach { vm =>
    test(s"${vm.name}: a program is run fifty times and measured with the budget it spent") {
      val backend = UplcBackend.of(vm)
      val language = PlutusLanguage.V2
      val context = defaultCostModel(language, 8)
        .fold(e => fail(e.message), backend.context(language, 8, _))
        .fold(e => fail(e.message), identity)
      val program = UplcParser
        .program("(program 1.0.0 [(lam x [(builtin addInteger) x x]) (con integer 21)])")
        .fold(e => fail(e.toString), identity)
      val prepared = backend.text(context, program).fold(r => fail(r.toString), identity)
      val measured =
        measure(prepared, counterOverhead(), Limit).fold(e => fail(e.message), identity)
      assert(measured.cpuMicros > 0, "a time is measured")
      assert(measured.allocated >= 0, "allocation is never negative")
      assert(measured.spent.cpu.toLong > 0 && measured.spent.memory.toLong > 0, "a budget is spent")
      val json = item(Bench("b-0-c-v2.flat", language, 8, Bytes.empty), measured, Memory.Unreported)
      assertEquals(
        json.asObject.map(_.keys.toVector),
        Some(
          Vector(
            "bench",
            "plutus_version",
            "protocol_version",
            "budget_cpu",
            "budget_mem",
            "cpu_us",
            "mem_bytes"
          )
        )
      )
      assertEquals(json.hcursor.get[String]("protocol_version").toOption, Some("8.0"))
      assert(json.hcursor.downField("mem_bytes").focus.exists(_.isNull), "null when not reported")
    }

    test(s"${vm.name}: a program that fails is not measured") {
      val backend = UplcBackend.of(vm)
      val language = PlutusLanguage.V1
      val context = defaultCostModel(language, 9)
        .fold(e => fail(e.message), backend.context(language, 9, _))
        .fold(e => fail(e.message), identity)
      val program =
        UplcParser.program("(program 1.0.0 (error))").fold(e => fail(e.toString), identity)
      val prepared = backend.text(context, program).fold(r => fail(r.toString), identity)
      assert(measure(prepared, 0L, Limit).isLeft)
    }
  }

  test("the README's budget is the restricting limit, and the default of the options") {
    assertEquals(Limit, ExBudget.of(10_000_000_000L, 10_000_000L))
    assertEquals(options(Nil), Some(Options(Memory.Allocated, 1, Limit)))
  }

  test("the options are read in any order, and anything else refuses the command line") {
    assertEquals(
      options(List("--memory-limit", "14000000", "--memory", "null", "--warm-up", "0")),
      Some(Options(Memory.Unreported, 0, ExBudget.of(10_000_000_000L, 14_000_000L)))
    )
    assertEquals(options(List("--warm-up", "-1")), None)
    assertEquals(options(List("--memory", "rss")), None)
    assertEquals(options(List("--memory-limit")), None)
  }
