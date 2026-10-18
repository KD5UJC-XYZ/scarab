// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.node.tools.MithrilBootstrapCommand as Command

class MithrilBootstrapCommandSuite extends FunSuite:
  private val base = List(
    "--artifact",
    "a" * 64,
    "--aggregator",
    "https://example.test/aggregator",
    "--genesis-verification-key",
    "genesis.vkey",
    "--ancillary-verification-key",
    "ancillary.vkey",
    "--mithril-client",
    "mithril-client",
    "--db",
    "fresh-db",
    "--config",
    "config.json"
  )

  test("bootstrap requires explicit artifact, certificate root and independent ancillary root") {
    assert(Command.parse(base).exists(_.source.isEmpty))
    assert(Command.parse(base ++ List("--source-db", "cached-db")).exists(_.source.nonEmpty))
    Vector(
      "--artifact",
      "--genesis-verification-key",
      "--ancillary-verification-key",
      "--mithril-client"
    ).foreach { flag =>
      val index = base.indexOf(flag)
      assert(Command.parse(base.take(index) ++ base.drop(index + 2)).isLeft)
    }
  }

  test("unsafe shortcuts, aliases and ambiguous command lines are refused") {
    Vector(
      base.updated(1, "latest"),
      base ++ List("--db", "other"),
      base ++ List("--allow-missing", "true"),
      base ++ List("--no-verify-ancillary", "true"),
      base ++ List("--timeout-seconds", "0"),
      base ++ List("--timeout-seconds", "604801"),
      base ++ List("--timeout-seconds", "1.5"),
      base ++ List("--source-db"),
      base.updated(3, "http://public.example.test/aggregator")
    ).foreach(args => assert(Command.parse(args).isLeft, args.toString))
  }
