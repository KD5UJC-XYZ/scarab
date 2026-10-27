<!-- SPDX-License-Identifier: Apache-2.0 -->
# The mainnet follower and signature checks

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 13. Live sync against replay on mainnet (#124)

**The question.** A mainnet follower in Mary and Alonzo applied about 58,000 blocks an hour while a replay of stored blocks
on the same machine had run at about 240,000. Where does the time go, and how much of the gap is the network?

**Short answer.** None of it was the network. Block fetch, header checks and the verification cache had headroom
(section 7 and the numbers below). The apply thread, which is one thread, spent its time in two places that replay does
not meet: writing the UTxO store after every block that became immutable (about 32% of the samples) and removing the
pointers of a deregistered stake key by scanning all of them (about 24%). Both are fixed; with a third change (Ed25519
witness verification) the same 12,000 blocks apply at 255,000 an hour instead of 70,500 on 4 CPUs, 3.6 times. The
240,000 of the replay was also a comparison of unlike blocks: the same log shows replay at 113,000 to 145,000 an hour
from block 6.10 M (Mary, the blocks and the pointer map are larger), against live sync at 40,000 to 90,000 over
blocks 6.20 M to 6.28 M.

### What was measured, and how

- **The running node (read only).** A multi-core host, a mainnet follower (`--rules old`, one peer, `-Xmx24g`, G1), blocks
  6,203,514 to 6,277,016 (Mary into Alonzo, epochs 289 to 291). From its log (73,503 `extended` events in 4,405 s):
  median 33 ms between blocks, 90th percentile 172 ms, 99th 372 ms; the 15% of the blocks that took more than 100 ms
  between them account for 55% of the time; 316 gaps over 0.5 s (6% of the time: epoch boundaries, snapshots).
  `chainSyncRounds`: 128 headers per round in 307 to 310 ms on average (737 ms at most); one `slowFetch` in 1.2 hours;
  `headersChecked`: 50,049 validated, 0 unchecked, 0 waited; verification cache over the live phase: witnesses
  4,905,775 of 4,906,968 already verified (99.98%), operational certificates 100%, VRF 99.8%; `cpuStarvation`: two
  warnings (135 and 174 ms) in the live phase. Six thread dumps in 24 s: in each, the 24 workers of the follower pool
  and all the fetch tasks were parked and **one** thread was running, in `UtxoStore.advance` (four dumps), in
  `PreConwayCerts` (one) and in the rules (one).
- **JFR, 10 minutes** (`settings=profile`, 65,047 execution samples on the follower's pools): G1 pauses 9.3 s of 600 s
  (1.6%, 280 ms at most); the JVM used 3.7 cores on average (user) and 0.3 (system) of 24.
- **A controlled A/B.** My own container, 4 CPUs, 8 GB, `-Xmx6g`, one fresh copy of the mainnet database truncated at the
  snapshot of block 6,242,300 (slot 40,045,057, epoch 290), following the public backbone relay from block 6,243,505
  for 12 minutes; the same 12,000 blocks in each run (the blocks of epochs 290 to 291). The relay and the time of day are
  not controlled, so a figure is good to about 10%.

### Where the apply thread's time went (JFR of the mainnet follower)

<!-- register:table perf-14.t1 -->
| samples (65,047) | share | what |
|---|---|---|
| `UtxoStore.advance`: three walks of the whole diff of the immutable state for every block that became immutable (`exists`, `filter`, `max`) | 32% | live only: in replay each state is immutable at once and its diff is small. The diff of the anchor holds the changes of the last k = 2,160 blocks (about 100,000 entries) |
| `PreConwayCerts` `Unregister`: `ptrs.filterNot(_._2 == c)`, `Credential.equals` over every pointer | 24% | once per stake key deregistration, over about a million entries (200 ms or so); the blocks that took more than 100 ms are 15% of all and are the ones that carry such a certificate (not checked block by block) |
| signature verification (witnesses, VRF, operational certificates) in `VerificationCache.warm` | 27% | on the parallel threads, off the apply thread; this is what the first two were hiding |
| the rules, decoding, chain selection, RocksDB reads | about 15% | |
<!-- /register -->

Allocation (JFR `ObjectAllocationSample`): 18% in iterating the persistent maps (`BitmapIndexedMapNode.getPayload`), 15% in
Ed25519 lookup tables, 5.5% `UInt64.saturating`, 3.6% `BlockRef.point` (per-header chain selection).

What was ruled out: block fetch (the producer keeps three batches of 128 headers in flight; a 100-block range is 1 round
trip, and 70,000 blocks an hour is 20 blocks a second), header-first checks (the verdicts are kept; the cache hit rates
above), GC (1.6%), the timer pool (two starvation warnings), logging and the volatile log (no frame of either in the profile).

### Results (same 12,000 blocks, 4 CPUs, one peer)

<!-- register:table perf-14.t2 -->
| build | blocks per hour | speed-up | change |
|---|---|---|---|
| main (1e9b2847) | 70,500 | 1.0 | |
| + the store follows the immutable tip every 128 blocks, one walk (551b7f1b) | 117,100 | 1.66 | `ChainStorage`, `Utxo.changesSince` |
| + `PtrMap`, the reverse index of the pointers (00d257da) | 168,600 | 2.39 | persisted as the same map: the snapshot fingerprint is unchanged |
| + one interleaved double-scalar multiplication per Ed25519 witness, and the shared `BlockPool` (single peer) | 255,200 | 3.62 | 230 to 118 us per verification (200 keys, warmed, one core) |
<!-- /register -->

The whole runs (12 to 14 minutes each, 12,700 to 55,000 blocks): 69,500, 109,100 (533 s), 171,600, 236,000 an hour. The
median gap between two blocks went from 33 ms to 7 ms and the 90th percentile from 98 ms to 28 ms. A second JFR (5 minutes,
37,873 samples) after the three changes: 53% of the samples are signature verification on the parallel threads
(the leaf frames are curve arithmetic: field multiplication and squaring, point-table lookups), 36% the apply thread, which now has no single
hot spot above 4%. The bottleneck is CPU for signatures: with the 4 CPUs of the test the verification competes with the
apply thread, and the mainnet follower has cores to spare.

### Several peers, and the reference

`Follower` runs one session per peer, each with its own ChainSync pipeline and a candidate chain; chain selection decides
which candidate to adopt. Before this change each session's producer also fetched every block its peer announced, whether
or not its candidate was preferred: with N peers N times the download and N times the verification, and for a session
whose candidate is never preferred a map of fetched blocks that nothing takes (a leak). A `BlockPool` shared by the
sessions now holds claims: a session claims the blocks of a range when its connection is free to send the request, so
each block is fetched from one peer, the ranges of a batch go to whichever connection is idle, and the session that
applies a block takes it from the pool whichever peer delivered it. A claim lives only as long as the session that
made it (a per-session resource, `BlockPool.session`): when the session ends, by a failure or a cancellation, what it
still holds is released and a session waiting for it fetches the block itself (#130; the first version released a
claim on an error but not on a cancellation, and a peer's timeout left the other peer waiting for ever).

The reference (ouroboros-network, `Ouroboros.Network.BlockFetch.Decision`): `fetchDecisions` filters the candidates
(`filterPlausibleCandidates`, `selectForkSuffixes`, `filterNotAlreadyFetched`, `filterNotAlreadyInFlightWithPeer`),
prioritises the peers (`prioritisePeerChains`: in `FetchModeBulkSync` the preferred chain first, then the expected
response duration from the peer's measured delay and bandwidth, `comparePeerGSV'`; in `FetchModeDeadline` the probability
band first), removes what other peers have in flight (`filterNotAlreadyInFlightWithOtherPeers`; in bulk sync only: in
deadline mode the same block may be asked of several peers) and decides per peer (`fetchRequestDecisions`), declining with
`FetchDeclineConcurrencyLimit` past `maxConcurrencyBulkSync` peers, and past `maxInFlightReqsPerPeer` requests or the
in-flight byte watermarks (`inFlightBytesLowWatermark`, `inFlightBytesHighWatermark`, from `PeerFetchInFlightLimits`).
`BlockFetchConfiguration` (`bfcMaxConcurrencyBulkSync`, `bfcMaxConcurrencyDeadline`, `bfcMaxRequestsInflight`) feeds
`FetchDecisionPolicy`; cardano-node exposes `MaxConcurrencyBulkSync` and `MaxConcurrencyDeadline` in its configuration
file and takes the library's `defaultBlockFetchConfiguration` when they are absent; `bfcMaxRequestsInflight` is clamped to
`blockFetchPipeliningMax` of the mini-protocol parameters. I could not retrieve the numeric defaults of
`defaultBlockFetchConfiguration` here (the names and the structure above are from the sources; the values are not
asserted). So the reference also fetches in bulk sync from a bounded number of peers at once, with a per-peer window of
requests and bytes in flight; ours takes a range per idle connection, 100 blocks or 8 MB per request and one request in
flight per connection (the BlockFetch client of a connection is sequential).

Measured with two peers (the IOG and Cardano Foundation backbone relays; the Emurgo name did not resolve from the test
host), same 12,000 blocks: 174,400 an hour with the pool and without the Ed25519 change (against 168,600 with one peer
and no pool), 263,800 with all three (against 255,200). That is no gain and no loss: at 4 CPUs the follower is bound by
signature verification and the apply thread, not by the one connection. No block is fetched twice: the bytes the
container received per block applied over the same two minutes of the run (docker stats) were 22.4 KB with one peer and
22.0 KB with two (a block of this stretch is 15 to 22 KB, headers included).

**Check of the claims.** A debug build that printed, for every range a session looks at, how many of its blocks the session
owned (two peers, 6 minutes, 14,337 headers' worth of ranges): each peer looked at all of them and owned 7,700 and 6,637,
which add up to 14,337: every block was claimed once, and the ranges were shared 54% to 46% between the two relays.

### Two of those fixed: the chain selection window (#126) and the snapshot policy (#127)

**The window (#126).** `Follower.local` built the 2,160-block window (`ChainSelection.Local`: the anchor, its view, and a
point and select view per block) for every header that chain selection looked at. It is now kept: `commit` rolls it back to
the switch point and appends each applied block (`Local.rolledBackTo`, `Local.extended`, an append and at most one drop from
the front), and `local` rebuilds it only when none is kept or its anchor, length or tip is not the chain's (a restored
chain). `LocalWindowSuite` checks the kept window against the rebuilt one (`Local.of`, the old code) after every step of
random extensions and rollbacks of at most `k` (k from 0 to 12, forks from different salts), and once at `k = 2160` over
5,000 blocks and a rollback of `k`. Cost per header (JIT-warm microbenchmark, `k = 2160`, one core): 45,000 ns to rebuild,
120 ns to extend. Against the 1 to 3% of the apply thread's samples (twice that with two peers) that is the whole of it; the
live gain was not re-measured on mainnet (no mainnet follower was started for this change).

**The snapshot policy (#127).** The issue and the notes above assumed the reference node snapshots every `2k` seconds and
after a bulk-sync threshold of 50,000 blocks; that is the older `DiskPolicy` (`onDiskShouldTakeSnapshot`). The version
cardano-node 11.1.3 pins, ouroboros-consensus 4.2.0.0, replaced it (`Ouroboros/Consensus/Storage/LedgerDB/Snapshots.hs`):
`defaultSnapshotPolicyArgs = mithrilSnapshotPolicyArgs` (lines 652-653, 664-675) with `sfaInterval = DefaultSnapshotInterval`,
`sfaOffset = 0`, `sfaRateLimit = 10 * 60` s, `sfaDelaySnapshotRange = 5 to 10 min` and `spaNum = NumOfDiskSnapshots 2`;
`resolveSnapshotInterval` makes the default `40 * k` slots (lines 631-642, 86,400 on mainnet: a day); `defaultSnapshotPolicy`
(lines 689-754) snapshots the immutable slot before every multiple of the interval that the immutable tip passes, and
returns nothing when the previous request is less than the rate limit old (lines 703-706, 750-753: "useful while
bulk-syncing"); cardano-node reads `SnapshotInterval`, `NumOfDiskSnapshots`, `SlotOffset`, `RateLimit` and the delay range from
the configuration (`cardano-node/src/Cardano/Node/Configuration/POM.hs`, lines 489-542). There is no block-count threshold.
The node now takes that as its default (`SnapshotPolicy.Aligned(40 k, 10 min)`, `SnapshotPolicy.reference`), with 2 snapshots
kept. A skipped crossing is not postponed, as in the reference; the snapshot is of the immutable tip that crossed the
boundary, not of the block before it (we do not keep the earlier state), and is written at once, without the reference's random
delay of 5 to 10 minutes (which spreads the work of a node that is at the tip; ours is in the background already).
`--snapshot-every` (`SnapshotPolicy.EverySlots`, the distance from the previous snapshot as before) and `--keep-snapshots`
override. The decision is the pure `SnapshotPolicy.due`, tested in `SnapshotPolicySuite` (the boundary, the rate limit, a
simulated bulk sync that takes at most one snapshot per rate limit, the override). The graceful stop still writes a snapshot.

Measured on the one fixture at hand, Preprod from genesis to slot 8,596,914 (chunks 0 to 399, 173,304 blocks, 2 CPUs,
2 GB heap, two runs each): the old default (`--snapshot-every 1epochs`) wrote 19 snapshots and a shutdown snapshot, the new
default 1 and the shutdown snapshot (the first crossing; the next ones came inside 10 minutes of it). Wall time was 107.7 and
107.9 s against 108.2 and 106.2 s, no difference, because a Preprod state of that age is 5 to 14 KB and a snapshot takes
9 to 25 ms. The saving is the mainnet one and is not measured here: the section 13 log has a snapshot of about 50 s of
background CPU every few minutes of replay (12 to 20% of its wall time); at most one per 10 minutes bounds that at 50 s of
600 s, 8%, and near the tip one a day replaces one per epoch (five days on mainnet), so the replay after a crash is
at most a day of blocks, not up to five. The epoch-boundary step is not touched.

### What is left, ranked by what it should gain

1. **Signature verification is now the largest cost (53% of the samples at 4 CPUs).** In order: remember the decoded and
   checked public key of a witness (the same keys sign again and again; decompress, compress and the small-order check of
   `A` are about a sixth of a verification, about 17 of 118 us); a native or batched Ed25519 verifier with the same strict
   profile (the pure Java curve arithmetic is `FieldElement.multiply` and `square`: 40% of the samples); the VRF
   (`Ed25519Point`, 8%) the same way. Expected: 1.2 to 1.5 times at 4 CPUs, little on 24.
2. **The epoch boundary** is one step of 8 to 9 s (`longestStep: epochBoundary` in `epochValidated`) every 21,600 blocks:
   2% of the time at 170,000 blocks an hour, more as the rest gets faster.
3. **A snapshot every epoch** (about 50 s and 340 MB, in the background): done, see the subsection above (#127).
4. **Chain selection rebuilds `Local` for every header**: done, see the subsection above (#126).
5. **Replay** meets the same two hot spots (the pointer scan; the store write is per block in replay too, so it gains from
   the 128-block step); its snapshot every few minutes is now at most one per 10 minutes (#127).
6. **Bytes**: a block of this stretch is 15 to 22 KB; at 255,000 blocks an hour that is 1 to 1.6 MB/s, so one relay is not
   the limit until the node is several times faster.

### Flags for the fastest follow

<!-- register:listing perf-14.c1 -->
```sh
follow --genesis-dir <mainnet> --db <dir> \
  --peer backbone.cardano.iog.io:3001 --peer backbone.mainnet.cardanofoundation.org:3001 \
  --metrics-listen 0.0.0.0:12798          # defaults kept: --read-ahead 128 --header-first on --cpu-threads <CPUs>
```
<!-- /register -->

`--header-first on` (the default) costs nothing and keeps the verdicts for the block; `--read-ahead` 64 to 256 made no
difference in section 7 and none here (the producer is not waiting for headers); `--cpu-threads` sets the workers of the
pool the single apply thread runs on, not the parallelism of the signature checks (that is the number of CPUs), so more of
it does not help. A second relay did not change the rate at 4 CPUs (174,400 against 168,600 and 263,800 against 255,200 an
hour): it is for resilience and for the follower with more cores than the one test; whether it helps on the multi-core
mainnet follower is the A/B to run. The JVM flags of the running follower (`-Xms8g -Xmx24g`, G1, 50 ms) are not a limit
(1.6% of the time in pauses).

### To repeat

Truncate a copy of a mainnet database at a snapshot (the chunks up to the snapshot's chunk, the snapshot, its UTxO
checkpoint, `network-identity`), follow the public relay for 12 minutes with `--log-file`, and read the `extended` events:
the first 12,000 blocks after `started` are the comparison. A bundle is the classes of each module beside the jars of the
last one. JFR from inside the container: `jcmd 1 JFR.start settings=profile duration=5m filename=/data/p.jfr`, then
`jfr print --stack-depth 64 --events jdk.ExecutionSample`; the JFR stack depth of 64 truncates the deep stacks of the rules, so
the cause is the leaf frames and the thread dumps (`jcmd 1 Thread.print`), not the root.

## 14. Parallel signature checks within a batch (#125)

**The question.** In late Alonzo (epochs 319 to 323) the mainnet follower applied about 21 blocks/s using only 2.2 cores.
Thread dumps (39 samples): exactly one `scarab-cpu-*` thread busy, in `VerificationCache.warm` -> `warmBlock` ->
`Verdicts.Direct.witness` -> `StrictEd25519.verify`.

**Cause.** `warm` was `blocks.parTraverseN(parallelism)(warmBlock)`: parallel across the blocks of a batch only. The
range-prefetch caller in `Peer` warmed each fetched range as soon as it arrived and before the block was taken. At the time
of this measurement a range was the unit it had, and since #124 the ranges were small (a block of this stretch is 60 to 80 KB with
50 or more transactions; ranges are claimed per session from the shared `BlockPool`). With one block in a batch, all of its
witnesses, its operational certificate and its VRF proof ran serially. Handing `warm` a larger batch was not an option
(it would wait for the next fetch before verifying what it has); the work has to be split below the block.

**Change.** `jobsOf` decodes a block and splits its checks into independent jobs: the header's operational certificate and
KES signature, the VRF proof(s), and one job per distinct vkey/bootstrap witness. `warm` flattens the jobs of the whole
batch and runs them on the blocking pool in chunks (`chunked`: at least 8 jobs, about 1 ms of Ed25519, and at most four
chunks per thread, so a slow chunk does not idle the rest). A block with fewer than 8 checks is one task and pays no
scheduling. The block is offered to the rules (`prepared`) by the last of its jobs, as before once its verdicts are in.
The caches, their keys, their verdicts and limits are unchanged. This is signature verification filling a cache; the
application of transactions within a block stays sequential and is not touched (owner rule). No recursion: the work is
a fold over vectors, so there is no stack question (ADR 0011).

**Measured** (`WarmBenchMain`, 400 consecutive mainnet blocks from slot 52,876,855, epoch 320, 67 KB each on average,
captured with `CaptureMain` from a public relay; witnesses and operational certificates, not VRF; fresh cache, one warm-up
pass; a multi-core desktop shared with other containers, so a figure is good to about 10 to 15%). Blocks per second, and cores
used (process CPU / wall), `docker run --cpus N`:

<!-- register:table perf-15.t1 -->
| CPUs | batch (blocks) x ranges in flight | before | after |
|---|---|---|---|
| 4 | 1 x 1 | 62 (1.2 cores) | 139 (3.4) |
| 4 | 1 x 3 | 150 (3.4) | 156 (4.0) |
| 4 | 4 x 1 | 132 (2.7) | 183 (4.0) |
| 4 | 16 x 1 | 169 (3.8) | 177 (4.0) |
| 4 | 100 x 1 | 170 (3.9) | 180 (4.0) |
| 12 | 1 x 1 | 59 (1.3) | 182 (6.3) |
| 12 | 1 x 3 | 143 (3.8) | 269 (11.0) |
| 12 | 4 x 1 | 106 (2.8) | 266 (9.5) |
| 12 | 16 x 1 | 254 (8.0) | 301 (11.4) |
| 12 | 100 x 1 | 302 (11.0) | 311 (11.9) |
<!-- /register -->

A first version without the limit below was 20 to 25% slower than the old code at 4 CPUs with three ranges in flight (the
follower warms every fetched range concurrently; each `warm` started its own four chunks, 12 runnable threads on a 4 CPU
quota). All the `warm` calls of a cache now share one semaphore of `parallelism` permits taken per chunk, which removed that
(150 -> 156). At 4 CPUs the verification is CPU-bound anyway (the apply thread competes with it), so the gain there is
the one block per batch case (2.2x); on 12 CPUs it is 3.1x for one block per batch, with 6 to 11 cores in use instead of 1.3
to 3.8, and equal when batches are already large (100 blocks), where the old code had enough blocks to fill the cores.
Pinning the CPUs (`--cpuset-cpus`) instead of a quota gave the same picture. This tool measures `warm` alone; the end-to-end
blocks/s of a late-Alonzo follower needs a mainnet follower at that stage and is the check to run when this lands.

**Repeat.** `CaptureMain capture --peer backbone.cardano.iog.io:3001 --magic 764824073 --blocks 600 --byron-epoch-slots 21600
--from <slot>.<hash> --out blocks.hex`, then run `xyz.kd5ujc.scarab.node.tools.WarmBenchMain blocks.hex <batch> <blocks>
<in flight>` from the test classpath.

## 19. The mainnet follower at 8.7 blocks a second: signatures, a slow window, and the weight of the chain (#147)

**The observation.** On 2026-10-17 the mainnet follower (late Alonzo, epochs 334 and 335, blocks 7.16 M to 7.17 M, build 4e0fce00,
before #131 and #133) applied about 8.7 blocks/s, against 24 to 27 in epochs 326 to 328, with about 9.5 cores busy instead of
about 1.1, and the thread samples put the busy threads in Ed25519 verification (`VerificationCache.warm`, `StrictEd25519`). At
118 us a verification that is 80,000 a second, or 9,000 a block: so the suspicion was repeated work.

**Short answer.** There is none. Each witness is verified once, about 30 to 80 a block, 500 to 1,000 a second. The follower was
slow for 28 minutes because its sequential apply thread took 114 ms a block instead of 34, from a restart on, on blocks no heavier
than the ones it applies at 20 to 25 blocks/s an hour later; why is not known (below). The chain and the state grown since epoch 326
explain a fall from about 24 to about 20, not to 8.7. Signature verification is a quarter of the samples, runs off the apply thread, and had 9% to give,
which this section takes.

### What was measured

- **The log** (`sync.jsonl`, 194,000 `extended` events, read only). Blocks/s by epoch: 26.9 (326), 22.2, 22.4, 20.9, 20.8, 21.8, 18.7,
  17.7 (333), 13.5 (334), 13.0 (335). In steps of 5,000 blocks the node ran at 16 to 23 until a restart (graceful stop at 20:15 UTC,
  container started at 20:25, snapshot restored at 20:34); then 6.6 to 16 in steps of 1,000 blocks from 20:39 to 21:01 (blocks
  7,160,000 to 7,172,400), and 16, 23, 17, 16, 19, 26, 20 from 21:02 on. The resume of the 2,160 volatile blocks, which warms them
  all and applies them with no network, took 75.6 s at the first start (epoch 326: 28.6 blocks/s) and 266.6 s at the second (blocks
  7,157,879 on: 8.1 blocks/s).
- **The apply thread** (`scarab_block_apply_seconds`): 2,259 s for 41,263 blocks since the restart (55 ms on average); 44.5 s for the
  1,306 blocks of the next minute (34 ms; section 17 had 31 ms in epoch 327). The median gap between two blocks in the log was
  114 ms at 20:56 to 20:58 (no pauses for batches: the thread was slow, not waiting) and 42 ms at 21:06 to 21:08. At 21 blocks/s and
  34 ms the thread is busy 72% of the time: it is the bottleneck, as in section 17.
- **JFR, 4 minutes from 21:03**, after the slow window had ended (`jdk.ExecutionSample`, `jdk.ObjectAllocationSample`, `jdk.CPULoad`).
  The JVM used 2.6 cores on average in one-second steps, not 9.5: 0.8 most of the time and 5 to 16 for one to three seconds every ten
  (one burst for each fetched range of 128 blocks). A 30 s reading of `/proc` per thread gave 3.0 cores (apply pool 1.0, blocking
  pool 1.1, G1 0.65). The 9.5 cores were a burst, or the slow window.

### Verifications a block, and whether any is repeated

400 consecutive blocks from each point, `CaptureMain` from the public backbone relay; the rate is what the log shows for those
blocks. (The epoch-320 capture of section 14 is not kept; 326, the first of the log, stands for it.)

<!-- register:table perf-20.t1 -->
| blocks from | rate then | witnesses / block | redeemers / block | ex-unit steps / block | bytes / block | txs / block |
|---|---|---:|---:|---:|---:|---:|
| 6,988,434 (326) | 25 | 58.7 | 8.3 | 15.2 G | 70,124 | 27.0 |
| 7,020,034 (327) | 21 to 23 | 43.9 | 10.9 | 15.4 G | 67,348 | 25.0 |
| 7,060,034 (329) | 21 to 23 | 40.1 | 10.0 | 15.7 G | 59,415 | 23.0 |
| 7,100,034 (331) | 25 to 27 | 28.4 | 6.2 | 8.5 G | 44,639 | 16.3 |
| 7,140,034 (333) | 17 | 49.9 | 11.0 | 12.6 G | 77,410 | 33.0 |
| 7,157,879 (the resume) | **8.1**, no network | 28.2 | 7.2 | 8.5 G | 51,362 | 18.0 |
| 7,163,435 | **6.9** | 42.4 | 10.0 | 11.5 G | 69,808 | 26.2 |
| 7,168,894 (335 begins) | **6.6 to 7.0** | 80.6 | 9.8 | 11.3 G | 84,496 | 33.8 |
| 7,170,435 | **8.8** | 55.6 | 7.8 | 8.3 G | 64,375 | 27.1 |
| 7,172,435 | 11 to 16 | 52.0 | 11.0 | 13.8 G | 84,729 | 32.7 |
| 7,174,435 | 23 | 30.9 | 6.9 | 7.0 G | 49,716 | 17.8 |
| 7,178,435 | 20 to 26 | 29.3 | 6.3 | 6.6 G | 48,144 | 19.5 |
<!-- /register -->

Outside the slow window the rate follows the content of the blocks (17 for the heaviest, 25 to 27 for the lightest), and a
block in the window is 3 times slower than a block of the same content outside it: 28 witnesses at 8.1 against 26
(7,100,034) and 23 (7,174,435); 42 at 6.9 against 22 (7,060,034); 56 at 8.8 against 17 to 22. The stretches of epochs 334 and 335 are
about 10% heavier in witnesses and 5% in bytes than those of 326 to 333 on average (48 against 44 witnesses a block, 67 against 64
KB), and the apply time of a block is 31 ms in epoch 327 (section 17) and 34 ms after the window: a rate of 19 to 20 where the
first was 24, a factor of 1.2 (content varies the rate by 1.5 either way inside one epoch, so this is good to about 20%).

Repeated work, one suspect at a time:

- **The same witness verified twice** (cache keyed `(vkey, signature, txid)`, cleared at 100,000 entries): no. The log's cache
  summary has 7,787,192 of 7,787,292 lookups already verified (99.9987%) after 160,000 blocks, and 879,259 of 879,270 after the
  restart; 49 witnesses a block on average, 44 since. A second copy of a range would leave verdicts that the rules never take, the
  map would reach its limit and be cleared, and misses would follow: there are none. Section 13 had shown each block claimed once.
- **Counted in the JFR window.** The allocation samples under `Verdicts.Direct.witness` weigh 68.4 GB in 240 s; a verification
  allocates 521 KB (`WarmBenchMain`), so 547 verifications a second. The log has 4,000 blocks in the 220 s of that window (18 a
  second) of about 30 witnesses: 545 a second. The same number.
- **Verified again inline by the rules:** no, the same hit rate.
- **Decoded again:** the block decoded for the checks is handed to the rules (`takePrepared`); decoding is 2% of the samples.
- **The header side**: the operational certificate with the KES signature, and the two TPraos VRF proofs, are `computeIfAbsent` in a map,
  once, whoever asks first (`warmHeaders` or the block's own jobs); 5% of the samples. VRF verdicts found by the rules: 89%
  (`vrf=35680/40000` after 20,000 blocks); the rest are computed by the rules because a proof is kept for the epoch nonce it was made under only.

Serial cost on a core of the test box (`WarmBenchMain`): 120 to 150 us a witness, 340 us for the operational certificate and KES
signature, 900 us for the two VRF proofs. At 30 witnesses a block that is 4 ms and 1.3 ms, about 0.15 core at 21 blocks/s. The
samples put signature work at a quarter of what Java threads run (witnesses 25%, headers 5%; the Plutus machine 27%, the rules in
the apply thread 13%, chain selection 8%, storage 2%) and imply 0.65 core: the checks on the parallel threads cost about four times
their serial price (a verification allocates 521 KB and does not scale with the cores), which is also why a burst of 128 blocks
takes 5 to 16 cores for a second or two.

### What the slow window was

Not the signatures, not the content of the blocks, not the network (the resume had none), and not the two wastes of this build that
section 17 found (chain selection, 8% of the samples here, and a thread started for each script, 146 a second; both are fixed on
main by #133). The apply thread took 114 ms a block for 28 minutes after a restart, including the replay of 2,160 blocks of 28
witnesses with no peer connected, and recovered by itself at 21:02 with the host idle (the machine used 3.0 cores while the JVM used
2.6). The data cannot tell between:

1. a **cold UTxO store**: after a restart the resident set is empty and each block reads its inputs from RocksDB (256 MB block cache
   over 7 M entries; the thread samples of the slow window had `multiGetAsList`); the container had been stopped for 19 minutes;
2. **JIT compilation** of the Scalus machine and the curve loops in the first half hour of a JVM with 12 compiler threads (the code
   cache is not the limit: 23 MB used of 240 MB);
3. **another load on the host** from 20:34 to 21:02 (`cpuStarvation` warnings are in the log throughout the run, so they do not
   discriminate).

The first start (17:56) had none of it: 25 blocks/s at once. The check at the next restart is free: `jdk.CPULoad` (`machineTotal`
against `jvmUser`), the CPU of each thread group from `/proc` and a thread dump at minutes 5, 15 and 25. Not reproduced; nothing in
the code is changed for it.

### What changed

- **`StrictEd25519`**: the test that an encoding is canonical no longer compresses the decompressed point (a field inversion each, for
  the key and for R); it is `y < p` on the bytes. They are the same test: `decompress` reads `y` modulo `p`, so only the 19
  non-canonical `y` differ, and it takes the `x` whose sign is the encoded bit, so the sign differs only for `x = 0`, i.e. `y = +-1`,
  the points of order 1 and 2, which the small-order test refuses anyway. Not changed: the equation, the small-order and `S < L`
  tests, `A` negated and not the scalar (mixed-order case 3), the final compression (the library's `ctEquals` compresses both points
  and measured slower). 152.8 to 137.8 us in one JVM, alternating (mean of eight captures 134.0 to 122.3 us, -8.7%), 521 KB to
  481 KB allocated. `StrictEd25519DifferentialSuite` keeps the old verifier as an oracle and compares verdicts on: the edge
  encodings (y from 0 to 39, from p - 20 to p + 19, 2^255 - 1, the blacklist, both signs), 100,000 random encodings, 6,000 public
  keys and their sign-flipped twins, the published vectors of `fixtures/witness` (about 2,200), and 1,500 signatures with bit flips
  in key, signature and message, an edge encoding as key or as R, and sign flips (about 13,000 verdicts). All equal; the speccheck
  cases (2, 4, 5 rejected, mixed-order 3 accepted) as before.
- **`VerificationCache.warm`** skips a witness whose verdict is already in the map (the same block warmed twice) and counts what it
  was asked for and what it skipped; the `verificationCache` event reads `warmed=skipped/asked` after the hit rates, so the next
  reading shows at once whether the checks run ahead are the ones the rules take. Test: `VerificationCacheSuite`.
- **`WarmBenchMain`** prints what the blocks hold (witnesses, scripts, ex-units, bytes), the cost of a witness, of a header's
  certificate and of its VRF proofs, the previous and the present verifier alternating, and the verifications `warm` computed
  against the lookups the rules make.

Rejected, with the numbers: a **cache of the decoded key** (decompress is 7.5% of a verification and half of it is the key: 4% at
best, with 56% of the keys of 400 blocks seen before; not worth a map); **batch verification** (it checks the cofactored equation;
this profile is cofactorless and accepts mixed-order keys, so verdicts could differ); **BouncyCastle's Ed25519** (not timed here: the
repository's own tests show it accepting speccheck cases 2, 4 and 5 that the strict profile refuses, so it is not the same
verifier); **a smaller warm pool** (the bursts of 5 to 16 cores per range may slow the apply thread through the shared caches; a
test box with a CPU quota cannot show it, so it is not changed).

### Before and after, on the captured blocks

`WarmBenchMain <capture> 8 400 1`, 400 blocks, 8 CPUs of a shared box (good to 15 to 30%; the two runs were ten minutes apart),
blocks/s of the warm alone: 326: 364 to 425; 7,157,879: 659 to 774; 7,163,435: 472 to 534; 7,168,894: 238 to 318; 7,170,435: 419 to
487; 7,172,435: 403 to 512; 7,174,435: 640 to 709; 7,178,435: 684 to 728. The alternating timing above is the cleaner figure. The
verification is not on the apply thread, so the follower's rate is not expected to change by more than the 9% of a quarter of its
CPU that this frees.

### Share of the slowdown

24 to 8.7 blocks/s is a factor of 2.8: **1.2 is the chain and the state grown** (24 to 20: a little heavier
blocks, and 31 to 34 ms to apply one);
**2.3 is the slow window**, unexplained, on blocks that were not heavy; **waste in the signature path is under 2% of the CPU** (9%
of a quarter, taken) and none of it was on the critical path. The waste the build did have was #133's, fixed on main.

**Repeat.** `CaptureMain capture --peer backbone.cardano.iog.io:3001 --magic 764824073 --blocks 400 --byron-epoch-slots 21600 --from
<slot>.<hash> --out b.hex`; `WarmBenchMain b.hex 8 400 1`; `jcmd 1 JFR.start name=p settings=profile duration=4m filename=/data/p.jfr`,
then `jfr print --events jdk.ExecutionSample --stack-depth 64` and `jdk.ObjectAllocationSample`; `scarab_block_apply_seconds` sum and
count twice a minute apart.

## 31. Live-sync investigation closeout (#124)

The original live-sync/replay investigation is complete; its residual work is tracked separately.
Section 13 explains the observed bottlenecks: repeated UTxO diff walks and pointer-map scans, then
signature CPU once those costs were removed. The same 12,000-block window improved from 70,500 to
255,200 blocks/hour on four CPUs. The original approximately 4× comparison used different block
windows, so it is not a remaining measured ratio to eliminate. Later Alonzo samples in sections
17–19 used other builds and workloads; none establishes present hardware-matched throughput
parity with the Haskell node.

<!-- register:table perf-33.t1 -->
| Residual question | Where it remains | Evidence boundary |
|---|---|---|
| Safely parallelised ledger application and captured Preview verdict/digest acceptance | D2, #21, complete in PR #209 | Existing captured-block measurements do not establish uninterrupted live-sync throughput; later characterization remains D6, #25. |
| Timer fairness and long forger stages on one CPU | D8, #65 closed with an accepted v0.2 limitation; later characterization with D6, #25 | Existing isolation/instrumentation retained; no proved 50 ms step bound or zero-miss guarantee under one-CPU load. |
| Reward-update spikes | D15, #135, completed in PR #197 | Section 30 records incremental completion and sparse-chain forced completion separately. |
| Evaluator environment-copy cost and the evaluator choice | I2, #43 | The historical Scalus profile does not prove the same hotspot in every current workload. |
| Header-only relay reads, lagging candidates, snapshot sorting and commit locking | D17 in the roadmap | Historical #146 was folded into this investigation; these are later profiling candidates, not claimed implementations. |
| Uninterrupted throughput/RSS/disk and the unexplained restart slow window | D6, #25 | Cold RocksDB cache, JIT and host scheduling remain hypotheses from section 19. |
<!-- /register -->

This closeout audits existing evidence and follow-up ownership. It adds no performance experiment,
public-network sync or operational restart. Stored CBOR and native V5 remain; MemPack serves
Haskell snapshot import/export. Future optimization should use the same captured block window,
record the exact source/runtime and resource limits, and compare complete verdict/state results
before claiming an improvement.

## 32. Timing probe for the crypto math (#234)

`TimingProbeMain` (`crypto`, package `vrf`, diagnostic tool — never called by the node) measures
the crypto math for secret-dependent timing with a dudect-style protocol: a fixed-secret class
and a random-secret class, a fresh pre-generated random-class input per sample, percentile
bucketing of the two timing distributions, and Welch's t per bucket. JVM timing is noisy, so
every verdict is relative to a measured noise control (the basepoint table multiply, whose cost
does not depend on its scalar's value); a target is reported as leaking when
`t > 10 && t > 5 × noise`. A "no leak" verdict means **no leakage detected by this experiment** —
evidence of absence within the experiment's power, not a proof of constant-time behaviour.
Every successful output byte contributes to a checksum printed after measurement, outside the
timed intervals, so the results remain observable. Failed operations are reported and abort the
run; they are never timed as successful crypto.

Run on an idle machine (an interactive session's scheduler noise raises the floor):

<!-- register:listing perf-34.c1 -->
```
sbt "crypto/runMain xyz.kd5ujc.scarab.crypto.vrf.TimingProbeMain [samples [buckets [repetitions]]]"
```
<!-- /register -->

Defaults: 20,000 samples, 100 buckets, 2 repetitions; bounds are validated
(`samples >= 1000`, `2 <= buckets <= samples/2`, `1 <= repetitions <= 10`); ideally
`-XX:+UseSerialGC -Xbatch`. Expected on a quiet machine: curve25519's `point.multiply` shows no
leakage above the floor, while `Ed25519Point.scalarMultiply` and the `uniform` control exceed
the floor by an order of magnitude — the "variable-time, public inputs only" contract of
`Draft03Math.java`. An intermediate per-sample-input harness before the observable-checksum
correction (2026-10-19, a busy 2012-era i7-3770, JDK 21, defaults): noise 41.9/54.7,
`point.multiply` 23.5/20.2 (no leak
detected), `scalarMultiply` 626.6/691.8 (leak, as designed), `uniform` 5989.5/13877.3
(public input). These numbers are historical measurements of that intermediate harness, not
validation of the final observable-checksum harness. A pre-review measurement with an earlier
two-fixed-input harness on the same host recorded noise 18.5/53.9, `point.multiply` 24.6/11.0, `scalarMultiply` 693/1281,
`uniform` 3182 — attributed to that harness, not to the corrected protocol. The probe is a
manual/nightly tool, not part of `check` (shared CI runners are too noisy).

**Contamination and the discard rule.** On shared or virtualized hosts the noise floor
occasionally explodes with environmental spikes, and since every verdict is noise-relative,
a contaminated floor silently inverts verdicts (a genuinely leaky target reads "no leak
detected" against an absurd floor). Measured floors (2026-10-19, rutherford — Windows + WSL2
+ Docker Desktop, container pinned `--cpuset-cpus` with no `--cpus` quota): clean
repetitions read at or below ~54, contaminated ones read 9,000 to 2×10¹⁴. Docker CPU quotas
(`--cpus`) make it worse with periodic CFS stalls, so pin instead of quota. The probe
therefore discards any repetition whose noise floor exceeds the contamination bound (1,000)
and retries it once; a discarded repetition emits no verdicts and is counted in the final
summary. Expect a shared box to contaminate most repetitions — re-run until the wanted
number of clean repetitions pass, or use a quiet machine.
