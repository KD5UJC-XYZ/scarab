# Performance

**Status**: Historical records of measurements, each with its build and setup; the newest sections are the current numbers. Classes and flags named in older sections (`--rules`, `--mirror-check`, the differential harness, `NodeRules.tick`, `PreConwayRules`, `ConwayRules`) were removed in J2.9 and are kept as they were measured.

Measurements of syncing the public Preview testnet, what the profiles showed and what changed.
These are one developer's measurements on one machine, not a benchmark suite; they are not
reproducible bit for bit, but each section names its build and setup.

Setup (all runs): multi-core host, Docker, JDK 21 Temurin, Preview via
`preview-node.play.dev.cardano.org:3001`. The node container had `--cpus 4` unless stated;
`-Xmx6g` before and `-Xmx3g` after the changes below. Profiles are JFR (`settings=profile`) taken
from the running node and aggregated over `jdk.ExecutionSample`; JFR samples a few threads per
interval, so shares below are shares of samples.

## 1. Baseline (commit ef68faf): where the time went

Replay of 526,726 stored Preview blocks from genesis (no network), 4 CPUs:

| blocks | seconds | blocks/s (cumulative) |
|---:|---:|---:|
| 60k | 88 | 680 |
| 120k | 307 | 390 |
| 240k | 1048 | 229 |
| 360k | 1567 | 230 |
| 480k | 2241 | 214 |
| 526,726 | 2943 | 179 |

JFR, blocks 0 to 180k (28k samples):

- Ed25519 verification (`StrictEd25519.evaluate`): 53.8% inclusive; transaction witnesses
  (`PreConwayRules.witnessChecks`) 31.7%, operational certificates and KES 22.6%. The library is
  constant-time (curve25519-elisabeth) and does two scalar multiplications per signature.
- VRF proof verification (`StrictDraft03`): about 15%.
- `Bytes.fromArray` (a `Vector[Byte]` per byte string) 7 to 9%, plus boxed hashing and equality.
- Plutus (Scalus CEK machine) 4% early, 8% at block 380k.

Heap (`GC.class_histogram`) with the old in-memory ledger: 405 MB of `Object[]` at block 175k (284k
UTxO entries) and 888 MB at block 380k (757k entries); the live set was 2.7 GB and snapshots 335 MB
at block 527k. Live sync past block 527k with the old strictly sequential peer ran at 18 to 20
blocks/s on one busy core (about 1.5 s fetch plus 1.2 s apply per batch of 64).

## 2. What changed, in the order it paid off

1. `Bytes` became an `Array[Byte]` window with a cached hash; CBOR nodes are zero-copy windows of
   the block; integers are read as `Long`; Blake2b digests are reused per thread; key hashes cached.
2. The UTxO set moved behind `UtxoStore[F]` (RocksDB or memory) with a pure `Utxo` view; stake,
   total and count are kept incrementally. Heap live set after a full GC at block 522k: 383 MB (was
   2.7 GB); snapshot files 3 to 7 MB plus a 50 to 170 MB RocksDB checkpoint (was 335 MB).
3. Signature work moved ahead of the sequential rules: transaction witnesses, operational
   certificates/KES and (with the epoch nonce the rules last saw) VRF proofs are verified for a whole
   fetched batch in parallel and the rules read the remembered verdict. Hit rates over 60k blocks:
   witnesses 99.99%, opcerts 99.998%, VRF 99.9% (92% before re-warming on an epoch change).
4. The peer pipelines: a producer keeps three batches in flight (`--read-ahead`, default 128 since D3; an
   earlier note that 256 timed out the ChainSync wait is examined in section 7).
5. Per-block recomputation removed: `ln(1-f)` of the leader check, the pool distribution view, the
   stability window.

## 3. Results

Replay of the stored blocks, same machine and 4 CPUs (compact bytes, UTxO store, parallel
witness/opcert warm-up; no peer work):

| blocks | old s | new s | speedup |
|---:|---:|---:|---:|
| 60k | 88 | 50 | 1.8x |
| 240k | 1048 | 526 | 2.0x |
| 480k | 2241 | 1083 | 2.1x |
| 526,726 | 2943 | 1397 | 2.1x |

Live sync from genesis over the network (8 CPUs, `-Xmx3g`, RocksDB UTxO). One database from genesis
to the tip, restarted from its newest snapshot after each fix (twelve segments). **The build changed
as the work went on and the host was busy with tests and devnet runs, so the early buckets are
slower than the final builds would be; this is not an uninterrupted single-build run.** Blocks per
second per bucket of epochs (restarts excluded):

| epochs | blocks | seconds | blocks/s |
|---|---:|---:|---:|
| 0-99 | 403,344 | 873 | 462 |
| 100-199 | 350,067 | 1046 | 335 |
| 200-299 | 367,257 | 561 | 655 |
| 300-399 | 362,644 | 633 | 573 |
| 400-499 | 354,157 | 684 | 517 |
| 500-599 | 343,026 | 592 | 580 |
| 600-699 (Conway from 646) | 314,274 | 581 | 541 |
| 700-799 | 314,500 | 547 | 575 |
| 800-899 | 302,970 | 383 | 792 |
| 900-999 | 309,088 | 438 | 706 |
| 1000-1099 | 288,124 | 361 | 797 |
| 1100-1199 | 278,410 | 337 | 825 |
| 1200-1299 | 279,574 | 334 | 836 |
| 1300-1399 | 310,102 | 666 | 466 |
| 1400-1447 | 144,222 | 528 | 273 |
| all | 4,721,759 | 8,565 | 551 |

The heavy region just past block 527k (epochs 130 to 140) ran at 57 to 110 blocks/s with the
pipelined peer, 20 blocks/s before. The slowest stretches are the Plutus-dense blocks of the last
100 epochs, where the single apply thread (rules, Plutus, leader threshold) is the limit.

### Final uninterrupted run

One process on one build: commit d66b216 (frozen classes), fresh db, `follow --db`, no restarts, no
code changes, `-Xmx3g`, 6 CPUs, peer `preview-node.play.dev.cardano.org:3001`, network magic 2. The
tag `v0.1.0` (244d1c14) is on a later commit (hygiene, docs, CI on top of d66b216, no intended
behaviour change, all gates re-run); the sync ran on d66b216.

- Start 2026-10-11 15:50:22Z, tip reached 19:27:04Z (3h36m42s): 4,748,226 blocks, epochs 0 to 1447,
  about 365 blocks/s average.
- Blocks/s per 100 epochs: 349, 271, 400, 394, 375, 353, 361, 420, 406, 400, 397, 409, 398, 348
  (the last bucket mixes the catch-up with the live tip).
- Followed the live tip for 32.8 min (until 19:59:55Z). Final block: slot 125092775, block 4748301,
  hash `c182c2d1b783983c614d5331241bd640ebd76ba58f7f51a95dda5921865f733e`, identical to Koios `/tip`
  at that moment.
- 0 error events. Phase 2: 3,229,757 successful and 242 failing evaluations (each equal to
  `is_valid`), 11,213,919 scripts, 3,303,884 script data hashes checked; 1447 epochs validated. Final
  ledger: 3,195,803 UTxO, 112,205 accounts, 726 pools, protocol 11.0.
- Memory: container RSS grew from about 1 GB to a peak of 5.66 GB with `-Xmx3g`. The container limit
  was raised from 6 GiB to 10 GiB at 17:20 with `docker update` (no restart, no code change).
  Heap after GC was not measured in this run. Section 7 profiles the growth (glibc malloc arenas, not
  the heap or RocksDB) and bounds it. Disk: 14.8 GB.

What this shows: the node accepts the real Preview chain with full validation and no false
rejections, on a single build. It does not by itself show that the ledger state equals the Haskell
node's (roadmap K1, db-analyser comparison); the devnet comparator covers identity on generated load.

## 4. State at the tip (block 4,747,639, epoch 1447, protocol 11)

Memory and disk, from the earlier segmented run (12 restarts; the uninterrupted run above peaked higher on RSS):

- UTxO: 3,195,803 entries; RocksDB 466 MB; checkpoints 454 MB for two snapshots (hard links share
  most); one ledger snapshot file 52 MB; immutable blocks 14 GB.
- Heap after a full GC: 272 MB (class histogram top: `byte[]` 64 MB, `Bytes` 20 MB, `BigInt` 17 MB);
  container RSS 2.2 GB at the tip of that multi-restart run with `-Xmx3g`. The uninterrupted run reached 5.66 GB RSS with the same heap limit, so 2.2 GB is not a bound; section 8 has the composition and the bound.
- Verification verdicts found ahead (since the last restart, 300k blocks): witnesses 99.9997%,
  operational certificates 99.9997%, VRF 99.8%.
- Counts: 112k accounts, 726 pools; 3.23M Plutus transactions evaluated, 11.2M scripts.

## 5. Known gaps

- The ledger maps other than the UTxO (accounts, pools, DReps, the three stake snapshots, the pulsed
  DRep distribution) are still in memory and in the snapshot file; mainnet has about 1.3M stake
  credentials (roadmap D1).
- The apply thread is sequential: VRF is warmed, but the rules, Plutus evaluation and the leader
  threshold are not (D2).
- Ed25519 verification is half of the CPU time and only parallelised. A faster verifier that can only
  accept was designed and then rejected as a weakening of the strict profile; any speedup must keep
  strict semantics (D7).
- One ChainSync stream per peer, `--read-ahead` 128 headers per round trip by default (D3 measured 64 to 256 on Preview
  only; the large-block regions of mainnet were not reached). Mainnet (about 12M blocks) has
  not been synced (D6).

## Preprod from genesis (C4)

Commit 6dd8be3, frozen classes, fresh db, `follow --db`, `-Xmx3g`, container `--cpus 4 --memory 10g`,
2026-10-11, `--seconds 3600` against `preprod-node.play.dev.cardano.org:3001`, no restart: 697,724
blocks in 60 minutes (about 195 blocks/s, slower than Preview because preprod's early epochs are dense
and the full ledger runs from Shelley; the other devnet and sbt jobs shared the machine for part of
it), slot 22,441,191, 0 error events, database 1.5 GB (immutable blocks, RocksDB UTxO, snapshots).
Byron: 46 blocks, derived transition epoch 4; eras entered at epochs 4 (Shelley), 5, 6, 7 and 12.
The tip (slot ~136M, about 6M blocks) was not reached; an earlier 50-minute run on a slightly earlier
build (restart from the db after two minutes) agreed (epoch 54, 0 errors).

**To the tip (2026-10-15).** Main 9ff0ca7d, frozen classes, fresh db, `follow --genesis-dir --db`
against `preprod-node.play.dev.cardano.org:3001`, `-Xms3g -Xmx3g -XX:+UseG1GC
-XX:MaxGCPauseMillis=20`, `MALLOC_ARENA_MAX=2`, container `--memory 10g` with no CPU limit (24 host
threads, shared with test gates and other syncs for most of the run), no restart: 5,295,553 blocks
from genesis to the tip in 4 h 52 min (about 300 blocks/s on average), 0 error events. Era entry
times: Shelley to Alonzo within the first 2 minutes, Babbage at 5 min, Conway at 1 h 46 min; Conway
(epochs 163 to 319) took the remaining 3 h 6 min. At the tip: container memory 5.6 GiB of 10 GiB,
database 18 GB. Tip block 5,295,552 (slot 136,392,901, epoch 319) equal to Koios.

## 6. Restart and scheduling (D4, D8)

Measured on this branch (the commit is in [compatibility.md](compatibility.md) with the other
evidence) against `origin/main` at 7842866, "base", extracted and built separately. The sync runs:
Preview from genesis, fresh `--db`, `follow --db`, 1860 s, `--seconds` ending the run (a graceful stop).
**The host had a load average of 25 to 35 (other work, several syncs and clusters at the
same time), so the absolute rates are about half of section 3's and only runs that were started
together are comparable.** The base and the final build of each pair were started at the same second.
Reproduce with the commands under "How to repeat" below.

### Restart (D4)

Preview, 6 CPUs, `-Xmx3g`, the database of the 6-CPU run above (epochs 74 to 80), the `k` = 432
volatile blocks, `kill -9` with `docker kill -s KILL`:

| start | what the log says | time from JVM start to the volatile chain being back |
|---|---|---|
| after the run's graceful stop (`--seconds`) | snapshot restored at the immutable tip, `replayFinished` 0 blocks, `volatileResumed` 432, 0 dropped | 4.7 s |
| the same again, then `kill -9` 22 s later | restart: `replayFinished` 0, `volatileResumed` 432; the ChainSync intersection offered to the peer is the last block applied before the kill (slot 6,669,177), the next `extended` is the following block | 5.2 s |
| after that `kill -9` (no shutdown snapshot) | newest periodic snapshot restored, `replayFinished` 16 stored blocks in 0.7 s, `volatileResumed` 432, 0 dropped; no error event | 3.3 s |
| `docker stop` 23 s after start (an earlier run at epoch 44: stop to exit 1.2 s) | `shutdownSnapshot` written | |
| start after that | `replayFinished` 0, `volatileResumed` 432 | 3.7 s |

