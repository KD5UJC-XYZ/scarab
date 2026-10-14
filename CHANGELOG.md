# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0 research
software; versions mark milestones, not stability. Commit hashes refer to this repository's history.

## [Unreleased]

### Memory and storage (K6, D1)
- K6: the RSS growth to 5.66 GB on a Preview sync was glibc's per-thread malloc arenas. `docker/jdk.Dockerfile`
  and the devnet node set `MALLOC_ARENA_MAX=2`; running.md says to set it outside a container. With it
  `RssAnon` was the heap limit plus 0.8 GB (3.86 GB with `-Xmx3g` after 45 minutes) against 4.29 GB and
  growing without. New `--memory-report-seconds N` logs the JVM's, the process's, the cgroup's and RocksDB's
  figures; `--utxo-cache-mb` is now the native-memory budget of the whole store (one block cache for all
  tables, the memtables charged to it). Container limit: `-Xmx` plus 2 GiB. performance.md section 7.
- D1: the accounts, pools, DReps and the three stake snapshots moved behind the ledger store. The ledger
  state holds a `Table` of each (the changes of the last `k` blocks, a count and sums) and RocksDB a column
  family each, in the database of the UTxO set (`LedgerStore`, `RocksLedgerStore`, `MemoryLedgerStore`;
  the classes were `UtxoStore` interpreters). The pure rules are unchanged: a row the state does not
  hold is fetched and the block applied again, and the rows a block names are fetched first. The
  snapshot schema changed (older snapshots are rejected and the node replays from the immutable blocks);
  the snapshots written are about a quarter smaller. Throughput on Preview: -2.7% over the first 29 epochs.
  An epoch boundary still reads the accounts and the `go` snapshot whole. performance.md section 8.

### Node-to-client
- LocalStateQuery server (roadmap F1): the mini-protocol (acquire at a point, the volatile or immutable
  tip, re-acquire, release), the hard-fork-combinator query envelope and the Conway queries
  `cardano-cli conway query` sends: tip, epoch, protocol parameters, UTxO (whole, by address, by
  input), delegations and rewards, stake pools, pool parameters and state, stake snapshots,
  constitution, governance state, DRep state, DRep stake distribution, committee state, vote
  delegatees, treasury and reserves, SPO stake distribution, proposals, ratify state, future
  parameters, pool and stake distributions, DRep delegations, genesis configuration, protocol
  (chain-dependent) state, era history, system start, chain point and block number. The answers are
  the same CBOR as the Haskell node's at the same block (`devnet.py ntc`). Not answered yet:
  `ledger-state`, the reward-provenance and non-myopic queries, ledger peers, eras before Conway.
- ApplyTxErr (roadmap F3): a refused transaction is sent as the ledger's predicate-failure CBOR, so
  `cardano-cli transaction submit` prints the error the Haskell node gives. The Conway rules raise a
  typed `PredicateFailure` tree with the ledger's payloads; the ledger's MEMPOOL rule (all inputs
  spent) runs first; the CERTS and GOV checks run before UTXOW as in the ledger.
- Protocol parameters read the `extraConfig.costModels` of the Alonzo genesis (PlutusV2); the Scala
  parameters had two cost-model languages where the reference has three.
- `PraosState` keeps the previous epoch nonce (the chain-dependent state a client reads); snapshots
  written by earlier builds fail their schema check and are replayed.

### Byron
- Byron ledger (roadmap C1): UTxO with witness signatures, address roots, network magic, value
  conservation and the linear fee; AVVM and non-AVVM genesis UTxO; delegation certificates; the update
  system; the Byron UTxO carried into the Shelley ledger. Real mainnet transactions (an AVVM redemption
  and verification-key transactions) validate.
- Byron consensus (C2): signer must be the current delegate of a genesis key, last-signed-slot rule,
  PBFT window and `PBftSignatureThreshold`, body proofs; the `unchecked` header outcome and the
  `signatureUnchecked` log field are gone (`offSchedule`, `transactions` added). Light delegation tags
  are decode errors, as upstream.
- The first Shelley epoch is derived from the Byron update state when the configuration has no
  `TestShelleyHardForkAtEpoch` (preprod: epoch 4); `0` still means no Byron prefix.
- Byron epoch length comes from the genesis everywhere (C3): `Block.decode`, `Header.fromEnvelope`
  and friends take it, `Peer.Settings` and `ChainStorage.Config` require it. `CaptureMain` has
  `--byron-epoch-slots`. Snapshot schema changed (old snapshots are rejected and recovery replays).
- Preprod fixtures (`fixtures/preprod`) and mainnet Byron transaction fixtures.

### Storage
- D4: the volatile chain (the last `k` blocks) is logged in `<db>/volatile/` (`VolatileStore`: CRC per
  record, rollback markers, files collected once wholly below the forced immutable tip) and applied
  again on start, so a restart continues at the previous tip instead of fetching those blocks. A
  graceful stop (SIGTERM, SIGINT, `--seconds`) writes a snapshot at the immutable tip: the next start
  replays no stored block. Persisting a block is uncancelable, so a stop never lands between two stores.
