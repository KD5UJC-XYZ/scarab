<!-- SPDX-License-Identifier: Apache-2.0 -->
# Rewards, parallel Plutus and script decoding

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## Bounded parallel Plutus after ordered preparation (D2, 2026-10-18)

[ADR 0018](../adr/0018-parallel-plutus-after-ordered-preparation.md) and the
[as-built design](../design/parallel-plutus.md) replace the earlier scope restriction on
cross-transaction Plutus evaluation. Ledger application remains ordered; only immutable evaluator
requests run concurrently. `--plutus-workers 1` retains the serial rules. The node default is
`min(4, availableProcessors)`, with at least one worker, shared across validation calls including
serial fast paths. The subsequent Preview acceptance for [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21)
is complete and recorded in section 33.

**Dataset and binding.** A read-only capture of eight sealed preprod immutable chunks (6318–6325,
active chunk excluded) contains 7,674 blocks, 49,266 transactions and 32,324 redeemer-bearing
transactions, slots 136468829–136641596, blocks 5298878–5306551. Indexed CRCs, file SHA-256,
header BLAKE2b hashes, slots, block numbers and previous-header links were checked. Capture archive
SHA-256: `3a71ba315d3b17bd00e9f812113aa4a6d1748c506461fecd7a0d523c93618e7c`.
A larger initial capture failed with an I/O error on an older chunk; it produced no usable sample.
This window is the successful narrower capture, not a claim about the omitted data or a diagnosis
of that error.

The replay starts after snapshot slot 136511997/block 5300868/hash
`1f6f537bf2809a8bac92e4d64a9489fa97a5361afacdc0e70fe37a3bb8fe77e7`.
Snapshot file SHA-256: `4fc7424aa6439ef10727dd22425c198b51023fd0d6489c7cce83a9bb2ba36134`.
The snapshot checksum and decoded anchor were independently bound to the captured index/header;
its UTxO checkpoint is restored into a fresh scratch store. The remaining 5,683 blocks contain
43,061 transactions, 27,461 redeemer-bearing transactions across 3,875 blocks, 104,392 collected
requests and 104,388 reached evaluations. Two invalid-tag transactions stop four later scripts.
Only 225 blocks have UTxO dependencies; the graph also accounts for reference reads and
read-before-consume edges. No network sync, deployment or restart was needed.

**Prior evaluator-only experiment.** Every actual context was recorded during serial full-rule
replay, then bound back to its transaction's original body hash, slot and validity flag. Three
rotated rounds over the complete post-baseline window, keeping each transaction's first-failure
order and using dependency batches, gave median sums of per-block evaluator time:

<!-- register:table perf-30.t1 -->
| Concurrent transaction jobs | Evaluator time | Relative to 1 |
|---|---:|---:|
| 1 | 9.194 s | 1.00× |
| 2 | 6.326 s | 1.45× |
| 4 | 4.617 s | 1.99× |
| 8 | 3.798 s | 2.42× |
<!-- /register -->

All 329,532 transaction-result comparisons and 1,252,656 script-result comparisons matched,
including execution units, logs and typed failure payloads. These are prepared-request timings;
they exclude context construction and ledger application. A selected-heavy-block pilot gave larger
speedups and was not used for these claims. On this same post-baseline window, a structural
model weighted by declared execution-step budgets predicted 1.37×/1.47×/1.50× at 2/4/8 workers.
Those budgets are caps, not elapsed work; they were not used as scheduling weights.

**Environment and limits.** Linux x86-64, 24 logical host CPUs, about 139 GiB RAM; Docker JDK
21.0.11, Scala 3.3.8, sbt 1.10.7, Scalus 1.3.0. `scripts/sbtw` uses a 2 GiB maximum heap and
`-XX:ActiveProcessorCount=4`; eight jobs means eight admitted machines, not an eight-core JVM.
Shared-host measurements are not guaranteed throughput. The local, one-block recorder is the
performance-critical imperative exception: the earlier profile attributed 23% to the CEK workers
and the serial apply thread also held the remaining ledger work. A second complete rules pass would
repeat that work. The integrated comparison measures one preparation pass plus scheduling/landing,
rather than presenting the evaluator-only gain as a node gain.

