// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.network.genesis

import munit.FunSuite
import xyz.kd5ujc.scarab.codec.Rational

class LeakyBucketSuite extends FunSuite:
  private val second = LeakyBucket.PicosPerSecond
  private val config = LeakyBucket.Config(Rational(10), Rational(2))

  test("reference Genesis defaults allow 200 seconds without progress"):
    val full = LeakyBucket.initial(LeakyBucket.Config.genesis, 0)
    assertEquals(full.waitMicros, Some(BigInt(200000000)))
    assert(full.snapshot(second * 200).expired)

  test("picosecond leakage and fills preserve exact rational accounting"):
    val full = LeakyBucket.initial(config, 0)
    val (filled, overflow) = full.fill(Rational(1, 3), second / 3)
    assertEquals(filled.level, Rational(10) - Rational(2 * (second / 3), second) + Rational(1, 3))
    assert(!overflow)

  test("pausing snapshots leakage first; repeated pause and fills do not leak"):
    val paused = LeakyBucket.initial(config, 0).setPaused(true, second)
    assertEquals(paused.level, Rational(8))
    val twice = paused.setPaused(true, 2 * second)
    val resumed = twice.fill(Rational.one, 50 * second)._1.setPaused(false, 100 * second)
    assertEquals(resumed.level, Rational(9))
    assertEquals(resumed.snapshot(101 * second).level, Rational(7))

  test("overflow either caps the whole fill or declines the whole fill"):
    val capped = LeakyBucket.initial(config, 0).fill(Rational(100), second)
    val refused =
      LeakyBucket.initial(config.copy(fillOnOverflow = false), 0).fill(Rational(100), second)
    assertEquals(capped._1.level, Rational(10))
    assertEquals(refused._1.level, Rational(8))
    assert(capped._2 && refused._2)

  test("GSM reconfiguration resets full, advances generation and preserves pause"):
    val paused = LeakyBucket.initial(config, 0).setPaused(true, second)
    val disabled = paused.reconfigure(LeakyBucket.Config.disabled, Rational.zero, 2 * second)
    val reset = disabled.reconfigure(config, config.capacity, 3 * second)
    assertEquals(reset.level, Rational(10))
    assertEquals(reset.generation, BigInt(2))
    assert(reset.paused)
    assert(!disabled.expired)

  test("positive sub-microsecond remainder expires at the reference timer boundary"):
    val full = LeakyBucket.initial(LeakyBucket.Config(Rational.one, Rational.one), 0)
    assert(!full.snapshot(second - 1000000).expired)
    assert(full.snapshot(second - 999999).expired)

  test("configuration changes clamp supplied levels"):
    val full = LeakyBucket.initial(config, 0)
    assertEquals(full.reconfigure(config, Rational(-1), second).level, Rational.zero)
    assertEquals(full.reconfigure(config, Rational(100), second).level, config.capacity)
