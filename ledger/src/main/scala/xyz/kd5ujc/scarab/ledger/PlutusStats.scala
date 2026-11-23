// SPDX-License-Identifier: Apache-2.0
package xyz.kd5ujc.scarab.ledger

/** Plutus phase-2 evaluation counters. `succeeded` and `failed` count transactions whose scripts
  * were evaluated and whose verdict equalled the block's `is_valid` flag (a disagreement is a
  * validation error, so it is never counted); `scripts` counts the redeemers evaluated and
  * `integrityChecked` the script data hashes recomputed and found equal.
  */
final case class PlutusStats(
    succeeded: Long,
    failed: Long,
    scripts: Long,
    integrityChecked: Long
)

object PlutusStats:
  val zero: PlutusStats = PlutusStats(0, 0, 0, 0)

  /** Counters added field by field: the node's running counters and a block's (Conway's era run
    * counts each block's from zero, `ConwayPhase2.counted`).
    */
  given cats.Monoid[PlutusStats] with
    def empty: PlutusStats = zero
    def combine(a: PlutusStats, b: PlutusStats): PlutusStats =
      PlutusStats(
        a.succeeded + b.succeeded,
        a.failed + b.failed,
        a.scripts + b.scripts,
        a.integrityChecked + b.integrityChecked
      )
