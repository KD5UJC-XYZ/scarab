# Roadmap

What remains after the v0.1.0 research preview. Item IDs (A1, B3, ...) are stable: issues and pull
requests reference them, and they are not renumbered when items are done (done items are marked, not
deleted). Priorities:

- **P0** is needed for the research preview or blocks collaborators.
- **P1** is needed for a full reference-compatible node.
- **P2** is hardening or nice-to-have.

Each open item links to its GitHub issue; the pinned [tracking issue #64](https://github.com/ottobot-ai/cardano-scala-lab/issues/64) groups them. How each area stands today is in [compatibility.md](compatibility.md).


## A. Publication and project hygiene

- **A1** (P0, done in v0.1.0) Compatibility matrix: [compatibility.md](compatibility.md).
- **A2** (P0, done in v0.1.0) Docs restructure: README, architecture, running, compatibility, performance, findings, roadmap, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CHANGELOG, issue and PR templates.
- **A3** (P0, done in v0.1.0) CI: `ci.yml` (check with dependency caching, script tests, actionlint) on every pull request; `e2e.yml` (soak, e2e, relay, pool, reference image downloaded by URL and sha256) nightly, on demand and on tags. The first `e2e.yml` run on GitHub (tag v0.1.0) passed all four scenarios. Still open: a secret scan and dependency review in CI (A8).
- **A8** [#2](https://github.com/ottobot-ai/cardano-scala-lab/issues/2) (P1) Secret scanning (gitleaks) and dependency review in CI. A manual gitleaks 8.24.3 scan of the full history before v0.1.0 found 182 generic-api-key hits, all false positives (key hashes, verification keys, SHA-256 file digests); CI needs an allowlist for those fixture paths.
- **A4** (P1, done in v0.1.0) Split `scripts/devnet.py` into a package and add Python unit tests for the pure helpers (CBOR, bech32, projections).
- **A5** [#1](https://github.com/ottobot-ai/cardano-scala-lab/issues/1) (P0, done: secret scan for v0.1.0; licence, fixture and wording review 2026-10-11) Legal and provenance review: dependency licences ([licenses.md](licenses.md), reconciled with `NOTICE`; none incompatible, borer is MPL-2.0, RocksDB is used under Apache-2.0), fixture provenance (every fixture directory with a README now states source and licence except `network`, `praos`, `sum6` and `vrf`, which remain under M9), trademark wording (checked against the Cardano Foundation Trademark Policy of 06.03.24: nominative and section 3.1 use; attribution line added; see [licenses.md](licenses.md#name-and-trademark-wording)). Open follow-up: licence texts for any binary distribution. Naming: the owner keeps "cardano" in the repository name (it names the network; the disclaimers separate the project from the official organisations); a final project name will be chosen later, together with M7.
- **A6** (P0, done in v0.1.0) GitHub repository description and topics (the old description says "not a complete validating node or block producer").
- **A7** (P0) Release: tag `v0.1.0` as a research preview The uninterrupted Preview genesis-to-tip sync is recorded (commit 5cfdefd, see [performance.md](performance.md)); release pending: the tag goes on a later hygiene/docs/CI commit with all gates re-run.

## B. Unconfirmed rules: implemented but not yet checked against the reference (P1)

- **B1** [#3](https://github.com/ottobot-ai/cardano-scala-lab/issues/3) (done) Conway VRF tie-break restriction in chain selection, confirmed against ouroboros-consensus 4.2.0.0 `comparePraos`. One deviation fixed: the opcert issue number is compared only for the same issuer **and slot**. See [findings.md](findings.md).
- **B2** [#4](https://github.com/ottobot-ai/cardano-scala-lab/issues/4) SPO default votes after the bootstrap phase. The devnet pools' reward accounts are unregistered, so this is covered by unit tests only.
- **B3** [#5](https://github.com/ottobot-ai/cardano-scala-lab/issues/5) Committee rules: committee `minSize`, expiry and term limits (unit tests only).
- **B4** [#6](https://github.com/ottobot-ai/cardano-scala-lab/issues/6) Treasury withdrawals and script DReps: withdrawals to script accounts and script-DRep voting, checked on Preview data only.
- **B5** [#7](https://github.com/ottobot-ai/cardano-scala-lab/issues/7) Protocol-11 rule changes: disjoint reference inputs and unelected committee voters were implemented from the ledger source but never exercised. Also enforce VRF key-hash uniqueness at PV11.
- **B6** [#8](https://github.com/ottobot-ai/cardano-scala-lab/issues/8) Empty-redeemers integrity hash. The `0x80` (pre-Conway) and `0xa0` (Conway) cases are unconfirmed on chain.
- **B7** [#9](https://github.com/ottobot-ai/cardano-scala-lab/issues/9) Pre-Allegra and Shelley-era details: pre-Allegra reward aggregation, MIR timing, and the Shelley–Mary min-UTxO formulas. The devnet compare covers part of this.
- **B8** [#10](https://github.com/ottobot-ai/cardano-scala-lab/issues/10) Pre-Babbage reward prefilter timing (registered at epoch start + 4k/f). Confirm it against the Haskell source.
- **B9** [#11](https://github.com/ottobot-ai/cardano-scala-lab/issues/11) Plutus builtin availability. Availability per language and protocol version is not enforced: a disallowed builtin evaluates when it should fail at decode.
- **B10** [#12](https://github.com/ottobot-ai/cardano-scala-lab/issues/12) Plutus semantics-variant C costing for `modInteger` and `remainderInteger` relies on Scalus.
- **B11** [#13](https://github.com/ottobot-ai/cardano-scala-lab/issues/13) Scalus deviations: the `verifyEd25519Signature` variant-A costing (patched in our wrapper) and the V3 `NewConstitution` context. Report both upstream to Scalus.
- **B12** [#14](https://github.com/ottobot-ai/cardano-scala-lab/issues/14) Mary output-size and value-size checks are approximations; there is no `maxValueSize` check before Alonzo.
- **B13** [#15](https://github.com/ottobot-ai/cardano-scala-lab/issues/15) (done) Hard-fork rules: envelope checks (`ObsoleteNode`, header and body size, Conway mainnet `HeaderProtVerTooHigh`) and the era of every block derived from the ledger's protocol version (`TriggerHardForkAtVersion`) unless a `Test<Era>HardForkAtEpoch` key gives an epoch (`consensus/HardFork.scala`). Byron to Shelley by version needs the Byron update state (C1) and stays a configured epoch; in bootstrap (ledger-lite) mode the Babbage to Conway version cannot be seen, so the check is relaxed there.

## C. Byron (P1, needed for preprod and mainnet)

- **C1** [#16](https://github.com/ottobot-ai/cardano-scala-lab/issues/16) Byron ledger: UTxO and transactions, including Byron txs; AVVM and non-AVVM genesis.
- **C2** [#17](https://github.com/ottobot-ai/cardano-scala-lab/issues/17) Byron consensus (PBFT): the slot-leader schedule, the delegation certificate's signature, on-chain delegation certificates, block body proofs, and light delegation.
- **C3** [#18](https://github.com/ottobot-ai/cardano-scala-lab/issues/18) Byron epoch length should be read from genesis everywhere (`Header.byronFromRaw` defaults to 21600).
- **C4** [#19](https://github.com/ottobot-ai/cardano-scala-lab/issues/19) Verification: sync preprod from genesis.

## D. Scale (P1)

- **D1** [#20](https://github.com/ottobot-ai/cardano-scala-lab/issues/20) More state on disk: accounts, pools, DReps and stake snapshots behind stores (only the UTxO is on disk today).
- **D2** [#21](https://github.com/ottobot-ai/cardano-scala-lab/issues/21) Parallel or optimised apply. The apply thread is sequential (rules, Plutus, leader threshold).
- **D3** [#22](https://github.com/ottobot-ai/cardano-scala-lab/issues/22) ChainSync pipelining. Depth is 64–128 headers per round trip, and 256 times out against the public relay; investigate.
- **D4** [#23](https://github.com/ottobot-ai/cardano-scala-lab/issues/23) Volatile blocks. The last k blocks are re-fetched after a restart rather than persisted. There is also no final snapshot on shutdown.
- **D5** [#24](https://github.com/ottobot-ai/cardano-scala-lab/issues/24) DB network identity. The DB doesn't record which network it belongs to, so pointing `--db` at the wrong network goes undetected.
- **D6** [#25](https://github.com/ottobot-ai/cardano-scala-lab/issues/25) Mainnet sync. About 12M blocks: measure throughput and memory, then optimise.
- **D7** [#26](https://github.com/ottobot-ai/cardano-scala-lab/issues/26) Ed25519 throughput. Only parallelism is used. A faster path that can only accept was correctly rejected as a security weakening, so any speedup must keep strict semantics.

## E. Fast start (P1)

- **E1** [#27](https://github.com/ottobot-ai/cardano-scala-lab/issues/27) Mithril snapshot import. Decode the Haskell ImmutableDB and the ledger snapshot format, which needs `snapshot-converter` / UTxO-HD formats.

## F. Node-to-client (P1)

- **F1** [#28](https://github.com/ottobot-ai/cardano-scala-lab/issues/28) LocalStateQuery across the full query surface. `cardano-cli`, db-sync, wallets and Ogmios depend on it.
- **F2** [#29](https://github.com/ottobot-ai/cardano-scala-lab/issues/29) LocalTxMonitor and local ChainSync.
- **F3** [#30](https://github.com/ottobot-ai/cardano-scala-lab/issues/30) ApplyTxErr encoding. It must match the ledger's predicate-failure CBOR so `cardano-cli` can render rejections; today the reason is sent as plain text.

## G. Networking (P1/P2)

- **G1** [#31](https://github.com/ottobot-ai/cardano-scala-lab/issues/31) P2P governor: root, ledger, big-ledger and bootstrap peers; churn; PeerSharing.
- **G2** [#32](https://github.com/ottobot-ai/cardano-scala-lab/issues/32) Connection management: a connection manager and inbound governor. Run our own clients on inbound duplex connections (not done today).
- **G3** [#33](https://github.com/ottobot-ai/cardano-scala-lab/issues/33) Ouroboros Genesis (GDD, LoE/LoP).
- **G4** [#34](https://github.com/ottobot-ai/cardano-scala-lab/issues/34) Protocol versions: handshake v15/v16 features; NtC versions.
- **G5** [#35](https://github.com/ottobot-ai/cardano-scala-lab/issues/35) Adversarial robustness: resource limits per peer, slow-loris and oversized-message handling, fuzzing the mini-protocol state machines.
- **G6** [#36](https://github.com/ottobot-ai/cardano-scala-lab/issues/36) Header-first validation. Headers are not validated before their blocks are fetched.

## H. Block production (P1/P2)

- **H1** [#37](https://github.com/ottobot-ai/cardano-scala-lab/issues/37) Public pool demo: a Scala-run pool on Preview (register a pool, get faucet stake, wait out the stake snapshots).
- **H2** [#38](https://github.com/ottobot-ai/cardano-scala-lab/issues/38) KES lifecycle: a KES agent, KES evolution that persists across restarts, and opcert issuing / rotation tooling.
- **H3** [#39](https://github.com/ottobot-ai/cardano-scala-lab/issues/39) TPraos forging, for historical or test eras only (low priority).
- **H4** [#40](https://github.com/ottobot-ai/cardano-scala-lab/issues/40) Mempool: epoch-boundary ticking; whether txs from rolled-back blocks should be re-added.
- **H5** [#41](https://github.com/ottobot-ai/cardano-scala-lab/issues/41) Forging-loop hardening: timing, a missed-slot metric, and slot-battle stats.

## I. Plutus (P2)

- **I1** [#42](https://github.com/ottobot-ai/cardano-scala-lab/issues/42) Conformance: run the upstream Plutus conformance suite against our `ScriptEvaluator`.
- **I2** [#43](https://github.com/ottobot-ai/cardano-scala-lab/issues/43) Own evaluator. Decide on our own CEK machine versus staying on Scalus, then differential-test it against Scalus and the conformance suite.

- **I3** [#44](https://github.com/ottobot-ai/cardano-scala-lab/issues/44) Drive Plutus V1 and V2 scripts in the devnet load (`scripts/devnet/eraload.py` TODO) so the Haskell comparator covers them; today they are verified by the Preview sync and unit/oracle tests only.

## J. Operational parity (P2)

- **J1** [#45](https://github.com/ottobot-ai/cardano-scala-lab/issues/45) Config and topology: read the Haskell node's `config.json` and `topology.json` (P2P) formats, including verifying the Shelley, Alonzo and Conway genesis hashes it lists (only Byron's is checked today).
- **J2** [#46](https://github.com/ottobot-ai/cardano-scala-lab/issues/46) CLI parity: flags compatible with `cardano-node run`.
- **J3** [#47](https://github.com/ottobot-ai/cardano-scala-lab/issues/47) Observability: Prometheus metrics, structured tracing, and EKG-equivalent counters.

## K. Verification at scale (P1)

- **K1** [#48](https://github.com/ottobot-ai/cardano-scala-lab/issues/48) db-analyser comparison. Diff our ledger against `db-analyser` ledger-state dumps at every epoch boundary on Preview, then preprod and mainnet. Today Preview only shows we don't wrongly reject valid blocks; this would show the ledger state is identical.
- **K2** [#49](https://github.com/ottobot-ai/cardano-scala-lab/issues/49) Upstream conformance suites: the cardano-ledger conformance tests, ouroboros-network tests, and the Plutus suite (I.1).
- **K3** [#50](https://github.com/ottobot-ai/cardano-scala-lab/issues/50) Fuzzing: the CBOR decoders, tx and block decoding, and the mini-protocol codecs.
- **K4** [#51](https://github.com/ottobot-ai/cardano-scala-lab/issues/51) Long-run stability: days at the tip, checking for memory growth, file handles and reconnect behaviour.
- **K5** [#52](https://github.com/ottobot-ai/cardano-scala-lab/issues/52) Single-build evidence. Keep running uninterrupted genesis-to-tip syncs on tagged builds.
- **K6** [#53](https://github.com/ottobot-ai/cardano-scala-lab/issues/53) Memory profile of the RSS growth to 5.66 GB (RocksDB block cache/off-heap vs heap) and a bounded configuration.

## L. Future eras (P2)

- **L1** [#54](https://github.com/ottobot-ai/cardano-scala-lab/issues/54) Dijkstra, the next era, is already in the 11.x configs. Track its spec.

## M. Code hygiene (from the pre-publication audit)

- **M1** [#55](https://github.com/ottobot-ai/cardano-scala-lab/issues/55) (P1, done in v0.1.0) Reject unknown `follow` flags (the parser accepts any `--name value` pair); consider a declarative parser.
- **M2** [#56](https://github.com/ottobot-ai/cardano-scala-lab/issues/56) (P1, done in v0.1.0) Remove unsafe partial calls in main code (`.toOption.get` on provably total conversions behind one helper; `Utxo.apply` and `RocksUtxoStore` should raise `StorageError.Corrupt` instead of throwing); review `Peer.scala` `raiseError` use.
- **M3** [#57](https://github.com/ottobot-ai/cardano-scala-lab/issues/57) (P1) Hide the deliberate global caches (`Verification.scala` verdict maps and pool, the `PlutusCases` recorder, the RocksDB handle reference) behind algebras so tests can isolate them.
- **M4** [#58](https://github.com/ottobot-ai/cardano-scala-lab/issues/58) (P1, partly done: fixtures moved, `CaptureMain` in `lab.node.tools`; `PlutusCases` remains) Move test-only code out of main (`FixtureChainModel`, `ChainSyncFixtures`) and move `CaptureMain` and `PlutusCases` under a `tools` package or subproject.
- **M5** [#59](https://github.com/ottobot-ai/cardano-scala-lab/issues/59) (P1, done in v0.1.0) Reformat the cryptographic Java sources (`Draft03Math`, `Ed25519Point`) for review and add SPDX headers to them, `build.sbt`, `project/plugins.sbt` and the archived fixture evidence scripts.
- **M6** [#60](https://github.com/ottobot-ai/cardano-scala-lab/issues/60) (P2) Split the largest files (`PreConwayRules`, `ConwayRules`, `ScriptContexts`, `Tx`, `Main`); fix the `NodeRules`/`LedgerRules.scala` name mismatch.
- **M7** [#61](https://github.com/ottobot-ai/cardano-scala-lab/issues/61) (P2, partly done: organisation is `io.github.ottobot-ai`) Naming: the Scala package root `lab` and sbt organisation `dev.cardano.research` imply namespaces the project does not own; decide a final project name (owner will choose one later) before any release artefact is published to a package registry.
- **M8** [#62](https://github.com/ottobot-ai/cardano-scala-lab/issues/62) (P2) Serial test execution (`Tags.limit(Test, 1)`): find which suites share global state and run modules in parallel in CI.
- **M9** [#63](https://github.com/ottobot-ai/cardano-scala-lab/issues/63) (P2) READMEs for `fixtures/{network,praos,sum6,vrf}`; Koios attribution for the data in `fixtures/preview`.
