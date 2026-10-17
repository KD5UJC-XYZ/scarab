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
BlockFetch (the multiplexer reads one socket; a full ingress queue of any mini-protocol stops the
reads for all of them), the receive path turned out to be quadratic. `ProtocolWire.Reader` read every
byte through `Bytes.value`, which builds a `Vector[Byte]` of the whole buffer, and the multiplexer
and its decoder copied through `Vector`s as well. A range of 100 blocks of 90 KB (9 MB) through
`BlockFetchClient` over an in-memory bearer took **12.6 s (0.72 MB/s)** on a host at load 40; after
reading bytes by index and slicing windows it takes **0.19 s (48 MB/s)** (`ClientSuite` pins it under
5 s). A range that takes longer than 10 s to receive holds the next batch's ChainSync replies behind
its data: that is how a deep pipeline of large blocks reaches the ChainSync wait, and it did not need
more than the 8 MB range budget to do so. Preview's blocks are small (Koios samples of 60 blocks per
epoch from epoch 8 to 1450 average 0.2 to 5 KB, with a burst of 63 KB blocks around epoch 7), which is why the runs above never came
near it; a chain with large blocks (mainnet during busy epochs) does.

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
MemPack `TxOut`; that is not done.

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
