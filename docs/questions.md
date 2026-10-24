<!-- SPDX-License-Identifier: Apache-2.0 -->
# Question index

**Status: Current.** Where to start reading for questions that a module listing does not answer
directly. Each row names the defining file and symbol and the record that explains it; the code and
that record are authoritative. Modules and packages are in [codemap.md](codemap.md); terms are in
[glossary.md](glossary.md).

## Where is it?

| Question | Start at | Record |
|---|---|---|
| Where is min-UTxO computed? | `TxChecks.minUTxO` in [TxChecks.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/TxChecks.scala) | [roadmap B](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) |
| Where are the typed ledger failures and their wire encoding? | [PredicateFailure.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PredicateFailure.scala), [PredicateFailureCbor.scala](../node/src/main/scala/xyz/kd5ujc/scarab/node/ntc/PredicateFailureCbor.scala) | [roadmap F](roadmap.md#f-node-to-client-p1) |
| How is a header validated before its block is fetched? | `HeaderGate.forNodeChain` in [HeaderGate.scala](../node/src/main/scala/xyz/kd5ujc/scarab/node/HeaderGate.scala) | [design/genesis.md](design/genesis.md#shared-early-header-validation-318) |
| Which clock admits a header by wall time? | `HeaderEligibility` in [HeaderClock.scala](../node/src/main/scala/xyz/kd5ujc/scarab/node/HeaderClock.scala) | [roadmap G](roadmap.md#g-networking-p1p2) |
| How is a slot turned into POSIX time for a script context? | `ScriptContexts.Environment` (its `posix`) in [ScriptContexts.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/ScriptContexts.scala) | [ADR 0003](adr/0003-plutus-through-scalus.md) |
| Where is phase 2 run, and how is it bounded? | [Phase2.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/Phase2.scala) | [ADR 0018](adr/0018-parallel-plutus-after-ordered-preparation.md) |
| Which corrections does the Plutus wrapper apply? | `ScalusEvaluator.CorrectedVm` in [ScalusEvaluator.scala](../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/ScalusEvaluator.scala) | [ADR 0003](adr/0003-plutus-through-scalus.md), [plutus-conformance.md](plutus-conformance.md) |
| Which builtins does a Plutus language version allow? | [PlutusAvailability.scala](../plutus/src/main/scala/xyz/kd5ujc/scarab/plutus/PlutusAvailability.scala) | [roadmap B](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) |
| How do the epoch boundary and an era change run? | `Hfc.tick` in [Hfc.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/Hfc.scala) | [design/ledger-mirror-step2.md](design/ledger-mirror-step2.md) |
| How are snapshots versioned and migrated? | `FileKind` in [PersistedFormat.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/PersistedFormat.scala), [SnapshotV7.scala](../storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotV7.scala), [SnapshotMigration.scala](../storage/src/main/scala/xyz/kd5ujc/scarab/storage/SnapshotMigration.scala) | [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md), [storage-migrations.md](storage-migrations.md#worked-example-snapshot-v5-to-v7) |
| Why does the `legacy` module still exist? | `NodeSetup` and `legacy.GenesisState.initial` | [ADR 0009](adr/0009-ledger-mirror-step-2.md) (amendment) |
| Where is the consensus mode chosen? | `ConsensusMode` in [Sensitivity.scala](../network/src/main/scala/xyz/kd5ujc/scarab/network/selection/Sensitivity.scala), `NodeConfig.consensusMode` | [ADR 0022](adr/0022-defaults-follow-the-reference-binary.md) |
| Where does the governor promote and demote peers? | `PeerSelection.decide`, `Policy.warmPromotion`, `Policy.hotDemotion` in [selection](../network/src/main/scala/xyz/kd5ujc/scarab/network/selection) | [ADR 0016](adr/0016-peer-states-and-the-governor.md) |
| How does a fiber hand an error to its owner? | `Fibers.scoped` in [Fibers.scala](../network/src/main/scala/xyz/kd5ujc/scarab/network/Fibers.scala) | [ADR 0017](adr/0017-fibers-end-with-values.md) |
| How does the forger end on a failure? | `Forger.scoped`, `BlockBuilder.select`, `HotKeys` in [forge](../node/src/main/scala/xyz/kd5ujc/scarab/node/forge) | [ADR 0015](adr/0015-kes-key-written-back-and-checked-reload.md) (amendment) |
| How is deeply nested CBOR walked without recursion? | [TreeFold.scala](../codec/src/main/scala/xyz/kd5ujc/scarab/codec/TreeFold.scala) | [ADR 0011](adr/0011-stack-safety.md) |
| Where is a decode failure located in the input? | [DecodeSite.scala](../ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/DecodeSite.scala) | [design/domain-errors.md](design/domain-errors.md) |
| Where are the fuzz targets registered? | `Targets.all` in [Targets.scala](../fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala) | [running.md](running/fuzzing.md#fuzzing-the-decoders-roadmap-k3) |
| Where are devnet scenarios registered? | [scripts/devnet/cli.py](../scripts/devnet/cli.py), [scripts/devnet/scenarios](../scripts/devnet/scenarios) | [running.md](running/devnet.md#devnet-scenarios-scriptsdevnetpy) |

## Decisions recorded outside an ADR

Some decisions live in a roadmap item or a design document rather than an ADR. The record is
authoritative, including what would reopen the decision.

| Decision | Record |
|---|---|
| Accounts, pools, DReps and stake snapshots stay in memory; only the UTxO set is behind a store | [roadmap D1](roadmap.md#d-scale-p1) |
| The one-CPU scheduling limitation is accepted; no hard real-time guarantee is claimed | [roadmap D8](roadmap.md#d-scale-p1), [performance.md](performance/sync-and-scheduling.md#scheduling-d8) |
| Stored UTxO values stay CBOR; MemPack is the import and export format | [roadmap D16](roadmap.md#d-scale-p1), [design/haskell-snapshots.md](design/haskell-snapshots.md) |
| No Byron (PBFT) block production; reopened only if a live network again requires it | [roadmap H7](roadmap.md#h-block-production-p1p2) |
