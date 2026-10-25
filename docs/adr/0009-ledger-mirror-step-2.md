# 0009. Ledger mirror step 2: three jumps behind a differential harness

- **Status**: Accepted. Jumps 1 and 2 are done; the differential harness and the old rules this record describes were removed in J2.9 (2026-10-17), so the text below is the decision as taken, not the code as it is.
- **Partial supersession**: D8's eager reward calculation/progress-counter choice is superseded by [ADR 0019](0019-incremental-reward-calculation.md); the historical decision below is retained.
- **Date**: 2026-10-15
- **Issue**: [#82](https://github.com/KD5UJC-XYZ/scarab/issues/82)
- **Design**: [ledger-state mirror, step 2](../design/ledger-mirror-step2.md)

## Context

Step 1 built the Haskell-shaped, era-indexed state and a lossless conversion to and from the node's own
state, proven on every real state available. Step 2 moves the node and its rules onto that state. The
owner asked for larger jumps where confidence is high, with bisection if something breaks.

## Decision

Three jumps instead of about twelve stages: (1) the edges (node-to-client answers from the new codec,
snapshots as Haskell bytes plus a sidecar, migrated on open, the consensus state on the new types; no
behaviour change); (2) the rules on the era-indexed types, run side by side with the old rules by a
differential harness that names the block, stage and field of any disagreement, old rules removed only
after agreement on the eras fixture, every Preview epoch and a preprod replay; (3) the remaining gaps,
to byte-equal answers. Small commits with cheap checks inside a jump; the expensive replays once per
jump. The decisions D1 to D10 of the design document were accepted as recommended, among them: old
formats kept readable by a frozen `legacy` module (D3); per-era transaction types deferred to #85 (D5);
the pool-ranking likelihoods (floats, read only by the wallet API) matched bit for bit if feasible,
otherwise a documented gap (D9); Haskell's VRF-registry rule adopted, a verdict change confined to
jump 3 (D10).

## Consequences

- During jump 2 the node can run either rule set, or both.
- Performance budgets per jump: replay within +15%, epoch boundary within +25%, peak heap within +20%.

## Amendment (2026-10-25): D3 and the `legacy` module

D3 (old formats kept readable by a frozen `legacy` module) is superseded by the reader retirement
of [ADR 0006](0006-versioned-formats-migrate-on-open.md) (amended 2026-10-19, #83), which removed
the V1 to V4 snapshot readers from `legacy` (commit `edf8a887b`). V5 is the oldest supported
snapshot version (`FileKind.SnapshotFile.readableFrom`); V5 migrates to V7 on open
([storage-migrations.md](../storage-migrations.md)). As `FileKind.versionOf` and `FileKind.readable`
implement it, a registered version older than that raises `StorageError.RetiredVersion`, an
identifier of a snapshot family that no registered version spells raises `StorageError.UnknownVersion`,
and any other leading bytes are `StorageError.Corrupt`; `ChainStorage.preflight` applies these
before mutable stores are acquired.

`legacy` remains because the genesis start path uses it: `NodeSetup` builds the first ledger state
with `legacy.GenesisState.initial`, holds it as `legacy.FlatNodeState` and converts it to the
era-indexed state with `legacy.Mirroring`.
