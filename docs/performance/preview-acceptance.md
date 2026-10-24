<!-- SPDX-License-Identifier: Apache-2.0 -->
# Preview acceptance evidence and native likelihoods

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 33. Preview acceptance evidence reconciliation (#21)

**Historical evidence recovered, 2026-10-19.** The retained K1 Haskell projections were recovered
from the read-only archive, independently of the Scala outputs. All 1,518 transferred files
(163,493,903 bytes) matched their source SHA-256; no raw Haskell database was copied or restarted.
The reference index has 1,451 unique epochs, continuously 1 through 1,451, with SHA-256
`f2c74064442493decbadb77fbeb6e3f4d42eac1e6e64a3505b0ad5cbe232eabb`.

The current `oracle_compare.py diff --expected-epochs 1451` reproduces **1,451/1,451 matches**,
zero differences, missing points or duplicate epochs, against both the original K1 Scala dumps
and the frozen `a315286e` rerun. Both result files have SHA-256
`97c7e4b0271782a7ed50cb1d5d82b571e3ee2b3d619fcf5c11de8fe324d133f9`.
This authenticates the historical comparison; it does not execute current rules over those blocks.
The strict option matters: the ordinary progress-reporting command can legitimately have no
mismatches while still waiting for Scala points.

K1 compares the reference's projected fields, rather than complete Haskell-state bytes. The archived
projector omits `NonMyopic` and cost models, pool relays/metadata/reward-account network, and stake
snapshot fields other than stake, delegations and pool identities; pending reward updates project
as null. Comparison ignores the top-level era label and rounds floating-point fields to ten decimal
places. Maps above 64 keys are compared by their count, modular SHA-256 sum and 16 digest
buckets. Parameters absent from an era are absent from its projection. These are the existing
comparison boundaries, not new masks introduced for the current replay. The historical wrapper
names reference release 11.1.3, but no immutable historical Docker image ID was recovered.

The historical **242** phase-2 failures count invalid transactions. The original block bytes identify
242 unique transaction hashes in 230 blocks: 137 Babbage and 105 Conway transactions, all at or
before the uninterrupted run's final slot 125092775/block 4748301. Original transaction-body
BLAKE2b-256 hashes and the block/body/witness SHA-256 values were independently checked. The
private extraction has SHA-256
`81ad623b4c868ac8855ec0748cbacc0d50629caa3d287eb8e6c8300e27823620`.
An epoch log counter is discovery evidence rather than a transaction identity: the epoch 762/763
log deltas are 10/7, while the canonical invalid-transaction counts are 8/9; the total agrees.
Canonical block validity flags determine the fixture identities.

The section 3 figure of about 365 blocks/s retains its original build, hardware and live-sync
conditions. Neither the recovered comparison nor fixture extraction measures current throughput.
Current-rule Preview replay, its serial/parallel comparison and any timing result are recorded
separately from this recovered evidence.

**Continuous Preview replay baseline, 2026-10-19.** One uninterrupted offline run applied all
4,758,521 captured blocks from origin through slot **125,366,428**, header block number
4,758,520, hash `956541c0937e9cdc279514c10fec9706865bb6004ddcfb487bf53c7bbdc97ad1`.
It started from genesis with no ledger snapshot. The endpoint and original bytes, rather than a
moving testnet tip, define the range for a future comparison.

<!-- register:table perf-35.t1 -->
| Measured interval | Elapsed | Scope |
|---|---:|---|
| Immutable replay (`ReplayFinished`) | 22,325.190 s (6 h 12 m 05.190 s) | 213.15 blocks/s over the complete captured range |
| Store/cache ownership (`summary.elapsedNanos`) | 22,341.847 s | Also includes opening and resource finalizers |
| Docker/JVM process wall time | 22,343.919 s (6 h 12 m 23.919 s) | Launch through exit; excludes the post-run acceptance audit |
<!-- /register -->

The replay interval includes original-block reads/decoding, signature checks, full header and ledger
validation, phase-2 execution, UTxO updates, link/counter guards, all 1,451 full-UTxO reference
projection exports and in-loop persistence/snapshots. It starts after database/index opening and
genesis setup, and excludes the final graceful-close snapshot and post-run comparison/hashing.
This is a run with correctness instrumentation on a shared host, not an isolated validation-only
or live-network throughput benchmark.

The run used four script workers, Docker JDK 21, four CPU quota/active JVM processors, 12 GiB
container memory, `-Xms2g -Xmx10g -Xss1m`, networking disabled and a fresh private writable store.
The host was an Intel Core Ultra 9 285K with 24 logical CPUs, running Linux 6.6.87.2 under WSL2;
other sessions also built and tested. Host one-minute load was 3.37 at launch and 1.64 at exit;
these endpoint readings do not bound load during the run. Docker working-set samples are retained,
but no true peak RSS or isolated-host CPU measurement was taken. The JDK image was
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`.

Frozen tool `dc00416b` used production base `235cd12a`; the source/runtime binding has SHA-256
`7ac9f68d3c6fd4995fb3a524a22976194bf55af0e035af80ee1745393cdfa40a`.
The completed run exported all **1,451** authentic reference boundaries, all matching under the
projection contract above, with no missing or duplicate epochs. Its ordered invalid-transaction
list exactly matches the **242** pinned identities. Counters are 3,233,583 reached transactions,
11,251,372 evaluated scripts and 244 failed scripts, with zero evaluator-unavailable or collection
failures. **Six** runtime responsiveness warnings were retained; the run makes no zero-starvation
claim. An independent integration audit rehashed all 1,467 accepted artifacts. The accepted manifest
has SHA-256 `489094c44e9e97793aba1371c65eb3643bb18c7bb8d3416d7ce5391fb5b9dcde`;
the summary has SHA-256 `15a3a2f937198caa10cf8191bae74a445d4b597995b9f241f94b5a90855a8a76`.
Private evidence, exact command, resource observations and comparison conditions are retained under
`/tmp/scarab/issue-21`, including `k1-performance-record.json`. The owned container was removed.

**Comparison status.** No relative speed claim against another implementation follows from this
run. The historical Haskell Preview oracle used `--minimum-block-validation --in-mem`, three
parallel per-target jobs and different snapshot/seed replay spans. Its retained analyser/projection
times are useful provenance, but cannot be summed into a matched full-validation wall time.
[Dingo's published component benchmarks](https://github.com/blinklabs-io/dingo/blob/278bbde98187863e3b6a6fbf37f0398bfa2336ac/docs/benchmarks/benchmark_results.md)
dated 2026-06-12 use early Preview data (40,000+ blocks, slots 0–863,996) on a 128-core ARM host;
they do not cover this range or workload. No matched Preview timing for Amaru was located or retained
as of this record. A later comparison must pin the same genesis, original blocks and endpoint hash,
full header/ledger/phase-2 validation, invalid-transaction semantics, storage/cache policy and resource
limits. Run on the same host without competing jobs; report startup, replay, projection exports and
final checkpoint separately, and verify state/invalid-transaction equality before interpreting speed.

**Current-rule failure windows, 2026-10-19.** All 68 authenticated windows passed: 428,517
original blocks, each validated serially and with four workers, for 857,034 full validation
applications. Every block produced equal complete mirror states and ordered typed evaluator
requests/results, declared and consumed budgets, diagnostics and logs. All per-window invalid
transaction lists matched in order. The 319 observations include overlap between windows; their
union is exactly the 242 pinned transaction identities. Reference-path counters, including that
overlap, are 447,674 reached transactions, 1,201,042 evaluated scripts and 321 failed scripts, with
zero evaluator-unavailable or collection failures. The corresponding consumed units sum to
659,922,009,346 memory and 248,661,954,179,859 steps. These are correctness counters, not a
throughput measurement.

All 68 selected epoch-boundary projections matched the authentic Haskell reference with strict
coverage: zero missing, duplicate or mismatching epochs. This subset supplements the exact
serial/parallel comparison; it does not constitute a complete independent Haskell per-script
request/log/budget oracle. Historical per-script diagnostics were not retained.

The run used frozen source `a5a9bdf01ba3e96e5a4e96ce0747560ea8cbdf93` and the unchanged
16,345-file runtime classpath inventory named below. All 68 native V5 snapshots and checkpoint paths were
bound to the earlier conversion/copy manifests; the external checkpoint proof has SHA-256
`6454c12f54274472c4d38256552c13f758a09c2bc24e684e5023517956210ad2`.
That proof establishes continuity to the retained capture, independently of the Haskell boundary
comparisons. The accepted 503-artifact execution manifest has SHA-256
`d09232851f34225d57a95391729e9381c731764d065d9001f8b5b4a60da6024d`.
A separate integration audit rehashed every artifact, counted all validation rows, checked each
ordered invalid list and the exact golden union. All 68 scratch stores and the owned container
were removed after their evidence was retained.

**Current-rule timing, 2026-10-19.** The shared `RecordedReplay` runner validated the captured
epoch-135 window: 6,494 original blocks, 67,163 reached phase-2 transactions and 138,358 evaluated
scripts per pass. All 12 expected invalid transactions were reproduced, with zero unavailable or
collection failures. Complete mirror states and ordered typed evaluator requests, results, declared
and used budgets, diagnostics and logs agreed between serial validation and four workers.

Each block had a warm serial reference followed by three rotated serial/four-worker rounds. Only
the reference advanced the UTxO store. Timings include full validation, signature checks and store
reads and script-context construction; they exclude input loading, comparisons, trace serialization
and store flushing.

<!-- register:table perf-35.t2 -->
| Round | Summed serial validation | Four workers | Serial / four workers |
|---|---:|---:|---:|
| 1 | 77.518 s | 65.970 s | 1.1751× |
| 2 | 77.533 s | 65.796 s | 1.1784× |
| 3 | 77.615 s | 65.395 s | 1.1869× |
<!-- /register -->

The median ratio is **1.1784×**, about **15.1% less summed validation time**. This is a selected
Preview window on a shared host, not live follower throughput. The Docker JDK container had a
four-CPU quota, four active JVM processors, 16 GiB memory and a 10 GiB test heap, with networking
disabled. The host had 24 logical CPUs and 139 GiB RAM; its one-minute load ranged from 3.54 to
8.65 while other sessions built and tested. The image ID was
`sha256:c90140def52d2711644c85a81c9bf218ffbe5dc81003bfb866154c2da832af38`.

The runner source was `a5a9bdf01ba3e96e5a4e96ce0747560ea8cbdf93`; its 450 production Scala
files matched integration source `3c27c84cff6109f31a896060b78fd92d7f51501b` on base `235cd12a`.
The isolated-worktree build reported `BuildInfo.gitCommit=unknown`, so independent source and
class manifests bind this run instead. All 16,345 runtime classpath files (compiled classes, resources and jars) remained unchanged;
their normalized manifest SHA-256 was `9461cca620c595e21703527c9783c08e48854feed5afccc91f43e3d3192d89a7`.
The retained 21-artifact evidence manifest, independently rehashed after the run, has SHA-256
`395d0ff72707146701e688ea8e61a9871c3067a87e9996f12519ea6a7c6e4af7`.
The [shared-runner recipe](../design/parallel-plutus.md#preview-replay-using-the-same-runner) describes
the input export and environment controls.

The feature was subsequently rebased onto `ad5288a9`. That merge adds networking and topology
configuration, typed configuration cases, a DNS dependency and two immutable-index lookup methods.
The replay does not call those new methods; existing immutable open/recovery/stream, `ChainStorage`,
ledger, evaluator, reward, snapshot and projection paths are unchanged. The frozen replay manifests
retain their earlier source and class bindings; they are not claimed to be identical to every file
on the rebased branch.

A further rebase onto `edf8a887` retires V1–V4 snapshot payload readers. The authenticated
Preview and preprod inputs are native V5; the continuous K1 scratch starts from genesis without
snapshots. The valid V5 section decoder, native decode body, and encoder with its former default
`carried=None` are unchanged, as are the sidecar/ledger codecs and production immutable replay.
Retired/unknown snapshot admission behavior is outside these inputs and is not claimed unchanged.
Original immutable-index migration remains supported on private staging metadata. The retained
native-path audit has SHA-256
`ebc16aee86b54988f7391e763bf07f0e0605e57e8172fe8d1ab8c620aa216dde`.
Source `e3dbe2da` on that base passes a fresh Docker `check`: 5,153 tests, 17 optional-data skips, all 14
modules, 77 default fuzz targets and scalafix; all 241 Python tests pass. Frozen replay evidence
retains its actual earlier source/class identities rather than claiming execution on the new base.

The subsequent rebase onto `57608c6e` adds upstream Wycheproof tests and a Mithril bootstrap-stage
identity write; none of these replay entry points invokes Mithril bootstrap. All feature Scala and
Python sources remain byte-identical to checked source `e3dbe2da`, and the used core replay paths
are unchanged. The additional audit has SHA-256
`7504d11b69dc39157655309259dc8de2aa1bf7f6948d781d182846307d709ade`.
The accepted one-CPU v0.2 limitation in section 31 is retained.

The final integration rebase onto `50759002` includes #241's DELEG/POOL/GOVCERT/RATIFY rule
extractions and conversion of proposal lookup to a map. Source review found the inspected
production decisions/accounting equivalent for valid states with unique proposal IDs. Pool
certificate decoding now represents canonical empty relays (`80`) as `Bytes.empty`; this is a
representation change, not a claim that all executed code is unchanged. The focused
`UpstreamPoolConformanceSuite` regression checks normalization, native pool-parameter encoding
and retention of the original noncanonical transaction body/witness bytes. Network codec/DNS
changes are outside the offline replay path. The private audit is
`rebase-final-main-audit.json`, SHA-256
`9e4a65ac85ba8f7efa193cde1dff490fefc362c937f6b9eeddbac67d9079bad3`.

Fresh Docker `check` on `59d3b8c2`, based on `50759002`, passes 5,273 tests with 17 optional-data
skips across all 14 modules, formatting, warning checks, default fuzz and scalafix. All 250 Python
tests pass. The added relay regression then passes with its two existing native POOL adapter
controls (three tests), formatting and scalafix; production source is unchanged by that test-only
addition. The current area scenario and final published-head CI are reported in
[PR #209](https://github.com/KD5UJC-XYZ/scarab/pull/209). These integration checks do not relabel
any frozen full-data run as execution on the final main revision.
## 36. Native non-myopic likelihood generation (#268)

On 2026-10-20, `feature/268-reward-likelihood` runtime `e96cd373` computes the
reference's 100 Float samples for every snapshot pool at reward start. A retained
native V5 Preview epoch1449 snapshot supplies 727 pools, 79,851 active credentials
and 71 producing pools. No network following or operational database change occurs.
The [fixture provenance](../../storage/src/test/resources/reward-likelihood-1.19.0.1/README.md)
binds the original converted V5 source, full private API inputs/results and reduced
public pool/sample corpus. The released Haskell reward API produces identical
complete start/completion CBOR, including all 72,700 sample bits.

Docker JDK21, two CPUs/four GiB on the shared host; ten warmups and 25 measured
repetitions per condition, alternating order. The baseline is the unchanged-main
reward implementation over the same decoded state. This measures the entire
`Rewards.start` API, not a whole block, tick, throughput or hard latency bound.

<!-- register:table perf-41.t1 -->
| Condition | Median wall | Maximum wall | Median CPU | Median allocated bytes |
|---|---:|---:|---:|---:|
| Previous start, no new likelihoods | 1.980 ms | 2.502 ms | 1.980 ms | 5,165,256 |
| Native likelihood generation included | 3.823 ms | 38.083 ms | 3.813 ms | 8,208,128 |
<!-- /register -->

The larger wall-time tail remains explicit; these observations do not establish its
cause or a worst-case bound. The code stays pure. Existing history decay and
normalization are also compared with a second explicit native API observation;
that experiment is not an assertion that the old snapshot contained historical
likelihoods. The snapshot's missing history is retained, not invented.

Private profile summary SHA-256:
`a4f061e5227835cdcd4b11842ffabe5246bb25f9ae68de8783ec43fe2dfc5c0e`;
probe source `b93ed1804e9432c0e79b398f2409f14237fb27e344f11607b5677e422489d49d`;
baseline source `b2d7bf0edf4898bc8ab0deeb71fbb25648c7e8963c2d321f7ec9cf6a1f53b3c1`.
Full states and run logs remain outside Git under `/tmp/scarab/268-likelihood`.
