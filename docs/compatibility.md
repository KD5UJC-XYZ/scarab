# Compatibility matrix

Status of each component against the Haskell reference (`cardano-node` 11.1.3, see
[reference-image.md](reference-image.md)), the evidence behind it, and what is explicitly **not**
checked. Unofficial, research software, not audited. "Verified" always means "agrees with the
evidence named in the row"; it is not a proof of equivalence. Roadmap IDs (A1, B3, ...) point into
[roadmap.md](roadmap.md).

Evidence kinds:

- **Unit/property**: munit, ScalaCheck and cats-laws suites under `*/src/test`.
- **Golden/oracle**: fixtures under `fixtures/` (see their READMEs) and a Scalus oracle for script contexts.
- **Devnet**: `scripts/devnet.py` scenarios against a Haskell cluster, see [running.md](running.md).
- **Preview sync**: following the public Preview testnet from genesis (every block checked against
  our rules; the chain is the oracle, so it shows we do not reject valid blocks, not that our ledger
  state is identical, see K1).

Status values: **implemented**, **partial**, **not implemented**.

## Matrix

| Component | Status | Evidence | Explicitly unchecked or unconfirmed |
|---|---|---|---|
| **Network: handshake, mux, KeepAlive, ChainSync, BlockFetch (client)** | implemented (node-to-node 14, 15; one connection per peer, pipelined) | unit/property over in-memory bearers and loopback TCP; `fixtures/network` captured envelopes; Preview sync; devnet | no peer governor, churn or PeerSharing (G1); no connection manager (G2); no Ouroboros Genesis (G3); v16 features (G4); adversarial limits and fuzzing (G5, K3); headers are not validated before their blocks are fetched (G6) |
| **Network: servers (ChainSync, BlockFetch, KeepAlive, TxSubmission2) and duplex** | implemented | unit/property (both directions, duplex, pipelining, timeouts, refusals; served-chain reconstruction property); devnet `relay` (Haskell relay synced solely from the Scala node, tip lag <= 1 in 59/59 samples) | inbound duplex connections are accepted but our own clients are not run on them (G2); no per-peer resource limits (G5) |
| **Consensus: Praos headers (Babbage, Conway)** | implemented: VRF, KES, opcert counters, nonce evolution, leader threshold | unit/property; golden Praos/VRF/KES/opcert vectors; devnet nonce comparison (all five Praos nonces); Preview sync (every header threshold-checked) | VRF tie-break restriction in chain selection written from memory (B1); header protocol-version checks and exact hard-fork timing not enforced (B13) |
| **Consensus: TPraos headers (Shelley to Alonzo)** | implemented: overlay schedule, two VRF certificates, 512-bit leader value | unit/property; devnet e2e (`leaderUnchecked` 0 in every era); Preview Alonzo/Babbage sync (every non-overlay header threshold-checked) | overlay-slot headers have no threshold check (by definition); TPraos forging not implemented (H3) |
| **Consensus: Byron** | partial: headers only | `EraTransitionSuite`, devnet e2e (all Byron signatures verified); mainnet Byron fixtures | signature verified only for the genesis delegate; on-chain delegation certificates not tracked (header reported `unchecked`); delegation certificate signature, block body proofs and slot-leader schedule not checked; no Byron transactions or UTxO (C1, C2); Byron epoch length default 21600 (C3); preprod/mainnet not synced (C4) |
| **Chain selection** | implemented: longer chain, opcert issue number, VRF tie-break, max rollback k, several peers | unit/property (antisymmetry, longer wins, never more than k rolled back, order independence); devnet `relay` and `pool` (slot battles, deepest rollback 1) | Conway VRF tie-break restriction (5-slot window) unconfirmed against ouroboros-consensus (B1) |
| **Ledger: Shelley to Mary** | implemented: UTXOW/UTXO/DELEGS/PPUP, MIR, rewards, epoch boundary | unit/property; devnet e2e per-era comparison (Shelley 4, Allegra 4, Mary 4, all matched); era fixtures | small comparison counts per era; pre-Allegra reward aggregation, MIR timing and the Shelley-Mary min-UTxO formulas partly covered by the comparison only (B7); Mary output-size and value-size checks are approximations, no `maxValueSize` check before Alonzo (B12); pre-Babbage reward prefilter timing (registered at epoch start + 4k/f) unconfirmed against the Haskell source (B8) |
| **Ledger: Alonzo and Babbage** | implemented incl. collateral, inline datums, reference inputs and scripts | unit/property; devnet e2e (8 + 8 comparisons, matched); Preview Alonzo/Babbage sync (whole chain, no rule failure) | block execution-unit limits are validated (protocol >= 5) but only by unit tests (`BlockExUnitsSuite`) and the sync; empty-redeemers integrity hash `0x80` unconfirmed on chain (B6) |
| **Ledger: Conway** | implemented incl. scripts, all certificates, treasury donation | unit/property; devnet soak (24/24 and 41/41) and e2e (Conway 30 comparisons, matched); Preview sync from epoch 646 (Conway, protocols 9 to 11) | see governance row; VRF key-hash uniqueness at protocol 11 not enforced (B5); disjoint reference inputs at PV11 implemented from source, never exercised (B5) |
| **Ledger: Conway governance** | implemented: proposals, votes, ratification, enactment, DRep pulser, guardrails, committee | unit/property (Ratify, ConwayGovernance suites); devnet soak/e2e compares proposals, votes, committee and committee state, constitution, DRep stake distribution and ratify state; Preview governance fixtures and sync | SPO default votes after the bootstrap phase (devnet reward accounts are unregistered; unit tests only, B2); committee `minSize`, expiry and term limits (unit tests only, B3); treasury withdrawals to script accounts and script DRep voting (Preview data only, B4); unelected-committee vote check implemented, never exercised (B5); non-myopic pool ranking not implemented |
| **Plutus phase 2 (V1, V2, V3)** | implemented: Scalus 1.2.0 UPLC machine behind `ScriptEvaluator[F]`; our own script contexts, cost-model selection, script data hash | unit/property; Scalus oracle for contexts; golden Preview cases (`fixtures/preview/plutus`); Preview sync: 3.23M Plutus transactions evaluated, each verdict equal to the block's `is_valid` flag (242 evaluate to failure), 3.30M script data hashes recomputed and equal; devnet drives V3 only | V1 and V2 have no devnet comparator (I3); builtin availability per language and protocol version is not enforced (B9); semantics-variant C costing of `modInteger`/`remainderInteger` relies on Scalus (B10); two Scalus deviations patched or worked around, not yet reported upstream (B11); upstream Plutus conformance suite not run (I1); we do not own the CEK machine (I2) |
| **Persistence (UTxO-HD style)** | implemented: `ImmutableStore`, `LedgerSnapshots`, RocksDB `UtxoStore`, restart recovery | round-trip and corruption property tests; store crash cases (torn data/index, every cut point of the newest chunk); `UtxoStoreSuite` equals a `Map` model across rollbacks and flushes; restart equivalence over the Byron-to-Conway capture; live Preview restart in 2.3 s and `kill -9` recovery (recorded 2026-10-11) | only the UTxO set is on disk; accounts, pools, DReps and stake snapshots are in memory and in the snapshot file (D1); volatile blocks not persisted, no final snapshot on shutdown (D4); DB does not record its network (D5); no fault injection on RocksDB itself; mainnet-scale memory and throughput not measured (D6) |
| **Relay and mempool** | implemented: pure `MempoolState`, TxSubmission2 both roles, typed rejections | unit/property (held transactions always apply in order, byte accounting, tickets); devnet `relay` (203/203 transactions via the Scala mempool into pool blocks) and `pool` | no epoch-boundary ticking; transactions of rolled-back blocks are not re-added (H4); no DoS limits or fairness policy (G5) |
| **Block forging** | implemented: Praos (Conway) only; ECVRF draft-03 proving, Sum6 KES generate/sign/evolve, opcert loading | IETF draft-03 vectors 16 and 17 reproduced exactly; KES verification key reproduced from `cardano-cli` key seed; `ForgeSuite`; devnet `pool` (Scala node as pool 1 of three: 86 forged, 85 adopted, Haskell blocks-made 85 against 83.2 expected, 151 transactions, 18 KES periods, 16/16 comparisons) | TPraos forging (H3); no KES agent, evolution is not persisted across restarts, no opcert issuing (H2); never run on a public network (H1); forging-loop timing/metrics (H5); side-channel properties of the secret-scalar arithmetic not reviewed |
| **Node-to-client** | partial: LocalTxSubmission over a Unix socket | unit tests over a Unix socket; devnet `relay` (one transaction submitted with `cardano-cli`) | no LocalStateQuery, LocalTxMonitor or local ChainSync (F1, F2); rejection reasons sent as CBOR text, not the ledger's ApplyTxErr encoding, so `cardano-cli` cannot render them (F3) |
| **Fast start (Mithril, snapshot import)** | not implemented | | E1 |
| **Operational parity (config/topology files, CLI, metrics)** | not implemented | | J1 to J3 |
| **Dijkstra (next era)** | not implemented | | L1 |