The 2.3 s restart recorded before D4 measured the snapshot restore; the node then reconnected and
fetched and applied the last `k` blocks. It now applies those blocks from the log before it connects
(2 to 5 s here, JVM still cold, the same rules), so the time to the tip is about the same and the
range fetch is saved. A `kill -9` earlier in this branch's life, with the newest periodic snapshot an
epoch behind, replayed 4,014 stored blocks in 66 s on a cold JVM and the loaded host; the shutdown
snapshot is what removes that case for a graceful stop.

Cost of the volatile log on the sync path (D4 only, `--cpu-threads 0`, 6 CPUs, started with the base):
30,423 blocks against 31,617 after 120 s (-3.8%) and 69,809 against 70,787 after 300 s (-1.4%). The log forces
the immutable store and collects its old files every 4,096 immutable blocks; at every 512 it was a flush
on the apply path.

### Scheduling (D8)

**Release decision, 2026-10-19.** The owner accepts the remaining one-CPU timer/forger-busy
limitation for v0.2. Closing [#65](https://github.com/KD5UJC-XYZ/scarab/issues/65) records that
decision; the approximately 50 ms step target and zero missed leader slots under load have not
been demonstrated. Existing CPU boundaries, bounded verification and instrumentation remain.
The later admission experiment reduced throughput by 38.4%; cancellation/lifetime obligations
prevented adoption of the other prototypes ([recorded outcome](https://github.com/KD5UJC-XYZ/scarab/issues/65#issuecomment-6255538228)).
Broader characterization belongs with [#25](https://github.com/KD5UJC-XYZ/scarab/issues/25): use
a frozen runtime and representative recorded workloads, report CPU quota/host load and GC alongside
throughput, validation/admission/forging latency distributions, timer lateness and missed slots,
and verify unchanged results. This is future measurement scope, without expanding #25's existing
mainnet-sync acceptance or starting an experiment. The historical measurements below are unchanged.

**6 CPUs, `-Xmx3g`** (the flags of section 3), base and final together:

| | base | final |
|---|---|---|
| blocks after 300 s / 600 s / 1200 s / 1800 s | 99,064 / 156,564 / 211,602 / 309,766 | 95,264 / 150,565 / 205,747 / 299,718 |
| blocks in 1860 s, epochs reached | 318,481, 0 to 76 | 308,025, 0 to 74 |
| difference after 1800 s | | -3.2% |
| starvation warnings | 0 | 0 |
| timer lateness (final, 88,524 samples of a 20 ms timer) | | max 116 ms, 45 over 50 ms, 4 over 100 ms |
| longest CPU-bound step | | 167 ms (a block with Plutus scripts, epoch 58) |

**1 CPU** (`--cpus 1`). The JVM then picks the serial collector, and its pauses stop every thread:

| JVM flags | build | result |
|---|---|---|
| default (serial), `-Xmx3g` | early design (rules of each block on a thread pool) | 13 starvation warnings in 1,405 s, worst timer 674 ms; the GC log of a 420 s run of that kind: 7,595 collections, 11 of them full, longest 396 ms |
| `-Xms3g -Xmx3g -XX:+UseG1GC` | base / final | 1 / 4 starvation warnings in 1860 s; 204 / 187 collections over 100 ms (the pause goal is 200 ms) |
| the same, 10 min each, goal 200 / 40 / 20 ms | final | 1 / 0 / 0 warnings; timers over 100 ms: 19 / 1 / 0; 152 / 150 / 161 blocks/s |
| `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`, 1860 s | base | 205,713 blocks after 1800 s, **0** starvation warnings, longest collection 138 ms |
| the same | final | 206,192 blocks after 1800 s (+0.2%), **0** starvation warnings, 0 timers over 100 ms (max 89 ms) of 75,426, 2,222 over 50 ms, longest collection 100 ms, longest CPU-bound step 512 ms (a Plutus block in epoch 8, cold JIT) |

So with one CPU the starvation warnings came from the collector, not from the rules: with the flags in
the last two rows the base build has none either. What the pool adds is that the follower's own work
is not on the thread that serves the timers; the numbers above cannot show a gain from it, only that
it costs less than 4%.

Devnet `pool`, the Scala node as one of three pools (500-slot epochs of 0.1 s, f = 0.05; the oracle is
the reference node's `query leadership-schedule`), final build, 480 s each, G1 with a 20 ms pause goal:

| Scala container | runs | passed | scheduled slots per run | missed in the others | starvation warnings | worst timer lateness | longest step |
|---|---|---|---|---|---|---|---|
| 2 CPUs | 1 | 1 | 72 | | 0 | 22 ms | 39 ms |
| 1 CPU | 14 | 11 | 58 to 93 | 17 of 71, 19 of 69, 16 of 88 | 0 in all 14 | 69 to 134 ms | 20 to 93 ms |
| 1 CPU, base build (`7842866`, scenario edited to give the container 1 CPU and the same JVM flags) | 6 | 5 | 73 to 95 | 24 of 89 | not measured | | |

All comparisons matched (16/16 in each run). The three failing runs of the final build and the one of the
base build have the same signature, and it is not the CPU: the forger logs nothing for the missed slots,
and the Scala node's slot clock was one second behind the network's. In the runs whose log was kept, the
blocks of the other pools reached the Scala node 0.99 s before their slot began by its clock (the
final build's forged blocks were logged 1.0 s after their slot, in healthy runs 5 ms), so a leader slot of
the Scala node finds the tip already at a later slot and is skipped without an event. Healthy runs show
the forge at +5 ms and the arrivals at +14 ms. The cause (roadmap H6, found afterwards): the Haskell
nodes start their clock at the Byron genesis' `startTime`, not at the Shelley `systemStart` the Scala node
used, and the scenario wrote the two a second apart in some runs (`create-env` stamps the Byron one, the
scenario then rewrote only the Shelley one). The node now follows the Byron one, and the scenario writes both. The
`pool` summary carries `slotClock` (`forgeLatencyMs`, `arrivalMs`) and a failure that names an offset. So the 1-CPU check passes
whenever the clocks agree, and the scheduling numbers of the failed runs are as good as the others'.

### What did not work (the order matters)

Each row is an earlier build of this branch, started at about the same time as a base run (the first
two rows together with it, the others a few minutes later, so their percentages are less exact), blocks
after 300 s (120 s where noted) against that base run's:

| change | 6 CPUs | 1 CPU |
|---|---|---|
| the rules of each block and the verification on one fixed `ThreadPoolExecutor` | -33% (21.7k against 32.3k at 120 s) | -36% (10.4k against 16.3k at 120 s) |
| the rules of each block on that pool, verification back on `blocking` threads | -48% (37.1k against 70.8k) | -23% (32.5k against 42.2k) |
| the blocks of a ChainSync step applied as one `within` (fixed pool) | -18% (70.2k against 85.3k) | -15% (44.1k against 52.0k) |
| the same on a second cats-effect work-stealing pool | -15% (72.6k against 85.3k) | -17% (43.4k against 52.0k) |
| **the whole peer session on the work-stealing pool** (final) | **-3.8%** (95.3k against 99.1k) | **-1.9%** (53.6k against 54.6k) |

(The session applies one header at a time, so a "batch" was one block and the third and fourth rows
still crossed once per block.) Every hand-off between pools is a thread wake-up, which on a busy host
is a scheduler delay in a chain of 400 sequential blocks a second; the cost is in how often the work crosses, not in what it
does. Moving verification onto the same pool serialised it behind the rules, and putting it back on
`blocking` threads recovered the throughput.

### How to repeat

```sh
# base: git archive 7842866 into a directory, build it there, freeze its classes (as devnet.py does)
# final: freeze this checkout's classes; then, for each CPU count and JVM flags, in parallel:
docker run --rm --cpus 1 --memory 10g -v <frozen classes>:/run/classes:ro ... scarab-jdk:local \
  java -Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20 -cp <classpath> xyz.kd5ujc.scarab.node.Main follow \
  --genesis-dir <preview config> --peer preview-node.play.dev.cardano.org:3001 --db <fresh dir> --seconds 1860
```

The log's `extended` lines give blocks per second, the `scheduling` object of each `ledgerEpoch` and
`epochValidated` line the timer lateness and the longest step, `-Xlog:gc` the collector's pauses, and the
starvation warnings are lines containing `starvation-and-tuning`. `python3 scripts/devnet.py pool
--scala-cpus 1 --max-starvation-warnings 0` is the devnet check.

## 7. ChainSync pipelining depth (D3) and header-first validation (G6)

### What limits the depth

The note that 256 pipelined ChainSync requests timed out against the public relay (while 64 to 128
worked) was checked against the pinned upstream sources and against the relay:

- **Protocol limits do not forbid 256.** `Cardano.Network.NodeToNode.defaultMiniProtocolParameters`
  has `chainSyncPipeliningLowMark = 200` and `chainSyncPipeliningHighMark = 300`, and the reference
  client (`pipelineDecisionLowHighMark 200 300`) keeps up to 300 requests outstanding. The ingress
  queue limit of ChainSync (`chainSyncProtocolLimits`) is `1.1 * 300 * 1400 = 462,000` bytes, counted
  on the payload bytes a peer has sent that its mini-protocol has not read yet; our requests are 2
  bytes each, so 256 of them are 512 bytes. The time limits (`timeLimitsChainSync`) are 10 s for
  `StIntersect` and `StNext StCanAwait` (`shortWait`), a random 135 to 269 s for `StNext StMustReply`
  (untrusted peers) and the idle timeout for `StIdle`; our client uses the same 10 s, 10 s and 300 s,
  with each reply of a pipelined round given its own 10 s from the previous one
  (`ChainSyncClient.Settings`).
- **The relay answers a deep round quickly.** The node now logs the time of the ChainSync rounds
  (`chainSyncRounds`: from the first of `n` pipelined requests to the last reply). Over 15 minutes of
  Preview from genesis, depth 64: 165 ms on average, 596 ms at most; 128: 162 to 187 ms, at most 757
  ms; 200: 230 ms, 677 ms; 256: 262 ms, 805 ms (one run without header-first had one round of 2.3 s on
  a host at load 40). The 10 s wait has a factor of 4 to spare at the worst round measured, and
  40 or more for the typical one.
- **No timeout occurred.** In 16 runs of 12 to 15 minutes against `preview-node.play.dev.cardano.org:3001`
  (each from genesis to epoch 33 to 44, 139,000 to 183,000 blocks; depths 64, 128, 200 and 256, with
  the build before this work and with it, `--header-first on` and `off`, and, with the base build on
  2 CPUs with everything on the compute pool as before D8 (`--cpu-threads 0`), depth 256 (141,954
  blocks) and 64 (139,223)) there was no `reconnecting` event, no timeout and no starvation warning.
  The observation recorded in `findings.md` was made on a build from before the D8 scheduling work
  and before the receive-path fix below, and was not reproduced: the regions of the chain
  reached in 15 minutes (epochs up to 44) have small blocks, and the relay was not slow.

### What was found: the receive path

Looking for what can hold a ChainSync reply for 10 s on the connection that ChainSync shares with
BlockFetch (the multiplexer reads one socket; until #132 a full ingress queue of any mini-protocol stopped the
reads for all of them, now the queues are counted in bytes and never wait, see section 21), the receive path turned out to be quadratic. `ProtocolWire.Reader` read every
byte through `Bytes.value`, which builds a `Vector[Byte]` of the whole buffer, and the multiplexer
and its decoder copied through `Vector`s as well. A range of 100 blocks of 90 KB (9 MB) through
`BlockFetchClient` over an in-memory bearer took **12.6 s (0.72 MB/s)** on a host at load 40; after
reading bytes by index and slicing windows it takes **0.19 s (48 MB/s)** (`ClientSuite` pins it under
5 s). A range that takes longer than 10 s to receive holds the next batch's ChainSync replies behind
its data: that is how a deep pipeline of large blocks reaches the ChainSync wait, and it did not need
more than the 8 MB range budget to do so. Preview's blocks are small (Koios samples of 60 blocks per
epoch from epoch 8 to 1450 average 0.2 to 5 KB, with a burst of 63 KB blocks around epoch 7), which is why the runs above never came
near it; a chain with large blocks (mainnet during busy epochs) does.

### Typed failures on the measured receive path

The domain-error migration retains local mutable cursors, framing buffers and explicit heap
stacks on these critical receive paths and the indexed CBOR path. The measurements above and
[the indexed-decoding spike](design/memory-lean-decoding.md#2-what-was-measured) are the existing evidence
for those exceptions to functional composition. CBOR and wire loops now leave their lexical
`scala.util.boundary` scope with typed `Left` values, rather than domain exceptions. This records
the retained implementation choices; it adds no post-migration throughput or allocation result
and does not justify imperative patterns elsewhere. The Scalus callback signal is a separate
[foreign ABI limitation](design/domain-errors.md#preserved-behavior-and-deliberate-seams).

### Depth against throughput

15 minutes from genesis, `--cpus 6`, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`, fresh
databases, on a host shared with other builds (load average 20 to 43, so only runs started
together compare):

| run | depth | blocks | blocks/s | epoch reached |
|---|---|---|---|---|
| base build, one after another | 256 | 183,050 | 194 | 44 |
| | 200 | 181,047 | 191 | 43 |
| | 128 | 181,198 | 191 | 43 |
| | 64 | 165,148 | 174 | 40 |
| this work, five together, header-first on | 256 | 156,837 | 164 | 38 |
| | 200 | 158,276 | 165 | 38 |
| | 128 | 150,992 | 158 | 36 |
| | 64 | 141,641 | 148 | 34 |
| this work, header-first off, together with the above | 128 | 155,919 | 163 | 37 |

Depth 64 is 6 to 10% slower than 128 and above in both series; 128, 200 and 256 differ by less than
the noise (the apply thread is the limit). The default is therefore **128**: the start of the plateau,
twice the previous default, well inside the reference client's high mark of 300, and at least 4 times
inside the 10 s wait at its slowest measured round (2.3 s; the typical round is 0.2 s). `--read-ahead 256` works and gains nothing
measurable on Preview.

### Header-first validation (G6): cost

Started together (three containers, same host load), depth 128, 15 minutes from genesis:

| build | blocks | blocks/s |
|---|---|---|
| base build (no header-first) | 175,279 | 183 |
| this work, `--header-first on` | 175,492 | 183 |
| this work, `--header-first off` | 175,677 | 183 |

Header-first validation costs nothing measurable: the signatures are verified once, in parallel
ahead of the rules, and the block's own validation consumes the same verdicts
(`VerificationCache.forHeaders`); in an earlier pair at depth 128 (the base build then one with
header-first, run one after the other) the difference was 2%, inside the noise of that host. Every
run logged `headersChecked` with 150,000 headers validated and `unchecked: 0`, `waited: 0`: the
whole Alonzo and Babbage region of Preview (TPraos from epoch 0, Praos from the epoch of the Babbage
hard fork) validates header-first against the real chain, across 34 to 44 epoch boundaries, with
no header refused.

### How to repeat

As in section 6, with `--read-ahead N` and `--header-first on|off`; the `chainSyncRounds` lines of the
log give the round times (every 200 rounds, cumulative) and the `headersChecked` lines the validated,
unchecked and waited header counts (every 50,000).

## 9. The differential harness: what the conversion costs (J2.2)

`--rules differential` (and `PreviewDifferentialMain`) converts the old state to the mirror (`StateMirror.toMirror`) at
the sampling points: the first block, the first block of every epoch and era, every 2,160th block. While the new side is
not ported the conversion is the whole cost. Linux x86-64, JDK 21, 8 CPUs, `-Xmx16g`, commit of the J2.2 harness,
`PreviewDifferentialMain <K1 db> 72` (21 epochs of the Preview sync, each replayed from its snapshot and UTxO checkpoint, once
with the old rules alone and once with the harness):

| | |
|---|---|
| blocks replayed | 70,183 in 21 epochs (1, 73, 145, ..., 1441) |
| replay, old rules alone / with the harness | 305.4 s / 317.4 s (+3.9%) |
| conversions | 73; median 302 ms, p99 and maximum 1.39 s |
| conversion per epoch | median 1.13 s, maximum 5.15 s (epoch 1441: 4 conversions of a state of about 3.2 M UTxO entries and 112 k accounts) |
| eras fixture, every block sampled | 731 blocks, 0.40 ms median, 0.64 ms mean, 57.9 ms maximum per conversion |

The cost grows with the state (121 ms at epoch 1, about 1.3 s at epoch 1441). Sampling is the design's D7 interval; a
conversion after every block is out of the question on a real chain (twenty thousand blocks an epoch). Memory: the new side's
states ride beside the old ones for the volatile window; while nothing is ported the harness keeps none.

## 8. Memory (K6)

The uninterrupted run of section 3 grew from 1 GB to a container RSS of 5.66 GB with `-Xmx3g`. This
section says where the memory is and what bounds it. Measurements are one developer's, on a host that
was busy with other work (load average 2 when the 45-minute profile started, 25 to 37 in the hours after), so
the blocks per second are only comparable between runs started together.

**Method.** Commit 1e36a2d added `--memory-report-seconds N`: a `memory` log line with the JVM's own
accounting (heap used and committed, non-heap, metaspace, the direct and mapped buffer pools, thread
count, collections), the kernel's view of the process (`/proc/self/status`: `RssAnon`, `RssFile`),
the container's (cgroup v2 `memory.current` and its `anon`/`file` split) and the RocksDB properties of
the store (block cache usage and pinned usage, memtables, table-reader memory). Native Memory Tracking
(`-XX:NativeMemoryTracking=summary`, `jcmd 1 VM.native_memory summary.diff`) and `jcmd 1
GC.class_histogram` / `GC.run` were taken from outside every 5 to 15 minutes. NMT does not see
`malloc` calls made through JNI, which is what RocksDB does: the attribution below is RSS minus
what NMT and the store report, so it names glibc's arenas as what is left (and the run with
`MALLOC_ARENA_MAX=2` shows how much of that is arena fragmentation).

**Profile: Preview from genesis, 45 minutes, fresh database, two runs started together** (`--cpus 6`,
container limit 10 GiB, `-Xms3g -Xmx3g -XX:+UseG1GC -XX:MaxGCPauseMillis=20`, the build before D1,
commit 1e36a2d, `--utxo-cache-mb 256`), one with glibc's default arenas and one with
`MALLOC_ARENA_MAX=2`. `RssAnon` is the resident anonymous memory of the process (heap, malloc,
thread stacks); the cgroup `current` adds the page cache of the files the node wrote and read:

| minute | blocks (default / 2 arenas) | epoch | `RssAnon` MiB (default / 2 arenas) | cgroup `current` MiB (default / 2 arenas) | page cache, default MiB |
|---:|---:|---:|---|---|---:|
| 2 | 46,241 / 48,368 | 11 / 12 | 2292 / 2264 | 2373 / 2354 | 70 |
| 5 | 100,506 / 101,920 | 24 / 25 | 2659 / 2563 | 2984 / 2913 | 306 |
| 10 | 158,239 / 161,073 | 38 / 39 | 2996 / 2925 | 3776 / 3707 | 746 |
| 15 | 190,945 / 190,650 | 46 / 46 | 3352 / 3576 | 4727 / 4940 | 1323 |
| 20 | 209,955 / 207,505 | 50 / 50 | 3450 / 3662 | 5100 / 5330 | 1590 |
| 25 | 251,843 / 247,632 | 60 / 59 | 3624 / 3691 | 5492 / 5493 | 1802 |
| 30 | 287,732 / 282,694 | 69 / 68 | 3845 / 3703 | 5854 / 5697 | 1937 |
| 35 | 341,010 / 335,615 | 82 / 81 | 4019 / 3770 | 6281 / 6013 | 2183 |
| 40 | 369,819 / 365,796 | 90 / 89 | 4168 / 3858 | 6619 / 6280 | 2366 |
| 45 | 419,242 / 404,320 | 104 / 100 | 4259 / 3862 | 6957 / 6507 | 2608 |

Where the 4.29 GB of `RssAnon` of the default run is, at the NMT sample of minute 45 (16:36:51; the
table above has the nearest memory line, a few MB earlier):

| | MiB | what |
|---|---:|---|
| Java heap | 3072 | `-Xms3g`: G1 touches all of it; the live set after a full GC is 270 to 520 MB (histogram total 366 MB at epoch 138 with the base build) |
| JVM, not heap | 296 | NMT committed: GC structures (G1 remembered sets) 111, code cache 63, metaspace 62, symbols 21, class space 10, shared class space 13, thread stacks 6, other 10 |
| RocksDB | 260 | block cache 255 (full from minute 19: the cap holds), memtables 5, table-reader memory 0 (index and filter blocks live in the cache) |
| left over | about 660 | glibc malloc arenas: about 230 with `MALLOC_ARENA_MAX=2`; the default run's part grew by about 30 MB per minute from minute 15, the other's by about 8 MB per minute and was flat in the last 5 minutes |
| **`RssAnon`** | 4286 (default), 3862 (2 arenas) | |

Direct buffers were about 1 MiB (27 buffers), mapped buffers 0: the NIO off-heap pools are not involved.
The page cache is not memory the node holds: it is the blocks and SST files it writes (14 GB at the tip),
and the kernel gives it back under a memory limit (`docker stats` reports the cgroup usage minus
the inactive file pages, which here tracks `RssAnon`: 3.70 GiB while the cgroup's `current` was 5.4 GB).

**What caused the 5.66 GB.** The heap is bounded by `-Xmx`, the JVM's own native memory is flat at about
0.3 GB, and RocksDB was already bounded (one 256 MB block cache that holds index and filter blocks,
memtables of up to 3 x 64 MB, 512 open files). The growth is glibc: each of the node's threads
(cats-effect workers, the blocking pool, the verification pool, RocksDB's compaction threads) gets its
own malloc arena, and the arenas fragment and keep their memory. In the default run `RssAnon` grew by
0.9 GB between minutes 15 and 45 with the heap and the RocksDB cache at their caps; restarted from that
database (epoch 108) it went on to 4.47 GB at epoch 138 within 36 minutes of the restart. With
`MALLOC_ARENA_MAX=2` the growth over the same 30 minutes is 0.29 GB, and flat at the end. The 2-arena
run was 1 to 4% behind in blocks at every sample from minute 20 (404,320 against 419,242 at minute 45):
the size of the noise between two runs on this host, no paired repeat was made. `MALLOC_ARENA_MAX=2` is set in `docker/jdk.Dockerfile`
and in the devnet node's `docker run`; outside a container export it before starting the JVM.
jemalloc was not tried: two arenas bring the left-over part to about 0.3 GB, which is the same size as the
rest of the JVM.

**The bounded configuration.** `RocksUtxoStore` uses one LRU block cache (data, index and filter blocks,
`--utxo-cache-mb`, default 256) and a write buffer manager that charges the memtables to that cache, so the
store's native memory is the cache size however many memtables there are. The cap holds: cache usage after 8
minutes was 6.9, 30.4 and 255.5 MB for `--utxo-cache-mb` 8, 32 and 256 (`block-cache-usage` of the `memory`
lines). The cost of a small cache, measured from copies of one database at epoch 138 (live RocksDB 157 MB)
restarted together for 8 minutes (`MALLOC_ARENA_MAX=2`, 6 CPUs): 25,951, 26,192 and 27,658 blocks for 8, 32 and
256 MB (-6.2%, -5.3%, 0). The live database was 157 MB, so the 8 and 32 MB caches held 5% and 20% of it and the
256 MB cache all of it; at the tip (466 MB of RocksDB) the default cache holds 55%, and what that costs has
not been measured. The UTxO key is now the Haskell node's, the 32-byte txid and the index as a big-endian u16
(34 bytes, it was 36); the snapshot format version is 2, so older snapshots (whose checkpoints hold the old
keys) are rejected and the node replays. The value is still the output's CBOR, not the Haskell node's
MemPack `TxOut`: the codec exists and was measured (section 21, #143), and is not adopted.

**The bound.** With two arenas, `RssAnon` is the heap limit plus about 0.8 GB: 3.86 GB at minute 45
of the profile with `-Xmx3g` (3072 heap, 296 JVM, 260 RocksDB, 230 other). The recommended container
limit is `-Xmx` plus 2 GiB (5 GiB for `-Xmx3g`): the 0.9 to 1.1 GB of native memory, kernel memory and
head room for the page cache the kernel can always give back. A long run to epoch 1303 and on (4.35M blocks) of the build with the ledger tables
on disk (a separate branch, not this one) ran under that limit: `RssAnon` 3.61 GB at epochs 148, 161 and 358, 3.68 GB at epoch 902 with 3M UTxO entries, 0 errors, no out-of-memory kill; `docker stats` 3.61 GiB
against the 5 GiB limit.

## 10. The tick of every era: new rules against the old (J2.3)

`NewRules.tick` (the era-indexed `Tick`) runs beside `NodeRules.tick` (the old `OldTick`) at the eventful ticks and at the
sampling points of `--rules differential`. Linux x86-64, JDK 21, `-Xmx16g`, a host loaded to 25 (so absolute
times are pessimistic), `PreviewDifferentialMain <K1 db> 72`, 21 epochs, 70,183 blocks, 0 divergences. "Ticks" are the
compared ticks of the epoch (four or five), summed:

| epoch | old ms | new ms | seed (conversion) ms |
|---|---|---|---|
| 73 | 9 | 50 | 1,089 |
| 289 | 29 | 121 | 2,637 |
| 649 | 79 | 185 | 4,874 |
| 1009 | 198 | 311 | 8,947 |
| 1297 | 191 | 358 | 9,189 |
| 1369 | 1,010 | 2,492 | 22,977 |
| 1441 | 997 | 2,540 | 28,528 |

Replay with the harness 1,368 s against 896 s alone (the conversions, 73 of them, are most of the difference: median 1.3 s,
maximum 5.5 s). The new boundary is 2 to 3 times the old one: at 112,000 accounts SNAP takes 0.4 to 0.55 s, EPOCH with the
fresh DRep pulser 0.85 s, `applyRewardUpdate` 0.25 s, and the reward update (`Rewards.update`, at the first slot past 4k/f) 1.1 s,
because the era-indexed state is a sorted map where the old mirror kept hash maps and the rewards are attributed per credential.
Profiles: `SortedMap` builds and lookups (`Bytes.compareTo`, 19%) and rational arithmetic. `TreeMap.transform` and `filter` make
no comparisons, which is an easy saving for J2.8.

**`RuleT` against the reference stack.** The same `Tick` run through `RuleT` and through `Kleisli` over `StateT` over `Either`
at 112,000 accounts: the boundary 0.8 to 1.5 s with both, a tick that changes nothing 0.2 to 0.5 ms with `RuleT` against 0.5 to
1.1 ms with the stack. No difference beyond noise where it matters, so the 10% gate holds; `TickRulesSuite` checks that both give
the same state.

## 11. The blocks of Conway: the new rules against the old (J2.4)

`NewRules.forEra(Era.Conway)` applies each block of Conway (the tick and BBODY on a state of its own) beside the old rules
under `--rules differential`; the harness times the blocks both sides applied (`Stats.oldBlocks`, `newBlocks`). Linux x86-64,
JDK 21, `-Xmx16g`, a host shared with other runs (so absolute times are pessimistic). The two are not the same work: the old
side's time is its whole block, the headers' checks included, but its signatures and the block's decoding come from the
verification pipeline (`VerificationCache`, taken once), where the new side verifies the signatures and decodes the block
itself.

| run | the blocks | old, mean per block | new, mean per block |
|---|---|---|---|
| `PreviewDifferentialMain` epoch 701 (Conway, protocol 10), no sampling | all 3,068 are Conway | 2.14 ms | 2.75 ms |
| the forks run (snapshot indices 1, 2, 20, 21, 644, 645, 741, 742, 1288 to 1291, sampling every 500 blocks) | the Conway blocks of 41,474 | 0.64 ms | 0.95 ms |
| stride 72, 21 epochs, sampling every 500 blocks | the Conway blocks of 70,183 | 1.26 ms | 1.65 ms |

The new side's block, epoch 701 (3,068 blocks, 2.75 ms): the tick 0.08 ms, the block's decoding 0.10 ms, the signatures 0.31 ms
(`Verdicts.Direct`, 13% of BBODY), the rules with the phase-2 evaluation 2.0 ms. The phase-2 evaluation is what both sides spend
their time on, so the rules themselves cost little beside it.

**`RuleT` against the reference stack.** BBODY of the same 3,068 blocks run through `RuleT` and through `Kleisli` over
`StateT` over `Either` (both results compared, equal in every block): 7,886 ms and 7,856 ms. The interpreter is below the
noise of the work (phase 2 is most of it), `RuleT` stays and no fused form is needed; the perf gates of design 2.3 apply at J2.8.

**Recording votes** (`Proposals.withVotes`, one pass over the proposals) against a map over every proposal per vote: 100
votes cost 20 us with 5, 50 or 500 proposals in the store, against 21, 59 and 163 us. The preprod epochs 187 to 193 carry
648,752 committee votes on five proposals (#94).

The conversions of the harness are as in section 9 (median 0.44 s, p99 1.7 s and maximum 1.7 s over 183 conversions of the
stride-72 run); the sampling interval of the stride-72 run (500 blocks) is denser than the default (2,160), so its
replay is 2 times the baseline.

## 12. Decoding a snapshot: the heap of the epoch-188 state (#94, stages 2 and 3)

**The input.** The snapshot of the issue (233 MB, preprod blocks 3,033,001 to 3,114,547) is not on the machines that did
this work: the one kept as an acceptance input is a `CSLSNAP1` file of a schema the migration no longer reads, and the
live node's snapshots have lost the votes with the proposals. The measured file is made by `Memory94Main spam`: a V4
snapshot of the preprod tip (block 5,297,790, 51.6 MB, 29 MB of Haskell state) in which the Conway gov state holds five
proposals, each with the same 648,752 committee votes `[0, hash28] => 0..2` (random hashes), and the DRep pulser's
snapshot holds the five again, which is the ten copies of the heap dump of the issue. It is 253 MiB, of which the
Haskell state is 233 MiB (the issue's 227 MB), and 6.5 M `Credential.Key`. The copies are equal by construction, which
would overstate the gain of sharing whole maps and not that of sharing credentials.

**How.** Docker, JDK 21, G1, `Memory94Main` (`ledger`: `HaskellLedgerFile.decode` of the Haskell section; `v4`: the whole
`SnapshotV4` read to the node state; `written`: the node's write with its read-back), `-Xmx6g` for the retained size
(the heap in use after two full collections while the result is reachable, less the 262 MiB of the file), the minimum
`-Xmx` by bisection to 32 MiB. "Before" is `83afd506` (stages 0 and 1), "after" the commits of this section, both run on
the same file. The machine was shared and loaded (load average 30), so times are only good to about 30%.

| | before | after |
|---|---|---|
| `ledger`: typed state retained | 907 MiB | **353 MiB** |
| `ledger`: minimum `-Xmx` | 1,398 MiB | **869 MiB** |
| `ledger`: time | 4.3 to 6.2 s | 6.8 to 7.6 s |
| `v4`: retained | 959 MiB | **410 MiB** |
| `v4`: minimum `-Xmx` | 1,593 MiB | **1,059 MiB** |
| `v4`: time (-Xmx6g, quiet; at load 30, -Xmx3g) | 14.3 s (26.9 s) | 19.2 s (28.1 s) |
| `written` (write with read-back) at `-Xmx3g`: post-GC peak | 3,119 MiB | 2,283 MiB |
| 1 M committee votes (`MemoryBoundSuite`): minimum `-Xmx` | 218 MiB | 202 MiB |
| the 51 MiB tip snapshot, `ledger`: retained, time | 116 MiB, 1.25 s | 97 MiB, 1.48 s |

Stage 2 alone (builders, no sharing) left the first four rows where they were. The time is the lookup of 6.5 M
credentials in a table of 650 k (a few cache misses each); a `java.util.HashMap` made it 13 to 15 s and used 45 bytes a
value. The write at `-Xmx3g` passes before and after; its time under load varied from 49 to 100 s between runs of the same
build and is not a measurement.

**To repeat.** `export node/Test/fullClasspath`, then
`java -Xmx6g -cp <classpath> xyz.kd5ujc.scarab.node.Memory94Main spam <V4 snapshot> 648752 <out>` and
`... Memory94Main <ledger|v4|written> <out> [genesis-dir]` (the V4 snapshot of a preprod node, or any other: the spam is
put into its Conway gov state).

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

| samples (65,047) | share | what |
|---|---|---|
| `UtxoStore.advance`: three walks of the whole diff of the immutable state for every block that became immutable (`exists`, `filter`, `max`) | 32% | live only: in replay each state is immutable at once and its diff is small. The diff of the anchor holds the changes of the last k = 2,160 blocks (about 100,000 entries) |
| `PreConwayCerts` `Unregister`: `ptrs.filterNot(_._2 == c)`, `Credential.equals` over every pointer | 24% | once per stake key deregistration, over about a million entries (200 ms or so); the blocks that took more than 100 ms are 15% of all and are the ones that carry such a certificate (not checked block by block) |
| signature verification (witnesses, VRF, operational certificates) in `VerificationCache.warm` | 27% | on the parallel threads, off the apply thread; this is what the first two were hiding |
| the rules, decoding, chain selection, RocksDB reads | about 15% | |

Allocation (JFR `ObjectAllocationSample`): 18% in iterating the persistent maps (`BitmapIndexedMapNode.getPayload`), 15% in
Ed25519 lookup tables, 5.5% `UInt64.saturating`, 3.6% `BlockRef.point` (per-header chain selection).

What was ruled out: block fetch (the producer keeps three batches of 128 headers in flight; a 100-block range is 1 round
trip, and 70,000 blocks an hour is 20 blocks a second), header-first checks (the verdicts are kept; the cache hit rates
above), GC (1.6%), the timer pool (two starvation warnings), logging and the volatile log (no frame of either in the profile).

### Results (same 12,000 blocks, 4 CPUs, one peer)

| build | blocks per hour | speed-up | change |
|---|---|---|---|
| main (1e9b2847) | 70,500 | 1.0 | |
| + the store follows the immutable tip every 128 blocks, one walk (551b7f1b) | 117,100 | 1.66 | `ChainStorage`, `Utxo.changesSince` |
| + `PtrMap`, the reverse index of the pointers (00d257da) | 168,600 | 2.39 | persisted as the same map: the snapshot fingerprint is unchanged |
| + one interleaved double-scalar multiplication per Ed25519 witness, and the shared `BlockPool` (single peer) | 255,200 | 3.62 | 230 to 118 us per verification (200 keys, warmed, one core) |

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

```sh
follow --genesis-dir <mainnet> --db <dir> \
  --peer backbone.cardano.iog.io:3001 --peer backbone.mainnet.cardanofoundation.org:3001 \
  --metrics-listen 0.0.0.0:12798          # defaults kept: --read-ahead 128 --header-first on --cpu-threads <CPUs>
```

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
caller (`Peer.prefetchRange`) warms each fetched range as soon as it arrives and before the block is taken, which is as early
as possible, but a range is the unit it has and since #124 the ranges are small (a block of this stretch is 60 to 80 KB with
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

## 15. The epoch boundary of the new rules against the old, on the largest real state (J2.9 step 0)

The gate of design `ledger-mirror-step2.md` 2.3 is `epochBoundary` p99 within +25% of the old rules. J2.8 left it unmeasured. A
copy of the Preview harness (`PreviewDifferentialMain`, snapshots 1400 to 1449 of the K1 run: epochs 1401 on, protocol 11, about
112,000 accounts, the largest real state held locally) times the tick of the first block of each epoch, which crosses the boundary,
on the old rules (`NodeRules.tick`, the flat state) and on the new (`NewRules.tick`, the mirror), each from its own state, in one
JVM (`-Xmx12g`, G1, a host at load 6 to 9). The run was stopped after 23 of the 50 boundaries because the result was
decisive; the database was the K1 copy mounted read-only.

| 23 boundaries | old ms | new ms | new / old |
|---|---|---|---|
| mean | 271 | 406 | 1.50 |
| p99 (the maximum at this count) | 389 | 568 | 1.46 |
| max | 389 | 568 | 1.46 |

The gate fails: +46% at p99, +50% at the mean. Every boundary measured was slower on the new side (ratios 1.4 to 1.7). Heap: the
harness holds both states, so it is not the new rules' alone; the collector's post-collection heap was a median of 0.95 GB and a
maximum of 4.4 GB of the 12 GB. The memory gate needs a run of `--memory-report-seconds` on a node, not this harness.
## 16. The epoch boundary of the era-indexed rules made fast (#131)

Issue #131: at the boundaries of the Preview K1 snapshots 1400 to 1449 (protocol 11, 111,910 accounts, 278,970 instant-stake
credentials, 75,972 rewards at the largest) the new rules took 1.4 to 1.7 times the old rules, against the +25% p99 gate of
`ledger-mirror-step2.md` 2.3, and section 15 measured it at 1.46 (p99) at the start of J2.9. The first measurements here (before the change) were the same: 1.47 mean, 1.86 p99.

**Method.** `BoundaryTickMain <db> <genesis> 1400 1449 4` (a test-side tool): each snapshot of the K1 run is taken in the middle
of an epoch (about 43% in, past the start of the reward update); the tool reads it, finds the first block of the next epoch in
the ImmutableDB (read only), converts the state to the mirror and times `Hfc.tick` to that block, four times (JDK 21, G1,
`-Xms4g -Xmx12g`, Linux x86-64 on WSL2). The tables of the next paragraphs were taken on the J2.8 base, before J2.9 removed the
old rules, with the old rules' `NodeRules.tick` of the same block timed four times in the same JVM; the final before / after
at the end of this paragraph is on `main` after J2.9, the new rules alone. It prints the SHA-256 of the ticked `NewEpochState` written in the Haskell format
(`HaskellState.encode`). The host was shared and loaded (load 6 to 11 on 24 cores) and the same tick of the same state varies
between 200 and 900 ms from run to run, so the numbers are paired: the build before and the build after ran at the same time in
two JVMs, 50 snapshots by 4 ticks, 200 ticks each; the first tick of a snapshot is the one a state just converted meets (cold
caches), the others a warm one. The old rules measured here have a mean of 480 ms and a p99 of 790 to 870 ms, against 271 and
389 in section 14 at a lighter load: the ratio is what carries over, not the milliseconds.

| 200 ticks, concurrent runs | before | after | after / before |
|---|---|---|---|
| new rules, mean ms | 704 | 497 | 0.71 |
| median | 696 | 445 | 0.64 |
| p99 | 1,467 | 974 | 0.66 |
| the old rules in the same JVM, mean / median / p99 | 479 / 479 / 790 | 481 / 468 / 870 | |
| **new / old**, mean | 1.47 | **1.03** | |
| new / old, median | 1.45 | 0.95 | |
| new / old, p99 | 1.86 | **1.12** | |

Two rounds of the same builds run one after the other (one JVM at a time) agree: new / old mean, median, p99 of 1.38, 1.35, 1.49
and 1.36, 1.41, 1.74 before; 0.96, 0.87, 1.08 and 1.01, 0.99, 1.23 after (the build of those rounds differs from the final one
only in the tool). The gate (p99 within +25% of the old rules) holds in all four, with little room in the worst (1.23). The first
tick of a snapshot is slower than the later ones in both builds (556 against 422 ms best of four after); the best of four is
0.7 to 0.9 times the old rules.

**After the rebase onto J2.9** (the old rules, the flat state and `Relations.seed` gone; the tool reads the K1 snapshots with the
frozen reader of the `legacy` module and its conversion, which seeds the state as the migration does): `main` against `main` plus
this change, concurrent, 50 snapshots by 4 ticks: pooled over the 200 ticks the mean is 699 ms before and 381 after, the median 631
and 352, the p99 1,326 and 773; the first tick of each snapshot 828 and 478 (mean), the best of four 576 and 297. The gate against
the old rules cannot be measured on `main` any more; the ratio to
them was 1.03 (mean) and 1.12 (p99) on the J2.8 base above. The two measurements differ in the tool (this one has no old ticks
in the JVM, and seeds the state through the migration's conversion), so their milliseconds are not to be read against each other.

**Equal results.** The digest of the ticked state is the same before and after for all 50 snapshots, in both measurements. On the
J2.8 base the SHA-256 of the 50 lines `<snapshot> <digest>` is `362a1e6d3a26485a0f6d4f40ecf946a694133b3e15b80097bcf53c48f497986b`
before and after (the intermediate builds agreed too, at every step of the change); on `main` after J2.9, where the tool's seed
differs and so do the digests, it is `7d4770b48afc2cfb65fecccff3aac06c4078fc63cbb6b2733e6f07e57cbd9818` before and after.
`BoundaryDigestSuite` keeps six of the latter as goldens
(snapshots 1400, 1410, 1420, 1430, 1440, 1449; it runs when `SCARAB_K1_DB` names the K1 `db` directory, with `-J-Xmx12g`), and
`EpochBoundarySuite` checks each rewritten step against the straightforward version it replaced on generated data (the order of
byte strings, a map built from sorted input, the merge of accounts with the instant stake, the crediting of rewards, the cursor),
including the refusals.

**Profile, before** (JFR `profile` settings, 20 snapshots by 5 ticks, 5,956 samples in `Hfc.tick`; the stack depth of 300 keeps
the roots): leaf frames: `Bytes.compareTo` 27%, the tree iterator 10%, the lambda of the `Credential` order 8% and `cats`
`Order.from` 8%, `RedBlackTree.lookup` 4%, hash-map builders 5%. By callee, 48% of the tick compared keys and 43% was `TreeMap.get`
(a lookup of each account's instant stake, of each reward's account, of each delegator's stake); `ConwayPulser.fresh` 37% (flat
copies of every account for the ratification, which reads a few), SNAP 42% (30% in `StakeDistribution.resolve`, whose `toList`,
`traverse` and `SortedMap.from` of the result were another 15%), `applyRewardUpdate` 17% (`partition` of the reward map and a
`foldM` of `updated`).

**What changed, in the order of the plan.**

1. *No rebuilds, no lookups for what is already in order.* The accounts, the instant stake and the rewards are maps in the same
   order, so they are walked together (`Sorted.Cursor`: step from the last key, and after eight steps look the key up from the
   root with `iteratorFrom`) where a lookup of each key compared about seventeen. The active stake is built from its sorted
   entries once (`Sorted.fromAscending`, which hands `TreeMap.from` a sorted source: `RedBlackTree.fromOrderedEntries`, linear);
   rewards are split into registered and not in one pass instead of `partition` (two trees built by inserting each entry), and
   credited by one merge with the accounts and one build (`EraLedger.creditAscending`; a few amounts are still credited one by
   one: fewer than one in 24 accounts). The pulser's copy of the accounts holds those the pools' reward accounts name, which
   is all ratification reads; its proposal-deposit scan is skipped when there are no deposits (usual), and the DRep
   distribution is summed in one pass with the instant stake walked beside the accounts. `Rewards.aggregate` and `total` take
   any sequence of entries and `aggregate` is a loop where it was `toVector.traverse`.
2. *Cheaper comparison.* `Bytes.compareTo` is `Arrays.compareUnsigned` (a vectorised intrinsic) after a comparison of the first
   four bytes kept in the object (`cachedPrefix`, filled on first use, like the cached hash). The object is 32 bytes with
   compressed references before and after (28 bytes of fields rounded up; 40 either way without them), so the UTxO set's many
   `Bytes` do not grow. Hashes differ in the first four bytes in all but one comparison in 4 billion, so most comparisons
   read two objects and no arrays. Every map of one key type now shares one `Ordering` (`SortOrder`, an `Order` that carries its
   `Ordering`): `Order.toOrdering` made a new one for each map, so `TreeMap.from` never saw two equal orderings and copied sorted
   maps by insertion. The `Credential` order is a direct match, without the tuple and the closure of `Order.from`.
3. *Rational arithmetic.* None of the changed code is rational: the reward calculation (`Rewards.update`, once in an epoch at the
   first slot past 4k/f) is not at the boundary and is not changed here; its 1.1 s of section 10 is a separate tick, to measure
   and, if it matters, to mirror from `Rewards.hs`' integer steps (a follow-up to track as its own issue).
4. *The fallback* (hash maps live, a sorted view at encode time) was not needed and is not taken; no decision to record.

**Profile, after** (same setup, 4,537 samples): `Sorted.Cursor` 41% of the tick (the walks of SNAP, the pulser and the rewards,
bound by the memory they read: 279,000 instant-stake credentials against 112,000 accounts), SNAP 37% (26% resolve, 11% the
pools' snapshots, which look up each delegator), `ConwayPulser.fresh` 35% (about 4% converting the DReps' delegators),
`applyRewardUpdate` 23% (10% registration, 11% the merge), `Bytes.compareTo` 13%, `TreeMap.get` 7%. What is left is the walks
over the three big maps; fusing them across rules (SNAP and the pulser both walk accounts against instant stake) would need the
rules to share a result, which the rule algebra does not have, and the memory reads would remain.

A change tried and dropped: adding up the stake of an account in unsigned 64-bit arithmetic instead of `BigInt` (the sum of two
words, with the integers only on a wrap or with pointer stake) was 15 to 20% slower in a paired run than the `BigInt` version, not
faster, and was not kept.

**To repeat.** The `BoundaryTickMain` command above, from a test classpath
(`export node/Test/fullClasspath` in sbt), with `-Xmx12g`. The tool times the new rules alone (J2.9 removed the old); the ratio
to the old rules is this section's record.


**Current replay input (#83).** The measurements and commands above retain their original source/data
scope. Current `BoundaryTickMain` and `BoundaryDigestSuite` consume the verified V5 replacement
corpus, with the original immutable chain available read-only; original flat files remain archived
historical inputs for their pinned builds. Retarget `SCARAB_K1_DB` to the verified replacement before
using the current tool. Existing golden digests remain unchanged; this is conversion validation,
not a new performance comparison. See [Storage migrations](storage-migrations.md).

## 17. Per-block ledger application in late Alonzo (#133)

**The question.** The mainnet follower in late Alonzo (epoch 327, block 7.0 M) applied about 26 blocks/s on 1.5 of 12 cores:
`scarab_block_apply_seconds` averaged 31 ms per block (33,520 blocks; 69% between 16 and 32 ms), and the Haskell node does this
era at about 13 ms per block. Thread dumps had put the one busy thread in `PreConwayRules.apply`.

**Profile** (JFR, 10 minutes, `settings=profile`, 45,793 execution samples, read only on the running follower; the deep
stacks of the rules are cut at 64 frames, so shares are by the frames that remain; JFR also samples few threads per interval,
so these are shares of samples, not of wall time). `jdk.ThreadStart` counted 133,584 thread starts in the 600 s.

| share | what | on the apply path |
|---|---|---|
| 26.3% | signature checks (`VerificationCache.warm`, Ed25519, VRF) | no, parallel (#125) |
| 23.0% | the `plutus-evaluator` threads: the Scalus CEK machine | yes, the apply thread joins them |
| 11.0% | `PreConwayRules`/`TxRules`: 33% of these in `Value.+` (`mergeAssets`), 10% hex-string `TxIn` ordering (`Purpose.all`, three times per transaction with scripts), 8% `ScriptIntegrity` (about 300 cost-model integers encoded as a `Vector[Byte]` per transaction), 10% `Phase2` before the machine (half of it `ScriptContexts.bytesOrder`, which built the `Vector[Byte]` of both operands on every byte of every comparison), the rest UTxO updates and small checks | yes |
| 10.5% | `Follower.adopt` -> `ChainSelection.decide` -> `Header.point` (`UInt64.saturating`, `BigInt.compare`) | yes, under the follower's lock |
| 10.4% | the snapshot writer (`LedgerMirror` `Sorted.map`, SHA-256): one 600 MB snapshot every 10 minutes | background |
| 3.4% | `Follower.commit`, of which `ChainServer.headerOfBlock` decodes and re-hashes the whole block to get its header | yes |
| 2.4% | epoch boundary (#131, not touched) | yes, once per epoch |
| 2.0% | UTxO store (`advance`, `settled`); RocksDB `multiGet` is 24 of 28,956 native samples | partly |

What the thread dumps called `PreConwayRules.apply` was the sum of the rules and the machine; the rules alone are a fifth of
the path. The `new Thread` frames were `ScalusEvaluator.onBigStack`: a `Thread` with a 256 MB stack per script, 220 a second
(8.5 scripts a block; the log has 5.9 evaluated per block in epoch 328).

**Changes** (each in code the new rules share: `Address.scala`, `Purpose`, `ScriptContexts`, `ScriptIntegrity`, `Bytes`, the
consensus and the plutus modules; none in the flat rules J2.9 deleted, whose frames in the profile were the shared predicates, now
`TxChecks`, inlined into them):

- `ChainSelection.decide` searched the candidate from the tip and built the `Point` of every header first. The candidate of
  a peer that is behind us is wholly ours, the answer is `Keep`, and it is never trimmed, so it grows by a header per header
  and the cost per header grew with it. Now the points are made on demand from the tip: the first probe answers.
  `DecideBenchMain`, window of 2,160: 5,000 headers 117 us -> 9 us; 50,000 headers 9,200 us -> 35 to 57 us. A property test
  (`ChainSelectionSuite`) keeps the old definition and compares the decisions on random candidates.
- `ScalusEvaluator`: the big-stack threads are pooled (daemon, kept 30 s) instead of started per script.
- `Value.+`/`-` fold the entries of the right operand into the left (a persistent map keeps what it does not touch) instead of
  rebuilding both over the union; zero quantities and empty policies are dropped as before. `HotPathEquivalenceSuite`
  compares with the old definition on random values, zeros and negatives included.
- `TxIn`, `Credential` and withdrawal orders, and `ScriptContexts`' byte order, compare bytes (`Bytes.compareTo`, now also the
  `Ordering[Bytes]` given) instead of building hex strings or vectors; the same order (properties).
- `ScriptIntegrity` keeps the encoded language views for the cost models in force.
- `Value.headSize` no longer builds `2^32` on each call.

**Measured offline** (`ApplyBenchMain`, 400 consecutive mainnet blocks from epoch 320, 17,806 transactions; 4 CPUs, JIT-warm,
about 10 to 15% noise). The capture does not carry the UTxO set it spends, so the inputs from before it are made up (an
address of one of the witnesses, the value the transaction needs, ada only): of the transactions 9,788 pass the whole of
`apply`, the others stop at a check after most of the predicates ran. The checks that are timed read none of the made-up
values, but real inputs carry tokens and the mainnet share of `Value` was larger than here.

| per transaction | before | after |
|---|---|---|
| `PreConwayRules.apply` as a whole (measured before J2.9 removed it) | 30.5 us (1.36 ms/block) | 20.3 us (0.90 ms/block) |
| sum of the inputs and outputs' values | 9.5 us | 2.6 us |
| sum of the real output values | 4.3 us | 1.2 us |
| sort of the inputs | 0.5 us | 0.1 us |
| script integrity hash | 1.9 us | 0.5 us |
| phase 2 of the 45 recorded Preview cases (90 scripts, median of 40 passes) | 69.9 ms | 55.9 ms |

These are one-tenth of the 31 ms a block costs on mainnet (cold caches, a UTxO diff of 100,000 entries, 12 threads, garbage
collection, tokens in the inputs), so the offline numbers rank the changes; they do not predict the follower's rate. The
decisive measurement is a JFR of the follower after a deploy, with the same classification (the script of this section is the
`an5` classification of the table above) and `scarab_block_apply_seconds` against its 31 ms mean.

**Not done, and why.**
- The CEK machine itself is 23% of the samples. Inside Scalus 1.3.0 (the latest release) the machine's environment is an
  `ArraySeq` extended with `:+` (a copy of the whole environment per binding: `Array.copyOf` was 18% of the evaluator's
  samples) and `CorrectedVm.runtime` was 14% (the `getBuiltinRuntime` call; a per-ordinal memo made no difference on the
  Preview scripts and was dropped). A faster machine means a change upstream; the redeemers of one transaction are independent,
  but the owner rule is that transactions stay sequential, and a parallel evaluation of one transaction's few redeemers was
  not tried.
- `ChainServer.headerOfBlock` decodes and hashes the whole block (775 of its 1,173 samples are the body hash) to read its
  header: a header-only read would take about 1 ms off the serial path; not done here.
- The snapshot writer (10%) sorts every account for the Haskell layout; it is in the background and not the apply path.
- A lagging peer's candidate is still never trimmed.

**Repeat.** `jcmd 1 JFR.start name=p settings=profile duration=10m filename=/data/p.jfr` on the follower; `jfr print --events
jdk.ExecutionSample --stack-depth 64` and `jdk.ThreadStart`; offline `xyz.kd5ujc.scarab.ledger.ApplyBenchMain <blocks.hex> 400 6`,
`ApplyBenchMain plutus 40` and `xyz.kd5ujc.scarab.consensus.DecideBenchMain 50000 20` from the test classpath.


## 18. The cost of an extra hot session (G1.1)

The design asks what one more hot peer costs (D5): a hot session runs ChainSync, BlockFetch and TxSubmission2, so each one
is a header-validating client with its own pipelined reads.

**Devnet, steady state.** `devnet.py relay --seconds 300` with the Scala node following 1 and then 3 reference nodes (the
governor keeps every `--peer` hot), the container sampled every 5 s by `docker stats` (2 CPUs, 2 GB; the first minute dropped):

| hot peers | CPU mean (median) | resident memory mean (max) | blocks applied | relay scenario |
|---|---|---|---|---|
| 1 | 8.9% (6.6%) of the container | 275 (279) MiB | 194 | passed |
| 3 | 7.7% (6.4%) | 279 (291) MiB | 199 | passed |

Two more hot sessions cost about 2 MiB each and no measurable CPU. The devnet makes one block about every 20 s, so this is the
price of idle sessions and keep-alives, not of fetching: the difference in CPU is below the noise of a shared host.

**Preview, one peer.** The official topology has one bootstrap peer, so one hot session is all the public network gives: a
30-minute follower from genesis (`config.json` and `topology.json` of the official Preview files, no `--peer`, `-Xmx3g`, 3 CPUs,
6 GB) applied 517,605 blocks (epoch 132, about 290 blocks/s), the container used a mean of
204% of one CPU (maximum 295%) and a mean of 1.8 GB (maximum 2.7 GB resident, heap capped at 3 GB). That number is the cost of the
sync with one hot peer; the extra cost of a second, third or twentieth session against the public network was not measured:
it needs more reachable relays than the official topology lists. The test of the full target (30 established, 20 hot) is in
`FortyBearersSuite` (in-memory bearers, no CPU figure).

**Not measured.** Fetching in parallel from several hot peers during a long catch-up (the part the fetch mode will change, issue
#144). Repeat with `devnet.py relay --peers N` and `docker stats` on the node's container, or on a Preview follower with a
topology of several local roots.

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

## 20. The cost of the peer metrics callbacks (G1.2)

The hot sessions report to `PeerMetrics` on the per-header and per-block path: one `Peer.Observation.HeadersArrived` per ChainSync
batch (the slots of its roll-forwards, one arrival time) and one `BlocksArrived` per BlockFetch request, each a single
`Ref.update` of an immutable value (two 180-slot sorted maps and two small registries). Mainnet applies about 25 blocks a second.

**Micro-benchmark** (`node/Test/runMain xyz.kd5ujc.scarab.node.PeerMetricsBench`, one JVM, three interleaved rounds, steady
state: full boards, 20 peers; median ns after warm-up; the machine was loaded by a devnet and two followers). The first round
is cold JIT, the last two are the figures:

| what | per header or block |
|---|---|
| a header, a batch of 128 (a pipelined round far behind), pure | 320 to 396 ns |
| a header, a batch of 16 | 330 to 416 ns |
| a header on its own (at the tip) | 478 to 556 ns |
| the same through `Ref.update` in IO, batch of 128 / of 1 | 333 to 343 ns / 508 to 542 ns |
| a block, a range of 100 / on its own | 307 to 393 ns / 781 to 866 ns |
| `hotScores` for 20 peers, once per governor pass that demotes | 21 to 26 us |

At the tip a block and its header cost under 2 microseconds in all, against the 40 ms between mainnet blocks (0.005%); in a
catch-up at 300 blocks a second under 1 microsecond a block, against 3.3 ms (0.03%).

**Preview from genesis, base against final** (the method of section 6, D8): the build before the stage (`5f3b6174`) and this
one, started together, each `follow --genesis-dir fixtures/preview/genesis --peer preview-node.play.dev.cardano.org:3001`
in a container of 2 CPUs and 6 GB, `-Xms2g -Xmx3g`, G1 with a 20 ms pause goal, 660 s, a fresh database each, on a host
that also ran a devnet and `check`:

| | base | final |
|---|---|---|
| blocks applied after 120 s / 300 s / 600 s | 47,182 / 107,450 / 181,216 | 48,916 / 109,217 / 182,692 |
| blocks applied in 660 s | 188,373 | 188,979 (+0.3%) |
| reconnects, errors, stalls | 0 | 0 |

The final build is not slower (the difference, +0.8% to +3.7%, is within the noise of two runs on a shared host); the callbacks
do not add a cost that can be measured on the apply path. One hot peer is all the public Preview topology offers, so the
update contends with no other session; the contention of 20 sessions on the one `Ref` was not measured (a compare-and-set that
retries on a 20-peer value of this size costs microseconds, once per batch).

## 21. The MemPack `TxOut` against the stored CBOR value (#143, Step D stage S1)

Question of #143: is the reference's MemPack `TxOut` a better stored UTxO value than the output's CBOR? The codec is
`ledger/.../mempack/TxOutMemPack.scala`; the design, the format and the verdict are in
[design/haskell-snapshots.md](design/haskell-snapshots.md) section 8.

**Setup.** The `tables` file of the Mithril preprod snapshot at slot 136,511,997 (node 11.1.3, 4,526,058 outputs, 1.01 GB) and
the UTxO checkpoint of build ce8c438 (preprod, slot 80,177,772, 1,797,769 outputs). The two sorted streams are joined by key:
1,731,810 outputs are in both. `MemPackJoin join <tables> <checkpoint> bench` (storage tests, run in the sbt JVM, JDK 21, one
thread): the first 400,000 joined outputs in memory, three warm-up runs and the median of seven timed runs, the median of three
rounds, as `FormatBenchSuite`. The stored value is `UtxoStore.Coding`'s: a tag byte and the output's own CBOR.

**Equality.** All 4,526,058 values of the table decode and pack back to the same bytes; for all 1,731,810 joined outputs the
stored CBOR and the table's MemPack are the same output and packing the first gives the second's bytes (0 differences).

**Size and cost** (ns per output, 400,000 joined outputs):

| | stored CBOR value | MemPack |
|---|---:|---:|
| bytes | 230.6 | 231.6 |
| decode to the node's `TxOut` | 2,545 | 2,664 |
| decode to the fields only | | 562 |
| encode from the node's `TxOut` | 2,929 (with its read-back check) | 2,017 |
| encode from the fields | | 1,632 |

The whole table in one cold pass: 186.1 bytes an output in MemPack, 185.8 for the canonical CBOR value; to fields 982 ns, to
`TxOut` 2,747 ns, encode 2,339 ns. The MemPack value is no smaller and decoding to the `TxOut` is no faster, since the
original encoding that the rules re-read (`raw`) and the `Address` are rebuilt (2.1 of the 2.66 microseconds). Adoption is
not recommended; converting at an import or an export costs about 5 microseconds an output.

## 22. Channel ingress and fiber boundaries (#132, #154)

The multiplexer replaces an SDU-counted blocking ingress queue with a byte-counted queue that fails an
oversized peer without blocking unrelated protocols. Each background fiber returns a closed outcome to
its owner. `network/Test/runMain xyz.kd5ujc.scarab.network.MuxThroughputBench` measures messages over an
in-memory bearer with a bounded credit window; `node/Test/runMain
xyz.kd5ujc.scarab.node.FiberBoundaryBench` measures the error scope and pool-attribution race.

Docker JDK 21.0.11, four active processors, shared loaded host, October 18 2026. The same benchmark source
ran on baseline `bea81d57` and the preserved fiber implementation `3f5a479c`; baseline source was injected
as an additional test source without changing its checkout. After the initial cold rounds, sequential
warmed runs gave these medians per message (one baseline sample, two fiber samples):

| payload / credit window | baseline | fiber implementation |
|---|---:|---:|
| 1,400 bytes / 64 | 6.04 us | 7.71–8.62 us |
| 400 bytes / 64 | 5.23 us | 6.92–7.03 us |
| 65,000 bytes / 8 | 211.58 us | 202.73–223.06 us |

Small messages cost about 1–3 additional microseconds in this benchmark. Large-message differences are
within the observed run variation. This is an in-memory channel measurement, not end-to-end validation
throughput. Fiber start/join measured 496 ns without a scope and 808 ns with `Fault.fiber`; inline
`Handle.allow` was 73 ns. Queue offer/take measured 523 ns, and racing it against pool attribution measured
1,957 ns. Decoding the benchmark block took 4,936 ns. The added cost buys explicit ownership of errors
and bounded ingress without head-of-line blocking; these measurements do not claim it is free.

Original implementation `1c4d6806` versus parent `ee19ac38` also had two 330-second paired Preview
comparisons, recording a 0.4% reduction in applied blocks and no errors. These are historical stage
observations, supporting rather than replacing the channel measurements. Current regression gates use
local tests and private devnets.


## 23. Ledger peers at the Preview tip (G1.3b, #137)

Historical acceptance run on d05e38d8, 2026-10-18: official Preview configuration and topology,
no explicit peer, 1,860 seconds of observation after restoring a copied snapshot; Docker JDK21,
3 CPUs, 3GiB heap, MALLOC_ARENA_MAX=2. The original database was unchanged. This retained run
is reused during integration; no new public-network sync was started for these measurements.

The tip was reached in29 seconds. The governor held20 ordinary hot peers plus5 big-ledger hot
peers, with big-ledger known/established/active15/10/5. Container CPU after the first100 seconds
averaged7.5% of one CPU; whole-run CPU averaged14.3% and peaked229% during restore/replay/catch-up.
RSS averaged1.44GiB and peaked1.59GiB. The earlier single-peer G1.1 run averaged5.4% CPU at the tip
and1.74GiB RSS, so this run shows roughly2 percentage points more CPU for the extra sessions.
These are separate observations, not a controlled memory experiment.

One twelve-step churn round ran. Steps1–10 met their targets; steps11/12 timed out below the
known/established targets. Long-duration behavior and repeated churn remain broader G1/K4 coverage.
Source selection, traces, exact pool counts and remaining differences are in the
[peer-selection design](design/peer-selection.md), sections7d/7e.


## 24. Authenticated offline preprod ledger import (Step D S3, #140)

The pinned node 11.1.3 preprod ancillary snapshot at slot 136,511,997 imports in **57.1 seconds**
(2026-10-18, Docker JDK21). It contains 4,526,058 outputs in a 1,014,553,955-byte table and a
32,511,416-byte state. The command copies and verifies all six manifest files, checks the original
CRC/meta and exact locally recorded immutable point, loads 1,024-entry RocksDB batches, recomputes
instant stake and writes the V5/checkpoint pair. The resulting count is 4,526,058 and lovelace total
30,022,461,204,412,072. V5/checkpoint readback is included in the 60.3-second test case, outside the
57.1-second import interval. Heap/RSS were not sampled, so this run makes no memory-size claim.

Inputs were already cached: the public SHA-pinned ancillary and recorded immutable chunks. No
network follower or new public sync was started. The full table is streamed; batch memory is bounded,
while the bookkeeping maps scale with distinct stake credentials. This measures import; S4
export is measured below. S5 recorded-chain replay measurements are in section 26.
Reproduce with `SCARAB_IMPORT_FIXTURE` naming a read-only cache containing the extracted ancillary
and `recorded-db/`, then `node/testOnly *PreprodSnapshotImportSuite` in the JDK container. Optional
`SCARAB_IMPORT_DB` names a fresh external destination to preserve for later offline export/replay.

## 25. Offline preprod ledger export (Step D S4, #140)

The authenticated S3 checkpoint at slot 136,511,997 exports in **30.8 seconds** (2026-10-18,
Docker JDK21, core `b633bc50`), reusing the preserved 4,526,058-output RocksDB checkpoint. It reads the exact V5
Haskell section, streams sorted CBOR values to canonical MemPack `tables`, computes metadata,
forces the staged files and atomically publishes the fresh suffixed directory. The 32.7-second
test also compares original `state` and `tables` with `Files.mismatch` and checks every source
database file's SHA-256 before/after. Both files are byte-identical to the pinned originals;
parsed metadata is equal. Heap/RSS were not sampled.

This is one cached offline run, not a cold-I/O benchmark or the S5 replay/epoch acceptance. No
new import or public sync was performed. Reproduce using `SCARAB_EXPORT_DB` for a preserved
authenticated S3 database and `SCARAB_EXPORT_FIXTURE` for the pinned extracted ancillary, then
`node/testOnly *PreprodSnapshotExportSuite` in the JDK container. `SCARAB_EXPORT_OUT` optionally
preserves the new external output for offline reference acceptance.


## 26. Recorded preprod snapshot replay (Step D S5, #140)

These cached offline runs use pre-integration source `0b2efe56`, Docker JDK21 and reference
node 11.1.3 on 2026-10-18. Every Scala run starts from a fresh copy of the authenticated slot
136,511,997 checkpoint; Haskell starts from the original gap-free S4 export. No new public
sync or source-node restart is involved. Original database file hashes remain unchanged.

| Recorded target | Post-import blocks | Scala replay interval | Projection work included | Haskell full-validation/store interval |
|---|---:|---:|---:|---:|
| Epoch-320 first block 136,598,413 and immutable endpoint 136,602,570 | 3,980 | 66.307 s | 22.911 s + 22.526 s | 27.010 s to boundary, then 16.207 s to endpoint |
| Point reported 100% synced: 136,651,599 / block 5,307,009 | 6,141 | 55.148 s | 23.402 s | 37.821 s from imported anchor |

Both boundary/immutable projections and the captured synced-point projection have zero
differences under the existing oracle. Scala replay intervals exclude initial checkpoint restore
and final shutdown snapshot, but include the listed UTxO/projection work. The reference intervals
include reference state loading and snapshot storage. These are different measured intervals,
not a throughput comparison between implementations. Heap/RSS were not sampled.

The latter point comes from verified copied immutable and volatile records, with all recorded
rollbacks applied; it is the exact point reported at query time, not evidence of ongoing live
following. Input reconstruction/proof scope and retained mirror limits are in the snapshot design,
section 12. Reproduce with a fresh authenticated import, the verified original-byte recorded tail,
`follow --dump-state-at <boundary>,<endpoint>` in a network-disabled JDK container and
`db-analyser --analyse-from <anchor> --store-ledger <target> --full-ledger-validation` in the
network-disabled reference image, then compare with the unchanged oracle.

## 27. Certified Preview bootstrap (#27)

Recorded on 2026-10-18 with Docker JDK21, a 6 GiB heap and four active processors for the bootstrap.
The pinned official Mithril CLI is 0.13.28 at 769d862 plus the reviewed receipt/manifest-preservation patch.
The preserved Preview epoch 1454/i29089 artifact contains 16.0 GB of uncompressed database data and
3,192,290 ledger outputs. An owned loopback server serves genuine retained certified file bytes and certificates; unsigned tar
wrappers for reused trios are regenerated locally. These intervals do not measure public network throughput. A full check was running concurrently.

| Operation | Wall interval | Result/scope |
|---|---:|---|
| Production full download, full certification, immutable conversion, authenticated ledger import and atomic publication | 247.896 s | fresh 125,660,129 database, 3,192,290 outputs |
| Restore untouched imported V5/checkpoint and write existing state/UTxO digest | 20.716 s | genuine Haskell-supported projection 0diff |
| Ordinary bootstrap follower startup, 345-block recorded replay, endpoint dump and graceful shutdown | 28.206 s | replay 20.467 s including 18.378 s projection; endpoint 125,669,042 |
| Production cached-copy/full verification with an artifact-substitution descriptor | 42.504 s | typed refusal, no publication/staging left |

The independent genesis-derived continuation reaches the same anchor and recorded endpoint. Exact raw
Scala dumps retain two inactive Conway parameter differences; every field represented by Haskell's
Conway projection agrees (see [trust and acceptance scope](design/mithril-bootstrap.md#acceptance)).
No heap/RSS sampling, ongoing public following or mainnet performance claim is made.

## 28. Acquired LocalStateQuery UTxO view (#158)

The read-view cost was measured on two already-local datasets. These are storage-cost
measurements; neither is a new network sync. The archived Preview checkpoint is a 3,201,055-output
copy, not mainnet scale. Its source archive remained unopened and unmodified. A separately
constructed eight-million-record database is synthetic scale only; it does not claim mainnet
state fidelity.

| Dataset | Read-view acquisition/release | Held-view write workload and retention observation |
|---|---:|---|
| Archived Preview checkpoint copy: 3,201,055 outputs; stamp 4,756,803; selected anchor 4,756,810 | 26 ms | During a 10,000-key write batch snapshot count was 0 → 1 → 0; the old view did not see the appended key. |
| Synthetic RocksDB: 8,000,000 fixed-size 34-byte keys with valid CBOR outputs | 36 ms initial; 30 ms after writes | 1,000,000 updates in 100 batches of 10,000 took 1,496 ms while the view was held. The pinned view returned prior values and live reads returned updates; snapshot count was 0 → 1 → 0. SST size grew 266,797,837 → 287,626,320 bytes and memtable size 2,048 → 33,556,736 bytes during the held-view workload. |

The archived copy's checkpoint was 492,274,628 bytes and its paired V5 snapshot was
55,298,361 bytes. The synthetic initial database was 235,447,729 bytes. These figures show
measured acquisition and retention behavior for these datasets only; no query latency at eight
million entries, heap/RSS, or actual mainnet state fidelity was measured. Runs used bounded 10,000
entry writes and the approved owned copy under `/tmp/scarab/luna-158`; cost logs and exit records
are under `/tmp/scarab/luna-158/gates/cost-*`.

## Bounded parallel Plutus after ordered preparation (D2, 2026-10-18)

[ADR 0018](adr/0018-parallel-plutus-after-ordered-preparation.md) and the
[as-built design](design/parallel-plutus.md) replace the earlier scope restriction on
cross-transaction Plutus evaluation. Ledger application remains ordered; only immutable evaluator
requests run concurrently. `--plutus-workers 1` retains the serial rules. The node default is
`min(4, availableProcessors)`, with at least one worker, shared across validation calls including
serial fast paths. This does not close the broader Preview acceptance in [#21](https://github.com/KD5UJC-XYZ/scarab/issues/21).

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

| Concurrent transaction jobs | Evaluator time | Relative to 1 |
|---|---:|---:|
| 1 | 9.194 s | 1.00× |
| 2 | 6.326 s | 1.45× |
| 4 | 4.617 s | 1.99× |
| 8 | 3.798 s | 2.42× |

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

| Interpreter / worker bound | Full validation | Round range | Speedup vs original serial |
|---|---:|---:|---:|
| Original serial | 34.649 s | 34.603–34.959 s | 1.00× |
| 1, exact serial rules with admission | 34.660 s | 34.647–34.922 s | 1.00× |
| 2 | 29.337 s | 29.327–29.757 s | 1.18× |
| 4 | 26.913 s | 26.895–27.050 s | 1.29× |
| 8 | 26.052 s | 26.045–26.235 s | 1.33× |

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
in [reward-pulser.md](design/reward-pulser.md); #135 remains open.

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

| Snapshot index / epoch | Accounts | Active stake credentials | Snapshot pools / block-producing pools | Reward accounts returned |
|---|---:|---:|---:|---:|
| 1400 / 1401 | 110,718 | 79,745 | 725 / 93 | 5,908 |
| 1420 / 1421 | 111,081 | 79,748 | 727 / 72 | 6,035 |
| 1449 / 1450 | 112,311 | 79,723 | 727 / 74 | 75,616 |

Pool participation, stake distribution and the number of nonzero rewards matter; account count
alone does not explain these workloads' different costs. At `k = 432`, the reference formula
would choose **47 credentials per pulse** for all three. That is a work-item count, not a
measured pulse duration or a guarantee on the separate start/completion phases.

| Index | `Rewards.update` median / range (ms) | Trigger tick median / range (ms) | Ordinary tick median (ms) | Completed tick median (ms) |
|---|---:|---:|---:|---:|
| 1400 | 23.705 / 22.212–59.662 | 24.180 / 22.444–41.456 | 0.219 | 0.152 |
| 1420 | 25.525 / 22.727–44.033 | 25.614 / 22.874–42.534 | 0.221 | 0.162 |
| 1449 | 285.902 / 243.375–368.318 | 291.572 / 237.261–367.183 | 0.230 | 0.174 |

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

| Snapshot | SHA-256 |
|---|---|
| `snapshot-00000000000121083462-4605407.bin` | `79ce0874f6f8e1abb56a7d83e2ba49e5c0059ecd3bc07545fdaaa7d1d22b7172` |
| `snapshot-00000000000122811953-4664563.bin` | `745df2af04cdcacd4b17cc433e68c54e53bcb13c2e10377ac51ac7c93b3c9f38` |
| `snapshot-00000000000125318360-4756810.bin` | `5ce9ac8813202eb376c12f41f7d00d3c238c52458fb5eb6a77b5dfb364633545` |

Artifact SHA-256:

| Artifact | SHA-256 |
|---|---|
| Standalone source `RewardProfile.scala` | `8c38916527bcb8a11dc8adba35de4f60588edb00decc3bde7ddc3deb0d4e58cc` |
| Baseline class inventory `class-manifest.json` | `a9ddd74851c5171075317d260fea4b7354a0e7d5fdf300f5784c854bc68e945f` |
| Exact invocation `command.json` | `f866809ddba0e3e235761409d26228ad522187932ba5ea2f5112383441062bc5` |
| Output `profile.log` | `66f6aaf22c46cbae2476ae381bce7e8fd8cad865561f3c3cdeb7fc089e2ac868` |
| Recording `rewards.jfr` | `994083d5f323fa3517aceccae49ac9a77e50d71e903e7016be3b73ad865d37ab` |

The resulting-state digests for indices 1400/1420/1449 are respectively
`08da6612ee4a403ad358492b3ccde05199570ba1ff02d66d6c45978c8c63f2f2`,
`7f6cbc39c031526555749b0869aec8fd0cc3d11adc9983ac0fb193c338b0c9b5`, and
`a2912a1475bf177677e31589f4a9832116a5fe619706999851a8b7634ced936b`.


## 30. Incremental reward implementation (#135)

**Scope.** The [implemented machine](design/reward-pulser.md) spreads member calculation over
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

| Controlled API condition | Wall median (ms) | Largest wall call (ms) | Current-thread CPU median (ms) | Allocation per sequence (MB) |
|---|---:|---:|---:|---:|
| Eager, one call | 265.582 | 335.810 | 264.547 | 332.297 |
| Start | 4.278 | 6.214 | 4.276 | 5.199 |
| Member pulse, 1,697 calls/sequence | 0.0717 | 0.269 | 133.884 summed across pulses | 249.234 summed across pulses |
| Normal completion | 15.990 | 19.299 | 16.011 | 8.066 |
| Pulsed, whole sequence | — | — | 154.660 | 262.500 |

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

## 31. Live-sync investigation closeout (#124)

The original live-sync/replay investigation is complete; its residual work is tracked separately.
Section 13 explains the observed bottlenecks: repeated UTxO diff walks and pointer-map scans, then
signature CPU once those costs were removed. The same 12,000-block window improved from 70,500 to
255,200 blocks/hour on four CPUs. The original approximately 4× comparison used different block
windows, so it is not a remaining measured ratio to eliminate. Later Alonzo samples in sections
17–19 used other builds and workloads; none establishes present hardware-matched throughput
parity with the Haskell node.

| Residual question | Where it remains | Evidence boundary |
|---|---|---|
| Safely parallelised ledger application and captured Preview verdict/digest acceptance | D2, #21 | Existing captured-block measurements do not establish uninterrupted live-sync throughput. |
| Timer fairness and long forger stages on one CPU | D8, #65 closed with an accepted v0.2 limitation; later characterization with D6, #25 | Existing isolation/instrumentation retained; no proved 50 ms step bound or zero-miss guarantee under one-CPU load. |
| Reward-update spikes | D15, #135, completed in PR #197 | Section 30 records incremental completion and sparse-chain forced completion separately. |
| Evaluator environment-copy cost and the evaluator choice | I2, #43 | The historical Scalus profile does not prove the same hotspot in every current workload. |
| Header-only relay reads, lagging candidates, snapshot sorting and commit locking | D17 in the roadmap | Historical #146 was folded into this investigation; these are later profiling candidates, not claimed implementations. |
| Uninterrupted throughput/RSS/disk and the unexplained restart slow window | D6, #25 | Cold RocksDB cache, JIT and host scheduling remain hypotheses from section 19. |

This closeout audits existing evidence and follow-up ownership. It adds no performance experiment,
public-network sync or operational restart. Stored CBOR and native V5 remain; MemPack serves
Haskell snapshot import/export. Future optimization should use the same captured block window,
record the exact source/runtime and resource limits, and compare complete verdict/state results
before claiming an improvement.

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
recorded separately in [compatibility.md](compatibility.md#evidence-log).

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

| Workload | Original | First typed | Field/case retention | Allocated bytes: original / typed / retention |
|---|---|---|---|---|
| Equal 20,000-account state, ms | 2.044–2.054 (2.022–2.160) | 1.954–2.013 (1.939–2.094) | 1.953–1.984 (1.913–2.079) | 4,468,139 / 6,695,947 / 4,615,907–5,255,907 |
| First-account difference, ns | 99.6–104.7 (94.2–123.1) | 99.6–102.9 (92.6–121.5) | 94.9–95.3 (87.6–119.4) | 888 / 904 / 880 |
| Last-account difference, ms | 0.704–0.705 (0.699–0.709) | 0.736–0.744 (0.733–0.753) | 0.734–0.752 (0.729–0.753) | 875 / 915 / 891 |
| Same enum case, ns | 29.4–40.7 (28.0–51.3) | 36.8–41.4 (34.7–48.7) | 30.2–30.9 (29.2–32.0) | 288 / 360 / 304 |

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
## 33. Typed per-kind decode interning (#204)

**2026-10-20, level 3.** `Interned[A]` owns a homogeneous weak scope index returning only
`Table[A]`; the document retains that table strongly. Typed entries preserve the path
`Interned[A] → Table[A] → Entry[A] → A`. No heterogeneous value recovery, source cast or runtime
generic/phantom-role test remains. The [ADR 0013 addendum](adr/0013-cbor-documents-are-an-index-over-the-input.md#addendum-typed-kind-ownership-204-2026-10-20)
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

| Input | Identity | Bytes |
|---|---|---:|
| Current Preview native V5 | #27 authenticated bootstrap/replay, slot 125669042/block 4769869; `snapshot-00000000000125669042-4769869.bin`; SHA-256 `bbdc6fd7434564e0a289af5b596401d713dca75b26e70172b16803683e7be512` | 51,199,374 |
| Legacy-derived preprod native V5 | Maintained `preprod-v4` corpus, `snapshot-00000000000136444301-5297790.bin`; SHA-256 `b77a119082acbda5d6c2fb43131b5cad856056790b13a0164a02f48299a2ebcf` | 51,777,723 |

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

| Decode | Old per-JVM medians (ms) | Typed per-JVM medians (ms) | Median-of-medians change | Allocation old → typed (MB/decode) | Slowest typed / fastest old median |
|---|---:|---:|---:|---:|---:|
| Current | 500.121 / 601.654 | 513.600 / 509.894 | −7.11% | 1,241.477 → 1,241.858 (+0.03%) | +2.70% |
| Legacy-derived | 754.154 / 751.726 | 815.894 / 733.997 | +2.92% | 1,596.298 → 1,601.184 (+0.31%) | +8.54% |

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

| Memory, median of two JVMs | Current old → typed (MB) | Legacy-derived old → typed (MB) |
|---|---:|---:|
| LIVE−BASE | 152.195 → 152.151 (−0.03%) | 229.779 → 230.503 (+0.32%) |
| Sampled absolute peak | 528.591 → 533.744 (+0.97%) | 627.649 → 633.424 (+0.92%) |
| Sampled peak−BASE | 397.903 → 403.009 (+1.28%) | 497.274 → 503.040 (+1.16%) |
| RELEASED−BASE | 0.832 → 0.829 | 1.076 → 1.075 |

Every old/new LIVE histogram has exactly the same relevant retained counts:

| Objects | Current | Legacy-derived |
|---|---:|---:|
| `Bytes` | 604,950 | 1,170,833 |
| `ledger.Credential.Key` / `.Script` | 292,119 / 3,477 | 573,337 / 7,826 |
| Native `Credential.KeyHashObj` / `.ScriptHashObj` | 714,117 / 19,448 | 720,423 / 16,291 |

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

| JVM condition | sum of 16 block medians (ms) | sum of block median allocations (B) | epoch median / min–max (ms) | epoch median allocation (B) |
|---|---:|---:|---:|---:|
| 1: old | 10.616 | 5,582,643 | 128.933 / 119.868–146.499 | 189,684,048 |
| 2: typed | 10.201 | 5,584,775 | 136.438 / 126.009–181.238 | 194,147,472 |
| 3: typed | 10.532 | 5,584,387 | 124.670 / 116.000–138.672 | 194,147,472 |
| 4: old | 10.313 | 5,584,826 | 119.171 / 113.045–132.078 | 189,672,464 |
| 5: old | 10.369 | 5,595,939 | 136.279 / 119.611–171.220 | 186,629,096 |
| 6: typed | 10.494 | 5,582,846 | 141.159 / 130.718–161.803 | 191,121,528 |

The block column sums separate workload medians; it is not a timer around a whole replay.
Block allocations differ by less than 0.04% at the median of runs. Epoch median allocation
increases from 189,672,464 to 194,147,472 B (+2.36%). The full per-case latency summary follows;
old/typed values are medians across the three JVM medians, with the span of those JVM medians.
Raw per-sample ranges and allocations for every case remain in the bound summary.

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

## 32. Typed snapshot codec derivation (#205)

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

| Input | Provenance and SHA-256 | Bytes |
|---|---|---:|
| current Preview V5 | #27's authenticated bootstrap/replay, endpoint slot125669042/block4769869; `snapshot-00000000000125669042-4769869.bin`, `bbdc6fd7434564e0a289af5b596401d713dca75b26e70172b16803683e7be512` | 51,199,374 |
| legacy-derived preprod V5 | `snapshot-00000000000136444301-5297790.bin`, `b77a119082acbda5d6c2fb43131b5cad856056790b13a0164a02f48299a2ebcf`; authenticated conversion of the archived V4 input `0b7c061b8e47560c665d3d22c7898ca53884c361002c2975bd5d925a433f94a8`, source51,584,590 B | 51,777,723 |

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

| Case | old JVM medians, ms | typed JVM medians, ms | old → typed allocation per20k operations |
|---|---:|---:|---:|
| codec.encode | 95.979 / 97.291 | 95.813 / 96.704 | 1,338,080,000 / 1,337,120,000 → 1,337,120,000 / 1,337,120,000 B |
| codec.decode | 139.994 / 141.267 | 120.252 / 120.547 | 1,673,280,000 / 1,673,280,000 → 1,493,760,000 / 1,493,760,000 B |
| codec.record.encode | 0.684 / 0.687 | 0.820 / 0.794 | 9,120,000 / 9,120,000 → 8,640,000 / 8,640,000 B |
| codec.record.decode | 9.421 / 9.491 | 8.120 / 8.134 | 113,280,000 / 113,280,000 → 112,960,000 / 112,960,000 B |

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
`ThreadMXBean.getCurrentThreadAllocatedBytes` per thread; medians reported with min/max. The
sequence is interleaved — dev, branch, dev, branch — so drift over the session cannot be mistaken
for a difference between builds. Measured 2026-10-19/20 (CDT), OpenJDK 21.0.12.1, `-Xmx5g`.

**The host drifts, and the drift is the honest error bar.** Two runs of the *same* old build in one
session span 129.1–161.8 ms on `credentials.mapUpdate`, 119.1–140.0 ms on `mapBuild`, 3.16–3.18 s
on the 10,000-transaction block; the new build spans 5.45–8.67 ms on `diff.map.late`. A difference
smaller than the side's own span is reported as "within drift", not as a win.

**An earlier revision of this table was wrong, and this one replaces it.** The first harness
generated credentials that repeated every 256 inputs, so the map workloads held 256 keys, and its
digests covered counts rather than outputs. The review of #250 caught both. The numbers below are
from the corrected harness: 100,000 distinct credentials, real map copies and updates, complete
output digests.

| Workload (#, what it drives) | old (dev, span of 2 runs) | new (branch, span of 2 runs) | allocation old → new | reading |
|---|---|---|---|---|
| rules.bbody — 10,000 transactions through `Sts(Bbody)` (#202) | 3159.9 / 3175.7 ms | 3073.9 / 3256.9 ms | 5.224–5.230 GB → 5.217–5.227 GB | within drift; digest `159017f5…` identical on all four runs |
| credentials.sort — 100,000 distinct credentials (#207) | 16.647 / 15.939 ms | 12.961 / 15.687 ms | 1.327 MB → 1.327 MB | within drift; digest `2e82ce87…` identical |
| credentials.mapBuild (#207) | 139.952 / 119.059 ms | 128.674 / 114.461 ms | 60.912 MB → 60.912 MB | within drift; digest `0452736d…` identical |
| credentials.mapCopy — 100,000 entries (#207) | 64.367 / 60.248 ms | 87.578 / 65.396 ms | 82.352 MB → 82.352 MB | within drift |
| credentials.mapUpdate — 100,000 updates (#207) | 129.080 / 161.836 ms | 136.701 / 138.009 ms | 54.592 MB → 54.592 MB | within drift (the old build alone spans 129–162); digest `237a18d9…` identical |
| credentials.iterate (#207) | 1.215 / 1.302 ms | 2.121 / 1.354 ms | 2.422 MB → 2.422 MB | within drift |
| decode.nested — 2,000-deep array (#203) | 3.395 / 2.977 ms | 5.187 / 2.758 ms | 435,504 B → 435,504 B | within drift |
| decode.wide — 50,000-entry map (#203) | 21.874 / 15.752 ms | 15.911 / 16.263 ms | 10.697 MB → 10.697 MB | within drift; both digests identical |
| diff.row.equal / early / late (#206, **product** path) | 0.001 / 0.027–0.050 / 0.030–0.071 ms | 0.001 / 0.037 / 0.048 ms | 0/1832/1904 B → 24/1928/2328 B | equal is free; early +~30 µs, late +~20 µs per comparison — the product path's `ClassTag` extractor, **not** addressed by #249 |
| diff.sum.sameCase / otherCase (#206, **sum** path) | 0.030–0.055 / 0.030–0.039 ms | 0.047 / 0.030 ms | 1760/2184 B → 1952/2184 B | inside the microsecond noise; #249's claim is structural (one instance per derivation), not a percentage |
| diff.map.equal / early / late — 20,000 entries (#206) | 11.471–12.215 / 0.044–0.072 / 7.143–8.670 ms | 10.034 / 0.072 / 5.447 ms | 1.005–1.229 MB → 1.005 MB; 1.763 MB → 1.763 MB | within drift; digest `d582731d…` identical |
| codec.encode / decode — 20,000 iterations, nested synthetic record (#205) | 188.6 / 194.7 ms encode; 273.3 / 284.2 ms decode | 220.5 / 321.9 ms encode; 250.4 / 263.6 ms decode | 1.337 GB → 1.348 GB encode; 1.668–1.684 GB → 1.494–1.509 GB decode | encode **over the bar** (+13…+71%); decode 7% faster; bytes identical (`c19600a83b7ba784`, 333 B) |
| codec.record.encode / decode — `FutureGenDeleg` through `Record.auto` (#205) | 4.058 / 4.783 ms encode; 17.905 / 20.439 ms decode | 5.546 / 6.383 ms encode; 22.366 / 32.222 ms decode | 10.24 MB → 10.88 MB encode; 113.8 MB → 116.1 MB decode | encode +16…+57%; decode +5…+58% by median, +5% by minimum; bytes identical (`9b5615ce2d835aac`, 34 B) |

**What the batch cost, and what it did not.** The block and the decoder — the two primary workloads
(#202, #203) — sit inside the host's own drift with identical digests; #207's maps allocate the same
bytes as before; #206's large-map differences are inside drift. Two costs remain measurable and are
not hidden here. First, single-record differences pay tens of microseconds per comparison on
#206's product path (`ClassTag` test plus a `Some` per field, +96…+424 B per call) — a path that runs
once per divergence, never inside a block; #249 removes a per-comparison derivation from the enum
path but not that per-field cost. Second, #205's typed derivation makes **encoding** 13–71% slower
on both derivations measured, while decoding is neutral (7% faster on the synthetic record,
+5…+58% on the real one by median, +5% by minimum). #205 therefore stays open for an owner decision:
accept the encode cost for cast-freedom, or hold it.

**What this chapter does not show.** The issue's pinned Preview block/epoch workload for #202 is
not measured here — the 10,000-transaction BBODY is synthetic and is additional evidence, not a
substitute; no Preview capture is in-tree. #205's representative current/legacy snapshot is not
measured either: the codec workload encodes a synthetic nested record and one real two-field
snapshot record, not a full state snapshot. The devnet area scenarios (`e2e` for the ledger,
`restart` for storage) are separate evidence recorded on the PRs and in the compatibility matrix,
and neither is green for this batch yet.