- D5: the database records its network (`<db>/network-identity`: magic, Shelley and Byron genesis
  hashes) and refuses a mismatch with `StorageError.NetworkMismatch`; a database from before the file
  is adopted if its first block names the configured genesis (`ForeignChain` otherwise).

### Scheduling
- D8: the peer session (header handling, chain selection, the rules, persisting) runs on its own
  work-stealing pool (`Compute[F]`, a `Resource`, `--cpu-threads`), apart from the pool that serves the
  connection, the relay servers, the forger and the timers, so a long CPU-bound step cannot hold up a
  KeepAlive or the slot clock. A monitor fiber records timer lateness; `epochValidated` and `ledgerEpoch`
  lines carry `scheduling` (`maxTimerLatenessMs`, `longestStepMs`, `longestStep`), and `GET /scheduling`
  serves the totals. Moving each block's rules to a pool cost 15 to 48 percent of the throughput; the
  session on the pool costs 1 to 4 percent (Preview, 31 min, started with the base build). The replay of
  stored blocks yields with `cede`.
- With one CPU the JVM's serial collector, not the rules, made timers late: run with
  `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20` (running.md); the devnet node does.

### Ledger
- Before Allegra an account with a leader and a member reward keeps the leader reward (it kept the member reward).
- Reward prefilter confirmed against the source (registered at the first block after 4k/f); now applied when the update is created, as the reference shows it.
- Mary scaled minimum output: divisor 27 and distinct asset names; Allegra and Mary only call ada-only outputs too small, as the reference does.
- Output value limit: 4000 serialised bytes in Allegra and Mary, `maxValueSize` from Alonzo, measured on the canonical encoding.
- MIR: transfers and negative entries need protocol 5, later certificates replace earlier ones before that, deregistration keeps pending entries.
- Protocol version 11: a pool registration may not take a VRF key hash another pool holds or has
  scheduled by a re-registration (`VRFKeyHashAlreadyRegistered`, `hardforkConwayDisallowDuplicatedVRFKeys`).
- The mempool refuses votes of unelected committee members before protocol version 11, as the
  reference node's mempool does; from 11 the GOV rule does (`ConwayMempoolFailure`).

### Build, CI and structure
- Dependabot alerts: http4s 0.23.30 to 0.23.37 (ember request smuggling and HTTP/2 denial of
  service), circe 0.14.10 to 0.14.16 (brings jawn-parser 1.7.0), fs2 3.11.0 to 3.14.0 (the 3.12.2
  fix; http4s 0.23.37 resolves 3.14.0), cats-effect 3.6.3 to 3.7.1, cats-mtl 1.6.0 to 1.7.0. fs2 3.13
  deprecates `UnixSockets`, `serverResource`, `client` and `remoteAddress`; the bearers now use
  `Network.bind`, `bindAndAccept`, `connect` and `peerAddress`. The jackson, jsoup and jline alerts
  come from the Scala 3.3.8 scaladoc and REPL tool dependencies (not shipped, not on the node
  classpath); no 3.3.x release updates them.
- CI: gitleaks secret scan of the full history (`.gitleaks.toml`, 182 known generic-api-key false
  positives allowlisted by path and commit, 0 findings), dependency review on pull requests and the
  sbt dependency graph submitted on pushes to main (roadmap A8).
- The signature-verdict caches are no longer process-global: `VerificationCache` is created in a
  `Resource` in `Main` and reaches the pure rules as `Verdicts` through `PraosConfig.verdicts` and
  `TxRules.Env.verdicts`; `Verdicts.Direct` is the uncached specification (roadmap M3). A 21 minute
  Preview follow from a fresh db (6 CPUs) ran epochs 0 to 107 at 342 blocks/s (docs/performance.md:
  349 for the first 100 epochs), 0 errors; devnet soak 480 s, 23 comparisons, 0 mismatches.
- `PlutusCases` moved to `lab.node.tools` (roadmap M4).

### Devnet
- `devnet.py ntc`: the Scala node's node-to-client interface against the reference's on a loaded
  cluster: LocalStateQuery byte for byte at the same block (707 of 707 answers, 14 rounds), 23 of 24
  `cardano-cli conway query` commands, 18 refused transactions as raw `ApplyTxErr` bytes and as
  `cardano-cli` text, and a valid submission (`scripts/devnet/ntcqueries.py`, `ntccases.py`,
  `scenarios/ntc.py`; `ntcquery.py` speaks LocalTxSubmission and host sockets).
- Recorded reference answers for the activity-independent queries (`fixtures/devnet/lsq`, replayed by
  `QueryGoldenSuite`) and the reference's rejection bytes (`PredicateFailureCborSuite`); the three
  recorded refusals of `fixtures/devnet` are compared with the text `cardano-cli` printed.

