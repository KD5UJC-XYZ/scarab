// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.ledger.{ConfigError, ConfigProblem}

class ConfigErrorCaseSuite extends FunSuite:
  private def problem(args: String*): ConfigProblem =
    Main.parse(args.toList) match
      case Left(ConfigError(ConfigProblem.WithUsage(cause, _))) => cause
      case Left(ConfigError(cause))                             => cause
      case Right(_)                                             => fail("unexpected valid command")

  test("unknown, duplicate and incomplete flags retain the offending flag") {
    assertEquals(problem("follow", "--unknown", "x"), ConfigProblem.UnknownFlag("--unknown"))
    assertEquals(
      problem("follow", "--seconds", "1", "--seconds", "2"),
      ConfigProblem.DuplicateFlag("--seconds")
    )
    assertEquals(problem("follow", "--config"), ConfigProblem.MissingFlagValue("--config"))
  }

  test("an invalid flag value retains its flag and original input") {
    assertEquals(
      problem("follow", "--config", "config.json", "--peer", "127.0.0.1:3001", "--seconds", "bad"),
      ConfigProblem.FlagValue("seconds", "bad")
    )
  }

  test("a configuration that is valid JSON but has the wrong shape is distinguished") {
    assertEquals(
      NodeConfig.parse("[]").left.map(_.problem),
      Left(ConfigProblem.ExpectedObject("configuration", io.circe.Json.arr()))
    )
  }
