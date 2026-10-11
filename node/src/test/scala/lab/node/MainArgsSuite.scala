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