**Integrated full-validation comparison.** Code `7ba8c7f3`, Docker JDK, the same capture and baseline
mounted read-only; `node/testOnly *PlutusReplaySuite` restored a fresh scratch store. Every block
first warmed the original pure serial interpreter, then each of three rounds rotated that interpreter
and 1/2/4/8-worker services within the block. This made 68,196 feature-versus-original complete mirror
state comparisons plus 17,049 repeated-original controls, all equal. The two invalid-tag transactions,
collateral effects, 104,388 reached script evaluations per pass, UTxO diffs, counters and non-UTxO
ledger/consensus state all matched. Total harness duration was 509.2 s; this includes repetitions,
comparisons, snapshot loading and store flushes and is not a throughput result.

Median sums of timed validation over the 5,683 blocks (three rounds):

<!-- register:table perf-30.t2 -->
| Interpreter / worker bound | Full validation | Round range | Speedup vs original serial |
|---|---:|---:|---:|
| Original serial | 34.649 s | 34.603–34.959 s | 1.00× |
| 1, exact serial rules with admission | 34.660 s | 34.647–34.922 s | 1.00× |
| 2 | 29.337 s | 29.327–29.757 s | 1.18× |
| 4 | 26.913 s | 26.895–27.050 s | 1.29× |
| 8 | 26.052 s | 26.045–26.235 s | 1.33× |
<!-- /register -->

Times include direct signature/header validation, HFC ticking, ordinary ledger rules, context
collection, worker admission/scheduling, ordered landing and store reads. File loading, result
comparison, context recording, snapshot loading and store flushes are outside the timed interval.
There is no ahead-of-rules verification cache or scheduling-statistics interpreter in this harness.
These are full block-validation timings on this window, not live follower throughput, network,
forging or whole-node latency measurements. Gains are smaller than the earlier evaluator-only result
because the remaining ledger work stays sequential. Eight workers improve only modestly over four
on this sample; the bounded default remains at most four. The final harness shared the host with
a private era-transition gate; condition order rotated within each block and round ranges are shown.
Pre-hardening replays are preserved separately and are not used for these numbers.

## 29. Reward-update shaping (#135)

