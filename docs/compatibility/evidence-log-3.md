<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence log, part 3 of 5

Part of the [compatibility matrix](../compatibility.md#evidence-log). Each run is one bullet with its former table columns as sub-bullets; the text of every cell is unchanged.

Other parts: [part 1](evidence-log-1.md), [part 2](evidence-log-2.md), [part 4](evidence-log-4.md), [part 5](evidence-log-5.md).

- **Run:** `devnet.py --name f132-ntc-final ntc` (#132, #154)
  - **Result:** All protocol checks passed: 18 rounds, 986/986 implemented query comparisons with the existing ledger-state gap mask; 88/88 supported versioned replies and 48/48 out-of-contract refusals; 23 supported CLI queries and 20 raw/text transaction refusals equal; valid, flagged and CLI-built submissions included; all 76 local ChainSync blocks equal; 573 comparable monitor snapshots (224 nonempty) equal, both awaiters wake, fresh clients healthy after refusals. Original runner exited 1 solely because normal EOF is now rendered `connection closed: closed by peer`; the exact whitelist adaptation and negative controls pass 158 Python tests, and replaying the retained user-CLI close observations clears that sole failure
  - **Commit:** runtime `2c40dd00`; tooling `b42cbc2d`
  - **Environment:** 2026-10-18, private devnet, default 240 s comparison phase / 60 s warmup; reference node 11.1.3, Docker JDK 21; no production change or repeat network run for the diagnostic adaptation

- **Run:** REF-04 final gates (2026-10-18): Docker `check`; `node/testOnly *GenesisConfigSuite *QueryVersionSuite *QueryGoldenSuite *NtcGoldenSuite *DeepQuerySuite`; Python script tests; `fuzz 100000 1 4`, followed by `fuzz 100000 1 4 ntc-query` after sharing the G4 protocol-version types
  - **Result:** Full check, including scalafix, passes. All 38 targeted tests and 156 Python tests pass. Extended decoder campaign: 60 targets × 400,000 cases; both NTC decoder targets refreshed with another 400,000 each. Genesis parsing reports closed typed failures; the valid legacy/current byte goldens remain unchanged, so the completed live comparison above applies
  - **Commit:** final check and typed-genesis goldens `60dba553`; live/version refresh `2dc82a40`
  - **Environment:** Docker `scarab-jdk:local`, JDK 21; exact reference image/version recorded above

- **Run:** REF-04 / REF-05 / REF-08, `devnet.py --name scarab-resume-ntc ntc` (2026-10-18)
  - **Result:** Passed: 17 rounds, 931/931 implemented query comparisons (the existing ledger-state gap mask applies); V16–V23 explicitly negotiated, 88/88 supported replies byte-equal to Haskell and 48/48 out-of-contract Scala refusals. At V20/V21, Conway protocol parameters are equal, genesis parameters differ, and `GetCBOR` genesis answers are equal as the reference specifies. A 20,000-layer `GetCBOR` answer equals the shallow Haskell leaf; three valid HFC forms match, eleven malformed forms close on both nodes, deep unsupported queries close and a new session remains healthy. Twenty transaction refusals match as raw bytes and CLI text; valid, flagged and CLI-built transactions reach blocks. All 76 local ChainSync blocks and 313 monitor comparisons (179 nonempty) match; 23 supported CLI query outputs match, with the existing ledger-state text gap retained. No unexpected user-CLI close
  - **Commit:** `2dc82a40`, combined tree based on `d5139660` with the HTTP point fix and query-safety changes
  - **Environment:** Docker `scarab-jdk:local`, JDK 21, `cardano-reference-11.1.3:local`; run data removed after preserving summaries outside the checkout

- **Run:** Pool monitoring ([#117](https://github.com/KD5UJC-XYZ/scarab/issues/117)): `python3 -m unittest discover -s scripts/tests`, Prometheus 3.5.0 `promtool check config` / `test rules`, live dashboard/rule/metric inspection; `devnet.py --name scarab-resume-kes kes-rotate`
  - **Result:** 153 Python tests pass, including seven dashboard tests and two timestamp-precision regressions; all 17 alert rules validate and their rule tests pass. Live dashboard discovered, 17 rules loaded, fresh rotation timestamps and remaining periods scraped. The 300 s rotation devnet passes: four reloads, 29 driver runs all successful, 59 forged/adopted/on-chain blocks, 89 included transactions, 10/10 reference comparisons, no missed scheduled slots or error events. Full `check`, including scalafix, passes. Notification routing remains operator configuration
  - **Commit:** `bea81d57`, root tree also containing `dd7e2d34`
  - **Environment:** 2026-10-18, Docker JDK 21, reference 11.1.3 and Prometheus 3.5.0

- **Run:** Loopback HTTP state requests ([REF-06, #170](https://github.com/KD5UJC-XYZ/scarab/issues/170)): `node/testOnly *QueryServerSuite`
  - **Result:** all three route tests pass: 13 malformed, partial or duplicate point requests return 400 without reading state; bare `/state` and a retained explicit point return 200; well-formed unavailable points return 404, including slot 2^64-1. This API is Scarab-specific; no Haskell REST parity is claimed
  - **Commit:** `dd7e2d34`
  - **Environment:** 2026-10-18, JDK 21 Docker image

- **Run:** REF-05 / REF-08, `node/testOnly *NtcQuerySuite *DeepQuerySuite *QueryGoldenSuite` (2026-10-18)
  - **Result:** 27 tests passed: all query walks, including an acquired session's refusal diagnostic, run with 20,000 nested `GetCBOR` wrappers on a 256 KiB worker; the query fits the unchanged transport byte/item limits, and over-item-limit input is refused. Pre-Conway gating uses the public era capture through its first Shelley block. Exact valid/invalid HFC constructors follow `ouroboros-consensus` `82ecba3`, `HardFork/Combinator/Ledger/Query.hs:536-551` and `Serialisation/SerialiseNodeToClient.hs:410-417,460-481`; existing Haskell answer goldens remain equal. This row is source/fixture evidence; the live malformed-query comparison belongs to the combined `ntc` run.
  - **Commit:** `97095b3e`
  - **Environment:** Docker `scarab-jdk:local`, JDK 21; stdlib small-stack worker, `SyncIO` session

- **Run:** REF-07 gates: Docker `check`, `scalafixAll --check`, `fuzz 100000 1 4`
  - **Result:** all passed; extended fuzz 59 targets × 400,000 cases (23.6 million); 15 signed complete-block/version cases and five retained raw reference proposal cases; preprod Byron replay and historical snapshot byte/schema goldens unchanged
  - **Commit:** 1db20006 (production/fuzz), 805b30fc (fixture tests, final check)
  - **Environment:** 2026-10-18; `scarab-jdk:local`, JDK 21.0.11

- **Run:** `devnet.py --name ref07-e2e e2e` (REF-07, #171)
  - **Result:** passed: 1,687 blocks applied through Conway epoch 17, 50 Byron main signatures verified; 66 ledger comparisons matched (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30), no mismatch, comparison error or skip; 0 validation/fatal errors, reconnects or CPU starvation warnings; the CLI cannot query Byron directly, its UTxO is checked after hand-over to Shelley
  - **Commit:** 1db20006 (production; 805b30fc adds fixtures/tests only)
  - **Environment:** 2026-10-18; official reference node 11.1.3 / CLI 11.2.3.0, `scarab-jdk:local`

- **Run:** Raw Byron proposal decoding (REF-07, #171), `cardano-cli byron governance submit-update-proposal --testnet-magic 4242 --socket-path /oracle/no-such-node.socket --filepath <case>`
  - **Result:** signed `(65535,65535,255)` proposal decoded and reached the absent socket; byte-mutated major/minor 65536, alt 256 and major 4294967297 rejected during CBOR decoding with `expected word16`/`expected word8` before connection; [fixtures, exact errors and provenance](../../fixtures/byron-version/README.md); the valid result is not ledger acceptance, and this historical decoder result is not registration evidence; later offline evidence below separately executes full registration and signed block composition
  - **Commit:** 1db20006 (implementation)
  - **Environment:** 2026-10-18; CLI 11.2.3.0, GHC 9.6, bundled in official node 11.1.3 release; CLI binary SHA-256 and image ID in the fixture manifest

- **Run:** `devnet.py soak --seconds 480`
  - **Result:** 24/24 comparisons matched, epochs 0 to 9, no error
  - **Commit:** 788f51c
  - **Environment:** reference 11.1.3 image, `cardano-scala-jdk` (then named `cardano-public-v023-check:local`)

- **Run:** `devnet.py soak --seconds 900`
  - **Result:** 41/41 matched, epochs 0 to 18, through the hard fork to protocol 11, a no-confidence action and a committee election
  - **Commit:** 788f51c
  - **Environment:** same

- **Run:** `devnet.py soak --seconds 900 --governance-extended`
  - **Result:** 44/44 comparisons matched, epochs 0 to 18; all nine stages reached; 9 transactions refused by the reference with the expected predicate failure (2 reference-input, 2 unelected-voter, 5 VRF), 0 unexpected; the reference's ratification enacted the second hard fork only, no-confidence on default pool votes, one of three committee updates, then a parameter change and a withdrawal together, and never the withdrawal proposed after the member expired
  - **Commit:** 08fb267
  - **Environment:** same

- **Run:** `SCARAB_RULES=differential devnet.py --name ltnet e2e` (#121, typed predicate failures)
  - **Result:** every comparison matched (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30), no `rulesDiverged`; the raw `ApplyTxErr` of the 8 expected refusals of Shelley to Alonzo recorded (`fixtures/devnet/refused-raw-eras.jsonl`) and written byte for byte by `PreConwayFailureCbor` (`PreConwayFailureReferenceSuite`)
  - **Commit:** this branch
  - **Environment:** reference 11.1.3 image, `scarab-jdk`

- **Run:** `devnet.py --name h123e e2e` (#123, Plutus and witness refusals)
  - **Result:** passed; every comparison matched (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30); 46 further refusals (Alonzo 13, Babbage 16, Conway 17) recorded as raw `ApplyTxErr` in `fixtures/devnet/refused-raw-plutus.jsonl` and written byte for byte by `PreConwayFailureCbor` and `PredicateFailureCbor` (`PlutusFailureReferenceSuite`, 47 tests); the reference at protocol 9 accepted a Plutus V1 spend with a reference-script input (this run) and one with a reference input (the run before it), so Conway has no refusal for them
  - **Commit:** `760474404` (recorded in each line with the command and image)
  - **Environment:** reference 11.1.3 image (`cardano-node 11.1.3`), `scarab-jdk`

- **Run:** `devnet.py e2e` (with the light extended subset, 08fb267)
  - **Result:** every comparison matched: Shelley 4, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28; script credentials registered, reference-input refusals at 9 and 10, VRF refusals; the earlier run: Conway 30; seven Conway epochs after the transition
  - **Commit:** 788f51c (Shelley to Babbage first matched at 3a0da52)
  - **Environment:** same

- **Run:** `devnet.py e2e` (three Shelley epochs, boundary load)
  - **Result:** every comparison matched: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30; 8 submissions that must be refused were refused for the named rule
  - **Commit:** after fccf509
  - **Environment:** same

- **Run:** `devnet.py e2e` (earlier, one Shelley epoch)
  - **Result:** every comparison matched: Shelley 4, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30; seven Conway epochs after the transition
  - **Commit:** 788f51c (Shelley to Babbage first matched at 3a0da52)
  - **Environment:** same

- **Run:** `devnet.py relay --seconds 300`
  - **Result:** Haskell relay synced solely from the Scala node, at the tip across 7 epochs (lag <= 1 in 59/59 samples), 203/203 transactions included
  - **Commit:** 2cc9840
  - **Environment:** same

- **Run:** `devnet.py --name s9ntc ntc` (S9, 2026-10-15)
  - **Result:** 18 rounds: 987 of 987 answers equal, including `DebugEpochState`, `DebugNewEpochState` and their `GetCBOR` forms (18/18 each) with the gap mask of `gapmask.py`: the mask hid at most 10.3% (epoch state) and 27% (new-epoch state, while the reference's reward update was pulsing in 3 answers) of an answer; no byte outside the masked places differed; other queries unchanged, 0/18 for the three not answered
  - **Commit:** S9 branch
  - **Environment:** reference 11.1.3 image, `scarab-jdk`

- **Run:** `devnet.py --name f163ntc ntc` (#163)
  - **Result:** 17 rounds: 932 of 932 query answers byte-equal; 20 refused transactions equal as raw `ApplyTxErr` bytes and as `cardano-cli` text (18 earlier cases and the withdrawal-network pair; the second of the pair compared by its first failure), all 20 named by the reference for the rule they are built for; a valid transaction reached a block; a transaction with the validity flag false was accepted by the Scala node and took its collateral on both nodes with its spent input kept
  - **Commit:** cacb6d3e
  - **Environment:** reference 11.1.3 image, `scarab-jdk`

- **Run:** `devnet.py ntc`
  - **Result:** 14 rounds under the ordinary and governance load, epochs 0 to 5: 707 of 707 answers byte-equal to the reference's at the same block (51 query forms of the battery, including the governance state, the ratify state, the stake and pool distributions, the UTxO, the genesis configuration, the era history and the protocol state); 23 of the 24 `cardano-cli conway query` commands equal (`ledger-state` is not answered); 18 refused transactions equal as raw `ApplyTxErr` bytes and as `cardano-cli transaction submit` text (the validity-interval ones with the node's current slot masked); a valid transaction submitted with `cardano-cli` to the Scala socket reached a block. Not answered, reported as 0/14: reward-info pools, non-myopic member rewards, ledger peers
  - **Commit:** 520359b
  - **Environment:** reference 11.1.3 image, `cardano-scala-jdk`

- **Run:** `devnet.py --name csl-f2-ntc ntc` (F2 gate, 2026-10-14)
  - **Result:** 15 rounds: 763 of 763 query answers byte-equal; 23 of 24 `cardano-cli conway query` commands equal (`ledger-state` not answered); 18 of 18 refusals equal as raw bytes and as text; the valid transaction reached a block. Local ChainSync with the Scala node on `--db`: 60 blocks from Origin, 12 from the tip and 4 as a backlog, all equal as raw `#6.24(bytes)` items (76,505, 16,495 and 5,399 bytes), the first instruction a roll-back to the point found on both. LocalTxMonitor: 370 rounds of snapshot walks on both nodes, 361 comparable (same slot and transactions), 235 of them with 1 to 4 transactions, 0 differing answers over sizes, measures (also after `NextTx`), membership (held, absent, another era), every `NextTx`; `cardano-cli conway query tx-mempool info`, `next-tx` and `tx-exists` equal on both sockets when empty and in three bursts of 4 chained transactions submitted through the Scala socket; `AwaitAcquire` woke on both. Found and fixed by this run: the transaction size in the mempool sizes (ledger size without `is_valid`, plus 4) and the first ChainSync instruction. Scala node started with `--mempool-bytes 2 * (maxBlockBodySize - 1024)`
  - **Commit:** 082b78b (working tree of the run)
  - **Environment:** reference 11.1.3 image, `cardano-scala-jdk`

- **Run:** `devnet.py --name luna158-final ntc` (#158, 2026-10-19)
  - **Result:** Exit 0 on frozen source `6db8c0607b5fee0278cdb1238f6aedfcae69ad9d` (combined with PR191); runtime classes were rebuilt by the documented Docker JDK workflow from that source and bound by SHA-256 in the harvest. 23 rounds: 1,242/1,242 implemented LocalStateQuery answers equal; the three not-implemented forms (ledger peer snapshot, non-myopic member rewards, reward info pools) were consistently reported as unsupported. Whole, address and input UTxO queries each matched in all 23 rounds. Version matrix 88/88 equal, including 48 expected refusals. CLI whole UTxO, address and input each 1/1 equal; 23/24 CLI commands matched (`ledger-state` was unbracketed, retaining the known mirror gap). All 20 refused transactions matched as raw bytes and CLI text; valid and invalid-flag submissions reached a block. Local ChainSync: 60 blocks from Origin, 12 live and 4 backlog blocks matched byte-for-byte after rollback, with no differences. LocalTxMonitor: 402/402 comparable snapshots, 258 nonempty, no differences; CLI and `AwaitAcquire` matched. Deep-query safety: 20,000 layers answered equally, malformed inputs closed, and healthy queries succeeded after refusals. The required storage restart scenario on the same code source exited 0: two graceful stops in 0.7 s each, V5 snapshots were written and restored, and no refusal/fatal/error events occurred. Harvest and hashes: `/tmp/scarab/luna-158/gates/final-ntc-harvest/` and `final-restart-harvest/full-dwell/`.
  - **Commit:** source `6db8c0607b5fee0278cdb1238f6aedfcae69ad9d`
  - **Environment:** Docker JDK21, private reference 11.1.3, clusters removed after harvest

- **Run:** `cardano-cli` 11.1.3 against a preprod follower's local socket (the `cardano-cli` fix, 2026-10-17; 2 cores, 3 GiB heap)
  - **Result:** `query tip` equal to the chain (epoch 319, slot in epoch and slots to the epoch's end as Koios gives them); `query protocol-parameters` and `query utxo --address` answered (the latter after about 5 minutes: a scan that decoded every output of the whole on-disk set, #113; since then the scan decodes only candidate outputs, 8.0 s to 1.8 s on a copy of the 4.5 M-entry set at the Conway-era point of the same follower, not yet re-timed on the 2-core follower); `transaction build` in 4.4 s; the signed transaction submitted through the socket, admitted to the mempool, offered to the public relay and in preprod block 5,301,263 16 s later (tx c41a6d88eb51b4aa2ffd62232556328017310a6b6c3e373d325c7a756980469f, slot 136,520,735), confirmed by Koios; `mempoolAdopted` when the node applied that block
  - **Commit:** the `cardano-cli` fix commits
  - **Environment:** 2026-10-17, preprod, reference CLI image 11.1.3

- **Run:** `devnet.py soak --seconds 480` (F2/J1 gate, 2026-10-14)
  - **Result:** 23/23 comparisons matched, epochs 0 to 10, 260 blocks, 309 transactions submitted (11 refused by the ledger as expected), no error, no rollback or reconnect; the genesis directory's configuration is now hash-checked at start
  - **Commit:** 082b78b
  - **Environment:** reference 11.1.3 image, `cardano-scala-jdk`

- **Run:** `devnet.py relay --seconds 300` (F2/J1 gate, 2026-10-14)
  - **Result:** passed: the Haskell relay synced from the Scala node in 1.6 s, at its tip in 56 of 57 samples (maximum lag 2 blocks), epochs 1 to 8, all tips equal; 172 transactions submitted to the relay, 173 arrived in the Scala mempool (one more from the `cardano-cli` submission to the Scala socket), 170 included, 0 rejected or dropped
  - **Commit:** 082b78b
  - **Environment:** same

- **Run:** Preview from genesis with `follow --config config.json --topology topology.json` (official files, J1 gate, 2026-10-14, 10 minutes)
  - **Result:** the peer came from the topology's bootstrap peers, every listed hash (Byron, Shelley, Alonzo, Conway, checkpoints: the file was not at hand) was logged, 134,590 blocks applied from Origin (slot 2,835,016, epoch 32), the Alonzo to Babbage transition at epoch 3, 0 errors, a graceful shutdown snapshot; `configUnsupported` named `ConsensusMode GenesisMode`. The database was deleted afterwards
  - **Commit:** working tree of 082b78b (the `started` key `genesisHashes` was renamed after the run)
  - **Environment:** `cardano-scala-jdk`, public relay `preview-node.play.dev.cardano.org:3001`

- **Run:** `devnet.py soak --seconds 480` (F1/F3 gate)
  - **Result:** 23/23 comparisons matched, epochs 0 to 10, no error, after the Conway rules were reworked to raise typed predicate failures and run the LEDGER-level checks first
  - **Commit:** 24f650c
  - **Environment:** same

- **Run:** `devnet.py relay --seconds 300` (F1/F3 gate)
  - **Result:** passed: the Haskell relay synced from the Scala node in 3 s and stayed at its tip, 177 transactions arrived in the Scala mempool through the relay, 175 reached blocks (the others were in flight), 0 rejected, all tips equal, one transaction submitted with `cardano-cli` to the Scala socket adopted
  - **Commit:** 24f650c
  - **Environment:** same

- **Run:** `devnet.py pool --seconds 480`
  - **Result:** latest (release gate): 80 forged, 79 adopted, 78 on the final chain; Haskell blocks-made 78 = Scala on-chain 78; 16/16 comparisons
  - **Commit:** d66b216
  - **Environment:** same

- **Run:** `devnet.py pool --seconds 480` (earlier)
  - **Result:** 86 forged, 85 on the final chain (one lost a slot battle); Haskell blocks-made 85 against 83.2 expected; 151 transactions in 72 Scala blocks; 18 KES periods; deepest rollback 1; 16/16 comparisons
  - **Commit:** a3c6929
  - **Environment:** same

- **Run:** `devnet.py e2e` (Byron ledger and PBFT)
  - **Result:** every comparison matched: Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28; all Byron blocks verified and on schedule; the first Shelley epoch starts from the Byron UTxO the Scala node tracked
  - **Commit:** 6dd8be3
  - **Environment:** same

- **Run:** `devnet.py soak --seconds 480` (Byron ledger and PBFT)
  - **Result:** 21/21 comparisons matched, epochs 0 to 10, no error
  - **Commit:** 6dd8be3
  - **Environment:** same

- **Run:** Preprod sync from genesis, 60 minutes, fresh db
  - **Result:** 697,724 blocks, slot 22,441,191 (epoch 55 of about 315), 0 error events; Byron era (boundary block, 45 main blocks, no transactions, 2 update proposals with 7 votes each) validated in full; hard-fork epoch derived from the Byron update state: 4, as the network; then Shelley (4), Allegra (5), Mary (6), Alonzo (7), Babbage (12) entered by protocol version as on the network; Plutus: 106,663 successful and 438 failing evaluations, each equal to `is_valid`; db 1.5 GB. Not the tip: about 200 to 300 blocks/s, so the tip was hours away
  - **Commit:** 6dd8be3 (frozen classes)
  - **Environment:** 4 CPUs, `-Xmx3g`, container limit 10 GiB, RocksDB UTxO

- **Run:** Preprod sync from genesis to Conway (stop 1: `serialiseData`)
  - **Result:** blocks to 3,050,798 (slot 80,239,036, epoch 189, Conway, protocol 10) validated with 0 errors; stopped at block 3,050,799 (slot 80,239,053): transaction 2edd684f, a valid Hydra fanout on chain, failed phase 2 because Scalus 1.2.0 serialises a `Data` constructor index of 2^64 - 1 as -1 (`serialiseData`); fixed by replacing the builtin in the evaluator (`DataSerialiser`), pinned by `PreprodConwayRulesSuite` on the real transaction
  - **Commit:** 099f838 (failed), fix in the commit that adds `DataSerialiser`
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis, replay past stop 1 (B14)
  - **Result:** blocks to 3,158,875 (epoch 196, Conway) validated with 0 errors; stopped at block 3,158,876 (slot 83,038,792): transaction 0c22edee spends two Byron outputs with a vkey witness whose key hashes to the address root, and we demanded a bootstrap witness (`MissingBootstrapWitnessesUTXOW`); fixed (Byron roots are needed key hashes, satisfied by a vkey or a bootstrap witness), pinned by `PreprodByronKeyWitnessSuite` on the real transaction
  - **Commit:** c03b73d (failed), fix in the B14 commit
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis, replay past B14 (B15)
  - **Result:** blocks to 3,498,868 (epoch 217, Conway) validated with 0 errors; stopped at block 3,498,869 (slot 92,114,397): transaction 97b31e35, valid on chain, failed phase 2 because Scalus 1.2.0 and 1.3.0 raise an error from `verifyEcdsaSecp256k1Signature` for a signature with `r = 0` or `s = 0` (the all-zero signature) where the reference returns `False`; fixed by replacing the builtin in the evaluator (`Secp256k1.verifyEcdsa`), pinned by `PreprodSecp256k1RulesSuite` on the real transaction and `Secp256k1Suite` on the edge cases; `verifySchnorrSecp256k1Signature` matches the reference
  - **Commit:** 82af74e base build ecc9c64 (failed), fix in the B15 commit
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis, replay past B15 (B16)
  - **Result:** blocks to 4,045,971 (epoch 248, Conway) validated with 0 errors; stopped at block 4,045,972 (slot 105,654,302): transaction aee75c1c, valid on chain, carries a Plutus V2 reference script whose bytes are its CBOR item twice; the reference ignores bytes after the item for V1 and V2, Scalus `fromCbor` rejects them (`MalformedReferenceScripts`); fixed by cutting the first item before decoding (`PlutusScriptBytes`; a remainder stays an error for V3), pinned by `PreprodMalformedRefScriptSuite` on the real transaction
  - **Commit:** 32913e4 (failed), fix in the B16 commit
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis, replay past B16 (B17)
  - **Result:** blocks to 5,183,973 (epoch 313, Conway) validated with 0 errors; stopped at block 5,183,974 (slot 133,883,340, hash bac16602...): transaction f90dce57 carries a native script nested 5,383 levels deep (CBOR depth 10,772) and our decoders limited nesting to 2048 (blocks) and 1024 (Plutus data) levels (`decode block: depth limit exceeded`); the reference has no limit below the block size; fixed by keeping the decoding, encoding and walks over the item on the heap (`TreeFold`, iterative `Cbor`), pinned by `DeepNestingSuite` on the real transaction
  - **Commit:** 7bb98e3 (failed), fix in the B17 commit
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis, replay past B17 (B19)
  - **Result:** blocks to the one before slot 133,902,219 (epoch 313, Conway) validated with 0 errors; stopped at block 5,184,855 (slot 133,902,219, hash 8ddb2e96...): transaction b1db2a41 carries the native script `[3, -1, [sig, sig]]` and we required `n` of `script_n_of_k` to be unsigned (`script n: expected unsigned integer`); the reference decodes an `Int` (-2^63 to 2^63 - 1) and a slot as a `Word64`; fixed (`NativeScript.decode`, typed `DecodeError`; evaluation already matched), pinned by `PreprodNOfKSuite` on the real transaction
  - **Commit:** 6767981 (failed), fix in the B19 commit
  - **Environment:** 3 GiB heap, RocksDB UTxO

- **Run:** Preprod sync from genesis to the tip on one build (C4)
  - **Result:** uninterrupted, fresh db, no restart: 5,295,553 blocks validated with 0 error events in 4 h 52 min (2026-10-15 09:58:41 to 14:50:35 UTC); Byron with the derived transition, then Shelley, Allegra, Mary, Alonzo, Babbage (10:03 UTC) and Conway (11:44 UTC); tip block 5,295,552, slot 136,392,901, epoch 319, hash 0a2f0dd4..., equal to Koios (`block_info`: height 5,295,552, slot 136,392,901), as is block 5,295,493 (ccd5e651...); db 18 GB; every fix B12 to B19 and B18 included
  - **Commit:** 9ff0ca7d (main, frozen classes)
  - **Environment:** `-Xms3g -Xmx3g` G1, `MALLOC_ARENA_MAX=2`, container `--memory 10g`, no CPU limit (24 host threads, shared with gate runs), RocksDB UTxO

- **Run:** Preprod restart from a migrated V2 snapshot (stop: Phase2 stack)
  - **Result:** snapshot at block 2,214,108 (slot 59,380,639, hash 61aa79a0...) migrated V2 to V4 (3 files), restored, 0 blocks replayed; the follower stopped within about 35 s with `java.lang.StackOverflowError` (exit code 1, no error event) in `Phase2.loop` on transaction c70243bb (block 2,216,025, 158 redeemers); a second start did the same; fixed by evaluating the redeemers with `tailRecM` (B21), pinned by `PreprodManyRedeemersSuite` on the real transaction on a 256 KiB stack. The same blocks had passed in the from-genesis run on be476555
  - **Commit:** 403e143b (failed), fix in the B21 commit
  - **Environment:** worker-a, 2 CPUs, 3 GiB heap, RocksDB UTxO

- **Run:** BLS12-381 on a CPU without ADX/BMI2 (B25, #93)
  - **Result:** worker-a (older 2-core x86-64 CPU, no `adx`/`bmi2`), container `scarab-blst-test`: `BlstCheckMain bench` on the bundle: ok, library `portable`, the three BLS12-381 Plutus V3 scripts (add/scalarMul/equal in G1 and G2, `e(3 G1, 5 G2) = e(15 G1, G2)`) succeed, 26,829 G1 and 13,412 G2 uncompressions, 442 pairings and 356 24-seat Leios certificates per second; the same class path with the library of blst-java is refused with the typed error and exit 1 (no SIGILL). dev-host (a recent desktop CPU): same results, 68,451 / 35,274 / 1,274 / 994 per second against 65,446 / 34,737 / 1,361 / 1,016 with blst-java's library
  - **Commit:** 7b40a480
  - **Environment:** `docker run --memory 2g scarab-jdk:local`, bundle `bundle-7b40a480`, JDK 21

- **Run:** Mainnet sync from genesis (stop 1: Byron address size)
  - **Result:** blocks to slot 1,769,881 (Byron epoch 81) validated with 0 error events; stopped at block slot 1,769,882 (epoch 81, hash f277604972be4b11..., previous block 7bc92640...): `rejected by byron: block body does not decode: input byte limit exceeded`; transaction bc61865d pays an address of 7,689 bytes (derivation path attribute of 7,641) and `ByronAddress.decode` accepted at most 4096 bytes; the reference has no limit on an address; fixed (the Byron decoders are bounded by the body's 8 MiB), pinned by `ByronLargeAddressSuite` on the real block (decoded, proofs, outputs, the transaction validated in full)
  - **Commit:** be476555 (failed), fix in the B20 commit
  - **Environment:** 24 GiB heap, worker-b

- **Run:** Mainnet sync from genesis (stop 2: pool reward account network)
  - **Result:** every Byron block validated (after the stop 1 fix) and the Byron to Shelley transition at epoch 208 (slot 4,492,800, block 4,490,511, hash aa83acbf...) succeeded; stopped at block 4,490,658 (slot 4,495,760, epoch 208, protocol version 2, hash fb4e87a00f0eea0d..., previous block c8a3bd53...): `tx 49be5965c31a60c3 violates WrongNetworkPOOL: ff5181ba...`, a pool registration whose reward account is on network 0; the reference checks the network only from protocol version 5 (`hardforkAlonzoValidatePoolAccountAddressNetID`); fixed (`ProtocolParams.validatesPoolRewardAccountNetwork`), pinned by `MainnetPoolNetworkSuite` on the real block
  - **Commit:** 62141bc0 (failed), fix in the B22 commit
  - **Environment:** 24 GiB heap, worker-b

- **Run:** Mainnet sync from genesis (stop 3: Shelley time to live)
  - **Result:** resumed on the stop 2 fix (snapshots migrated from V3 to V4 on open, restored at block 4,488,497); validated to block 4,490,808 (slot 4,498,760); stopped at slot 4,498,780 (epoch 208, Shelley, hash c3015880...): transaction c7e8a043 with `ttl` equal to the slot was refused as `OutsideValidityIntervalUTxO`, where the Shelley rule is `ttl >= slot`
  - **Commit:** a9a709ad (failed), fix in the B23 commit
  - **Environment:** 24 GiB heap, worker-b

- **Run:** Mainnet sync from genesis (stop 4: Byron UTxO lost at the handover)
  - **Result:** resumed on the stop 3 fix (restored at block 4,488,648, 2,160 volatile blocks resumed); every block before it validated; stopped at slot 4,536,140 (epoch 208, Shelley, hash 199d5cec2f3e8d96...): transaction ce1c09a1 refused with `BadInputsUTxO: c1092b9c...#0`, an output created in Byron slot 4,105,943 (epoch 190) and unspent until then; the Byron entries had never been written to the UTxO store (stamp 0 shared with the genesis placeholder); fixed in the B24 commit (`UtxoBacking`, `NodeRules`), pinned by `ByronHandoverUtxoSuite`
  - **Commit:** c897029c (failed), fix in the B24 commit
  - **Environment:** 24 GiB heap, worker-b

- **Run:** Mainnet sync from genesis (stop 5: AVVM balances at the Allegra boundary)
  - **Result:** resumed on the stop 4 fix (restored at block 4,488,648, before the handover); every block validated through the Shelley to Allegra transition (epoch 236, slot 16,588,800) to block 5,107,443; treasury and reserves equal the reference (Koios `totals`) at epoch 235, the treasury also at 236; stopped at slot 17,020,941 (epoch 237, Allegra, hash 448a53bfbb7aa267...): transaction 48f567e5 withdraws 351,635,274 lovelace from `e1f6cb9f...` and was refused as `WithdrawalsNotInRewardsDELEGS` against a balance of 351,050,635. The balance was the leader reward of pool ffdf1b8f for epoch 235, calculated with reserves 318,200,635 ada short: the node had not returned the unredeemed AVVM balances to the reserves on entering Allegra (`returnRedeemAddrsToReserves`); fixed in the B26 commit, pinned by `MainnetAllegraReservesSuite` (both amounts from the real inputs) and `AllegraRedeemStoreSuite`. No snapshot was written after the handover (B27)
  - **Commit:** 31577ba8 (failed), fix in the B26 commit
  - **Environment:** 24 GiB heap, a 12-core worker

- **Run:** Mainnet snapshots after the Byron to Shelley handover (B27)
  - **Result:** the follower of stop 5 wrote no snapshot after the handover: 37 writes through Allegra epoch 237 refused with `the mirror refused the state: NetworkMismatch(expected = 1, actual = 0)`, for the pool ff5181ba registered at protocol version 2 with a reward account on network 0 (B22). The reference keeps a registered pool's account as its credential only and reads it back on the node's network; the mirror now does the same. `MainnetPoolNetworkSnapshotSuite` puts the real registration (transaction 49be5965, `fixtures/mainnet/shelley-pool-network-block.hex`) into a Shelley state on mainnet, registered, in the `mark` snapshot and re-registered: the V4 snapshot is written with the full read-back check and reads back as normalised, the registered pool on network 1, the future parameters with the account byte `e0` (network 0). Confirmation on a mainnet follower (snapshots written past epoch 208, a restart from one) is pending
  - **Commit:** fix in the B27 commit
  - **Environment:** gates only (`check`, devnet `e2e` and `restart`), a development host

- **Run:** Mainnet sync from genesis (stop 6: bytes after an address in a Mary output, B28)
  - **Result:** the sixth sync stopped at block 6,203,514 (slot 39,249,764, epoch 288, hash 24dcc9a0ecbcbd9c...) with `rejected by decode: tx 369826b2f60b443d: base address length`: output 0 is a base address of 57 bytes followed by 21 bytes. Before Babbage the reference drops the bytes after an address (`fromCborBackwardsBothAddr`, cardano-ledger-core 1.21.0.0), from Babbage it refuses them; the node chooses the reading by the era of the block (`Address.Mode`), keeps the address of 57 bytes in the set and the output as it came. `MainnetGarbageAddressSuite` decodes the real block (the transaction id equals the hash of its body, the era rule for Shelley to Alonzo and for Babbage and Conway, pointer and enterprise addresses), `MainnetGarbageAddressStoreSuite` writes the output through both UTxO stores, the value coding and the Haskell UTxO codec and reads it back by its input. The database of the run is valid and resumes at the block. Confirmation on a mainnet follower past the block is pending
  - **Commit:** fix in the B28 commit
  - **Environment:** gates only (`check`, `scalafixAll --check`, decoder fuzz, devnet `e2e`), a development host

- **Run:** Mainnet follower (stop 7: every snapshot refused from block 5,581,257, a pointer address that does not fit its fields, B29)
  - **Result:** the follower, past slot about 26.6M in Mary, logged `snapshotRefused` ... `ConstraintViolation(Ptr, a pointer of a Word32 and two Word16s, (16292793057, 1011302, 20), ... instantStake, ptrStake)` for every epoch snapshot from block 5,581,257 and had no snapshot after block 5,560,095. Transaction 9a47e091709e4fe1 (block 5,567,164, slot 26,366,727, epoch 258) pays 50 ada to a pointer address with that pointer. Before Conway the reference normalises such a pointer to `Ptr 0 0 0` and keeps the address written again (`mkPtrNormalized`, `decodePtrLenient`, `cAddrNormalized`, cardano-ledger-core 1.21.0.0), from Conway it refuses the address; the node chooses by the era of the block (`Address.Mode` `Backwards`, `Rigorous`, `Strict`), keeps `Ptr 0 0 0` in the set and in the instant stake, and the output as it came. `MainnetPointerSuite` decodes the real transaction in every era, `MainnetPointerStoreSuite` puts the output through both UTxO stores, the value coding and the Haskell UTxO codec, `MainnetPointerSnapshotSuite` writes the V4 snapshot of a state holding it with the full read-back check. The database is kept; the follower restarts and replays from its snapshot at block 5,560,095. Confirmation on a mainnet follower past block 5,581,257 is pending
  - **Commit:** fix in the B29 commit
  - **Environment:** gates only (`check`, `scalafixAll --check`, decoder fuzz, devnet `e2e` and `restart`), a development host

- **Run:** Scheduling release decision (D8, #65)
  - **Result:** Existing CPU isolation, bounded verification and scheduling instrumentation retained. Owner accepts timer outliers/forger-busy misses under one-CPU load for v0.2; the original approximately 50 ms bound and zero missed-slot target remain unproved. Further characterization is retained with D6 (#25), without changing its original acceptance. No new run or runtime change
  - **Commit:** owner decision, 2026-10-19
  - **Environment:** Historical measurements below and [decision scope](../performance.md#scheduling-d8); closure records an accepted limitation, not a successful scheduling fix

- **Run:** Preview from genesis, 31 min, base `7842866` against the D4/D8 build, started together, 6 CPUs, `-Xmx3g`
  - **Result:** 299,718 blocks after 1800 s against 309,766 (-3.2%); 0 starvation warnings in both; final: timer lateness max 116 ms (4 of 88,524 samples over 100 ms), longest CPU-bound step 167 ms; host at load 25 to 35, so absolute rates are half of section 3's
  - **Commit:** 0cac5c4
  - **Environment:** multi-core host, `--cpus 6`, `-Xmx3g`

- **Run:** the same at `--cpus 1`, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`
  - **Result:** 206,192 blocks after 1800 s against 205,713 (+0.2%); 0 starvation warnings in both; final: max timer lateness 89 ms, 0 over 100 ms; without the flags (serial collector) 13 warnings in 1,405 s (early design)
  - **Commit:** 0cac5c4
  - **Environment:** `--cpus 1`

- **Run:** Preview restart on the 6-CPU database (epochs 74 to 80)
  - **Result:** graceful stop (`--seconds`, `docker stop`): 0 stored blocks replayed, 432 volatile blocks resumed (3.3 to 5.2 s); after `kill -9`: 16 replayed, 432 resumed, the first ChainSync intersection is the last block applied before the kill; no error events
  - **Commit:** 0cac5c4
  - **Environment:** `--cpus 6`

- **Run:** `devnet.py pool --scala-cpus 1 --max-starvation-warnings 0 --seconds 480`
  - **Result:** 14 runs with the final build: 11 passed (58 to 93 scheduled slots, 0 missed, 0 starvation warnings, timer lateness 69 to 134 ms, longest step 20 to 93 ms, 16/16 comparisons); 3 missed 16 to 19 of 69 to 88 slots because the node's slot clock was 1.0 s behind the network's, which the base build shows too (1 of 6 runs)
  - **Commit:** 0cac5c4
  - **Environment:** reference 11.1.3 image

- **Run:** `devnet.py pool --scala-cpus 1 --seconds 480 --byron-start-offset {-1,0,1}` (H6 fix)
  - **Result:** 20 runs, the offset cycling: 0 slot-clock offsets (forge latency 4 to 10 ms in all 20, arrivals 15 to 27 ms; the same build with the Shelley start reproduces 1011 ms and 382 `slotOccupied` skips in one 150 s run); 12 passed; 6 of the others lost 1 to 4 of 69 to 92 scheduled slots to "the forger was busy" (11 of 1594 in all, none in runs 1 to 11, more as the host load rose from 8 to 26 on 24 CPUs), 4 of the failed (three of them also among the 6) had no `leadership-schedule` answer for the last epoch, 1 failed on a counter check of the scenario that was too strict (fixed)
  - **Commit:** 00e5fcb
  - **Environment:** reference 11.1.3 image

- **Run:** `devnet.py pool --seconds 480` (Scala node on 2 CPUs)
  - **Result:** 72 scheduled, 72 forged, 0 missed, 0 starvation warnings, timer lateness max 22 ms, 16/16 comparisons matched
  - **Commit:** 0cac5c4
  - **Environment:** same

- **Run:** `devnet.py soak --seconds 480`, `relay --seconds 300`
  - **Result:** soak: 24/24 comparisons matched, no error; relay: passed (180 transactions into the Scala mempool, 179 in blocks), both with the Scala node in a 2-CPU container
  - **Commit:** 0cac5c4
  - **Environment:** same

- **Run:** Read paths from the mirror (J2.7): `ChainReadSuite`, `NtcGoldenSuite` (flat and mirror readings), `HeaderRulesSuite` / `HeaderRulesMirrorSuite`, `ProjectionSuite`, `ReadCorpusMain` over the Preview snapshots, K1 digests before and after, devnet `ntc`, `e2e`, `e2e --oracle`, `relay`
  - **Result:** `ChainReadSuite`: every fact agrees between the flat state and its mirror at the sampled states of the eras capture (Byron to Conway), and the follower's events are equal; the 265 recorded NtC answers hold read from the flat state and from its mirror (5 states, `fixtures/ntc-golden`); the header rules give the same outcomes on the flat anchor and on its mirror (the same cases and properties); `ProjectionSuite`: the projection of the 680 Shelley-based states of the eras capture is the digest recorded with the old code (`fixtures/projection-golden`), and the mirror's equals it; `ReadCorpusMain` over 81 real Preview states (every 18th of the 1,451 snapshots) and four late ones: projections equal, 27,410 query answers equal, 0 differences; the first account query on a flat state converts the accounts at about 6 us each (37,985 accounts, epoch 1200: 238 ms); K1 on Preview, from the same blocks, the build before (`integrate/j24`) and after: 118 dump files to epoch 300 byte for byte equal, and 1,451 of 1,451 snapshot states project to the same JSON and digest; devnet `ntc` 823/823 in 15 rounds (the debug-state masks as before, 18 refused transactions equal, `DebugChainDepState` 15/15), `e2e` passed (per-era comparisons matched, two `no stable bracket` skips), `e2e --oracle` 16/16 digests equal to `db-analyser`, `relay` passed; `--header-first on` is the default
  - **Commit:** `GetChainDepState` of #90: `ChainDepGoldenSuite` needs `fixtures/chain-dep` (the reference's answers at 43 points of an `up-eras` chain), which is on the branch of the agent that captured it and not yet merged here: the suite skips until it is; the TPraos forecast of the parameters of the next epoch differs between the readings for a state whose reserves cannot pay a change of deposits (the flat one tests it); a chain in Byron projects its consensus part only
  - **Environment:** this branch
  - **Further:** 2026-10-16

- **Run:** Memory profile of the RSS growth (K6), Preview from genesis, 45 minutes, two runs together
  - **Result:** glibc malloc arenas, not the heap or RocksDB: `RssAnon` 4.29 GB with the default arenas (heap 3.07, JVM native 0.30, RocksDB 0.26, left over 0.66) against 3.86 GB with `MALLOC_ARENA_MAX=2` (left over 0.23); the default run grew 0.9 GB in 30 minutes with the heap and the block cache at their caps; block cache usage 6.9 / 30.4 / 255.5 MB for `--utxo-cache-mb` 8 / 32 / 256; 25,951 / 26,192 / 27,658 blocks in 8 minutes from copies of one epoch-138 database (-6.2% / -5.3% / 0)
  - **Commit:** 1e36a2d before the rebase (frozen classes)
  - **Environment:** `--cpus 6`, container 10 GiB, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`, NMT and `--memory-report-seconds 30`, host load 2 to 37

- **Run:** Preview sync, genesis to tip
  - **Result:** 4,747,639 blocks, epoch 1447, 551 blocks/s average, no rule failure; **the run was assembled from 12 restarts across different builds** (see [performance.md](../performance.md))
  - **Commit:** perf branch, merged in d66b216
  - **Environment:** 8 CPUs, `-Xmx3g`, RocksDB UTxO

- **Run:** Preview sync, uninterrupted, one build
  - **Result:** 4,748,226 blocks, epochs 0 to 1447 in 3h36m42s (about 365 blocks/s), then 32.8 min at the live tip; 0 error events; 242 failing Plutus evaluations all equal to `is_valid`; final tip identical to Koios; RSS peak 5.66 GB, disk 14.8 GB (see [performance.md](../performance.md)); accepts the real chain, ledger identity vs Haskell not shown (K1)
  - **Commit:** d66b216 (the tag v0.1.0 is on 244d1c14, a later docs/CI commit)
  - **Environment:** 6 CPUs, `-Xmx3g`, container limit raised to 10 GiB, RocksDB UTxO

- **Run:** Ledger oracle, devnet `e2e --oracle` (Byron to Conway, 50-slot Byron and 500-slot Shelley epochs)
  - **Result:** 16/16 epoch-boundary digests (epochs 2 to 17) equal to the `db-analyser` state of the reference node, 0 differences; the first Shelley epoch has no digest; the e2e itself passed
  - **Commit:** this branch (K1 merge)
  - **Environment:** 2026-10-14, `.workspace/data/2026-10-14-k1-devnet/e2e.log`

- **Run:** Ledger oracle, Preview to the tip (live, both nodes syncing from genesis)
  - **Result:** 1,451/1,451 epoch-boundary **projections** equal to the `db-analyser` projection of cardano-node 11.1.3, 0 differences: UTxO (3,201,605 entries at epoch 1451), accounts, pools, DReps, projected stake snapshots/reward update, stake distribution, protocol state and Conway governance (806 Conway epochs from 646). Existing exclusions and recovered evidence are explicit in [performance.md section 33](../performance.md#33-preview-acceptance-evidence-reconciliation-21); this is not whole-state byte identity
  - **Commit:** d31f0b31
  - **Environment:** 2026-10-15, `.workspace/data/2026-10-14-k1-haskell-preview/diff.json`; archived projections independently rechecked 2026-10-19

- **Run:** Current Preview epoch-135 serial/parallel measurement (#21)
  - **Result:** 6,494 original blocks; 67,163 reached phase-2 transactions and 138,358 evaluated scripts per pass; all 12 expected invalid transaction IDs reproduced. Complete mirror states and ordered typed evaluator requests/results/budgets/logs agree, zero unavailable or collection failures. Three warmed rotated rounds reduce summed validation time by 15.1% at four workers (median ratio 1.1784×); [setup and exact binding](../performance.md#33-preview-acceptance-evidence-reconciliation-21)
  - **Commit:** Runner `a5a9bdf0`, production-source equivalent to integration `3c27c84c` on `235cd12a`, 2026-10-19
  - **Environment:** Docker JDK21, four CPUs/16 GiB/10 GiB heap, no network; shared-host captured-window measurement, not live follower throughput or historical diagnostic parity

- **Run:** Current continuous Preview acceptance (#21, 2026-10-19)
  - **Result:** All 4,758,521 original blocks from genesis to slot125366428/hash956541c0937e9cdc279514c10fec9706865bb6004ddcfb487bf53c7bbdc97ad1; 1,451/1,451 authentic reference projections match with zero missing/duplicate epochs, exact ordered 242 invalid transaction IDs. 3,233,583 reached transactions, 11,251,372 evaluated scripts, 244 failed scripts; zero unavailable/collection failures. Independent integration audit rehashes 1,467 artifacts and verifies original input/runtime bindings. Replay 22,325.190s, process wall 22,343.919s; six responsiveness warnings retained. [Scope/timers](../performance.md#33-preview-acceptance-evidence-reconciliation-21)
  - **Commit:** Frozen production `235cd12a`, tool `dc00416b`; accepted manifest SHA256 `489094c44e9e97793aba1371c65eb3643bb18c7bb8d3416d7ce5391fb5b9dcde`
  - **Environment:** Docker JDK21, four CPUs/12GiB/10GiB heap, four script workers, no network; shared host; projection equality, not complete NES-byte equivalence; container removed

- **Run:** Current Preview failure-window acceptance (#21, 2026-10-19)
  - **Result:** All 68 authenticated windows: 428,517 original blocks × serial/four workers =857,034 validations. Complete mirror states and ordered typed evaluator requests/results, declared/consumed budgets, diagnostics and logs equal. Ordered invalid lists equal; 319 overlapping observations yield the exact 242 pinned identities. All 68 selected Haskell projections match; zero unavailable/collection failures. All 503 accepted artifacts independently rehashed; snapshots/checkpoints bound to originals
  - **Commit:** Frozen runner `a5a9bdf0`; accepted manifest SHA256 `d09232851f34225d57a95391729e9381c731764d065d9001f8b5b4a60da6024d`
  - **Environment:** Docker JDK21; native V5 inputs; historical per-script reference diagnostics unavailable; all scratch stores and owned container removed
