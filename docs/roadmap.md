# Roadmap

What remains after the v0.1.0 research preview. Item IDs (A1, B3, ...) are stable: issues and pull
requests reference them, and they are not renumbered when items are done (done items are marked, not
deleted). Priorities:

- **P0** is needed for the research preview or blocks collaborators.
- **P1** is needed for a full reference-compatible node.
- **P2** is hardening or nice-to-have.

How each area stands today is in [compatibility.md](compatibility.md).


## A. Publication and project hygiene

- **A1** (P0, done in v0.1.0) Compatibility matrix: [compatibility.md](compatibility.md).
- **A2** (P0, done in v0.1.0) Docs restructure: README, architecture, running, compatibility, performance, findings, roadmap, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CHANGELOG, issue and PR templates.
- **A3** (P0, done in v0.1.0) CI: `ci.yml` (check with dependency caching, script tests, actionlint) on every pull request; `e2e.yml` (soak, e2e, relay, pool, reference image downloaded by URL and sha256) nightly, on demand and on tags. Still open: a secret scan and dependency review in CI (A8), and a first green `e2e.yml` run on GitHub.
- **A8** (P1) Secret scanning (gitleaks) and dependency review in CI. A manual gitleaks 8.24.3 scan of the full history before v0.1.0 found 182 generic-api-key hits, all false positives (key hashes, verification keys, SHA-256 file digests); CI needs an allowlist for those fixture paths.
- **A4** (P1, done in v0.1.0) Split `scripts/devnet.py` into a package and add Python unit tests for the pure helpers (CBOR, bech32, projections).
- **A5** (P0, secret scan done for v0.1.0) Legal and provenance review: dependency licences (listed in `NOTICE`), fixture provenance, a secret scan over the full history before pushing it, trademark wording.
- **A6** (P0) GitHub repository description and topics (the old description says "not a complete validating node or block producer").
- **A7** (P0) Release: tag `v0.1.0` as a research preview once the uninterrupted Preview genesis-to-tip sync on the tagged build is recorded (see the placeholder in the README and [performance.md](performance.md)).

## B. Unconfirmed rules: implemented but not yet checked against the reference (P1)

- **B1** Conway VRF tie-break restriction in chain selection. It was written from memory; check it against ouroboros-consensus.
- **B2** SPO default votes after the bootstrap phase. The devnet pools' reward accounts are unregistered, so this is covered by unit tests only.
- **B3** Committee rules: committee `minSize`, expiry and term limits (unit tests only).
- **B4** Treasury withdrawals and script DReps: withdrawals to script accounts and script-DRep voting, checked on Preview data only.
- **B5** Protocol-11 rule changes: disjoint reference inputs and unelected committee voters were implemented from the ledger source but never exercised. Also enforce VRF key-hash uniqueness at PV11.
- **B6** Empty-redeemers integrity hash. The `0x80` (pre-Conway) and `0xa0` (Conway) cases are unconfirmed on chain.
- **B7** Pre-Allegra and Shelley-era details: pre-Allegra reward aggregation, MIR timing, and the Shelley–Mary min-UTxO formulas. The devnet compare covers part of this.
- **B8** Pre-Babbage reward prefilter timing (registered at epoch start + 4k/f). Confirm it against the Haskell source.
- **B9** Plutus builtin availability. Availability per language and protocol version is not enforced: a disallowed builtin evaluates when it should fail at decode.
- **B10** Plutus semantics-variant C costing for `modInteger` and `remainderInteger` relies on Scalus.
- **B11** Scalus deviations: the `verifyEd25519Signature` variant-A costing (patched in our wrapper) and the V3 `NewConstitution` context. Report both upstream to Scalus.
- **B12** Mary output-size and value-size checks are approximations; there is no `maxValueSize` check before Alonzo.
- **B13** Hard-fork rules: header protocol-version checks and exact hard-fork timing are not enforced. Hard forks decided on chain by update proposals (mainnet-style) are not modelled in the era history.

## C. Byron (P1, needed for preprod and mainnet)

- **C1** Byron ledger: UTxO and transactions, including Byron txs; AVVM and non-AVVM genesis.
- **C2** Byron consensus (PBFT): the slot-leader schedule, the delegation certificate's signature, on-chain delegation certificates, block body proofs, and light delegation.
- **C3** Byron epoch length should be read from genesis everywhere (`Header.byronFromRaw` defaults to 21600).
- **C4** Verification: sync preprod from genesis.

## D. Scale (P1)

- **D1** More state on disk: accounts, pools, DReps and stake snapshots behind stores (only the UTxO is on disk today).
- **D2** Parallel or optimised apply. The apply thread is sequential (rules, Plutus, leader threshold).
- **D3** ChainSync pipelining. Depth is 64–128 headers per round trip, and 256 times out against the public relay; investigate.
- **D4** Volatile blocks. The last k blocks are re-fetched after a restart rather than persisted. There is also no final snapshot on shutdown.
- **D5** DB network identity. The DB doesn't record which network it belongs to, so pointing `--db` at the wrong network goes undetected.
- **D6** Mainnet sync. About 12M blocks: measure throughput and memory, then optimise.
- **D7** Ed25519 throughput. Only parallelism is used. A faster path that can only accept was correctly rejected as a security weakening, so any speedup must keep strict semantics.