**2026-10-19, baseline `7dfcded1` (merged #196), no runtime change.** The approximately 1.1 s
reward-update cost in section 10 was an older, loaded-host measurement. A fresh isolated profile
does not reproduce that absolute duration, but confirms that eager reward calculation concentrates
substantial work into one tick. The proposed incremental implementation and acceptance gates are
in [reward-pulser.md](../design/reward-pulser.md); #135 remains open.

**Inputs and method.** Read-only original Preview K1 snapshot files from
`.workspace/data/2026-10-14-k1-scala-preview/db`, sorted by filename; indices 1400, 1420 and 1449.
`LegacySnapshots.read` and `legacy.Mirroring.toMirror` load/convert each legacy snapshot, as the
boundary harness of section 16 does. Use the resulting `nes.es`, `nes.bprev`, epoch and Preview
globals to call the unchanged `Rewards.update`. Load, conversion, result hashing and Haskell
state encoding are outside timing. Each input warms update and trigger once, then runs 31
repetitions of update, ordinary tick, trigger tick and completed-state tick in that fixed order.

For the tick controls, clear only `nes.ru`: tick exactly at the start slot for the ordinary case
and at `start + 1` for the eager trigger. The completed-state control uses the snapshot's existing
completed update at `start + 2`. These are immutable, middle-epoch snapshot-derived workloads,
**not** replayed states immediately before the historical trigger block. No transaction bodies,
signature checks, complete block application or network work are measured. Legacy seeding and
the existing non-myopic gap remain; this is profiling, not a new Haskell-equivalence claim.

<!-- register:table perf-31.t1 -->
| Snapshot index / epoch | Accounts | Active stake credentials | Snapshot pools / block-producing pools | Reward accounts returned |
|---|---:|---:|---:|---:|
| 1400 / 1401 | 110,718 | 79,745 | 725 / 93 | 5,908 |
| 1420 / 1421 | 111,081 | 79,748 | 727 / 72 | 6,035 |
| 1449 / 1450 | 112,311 | 79,723 | 727 / 74 | 75,616 |
<!-- /register -->

Pool participation, stake distribution and the number of nonzero rewards matter; account count
alone does not explain these workloads' different costs. At `k = 432`, the reference formula
would choose **47 credentials per pulse** for all three. That is a work-item count, not a
measured pulse duration or a guarantee on the separate start/completion phases.

<!-- register:table perf-31.t2 -->
| Index | `Rewards.update` median / range (ms) | Trigger tick median / range (ms) | Ordinary tick median (ms) | Completed tick median (ms) |
|---|---:|---:|---:|---:|
| 1400 | 23.705 / 22.212–59.662 | 24.180 / 22.444–41.456 | 0.219 | 0.152 |
| 1420 | 25.525 / 22.727–44.033 | 25.614 / 22.874–42.534 | 0.221 | 0.162 |
| 1449 | 285.902 / 243.375–368.318 | 291.572 / 237.261–367.183 | 0.230 | 0.174 |
<!-- /register -->

The largest update's median current-thread CPU is 275.339 ms; the trigger's is 286.739 ms.
Its trigger/ordinary tick median ratio is about 1,270, **not a whole-block slowdown ratio**.
All 31 updates of each input produce the same encoded-state digest (93 stable outputs total).

**Setup and sampling.** Linux x86-64, WSL2 shared host, Docker `scarab-jdk:local`, JDK 21.0.11,
Scala 3.3.8, `--cpus 4 --memory 12g`, `-Xms3g -Xmx9g -XX:ActiveProcessorCount=4`, default G1,
network disabled, original database/config/cache mounted read-only. Image ID:
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`.
A separate reference devnet and other
sessions' sbt containers existed; host load before the run was approximately 2.8–3.4 on 24 logical
CPUs. The fixed condition order and limited warm-up mean these absolute times are diagnostic,
not an optimisation A/B or a universal latency estimate. An initial seven-repeat run of the same
recipe gave a largest update median 262.739 ms; the 31-repeat run above is the retained profile.

JFR `profile` settings with `stackdepth=256` yielded 4,315 execution/native samples overall;
383 retained a `ledger.rules.Rewards` frame. Of those, 168 included immutable `HashMap.get`,
159 had `Bytes.hashCode` as leaf, 146 included `Rational.apply`, and 92 included `BigInt.gcd`.
These overlapping, sampled stacks suggest pool lookup/hashing and rational normalisation as
targets for investigation. They do not attribute all elapsed time precisely: inlining can remove
the frame used by the filter, and loading, conversion and hashing appear elsewhere in the JFR.

**Reproduction and binding.** The private standalone test-side entry point is
`xyz.kd5ujc.scarab.node.differential.RewardProfile`; its source, exact Docker argument vector,
runner, class inventory, input/output log, analysis script and JFR are retained outside Git under
`/tmp/scarab/issue-135`. Compile the standalone source against `node/Test/fullClasspath` from
the baseline, in the JDK image; invoke it with `<K1 db> <Preview genesis directory> 1400 1420 1449`.
Its algorithm is the load/convert and four timed calls described above, using `System.nanoTime`
and `ThreadMXBean.getCurrentThreadCpuTime`, then SHA-256 of
`HaskellState.encode(nes.copy(ru = Some(Complete(result))))` outside the timer. The profile used
an isolated copy of the previously gated #196 classes: all Scala source files match merged main
(`git diff 7dfcded1 38936f9b -- '*.scala'` is empty), and `Rewards.scala` SHA-256 is
`a5fb5ebea36fc908000e35bac89c9bd28b996dafec205f58ec049bf4ff6580bf`.

Input SHA-256, with exact snapshot names:

<!-- register:table perf-31.t3 -->
| Snapshot | SHA-256 |
|---|---|
| `snapshot-00000000000121083462-4605407.bin` | `79ce0874f6f8e1abb56a7d83e2ba49e5c0059ecd3bc07545fdaaa7d1d22b7172` |
| `snapshot-00000000000122811953-4664563.bin` | `745df2af04cdcacd4b17cc433e68c54e53bcb13c2e10377ac51ac7c93b3c9f38` |
| `snapshot-00000000000125318360-4756810.bin` | `5ce9ac8813202eb376c12f41f7d00d3c238c52458fb5eb6a77b5dfb364633545` |
<!-- /register -->

Artifact SHA-256:

<!-- register:table perf-31.t4 -->
| Artifact | SHA-256 |
|---|---|
| Standalone source `RewardProfile.scala` | `8c38916527bcb8a11dc8adba35de4f60588edb00decc3bde7ddc3deb0d4e58cc` |
| Baseline class inventory `class-manifest.json` | `a9ddd74851c5171075317d260fea4b7354a0e7d5fdf300f5784c854bc68e945f` |
| Exact invocation `command.json` | `f866809ddba0e3e235761409d26228ad522187932ba5ea2f5112383441062bc5` |
| Output `profile.log` | `66f6aaf22c46cbae2476ae381bce7e8fd8cad865561f3c3cdeb7fc089e2ac868` |
| Recording `rewards.jfr` | `994083d5f323fa3517aceccae49ac9a77e50d71e903e7016be3b73ad865d37ab` |
<!-- /register -->

The resulting-state digests for indices 1400/1420/1449 are respectively
`08da6612ee4a403ad358492b3ccde05199570ba1ff02d66d6c45978c8c63f2f2`,
`7f6cbc39c031526555749b0869aec8fd0cc3d11adc9983ac0fb193c338b0c9b5`, and
`a2912a1475bf177677e31589f4a9832116a5fe619706999851a8b7634ced936b`.


## 30. Incremental reward implementation (#135)

**Scope.** The [implemented machine](../design/reward-pulser.md) spreads member calculation over
reference-sized immutable prefixes. Two different measurements separate scheduling from ledger
admission: controlled API calls on the largest recorded Preview state, and authenticated replay
of the original blocks across the actual start/force/epoch boundaries. These are offline,
shared-host measurements, without a public-network sync.

The initial incremental candidate still allocated 18,942,352 bytes at normal completion to build
75,616 singleton reward sets and a replacement map. Nine warmed completion calls had a median
33.007 ms and maximum 46.676 ms. That measured work justified the pure `SingletonReward`
representation: each pulse stores a singleton set once, the recent map reuses it, and completion
widens the same covariant immutable map. The accumulated-entry CBOR remains one `Reward`; an
imported accumulated leader reward is accepted as the reference allows. No mutable cache,
second pulser or imperative exception was introduced.

**Controlled regular schedule.** The private `PulseProfile` loads the section 29 index 1449
state outside timing and calls `start`, every member pulse, and the final empty-source completion
call. It warms that whole sequence once per JVM and measures three subsequent sequences; three
rotated eager/pulsed pairs produce nine measured sequences per condition. The eager baseline is
main `7dfcded1`; the candidate runtime source is `bfcd4fd0`. The same Docker/JDK/heap/CPU settings
as section 29 apply. `ThreadMXBean` measures current-thread CPU and allocation; timings exclude
loading, encoding, digests, per-call sample bookkeeping and explicit GC. Each output's monetary
and attributed-reward digest must match after normalising only the existing non-myopic gap.
Explicit GC before and after each sequence measures the retained completed-result delta, rather
than node-wide peak memory. A separate untimed prefix probe samples retained heap while keeping
one current immutable state. Neither measures a full node's retained volatile rollback history.

Sources, exact Docker argument vectors, class/source inventories, hashes, stage samples and
analysis stay outside Git under `/tmp/scarab/issue-135`. The controlled-profile runtime binding is
`candidate-binding-singleton.json` (SHA-256
`c362e39ea9e4f2903b2830655902e6ad82e2b4c88506fe650863b4f8063ef55e`):
eight runtime source files and 10,783 compiled production files. The final edits were compiled
while uncommitted, then recorded by `bfcd4fd0`; generated build metadata can name the preceding
head. Independent source/class hashes bind the measured implementation.


This controlled profile is bound to the **pre-rebase, pre-import-validation** runtime above.
The paired block replay uses main `0b5dde8f` plus the subsequent typed refusal for invalid
imported relative stake; it has a separate binding. Final gate/rebase bindings are recorded
separately in the compatibility log. All nine largest-state output
digests agree with the baseline's `a2912a1475bf177677e31589f4a9832116a5fe619706999851a8b7634ced936b`.

<!-- register:table perf-32.t1 -->
| Controlled API condition | Wall median (ms) | Largest wall call (ms) | Current-thread CPU median (ms) | Allocation per sequence (MB) |
|---|---:|---:|---:|---:|
| Eager, one call | 265.582 | 335.810 | 264.547 | 332.297 |
| Start | 4.278 | 6.214 | 4.276 | 5.199 |
| Member pulse, 1,697 calls/sequence | 0.0717 | 0.269 | 133.884 summed across pulses | 249.234 summed across pulses |
| Normal completion | 15.990 | 19.299 | 16.011 | 8.066 |
| Pulsed, whole sequence | — | — | 154.660 | 262.500 |
<!-- /register -->

Stage medians are medians of the nine per-sequence values; the member wall median is the median
of each sequence's per-call median. CPU/allocation totals exclude instrumentation between calls.
The CPU ratio is 0.585 of eager and allocation 0.790, within the proposed total-CPU target. The
remaining completion reduction over all delivered rewards is linear, as in the reference. Its
15.99 ms median is real CPU work, not merely scheduler noise, and exceeded the original proposed
5 ms API completion target; the largest start call also exceeds 5 ms. On 2026-10-19 the owner
explicitly accepted this measured normal-completion result relative to the historical approximately
1.1-second reward update, superseding the proposed 5 ms API target. The separate whole-block tail
and total-CPU criteria remain; #135 has no remaining 5 ms follow-up requirement. This records an
explicit acceptance decision rather than silently changing reference timing or hiding the result.

A cheaper section 29 index 1400 control (79,745 captured credentials but only 5,908 rewarded
accounts) also completes the same 1,697-pulse schedule and reproduces its baseline digest. It is
a three-measured-sequence diagnostic pair, without the largest workload's three-pair rotation.
The regular completion calls are 1.57–1.82 ms; many zero member results avoid retained reward
allocation. This control demonstrates that captured credential count alone does not predict
completion cost.

The largest-state completed-result retained deltas are 9.984 MB eager and 10.061 MB pulsed (medians
of explicit-GC measurements). The untimed prefix probe observes incremental retained heap rising
from 0.112 MB at start through 2.566/5.094/7.619/10.123 MB after 425/850/1,275/1,697 pulses, then
10.144 MB after completion; eager completion in that separate probe retains 10.020 MB. These are
sampled deltas with one current reward state and the input snapshot retained. They are not an
upper bound on peak live heap, a volatile-history benchmark or a full-node soak result.

Private reproduction uses `PulseProfile.scala`, `run-micro-singleton.py` and
`run-micro-cheap.py`; the separate untimed probe is `RewardMemoryProfile.scala`/`run-memory.py`.
The input names and hashes are in section 29. `micro-singleton-summary.json` retains all rounds,
stage summaries and output-log hashes; exact Docker argument vectors are in
`micro-singleton-commands.json`. Host load was about 6.6 on 24 logical CPUs at timing start;
other sessions compiled on the shared WSL2 host, and no other owned timed benchmark ran
concurrently. Ordinary JVM JIT/GC and host scheduling remain sources of variation.


**Recorded block replay against main `0b5dde8f`.** The final comparison uses eager main `0b5dde8f` and
feature runtime `c8265de3`, including the imported-divisor guard. Both load the same checksummed
native V5 copy of the archived K1 snapshot at slot 125231955/block 4753556, paired with its
RocksDB checkpoint. Authentication checks original block CRCs, original-header hashes, slot/block
numbers and parent links. The native input retains the original anchor, gaps and observations;
its unmasked canonical NES SHA-256 is
`9488acde5daafe88bff31aecbb1a52ba1ffee4ff971eaf17df68b2bc41930049`. Full typed equality with
the archive requires only the documented normalization of empty pool-relay encodings; no reward
fields are masked in this input proof. The original archive is unchanged.

Three rotated pairs replay 4,971 original blocks per condition, from the authenticated anchor
through epoch 1451: 29,826 full block applications. All 42 paired full-ledger checkpoints and all
three paired whole-UTxO digests agree; 28 repeated eager ledger controls and two repeated eager
UTxO controls also agree. Canonical comparison completes captured pending rewards outside the
validation timer and masks only the named `NonMyopic` gap. The actual pulser prefix is retained
for subsequent validation; the comparison does not replace live state with the completed view.

<!-- register:table perf-32.t2 -->
| Final recorded replay measure | Eager main | Incremental |
|---|---:|---:|
| Start block, median | 349.320 ms | 18.361 ms |
| First pulse block, median | 2.559 ms | 3.867 ms |
| First block past force, median | 0.926 ms | 36.755 ms |
| Normal reward interval p99, range across pairs | — | 10.898–11.508 ms |
| Same-epoch ordinary control p99, range | — | 9.105–9.487 ms |
| Normal reward interval maximum, range | — | 22.578–24.370 ms |
| Same-epoch ordinary control maximum, range | — | 16.524–19.040 ms |
| Full-window validation, median | 15.228 s | 12.839 s |
| Full-window process CPU, median | 23.36 s | 20.66 s |
| Full-window allocation, range | 20.742–20.761 GB | 20.674–20.698 GB |
| Maximum sampled retained heap, range | 310.85–311.26 MB | 310.76–311.30 MB |
| Maximum observed heap, range | 3.114–3.221 GB | 2.962–3.078 GB |
<!-- /register -->

The candidate's normal-interval p99 is at most 1.243 times its matched ordinary control, and its
maximum at most 1.367 times: both satisfy the proposed 2 times full-block tail criterion. Whole-
window time ranges overlap, so the three pairs do not establish a firm throughput improvement.
Process allocation counters cover currently live threads; observed heap is not RSS. Explicit GC
at matched comparison checkpoints is outside validation timing and may affect later blocks.
Completing captured rewards for comparison also warms calculation methods outside the timer.

The epoch 1450 window has only 1,261 blocks between start and force, fewer than the 1,697 member
pulses plus start/completion required by this state. It therefore necessarily forces an unfinished
suffix. The 36.755 ms force block measures reference-required remaining work; it does not measure
natural completion. Natural completion is measured separately in the controlled API profile.
On 2026-10-19 the owner explicitly accepted processing on the order of tens of milliseconds,
including the measured start, normal completion and sparse-chain forced completion. No further
latency follow-up is required for this stage.

Exact commands, full block rows, input/checkpoint hashes, native conversion proof, dependency
jar/genesis hashes, copied runtime inventories and analysis are retained under
`/tmp/scarab/issue-135-replay-native`. `accepted-evidence.json` binds 163 artifacts (SHA-256
`06ed671670fd978ca25f9ada7d6b280ece133b81466ab3ecd5dfd81b287c5738`);
`benchmark-binding.json` SHA-256 is
`c5eef7c34fed4823a9e05bbea3fe3aadd3b9fa667076a991e4373e5b012bbcf1`.
The feature author's independent manifest of 8 reward sources, 452 main sources and 11,379 production files
is `candidate-binding-rebased.json` in `/tmp/scarab/issue-135`, SHA-256
`9e8b987d17d35b1e1a4b9df5677bca8c59ccbce874e9d2cf4d4ae49b4a6808d3`.
Final verification confirms all original inputs, copied classes and bound sources unchanged.

## 40. Flat script decoding: an imperative walker against a functional one

**2026-10-25, level 2.** `FlatScan.decode` reads the flat bytes of every Plutus script in every block. Until
this measurement it was a pure function outside and an imperative walker inside: the bit position in a `var`,
`ArrayDeque` stacks of tasks, terms and constants, a mutable field count, and `boundary`/`break` for the typed
failures. AGENTS.md admits that form only on a performance-critical path with measurements, so the same contract
was written without mutation and the two were timed. The functional form is now production code; the imperative
walker stays in the tests unchanged as `FlatScanImperative`, the baseline of the equivalence suite and of the
benchmark.

**The functional form.** Each reader (`bits`, `natural`, `word64`, `byteString`, the type tags, the `value`
entries) is a pure `@tailrec` function of the bit position that returns the value and the position after it, or
the failure. The term walk is a `@tailrec` loop whose parameters are its whole state: the position, what to do
next, the frames of the terms under construction, the scan facts and the ordinal of the next variable. A frame holds
the parts of its term already built and the frames below it, so a finished term always has a frame to go to or ends
the walk; no stack of values can run short, and nothing is popped that might not be there. Constant values (lists,
arrays, pairs) have their own loop of the same shape. The stack is on the heap, as
[ADR 0011](../adr/0011-stack-safety.md) asks: the four deep programs of `FlatScanDepthSuite` (a `data` constant of
16,000 levels, 100,000 nested `delay`s, an application spine of 50,000, a constant type of 5,000 nested lists)
decode on a 256 KiB stack.

**Equivalence.** `FlatScanImperativeSuite` compares the two whole, program and scan facts or failure, on a
1 GiB stack (equality of a deep term recurses): every fixture script `FlatDecodeParitySuite` reads (the upstream
conformance programs and sidecars, the costing, boundary, upstream-boundary and transaction script corpora, the
boundary probes) plus the flat decoding probes and the deep programs; every prefix of each script up to 512 bytes
and 512 evenly spaced prefixes of a longer one; and sixteen seeded single-bit changes of each. All are equal. The
suite fails if a deliberate change is made to the functional form's filler failure, its `constr` field count or its
variable ordinals (each tried once). The benchmark also compares every input it times before timing it.

**Harness.** No JMH (the build has no plugin); a timed loop in the style of `CastErasureBench`,
[FlatScanBench](../../ledger/src/test/scala/xyz/kd5ujc/scarab/bench/FlatScanBench.scala), with the current thread's
allocated bytes (`ThreadMXBean.getCurrentThreadAllocatedBytes`). Per workload: eight warm-up passes of each
variant of about 50 ms, then ten rounds of about 250 ms each in ABBA order, the same number of passes for both;
the reported figure of a JVM is the median of its ten rounds. Workloads:

- *real scripts*: the 74 distinct Plutus scripts (witness scripts and reference scripts of spent, referenced and
  collateral outputs) of the 45 recorded Preview and 4 preprod transactions in `fixtures/preview/plutus` and
  `fixtures/preprod/plutus`: 277,531 bytes, from 6 to 16,086 bytes, median 2,960; one pass decodes all of them;
- *largest real script*: the 16,086-byte one;
- *real scripts applied to each other*: the terms of the real scripts, largest first, applied to one another until
  the program passes 64,000 bytes (73,780 bytes), encoded by Scalus;
- the four deep programs above.

**Environment.** Euler, Intel Core Ultra 9 285K (24 cores, WSL2), shared with other build containers (load average
4.5 to 6.8, not pinned away from these CPUs). Docker image `scarab-jdk:local`
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`, Temurin 21.0.11+10, Scala 3.3.8;
`--cpuset-cpus=8-11 --memory=4g --network none`, `-Xms2g -Xmx2g -XX:+UseG1GC -XX:ActiveProcessorCount=4`. Classes
frozen from commit `a829737e2` (the functional form in the tests, production unchanged; built before a rebase that changed only `.gitleaks.toml`), one JVM at a time in the
order imperative-first, functional-first, functional-first, imperative-first.

<!-- register:table perf-46.t1 -->
| Workload | Imperative µs/op | Functional µs/op | MB/s imp. / func. | MB allocated/op imp. / func. | Time, median (per-JVM range) | Allocation |
|---|---:|---:|---:|---:|---:|---:|
| Real scripts (74, 277,531 B) | 8,155 | 8,456 | 34.0 / 32.8 | 25.04 / 34.64 | +3.7% (+2.0% to +7.8%) | 1.38× |
| Largest real script (16,086 B) | 300.6 | 289.8 | 53.5 / 55.5 | 1.050 / 1.286 | −3.6% (−8.2% to +0.8%) | 1.23× |
| Real scripts applied to each other (73,780 B) | 1,835 | 1,940 | 40.2 / 38.0 | 5.87 / 8.52 | +5.7% (+0.4% to +12.1%) | 1.45× |
| `data` constant, 16,000 levels (16,071 B) | 3,422 | 3,477 | 4.7 / 4.6 | 20.75 / 20.72 | +1.6% (0.0% to +4.4%) | 1.00× |
| 100,000 nested `delay`s (50,004 B) | 2,315 | 1,245 | 21.6 / 40.2 | 7.45 / 6.40 | −46.2% (−47.1% to −40.6%) | 0.86× |
| Application spine of 50,000 (50,004 B) | 1,740 | 1,896 | 28.8 / 26.4 | 5.45 / 7.60 | +9.0% (+4.8% to +24.9%) | 1.39× |
| Constant type of 5,000 nested lists (6,255 B) | 90.1 | 109.2 | 69.5 / 57.3 | 0.169 / 0.681 | +21.2% (−0.8% to +41.6%) | 4.04× |
<!-- /register -->

Times are the median of the four per-JVM medians; the range is the per-JVM ratio, functional over imperative, in
the same JVM. Real scripts per script: 110 µs imperative, 114 µs functional. Two more JVMs at the switched head
(`0a9484f0f`, production functional, baseline in the tests) gave real scripts +0.1% and +6.8%, 1.38× and 1.40×
allocation, and the other rows within the ranges above. The production walk at that commit is the measured one
token for token apart from comments and the `scan` wrapper (compared with comments stripped).

**What the numbers say.**

- On real scripts the functional form is within 8% in every JVM, 3.7% at the median; on the largest real script it
  is no slower. This host's run-to-run spread is of the same size.
- It allocates about 1.4 times as much on real scripts: about 35 MB per pass of 277 KB, against 25 MB. The extra
  is short-lived (a `Next` value per step, a frame per term read, a `Got` per variable-length read, list cells for
  type tags), and its collection cost is inside the measured time. Per byte of input the worst functional figure
  apart from `data` is the application spine at 152 B/B, against the imperative's worst of 149 B/B (the `delay`
  chain); `data` constants dominate both at about 1,290 B/B, all of it in the shared `PlutusData` decoder.
