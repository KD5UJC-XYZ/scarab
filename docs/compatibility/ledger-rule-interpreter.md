<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: typed rule interpreter (#202)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-rule-interpreter` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: type-aligned `Frames` preserve intermediate, state, failure and scoped-Globals relationships; no casts, unchecked patterns or erased value channel in `RuleT.scala`

## Evidence

`StsSuite` laws, nested scope/rollback and 300,000-step cases; Conway/Shelley 10,000-transaction 256 KiB cases; reference-stack Tick/BBODY success and failure comparisons; Docker check; e2e 1,654 applications and 66 matching comparisons; [representative measurements](../performance.md#typed-rule-interpreter-202), [as built](../design/rule-interpreter-as-built.md)

## Explicitly unchecked or unconfirmed

Bounded Preview window and snapshot-derived epoch workload, not full-chain interpreter equivalence or throughput; direct Byron ledger queries unavailable in CLI 11.2.3; readiness on dev is not completion on main.
