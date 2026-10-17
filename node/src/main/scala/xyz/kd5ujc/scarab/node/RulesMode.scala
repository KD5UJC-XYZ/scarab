// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.node

import cats.{Eq, Show}

/** Which ledger rules the node runs (`--rules`, step 2 of the ledger-state mirror, design
  * `ledger-mirror-step2.md` §2.5):
  *
  *   - `New`: the era-indexed rules on the mirror of the Haskell ledger state, which is the chain's
  *     state (the default since J2.8);
  *   - `Old`: the flat rules and the node's flat state, as before J2.8 (until J2.9 removes the
  *     choice);
  *   - `Differential`: the old rules and the new ones side by side on every block, compared as
  *     `DifferentialRules` says (the chain's state is the flat one).
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
