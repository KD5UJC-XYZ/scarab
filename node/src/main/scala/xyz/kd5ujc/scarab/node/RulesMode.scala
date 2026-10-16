// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Eq, Show}

/** Which ledger rules the node runs (`--rules`, step 2 of the ledger-state mirror, design
  * `ledger-mirror-step2.md` §2.5):
  *
  *   - `Old`: the flat rules, as before (the default);
  *   - `New`: the era-indexed rules. Nothing is ported yet, so this is refused at start (J2.8 makes
  *     it the default, J2.9 removes the choice);
  *   - `Differential`: both, side by side on every block, compared as `DifferentialRules` says.
  */
enum RulesMode(val flag: String):
  case Old extends RulesMode("old")
  case New extends RulesMode("new")
  case Differential extends RulesMode("differential")

object RulesMode:
  given Eq[RulesMode] = Eq.fromUniversalEquals
  given Show[RulesMode] = Show.show(_.flag)

  /** The mode a `--rules` value names. */
  def parse(value: String): Option[RulesMode] = values.find(_.flag == value)
