// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import xyz.kd5ujc.scarab.protocol.ReferenceBuild
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

  // #54: the Leios build's ChainSync client fetches endorser blocks from the headers it validates,
  // and the reference has no block-only mode
  test("--header-first off is refused under the Leios build, and kept under a release build") {
    val follow = List("follow", "--config", "config.json", "--peer", "127.0.0.1:3001")
    val leios = List("--reference-build", "leios-b004fe0c")
    val off = List("--header-first", "off")
    assertEquals(
      problem((follow ++ leios ++ off)*),
      ConfigProblem.HeaderFirstRequired(ReferenceBuild.LeiosB004fe0c)
    )
    assert(Main.parse(follow ++ leios).map(_.headerFirst) == Right(true))
    assert(Main.parse(follow ++ leios ++ List("--header-first", "on")).isRight)
    assert(Main.parse(follow ++ off).map(_.headerFirst) == Right(false))
  }

  test("a configuration that is valid JSON but has the wrong shape is distinguished") {
    assertEquals(
      NodeConfig.parse(ReferenceBuild.Default)("[]").left.map(_.problem),
      Left(ConfigProblem.ExpectedObject("configuration", io.circe.Json.arr()))
    )
  }
