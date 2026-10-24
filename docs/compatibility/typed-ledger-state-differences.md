<!-- SPDX-License-Identifier: Apache-2.0 -->
# Typed ledger-state differences (#206)

Part of the [compatibility matrix](../compatibility.md); the register entry is `typed-ledger-state-differences` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: field and case comparisons use their declared types; instances are retained lazily per derivation, products stop at the first differing field and enums dispatch by ordinal

## Evidence

Conditional-given construction regressions (including identity/short-circuit/per-derivation checks), recursive-enum paths and existing generated era-state properties; original/typed/candidate model fingerprints match, with setup and measured costs in [performance.md](../performance/cast-erasure-decode-and-diff.md#typed-diff-instance-retention-206); final source and area-gate evidence below

## Explicitly unchecked or unconfirmed

Diagnostic comparisons only; the model does not establish public-chain state parity. Equal-state temporary allocation remains 3.3–17.6% above the original cast-based implementation; persisted formats and ledger predicates are unchanged.