- The 4.04× of the nested list type is relative to a cheap baseline: 0.68 MB for 6 KB of type tags (109 B/B), below
  the real-script average. From protocol 11 the reference refuses a type header longer than 32 tags.
- Part of the time difference is algorithmic, not a matter of style: the functional `bits` checks the end of the
  input once per read, the imperative one once per bit. The 46% gain on the `delay` chain most likely comes from that
  and from the walk's shape (one frame per `delay`, against two tasks and a term stack entry), not from immutability.

Allocation of the functional form while it was written (sbt `runMain`, one JVM, not pinned, so only the allocation
ratios on real scripts are worth quoting): a plain version returning a fresh `Right` per read, frames in a `List`
and a tuple per variable allocated 1.70×; caching the 256 results of a read of up to eight bits and dropping the
tuple brought it to 1.55×; making each frame hold the frames below it (no list cell per frame) to 1.38×. A JFR
allocation profile of the second version put the list cells of the frames and the `Next.Give` values first.

**Recommendation and decision.** Switch, and keep the functional form: the time is within the 10–15% the owner set
for this choice on real scripts, and the allocation increase is moderate, short-lived and already paid for in the
measured time. The imperative exception of AGENTS.md is not justified for
this path. `FlatScan.decode` keeps its contract (`Decoded`, `FlatScan`, `ValueFault`, `Failure` unchanged); the
equivalence suite keeps the old walker as its reference so a later change to either is caught.

**To repeat.** In the JDK container: `bash scripts/sbtw "ledger/Test/runMain xyz.kd5ujc.scarab.bench.FlatScanBench
imperative 10"` (the first argument names the variant each workload starts with, the second the rounds). For the
figures above, export `ledger/Test/fullClasspath`, copy the class directories it names, and run the main class with
`java` in a fresh container with the flags above, once per order.