## Evidence log

Numbers from recorded runs. Each names the commit it was obtained on, taken from the project
notes; the run logs themselves are not in the repository (they lived in `/tmp` and the gitignored
`.workspace/`), so these are reports, not artefacts you can re-inspect. The tagged-build re-runs
are pending (see the placeholder in the README and K5).

| Run | Result | Commit | Environment |
|---|---|---|---|
| `devnet.py soak --seconds 480` | 24/24 comparisons matched, epochs 0 to 9, no error | 87a36d4 | reference 11.1.3 image, `cardano-scala-jdk` (then named `cardano-public-v023-check:local`) |
| `devnet.py soak --seconds 900` | 41/41 matched, epochs 0 to 18, through the hard fork to protocol 11, a no-confidence action and a committee election | 87a36d4 | same |
| `devnet.py e2e` | every comparison matched: Shelley 4, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30; seven Conway epochs after the transition | 87a36d4 (Shelley to Babbage first matched at 85721cb) | same |
| `devnet.py relay --seconds 300` | Haskell relay synced solely from the Scala node, at the tip across 7 epochs (lag <= 1 in 59/59 samples), 203/203 transactions included | 435d2fc | same |
| `devnet.py pool --seconds 480` | latest (release gate): 80 forged, 79 adopted, 78 on the final chain; Haskell blocks-made 78 = Scala on-chain 78; 16/16 comparisons | 5cfdefd | same |
| `devnet.py pool --seconds 480` (earlier) | 86 forged, 85 on the final chain (one lost a slot battle); Haskell blocks-made 85 against 83.2 expected; 151 transactions in 72 Scala blocks; 18 KES periods; deepest rollback 1; 16/16 comparisons | 13460b5 | same |
| Preview sync, genesis to tip | 4,747,639 blocks, epoch 1447, 551 blocks/s average, no rule failure; **the run was assembled from 12 restarts across different builds** (see [performance.md](performance.md)) | perf branch, merged in 5cfdefd | 8 CPUs, `-Xmx3g`, RocksDB UTxO |
| Preview sync, uninterrupted, one build | 4,748,226 blocks, epochs 0 to 1447 in 3h36m42s (about 365 blocks/s), then 32.8 min at the live tip; 0 error events; 242 failing Plutus evaluations all equal to `is_valid`; final tip identical to Koios; RSS peak 5.66 GB, disk 14.8 GB (see [performance.md](performance.md)); accepts the real chain, ledger identity vs Haskell not shown (K1) | 5cfdefd (tag v0.1.0 will be on a later docs/CI commit) | 6 CPUs, `-Xmx3g`, container limit raised to 10 GiB, RocksDB UTxO |

The Scala unit and property suites run in CI (`.github/workflows/ci.yml`) via `scripts/sbtw check`
on every pull request; the devnet scenarios do not run in CI yet (roadmap A3).

## Not checked at all

Collected from the table, for quick scanning:

- ledger state equality with `db-analyser` at epoch boundaries (K1); today Preview only shows valid blocks are not rejected;
- upstream conformance suites for the ledger, ouroboros-network and Plutus (K2, I1);
- fuzzing of the CBOR decoders, transaction/block decoding and mini-protocol codecs (K3);
- days-long stability, memory growth, file handles, reconnect behaviour (K4);
- preprod and mainnet (C4, D6);
- security properties: not audited, no side-channel review, no adversarial network testing.
