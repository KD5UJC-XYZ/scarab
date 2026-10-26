<!-- SPDX-License-Identifier: Apache-2.0 -->
# Evidence log, part 5 of 5

Part of the [compatibility matrix](../compatibility.md#evidence-log). Each run is one bullet with its former table columns as sub-bullets; the text of every cell is unchanged.

Other parts: [part 1](evidence-log-1.md), [part 2](evidence-log-2.md), [part 3](evidence-log-3.md), [part 4](evidence-log-4.md).

- **Run:** Complete V3 Flat boundary reference transactions (#166/#167/#175)
  - **Result:** 72 actual submissions at queried protocols 10/11: 32 included, 40 refused; 18 original-byte probes per version, both validity flags and both funding/collateral roles. 36 exact applied-context Haskell API rows distinguish decode/scope/runtime/success. `BoundaryTransactionsSuite` replays phase 1, Phase2, LEDGER and BBODY; decode refusals remain phase 1 for either flag. Supplemental setup requires shared reference tips and k+1 blocks; two excluded PV10 setup failures are disclosed in the [fixture recipe](../../fixtures/reference/plutus-boundary-transactions/README.md)
  - **Commit:** substantive fixture/tooling follow-up to e1bb6473, 2026-10-18
  - **Environment:** Docker JDK21; actual reference node11.1.3/CLI11.2.3.0, fresh private genesis at actual 10/11; image/configuration hashes in fixture provenance; no public sync

- **Run:** Final published fiber-boundary peercut (#132/#154)
  - **Result:** Original default runner exits 0: continued from150 to164, equal to reference164; one Transient timeout reconnect, zero errors, stalls or unparsed events. Combined published-main CI and all seven private scenario jobs pass. Same-connection reintersection remains #152
  - **Commit:** BuildInfo `e1bb6473`; CI39099284947 and private39099332508, 2026-10-18
  - **Environment:** Fresh private reference node11.1.3 cluster; existing operational nodes untouched; no public sync

- **Run:** G1.4b actual two-way PeerSharing (#138, 2026-10-18)
  - **Result:** Fresh private cluster: Scarab has one configured local root, learns all three advertised pool endpoints from the Haskell discovery relay, and reaches known5/root1 (the additional peer is an inbound receiving relay). The receiving Haskell relay asks149 and decodes five distinct addresses, all advertised, successfully connected, zero-failure, and within the ten-peer cap. No Scala error/unparsed output or reference decoder failure. An excluded socket-path setup failure and an excluded topology-reload attempt (client discovery only, no server request) are retained as harness failures; the passing proof uses initial topologies and two reference relays in one private cluster
  - **Commit:** `devnet.py peer-sharing`; design7f; code on `feat/138-sharing-and-35-limits`
  - **Environment:** Actual reference node11.1.3/CLI11.2.3.0; private genesis; no public-network sync

- **Run:** G1.4b codec and scheduling gates (#138)
  - **Result:** 67 decoder targets ×400000cases =26.8M pass (`fuzz 100000 1 4`); PeerSharing66426 decoded. CDDL cross-check40terms/direction passes with all32existing allowlisted divergences asserted. Fixed-seed real-governor hour simulation uses discoveries, respects900s per-peer retry and at most two requests;3/10-second partial/timeout tests and caller-scoped fiber rejection pass
  - **Commit:** Protocol codecs unchanged; new governor/runtime/address tests and log golden
  - **Environment:** Docker JDK21, cuddle1.8.1.0; full check and explicit scalafix pass, completed16:41:04 UTC

- **Run:** G1.4b standard private relay gate (#138): `devnet.py relay --seconds 300`
  - **Result:** PASS: all58 samples lag≤1; all final reference tips agree at block189/hashd83f27d9e41a. The relay receives211 transactions through Scarab and208 are adopted; inbound duplex session headers1→158; zero node errors, dropped transactions, unparsed output or reference decoder failures. One ordinary reconnect and one mempool refusal are retained; accepted-connection reuse remains a separate G2 question
  - **Commit:** Same frozen networking production sources as the discovery proof; full check/scalafix pass
  - **Environment:** 2026-10-18; epochs1–7; private reference11.1.3; owned cluster removed

- **Run:** G1.5/#142 relay revalidation (`devnet.py relay --seconds 300`, 2026-10-19)
  - **Result:** Two attempts exited 1. Both passed chain-following and duplex assertions: 30 samples, 7 epochs, final tips equal, inbound duplex session advanced 1→148 and 6→162 headers, and no Scala errors or reference decoder failures. The load path submitted 3 transactions and all 3 reached the Scala mempool, but 0 were adopted; the local node-to-client submission was accepted but not included. One duplicate-spend refusal was `AllInputsSpent`. This is an acceptance failure; transaction propagation/inclusion still needs diagnosis
  - **Commit:** Frozen source `0be9982a`; run summaries and logs: `/tmp/scarab/scarab-v02-139-142/relay-summary.json`, `/tmp/scarab/scarab-v02-139-142-repeat/relay-summary.json`
  - **Environment:** Docker JDK21, private reference11.1.3; no other devnet group during retry; all owned scenario containers removed

- **Run:** G1.5/#142 relay after TxSubmission2 Init parity (`devnet.py relay --seconds 300`, 2026-10-19)
  - **Result:** PASS: 30 samples, epochs 1–7, all four tips equal at block 201; lag max 7 and 27/30 samples at the tip. The local NTC transaction was accepted/adopted; 73 load transactions were submitted, 74 arrived in Scala mempool, 64 were included/adopted, with 0 Scala mempool rejects/drops or node errors. The prior repeated `timed out in Init` events were absent. This materially supports the idle-duplex timeout mismatch as a cause of prior inclusion failures, without proving strict single-variable causality
  - **Commit:** Frozen source `df51e614`; full `check` 5,097 passed + 16 optional skips, 0 failures/errors; explicit `scalafixAll` exit 0; runtime binding and relay summary archived under `/tmp/scarab/scarab-v02-139-142-initwait`
  - **Environment:** Docker JDK21, private `cardano-reference-11.1.3:local`; run data retained outside Git and owned containers removed

- **Run:** G5 decoder and adversarial controls (#35): `fuzz 100000 1 4`
  - **Result:** All 67 targets × 400,000 cases pass (26.8M); TxSubmission 31,732 decoded. Absolute partial-SDU slow-loris deadlines, write/batch/handshake budgets, completed-message byte caps, finite-credit/FIFO generated traces and real mux conversations pass. A 100,000-SDU walk runs on an observed 256 KiB worker; source IOException/bug remain Defect while actual bearer IOException remains typed closure. The pre-fix source-IOException regression failed exactly as expected before correction
  - **Commit:** Frozen codec/limit policy; final 31 network + 5 attribution tests on 257c6eac base
  - **Environment:** 2026-10-18; Docker JDK21; reference source matrix in [matrix](../design/network-limits.md)

- **Run:** G5 final rebased gates (#35): Docker `check`, `scalafixAll --check`, `node/runtimeClasspathFile`
  - **Result:** All pass after clean rebase onto completed #27; formatting, warning-free compilation, every module test suite, decoder smoke and both scalafix checks pass. The 31 focused network and 5 attribution tests also pass on this exact source
  - **Commit:** `feat/138-sharing-and-35-limits`, completed #35 change on 257c6eac base
  - **Environment:** 2026-10-18; Docker JDK21; full check completed 17:26:36 UTC, explicit scalafix/runtime classpath 17:26:37 UTC

- **Run:** G5 private `soak --seconds 600` (#35)
  - **Result:** PASS: 30/30 ledger comparisons match with zero differences through epochs 0–12; 319 blocks applied, follower alive, zero reconnects/node errors. Both metrics endpoints' 230 series match with zero validation errors. Ordinary CLI insufficient-balance load-build refusals are retained in evidence
  - **Commit:** Frozen wire/policy/limit tree; source-error classification scope below
  - **Environment:** 2026-10-18; actual node11.1.3/CLI11.2.3.0; fresh private cluster removed

- **Run:** G5 private `relay --seconds 300` (#35)
  - **Result:** PASS: 58/59 samples lag ≤1, one lag 2; all final reference tips agree at block 183/hash 94126bc387eb. 264 transactions added/adopted (263 via relay load plus local submission), zero mempool refusals/drops, node errors, unparsed output or reference decoder failures. One rollback and one ordinary reconnect retained; inbound headers 1→151
  - **Commit:** Same frozen wire/policy/limit tree as soak
  - **Environment:** 2026-10-18; epochs1–7; actual node11.1.3/CLI11.2.3.0; owned cluster removed

- **Run:** Alonzo/Babbage predicates and collateral set accounting (#178)
  - **Result:** 32 original bodies reproduce 128 unchanged released Haskell API calls, including separate direct Conway collateral API checks; 62 targeted tests pass. Private e2e passes through epoch 17 with 1,707 applied blocks and 65 matching comparisons, zero mismatches. One Conway comparison has no stable reference bracket (the harness records errors=1, skipped=0); zero node validation/fatal errors, unparsed events or CPU-starvation warnings; 32 rollbacks and zero reconnects retained
  - **Commit:** Frozen production/runtime `3cc7cc88`; final test-only alias-import syntax correction leaves all 10,001 runtime files unchanged. Initial 14-module tests pass (4,584 passed, 15 skipped); the test-only import lint was corrected and explicit all-scalafix passes. Final integration on main `669646b4` passes the full check (4,587 passed, 15 skipped), explicit scalafix and runtime build, actual exit 0 at 20:21:34 UTC; tested commit `8a1c6949`
  - **Environment:** 2026-10-18; private node11.1.3/CLI11.2.3; owned containers removed; original body bytes unchanged; API/phase-1 scope [documented](../../fixtures/reference/pre-conway-utxo/README.md), generic protocol-9 set decoding remains M11/#82

- **Run:** Remaining ledger/query/provenance feature (#285), full combined gates
  - **Result:** Docker `check`: 5,743 passed/17 optional-data skips, warning-clean compilation, formatting and Scalafix; 291 Python tests, source error-channel guard, 116 script compile checks and 18 help commands; extended fuzz 84 targets/33.6 million cases, seeds 1–4; native V5 fingerprint remains `7c483457b06802d6`
  - **Commit:** runtime `d9d089039`, main base `2fcd30904`; 2026-10-21 UTC
  - **Environment:** JDK21.0.11 image `c90140def52d`; reference11.1.3/CLI11.2.3.0 image `74e849e1b68e`; all 12,744 runtime files and 64 dependency jars unchanged across private gates

- **Run:** Remaining feature private acceptance (#285): `e2e --scala-cpus 4 --keep`, `restart --scala-cpus 4 --seconds 20`, `ntc --seconds 90 --warmup 25 --every 15 --seed 141129 --chain-sync-blocks 20`
  - **Result:** e2e: 1,687 block applications, all seven eras, 64 equal ledger comparisons across the six Shelley-based eras, no mismatches/validation/fatal/starvation errors; 46 rollbacks and 7 KeepAlive client-idle reconnects, retained under [#292](https://github.com/KD5UJC-XYZ/scarab/issues/292). V5 restart: 35 applications, two shutdown writes, one restore; stops 0.7/0.6 s. Strict NtC: 330/330 query answers, 24/24 CLI commands, 20 exact debug-state pairs; version, submission and mempool controls as above
  - **Commit:** runtime `d9d089039`, 2026-10-21 UTC
  - **Environment:** Separate private devnets and copied runtime hashes; no operational preprod/mainnet database changed. A preceding rebased e2e attempt failed at the reference Byron EBB with zero comparisons; original failure retained separately in [#293](https://github.com/KD5UJC-XYZ/scarab/issues/293), never counted as passing

The G5 private runs and extended fuzz use frozen source before the final local-source exception
classification correction. Codec, wire, queue, timing and inventory policy paths are unchanged by that
correction and by the clean rebase onto completed #27 at 257c6eac. The corrected local-source failure
branch is covered separately by the original-cause/real-transport controls and final full check.

The Scala unit and property suites run in CI (`.github/workflows/ci.yml`) via `scripts/sbtw check`
on every pull request; the devnet scenarios (soak, e2e, relay, pool) run nightly, on demand and on tags in
`.github/workflows/e2e.yml` (roadmap A3), and the fuzz and cuddle cross-check workflows nightly.

## Peer policy and next-slot mempool follow-ups (#149 / #40, 2026-10-21)

The current network pin `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776` supplies weighted warm
promotion across ordinary, local-root and big-ledger activation. All 912 explicit native cases
compare complete selections for five policies and every eligible optional hot-demotion score with
identical unsigned Word32 inputs. These are native function observations, not an original upstream
generator corpus or a claim of identical PRNG streams. See the
[corpus contract](../../fixtures/peer-selection/policies/README.md).

The mempool now ticks the complete Conway new-epoch state to the successor of the selected tip,
using slot zero at Origin and current chain geometry. Original pinned native NEWEPOCH-4/5/6 outputs
compare every field of the resulting `MempoolLedger` projection without masks. Ordered revalidation
keeps current entries only, as native consensus does; adopted, invalidated or rejected transactions
may return when re-offered. There is no invented automatic resurrection of rolled-back block
transactions. Native consensus source semantics and local signed transaction regressions complement
the projection comparisons; this is not labeled an executed whole native mempool-kernel oracle.

- **Gate:** Docker `check`
  - **Observed result:** 7,548 passed; 17 existing missing external-input/benchmark cases reported separately as skipped. All new comparisons execute. Formatting, `-Werror` and scalafix pass. The final rebase adds only main's native-build/release tooling; source/build trees used by these tests are identical.

- **Gate:** Fixture/source guards
  - **Observed result:** 23 Python checks pass; 84 documented corpora, 4,870 pinned payloads and 20 comparison contracts validate.

- **Gate:** `relay --seconds 300`
  - **Observed result:** PASS: all final tips equal at block 183; 55/58 samples within one block, maximum lag five; 59 transactions reach the Scala mempool, 54 included; inbound header count 1→147; zero node errors. One typed `AllInputsSpent` refusal is retained.

- **Gate:** `pool` (480 seconds)
  - **Observed result:** PASS: 16/16 bracketed ledger-query projections match; 76 scheduled forge opportunities produced/adopted, 74 on the final chain with two slot battles; 137 transactions in Scala-forged blocks; zero forge failures/node errors.

- **Gate:** `governor`
  - **Observed result:** PASS: five churn rounds, the slow peer demoted on all three occasions it was hot, hot set changes, silent peer loss/recovery and continued block growth; zero stalls/node errors.

These runs precede pending Genesis/KeepAlive PR #309: pool/relay/governor retain 8/12/7 reconnects,
including the known client KeepAlive timeout addressed by #292/#309. Relay also records an ordinary
no-intersection reconnect. They are not described as zero-reconnect runs. An earlier relay attempt
was deliberately interrupted when another task started a third global devnet; it supplies no
acceptance claim. The complete subsequent relay above passes. Generated databases, devnets and
build outputs are disposable; compact receipts and compressed evidence are retained by the task.

## Inbound metrics, Genesis churn and canonical headers (#150 / #311 / #318)

The shared metrics board credits accepted ChainSync headers and admitted BlockFetch blocks from
both connection directions, using the remote socket address. Header acceptance retains its arrival
timestamp; a rejected signature earns no usefulness credit. Controlled receiver/gate tests cover
the timing boundary, competing arrivals and session shutdown. Serving traffic alone earns no
client credit.

At network pin `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`, 1,152 direct executions of the
unchanged native churn governor supply 13,824 actions. Comparisons include raw proposals,
intermediate invalid targets, committed counter changes, timeout outcomes and both exact
picosecond waits. Nine direct native big-ledger requests independently cover absent, empty and
nonempty ledger answers and ordinary public-root fallback. These are explicit native decision
cases, not an imported upstream generator corpus. The [churn corpus](../../fixtures/peer-selection/churn/README.md)
and [fallback corpus](../../fixtures/peer-selection/root-fallback/README.md) retain their complete contracts.

The same pin supplies 391 [native ChainSync deadline observations](../../fixtures/chainsync-timeouts/README.md):
all retained state, peer-trust and idle-setting combinations, RNG advancement and unsigned Word64
boundaries compare. Production samples a fresh 601–911-second MustReply deadline for untrustable
peers, after the AwaitReply hook, and retains it across fragmented reads. Trustable outbound local
roots wait indefinitely. The activation job captures trustability; inbound activations always use
the untrustable profile. Fixed test deadlines remain explicit typed profiles. Native and JVM PRNG
seed streams are not equated.

Praos and Genesis now share the canonical all-era header rules. Original headers from all seven
eras, complete header-state comparisons and zero-body-fetch refusals cover Byron signatures,
the first Shelley header and epoch-boundary envelopes. Original pinned fixtures are preserved;
no forecast failure is converted to unchecked acceptance.

- **Gate:** Final Docker `check`, rebased source `7d5946fd3`
  - **Observed result:** 8,471 passed, 16 existing optional external-input/benchmark skips, zero failures/errors across fourteen modules. Formatting, `-Werror`, default fuzz and scalafix pass; every new native comparison executes.

- **Gate:** Python and fixture guards
  - **Observed result:** All 469 Python tests pass after the rebase. Normal coverage guard validates 94 corpora, 4,906 pinned payloads and 31 comparison contracts. All 4,900 original payloads are unchanged. The separate strict candidate guard still fails the unchanged pre-Conway instant-stake obligation [#299](https://github.com/KD5UJC-XYZ/scarab/issues/299); no waiver is added.

- **Gate:** Praos `e2e`, source `ca0fdda8e`
  - **Observed result:** All seven eras, six transitions and 66 matching projected ledger comparisons across the Shelley-based eras through Conway epoch17, zero mismatches/errors/skips. The standard CLI gate checks Byron's UTxO at the first Shelley boundary, rather than claiming a direct Byron ledger query. This run precedes the independent final churn changes.

- **Gate:** Final Genesis `relay --seconds 300 --every 10 --seed 311150 --no-peer-sharing`, runtime source `4fc725a4b`
  - **Observed result:** PASS: all 30 samples within one block; all final tips equal at block260, hash `0a9ef7c4fc78d97d18e6906878881432b2423f93980eef7a6e575e5de16a2c86`. Actual inbound duplex clients run; zero reconnects/node errors, two rollbacks.

- **Gate:** Original `governor`, source `9f63141c3`
  - **Observed result:** FAIL, retained: only four complete churn rounds within the observation bound; a slow peer exhausted the 300-second deactivation deadline. The native twelve-step ordering, ranking and timeout assertions remain unchanged. Investigation reproduced cancellation between publishing the pipeline's started flag and registering its producer. A controlled regression fails before the repair and passes afterward; the complete unchanged-gate rerun passes below.

- **Gate:** Final `governor`, runtime source `4fc725a4b`
  - **Observed result:** PASS: five complete twelve-step rounds in302seconds with no timeouts during those rounds; slow peer selected on all three occasions it was hot; hot set changes; silent-peer loss, continued growth and recovery to3established/2hot/1warm. Zero stalls/node errors. The later deliberate cut produces one reconnect, a failed demotion and an `IncreaseEstablished` timeout while the peer is unavailable; all are retained as fault/recovery evidence.

Both final live runs use the same 13,530 frozen class/resource files. Rebasing onto main
`3151ba92d` adds only six non-JVM tooling/documentation files. The embedded git identity changes
in three `BuildInfo` outputs; the other 13,527 runtime files match the completed full check byte
for byte. Exact file hashes and both build identities are retained. Compact input identities,
compressed logs and the original failed governor summary remain available for review; completed
disposable devnets have been removed. No operational database was modified.

- **Run:** Full REF-07 registration and signed block composition (#171)
  - **Result:** 22 actual unchanged released-reference cases: six legal and five illegal succession cases; independent bad proposal/header signatures and unauthorized proposer; eight raw header/proposal width refusals. Original annotated bytes pass full registration and proposal/ordinary transaction admission, complete-block ledger validation and authentic header/certificate crypto gates. Six accepted composites register the expected version and spend/create the signed transaction outputs. The bad header passes ledger rules but fails the separate composite crypto gate. `ByronRegistrationFixtureSuite` has45 passing checks; full Docker check/scalafix and173 Python tests pass; the clean frozen image reproduces all22 cases offline; [recipe, exact scope and provenance](../../scripts/oracle/byron/full/README.md)
  - **Commit:** test/tool follow-up on db235367; production unchanged
  - **Environment:** 2026-10-18; GHC9.10.3, frozen released packages matching node11.1.3 embedded versions; pinned Cardano libsodium; offline execution, controlled synthetic state, no PBFT/adoption-history claim

- **Run:** Era-aware output forms and body keys (#379), level 3: Docker `check`, `fuzz 100000 1 4`, `cddl_crosscheck.py`, `devnet.py e2e`
  - **Result:** An output and a body are read as the decoder of their era reads them (see the row above). `EraFormsReferenceSuite`: 231 oracle cases, 89 accepted and 142 refused; before the change 65 refusals were accepted. Gates: `check` 10,428 tests across 14 summaries, 16 optional skips, 0 failures, formatting, Werror and scalafix (`CodecSuite` fingerprint unchanged); `fuzz 100000 1 4` 84 of 84 targets pass; `cddl_crosscheck.py run --count 40 --jobs 4` 60 targets, no divergences, 0 exemptions; `devnet.py --name pw-dec e2e` passed on the first attempt that ran the follower (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 28 matched, 0 mismatched, 0 errors; an earlier attempt failed to start the follower because the node classpath file was not built yet, before any block). Each gate started below its load limit (check and fuzz below 20, e2e below 18)
  - **Commit:** branch `fix/379-era-aware-decoding` (main `f1031402d`, rebased onto `f25bed9ae` afterwards)
  - **Environment:** 2026-10-25; cardano-ledger f649f975; Docker JDK 21, Scala 3.3.8

- **Run:** Era-aware decoder closeout (#379): `scripts/preflight --check`, `fuzz 100000 1 4`, `scripts/cddl_crosscheck.py run --count 40 --jobs 4`, `scripts/devnet.py --name codex489-a756-e2e-2 e2e`
  - **Result:** Preflight passed: 10,439 tests passed, 16 optional external-data/benchmark skips, zero failures/errors; formatting, compilation and scalafix passed. Fuzz: 84/84 targets passed. CDDL: 60 targets, zero divergences/exemptions, all 21 modules resolved. E2E passed through epoch 17 and protocol 11: 66 projected comparisons matched (Shelley 12, Allegra 4, Mary 4, Alonzo 8, Babbage 8, Conway 30), zero mismatches/query errors/skips, validation/fatal errors or CPU-starvation warnings. These are projections, not whole states (#433); a Byron ledger query is unavailable, and its UTxO is checked at the first Shelley boundary. The first attempt failed before comparisons because the build wrapper injected a JVM environment notice into the follower JSON stream; the retry used unchanged runtime settings.
  - **Commit:** `a756657361a6f0d5bd443d82e97860ad59c781b1`, based on `3c5d3b99a17ad4225246d3229a0ea7f25a5d97f6`; run before the subsequent documentation-register rebase
  - **Environment:** 2026-10-26; Docker JDK 21.0.11, sbt 1.10.7; build/test CPU cap 4 and 8 MiB stack, private sbt socket; cardano-node 11.1.3/CLI 11.2.3, cuddle 1.8.1.0; devnet runtime settings unchanged. Owned devnet data and containers removed.
