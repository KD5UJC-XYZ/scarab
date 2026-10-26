<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence log, part 4 of 5

Part of the [compatibility matrix](../compatibility.md#evidence-log). Each run is one bullet with its former table columns as sub-bullets; the text of every cell is unchanged.

Other parts: [part 1](evidence-log-1.md), [part 2](evidence-log-2.md), [part 3](evidence-log-3.md), [part 5](evidence-log-5.md).

- **Run:** Final rebased Preview integration checks (#21, 2026-10-19)
  - **Result:** Docker `check`: 5,273 passed/17 optional-data skips across 14 modules, formatting/Werror/default fuzz/Scalafix; 250 Python tests and three CLI help checks pass. Follow-up empty-relay regression plus both native POOL adapter controls: three passed, formatting/Scalafix pass. Independent source audit distinguishes rule extraction from the empty-relay representation change; original signed/body/witness bytes are retained. [Final scenario and published-head CI](https://github.com/KD5UJC-XYZ/scarab/pull/209)
  - **Commit:** Full check `59d3b8c2` on main `50759002`; test-only addition `7289cef8`
  - **Environment:** Docker JDK21; frozen full-data runs retain their actual earlier source/runtime identities; current scenario copies match 11,633 compiled runtime files/64 jars independently during execution

- **Run:** Differential harness (J2.2), `PreviewDifferentialMain` stride 72 over the K1 Preview corpus
  - **Result:** 21 epochs, 70,183 blocks replayed from the snapshot and UTxO checkpoint of epoch e to the snapshot of e+1: 0 divergences, 21/21 end states equal to the recorded snapshots, +3.9% replay time, conversion 302 ms median (new side not ported: reseeded)
  - **Commit:** this branch (`jump2/j21-j22`)
  - **Environment:** 2026-10-16, 16 GB heap, [performance.md](../performance.md) section 9

- **Run:** Differential harness, `DifferentialErasSuite` and devnet `e2e`, `soak --seconds 480` (old rules), `soak --seconds 480 --rules differential`
  - **Result:** 731 blocks Byron to Conway, 0 divergences; e2e passed; soak 24/24 matched in both, the differential one with 12 samples and 0 divergences
  - **Commit:** same
  - **Environment:** 2026-10-16

- **Run:** Differential harness, the tick (J2.3): `DifferentialErasSuite`, `PreviewDifferentialMain` stride 72 / forks / stride-24 sweep, Preprod from genesis, devnet `e2e` and `soak --seconds 480` with `--rules differential`
  - **Result:** eras fixture: 731 blocks, 681 ticks compared, at least 6 era crossings, 0 divergences. Preview stride 72: 21 epochs, 70,183 blocks, 0 divergences, 21/21 end states equal to the snapshots. Forks run (snapshot indices 1, 2, 20, 21, 644, 645, 741, 742, 1288 to 1291: the hard forks to protocol versions 7 to 11): 12 epochs, 0 divergences. Stride-24 sweep, offsets 0, 6, 12, 18: about 35 epochs each, 0 divergences. Not the full 1,451 epochs. Preprod from genesis to epoch 39 (Shelley to Babbage), 0 rules divergences. Devnet e2e (differential): passed, 34 ticks compared, 0 divergences; e2e (default rules): Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28 matched, 0 mismatched; soak 480 s differential: 23/23 matched, 19 ticks compared, 0 diverged
  - **Commit:** jump2/j23, 2026-10-16
  - **Environment:** multi-core host under load, `-Xmx16g`

- **Run:** Differential harness, the Conway blocks (J2.4): `DifferentialErasSuite`, `PreviewDifferentialMain` stride 72 / stride 36 / forks / future-parameters, Preprod late epochs 317 to 319, Preprod from genesis, devnet `e2e` and `soak --rules differential`
  - **Result:** Preview stride 72: 21 epochs, 70,183 blocks, 0 divergences (per block, old / new mean 1.26 / 1.65 ms); stride 36: 40 epochs, 131,651 blocks, 0; forks: 12 epochs, 41,474 blocks, 0; future parameters: 2 epochs, 5,777 blocks, 0; dense epoch 1369 (3,068 blocks, Plutus included): 0; Preprod epochs 317 to 319: 2 epochs, 38,685 blocks, 0 (blocks old 5.7 ms, new 7.0 ms); devnet `e2e` passed with the blocks compared (the extended `xvrf` stage after it failed on a Docker exec error of the host, not on a comparison); soak 44/44 matched. **Preprod from genesis: 3 failures on one event, the first reward update of a seeded state (epochs 163 to 166), 0 in every other epoch to 197**: the seed's `mark` was read off the accounts after a pool's reaping had cleared its delegators (design section 4 item 4); fixed on `j24/seed-snapshot-fields`, whose rerun is the next row
  - **Commit:** this branch
  - **Environment:** 2026-10-16

- **Run:** Differential harness, the seed's snapshots (J2.4, `j24/seed-snapshot-fields`): `LedgerMirrorSuite`, `DifferentialErasSuite`, Preprod from a checkpoint of the old rules at the last Babbage block (slot 68,774,000)
  - **Result:** before the fix: the 3 divergences of the first reward update after the seed (slot 69,811,237, 8,871 credentials against 8,869); after it: 0 over the same 38,182 blocks; Preprod, `SnapshotBasis.AsTaken` seed, the old rules alone from genesis to the last Babbage block (slot 68,774,000), then the harness (sampling every 36th boundary, the tick stage at every block) over epochs 163 to 170 of the recording: 0 divergences, where the J2.4 run had 3 at epoch 165
  - **Commit:** `j24/seed-snapshot-fields`
  - **Environment:** 2026-10-17

- **Run:** Differential harness, the blocks of Shelley to Babbage (J2.5): `DifferentialErasSuite`, `PreviewDifferentialMain` stride 72 and every pre-Conway epoch, Preprod from genesis, devnet `e2e` and `soak --seconds 480` with `SCARAB_RULES=differential`
  - **Result:** eras fixture: 731 blocks, 675 of the 681 Shelley-based blocks applied by the new side (the first block of each era is the old side's), 0 divergences. Preview stride 72: 21 epochs, 70,183 blocks, 0 divergences, 21/21 end states equal to the snapshots (per block, old / new mean 2.2 / 2.9 ms). **Preview every epoch of snapshot indices 0 to 645 (epochs 1 to 646, Alonzo, Babbage and the first Conway epoch): 646 epochs, 2,342,760 blocks, 0 divergences, 646/646 end states equal to the snapshots.** **Preprod from genesis to slot 69,500,000 (epoch 164, Byron, Shelley, Allegra, Mary, Alonzo, Babbage and the first epochs of Conway): 2,646,309 blocks, 2,646,257 compared by both sides, 1,387 samples, 1,546 ticks compared, 0 failures, 0 divergences** (blocks old / new mean 2.0 / 2.1 ms). Devnet `e2e` (differential): passed, 60 comparisons matched, 0 mismatched; `soak --seconds 480` (differential): 22/22 matched, no divergence. `ShelleyLedgerSuite` (19 tests, five eras, both sides) and `ShelleyBbodySuite` (7). Not the 806 Conway epochs, which are J2.4's
  - **Commit:** this branch (`j2/5-pre-conway-ledger`)
  - **Environment:** 2026-10-16, multi-core host under load (Preview and Preprod `-Xmx10g`/`-Xmx16g`)

- **Run:** The default rules are the era-indexed ones, the chain's state the mirror (J2.8): `check`, `MirrorRulesSuite`, `DifferentialErasSuite`, `SnapshotMirrorSuite`, `AllegraRedeemMirrorSuite`, devnet `e2e` (default rules, and `SCARAB_RULES=differential`), `soak --seconds 480`, `restart`, `ntc`, `pool`, `kes-rotate`; replays from genesis on `--rules new` of the Preview K1 blocks and of the first 3,300 chunks of Preprod
  - **Result:** the production rules over the eras capture beside the old rules: 731 of 731 blocks, Byron to Conway, new-epoch state, header state, consensus bookkeeping and telescope bounds equal at each block; 680 of 681 Shelley-based blocks applied by the harness's new side (the first block of every era but Shelley included); every state of the capture written and read back whole as the mirror, and the files of the flat form read as the mirror; the entry into Allegra over a store (the stamp before the tick). Devnet: `e2e` passed on the default rules (1,713 blocks; Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30 comparisons matched, 0 mismatched; after the fix of the reward reading below) and with `differential` (1,661 blocks, same matches, no divergence); `soak` 480 s: 24/24 matched, 264 blocks, 2 rollbacks, no error; `restart` (stop 0.7 s, restored); `ntc` 18 rounds, every round equal with the debug-state masks; `pool` forged 86 blocks (85 on the final chain, 119 transactions from the new mempool, 16/16 comparisons); `kes-rotate` 42 blocks, 10/10. Preview from genesis on `--rules new`, read-only K1 blocks: 648 of 648 epoch digests so far equal to the K1 digests (`oracle_compare`: 26/26 first; the two parameter fields an era has no place for, `minUTxOValue` and `utxoCostPerWord`, differ as zero and are left out); Preprod from genesis (Byron hand-over, Allegra, Mary, Alonzo, Babbage, the first Conway epochs) new against old: 147 of 147 epoch digests equal; 1,023,906 Preview blocks new against old, concurrent on a loaded host: 2,446 s against 2,329 s (+5.0%), `plutus` steps +3.9%, epoch boundaries 9.1 ms against 10.1 ms mean
  - **Commit:** one bug found by the devnet and fixed: the mirror's reading of the reward update summed the several rewards of a credential where the reference pays the least before Allegra (`RewardsDueSuite`). Not measured: the epoch boundary on a large state (the preprod replay's scheduling statistics were lost when the replays were stopped; the J2.4 figure of 1.1 s against 0.1 to 0.4 s for 112,000 accounts stands), peak heap beyond the live-object sizes (122 to 164 MB new, 128 to 188 MB old, Preprod to block 1.5 M), the full Preview and Preprod replays to the tip, `relay`. Explicitly unchecked: an old-rules snapshot of the Shelley era of a Byron-start chain before Allegra has no redeem stash ([#128](https://github.com/KD5UJC-XYZ/scarab/issues/128)); the sidecar's gaps are a constant ([#129](https://github.com/KD5UJC-XYZ/scarab/issues/129)); the delegator counts of `set` and `go` of a restored old-rules file are lower bounds until two boundaries
  - **Environment:** this branch (`j2/8-flip`)
  - **Further:** 2026-10-17, shared host

- **Run:** The rule suites over the flat state on both rule sets (J2.6): `RuleSet` (`Old`, `New`) for `PreConwayRulesSuite`, `ConwayRulesSuite`, `ConwayGovernanceSuite`, `RewardsSuite`, `MainnetAllegraReservesSuite`, `MainnetPoolNetworkSuite`, the Preprod and Preview Plutus suites and `DevnetRefusedTxSuite`; `DifferentialErasSuite`; devnet `e2e` with `SCARAB_RULES=differential`
  - **Result:** 13 files, 26 test classes, every assertion on both sides: the same results as the old rules on Shelley to Conway transactions (refusals compared as typed values, payload included, the reference's refusals of the devnet among them), on epoch boundaries with the enactment of governance actions, on the reward update (the leader reward of mainnet epoch 235 to the lovelace of the withdrawal, 351,050,635 and 351,635,274) and on the recorded Preview and Preprod Plutus transactions; the deep transaction of `DeepNestingSuite` on a 256 KiB stack on both. Two findings (VRF key hash quirk of the reference at version 11, kept by the new rules; the pre-Allegra least reward in the conversion of the update, fixed). `DifferentialErasSuite`: 12 tests, 0 `StateDiffers`, 0 `VerdictDiffers`. Devnet `e2e` (differential): passed, 1,672 blocks, 33 ticks and 18 states compared, no `rulesDiverged`; the per-era comparisons with the reference matched (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28, 0 mismatched), the 8 expected refusals of Shelley to Alonzo and the Conway negatives refused as expected. `check` (all modules) and `scalafixAll --check` pass. Left to the old side by design: the first block of each era, the redeem outputs of the entry into Allegra, `PoolMedataHashTooBig`, `OutputBootAddrAttrsTooBig`
  - **Commit:** this branch (`j2/6-rule-suites`)
  - **Environment:** 2026-10-17, shared host, sbt heap 4 GB

- **Run:** The old rules, the flat state and the differential harness removed; snapshots are format 5 (J2.9): `check`, `scalafixAll --check`, the script tests, devnet `e2e`, `pool`, `restart --migrate-from` (the classes of 6ce5bb77, the last build with V4 files) and `ntc`; four V4 files of the K1 Preview sync migrated and restored
  - **Result:** `e2e`: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30 comparisons matched, 0 mismatched (as at J2.8); `pool` passed, 0 failures; `restart`: the V4 snapshots of the older build migrated to V5 (`formatMigrated` V4 to V5) and restored, a second restart restored the V5 snapshot, stops under 1 s; `ntc` passed; the real V4 files restored with the full read-back check. Open: the epoch boundary of the era-indexed rules is 46% slower at p99 than the old one's on the largest Preview state (`docs/performance.md`, #131); the projection digests of the capture and the node-to-client goldens were re-recorded from the era-indexed rules (reasons in their READMEs); the genesis start still goes through the flat `GenesisState`
  - **Commit:** branch `j2/9-removal` on `d28a69ff`
  - **Environment:** Docker devnets (`j29-*`), multi-core host

- **Run:** Restoring a database of the older snapshot formats (#145, `fix/145-old-format-restore`): `check`, `scalafixAll --check`, devnet `restart --migrate-from` (the classes of 6ce5bb77) and `e2e`; the K1 Preview database of 2026-10-14 (format 1, 36-byte checkpoint keys) opened on a copy; the J2.7 Preview database (format 4, 3 snapshots with checkpoints) opened on a copy
  - **Result:** before: format 1 migrated and restored, the 12th block after the anchor rejected with `BadInputsUTxO` (the checkpoint's keys are `txid ++ u32`, this build reads `txid ++ u16`); after: the checkpoint is read and the live store written under the current keys in 2.6 s (3.2 million outputs), 975 stored blocks replayed and 6,957 more followed over an epoch boundary with 0 errors; format 4: migrated, restored at block 248,618, 247,507 blocks replayed with 0 errors; `ChainStorageSuite` over formats 1 to 4 (frozen writers, live store ahead of the snapshot; format 4 also 700 blocks, 15+ checkpoints, a rollback): format 1 fails without the change; `e2e` 66 projected comparisons matched, 0 mismatched; `restart --migrate-from` passed. Formats 2 to 4 were never affected. Open: a checkpoint written by a build that restored a format-1 database without this fix holds both key layouts and is refused with a typed error (replay); a mainnet-size V4 copy is for the owner to run (docs/running.md, "Checking an older database")
  - **Commit:** branch `fix/145-old-format-restore`
  - **Environment:** Docker devnets (`a145*`), multi-core host

- **Run:** Ledger oracle, devnet `soak --seconds 480 --oracle`
  - **Result:** 10/10 digests equal, 23/23 ledger comparisons matched, 0 mismatches
  - **Commit:** same
  - **Environment:** same, `soak.log`

- **Run:** Ledger oracle, Preview from genesis against a Haskell cardano-node 11.1.3 (PraosMode) that synced the same relay
  - **Result:** epochs 0 to 124: 125/125 equal, 0 differences (includes the epochs where the stake-snapshot and pool-distribution fixes showed, 18 and 120); both syncs continued past that, results in `.workspace/data/2026-10-14-k1-haskell-preview/diff.json`
  - **Commit:** this branch
  - **Environment:** Scala 6 CPUs `-Xms3g -Xmx3g`, db-analyser 2 CPUs each

- **Run:** `devnet.py relay --seconds 300` (G2 gate)
  - **Result:** passed: the Haskell relay synced from the Scala node in 1.4 s and was at the tip in 55 of 58 samples (at most 6 blocks behind), 224 transactions arrived in the Scala mempool through it, 223 reached blocks, all tips equal; **the Scala node's own ChainSync client followed the relay over the relay's inbound duplex connection** (`Established(Inbound,Duplex)`, intersection found, 82 headers received, the relay's tip seen at block 178 of 180)
  - **Commit:** 40b7092, 2026-10-14
  - **Environment:** reference 11.1.3 image, `cardano-scala-jdk`, Scala node on 2 CPUs

- **Run:** `devnet.py soak --seconds 480` (G2, G6, D3 gate)
  - **Result:** 24/24 comparisons matched, epochs 0 to 10, 0 reconnects, no error
  - **Commit:** 40b7092, 2026-10-14
  - **Environment:** same

- **Run:** `devnet.py e2e` (G6 gate: Byron to Conway with header-first on)
  - **Result:** every projected comparison matched: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30; 1,606 blocks, 46 Byron blocks verified; no validation error, no reconnect
  - **Commit:** 96ef537, 2026-10-14
  - **Environment:** same

- **Run:** `devnet.py --name perf124 e2e` (#124 gate: the shared block pool, the pointer index, the store step, the Ed25519 double multiplication)
  - **Result:** every projected comparison matched, 0 mismatched, 0 errors: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30; exit 0
  - **Commit:** 1437076c, 2026-10-17
  - **Environment:** same

- **Run:** `devnet.py pool --seconds 480` (G2, G6 gate)
  - **Result:** 91 scheduled leader slots, 91 forged, 91 adopted, 90 on the final chain (one slot battle lost), 0 missed, 0 starvation warnings, timer lateness max 28 ms; Haskell blocks-made 88 against Scala on-chain; the Scala node followed both Haskell pools over the pools' own inbound duplex connections (our clients on them). Earlier runs on the same build under host load 20 to 40 missed 1 to 4 of 79 to 91 slots ("the forger was busy with an earlier slot"); the base build `d3a6eae` missed 11 of 42 in the same conditions, so it is the host, not the change
  - **Commit:** 40b7092, 2026-10-14
  - **Environment:** same, 2 CPUs

- **Run:** Preprod from genesis, 180 s, header-first on (G6)
  - **Result:** 47,681 blocks through Byron (first header names the genesis hash), the derived transition to Shelley at epoch 4, Allegra and Mary; 0 reconnects, 0 errors
  - **Commit:** 4571b5d, 2026-10-14
  - **Environment:** 4 CPUs, `-Xmx2g`

- **Run:** Preview from genesis, 15 min, three started together (depth 128): base build, this work with `--header-first on`, with `off` (G6, D3)
  - **Result:** 175,279, 175,492 and 175,677 blocks (183 blocks/s each); header-first logged 150,145 headers validated, 0 unchecked, 0 refused across 42 epochs of TPraos and Praos headers
  - **Commit:** 4571b5d against `d3a6eae`, 2026-10-14
  - **Environment:** multi-core host at load 40, `--cpus 6`, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`

- **Run:** Preview from genesis, 15 min, ChainSync depth 64, 128, 200, 256 (D3)
  - **Result:** base build one after another: 165,148 / 181,198 / 181,047 / 183,050 blocks; this work, started together: 141,641 / 150,992 / 158,276 / 156,837; no timeout or reconnect in any of 16 runs; ChainSync rounds of 64 to 256 requests: 165 to 262 ms on average, 805 ms at most
  - **Commit:** 4571b5d against `d3a6eae`, 2026-10-14
  - **Environment:** same

- **Run:** `scripts/sbtw check`
  - **Result:** core 192, network 139, network-runtime 71, node 760 tests passed (1 skipped)
  - **Commit:** 40b7092, 2026-10-14
  - **Environment:** `cardano-scala-jdk`

- **Run:** Prometheus metrics (J3): the Haskell node's names, from a running cardano-node 11.1.3
  - **Result:** the reference image run on the devnet as a fourth, non-forging relay and as a forging pool (pool 3's keys), with `"TraceOptions": {"": {"severity": "Notice", "detail": "DNormal", "backends": ["Stdout MachineFormat", "PrometheusSimple suffix 127.0.0.1 12798"]}, "Forge": {"severity": "Info"}, "Mempool": {"severity": "Info"}, "ChainDB": {"severity": "Info"}, ...}` and scraped at `:12798/metrics` (270 lines): `blockNum_int`, `slotNum_int`, `epoch_int`, `slotInEpoch_int`, `density_real`, `forks_counter`, `node_start_time_int`, `Forge_{about_to_lead,node_is_leader,node_not_leader,forged,adopted}_counter`, `blocksForged_int`, `nodeIsLeader_int`, `nodeCannotForge_int`, `slotsMissed_int`, `forgedSlotLast_int`, `forging_enabled_int`, `currentKESPeriod_int`, `remainingKESPeriods_int`, `operationalCertificate{Start,Expiry}KESPeriod_int`, `connectionManager_{inbound,outbound,duplex,fullDuplex,unidirectional}Conns_int`, `peerSelection_*`, `inboundGovernor_*` (all with the `cardano_node_metrics_` prefix, the integer ones gauges). `txsInMempool`, `mempoolBytes`, `txsProcessedNum` and `Forge.didnt-adopt` did not appear (no mempool traffic or lost battle) and are named from the binary's metric table
  - **Commit:** cardano-node 11.1.3 (reference image)
  - **Environment:** 2026-10-16, devnet, `strings` of the binary for the unscraped names

- **Run:** `devnet.py soak --seconds 480` with the metrics scrape (J3)
  - **Result:** 24/24 comparisons matched, 236 blocks applied, 0 reconnects, no error; at the end `/metrics` on the query server (7071) and on `--metrics-listen 127.0.0.1:12798` each gave 145 series, the required ones all present, the text well formed, `blockNum` 233 and 234 between the log's readings, `scarab_blocks_applied_total` 235 and 236 equal to the `extended` events, every block timed, 0 validation errors. (A first run, on 98da8b20, matched 23/23 with the same checks.)
  - **Commit:** 1c802338
  - **Environment:** 2026-10-16, reference 11.1.3 image, `scarab-jdk:local`, Scala node on 2 CPUs, host load 30 to 35 on 24 CPUs

- **Run:** `devnet.py pool --seconds 480` with the metrics check (J3)
  - **Result:** passed: 81 forged, 81 adopted, 1 slot battle lost, 0 failed; `Forge_about_to_lead`, `node_is_leader`, `forged`, `adopted` and `didnt_adopt` counters between two readings of `GET /forging`, the KES gauges present. A first run failed on "no leadership schedule for epochs [10]" (the oracle's schedule query for an epoch that had run 22 slots), with no metrics failure; the second run passed
  - **Commit:** 98da8b20 (1c802338 and 98939131 after it change only how the header counts are summed and how a label's case name is read; their suites cover both)
  - **Environment:** same

- **Run:** `devnet.py kes-rotate` (H2a, #116): the Scala node as a pool with `slotsPerKESPeriod` 250, `maxKESEvolutions` 24 and `scripts/pool/rotate_kes.py` on a 10 s loop with the pool's cold key (never mounted into the node)
  - **Result:** passed (the second of two runs of the final build; the first failed only on `no leadership schedule for epochs [6]`, the oracle query that the J3 row above also lost once, with every rotation check green): 4 reloads (counters 1 to 4) in 300 s, one `forgeStarted` (no restart), 0 refused, 0 `kesPersistFailed`, 29 driver runs all exit 0, the pool's counter taken from the node's `query kes-period-info` (no fallback); 53 forged, 52 on the final chain, 0 missed of 53 scheduled leader slots; blocks forged with counters 0 to 3 (4 was installed too late for a block), none older after a newer one; the Haskell nodes report the pool's counter as 3 = the last block on the chain, Haskell blocks-made 52 = Scala on-chain 52; 10/10 comparisons; only the newest generation directory remains, the KES key file rewritten by the node after the last certificate
  - **Commit:** 903690cb
  - **Environment:** reference 11.1.3 image, `cardano-cli` 11.2.3 from `ghcr.io/intersectmbo/cardano-node:11.1.3`

- **Run:** `devnet.py pool` and `e2e` with the KES key now written back (`/keys` mounted writable)
  - **Result:** `pool` passed: 75 forged, 75 adopted, 75 on the final chain, 0 of 74 scheduled slots missed, 19 KES periods crossed, 16/16 comparisons, Haskell blocks-made 73 = Scala on-chain 73; `e2e` passed (4 Allegra, 4 Mary, 8 Alonzo, 8 Babbage, 30 Conway comparisons matched)
  - **Commit:** 903690cb
  - **Environment:** same

- **Run:** `devnet.py e2e`, `restart`, `ntc`, `relay --seconds 300` (J3)
  - **Result:** all passed: e2e 12 Shelley, 4 Allegra, 4 Mary, 8 Alonzo, 8 Babbage and 27 Conway comparisons matched; restart stopped the JVM in 1.4 s and 1.0 s with the second listener open; ntc and relay as before (the relay's inbound duplex session followed the Haskell relay)
  - **Commit:** 98da8b20 (1c802338 and 98939131 after it change only how the header counts are summed and how a label's case name is read; their suites cover both)
  - **Environment:** same

- **Run:** Typed logging (J3a): `devnet.py e2e`, `soak --seconds 300`, `relay`, `ntc`, `restart`, `pool` with the scenarios unchanged
  - **Result:** all passed: e2e 12 Shelley, 4 Allegra, 4 Mary, 8 Alonzo, 8 Babbage and 30 Conway comparisons matched; soak 15/15 matched, 160 blocks, the metrics checks as before (`scarab_blocks_applied_total` 160 equal to the `extended` lines); relay and ntc passed; restart stopped the JVM in 0.8 s and 0.6 s; pool 65 blocks forged. No `SLF4J:` text in any follower log; the http4s start lines are `slf4j` JSON lines
  - **Commit:** 639ef7f4
  - **Environment:** 2026-10-16, reference 11.1.3 image, `scarab-jdk:local`

- **Run:** Typed logging (J3a): Preview from genesis for 240 s, `--log-file <dir>/node.jsonl --log-file-size 1M --log-file-keep 3 --log-every stdout:Chain.Extended=1000 --log-severity stdout:Storage=Warning --log-stderr human`
  - **Result:** about 107,000 blocks applied (to slot 2,251,254); the file turned over at 1 MiB: `node.jsonl` and `.1` to `.3` kept (1,048,357 to 1,048,385 bytes each, no line split), the older ones dropped; stdout had 107 `extended` lines (one in 1000) and no `Storage` line, the file the `snapshotWritten` and `shutdownSnapshot` lines; stderr (Warning and above) stayed empty
  - **Commit:** 639ef7f4
  - **Environment:** 2026-10-16, public Preview relay, `fixtures/preview/genesis`

- **Run:** Metrics during a replay (J3b, #95): a preprod follower restarted from a snapshot 31,204 blocks behind its immutable tip, `--metrics-listen` scraped every second
  - **Result:** the endpoint answered from the first seconds; `scarab_node_phase` went Restoring, Replaying (target block 5,297,780, `scarab_replay_block` at 5,286,576 after 20,000 blocks, then the target), Following about 3.5 minutes later; `cardano_node_metrics_blockNum_int` absent until Following; 0 errors
  - **Commit:** f92b31e9
  - **Environment:** 2026-10-16, `scarab-jdk:local`

- **Run:** Leios testnet `musashi`, read-only (N3, leios.md section 11): the whole chain's headers captured from Origin (154,698 in 200 s over one connection, version 14) and validated by `LeiosCheckMain` from the genesis of the published configuration (`config.json` of `book.play.dev.cardano.org/environments-pre/leios`), 255 s on one core
  - **Result:** 154,698 headers accepted, 0 rejected: 4,255 of era index 6 and 150,443 of 7 (Dijkstra from block 4,255, slot 86,463), 147 issuers; chain links, operational certificates, KES, VRF under the evolved nonces; leader threshold evaluated for 2,112 (epochs 0 and 1), unchecked for 152,586; six fetched blocks decoded, body hash and size equal to the header's; the node's own `CaptureMain` stops at block 0 with `decode header: expected 10-element array`; the relay closed the connection on a LeiosNotify pull (versions 14 and 16) and on a LeiosFetch request; ten connections in all, one at a time
  - **Commit:** n3/leios-follow, 550e2560 to 0b223300 on 83afd506
  - **Environment:** `scarab-jdk:local` container, relay `leios-node.play.dev.cardano.org:3001`, node build named `11.1.0.164-prototype-2026w38a`

- **Run:** `devnet.py --name leios e2e` and `relay` on the N3 branch (nothing of the node changed for a network without Leios)
  - **Result:** e2e passed: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28 comparisons matched, 0 mismatched, 2 Conway comparisons without a stable bracket (counted as errors by the table, the run exits 0); relay passed (the Haskell relay synced the Scala node's chain, 168 blocks at 5 minutes, heights and tips equal)
  - **Commit:** 0b223300
  - **Environment:** `scarab-jdk:local`, `cardano-reference-11.1.3:local`, `--scala-cpus` default

- **Run:** Peer governor G1.1: devnet `relay --seconds 300 --peers 3` and `--peers 1`
  - **Result:** passed both: 3 hot local roots (`GET /connections` `peerSelection`: hot 3, known 3), 199 and 194 blocks applied, 0 reconnects, 0 errors, all tips equal; steady state 7.7% and 8.9% of a 2-CPU container, 279 and 275 MiB
  - **Commit:** 5fd3d690 (G1.1 branch on main 9970ba73)
  - **Environment:** devnet, Scala node 2 CPUs 2 GB, `docs/performance.md` section 18

- **Run:** Peer governor G1.1: devnet `peercut` and `pool`
  - **Result:** `peercut` passed: the silent peer lost with `unavailable: chain-sync timed out in NextCanAwait`, 1 `reconnecting`, 8 `peerSelectionPromote` and 2 `peerSelectionDemote` events, the node reached the reference tip (block 171) and kept extending, 0 stalled, 0 error; `pool` passed: 0 missed slots
  - **Commit:** 5fd3d690
  - **Environment:** same

- **Run:** Peer governor G1.2: devnet `governor`
  - **Result:** passed: targets reached (3 established, 2 hot); 5 churn rounds of six steps in the reference's order, one hot peer demoted each, hot count back at 2; the slow peer demoted both times it was hot (lowest score); the silent peer lost, the node extended its chain on the others and the peer came back after release; `churn_rounds_total` equals the log, boards at 180, 0 errors
  - **Commit:** 1cf7833c
  - **Environment:** 2026-10-17, devnet, Scala node 2 CPUs, `scripts/devnet/scenarios/governor.py`

- **Run:** Peer governor G1.2: devnet `relay --seconds 300`, `peercut`
  - **Result:** `relay` passed (3 hot peers, tips equal, 0 errors); `peercut` passed on the second run, the first lost the slow peer as `diverged` (#152)
  - **Commit:** 1cf7833c
  - **Environment:** same

- **Run:** Peer metrics callbacks, Preview from genesis, base `5f3b6174` against 1cf7833c
  - **Result:** 188,373 and 188,979 blocks in 660 s, 0 reconnects or errors; micro-benchmark 0.3 to 0.5 us per header, 0.3 to 0.9 us per block
  - **Commit:** 1cf7833c
  - **Environment:** 2026-10-17, 2 CPUs each, `docs/performance.md` section 19

- **Run:** Peer governor G1.1: Preview follower, official `config.json` and `topology.json`, no `--peer`, 30 min (2026-10-17)
  - **Result:** from genesis (a pre-J2.9 database could not be replayed, #145): connected to the bootstrap peer at once (cold, warm and hot within 0.2 s; `TooOld`, `sensitive`, clean state, `UntrustedState` then `TrustedStateWithExternalPeers`; GSM `preSyncingToSyncing`), kept it hot for the 30 minutes, 517,605 blocks applied (epoch 132, slot 11.46 M, 0 error, 0 stalled, 0 reconnecting). The tip was not reached (Preview is at epoch 1450 or so) and the public network has one bootstrap peer, so no demotion and no target beyond the sensitive state's 2 active was exercised there
  - **Commit:** 5fd3d690
  - **Environment:** `-Xmx3g`, 3 CPUs, 6 GB container, G1; mean 204% CPU, 1.8 GB resident

- **Run:** Peer governor G1.1 acceptance (level 4): Preview follower from a restored snapshot to the tip, official `config.json` and `topology.json`, no `--peer`, 30 min (2026-10-17)
  - **Result:** met, with one peer. Database migrated on open (identity, 5,807 indexes, snapshot to format 5), snapshot restored and 975 blocks replayed in 3.5 s; governor started `TooOld` (sensitive state, bootstrap targets active 2, `UntrustedState`, clean), the bootstrap peer cold to warm to hot in 0.3 s (`TrustedStateWithExternalPeers`), GSM `preSyncingToSyncing`; 6,653 blocks applied in 16.1 s (412 blocks/s mean, 618/s peak), then GSM `enterCaughtUp`, judgement `YoungEnough`, sensitive state ended, fetch mode `Deadline`, targets to the relay regime (root 60, known 150, established 30, active 20). 29 further minutes at the tip: 70 blocks, tip within one block of the peer's, cold 0 / warm 0 / hot 1 / known 1 / root 1 at every scrape and in `GET /connections`, 15 public-root requests (backoff 2 to 256 s, none found), no demotion, 0 errors, 0 stalls, 0 reconnects, clean shutdown with a snapshot. The network offers one peer to this configuration (one bootstrap peer, no roots; ledger peers are G1.3), so the targets stayed unmet and demotion, forgetting and the 20-hot target were not exercised on Preview (the properties, `FortyBearersSuite` and the devnet show them). Design section 7b
  - **Commit:** 5f3b6174
  - **Environment:** `-Xmx3g`, 3 CPUs, 5 GB container, `scarab-jdk:local` (JDK 21.0.11), copy of a format-1 Preview database; CPU mean 9.9% of one CPU over the run (127% in the first minute, 5.4% at the tip, peak 277%); memory mean 1.74 GiB, peak 2.50 GiB

- **Run:** Ledger peers G1.3a (pure part of #137): pool relays, stake maps, draws, snapshot file
  - **Result:** `PoolRelaysSuite`, `LedgerPeersSuite`, `PeerSnapshotSuite` and the `pool-relays` and `peer-snapshot` fuzz targets pass in `check`: the relays of the real mainnet registration at slot 4,495,760 (one IPv4 relay, port 3001, `03652f85` read in network order) decode and re-encode to its bytes; the 90% quota (the crossing pool and the exact-90% boundary kept), the square-root and stake weightings and the 4.5-sigma draw proportions over 200,000 draws match the formulas of `LedgerPeers.hs`/`Utils.hs`; the slot rule of `stakeMapWithSlotOverSource` over all slot combinations; the Leios testnet `peer-snapshot.json` (version 23) read with exact stakes; 100,000 pools on a 256 KiB stack
  - **Commit:** this branch (on main 5f3b6174)
  - **Environment:** CPU only, no devnet: nothing here reads a ledger state yet. Historical G1.3a limits: IPv6 order was then unverified and proved wrong. G1.3b corrects it with two mainnet registrations and one Preview registration; live pool discovery is recorded below

- **Run:** G4 resume validation (2026-10-18): Docker `check`; `python3 -m unittest discover -s scripts/tests`; relative Markdown file links
  - **Result:** Full check, including every module test, decoder fuzz suite and scalafix, passes after rebasing onto negotiated NtC queries. All 157 Python tests and 99 relative file links pass. `ProtocolVersion` remains identical to the landed shared definition; NtC offers still cover 16–23 and session version handling is retained. The preceding G4 relay, cross-check and extended Leios fuzz evidence below applies unchanged; no public-network run was started for this resume
  - **Commit:** code `a6d50a2d`; restored/reconciled docs `653546bd`
  - **Environment:** Docker `scarab-jdk:local`, JDK 21; check completed 2026-10-18 03:51:19 UTC

- **Run:** Handshake versions G4 (#34): `check`; `devnet.py relay --seconds 300` at the default and with `--max-ntn-version 16`; `LeiosProbe ... ping-only` against the public Leios relay; `cddl_crosscheck.py run`; `fuzz 100000 1 4` on `leios-fetch` and `leios-notify`
  - **Result:** both relay runs passed (default: with `ExperimentalProtocolsEnabled` off for the Haskell relay and the Scala node, the relay proposed 14 and 15 and the Scala node accepted `NodeToNodeV_15`; with `--max-ntn-version 16`: the relay proposed 14, 15 and 16 and the Scala node accepted `NodeToNodeV_16` with data `[1082026, false, 1, false, false]`; 207 and 209 transactions included in blocks, the relay at the tip in 57 of 57 samples, epochs 1 to 7, no error event, all tips equal); the public relay accepted version 16 from the probe (initiator only, magic 164); the cross-check passed with the new `leios-fetch` root (33/40 cuddle terms decoded byte-exact, the 7 others hold floats: allowlisted); 400,000 cases each on the two Leios fuzz targets found one bug (the writers re-encoded a transaction and an announced header), fixed; handshake negotiation checked over 7 x 7 version sets x 16 x 16 flag settings
  - **Commit:** branch `g4/handshake-v15-v16`
  - **Environment:** 2026-10-17, reference 11.1.3 image, `scarab-jdk:local`, 2 CPUs each

- **Run:** CI rejection regression (#181, 2026-10-18)
  - **Result:** A forced healthy-first control reproduces the original missing-rejection timeout. The corrected test holds healthy headers until a typed invalid-block rejection and observes malformed bytes consumed from the original stream; it then checks healthy catch-up, released claims and no fatal error. All 32 focused tests and full Docker check/scalafix pass. Original failed CI39138433645 is retained. Production and private harness sources are unchanged from72ac3db9, whose E2E39138443978 passed all seven scenarios
  - **Commit:** this commit
  - **Environment:** Docker JDK21; full gates completed 2026-10-18 15:26:09 UTC; existing timeout unchanged

- **Run:** Step D completed integration (#140, 2026-10-18): Docker `check`, `scalafixAll --check`, `node/runtimeClasspathFile`; Python script checks
  - **Result:** Full formatting, warning-clean compilation, every module test, 71 fuzz/guard tests and scalafix pass on current main plus the complete import/export implementation. All 174 Python checks and 110 relative Markdown file links pass. Production/build files are identical to the pre-integration source used for the epoch-320 and 6,141-block captured-point acceptance below; V5 and stored CBOR remain unchanged
  - **Commit:** this commit
  - **Environment:** Docker JDK21; full gates completed 2026-10-18 14:57:53 UTC; source/reference/restart/extended-fuzz evidence below

- **Run:** Step D S3 reference import (#140, 2026-10-18): original Haskell fixtures and signed preprod ancillary, recorded local immutable chunks
  - **Result:** All six committed Shelley-based state/table fixtures reconstruct exact instant stake and metadata CRC. Authenticated preprod import: all six file digests and signature verified; 4,526,058 outputs, 30,022,461,204,412,072 lovelace, tip slot 136,511,997/block 5,300,868; original 32,511,416-byte state retained, unchanged V5 and checkpoint restore count/stamp. Import 57.1 s. Full Docker check/scalafix, 66 decoder targets × 400,000 cases and 159 script tests pass. Private restart: 16 blocks, stops 0.6/0.6 s, two shutdown snapshots, one restore, no errors, unchanged V5. Typed late-publication rollback and cleanup-denied regressions pass. Later S4/S5 export/cross-node/epoch acceptance is recorded below; no new public run
  - **Commit:** S3 production `7db6323f`, with shared container lease binding
  - **Environment:** Docker JDK21; node11.1.3 signed ancillary; `PreprodSnapshotImportSuite`, offline pinned inputs

- **Run:** Step D S4 reference export (#140, 2026-10-18): original six-era fixtures and authenticated preserved preprod checkpoint
  - **Result:** All six original state files retained byte-for-byte; tables equal as decoded outputs, metadata valid, read-only sources unchanged. Preprod: 4,526,058 outputs, exact original 32,511,416-byte state and 1,014,553,955-byte canonical table, metadata equal; whole database file SHA-256 unchanged. Export 30.8 s. Full check/scalafix and 21 focused export/publication/CLI regressions pass; Completed S5 comparisons and their scope limits are below
  - **Commit:** S4 core `b633bc50`
  - **Environment:** Docker JDK21; original node11.1.3 fixtures and authenticated pinned preprod artifact, fully offline

- **Run:** Step D S5 reference acceptance (#140, 2026-10-18)
  - **Result:** Haskell loads the private slot-7 S4 export and validates/replays 175 recorded blocks; all six private epoch boundaries match. Fresh imported Scala replay and real Haskell node startup/replay/shutdown exit zero. Private endpoint 3436 retains one unmasked pending-versus-complete reward-update difference. Preprod: original 2,377-block endpoint matches; fresh authenticated-import replay through epoch 320 matches at slot 136,598,413 and immutable endpoint 136,602,570. A separate fresh copy replays 6,141 blocks to slot 136,651,599/block 5,307,009, the exact point an existing node reported 100% synced; Haskell fully validates from the gap-free S4 export, and the existing oracle projection has zero differences. Captured volatile rollback/CRC/header reconstruction is independently verified. This is fixed-capture catch-up, not ongoing public following. Earlier post-replay gap flags refuse further export; no endpoint CRC claim
  - **Commit:** pre-integration `0b2efe56`
  - **Environment:** Docker JDK21/reference11.1.3; read-only recorded inputs and fresh local working copies, all runtime networking disabled; design section12

- **Run:** Step D S2 final integration (#140): rebase onto ledger-peers main, Docker `check`
  - **Result:** Clean rebase with unchanged S2 production files; full check, all module tests, 68 default fuzz/guard tests and scalafix pass. The preceding full-table, extended fuzz and private restart evidence applies unchanged
  - **Commit:** production `1585171a`; linear helper `14b89ce2`; tested tree `fb8defaa`
  - **Environment:** Docker JDK 21; completed 2026-10-18 04:34:34 UTC

- **Run:** Step D S2 gates (#140, 2026-10-18): Docker `check`, `scalafixAll --check`, `fuzz 100000 1 4`, private `restart`
  - **Result:** Full check and all 64 decoder targets × 400,000 cases pass (25,600,000 total). The table fuzz property retains linear allocation on 4,096 entries. Local restart: two graceful stops in 0.7/0.6 s, two shutdown snapshots, one restoration, 24 blocks followed, no error events; every snapshot remains `SCRBSNP5`. V5 and stored CBOR are unchanged
  - **Commit:** production `31f5c3f4`; linear fuzz helper `c7dd9420`
  - **Environment:** Docker JDK 21; private node 11.1.3 cluster; required storage-area gate

- **Run:** Step D S2 reference streaming comparison (#140): all 4,526,058 outputs of the pinned preprod table read, converted from MemPack to `TxOut` and back, and written in reference framing
  - **Result:** The 1,014,553,955-byte output is byte-identical (`Files.mismatch = -1`); CRC32 1172950672 and metadata checksum 2668385107 match. All 22 focused framing/streaming/storage-corruption tests pass; both pure and fs2 one-byte-chunk walks cover 20,000 entries on a 256 KiB worker. Conversion/export comparison took 50.1 s. Source fixture files match all three committed SHA-256 pins; this is a local fixture comparison, not an authenticated snapshot import
  - **Commit:** `31f5c3f4` (2026-10-18)
  - **Environment:** Docker JDK 21; node 11.1.3 preprod ancillary fixture; `SCARAB_MEMPACK_TABLES` full-file test

- **Run:** Step D S1: reference MemPack TxOut codec (#143)
  - **Result:** Era-indexed Shelley–Conway codec; real preprod table 4,526,058 outputs decoded, 1,731,810 joined stored outputs pack to identical reference bytes; all six constructors and reference-script kinds. Final full check (including scalafix), 47 codec tests and four seeds × 100,000 mempack-txout fuzz cases pass. Stored V5/CBOR unchanged; tables framing/import/export remain #140 S2–S5. Measurements and input provenance: design section 8 and performance section 21
  - **Commit:** c881e87a (2026-10-18)
  - **Environment:** Docker JDK 21; reference node 11.1.3; `check`, `fuzz 100000 1 4 mempack-txout`

- **Run:** REF-11: context collection/payload parity (#176)
  - **Result:** 24 actual signed Conway/PV10 submissions (12 included/12 refused), 145 unchanged released-API rows (66 original/79 derived), 91 exact collection-failure CBOR values and 14 direct certificate-purpose constructor encodings; ten complete raw LocalTxSubmission context refusals. Reference model/error/request ordering, typed output-source/governance payloads, invalid V2/V3 pointers, anchored votes and noncanonical pool/proposal diagnostic normalization; original hashes and snapshot format unchanged. 20,000-element small-stack controls; full check/scalafix and extended fuzz 69×400,000 pass. Ordinary seven-era e2e: 1,688 applied blocks, 50 Byron signatures, 66 equal projected ledger comparisons, zero validation/fatal/reconnect/starvation. NtC 18 rounds, version replies 88/88 and expected refusals 48/48; 23 supported CLI queries equal, ledger-state text remains 0/1 under the existing [mirror Jump3 boundary](../design/ledger-mirror-step2.md) (#82). Derived probes are API-only, not admission evidence
  - **Commit:** this change, 2026-10-18
  - **Environment:** Docker JDK21; node11.1.3/CLI11.2.3.0 actual submissions; unchanged released ledger APIs built with GHC9.10.3, source/package/provenance and reproduction in fixtures/reference/context-errors

- **Run:** REF-12: optional spending datum (#177)
  - **Result:** 18 complete actual reference Conway/PV10 transactions: V1/V2/V3 missing/supplied/no datum, both validity flags; 4 included with shared k+1 confirmation, 14 refused with raw ApplyTxErr. Every missing-hash case is MissingRequiredDatums in phase1; V3 no-datum valid control is included. SpendingDatumSuite: original signed bytes, optional V3 data/legacy arity, standalone phase2, supported-protocol phase1 checks, full LEDGER/BBODY verdicts and funding/script/collateral effects. Full check/scalafix passes; ordinary seven-era e2e: 1752 blocks, 50 Byron signatures, 66 equal projected ledger comparisons, zero validation/fatal/reconnect/starvation events
  - **Commit:** this change, 2026-10-18
  - **Environment:** Docker JDK21; official node11.1.3/CLI11.2.3.0; fixtures/reference/spending-datum

- **Run:** REF-02/03: era-specific Plutus V1 context construction (#164, #165)
  - **Result:** 74 reference-submitted cases: Alonzo PV5/6, Babbage PV7/8, Conway PV9/10; 31 included and 43 refused, with V2/V3 controls and both validity flags. Retained original transaction bytes and reference verdicts in fixtures/reference/v1-context; V1ContextSuite replays phase 2, transaction and block validation. ScriptContextV1EraSuite includes Conway PV11 and ordering checks. All contexts are prepared before any evaluation. Full check/scalafix passes. Reference predicate payloads and collection ordering are covered by #176 above; optional spending-datum treatment is covered by #177 above; additional boundary executions are recorded under #175
  - **Commit:** 4246312a (code), 95ed8951 (reference fixtures/driver), 2026-10-18
  - **Environment:** Docker JDK 21; official reference node11.1.3; regeneration commands and provenance in fixtures/reference/README.md

- **Run:** REF-02/03 integration: `devnet.py --name scarab-resume-v1-context e2e`
  - **Result:** Passed: 1,692 applied blocks through Conway epoch17; 50 Byron main signatures verified; all66 projected ledger comparisons match (Shelley12, Allegra4, Mary4, Alonzo8, Babbage8, Conway30), no comparison errors or skips. No validation/fatal errors, reconnects or CPU starvation warnings. The separate74-case reference context fixtures are replayed by the ledger suites; 153 Python script tests pass
  - **Commit:** 4246312a, 2026-10-18
  - **Environment:** Docker JDK21; official reference node11.1.3 / CLI11.2.3.0; full check includes scalafix

- **Run:** Ledger peers G1.3b (#137), Preview acceptance
  - **Result:** official files, no --peer, 1,860 seconds; tip reached in29 seconds, big-ledger known/established/active15/10/5, 20 ordinary and5 big hot sessions; ledger maps583 ordinary/68 big pools beat the older snapshot. One twelve-step churn round, steps1–10 met, steps11/12 timed out below target; no errors/stalls. Mean CPU7.5% of one CPU after first100 seconds; mean RSS1.44GiB, peak1.59GiB. Real IPv6 registrations and all60 decoder targets ×400,000 cases pass; mainnet-size discovery and repeated live churn not claimed
  - **Commit:** d05e38d8 (Preview run); rebased branch check/fuzz on60dba553 base, 2026-10-18
  - **Environment:** Docker JDK21,3 CPUs,-Xmx3g; design7d/7e and performance23; remaining #152

- **Run:** G1.3b final integration gates, `check`, `fuzz 100000 1 4`, Python tests, `devnet.py --name scarab-resume-ledger-peers peercut`
  - **Result:** Full check including scalafix passes on the published S1/G4/context base; 60 decoder targets ×400,000 cases and158 Python tests pass. Private peercut: backlog150, cut58, caught up to155, continued to175 equal to reference175; one ChainSync timeout reconnect, no stalled/error events or unparsed output. The harness checks the closed Transient demotion policy; same-connection reintersection remains #152
  - **Commit:** final code `2f1b7295`, 2026-10-18
  - **Environment:** Docker JDK21, official reference11.1.3; earlier relay/governor and bounded historical Preview acceptance above apply; no new public-network run

- **Run:** Offline REF-07 header and succession boundary follow-up
  - **Result:** pinned CLI decoded five complete public block variants and rejected four header width overflows; GHC9.10.3 executed the unchanged Word16/Word16/Word8 `pvCanFollow` excerpt for eleven transitions (six true/five false); [fixtures, hashes, exact decoder output and scope](../../scripts/oracle/byron/README.md). Decoding does not verify mutated signatures; predicate proof does not execute full reference registration.
  - **Commit:** test/fixture-only follow-up to 1db20006; no production changes
  - **Environment:** 2026-10-18; all executions in throwaway Docker containers with network disabled
