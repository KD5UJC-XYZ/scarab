# Performance

**Status**: Historical records of measurements, each with its build and setup; the newest sections are the current numbers. Classes and flags named in older sections (`--rules`, `--mirror-check`, the differential harness, `NodeRules.tick`, `PreConwayRules`, `ConwayRules`) were removed in J2.9 and are kept as they were measured.

Measurements of syncing the public Preview testnet, what the profiles showed and what changed.
These are one developer's measurements on one machine, not a benchmark suite; they are not
reproducible bit for bit, but each section names its build and setup.

Setup (all runs): multi-core host, Docker, JDK 21 Temurin, Preview via
`preview-node.play.dev.cardano.org:3001`. The node container had `--cpus 4` unless stated;
`-Xmx6g` before and `-Xmx3g` after the changes below. Profiles are JFR (`settings=profile`) taken
from the running node and aggregated over `jdk.ExecutionSample`; JFR samples a few threads per
interval, so shares below are shares of samples.

## Log

One row per measurement section, in the order the sections were written; the section number is the one other documents and code comments cite.

<!-- register:log -->
| Section | Date | Subject | Issues | Page |
|---|---|---|---|---|
| 1 | 2026-10-11 | Baseline (commit ef68faf): where the time went |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#1-baseline-commit-ef68faf-where-the-time-went) |
| 2 | 2026-10-11 | What changed, in the order it paid off |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#2-what-changed-in-the-order-it-paid-off) |
| 3 | 2026-10-11 | Results |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#3-results) |
| 4 | 2026-10-11 | State at the tip (block 4,747,639, epoch 1447, protocol 11) |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#4-state-at-the-tip-block-4747639-epoch-1447-protocol-11) |
| 5 | 2026-10-11 | Known gaps |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#5-known-gaps) |
|  | 2026-10-11 | Preprod from genesis (C4) |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#preprod-from-genesis-c4) |
| 6 | 2026-10-12 | Restart and scheduling (D4, D8) |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#6-restart-and-scheduling-d4-d8) |
| 7 | 2026-10-14 | ChainSync pipelining depth (D3) and header-first validation (G6) |  | [Preview sync, restart and scheduling](performance/sync-and-scheduling.md#7-chainsync-pipelining-depth-d3-and-header-first-validation-g6) |
| 9 | 2026-10-15 | The differential harness: what the conversion costs (J2.2) |  | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#9-the-differential-harness-what-the-conversion-costs-j22) |
| 8 | 2026-10-15 | Memory (K6) |  | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#8-memory-k6) |
| 10 | 2026-10-16 | The tick of every era: new rules against the old (J2.3) |  | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#10-the-tick-of-every-era-new-rules-against-the-old-j23) |
| 11 | 2026-10-16 | The blocks of Conway: the new rules against the old (J2.4) |  | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#11-the-blocks-of-conway-the-new-rules-against-the-old-j24) |
| 12 | 2026-10-16 | Decoding a snapshot: the heap of the epoch-188 state (#94, stages 2 and 3) | #94 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#12-decoding-a-snapshot-the-heap-of-the-epoch-188-state-94-stages-2-and-3) |
| 13 | 2026-10-17 | Live sync against replay on mainnet (#124) | #124 | [The mainnet follower and signature checks](performance/mainnet-follower.md#13-live-sync-against-replay-on-mainnet-124) |
| 14 | 2026-10-17 | Parallel signature checks within a batch (#125) | #125 | [The mainnet follower and signature checks](performance/mainnet-follower.md#14-parallel-signature-checks-within-a-batch-125) |
| 15 | 2026-10-17 | The epoch boundary of the new rules against the old, on the largest real state (J2.9 step 0) |  | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#15-the-epoch-boundary-of-the-new-rules-against-the-old-on-the-largest-real-state-j29-step-0) |
| 16 | 2026-10-17 | The epoch boundary of the era-indexed rules made fast (#131) | #131 | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#16-the-epoch-boundary-of-the-era-indexed-rules-made-fast-131) |
| 17 | 2026-10-17 | Per-block ledger application in late Alonzo (#133) | #133 | [Era rules: the differential harness and the epoch boundary](performance/era-rules.md#17-per-block-ledger-application-in-late-alonzo-133) |
| 18 | 2026-10-17 | The cost of an extra hot session (G1.1) |  | [Peers, connections and the network path](performance/peers-and-network.md#18-the-cost-of-an-extra-hot-session-g11) |
| 19 | 2026-10-17 | The mainnet follower at 8.7 blocks a second: signatures, a slow window, and the weight of the chain (#147) | #147 | [The mainnet follower and signature checks](performance/mainnet-follower.md#19-the-mainnet-follower-at-87-blocks-a-second-signatures-a-slow-window-and-the-weight-of-the-chain-147) |
| 20 | 2026-10-17 | The cost of the peer metrics callbacks (G1.2) |  | [Peers, connections and the network path](performance/peers-and-network.md#20-the-cost-of-the-peer-metrics-callbacks-g12) |
| 21 | 2026-10-17 | The MemPack `TxOut` against the stored CBOR value (#143, Step D stage S1) | #143 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#21-the-mempack-txout-against-the-stored-cbor-value-143-step-d-stage-s1) |
| 22 | 2026-10-17 | Channel ingress and fiber boundaries (#132, #154) | #132, #154 | [Peers, connections and the network path](performance/peers-and-network.md#22-channel-ingress-and-fiber-boundaries-132-154) |
| 23 | 2026-10-17 | Ledger peers at the Preview tip (G1.3b, #137) | #137 | [Peers, connections and the network path](performance/peers-and-network.md#23-ledger-peers-at-the-preview-tip-g13b-137) |
| 24 | 2026-10-18 | Authenticated offline preprod ledger import (Step D S3, #140) | #140 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#24-authenticated-offline-preprod-ledger-import-step-d-s3-140) |
| 25 | 2026-10-18 | Offline preprod ledger export (Step D S4, #140) | #140 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#25-offline-preprod-ledger-export-step-d-s4-140) |
| 26 | 2026-10-18 | Recorded preprod snapshot replay (Step D S5, #140) | #140 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#26-recorded-preprod-snapshot-replay-step-d-s5-140) |
| 27 | 2026-10-18 | Certified Preview bootstrap (#27) | #27 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#27-certified-preview-bootstrap-27) |
| 28 | 2026-10-18 | Acquired LocalStateQuery UTxO view (#158) | #158 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#28-acquired-localstatequery-utxo-view-158) |
|  | 2026-10-18 | Bounded parallel Plutus after ordered preparation (D2, 2026-10-18) |  | [Rewards, parallel Plutus and script decoding](performance/rewards-and-evaluation.md#bounded-parallel-plutus-after-ordered-preparation-d2-2026-10-18) |
| 29 | 2026-10-19 | Reward-update shaping (#135) | #135 | [Rewards, parallel Plutus and script decoding](performance/rewards-and-evaluation.md#29-reward-update-shaping-135) |
| 30 | 2026-10-19 | Incremental reward implementation (#135) | #135 | [Rewards, parallel Plutus and script decoding](performance/rewards-and-evaluation.md#30-incremental-reward-implementation-135) |
| 31 | 2026-10-19 | Live-sync investigation closeout (#124) | #124 | [The mainnet follower and signature checks](performance/mainnet-follower.md#31-live-sync-investigation-closeout-124) |
| 32 | 2026-10-19 | Timing probe for the crypto math (#234) | #234 | [The mainnet follower and signature checks](performance/mainnet-follower.md#32-timing-probe-for-the-crypto-math-234) |
| 33 | 2026-10-19 | Preview acceptance evidence reconciliation (#21) | #21 | [Preview acceptance evidence and native likelihoods](performance/preview-acceptance.md#33-preview-acceptance-evidence-reconciliation-21) |
|  | 2026-10-20 | Typed Diff instance retention (#206) | #206 | [Typed decode interning, Diff retention and snapshot codec derivation](performance/cast-erasure-decode-and-diff.md#typed-diff-instance-retention-206) |
| 34 | 2026-10-20 | Typed per-kind decode interning (#204) | #204 | [Typed decode interning, Diff retention and snapshot codec derivation](performance/cast-erasure-decode-and-diff.md#34-typed-per-kind-decode-interning-204) |
|  | 2026-10-20 | Typed rule interpreter (#202) | #202 | [The typed rule interpreter and the cast-erasure batch](performance/cast-erasure-interpreter.md#typed-rule-interpreter-202) |
| 35 | 2026-10-20 | Typed snapshot codec derivation (#205) | #205 | [Typed decode interning, Diff retention and snapshot codec derivation](performance/cast-erasure-decode-and-diff.md#35-typed-snapshot-codec-derivation-205) |
|  | 2026-10-20 | The cast-erasure batch (#202–#207): the old code against the new, on one harness | #202–#207 | [The typed rule interpreter and the cast-erasure batch](performance/cast-erasure-interpreter.md#the-cast-erasure-batch-202207-the-old-code-against-the-new-on-one-harness) |
| 36 | 2026-10-20 | Native non-myopic likelihood generation (#268) | #268 | [Preview acceptance evidence and native likelihoods](performance/preview-acceptance.md#36-native-non-myopic-likelihood-generation-268) |
| 37 | 2026-10-20 | Coordinated BlockFetch service model (#148) | #148 | [Peers, connections and the network path](performance/peers-and-network.md#37-coordinated-blockfetch-service-model-148) |
| 38 | 2026-10-21 | Genesis integration observations (#33) | #33 | [Peers, connections and the network path](performance/peers-and-network.md#38-genesis-integration-observations-33) |
| 39 | 2026-10-21 | Received-forest selection and idle decisions (#309 review) | #309 | [Peers, connections and the network path](performance/peers-and-network.md#39-received-forest-selection-and-idle-decisions-309-review) |
| 23 | 2026-10-23 | Mux receive buffer (#356) | #356 | [Peers, connections and the network path](performance/peers-and-network.md#23-mux-receive-buffer-356) |
| 40 | 2026-10-25 | Flat script decoding: an imperative walker against a functional one |  | [Rewards, parallel Plutus and script decoding](performance/rewards-and-evaluation.md#40-flat-script-decoding-an-imperative-walker-against-a-functional-one) |
| 41 | 2026-10-25 | Mux stream decoder partial frames |  | [Peers, connections and the network path](performance/peers-and-network.md#41-mux-stream-decoder-partial-frames) |
| 42 | 2026-10-24 | The judgment in the rule interpreter (#412, foundation) | #412, #420 | [The typed rule interpreter and the cast-erasure batch](performance/cast-erasure-interpreter.md#42-the-judgment-in-the-rule-interpreter-412-foundation) |
| 43 | 2026-10-26 | Incremental injection numeric coefficients (#499) | #499 | [Incremental injection numeric coefficients](performance/injection-coefficients.md#43-incremental-injection-numeric-coefficients-499) |
| 44 | 2026-10-25 | The reference's budget arithmetic in the evaluator (#485) | #485 | [The reference's budget arithmetic in the evaluator](performance/plutus-budget-arithmetic.md#44-the-references-budget-arithmetic-in-the-evaluator-485) |
| 45 | 2026-10-31 | The CBOR walk of the mini-protocol reader: functional against imperative (#657) | #657 | [Peers, connections and the network path](performance/peers-and-network.md#45-the-cbor-walk-of-the-mini-protocol-reader-functional-against-imperative-657) |
| 46 | 2026-10-31 | The UTxO store's scans: a fold against the loops (#650) | #650 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#46-the-utxo-stores-scans-a-fold-against-the-loops-650) |
| 47 | 2026-10-31 | The collections of the Haskell state: builders against immutable forms (#650) | #650 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#47-the-collections-of-the-haskell-state-builders-against-immutable-forms-650) |
| 48 | 2026-10-31 | The CBOR decoder: functional against imperative (#650) | #650 | [Peers, connections and the network path](performance/peers-and-network.md#48-the-cbor-decoder-functional-against-imperative-650) |
| 49 | 2026-10-31 | Reading a decoded document: functional against imperative navigation, sharing and caching (#650) | #650 | [Memory, snapshots and bootstrap](performance/memory-and-snapshots.md#49-reading-a-decoded-document-functional-against-imperative-navigation-sharing-and-caching-650) |
<!-- /register -->