## E. Fast start (P1)

- **E1** Mithril snapshot import. Decode the Haskell ImmutableDB and the ledger snapshot format, which needs `snapshot-converter` / UTxO-HD formats.

## F. Node-to-client (P1)

- **F1** LocalStateQuery across the full query surface. `cardano-cli`, db-sync, wallets and Ogmios depend on it.
- **F2** LocalTxMonitor and local ChainSync.
- **F3** ApplyTxErr encoding. It must match the ledger's predicate-failure CBOR so `cardano-cli` can render rejections; today the reason is sent as plain text.

## G. Networking (P1/P2)

- **G1** P2P governor: root, ledger, big-ledger and bootstrap peers; churn; PeerSharing.
- **G2** Connection management: a connection manager and inbound governor. Run our own clients on inbound duplex connections (not done today).
- **G3** Ouroboros Genesis (GDD, LoE/LoP).
- **G4** Protocol versions: handshake v15/v16 features; NtC versions.
- **G5** Adversarial robustness: resource limits per peer, slow-loris and oversized-message handling, fuzzing the mini-protocol state machines.
- **G6** Header-first validation. Headers are not validated before their blocks are fetched.

## H. Block production (P1/P2)

- **H1** Public pool demo: a Scala-run pool on Preview (register a pool, get faucet stake, wait out the stake snapshots).
- **H2** KES lifecycle: a KES agent, KES evolution that persists across restarts, and opcert issuing / rotation tooling.
- **H3** TPraos forging, for historical or test eras only (low priority).
- **H4** Mempool: epoch-boundary ticking; whether txs from rolled-back blocks should be re-added.
- **H5** Forging-loop hardening: timing, a missed-slot metric, and slot-battle stats.

## I. Plutus (P2)

- **I1** Conformance: run the upstream Plutus conformance suite against our `ScriptEvaluator`.
- **I2** Own evaluator. Decide on our own CEK machine versus staying on Scalus, then differential-test it against Scalus and the conformance suite.

- **I3** Drive Plutus V1 and V2 scripts in the devnet load (`scripts/devnet/eraload.py` TODO) so the Haskell comparator covers them; today they are verified by the Preview sync and unit/oracle tests only.

## J. Operational parity (P2)

- **J1** Config and topology: read the Haskell node's `config.json` and `topology.json` (P2P) formats, including verifying the Shelley, Alonzo and Conway genesis hashes it lists (only Byron's is checked today).
- **J2** CLI parity: flags compatible with `cardano-node run`.
- **J3** Observability: Prometheus metrics, structured tracing, and EKG-equivalent counters.

## K. Verification at scale (P1)

- **K1** db-analyser comparison. Diff our ledger against `db-analyser` ledger-state dumps at every epoch boundary on Preview, then preprod and mainnet. Today Preview only shows we don't wrongly reject valid blocks; this would show the ledger state is identical.
- **K2** Upstream conformance suites: the cardano-ledger conformance tests, ouroboros-network tests, and the Plutus suite (I.1).
- **K3** Fuzzing: the CBOR decoders, tx and block decoding, and the mini-protocol codecs.
- **K4** Long-run stability: days at the tip, checking for memory growth, file handles and reconnect behaviour.
- **K5** Single-build evidence. Keep running uninterrupted genesis-to-tip syncs on tagged builds.

## L. Future eras (P2)

- **L1** Dijkstra, the next era, is already in the 11.x configs. Track its spec.

## M. Code hygiene (from the pre-publication audit)

- **M1** (P1) Reject unknown `follow` flags (the parser accepts any `--name value` pair); consider a declarative parser.
- **M2** (P1) Remove unsafe partial calls in main code (`.toOption.get` on provably total conversions behind one helper; `Utxo.apply` and `RocksUtxoStore` should raise `StorageError.Corrupt` instead of throwing); review `Peer.scala` `raiseError` use.
- **M3** (P1) Hide the deliberate global caches (`Verification.scala` verdict maps and pool, the `PlutusCases` recorder, the RocksDB handle reference) behind algebras so tests can isolate them.
- **M4** (P1) Move test-only code out of main (`FixtureChainModel`, `ChainSyncFixtures`) and move `CaptureMain` and `PlutusCases` under a `tools` package or subproject.
- **M5** (P1) Reformat the cryptographic Java sources (`Draft03Math`, `Ed25519Point`) for review and add SPDX headers to them, `build.sbt`, `project/plugins.sbt` and the archived fixture evidence scripts.
- **M6** (P2) Split the largest files (`PreConwayRules`, `ConwayRules`, `ScriptContexts`, `Tx`, `Main`); fix the `NodeRules`/`LedgerRules.scala` name mismatch.
- **M7** (P2) Naming: the Scala package root `lab` and sbt organisation `dev.cardano.research` imply namespaces the project does not own; decide a final name before the first release artefact is published.
- **M8** (P2) Serial test execution (`Tags.limit(Test, 1)`): find which suites share global state and run modules in parallel in CI.
- **M9** (P2) READMEs for `fixtures/{network,praos,sum6,vrf}`; Koios attribution for the data in `fixtures/preview`.
