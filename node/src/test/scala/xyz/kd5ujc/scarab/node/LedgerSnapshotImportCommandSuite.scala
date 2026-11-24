// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotImportCommand as Command
import xyz.kd5ujc.scarab.ledger.ConfigError
import xyz.kd5ujc.scarab.ledger.ConfigProblem

class LedgerSnapshotImportCommandSuite extends FunSuite:
  private val base = List("--haskell-ledger", "ledger/123", "--db", "db", "--config", "config.json")

  test("ancillary verification is the default and bypass requires the explicit risk flag") {
    assert(Command.parse(base) match {
      case Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.AncillaryKeyRequired, _))) => true
      case _ => false
    })
    val verified = Command.parse(base ++ List("--ancillary-verification-key", "ancillary.vkey"))
    assert(verified.exists(o => !o.unverified && o.key.nonEmpty))
    assert(Command.parse(base :+ "--no-verify-ancillary").exists(_.unverified))
    assert(
      (Command
        .parse(
          base ++ List("--ancillary-verification-key", "key", "--no-verify-ancillary")
        )) match {
        case Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.AncillaryKeyRequired, _))) =>
          true
        case _ => false
      }
    )
    assert(
      (Command
        .parse(base ++ List("--ancillary-manifest", "manifest", "--no-verify-ancillary"))) match {
        case Left(ConfigError(ConfigProblem.AncillaryVerificationRequired)) => true
        case _                                                              => false
      }
    )
  }

  test("missing, duplicate and unknown arguments are refused") {
    Vector(
      List.empty[String],
      base ++ List("--ancillary-verification-key"),
      base ++ List("--db", "other", "--no-verify-ancillary"),
      base ++ List("--no-verify-ancillary", "--no-verify-ancillary"),
      base ++ List("--unknown", "x", "--no-verify-ancillary")
    ).foreach { args =>
      assert(
        Command.parse(args) match {
          case Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.DuplicateFlag(_), _))) |
              Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlag(_), _))) |
              Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.MissingFlagValue(_), _))) |
              Left(ConfigError(ConfigProblem.WithUsage(ConfigProblem.UnknownFlag(_), _))) =>
            true
          case _ => false
        },
        args.toString
      )
    }
  }
