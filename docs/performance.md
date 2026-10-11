# Performance

Measurements of syncing the public Preview testnet, what the profiles showed and what changed.
These are one developer's measurements on one machine, not a benchmark suite; they are not
reproducible bit for bit, but each section names its build and setup.

Setup (all runs): 24-core host, Docker, JDK 21 Temurin, Preview via
`preview-node.play.dev.cardano.org:3001`. The node container had `--cpus 4` unless stated;
`-Xmx6g` before and `-Xmx3g` after the changes below. Profiles are JFR (`settings=profile`) taken
from the running node and aggregated over `jdk.ExecutionSample`; JFR samples a few threads per
interval, so shares below are shares of samples.

## 1. Baseline (commit 47f7208): where the time went

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
4. The peer pipelines: a producer keeps three batches in flight; `--read-ahead 128` worked, 256 timed
   out the ChainSync wait.
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

One process on one build: commit 5cfdefd (frozen classes), fresh db, `follow --db`, no restarts, no
code changes, `-Xmx3g`, 6 CPUs, peer `preview-node.play.dev.cardano.org:3001`, network magic 2. The
tag `v0.1.0` will be on a later commit (hygiene, docs, CI on top of 5cfdefd, no intended behaviour
change, all gates re-run); the sync ran on 5cfdefd.

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
  Heap after GC was not measured in this run, so the split between heap, RocksDB block cache and
  other off-heap memory is unknown (roadmap K6). Disk: 14.8 GB.

What this shows: the node accepts the real Preview chain with full validation and no false
rejections, on a single build. It does not by itself show that the ledger state equals the Haskell
node's (roadmap K1, db-analyser comparison); the devnet comparator covers identity on generated load.

## 4. State at the tip (block 4,747,639, epoch 1447, protocol 11)

Memory and disk, from the earlier segmented run (12 restarts; the uninterrupted run above peaked higher on RSS):

- UTxO: 3,195,803 entries; RocksDB 466 MB; checkpoints 454 MB for two snapshots (hard links share
  most); one ledger snapshot file 52 MB; immutable blocks 14 GB.
- Heap after a full GC: 272 MB (class histogram top: `byte[]` 64 MB, `Bytes` 20 MB, `BigInt` 17 MB);
  container RSS 2.2 GB at the tip of that multi-restart run with `-Xmx3g`. The uninterrupted run reached 5.66 GB RSS with the same heap limit, so 2.2 GB is not a bound; the composition is not yet profiled (roadmap K6).
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
- One ChainSync stream per peer, 64 to 128 headers per round trip (D3). Mainnet (about 12M blocks) has
  not been synced (D6).
