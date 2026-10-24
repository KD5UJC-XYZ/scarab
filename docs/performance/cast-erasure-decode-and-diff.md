<!-- SPDX-License-Identifier: Apache-2.0 -->
# Typed decode interning, Diff retention and snapshot codec derivation

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## Typed Diff instance retention (#206)

**Status: measured on 2026-10-20; diagnostic comparisons, not block-application throughput.**
The typed derivation in #244 removed the erased `Diff[Any]` instances but could construct an enum
case's instance on every comparison, and its product walk could summon conditional field instances
again for every value. #249 now retains both in lazy per-derivation `Memo`s. A product builds its
field steps once and walks their array in field order; a sum selects its step by the mirror's known
ordinal. The local indexed field loop avoids a new field iterator for each record comparison and
stops at the first difference; its mutation remains inside the pure `Diff` implementation. The
measurements below cover that path and its allocation cost.

Ordinal selection also preserves correctness for mixed singleton/data enums. In the first typed
implementation, `ClassTag[Empty.type]` can have the enum's runtime class, so a walk that tries the
singleton first can accept two `Full(value)` instances there and return no difference. The focused
`DiffSuite` control reproduces that on `2ef35836`; selecting the actual ordinal reports the `value`
field instead. The original cast-based ordinal dispatch already behaved correctly. This is a
diagnostic comparison fix; no changed ledger-admission verdict is demonstrated.

**Inputs and sources.** `ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/state/DiffBenchMain.scala`
builds two independent equal diagnostic states, each containing 20,000 sorted accounts with a
nested enum balance, and persistent copies differing at the first or last account. Every workload
asserts the expected complete first-difference path before timing. This is a deterministic model;
the existing consensus properties and the separate `e2e` gate cover actual ledger-state comparison.
The same benchmark source was compiled with each historical `Diff.scala`, so the inline derivation
also belongs to the measured implementation:

- Original cast-based implementation: `57608c6e`.
- First typed implementation, before instance retention: `2ef35836`.
- Field and case retention: `4f8cc484`, with `Diff.scala` SHA-256
  `1bf891f956babbf5fe3b03a89eb89ef995f586777e227897c9a3bdbc710ded5e`.
- Shared harness at `4f8cc484`, SHA-256
  `098a2201e8b7f771394729f987209145bb807f46995072531b2dbc13a89b690b`.

