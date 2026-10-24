<!-- SPDX-License-Identifier: Apache-2.0 -->
# Question index

Where to look first for what a contributor asks. File and symbol name a definition (search for the name; the cited file defines it); decision is the ADR, design or roadmap item that explains it; open issue lists tracked follow-ups. Checked by `python3 scripts/check_onramp.py`: every cited path and symbol exists, no line numbers. The whole tree is in [codemap.md](codemap.md); terms are in [glossary.md](glossary.md).

| Question | File and symbol | Decision | Open issue |
|---|---|---|---|
| Where is min-UTxO computed? | `TxChecks.scala`, `TxChecks.minUTxO` | [ADR 0001](adr/0001-haskell-node-as-reference.md), [roadmap B7](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) | — |
| How is a header validated before its block is fetched? | `HeaderGate.scala`, `NodeHeaderRules.scala`, `HeaderGate.forNodeChain` | [roadmap G6](roadmap.md#g-networking-p1p2) | — |
| Which clock do Plutus contexts use? | `ScriptContexts.scala`, `ScriptContexts.Environment`, `TranslationError.scala`, `TranslationError.NoClockSample` | [ADR 0003](adr/0003-plutus-through-scalus.md), [roadmap F6](roadmap.md#f-node-to-client-p1) | #123 |
| Which clock admits an incoming header by wall time? | `HeaderClock.scala`, `HeaderEligibility` | [roadmap G6](roadmap.md#g-networking-p1p2) | — |
| How are snapshots versioned? | `PersistedFormat.scala`, `FileKind`, `FormatVersion`, `SnapshotV5.scala`, `SnapshotV5` | [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md), [storage-migrations](storage-migrations.md) | — |
| How does an older persisted file migrate on open? | `Migrate.scala`, `Migrate`, `SnapshotMigration.scala`, `SnapshotMigration` | [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md), [storage-migrations](storage-migrations.md#register-atomic-migration-and-refuse-incompatible-input-first) | — |
| Where does the governor pick hot peers? | `Policy.scala`, `Policy.warmPromotion`, `Policy.hotDemotion`, `PeerSelection.scala`, `PeerSelection.decide` | [ADR 0016](adr/0016-peer-states-and-the-governor.md), [design peer-selection](design/peer-selection.md) | — |
| How are churn targets chosen? | `Churn.scala`, `Churn.steady`, `Regime.scala`, `Regime.steady` | [design peer-selection](design/peer-selection.md), [roadmap G1](roadmap.md#g-networking-p1p2) | #311 |
| Where is the default consensus mode set? | `NodeConfig.scala`, `NodeConfig.consensusMode`, `Sensitivity.scala`, `ConsensusMode` | [decisions](decisions.md) | #394 |
| How do I add a devnet scenario? | `scripts/devnet/cli.py`, `scripts/devnet/scenarios/`, `scripts/devnet.py` | [playbook](playbooks/add-a-devnet-scenario.md), [running](running.md) | — |
| How do I add a fuzz target? | `fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/Targets.scala`, `Targets.all`, `Target` | [playbook](playbooks/add-a-fuzz-target.md), [running](running.md#fuzzing-the-decoders-roadmap-k3) | — |
| How do I add a ledger predicate? | `PredicateFailure.scala`, `UtxoFailure`, `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/Utxo.scala`, `PredicateFailureCbor.scala` | [playbook](playbooks/add-a-ledger-predicate.md), [ADR 0001](adr/0001-haskell-node-as-reference.md) | #401 |
| How do I confirm a decoding rule against the reference? | `scripts/oracle/conway-decoding/`, `ConwayDecodingReferenceSuite.scala` | [playbook](playbooks/confirm-with-the-decoding-oracle.md) | — |
| Where is a transaction's Plutus phase 2 run, and how is it bounded? | `Phase2.scala`, `Phase2`, `Scheduling.scala`, `Compute`, `ScalusEvaluator.scala` | [ADR 0018](adr/0018-parallel-plutus-after-ordered-preparation.md), [design parallel-plutus](design/parallel-plutus.md) | — |
| Which builtins and versions does a Plutus language allow? | `PlutusAvailability.scala`, `PlutusAvailability` | [roadmap B9](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) | — |
| How is a script evaluated through Scalus? | `ScriptEvaluator.scala`, `ScriptEvaluator`, `ScalusEvaluator.scala`, `ScalusEvaluator.CorrectedVm` | [ADR 0003](adr/0003-plutus-through-scalus.md), [plutus-conformance](plutus-conformance.md) | #43 |
| Where are rewards calculated and paid? | `ledger/src/main/scala/xyz/kd5ujc/scarab/ledger/rules/Rewards.scala`, `ConwayPulser.scala`, `NewEpoch.scala` | [ADR 0019](adr/0019-incremental-reward-calculation.md), [design reward-pulser](design/reward-pulser.md) | — |
| How is a Conway governance action ratified? | `Ratify.scala`, `Ratify`, `Gov.scala` | [roadmap B2](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) | #344 |
| How are ledger rules written and run? | `Sts.scala`, `Sts`, `RuleT.scala`, `RuleT` | [design rule-interpreter-as-built](design/rule-interpreter-as-built.md), [ADR 0009](adr/0009-ledger-mirror-step-2.md) | — |
| How does the epoch boundary and an era change run? | `Hfc.scala`, `Hfc.tick`, `NewEpoch.scala`, `Translate.scala` | [design ledger-mirror-step2](design/ledger-mirror-step2.md) | — |
| Where is the era of a block decided? | `HardFork.scala`, `HardForkConfig`, `ForkTrigger` | [roadmap B13](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) | #342 |
| How does chain selection choose between forks? | `ChainSelection.scala`, `ChainSelection.prefers`, `BlockSelection.scala` | [roadmap B1](roadmap.md#b-unconfirmed-rules-implemented-but-not-yet-checked-against-the-reference-p1) | — |
| Where is Byron validated? | `ByronLedger.scala`, `ByronLedger` | [roadmap C1](roadmap.md#c-byron-p1-needed-for-preprod-and-mainnet) | — |
| How are bytes kept for hashing and signing? | `Cbor.scala`, `Cbor.decode`, `Document.scala`, `Document` | [ADR 0005](adr/0005-cbor-codec-and-scodec.md), [ADR 0013](adr/0013-cbor-documents-are-an-index-over-the-input.md) | — |
| How is deeply nested CBOR walked without recursion? | `TreeFold.scala`, `TreeFold` | [ADR 0011](adr/0011-stack-safety.md) | — |
| How is a decode failure located in the input? | `DecodeSite.scala`, `DecodeSite`, `DecodeError.scala`, `DecodeError` | [ADR 0002](adr/0002-tagless-final-and-cats-mtl.md) | #386 |
| How are errors carried across fibers? | `Fibers.scala`, `Fibers.scoped` | [ADR 0017](adr/0017-fibers-end-with-values.md) | — |
| How is a typed log event defined and routed? | `EventKind.scala`, `EventKind`, `Traced.scala`, `Traced`, `LogRouter.scala`, `LogRouter` | [ADR 0014](adr/0014-typed-log.md) | #233 |
| Where are the Prometheus metrics defined? | `Catalogue.scala`, `Catalogue`, `Metrics.scala`, `Metrics` | [roadmap J3](roadmap.md#j-operational-parity-p2), [running](running.md) | — |
| Where is the UTxO set stored? | `UtxoStore.scala`, `UtxoStore`, `RocksUtxoStore.scala`, `RocksUtxoStore` | [decisions](decisions.md), [roadmap D1](roadmap.md#d-scale-p1) | #231 |
| How are the last k blocks kept and rolled back? | `VolatileStore.scala`, `VolatileStore`, `VolatileChain.scala`, `VolatileChain` | [roadmap D4](roadmap.md#d-scale-p1) | — |
| When is a ledger snapshot written and restored? | `SnapshotPolicy.scala`, `SnapshotPolicy`, `LedgerSnapshots.scala`, `LedgerSnapshots` | [ADR 0006](adr/0006-versioned-formats-migrate-on-open.md) | — |
| Where is the Haskell ImmutableDB read and imported? | `HaskellImmutableReader.scala`, `HaskellImport.scala`, `HaskellImport` | [design haskell-snapshots](design/haskell-snapshots.md) | — |
| Where is SCLS import and export? | `SclsInterchange.scala`, `SclsInterchange`, `SclsRecord.scala`, `SclsRecord` | [design scls-interchange](design/scls-interchange.md), [ADR 0005](adr/0005-cbor-codec-and-scodec.md) | — |
| How does a Mithril bootstrap verify and restore? | `MithrilBootstrap.scala`, `MithrilBootstrap` | [design mithril-bootstrap](design/mithril-bootstrap.md) | #69 |
| How is the KES key evolved, written back and reloaded? | `KesFiles.scala`, `KesFiles`, `HotKeys.scala`, `HotKeys`, `Sum6KesSigning.scala` | [ADR 0015](adr/0015-kes-key-written-back-and-checked-reload.md) | #38 |
| Where is slot leadership decided when forging? | `Leadership.scala`, `Leadership`, `PraosLeaderThreshold.scala`, `PraosLeaderThreshold` | [roadmap H1](roadmap.md#h-block-production-p1p2) | #37 |
| How is the mempool bounded and fed? | `Mempool.scala`, `Mempool`, `Capacity.scala`, `Capacity`, `LocalSubmission.scala` | [roadmap F2](roadmap.md#f-node-to-client-p1) | #79 |
| How does the node answer LocalStateQuery? | `QueryAnswers.scala`, `QueryAnswers`, `NtcQuery.scala`, `NtcQuery`, `QueryVersion.scala` | [roadmap F1](roadmap.md#f-node-to-client-p1) | — |
| Which node-to-node and node-to-client versions are offered? | `ProtocolVersion.scala`, `NtnVersion`, `NtcVersion` | [roadmap G4](roadmap.md#g-networking-p1p2) | — |
| How are transaction refusals encoded for `cardano-cli`? | `PredicateFailureCbor.scala`, `PredicateFailureCbor` | [roadmap F3](roadmap.md#f-node-to-client-p1) | #401 |
| How are connections limited and recovered? | `ConnectionManager.scala`, `ConnectionManager`, `InboundGovernor.scala`, `InboundGovernor` | [roadmap G2](roadmap.md#g-networking-p1p2), [design network-limits](design/network-limits.md) | #339 |
| How are blocks requested from several peers? | `BlockFetchPolicy.scala`, `BlockFetchPolicy`, `FetchCoordinator.scala` | [design block-fetch-coordination](design/block-fetch-coordination.md) | — |
| Which Genesis rules limit a peer's rate or density? | `LimitOnPatience.scala`, `LeakyBucket.scala`, `GenesisDensity.scala`, `ChainSyncJumping.scala` | [design genesis](design/genesis.md), [roadmap G3](roadmap.md#g-networking-p1p2) | #343 |
| When does the node consider itself synced? | `Gsm.scala`, `Gsm` | [design genesis](design/genesis.md) | #343 |
| How is the BLS12-381 library chosen and checked at start-up? | `Blst.scala`, `Blst`, `CpuSupport`, `BlstCheckMain.scala` | [ADR 0012](adr/0012-portable-blst.md) | — |
| Where is the configuration and topology read? | `NodeConfig.scala`, `NodeConfig.parse`, `Topology.scala`, `Topology.read` | [roadmap J1](roadmap.md#j-operational-parity-p2) | — |
| How do I run the gates for my change? | `scripts/sbtw`, `build.sbt`, `modules` | [playbook](playbooks/run-the-gates.md), [CONTRIBUTING](../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change) | — |
