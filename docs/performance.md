# Performance

Measurements of syncing the public Preview testnet, what the profiles showed and what changed.
These are one developer's measurements on one machine, not a benchmark suite; they are not
reproducible bit for bit, but each section names its build and setup.

Setup (all runs): 24-core host, Docker, JDK 21 Temurin, Preview via
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
  container RSS 2.2 GB at the tip of that multi-restart run with `-Xmx3g`. The uninterrupted run reached 5.66 GB RSS with the same heap limit, so 2.2 GB is not a bound; section 7 has the composition and the bound.
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
**The host had a load average of 25 to 35 on 24 cores (other work, several syncs and clusters at the
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

## 7. Memory (K6)

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
