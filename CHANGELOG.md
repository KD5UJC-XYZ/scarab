# Changelog

Format: [Keep a Changelog](https://keepachangelog.com/en/1.1.0/). The project is pre-1.0 research
software; versions mark milestones, not stability. Commit hashes refer to this repository's history.

## [Unreleased]

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

### Devnet
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
