<!-- SPDX-License-Identifier: Apache-2.0 -->
# Persistence: typed codec derivation (#205)

Part of the [compatibility matrix](../compatibility.md); the register entry is `persistence-codec-derivation` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: compiler-generated typed field accessors, typed result tuples and root-relative subtype-checked cases; no Codec[Any] or runtime ClassTag field recovery

## Evidence

format/fingerprint, migration, Haskell-format and snapshot goldens; meaningful generic/phantom compile probes with positive controls; nested field failures; full representative V5 encode/decode byte/state/refusal parity; extended 30.8M-case decoder fuzz; successful private restart ([as built](../design/codec-derivation-as-built.md), [measurements](../performance/cast-erasure-decode-and-diff.md#35-typed-snapshot-codec-derivation-205))

## Explicitly unchecked or unconfirmed

Bounded current and legacy-derived maintained V5 snapshots, no raw retired-V4 throughput or database-restore claim. Tiny Record encode is +17.72% at about6.1 ns/call, tracked by [#257](https://github.com/KD5UJC-XYZ/scarab/issues/257); no universal speed-up claim. Separate decode interning and Diff/ordering obligations remain #204/#206/#255.
