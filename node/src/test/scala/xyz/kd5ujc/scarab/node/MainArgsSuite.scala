// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.ledger.ConfigError

class MainArgsSuite extends munit.FunSuite:
  private val base = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("known flags parse with their defaults") {
    val options = Main.parse(base).toOption.get
    assertEquals(options.queryPort, 7071)
    assertEquals(options.keepSnapshots, 3)
    assertEquals(options.mempoolBytes, None)
    assertEquals(Main.DefaultMempoolBytes, 1048576L)
  }

  test("a repeated --peer is allowed") {
    val options = Main.parse(base ++ List("--peer", "127.0.0.1:3002")).toOption.get
    assertEquals(options.peers.size, 2)
  }

  test("an unknown flag is a ConfigError with the usage") {
    val result = Main.parse(base ++ List("--db-dir", "/x"))
    assert(
      result.left.exists(e =>
        e.message.contains("unknown flag --db-dir") && e.message.contains("usage:")
      )
    )
  }

  test("a duplicated flag is a ConfigError") {
    val result = Main.parse(base ++ List("--seconds", "1", "--seconds", "2"))
    assert(result.left.exists(_.message.contains("duplicate flag --seconds")))
    assert(Main.parse(base ++ List("--forge", "--forge")).isLeft)
  }

  test("a flag without a value is a ConfigError") {
    assert(Main.parse(base ++ List("--seconds")).isLeft)
  }

  test("--header-first, --max-inbound and --inbound-sync have defaults and refuse bad values") {
    val defaults = Main.parse(base).toOption.get
    assert(defaults.headerFirst && defaults.inboundSync)
    assertEquals(defaults.maxInbound, 64)
    assert(!Main.parse(base ++ List("--header-first", "off")).toOption.get.headerFirst)
    assert(!Main.parse(base ++ List("--inbound-sync", "off")).toOption.get.inboundSync)
    assertEquals(Main.parse(base ++ List("--max-inbound", "5")).toOption.get.maxInbound, 5)
    assert(Main.parse(base ++ List("--header-first", "maybe")).isLeft)
    assert(Main.parse(base ++ List("--max-inbound", "0")).isLeft)
  }

  test("--cpu-threads takes zero (no pool) or a positive number, nothing else") {
    assertEquals(Main.parse(base ++ List("--cpu-threads", "0")).toOption.get.cpuThreads, 0)
    assertEquals(Main.parse(base ++ List("--cpu-threads", "3")).toOption.get.cpuThreads, 3)
    assert(Main.parse(base ++ List("--cpu-threads", "-1")).isLeft)
    assert(Main.parse(base ++ List("--cpu-threads", "many")).isLeft)
    assert(Main.parse(base).toOption.get.cpuThreads >= 1)
  }

  test("--dump-state takes a directory and optional points; the points alone are refused") {
    val on = Main.parse(base ++ List("--dump-state", "/d")).toOption.get
    assertEquals(
      on.dumpState,
      Some((java.nio.file.Path.of("/d"), StateDump.Targets.EpochBoundaries(1)))
    )
    assertEquals(on.dumpStateFull, false)
    val slots = Main
      .parse(base ++ List("--dump-state", "/d", "--dump-state-at", "100,200", "--dump-state-full"))
      .toOption
      .get
    assertEquals(
      slots.dumpState.map(_._2),
      Some(StateDump.Targets.Slots(List(BigInt(100), BigInt(200))))
    )
    assertEquals(slots.dumpStateFull, true)
    assert(Main.parse(base ++ List("--dump-state-at", "100")).isLeft)
    assert(Main.parse(base ++ List("--dump-state", "/d", "--dump-state-at", "later")).isLeft)
  }
