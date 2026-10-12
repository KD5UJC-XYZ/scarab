// SPDX-License-Identifier: Apache-2.0
package lab.node

class MainArgsSuite extends munit.FunSuite:
  private val base = List("follow", "--genesis-dir", "/g", "--peer", "127.0.0.1:3001")

  test("known flags parse with their defaults") {
    val options = Main.parse(base).toOption.get
    assertEquals(options.queryPort, 7071)
    assertEquals(options.keepSnapshots, 3)
    assertEquals(options.mempoolBytes, 1048576L)
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
