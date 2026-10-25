<!-- SPDX-License-Identifier: Apache-2.0 -->
# The typed rule interpreter and the cast-erasure batch

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## Typed rule interpreter (#202)

**2026-10-20, level 3.** The indexed `Frames` implementation is retained. On the pinned
Preview workloads below, the median of three JVM medians changes by **+1.21%** for the sum
of sixteen block-application medians and **+5.82%** for the epoch tick. Every individual block
is below the issue's +10% latency limit (largest increase +8.23%). There is no requested
performance waiver. These are bounded offline ledger measurements, not sync throughput.

### Sources and inputs

This is an isolated interpreter comparison: all surrounding runtime classes are the same frozen
candidate classes, compiled from reviewed head `44192a0251d8b9316db4dd88b1c702e8a3580fa1`
(the production Scala sources are unchanged at `83c76b45`). The old condition prepends only the
classes compiled from `RuleT.scala` at `dev` `2ef358366f3a50397645851f7db81b6a91c5c779`.
The old/new interpreter source SHA-256 values are respectively
`ede5bd8e999e459347a131ddf3576e82f9d284e9f2f919d2d751440dbde5433a` and
`dae0210f9d3725861ac34765d903e54fdca98d75d24e9b7fe04fb3d42c020b95`.
No codec, credential-order, Diff, rule, crypto or reward implementation changes between conditions.

The maintained entry point is
`node/src/test/scala/xyz/kd5ujc/scarab/node/PreviewRuleBench.scala`, added in `ea616449`.
The measured standalone source SHA-256 is
`04ee354d1b73e93f6f4acdb024997ecabf21d62fb98b641faf0b14d1767f2000`;
the committed, scalafmt-formatted source is
`929703727068b4859feac327191742eb7bd5eba1612dff1d673ee3209ab8d944`.
Their executable source is identical; only whitespace and comment wrapping differ.

Inputs reuse the authenticated Preview K1 provenance of section 30; the original archives remain
unchanged outside Git. Native V5 snapshot `snapshot-00000000000125231955-4753556.bin`,
55,232,414 bytes, SHA-256
`1323741149d81ef700b671f34d4964082102b22d0df4f097418ec7597dd8f536`, is paired with its
UTxO checkpoint at stamp 4,753,556. Its original legacy snapshot SHA-256 is
`ca7833bb425ea47514c34cb739627fb880d0585d54b0f6594ca4b43627cf896c`;
the prior conversion proof binds the complete canonical NES to
`9488acde5daafe88bff31aecbb1a52ba1ffee4ff971eaf17df68b2bc41930049`, requiring only the
documented normalization of empty pool-relay encodings for typed equality to the original.
This comparison introduces no state masks or reward-completion normalization.

The block window is the next sixteen consecutive blocks, 4,753,557–4,753,572, slots
125,231,994–125,232,394, from immutable chunk `05797.chunk`, SHA-256
`53cd2f49dadf57bc713808fcf23ff477edca6953587dc7d1b57a7bcbad685ebf`.
Seven transactions occur in four of the blocks; the remaining twelve are empty. The authenticated
record manifest (`records.json`, including offsets, lengths, header identities and each raw-block
SHA-256) is `ea8b4a7abedab57028b5a6c8092bb479b11ffba634a2d2daaca25137df16796f`.
The harness verifies each selected raw-block hash before decoding. Checkpoint and chunk hashes
were rechecked against `issue-135-replay-native/input-authentication.json`; the final binding also
pins every Preview genesis/config file. That source capture was authenticated by CRC32,
original-header BLAKE2b, consecutive block numbers and previous-header links (section 30).

The epoch workload ticks the same unmodified, snapshot-derived epoch-1450 NES to the first slot
of epoch 1451 (125,366,400). It runs the actual `Hfc.tick`/TICK/NEWEPOCH/EPOCH rules, including
pending reward completion as required by that state. It is a **snapshot-derived boundary workload**,
not the state immediately before the historical epoch-1451 block: intervening blocks are not replayed
for this tick. No broader Haskell equivalence is inferred from the benchmark.

### Protocol and results

