// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import munit.FunSuite
import xyz.kd5ujc.scarab.node.tools.LedgerSnapshotImportCommand as Command

class LedgerSnapshotImportCommandSuite extends FunSuite:
  private val base = List("--haskell-ledger", "ledger/123", "--db", "db", "--config", "config.json")

  test("ancillary verification is the default and bypass requires the explicit risk flag") {
    assert(Command.parse(base).isLeft)
    val verified = Command.parse(base ++ List("--ancillary-verification-key", "ancillary.vkey"))
    assert(verified.exists(o => !o.unverified && o.key.nonEmpty))
    assert(Command.parse(base :+ "--no-verify-ancillary").exists(_.unverified))
    assert(
      Command
        .parse(base ++ List("--ancillary-verification-key", "key", "--no-verify-ancillary"))
        .isLeft
    )
    assert(
      Command
        .parse(base ++ List("--ancillary-manifest", "manifest", "--no-verify-ancillary"))
        .isLeft
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
      assert(Command.parse(args).isLeft, args.toString)
    }
  }
