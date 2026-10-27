// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.fuzz

import java.lang.management.ManagementFactory
import munit.FunSuite

/** Exercise the unchanged target's time/allocation guards, including its largest possible corpus
  * mutation size. These controls neither replace random cases nor change their distribution.
  */
class InjectionDecimalGuardSuite extends FunSuite:
  private val target =
    Targets.all.find(_.name == "injection-json").getOrElse(fail("injection-json target"))
  private def bytes(text: String): Array[Byte] =
    text.getBytes(java.nio.charset.StandardCharsets.US_ASCII)

  private def guarded(label: String, raw: Array[Byte]): Unit =
    val meter = ManagementFactory.getThreadMXBean match
      case bean: com.sun.management.ThreadMXBean => bean
      case _                                     => fail("allocation counters unavailable")
    val thread = Thread.currentThread().threadId()
    val before = meter.getThreadAllocatedBytes(thread)
    val start = System.nanoTime()
    target.check(new Input(raw))
    val allocated = meter.getThreadAllocatedBytes(thread) - before
    val millis = (System.nanoTime() - start) / 1000000
    println(s"injection decimal $label: input=${raw.length} allocated=$allocated millis=$millis")

  test("cold maximum fixed-feed coefficients retain the original resource guard") {
    guarded("cold-root-coefficient-327679", bytes("1" * 327679 + " "))
    guarded("cold-object-coefficient-327674", bytes("{\"n\":" + "1" * 327674 + "}"))
  }

  test("original native inputs retain unchanged resource guards") {
    target.corpus().foreach(raw => guarded("native-seed", raw))
  }

  test("coefficient allocation scales within the existing target bound") {
    Vector(32767, 32768, 65536, 131072, 262145, 327674, 327675).foreach { width =>
      guarded(s"coefficient-$width", bytes("{\"n\":" + "1" * width + "}"))
    }
    // Starting at the first chunk byte also exercises the maximum terminating root number.
    guarded("root-coefficient-327679", bytes("1" * 327679 + " "))
  }

  test("four duplications of the largest corpus input retain unchanged target guards") {
    val largest = target.corpus().maxByOption(_.length).getOrElse(fail("empty corpus"))
    assertEquals(largest.length, 327686)
    // All other mutations add at most the largest seed or 200000 bytes. Four doublings bound
    // every generated input and are themselves reachable without changing Mutate.generator.
    val maximum = (0 until 4).foldLeft(largest)((raw, _) => raw ++ raw)
    assertEquals(maximum.length, 5242976)
    guarded("largest-reachable-mutation", maximum)
  }
