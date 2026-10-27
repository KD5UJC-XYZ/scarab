<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: signed Coin and compact arithmetic (#492)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-coin-arithmetic` in [compatibility.json](../data/compatibility.json).

## Status as recorded

`Coin` represents a signed, arbitrary-precision integer, as cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` defines it in `Coin.hs:93–113`.
Construction and native pot arithmetic do not add sign checks. Existing Coin CBOR readers
still require Word64; compact conversion still checks the Word64 range. Writers and persisted
format versions are unchanged. Withdrawals carry Coin amounts at the decoded/native API
boundary, retaining the unique-key map, explicit consumer ordering and original body bytes.

Compact account additions and subtotals wrap modulo 2^64. MIR combines Coin amounts before
compaction; reward delivery compacts each reward before grouping. POOLREAP combines compact
refund subtotals before subtracting from deposited Coin. Account, pool and DRep obligation
subtotals wrap separately before their Coin sum. The reference boundaries are
`State/Account.hs:119–147`, `Shelley/Rules/Mir.hs:122–135`,
`Shelley/Rules/PoolReap.hs:179–221`, `Shelley/Rewards.hs:205–210` and
`Conway/Rules/Epoch.hs:181–240`.

## Evidence

`VocabularySuite` checks signed Coin construction and compact bounds. `HaskellStateCodecSuite`
checks the exact negative Coin encoding, unsigned Word64 decoding and refusal of out-of-range
values. The native mirror regression retains a negative treasury through conversion; it is not
a claim that negative Coin CBOR decodes successfully.

`CompactCoinRulesSuite` covers account additions/subtotals, sparse/dense credit, MIR union before
compaction, per-reward conversion and pool-refund boundaries. Withdrawal map tests preserve
last-wins/duplicate refusal, original body bytes and V1/V2 versus V3 context behavior with Coin
amounts. These are scoped source-derived checks, not complete native arithmetic comparisons or
whole-ledger equivalence. Current-head integration gates remain required before readiness.

## Explicitly unchecked or unconfirmed

Compact stake folds, wrapped-zero stake retention, pool/owner and DRep aggregation, and the
complete native arithmetic corpus remain under [#492](https://github.com/KD5UJC-XYZ/scarab/issues/492).
This entry does not claim those pending operations match the reference.

The accumulated MIR negative-update state regression requires the rule interpreter integration
under [#411](https://github.com/KD5UJC-XYZ/scarab/pull/411); the predecessor interpreter remains
fail-fast. Compact-conversion defect classification is separately tracked by
[#483](https://github.com/KD5UJC-XYZ/scarab/pull/483). The remaining reward-account payload
decoding alignment belongs to [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477).
The native genesis initial-funds regression for signed reserves is part of
[#502](https://github.com/KD5UJC-XYZ/scarab/pull/502).
