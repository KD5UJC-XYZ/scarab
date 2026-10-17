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
