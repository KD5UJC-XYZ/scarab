<!-- SPDX-License-Identifier: Apache-2.0 -->
# Relay and mempool

Part of the [compatibility matrix](../compatibility.md); the register entry is `relay-and-mempool` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: pure `MempoolState`, TxSubmission2 both roles, typed rejections

## Evidence

unit/property (held transactions always apply in order, byte accounting, tickets; the measure refusals `MaxTxSizeUTxO`, `ExUnitsTooBigUTxO` and `ConwayTxRefScriptsSizeTooBig` before the ledger rules, and each component of the capacity vector as the binding one); devnet `relay` (203/203 transactions via the Scala mempool into pool blocks) and `pool`

## Explicitly unchecked or unconfirmed

full successor-slot ticking includes epoch transitions, pending rewards, treasury and adopted parameters (H4/#40); ordered rollback revalidation matches the native current-entry policy, with no automatic block-transaction resurrection and no persistent seen-ID suppression of later re-offers; admission waits for room as the reference does (two FIFO lines, local clients ahead of peers, cancelable; tests `MempoolBackpressureSuite`, [IN-82](../implementer-notes.md)), per-client mempool admission fairness beyond that remains roadmap G5-F1; network resource bounds are recorded in [G5](../design/network-limits.md)