- `--scala-cpus N` for `follow`, `soak`, `relay`, `pool` and `e2e` (the Scala container's CPUs) and
  `--max-starvation-warnings N`; the `pool` summary reports `scheduling` (starvation warnings, worst
  timer lateness, longest CPU-bound step) and `slotClock` (when the node logged its forged blocks and
  received the others' relative to their slots; a run whose clock is a second behind the network's now
  says so instead of only listing missed leader slots).
- `e2e`: three Shelley epochs, 1 ada minimum, pool margin 0.1, cross-paying pools, boundary load and expected-rejection checks.
- `soak --governance-extended` (`govload --extended`, `scripts/devnet/govextra.py`): pool reward accounts
  with default votes, committee election with a term limit, a member that expires and `committeeMinSize`,
  a Plutus V3 script as stake credential, DRep and reward account, and the protocol-11 refusals (disjoint
  reference inputs, unelected committee voters, taken VRF key hashes) checked against the reference
  node's predicate failures (roadmap B2 to B5). `e2e` runs a light subset in its Conway epochs.
- Refused transactions are kept and replayed through the Scala rules (`DevnetRefusedTxSuite`,
  `fixtures/devnet`).

## [0.1.0] - 2026-10-11 - research preview

The uninterrupted Preview genesis-to-tip sync ran on commit 5cfdefd (4,748,226 blocks in 3h36m42s,
0 error events, final tip identical to Koios; see [docs/performance.md](docs/performance.md)). The tag
is on a later commit (hygiene, docs, CI, forge-loop reporting; no intended behaviour change). On the
tagged code `check` (494 node tests), soak (24/24 and 30/30), e2e (every era matched), relay and
pool all passed; the pool scenario now asserts every scheduled leader slot against
`cardano-cli query leadership-schedule` (92 scheduled, 92 forged, 0 missed).

### Foundation
- Cleanup to a tagless-final code base: pure `core` and `network`, fs2 `network-runtime`, `node`
  with cats-mtl typed errors; research modules, scripts and documents removed (6d330b5, history at 8dca66c).
- Network runtime rebuilt on fs2 as algebras over one multiplexed connection (e04dc9a).

### Consensus
- Praos header validation (VRF, KES, operational certificates, nonces) and TPraos validation with the
  overlay schedule; Preview followed from genesis (6332426).
- Era-aware headers and blocks for Byron, TPraos and Praos (d6122c7); Byron to Conway follow with
  the Byron signature check (8c8258b).
- Praos chain selection over several peers (bb38470).
- Block forging: ECVRF draft-03 proving, Sum6 KES generation, signing and evolution, leader check,
  block assembly from the mempool, adoption (00450a9, 803aefb).

### Ledger
- Full Shelley to Babbage ledger: UTXO/UTXOW/DELEGS/PPUP, MIR, rewards, epoch boundary (baee535, f2e0d34).
- Plutus V1 to V3 phase 2 via Scalus behind a `ScriptEvaluator` algebra, own script contexts and
  script data hash (35ff3e6, 82ab9b3, be02666).
- Conway: transaction rules, scripts, certificates, governance, ratification, enactment, DRep
  pulser (a785baf, 87a36d4, 21bdedd); governance snapshot codecs (47f7208).
- Block execution-unit limits validated on received blocks and enforced when forging.
- Fixes found by syncing Preview: pledge owners' stake (ca925cf), pointer addresses at the Conway
  boundary (ff735db), repeated redeemer purposes (aabb240, bd4871f), Plutus V1 with reference inputs
  (e5beb60), `NewConstitution` context (73e5b4f), CBOR nesting depth (f3003aa); reward-update
  activation (8d81d5c).

### Network and relay
- Node-to-node servers: duplex mux, handshake, ChainSync, BlockFetch, KeepAlive, TxSubmission2 over
  TCP (dad6087); the node serves its own chain and holds transactions in a mempool (aac9996).
- Node-to-client LocalTxSubmission over a Unix socket (435d2fc).
- BlockFetch ranges cut by body size and intersection candidates thinned (d0ed44d).

### Storage and scale
- `ImmutableStore`, `LedgerSnapshots`, restart recovery (b1a7f85); snapshot reader fix (24cef6b).
- UTxO set behind `UtxoStore` with RocksDB and memory interpreters, incremental stake (a71db6a).
- Compact array-backed bytes (1ef066d); parallel verification ahead of the rules (aee62d9); pipelined
  peer (84d3e54, d97196f); cached per-block recomputation (1426627, 0cc6ef3).

### Tooling and evidence
- `devnet.py`: `up-eras` and `e2e` (b5667f8, 8c8258b), per-era load and comparison (85721cb),
  Conway governance load (6f6e659), `relay` (8f35379), `pool` (288582d); fixtures `fixtures/eras`
  and Preview governance/Plutus cases.
- Documentation overhaul: README, architecture, running, compatibility matrix, performance,
  findings, roadmap; CONTRIBUTING, SECURITY, CODE_OF_CONDUCT; issue and pull request templates;
  complete `NOTICE`.

### Known limits
See [docs/compatibility.md](docs/compatibility.md) and [docs/roadmap.md](docs/roadmap.md): Byron
transactions, preprod and mainnet, full node-to-client queries, Mithril import, P2P governor, KES
agent and opcert issuing, TPraos forging, unaudited.
