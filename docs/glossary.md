<!-- SPDX-License-Identifier: Apache-2.0 -->
# Glossary

**Status: Current.** Terms that are easy to misread in issues, commits and documents, each with the
place that defines it. The definition there wins over this summary. Where code lives is in
[codemap.md](codemap.md); where to look for a common question is in [questions.md](questions.md).

## Labels and IDs

| Term | Meaning | Defined in |
|---|---|---|
| Roadmap ID | A stable item ID such as B21; a lower-case suffix is a sub-item (J3c), `-F` and a number a follow-up (G5-F1). Done items are marked, never renumbered | [roadmap.md](roadmap.md) |
| Design stage | A dotted label such as G1.4 is a stage of that item's design document, not a roadmap item | [design/peer-selection.md](design/peer-selection.md) |
| Design decision (D1 to D10) | A decision numbered inside one design document, such as step 2 D3; not the roadmap's D series | [design/ledger-mirror-step2.md](design/ledger-mirror-step2.md#1-decisions-for-the-owner-my-recommendation-first) |
| Jump; J1.5, J2.9 | One of the three steps of ledger-mirror step 2, and the stages inside a jump; not the roadmap items J1 to J3 | [ADR 0009](adr/0009-ledger-mirror-step-2.md), [design/ledger-mirror-step2.md](design/ledger-mirror-step2.md) |
| REF-nn | A reference-equivalence item of the roadmap | [roadmap.md](roadmap.md) |
| IN-nn | An implementer note: a reference behaviour or a specification gap | [implementer-notes.md](implementer-notes.md) |
| AUD-nn, AUD2-nn, AUD3-nn | A finding of an independent audit round, filed as an issue with that title prefix | [process.md](process.md#findings-from-an-independent-audit) |
| Level of change | The ladder 0 to 4 that decides a change's gates and paperwork | [CONTRIBUTING](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) |
| Status line | The line a design or reference document starts with: current, proposal, spike or historical | [process.md](process.md#documents-have-a-status) |

## State and storage

| Term | Meaning | Defined in |
|---|---|---|
| Reference | The Haskell node, at the revisions registered in `docs/data/reference-pins.json` | [ADR 0001](adr/0001-haskell-node-as-reference.md) |
| Mirror, native state | The Haskell-shaped, era-indexed ledger and consensus state the rules run on | `MirrorState`, [ADR 0007](adr/0007-era-indexed-state.md) |
| Flat state | The node's earlier state shape; in production it remains only for the genesis start path | `FlatNodeState`, [ADR 0009](adr/0009-ledger-mirror-step-2.md) |
| Gap | A named part of the mirror that its source state did not carry; it is saved with the snapshot and never filled with a default | `Gap`, [storage-migrations.md](storage-migrations.md#per-state-provenance-upgrades-129) |
| Sidecar | The typed binary part of a native snapshot that carries what the Haskell ledger-state layout does not | `SnapshotSidecar` |
| Checkpoint binding | What a snapshot records about its UTxO checkpoint: `Bound` (key layout and directory digest; restored only with that checkpoint), `Legacy` (a migrated V5 snapshot that recorded none; an existing checkpoint is admitted without a digest comparison) or `Unbacked` (the state keeps nothing outside the snapshot file) | `CheckpointBinding`, [ADR 0021](adr/0021-ledger-state-residency-and-utxo-checkpoints.md) |
| File kind | A persisted file family with its version history; `FileKind.current` is what a build writes, `FileKind.readableFrom` the oldest it reads | [PersistedFormat.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala) |
| Preflight | The read-only check of identity and supported versions before any store is opened for writing | `ChainStorage.preflight`, [storage-migrations.md](storage-migrations.md) |

## Ledger and consensus

| Term | Meaning | Defined in |
|---|---|---|
| STS | A state transition system: a rule over environment, state and signal | [Sts.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/Sts.scala) |
| HFC | The hard-fork combinator: carries the state across eras and runs the epoch boundaries a tick crosses | `Hfc.tick` |
| Mark, set, go | At each epoch boundary (SNAP) the new stake distribution becomes `mark`, the previous `mark` becomes `set` and the previous `set` becomes `go` | [Snap.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/Snap.scala) |
| Pulser | The incremental reward calculation, advanced at the reference's pulse and force points | [ADR 0019](adr/0019-incremental-reward-calculation.md) |
| Phase 1, phase 2 | Structural ledger validation, then Plutus script execution | [Phase2.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Phase2.scala) |
| Ledger-lite | The part of the pre-Conway ledger that header validation needs, kept without a UTxO set in the bootstrap follow mode | `LiteLedger` |
| Header-first | Validating the headers of a ChainSync batch before fetching any of its blocks | `HeaderGate.forNodeChain` |

## Network

| Term | Meaning | Defined in |
|---|---|---|
| NtN, NtC | Node-to-node and node-to-client protocol versions | [ProtocolVersion.scala](../protocol/src/main/scala/xyz/kd5ujc/scarab/protocol/ProtocolVersion.scala) |
| Governor | The pure peer-selection decision function over cold, warm and hot peers | [ADR 0016](adr/0016-peer-states-and-the-governor.md) |
| GSM | The Genesis state machine: PreSyncing, Syncing, CaughtUp | [Gsm.scala](../network/src/main/scala/xyz/kd5ujc/scarab/network/selection/Gsm.scala) |
| LoE, GDD, LoP, CSJ | Limit on eagerness, Genesis density disconnection, limit on patience, ChainSync jumping | [design/genesis.md](design/genesis.md#mechanisms-and-resource-policy) |

## Verification

| Term | Meaning | Defined in |
|---|---|---|
| Oracle | A tool that runs unchanged upstream code to produce expected results for comparison | [scripts/oracle](../scripts/oracle) |
| Scenario | One devnet run with its own checks; each exits non-zero on a mismatch | [running.md](running.md#devnet-scenarios-scriptsdevnetpy) |