The narrow dev-based replacement (#260, source `0d172b08`) contains the same candidate
implementation and identical harness. Its `Diff.scala` SHA-256 is
`e7dbf222c55d12bbf70331f7d234fdf51ca0ef6a8aa326ac64c08516fe18a424`; only comments
changed after the measured revision. The standalone source comparison above does not depend on
merging main into dev. Whole-tree and private-scenario evidence for the dev-based branch is
recorded separately in [compatibility.md](../compatibility.md#evidence-log).

**Protocol.** Docker `scarab-jdk:local` image
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`, OpenJDK21.0.11,
Scala3.3.8, `--cpuset-cpus 8-11`, no CPU quota, `-Xms1g -Xmx1g`, one JVM at a time. Each case warms
for at least two seconds, then takes seven samples. A sample performs 20 equal/late comparisons or
100,000 early/same-case comparisons; reported time and thread allocation are divided by that count.
`ThreadMXBean.getCurrentThreadAllocatedBytes` brackets the timed body. Complete input and output
fingerprints are outside timing. Run order is original/typed/candidate/candidate/typed/original.
The host was shared with a private relay, without a simultaneous build on the benchmark CPU set.
Short, count-based exploratory warmups gave unstable equal-state medians and are superseded by this
protocol. This is a warmed comparison; it does not measure cold derivation latency or retained heap.

For the maintained candidate, run in the documented Docker build:
`ledger/Test/runMain xyz.kd5ujc.scarab.ledger.state.DiffBenchMain`. To reproduce a historical row,
compile this identical harness with that revision's `Diff.scala`, rather than replacing runtime
classes underneath types whose derivation has already been inlined.

The two run medians are shown as ranges; parentheses contain the minimum/maximum sample across
both runs. Allocation is bytes per comparison, also retaining its observed range.

<!-- register:table perf-36.t1 -->
| Workload | Original | First typed | Field/case retention | Allocated bytes: original / typed / retention |
|---|---|---|---|---|
| Equal 20,000-account state, ms | 2.044–2.054 (2.022–2.160) | 1.954–2.013 (1.939–2.094) | 1.953–1.984 (1.913–2.079) | 4,468,139 / 6,695,947 / 4,615,907–5,255,907 |
| First-account difference, ns | 99.6–104.7 (94.2–123.1) | 99.6–102.9 (92.6–121.5) | 94.9–95.3 (87.6–119.4) | 888 / 904 / 880 |
| Last-account difference, ms | 0.704–0.705 (0.699–0.709) | 0.736–0.744 (0.733–0.753) | 0.734–0.752 (0.729–0.753) | 875 / 915 / 891 |
| Same enum case, ns | 29.4–40.7 (28.0–51.3) | 36.8–41.4 (34.7–48.7) | 30.2–30.9 (29.2–32.0) | 288 / 360 / 304 |
<!-- /register -->

No primary elapsed-time median is more than 10% above either historical implementation on these
workloads; the largest increase against the original is 6.8% for the last-account difference.
Equal-state allocation falls 21.5–31.1% against the first typed implementation, but remains
3.3–17.6% above the original; it is not allocation-neutral. Keep that range rather than describing
all typed narrowing costs as removed. The original's 29.4–40.7 ns same-case spread also precludes a
general microbenchmark speedup claim.

All six runs had identical input SHA-256
`e427593da102bf0a2ffc1ca24e74e3edc573157dffd235513bfecce451d2ea91`, complete state-difference output
SHA-256 `a45384a2914effec34e899b6eb41b2ae93a5d4f8bdfc263671f01ac5d5111491`, and enum-output SHA-256
`e0e96fc5eee36517c9e0b40b18d4e44df9f872029cc259f57663c66bdf003c62`. These bind the measured model
only; they do not assert public-chain state parity or cover codec derivation/interner performance.
## 34. Typed per-kind decode interning (#204)

**2026-10-20, level 3.** `Interned[A]` owns a homogeneous weak scope index returning only
`Table[A]`; the document retains that table strongly. Typed entries preserve the path
`Interned[A] → Table[A] → Entry[A] → A`. No heterogeneous value recovery, source cast or runtime
generic/phantom-role test remains. The [ADR 0013 addendum](../adr/0013-cbor-documents-are-an-index-over-the-input.md#addendum-typed-kind-ownership-204-2026-10-20)
records ownership, synchronization and the measured mutable-table exception. `Node.share` and
`Wire.interned` now require reference types; production `Bytes`/`Credential` kinds are unchanged.

The first homogeneous-array prototype (`7e4e87f0`) preserved all measured outputs and retention;
its legacy-derived decode aggregate was +7.69%, with a +22.17% extreme cross-JVM comparison.
A typed weak recent-association fast path (`c46d0214`) still measured **+10.45%** on that primary
input, failing the acceptance bar. Those complete experiments remain under
`/tmp/scarab-204-bench-diagnostic` and `/tmp/scarab-204-bench-weak-fast`; they are not relabelled as
passing or discarded. The final design `9ac7ea5e` uses typed entries carrying cached hashes.
Unequal hashes skip deep equality, and growth reuses each entry's hash. It adds entry allocation;
the full decode and memory measurements below include it. These experiments do not isolate a
causal cost for weak references, array stores, JIT or GC.

**Inputs and comparison.** Baseline is dev `2ef358366f3a50397645851f7db81b6a91c5c779`, with
frozen classes from the #247 baseline. That build has the visibility-only `private[storage]`
`SnapshotV5.encoded` seam, which is not invoked here; decode behavior is unchanged. Candidate
production differences are `Interns` and the reference bounds on `Node.share`/`Wire.interned`.
The compiled source/class inventories bind the experiment independently of Docker's unknown
`BuildInfo.gitCommit`. The maintained helper is
`node/src/test/scala/xyz/kd5ujc/scarab/storage/InternDecodeBench.scala`; at measured source
`9ac7ea5e`, its formatted source differs only in whitespace from the standalone file
(stripped-source equality asserted). The later tool-only cleanup puts sampler shutdown in
`finally` if a decode refuses. A checksummed invalid-content control reproduces the old helper
printing its refusal but remaining alive after 15 s; the corrected helper exits 1 promptly.
Latency mode and successful memory sampling are unchanged; no timing is attributed to that fix.

<!-- register:table perf-37.t1 -->
| Input | Identity | Bytes |
|---|---|---:|
| Current Preview native V5 | #27 authenticated bootstrap/replay, slot 125669042/block 4769869; `snapshot-00000000000125669042-4769869.bin`; SHA-256 `bbdc6fd7434564e0a289af5b596401d713dca75b26e70172b16803683e7be512` | 51,199,374 |
| Legacy-derived preprod native V5 | Maintained `preprod-v4` corpus, `snapshot-00000000000136444301-5297790.bin`; SHA-256 `b77a119082acbda5d6c2fb43131b5cad856056790b13a0164a02f48299a2ebcf` | 51,777,723 |
<!-- /register -->

The second input is the authenticated V5 conversion at source
`648aed117f826d06117f616b5c0a3716a50485a7`, manifest SHA-256
`d81e326f85eb47d7a92d041a3309007599bcdaa8e70de0eaf4b38bb293ce0168`, of the archived V4
51,584,590-byte capture `0b7c061b8e47560c665d3d22c7898ca53884c361002c2975bd5d925a433f94a8`.
Original archives are unchanged; no retired V4 decoder is restored. The inputs remain outside Git
under `/tmp/scarab/mithril27/download-bootstrap/snapshots` and
`.workspace/data/2026-10-18-snapshot-v5-corpus/preprod-v4/snapshots`. They contain complete native
snapshot payloads and UTxO handles/statistics, not the separately backed RocksDB UTxO entries.

**Protocol.** Euler Linux x86-64/WSL2 shared host; Docker Temurin 21.0.11/Scala 3.3.8 image
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`, CPUs 8–11, no
CPU quota, container memory 12g, network none, `-Xms3g -Xmx9g -XX:ActiveProcessorCount=4
-XX:+UseG1GC`. One timed JVM at a time, old/typed/typed/old order, fresh JVM per input. Ten full
decode warmups and nine samples follow an initial control. `SnapshotV5.decode` runs on the measured
thread; wall nanoseconds and current-thread allocated bytes exclude file I/O/checksum, config
loading, full result equality and SHA-256. Every measured result is compared completely with its
control outside timing. Separate fingerprints compare all old/new states and truncated-input
refusals. The later documentation/check commits do not change the measured runtime.

<!-- register:table perf-37.t2 -->
| Decode | Old per-JVM medians (ms) | Typed per-JVM medians (ms) | Median-of-medians change | Allocation old → typed (MB/decode) | Slowest typed / fastest old median |
|---|---:|---:|---:|---:|---:|
| Current | 500.121 / 601.654 | 513.600 / 509.894 | −7.11% | 1,241.477 → 1,241.858 (+0.03%) | +2.70% |
| Legacy-derived | 754.154 / 751.726 | 815.894 / 733.997 | +2.92% | 1,596.298 → 1,601.184 (+0.31%) | +8.54% |
<!-- /register -->

Both primary inputs pass the 10% limit, including the adverse cross-JVM comparison. The shared
host and the old current-input spread prevent a firm general speed-up claim. Per-sample rows,
allocation ranges, full command vectors, source/class/config inventories and output hashes remain
in `/tmp/scarab-204-bench`; all samples, not only fastest runs, contribute to these medians.

**Memory.** Separate fresh JVMs use the same old/typed/input order. After input/config setup,
`jcmd 1 GC.class_histogram` forces a full collection at BASE, LIVE (exactly one typed state held),
and RELEASED (state dropped). `GC.heap_info` and `MemoryMXBean` record the same checkpoints.
A 1 ms sampler observes heap during the single cold decode, stopping before output rendering;
histograms and full collection are outside decode timing. Peak is sampled used heap, not RSS or
an exact high-water bound. Net LIVE−BASE includes runtime initialization as well as the state.

<!-- register:table perf-37.t3 -->
| Memory, median of two JVMs | Current old → typed (MB) | Legacy-derived old → typed (MB) |
|---|---:|---:|
| LIVE−BASE | 152.195 → 152.151 (−0.03%) | 229.779 → 230.503 (+0.32%) |
| Sampled absolute peak | 528.591 → 533.744 (+0.97%) | 627.649 → 633.424 (+0.92%) |
| Sampled peak−BASE | 397.903 → 403.009 (+1.28%) | 497.274 → 503.040 (+1.16%) |
| RELEASED−BASE | 0.832 → 0.829 | 1.076 → 1.075 |
<!-- /register -->

Every old/new LIVE histogram has exactly the same relevant retained counts:

<!-- register:table perf-37.t4 -->
| Objects | Current | Legacy-derived |
|---|---:|---:|
| `Bytes` | 604,950 | 1,170,833 |
| `ledger.Credential.Key` / `.Script` | 292,119 / 3,477 | 573,337 / 7,826 |
| Native `Credential.KeyHashObj` / `.ScriptHashObj` | 714,117 / 19,448 | 720,423 / 16,291 |
<!-- /register -->

No `Interns.Table`, `Entry` or `Keep` remains after the LIVE collection: these materialized states
no longer retain their decoding document. At RELEASED the credentials/wrappers are gone and
`Bytes` returns to 99/121 objects in both conditions (setup BASE 98/120). Tests separately retain a
live document across GC and prove its first value survives; a stable kind retains neither an
expired scope nor its values. Concurrent documents, distinct phantom kinds with the same runtime
class, 4096 equal-hash collisions through growth, full-state sharing and the million-voter 256 MiB
heap control all pass. There is no material retained-heap regression on these captures; this is
not a long-running concurrent-node heap claim.

Full state digests are `a3fd6ef08420f9aa5e5e658cd0ab20e9699ef6dc93eb0afb3795d622768657b7`
and `4caa61c9366251e1da56fbcde62e467887ac8f4824c812438b01c53ab11597fc`; the complete truncated
refusal digest is `8b8d7c5df5b02e8f1b3d313a192316ef8a61781b4732f14e9633769a5fe42987`.
They match in every applicable run. Binding SHA-256
`36e7bb1f5d40f7f0be324bb6c66b9620a429d65e18e3eba83a19c8fae22d6338`; summary SHA-256
`636265625d56459b227e877581c20a58cfb2029a765f24c7130a2259d9c3a11d`; measured helper SHA-256
`38fdc419b67bf9bc20a30c936c5edfb8c7aef48a2457233c948c74b8163d0d50`.

**Scenario and gates.** On `9ac7ea5e`,
`python3 scripts/devnet.py --name scarab-204-root-entries-restart restart` exits 0: 30 block
applications, two 0.7 s graceful stops, two native V5 shutdown snapshots, one restoration, no
errors/refusals. Reference node 11.1.3/CLI 11.2.3.0, image
`sha256:74e849e1b68e2cf8ba3cdfe3fcda67114f22d9bebc6a34372657cd179e704c4b`.
Owned containers are removed; restart manifest SHA-256
`bc0a6bdc0ba5098852d2dd1e77c23b49099113d96de35b3c4a8e2d56badcec53` is retained under
`/tmp/scarab-204-evidence`. Docker `check` at this runtime source passes 5,144 tests with 16 optional-data skips, zero
failures/errors across 14 modules, formatting/-Werror/standard fuzz/scalafix. The
extended `fuzz 100000 1 4` gate passes 77 targets × 400,000 cases = 30.8 million, zero failures.
Combined check/fuzz log SHA-256 `645fe3088804e9502742369e1ab6052e41eba03071062560efd4c08f0a259252`.
The [PR #263 checklist](https://github.com/KD5UJC-XYZ/scarab/pull/263) carries the exact
published-head CI identity. Source-changing prototypes'
aborted checks are not counted as passing gates.

Reproduction: build each source with its own Docker/private-sbt-socket worktree, emit
`node/runtimeClasspathFile`, freeze classes, and compile the same maintained helper against each
classpath. Invoke `InternDecodeBench latency <label> <snapshot> <configs>` with the JVM/container
settings above. For `memory`, keep stdin open; at each `READY BASE|LIVE|RELEASED`, run
`docker exec <own-container> jcmd 1 GC.class_histogram` and `GC.heap_info`, then send `continue`.
The retained `run.py` records exact Docker vectors and drives these checkpoints without manual
timing choices. No file encoding or previous-version admission behavior changes.


**Integration after #260 (2026-10-20).** Rebased onto dev
`6988858f937a42eb34762cb5a61d3c622c49b124`. Interns/Node.share/Wire.interned production sources are unchanged; the newly merged Diff diagnostics are outside the timed snapshot decoding operations.
The original benchmark/gate source identities above remain historical evidence. Refreshed
checks on the rebased head are recorded in the [PR #263 checklist](https://github.com/KD5UJC-XYZ/scarab/pull/263);
pre-rebase gates are not relabelled as gates of the integrated head. Main and dev still diverge;
this prepares the dev target and does not claim completion after main landing.

## 35. Typed snapshot codec derivation (#205)

**2026-10-20, level 3.** The replacement uses generated, compiler-checked field accessors,
typed result tuples and root-relative sum-case evidence. It preserves the measured bytes,
fingerprints, case order and error paths. The two representative snapshot workloads meet the
issue's +10% primary-workload limit without an exception. This does not assert zero cost for
every record shape: the tiny encode control below retains a cost tracked by
[#257](https://github.com/KD5UJC-XYZ/scarab/issues/257).

### Sources, inputs and scope

Old source is `dev` `2ef358366f3a50397645851f7db81b6a91c5c779`. Candidate implementation is
`4196bebb`, benchmark entry points and the measurement seam are `ab85c6a7`; `679ea7f8`
strengthens test probes only. All other production Scala sources are held constant. Both
conditions apply the **same visibility-only change** to `SnapshotV5.encoded`, so the benchmark
calls the actual pure encoder on its current thread, without a disk write or an effect-thread
allocation-counter mismatch. No encoding implementation change is hidden in this seam.

The maintained helpers are `node/src/test/scala/xyz/kd5ujc/scarab/storage/SnapshotCodecBench.scala`
and `storage/src/test/scala/xyz/kd5ujc/scarab/storage/BenchCodecMain.scala`. The latter now actually
uses cheap primitive sinks: byte length, decoded `Row.i`, and decoded `FutureGenDeleg.slot` bits.
Its former decode sinks called `hashCode`, as review found. Earlier reports saying those hashes
had been removed were incorrect. Output SHA-256 and structural comparisons are outside timing.

<!-- register:table perf-39.t1 -->
| Input | Provenance and SHA-256 | Bytes |
|---|---|---:|
| current Preview V5 | #27's authenticated bootstrap/replay, endpoint slot125669042/block4769869; `snapshot-00000000000125669042-4769869.bin`, `bbdc6fd7434564e0a289af5b596401d713dca75b26e70172b16803683e7be512` | 51,199,374 |
| legacy-derived preprod V5 | `snapshot-00000000000136444301-5297790.bin`, `b77a119082acbda5d6c2fb43131b5cad856056790b13a0164a02f48299a2ebcf`; authenticated conversion of the archived V4 input `0b7c061b8e47560c665d3d22c7898ca53884c361002c2975bd5d925a433f94a8`, source51,584,590 B | 51,777,723 |
<!-- /register -->

The first input is retained under `/tmp/scarab/mithril27/download-bootstrap/snapshots`; its
chain/projection provenance is section27. The second is the maintained `preprod-v4` corpus
under `.workspace/data/2026-10-18-snapshot-v5-corpus`, not a newly rewritten original. Its bridge
source is `648aed117f826d06117f616b5c0a3716a50485a7`, conversion manifest SHA-256
`d81e326f85eb47d7a92d041a3309007599bcdaa8e70de0eaf4b38bb293ce0168`; the record pins slot,
block/hash,4,522,393-output handle/count, original/target hashes and successful verification.
Original private archives remain unchanged. The current repository requires maintained native
fixtures to use V5; no retired V4 reader is resurrected and no raw-V4 throughput is claimed.
Existing retired-version admission and independent toy-format migration tests remain in place.

These are complete **snapshot payload** operations, including the ledger state and UTxO handle,
statistics and pending changes. RocksDB entries/checkpoints, filesystem I/O and the file checksum
are not timed. Decode includes its ordinary structural checks; encode includes its normal codec
constraints. This is not a database restore or full-node throughput measurement.

### Protocol and full-snapshot results

Euler Linux x86-64/WSL2, Docker JDK21.0.11/Scala3.3.8; image
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`, network disabled,
`--cpuset-cpus 8-11 --memory 12g --user 1000:1000`, no CFS quota,
`-Xms3g -Xmx9g -XX:ActiveProcessorCount=4 -XX:+UseG1GC`, image `MALLOC_ARENA_MAX=2`.
One benchmark JVM at a time; old/typed/typed/old, a fresh JVM per input. Every case warms10 calls
then measures9 calls. `System.nanoTime` and current-thread `ThreadMXBean` allocation counters
surround only the pure codec call. Loading, checksum verification, full equality assertions,
rendering, SHA-256 and printing stay outside timing. Other independent sessions existed on the
shared host; the full ranges and both old runs are retained rather than treated as confidence bounds.

<!-- register:table perf-39.t2 -->
| Condition / input | Encode median [min–max], ms | Encode median allocation, B | Decode median [min–max], ms | Decode median allocation, B |
|---|---:|---:|---:|---:|
| 1: old, current | 176.225 [173.977–187.672] | 540,268,552 | 486.551 [463.377–630.440] | 1,242,286,792 |
| 1: old, legacy-derived | 131.287 [126.253–134.316] | 472,993,928 | 708.148 [559.052–907.384] | 1,617,251,344 |
| 2: typed, current | 129.776 [121.284–135.305] | 530,819,216 | 396.443 [388.347–529.078] | 1,125,802,416 |
| 2: typed, legacy-derived | 119.197 [112.789–137.980] | 451,846,448 | 589.722 [454.986–874.278] | 1,406,177,480 |
| 3: typed, current | 120.832 [117.066–126.050] | 530,819,216 | 402.118 [386.805–619.731] | 1,138,844,728 |
| 3: typed, legacy-derived | 109.831 [108.365–114.138] | 437,972,760 | 587.505 [447.893–726.938] | 1,406,102,872 |
| 4: old, current | 126.656 [124.361–133.406] | 525,166,496 | 466.313 [421.601–553.587] | 1,244,816,824 |
| 4: old, legacy-derived | 116.140 [113.236–160.153] | 472,993,928 | 621.214 [516.662–821.163] | 1,617,213,912 |
<!-- /register -->

Median-of-JVM-medians changes: current encode **−17.26%**, decode **−16.19%**;
legacy-derived encode **−7.44%**, decode **−11.44%**. Even comparing the slowest candidate run
median with the fastest old run median gives at most **+2.63%** on any primary operation.
Allocation medians change by −0.36%/−8.94% (current encode/decode) and −5.94%/−13.05%
(legacy-derived). Old encode samples show substantial allocation/JIT variation; no single
ClassTag/tuple/GC explanation is inferred from these combined changes.

Complete encoded bytes, complete typed decoded-state fingerprints, and the typed refusal of a
one-byte-truncated payload match in all four conditions for both inputs. Encoded SHA-256 values:
current `44c8e379fa15fbce0a17944bf2233c77fed551e8295aff4f5bf8f5a27b1258cf` (51,199,342 B),
legacy-derived `6a2a6081c1f6898b00e846d9afca551d4282699a9b5eff34933950d742d1b0e7`
(51,777,689 B). Full decoded-state SHA-256 values are respectively
`a3fd6ef08420f9aa5e5e658cd0ab20e9699ef6dc93eb0afb3795d622768657b7` and
`4caa61c9366251e1da56fbcde62e467887ac8f4824c812438b01c53ab11597fc`.
The complete failure digest is `8b8d7c5df5b02e8f1b3d313a192316ef8a61781b4732f14e9633769a5fe42987`.
Each timed result also passes an untimed byte/structural comparison with its expected result.

**Legacy rewrite detail.** The rewritten legacy-derived payload is two bytes shorter than its
input payload. Inspection with the unchanged old codec proves that both writers already remove
the obsolete `RewardAttribution`/`NumDelegatorsLowerBound` gap markers. The original and rewritten
Haskell sections are byte-identical, and sidecars are equal after only that existing gap metadata
normalization. This is an existing writer policy, not a codec-format change or a comparison mask.
The current Preview payload rewrites byte for byte. The benchmark compares complete old/candidate
outputs; it does not silently assert that every archival input rewrites unchanged.

### Small controls and investigation

Same pinned runtime,20,000 operations per sample,20 warmups/nine samples, same interleaved order.
These are supplementary controls, not substitutes for full snapshots.

<!-- register:table perf-39.t3 -->
| Case | old JVM medians, ms | typed JVM medians, ms | old → typed allocation per20k operations |
|---|---:|---:|---:|
| codec.encode | 95.979 / 97.291 | 95.813 / 96.704 | 1,338,080,000 / 1,337,120,000 → 1,337,120,000 / 1,337,120,000 B |
| codec.decode | 139.994 / 141.267 | 120.252 / 120.547 | 1,673,280,000 / 1,673,280,000 → 1,493,760,000 / 1,493,760,000 B |
| codec.record.encode | 0.684 / 0.687 | 0.820 / 0.794 | 9,120,000 / 9,120,000 → 8,640,000 / 8,640,000 B |
| codec.record.decode | 9.421 / 9.491 | 8.120 / 8.134 | 113,280,000 / 113,280,000 → 112,960,000 / 112,960,000 B |
<!-- /register -->

The 333-byte general codec encode control is −0.39%, decode −14.39%. For the real34-byte
`FutureGenDeleg` record, encode is **+17.72%**, about6.1 ns/call (34.3→40.4 ns), with5.26%
less allocation; decode is **−14.05%**. Byte/value fingerprints remain
`c19600a83b7ba784`/`62f843ecd191e6fe` and `9b5615ce2d835aac`/`e21908e63078f3cb`.
The remaining tiny encode cost is tracked by #257; the passing snapshot result does not erase it.

An earlier accessor prototype already passed full-snapshot parity but retained a slower tiny
record decode. It remains under `diagnostic-accessors/`. A typed final-two-fields decoder now
builds the pair directly, preserving error paths and avoiding that walk's intermediate one-element
tuple. The final protocol also increases warmup symmetrically; these measurements establish the
combined final implementation, not isolated causal percentages for either change. Compiler and
formatter failures during the prototype were fixed before measurement. A positive compile probe
caught that the first phantom-role negative probe omitted its `KeyRole` bound; both valid and invalid
snippets had failed for that reason. The corrected probe now has passing positive controls and
rejects only the mismatched generic/role projection. That test repair changes no timed code.

Historical rutherford figures in #250 used different source/harness/runtime conditions and are
superseded for #205 acceptance. In particular, the old explanation that hashing was removed from
decode timing was false for the then-pinned source; reported spikes do not identify their cause.
Record encode allocation10.24→10.88 MB was +6.25%, and decode17.905→22.366/20.439→32.222 ms was
+25…+58% on those paired medians, not “neutral”. Those historical figures are not mixed with
this corrected Euler comparison or attributed to one implementation mechanism.

### Gates and reproduction

At `ab85c6a7`, `python3 scripts/devnet.py --name scarab-247-root-restart restart` passes:
0.6/0.6 s graceful stops, two V5 shutdown snapshots, one successful restoration, zero errors.
JDK image above; reference image
`74e849e1b68e2cf8ba3cdfe3fcda67114f22d9bebc6a34372657cd179e704c4b`, node11.1.3/CLI11.2.3.0.
Restart manifest SHA-256 `b63d8b3d93276c5ab491c9bd98222fa56af97b05ec7c8367994923af493a52e3`;
artifacts outside Git in `/tmp/scarab-247-evidence`; own containers removed. Focused format,
migration, Haskell-format, snapshot and generic/phantom tests pass46/46. The corrected compile
controls/nested paths pass4/4. Extended `fuzz 100000 1 4` passes77 targets ×400,000 cases
(30.8 million). Final-head full check/CI is reported on PR #247.

The complete reproduction package is `/tmp/scarab-247-bench`: sources, exact per-run Docker
argument vectors, classpaths, both frozen runtime inventories, raw samples and full summaries.
`python3 /tmp/scarab-247-bench/run.py` compiles the same standalone helpers against both runtimes
and runs the fixed sequence. It mounts the input/config/cache read-only, calls
`xyz.kd5ujc.scarab.storage.SnapshotCodecBench <label> /input.bin /configs`, then runs the corrected
`BenchCodecMain 20000` control. Source/input/config/class identities are pinned in the binding.
Binding SHA-256 `28d04bcc9b0e36de744cc38a8e8d159a1d78cd060b81ca2b13374db03b9b63bc`;
summary SHA-256 `75a705cd2dafab49ab5a8c6bdd9a0a75db6292464b79b10fe313424b468bfdfe`.
No raw snapshot, key, run log or private archive is committed. The original V4 format remains
retired; format bytes/fingerprints/version5 and its existing admission/migration policy are unchanged.


**Integration after #260 (2026-10-20).** Rebased onto dev
`6988858f937a42eb34762cb5a61d3c622c49b124`. The codec/accessor/Record production sources and measurement helper are unchanged; the newly merged Diff diagnostics are outside the timed snapshot encode/decode operations.
The original benchmark/gate source identities above remain historical evidence. Refreshed
checks on the rebased head are recorded in the [PR #247 checklist](https://github.com/KD5UJC-XYZ/scarab/pull/247);
pre-rebase gates are not relabelled as gates of the integrated head. Main and dev still diverge;
this prepares the dev target and does not claim completion after main landing.