Linux x86-64, Euler WSL2 shared host, Docker image
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`
(`scarab-jdk:local`, Eclipse Temurin 21.0.11), Scala/compiler 3.3.8; network disabled,
`--cpuset-cpus 8-11 --memory 12g --user 1000:1000`, no CFS quota;
`-Xms3g -Xmx9g -XX:ActiveProcessorCount=4 -XX:+UseG1GC`, `MALLOC_ARENA_MAX=2` from the image.
An e2e scenario and the initial full check overlapped part of the measurements; results describe
this shared host, with interleaved order old/typed/typed/old/old/typed, one benchmark JVM at a time.

Each block/refusal case warms 256 calls then measures 31 batches of eight calls; each epoch case
warms 64 calls then measures 31 single calls. Timings and current-thread allocations use
`System.nanoTime` and `com.sun.management.ThreadMXBean.getCurrentThreadAllocatedBytes`, divided
by batch size. The pure call runs synchronously in one IO thunk; no allocation on other threads
is attributed to it. The timed sink keeps the typed result; equality checks, rendering, SHA-256,
printing, block/transaction decoding, snapshot loading and checkpoint reads are all outside timing.
Each historical block's input is prepared sequentially once, then reused immutably for all repetitions.
This measures ledger Tick+BBODY, not network, header crypto, RocksDB, decoding or complete-node time.
Local mutation is confined to the timing loop so the measurement avoids collection-building cost.

<!-- register:table perf-38.t1 -->
| JVM condition | sum of 16 block medians (ms) | sum of block median allocations (B) | epoch median / min–max (ms) | epoch median allocation (B) |
|---|---:|---:|---:|---:|
| 1: old | 10.616 | 5,582,643 | 128.933 / 119.868–146.499 | 189,684,048 |
| 2: typed | 10.201 | 5,584,775 | 136.438 / 126.009–181.238 | 194,147,472 |
| 3: typed | 10.532 | 5,584,387 | 124.670 / 116.000–138.672 | 194,147,472 |
| 4: old | 10.313 | 5,584,826 | 119.171 / 113.045–132.078 | 189,672,464 |
| 5: old | 10.369 | 5,595,939 | 136.279 / 119.611–171.220 | 186,629,096 |
| 6: typed | 10.494 | 5,582,846 | 141.159 / 130.718–161.803 | 191,121,528 |
<!-- /register -->

The block column sums separate workload medians; it is not a timer around a whole replay.
Block allocations differ by less than 0.04% at the median of runs. Epoch median allocation
increases from 189,672,464 to 194,147,472 B (+2.36%). The full per-case latency summary follows;
old/typed values are medians across the three JVM medians, with the span of those JVM medians.
Raw per-sample ranges and allocations for every case remain in the bound summary.

<!-- register:table perf-38.t2 -->
| Case | old median [run-median span], ms | typed median [run-median span], ms | change |
|---|---:|---:|---:|
| block-0 | 0.05223 [0.04865–0.05484] | 0.04808 [0.04757–0.04863] | -7.95% |
| block-1 | 0.03765 [0.03707–0.04187] | 0.03734 [0.03682–0.03797] | -0.83% |
| block-2 | 4.82267 [4.79552–5.12170] | 4.88202 [4.63565–4.96951] | +1.23% |
| missing-input-2 | 0.04691 [0.04584–0.04766] | 0.04831 [0.04729–0.05566] | +2.98% |
| block-3 | 0.00511 [0.00507–0.00535] | 0.00497 [0.00495–0.00566] | -2.82% |
| block-4 | 0.00509 [0.00483–0.00554] | 0.00507 [0.00498–0.00525] | -0.51% |
| block-5 | 0.00488 [0.00481–0.00507] | 0.00488 [0.00481–0.00511] | +0.04% |
| block-6 | 4.45610 [4.44501–4.49057] | 4.48092 [4.42190–4.52714] | +0.56% |
| missing-input-6 | 0.02179 [0.02036–0.02429] | 0.02321 [0.02204–0.02384] | +6.50% |
| block-7 | 0.00442 [0.00439–0.00448] | 0.00453 [0.00442–0.00587] | +2.42% |
| block-8 | 0.00446 [0.00445–0.00446] | 0.00453 [0.00453–0.00489] | +1.57% |
| block-9 | 0.00445 [0.00440–0.00473] | 0.00443 [0.00442–0.00447] | -0.54% |
| block-10 | 0.22797 [0.22707–0.24667] | 0.22937 [0.22210–0.24541] | +0.62% |
| missing-input-10 | 0.01227 [0.01187–0.01409] | 0.01173 [0.01146–0.01600] | -4.37% |
| block-11 | 0.00433 [0.00365–0.00466] | 0.00415 [0.00390–0.00435] | -3.98% |
| block-12 | 0.67015 [0.66346–0.72955] | 0.72533 [0.70662–0.79379] | +8.23% |
| missing-input-12 | 0.01534 [0.01487–0.01949] | 0.01529 [0.01503–0.01549] | -0.37% |
| block-13 | 0.00365 [0.00355–0.00366] | 0.00328 [0.00323–0.00344] | -10.29% |
| block-14 | 0.00333 [0.00321–0.00342] | 0.00337 [0.00329–0.00337] | +1.17% |
| block-15 | 0.00329 [0.00319–0.00332] | 0.00332 [0.00316–0.00344] | +0.85% |
| epoch-boundary | 128.93288 [119.17081–136.27919] | 136.43762 [124.67009–141.15866] | +5.82% |
<!-- /register -->

**Semantic evidence.** All 21 complete-result SHA-256 values agree in all six JVM runs:
sixteen `EraBlock.Outcome` results (entire NES and Plutus counters), four actual typed
missing-input refusals, and the complete `Either[TickFailure, EraNes]` epoch result. No count-only
fingerprint or field mask is used. Every warmup and measured batch's final result also equals
its untimed expected result structurally. This establishes parity on these inputs; the laws,
rollback/scoped-Globals tests, small-stack suites and reference-stack Tick/BBODY comparisons
(including failure paths) are separate evidence. The epoch digest is
`0b4fe05629565985bd4769d88143376fcca01c7b81b2faa3ce892d092d0b71fd`.

**Warmup investigation.** The retained first protocol (7 warmup, 21 single-call samples) gave
one block a +13.6% three-JVM median difference and one paired epoch run +14.9%. It was not
accepted as a passing result. Empty-block medians of roughly 40–120 microseconds became roughly
3–55 microseconds with the longer fixed warmup above; the two large transaction-bearing blocks
fell from roughly 7–8 ms to roughly 4–5 ms. That sensitivity establishes that the earlier protocol
was measuring a different warmup state. The longer, declared protocol was applied symmetrically
and all six runs retained, including slow samples. It brings the earlier problematic block to
+0.56%, with all block and epoch workloads below +10%; this is a steady-state verdict, not a
claim that startup latency is equal. No production optimization was made to obtain the result.
A separate attempted launch failed before measurement because the standalone assertion classpath
omitted JUnit; adding its pinned runtime dependency resolved that harness error.

**Reproduction and binding.** Artifacts live outside Git in `/tmp/scarab-248-bench`: immutable
runtime class inventories, old-only overlay, compiler classpath, standalone sources, exact Docker
argument vectors (`r0-old.json` through `r5-typed.json`), raw samples, all result digests,
`analyze.py`, `summary.json`, and the earlier protocol in `diagnostic/`.
Final binding SHA-256: `7b85100a0b759c12d5b0eb2e2b94c2781a64f9246374274bf1bedc519a1d7445`;
summary SHA-256: `af97400f710474aa9a040c53dda99e8c09769905aab14717b45d602d5ef1b854`.
The binding includes each input/config hash, each frozen runtime/overlay class hash, source
identities, and every command/log hash. Original private archives and run data are not committed.

Generate `node/runtimeClasspathFile` and `node/Test/compile` from the correct checkout mounted
at `/work`, with the shared cache mounted at `/work/.cache`. Freeze the runtime directories;
compile only the old `RuleT.scala` with the pinned Scala 3.3.8 `dotty.tools.dotc.Main` against
that runtime into a separate directory. Compile/run `xyz.kd5ujc.scarab.node.PreviewRuleBench`
against the frozen runtime and test dependencies, prepending that old directory only for the old
condition. The recorded driver is `python3 /tmp/scarab-248-bench/run.py` (SHA-256
`b298c833ba8f6471085582781761fdb4c53a1e13d890691628ea615a1bd691a4`). Mount the K1 database
read-only at `/input`, the converted native snapshot directory read-only at `/native`, the pinned
Preview genesis directory read-only at `/configs`, the record manifest at `/bench/records.json`,
and a fresh output directory at `/scratch`; all exact host/container paths are in the command JSON.

### Supplementary synthetic result and #250 reconciliation

The existing `CastErasureBench.scala` remains byte-identical to #250 at `7037eac6`:
SHA-256 `d2409a39398b575e4027a71750f8c30e8f436153c6ff45e86cf53406389686dd`.
The reported rutherford measurement (2026-10-19/20, Docker JDK 21.0.12.1, CPUs 0–7, `-Xmx5g`,
three warmups/five samples, old/new/old/new) compared `57608c6e` with measured code `9d2fa735`:
old 3,159.9/3,175.7 ms, typed 3,073.9/3,256.9 ms; allocation 5.224–5.230 GB versus
5.217–5.227 GB. The paired changes are **−2.7% and +2.6%**, correcting the prior +1.5% wording.
Those are reported historical medians, not fresh Euler measurements or a confidence interval.
The fixed-key synthetic workload hashes the complete rendered ledger state outside timing;
`159017f549d74b4d4cb6a520` matches all four reported runs. It remains supplementary and does not
replace the Preview result above. No unrelated codec implementation or unmeasured batch-wide
performance claim is incorporated into #248. #205/#204 retain their own representative snapshot
and restart requirements; their results cannot be inferred from this interpreter comparison.


**Integration after #260 (2026-10-20).** Rebased onto dev
`6988858f937a42eb34762cb5a61d3c622c49b124`. RuleT production and the shared class-freeze driver are byte-identical to the measured/reviewed candidate; the driver patch is now upstream through #260. The new Diff diagnostics do not execute in the timed BBODY/Tick comparison.
The original benchmark/gate source identities above remain historical evidence. Refreshed
checks on the rebased head are recorded in the [PR #248 checklist](https://github.com/KD5UJC-XYZ/scarab/pull/248);
pre-rebase gates are not relabelled as gates of the integrated head. Main and dev still diverge;
this prepares the dev target and does not claim completion after main landing.

## The cast-erasure batch (#202–#207): the old code against the new, on one harness

The batch's claim is that removing the erased value channels changed no verdict and no byte, and
that the typed implementations stay inside the cost bar the tickets set (10% on a primary
workload). One harness — `ledger/src/test/scala/xyz/kd5ujc/scarab/bench/CastErasureBench.scala`
(`ledger/Test/runMain xyz.kd5ujc.scarab.bench.CastErasureBench <workload|all> [n]`) — runs each
ticket's workload against both builds. The same file is added by #248 for #202 and by this chapter;
the two copies are identical, so the repository has one authoritative harness. The codec workload
is `storage/src/test/scala/xyz/kd5ujc/scarab/storage/BenchCodecMain.scala`
(`storage/Test/runMain xyz.kd5ujc.scarab.storage.BenchCodecMain [iterations]`).

**Inputs are deterministic and asserted so outside the timed region.** The credential workload
builds 100,000 credentials whose 28 bytes carry the index in the last four bytes, asserts
`distinct.size == n` before timing anything, and shuffles with `Random(42)`; the block workload
signs with one fixed Ed25519 key generated from a fixed `SHA1PRNG` seed. Two builds therefore
receive identical inputs, and the comparison measures the implementation, not the inputs.

**Every digest covers the complete output, computed outside the timed region.** Each workload
renders its whole answer canonically and prints its SHA-256: the sorted credentials, the map
entries with their values, both decoded trees (nested and wide), and the full applied ledger state.
Equal digests between the old and the new build mean equal outputs on the measured workload. They
do not establish chain-level state parity — that is the ledger area scenario's question.

**Protocol.** rutherford (12-core WSL2/Docker Desktop host, shared with the mainnet follower),
image `scarab-jdk-check:local` built from `docker/jdk.Dockerfile`, `--cpuset-cpus 0-7` and no CFS
quota, one JVM at a time; 3 warm-up runs and 5 measured runs per case; `System.nanoTime` per run,
`ThreadMXBean.getCurrentThreadAllocatedBytes` per thread; each run records median/min/max. The
historical table below displays the two run medians (or their span), not the per-run extrema. These
are retained author-reported measurements, not timings rerun by the takeover session. The
sequence is interleaved — dev, branch, dev, branch — so drift over the session cannot be mistaken
for a difference between builds. Measured 2026-10-19/20 (CDT), OpenJDK 21.0.12.1, `-Xmx5g`.

**Sources and harness identities.** Each row compares two exact commits; the harness that produced
it is pinned by hash.

<!-- register:table perf-40.t1 -->
| workload | old source | new source |
|---|---|---|
| rules.bbody (#202) | `dev` `57608c6e` | `9d2fa735` (the measured code; `f6d72013` adds only the harness) |
| credentials (#207) | `dev` `57608c6e` | `b16b487f` |
| decode (#203) | `dev` `57608c6e` | `4e515d49` |
| diff (#206) | `dev` `57608c6e` | `a4d198d5` (the same source as `30af639a`; only tests were added after) |
| codec (#205) | `dev` `57608c6e` | `9a158532` (the measured code; `d4f5d5b9` formats the bench only) |
<!-- /register -->

Harness identities: `CastErasureBench.scala` SHA-256 `d2409a39398b575e…`, `BenchCodecMain.scala` SHA-256
`1590e05ee16a8289…` for the historical storage measurements. The current ledger harness remains byte-identical in #248/#250. The current storage harness matches #247 at `ab85c6a7` (SHA-256
`9c33c50cb7e023e1a5e39500fee698cf109fe4018c793f39215228d6a11cd302`, primitive sinks,
20 warmups/9 samples); it differs from the historical measured file and does not retroactively change those measurements.

**The host drifts, and the drift is the honest error bar.** Two runs of the *same* old build in one
session span 129.1–161.8 ms on `credentials.mapUpdate`, 119.1–140.0 ms on `mapBuild`, 3.16–3.18 s
on the 10,000-transaction block; `diff.map.late` is 7.143–8.670 ms on old and 5.447 ms on new. These sparse shared-host observations do not establish an error distribution or a general 10% bound. Overlapping ranges are inconclusive, rather than proof of neutrality.

**An earlier revision of this table was wrong, and this one replaces it.** The first harness
generated credentials that repeated every 256 inputs, so the map workloads held 256 keys, and its
digests covered counts rather than outputs. The review of #250 caught both. The numbers below are
from the corrected harness: 100,000 distinct credentials, real map copies and updates, complete
output digests.

<!-- register:table perf-40.t2 -->
| Workload (#, what it drives) | old (dev, span of 2 runs) | new (branch, span of 2 runs) | allocation old → new | reading |
|---|---|---|---|---|
| rules.bbody — 10,000 transactions through `Sts(Bbody)` (#202) | 3159.9 / 3175.7 ms | 3073.9 / 3256.9 ms | 5.224–5.230 GB → 5.217–5.227 GB | within drift; digest `159017f5…` identical on all four runs |
| credentials.sort — 100,000 distinct credentials (#207) | 16.647 / 15.939 ms | 12.961 / 15.687 ms | 1.327 MB → 1.327 MB | within drift; digest `2e82ce87…` identical |
| credentials.mapBuild (#207) | 139.952 / 119.059 ms | 128.674 / 114.461 ms | 60.912 MB → 60.912 MB | within drift; digest `0452736d…` identical |
| credentials.mapCopy — 100,000 entries (#207) | 64.367 / 60.248 ms | 87.578 / 65.396 ms | 82.352 MB → 82.352 MB | inconclusive; paired +36.1% / +8.5%, no established 10% bound |
| credentials.mapUpdate — 100,000 updates (#207) | 129.080 / 161.836 ms | 136.701 / 138.009 ms | 54.592 MB → 54.592 MB | within drift (the old build alone spans 129–162); digest `237a18d9…` identical |
| credentials.iterate (#207) | 1.215 / 1.302 ms | 2.121 / 1.354 ms | 2.422 MB → 2.422 MB | within drift |
| decode.nested plus full-result hash — 2,000-deep array (#203) | 3.395 / 2.977 ms | 5.187 / 2.758 ms | 435,504 B → 435,504 B | within drift |
| decode.wide plus full-result hash — 50,000-entry map (#203) | 21.874 / 15.752 ms | 15.911 / 16.263 ms | 10.697 MB → 10.697 MB | within drift; both digests identical |
| diff.row.equal / early / late (#206, **product** path) | 0.001 / 0.027–0.050 / 0.030–0.071 ms | 0.001 / 0.037 / 0.048 ms | 0/1832/1904 B → 24/1928/2328 B | equal allocates 24 B; early/late micro-costs remain unresolved; **not** addressed by #249; no isolated ClassTag cost attribution |
| diff.sum.sameCase / otherCase (#206, **sum** path) | 0.030–0.055 / 0.030–0.039 ms | 0.047 / 0.030 ms | 1760/2184 B → 1952/2184 B | inside the microsecond noise; #249's historical sum-only claim is structural; see the later #260 comparison below |
| diff.map.equal / early / late — 20,000 entries (#206) | 11.471–12.215 / 0.044–0.072 / 7.143–8.670 ms | 10.034 / 0.072 / 5.447 ms | 1.005–1.229 MB → 1.005 MB; 1.763 MB → 1.763 MB | within drift; digest `d582731d…` identical |
| codec.encode / decode — 20,000 iterations, nested synthetic record (#205) | 188.6 / 194.7 ms encode; 273.3 / 284.2 ms decode | 220.5 / 321.9 ms encode; 250.4 / 263.6 ms decode | 1.337 GB → 1.348 GB encode; 1.668–1.684 GB → 1.494–1.509 GB decode | encode **over the bar** (+13…+71%); decode 7% faster; bytes identical (`c19600a83b7ba784`, 333 B) |
| codec.record.encode / decode — `FutureGenDeleg` through `Record.auto` (#205) | 4.058 / 4.783 ms encode; 17.905 / 20.439 ms decode | 5.546 / 6.383 ms encode; 22.366 / 32.222 ms decode | 10.24 MB → 10.88 MB encode; 113.8 MB → 116.1 MB decode | encode over the bar; paired decode medians +24.9% / +57.7%, not neutral; bytes identical (`9b5615ce2d835aac`, 34 B) |
<!-- /register -->

**Historical interpretation.** The synthetic BBODY pairs move −2.7% and +2.6%, with complete result parity. The decoder rows measure decode **plus `value.hashCode` inside timing**; their separate output fingerprints outside timing do not remove that work. Historical `BenchCodecMain` likewise hashes decoded Row/FutureGenDeleg inside timing. The earlier pathological spike is superseded as a measurement, without an established GC cause or demonstrated sink-based explanation. #247 now uses primitive sinks, with new source identities and new measurements below.

The two credential map-copy pairs move +36.1% and +8.5%. Identical allocation and noisy run ranges do not establish the 10% criterion. The sort/map/decoder/diff timing comparisons remain descriptive and inconclusive where drift prevents attribution; no additional acceptance verdict is inferred from two ranges. The historical #206 product path's additional allocation and timing are not resolved by the original sum-only #249 patch. The later #260 candidate also retains field instances; that change has its own nested-model comparison, rather than a rerun of this historical Row table. A runtime `ClassTag` extraction also does not prove generic or phantom-role relationships; #206 retains that compiler-proof obligation.

The original #205 record decode medians regress approximately 25% and 58%, and encode allocation 10.24→10.88 MB is **+6.25%**. They were not neutral or within 2%. Those historical encode/decode results justified holding the old candidate. The optimized candidate has since been measured independently below; historical numbers have not been relabelled as its results.

**Updated implementation evidence, 2026-10-20.** This table links each result to the implementation PR's pinned source/input/runtime protocol. It does not combine different workloads or hosts into one percentage.

<!-- register:table perf-40.t3 -->
| Work | Final evidence | Verdict and limits |
|---|---|---|
| #202 / PR #248, head `0798b5d5` | [Representative Preview block/epoch results](https://github.com/KD5UJC-XYZ/scarab/blob/0798b5d5c423cf4c6b657f514736e6160cca457e/docs/performance.md#typed-rule-interpreter-202); isolated old RuleT overlay, identical surrounding classes; 21 full success/refusal digests match | Block median-sum +1.21%, epoch +5.82%, worst individual block +8.23%; passes 10%. Snapshot-derived epoch input is explicitly not a captured immediate pre-boundary state. Synthetic BBODY remains supplementary. |
| #205 / PR #247, head `bacac534` | [Current and legacy-derived maintained V5 snapshot results](https://github.com/KD5UJC-XYZ/scarab/blob/bacac5342d5d09d37e2a949e9f23a2312cce560a/docs/performance.md#32-typed-snapshot-codec-derivation-205); identical complete bytes/typed state/truncation refusals | Encode/decode improve 7.44–17.26%; worst candidate JVM median against fastest baseline ≤+2.63%. Primary limit passes. Tiny two-field encode +17.72%, about 6.1 ns/call, tracked in #257; no universal speed-up claim. |
| #206 / merged PR #260 (supersedes #249) | Conditional-given regressions fail against the old dispatcher (100 constructions instead of 1) and enum-only retention (201 instead of 2); mixed singleton/data enum selection is corrected. Six old/typed/candidate model runs have identical full fingerprints; largest primary time median increase against original is 6.8%. | [Fresh dev-based evidence at `ca22fad2`](https://github.com/KD5UJC-XYZ/scarab/blob/ca22fad29b8b41f7d47a94b3968a060f1b0a13e5/docs/compatibility.md#evidence-log): check passes 5,146 tests; 1,699 applications and 64/64 ledger comparisons match, with 38 rollbacks/7 reconnects retained. Equal-state allocation remains 3.3–17.6% above original. Generic/phantom proof through `productElement`/`ClassTag` remains a bounded obligation in #206; no claim that runtime checks establish it. |
| #204 / PR #263, head `2f7983d7` | [Typed interning source and independent representative decode/heap comparison](https://github.com/KD5UJC-XYZ/scarab/blob/2f7983d716919001f67a3ff0061998311200eaa2/docs/performance.md#33-typed-per-kind-decode-interning-204); complete state/refusal fingerprints and retained object counts match. | Current/legacy-derived decode aggregates −7.11%/+2.92%, adverse cross-JVM median comparisons +2.70%/+8.54%; allocation +0.03%/+0.31%; retained heap at most +0.32%. Both primary inputs pass 10%; snapshot restart and full check pass. Extended-fuzz/exact-head receipts are on #263. |
<!-- /register -->

PR #248's ledger scenario now passes 66 comparisons through Conway with zero errors; exact-head Docker check and GitHub CI pass. PR #247's snapshot restart and extended decoder fuzz pass, and its exact-head Docker check/GitHub CI pass. Source/input/image hashes, commands, failure semantics, sampling settings and qualifications are in those pinned reports and their compatibility evidence. Both PRs are ready for **dev**; neither has landed on main, so their acceptance issues remain open. The historical batch measurements alone do not establish current live-sync throughput, full-node memory, or correctness beyond the tested inputs.

**Remaining erasure inventory.** A source sweep of production Scala on the #248 candidate
`0caad5c8`, checked against each follow-up's actual source, distinguishes compiler proofs from
runtime checks. Test-only casts and ordinary `equals(Any)` are outside the runtime recovery
inventory; an `Any` spelling alone is not evidence of a lost relationship.

<!-- register:table perf-40.t4 -->
| Channel | Invariant and disposition |
|---|---|
| `storage.Codec` (five casts), Haskell `Record` (four casts) | Each declared field/variant must use its own codec in original order. #247 replaces these with typed derivation and generated accessors; its bytes, errors and migrations are measured/tested. Cast-free source alone would not establish this. |
| `Interns` (two casts) | A kind and decode must select only `Table[A]`, with first-instance identity and bounded lifetime. #204 is the typed implementation/measurement track; values cannot be recovered through an erased registry. |
| `Diff` product/sum extraction | `productElement` returns erased values; `ClassTag` does not validate generic arguments or phantom roles. #260 repairs retention and enum selection, while #206 retains the bounded obligation to generate typed accessors/variant operations and add positive/negative generic-role probes. No `Diff[Any]` source is necessary for this gap to exist. |
| `Sorted.orderingOf`, `SortOrder[K @unchecked]` | Cached comparator must belong to exactly `K`, preserving comparator identity and linear sorted-map copying. #255 tracks a typed recovery design; moving the check or replacing it with a runtime key class test is insufficient. |
| `SyntaxSummary` diagnostic `Any` work list and `productIterator` | Bounded rendering only: 64 nodes/512 characters, complete typed error payload retained, no generic value returned to a typed operation. #261 bounds a typed AST diagnostic walker; no observed ledger/model defect is claimed. |
| Covariant `UtxoEnv[Any]` | The certified parameter is neither recovered nor consumed on the UTXOS path; this is safe widening, with no corresponding erased-value retrieval operation to repair. |
<!-- /register -->

The RuleT frame family preserves the relationships of pending values and continuations at the
compiler boundary. This inventory makes no blanket claim that the entire batch or repository now
has that property. The open obligations remain attached to their issues after the narrower PRs
land; readiness for dev does not close them or imply completion on main.


**Integration after #260 (2026-10-20).** Rebased onto dev
`6988858f937a42eb34762cb5a61d3c622c49b124`. Both shared benchmark files retain their measured/maintained hashes. Historical measurements remain bound to their actual source revisions; merging the new Diff update does not relabel them as new experiments.
The original benchmark/gate source identities above remain historical evidence. Refreshed
checks on the rebased head are recorded in the [PR #250 checklist](https://github.com/KD5UJC-XYZ/scarab/pull/250);
pre-rebase gates are not relabelled as gates of the integrated head. Main and dev still diverge;
this prepares the dev target and does not claim completion after main landing.

## 42. The judgment in the rule interpreter (#412, foundation)

**2026-10-24, level 2.** `RuleT` gained the recorded-failure trace, the failing flag and the nest
frame ([rule interpreter](../design/rule-interpreter-as-built.md#the-judgment-412)). No rule records
or nests yet, so the measured cost is that of the larger machine on unchanged rules: two more
loop arguments, two more fields in a handler frame and one more frame case. Old is
`feat/refusal-oracle` `d14d42cadff60dfe44584059b538eee76d97b2a3` (`RuleT.scala` SHA-256
`05ab67a4…737b`), new is this change (`RuleT.scala` SHA-256 `1d7210e0…c351`). Both builds are
complete (`node/Test/compile` of each tree), not a class overlay, because `LedgerFailure` and its
users change too. Results are identical: every Preview block and epoch digest and the synthetic
BBODY digest `159017f549d74b4d4cb6a520` agree between the builds. The four `missing-input-*`
digests differ by representation only: they hash `toString` of the refusal, which now holds a
one-element `NonEmptyChain`.

**Synthetic BBODY, quiet host (primary timing).** `CastErasureBench rules 10000` (source SHA-256
`d2409a39…6dd`, unchanged) on a 24-core host whose load stayed near 1.0, Docker `scarab-jdk:local`
`sha256:bc8169dd6a4ec253de2ae1c94dc177fd1082e7807ef9636fa0cbfc1fd8ba368f` (Temurin 21.0.11+10),
old and new built from clean clones at `d14d42ca` and `e753b2a3` (the measured sources), `--cpuset-cpus 16-19 --memory 12g --network none`, `-Xms3g -Xmx9g
-XX:ActiveProcessorCount=4 -XX:+UseG1GC`, twelve JVMs in the order old, new, new, old, old, new,
new, old, old, new, old, new (three warm-ups and five measured runs each):

<!-- register:table perf-48.t1 -->
| build | JVM medians, ms | median of medians | allocated per run, B |
|---|---|---:|---|
| old | 4071.8, 4081.9, 4083.0, 4073.0, 4140.5, 4097.8 | 4082.4 | 5,237,058,240–5,242,529,120 |
| new | 4141.4, 4148.5, 4056.2, 4118.3, 4161.7, 4152.3 | 4145.0 | 5,241,329,144–5,260,689,176 |
<!-- /register -->

The new machine is **+1.5%** slower at the median of JVM medians (+1.5% at the median of the JVM
minima, 4048 → 4108 ms) and allocates up to 0.4% more, inside the 2% budget of the change.

**Preview blocks and epoch (representative inputs, shared host).** `PreviewRuleBench` (source
SHA-256 `92970372…d944`, unchanged) on the inputs of the [typed rule interpreter](#typed-rule-interpreter-202)
section (native V5 snapshot `1323741149d8…f536`, records `ea8b4a7a…685ebf`); the genesis
directory is the maintained `fixtures/preview/genesis` (the same four genesis files, plus the
`checkpoints.json` the current `NodeSetup` requires). Same image digest as that section,
`--cpuset-cpus 20-23`, ten JVMs alternating old/new in pairs. Other workloads used about four
unpinned cores of the host during the runs, and the spread between JVMs of one build is ±15%
for the block sum and +25% for the epoch:

<!-- register:table perf-48.t2 -->
| build | sum of 16 block medians, ms (5 JVMs) | epoch median, ms (5 JVMs) |
|---|---|---|
| old | 10.341, 11.849, 11.197, 9.651, 12.777 | 168.95, 148.06, 136.52, 135.28, 135.20 |
| new | 13.090, 11.947, 9.637, 10.587, 11.394 | 148.18, 130.37, 139.68, 140.83, 147.43 |
<!-- /register -->

Medians of the JVM medians: blocks +1.8%, epoch +3.2%, both well inside the noise of this host;
block allocation changes by −0.03% and the epoch's by +0.01% at the median. These runs establish
identical results and no allocation cost on real inputs; the timing verdict is the quiet-host
synthetic run above. A first Preview series on an earlier candidate, which flattened the empty
trace at the end of every run, was discarded; the measured candidate returns at once when nothing
was recorded. The tables above and the per-case medians, per-JVM lines and exact argument vectors
in the [PR #420 benchmark record](https://github.com/KD5UJC-XYZ/scarab/pull/420#issuecomment-6380265355)
are the retained record; the raw sample logs were discarded after summarising.
