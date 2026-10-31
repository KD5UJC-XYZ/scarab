<!-- SPDX-License-Identifier: Apache-2.0 -->
# Memory, snapshots and bootstrap

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

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

<!-- register:table perf-10.t1 -->
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
<!-- /register -->

Where the 4.29 GB of `RssAnon` of the default run is, at the NMT sample of minute 45 (16:36:51; the
table above has the nearest memory line, a few MB earlier):

<!-- register:table perf-10.t2 -->
| | MiB | what |
|---|---:|---|
| Java heap | 3072 | `-Xms3g`: G1 touches all of it; the live set after a full GC is 270 to 520 MB (histogram total 366 MB at epoch 138 with the base build) |
| JVM, not heap | 296 | NMT committed: GC structures (G1 remembered sets) 111, code cache 63, metaspace 62, symbols 21, class space 10, shared class space 13, thread stacks 6, other 10 |
| RocksDB | 260 | block cache 255 (full from minute 19: the cap holds), memtables 5, table-reader memory 0 (index and filter blocks live in the cache) |
| left over | about 660 | glibc malloc arenas: about 230 with `MALLOC_ARENA_MAX=2`; the default run's part grew by about 30 MB per minute from minute 15, the other's by about 8 MB per minute and was flat in the last 5 minutes |
| **`RssAnon`** | 4286 (default), 3862 (2 arenas) | |
<!-- /register -->

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

<!-- register:table perf-13.t1 -->
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
<!-- /register -->

Stage 2 alone (builders, no sharing) left the first four rows where they were. The time is the lookup of 6.5 M
credentials in a table of 650 k (a few cache misses each); a `java.util.HashMap` made it 13 to 15 s and used 45 bytes a
value. The write at `-Xmx3g` passes before and after; its time under load varied from 49 to 100 s between runs of the same
build and is not a measurement.

**To repeat.** `export node/Test/fullClasspath`, then
`java -Xmx6g -cp <classpath> xyz.kd5ujc.scarab.node.Memory94Main spam <V4 snapshot> 648752 <out>` and
`... Memory94Main <ledger|v4|written> <out> [genesis-dir]` (the V4 snapshot of a preprod node, or any other: the spam is
put into its Conway gov state).

## 21. The MemPack `TxOut` against the stored CBOR value (#143, Step D stage S1)

Question of #143: is the reference's MemPack `TxOut` a better stored UTxO value than the output's CBOR? The codec is
`ledger/.../mempack/TxOutMemPack.scala`; the design, the format and the verdict are in
[design/haskell-snapshots.md](../design/haskell-snapshots.md) section 8.

**Setup.** The `tables` file of the Mithril preprod snapshot at slot 136,511,997 (node 11.1.3, 4,526,058 outputs, 1.01 GB) and
the UTxO checkpoint of build ce8c438 (preprod, slot 80,177,772, 1,797,769 outputs). The two sorted streams are joined by key:
1,731,810 outputs are in both. `MemPackJoin join <tables> <checkpoint> bench` (storage tests, run in the sbt JVM, JDK 21, one
thread): the first 400,000 joined outputs in memory, three warm-up runs and the median of seven timed runs, the median of three
rounds, as `FormatBenchSuite`. The stored value is `UtxoStore.Coding`'s: a tag byte and the output's own CBOR.

**Equality.** All 4,526,058 values of the table decode and pack back to the same bytes; for all 1,731,810 joined outputs the
stored CBOR and the table's MemPack are the same output and packing the first gives the second's bytes (0 differences).

**Size and cost** (ns per output, 400,000 joined outputs):

<!-- register:table perf-22.t1 -->
| | stored CBOR value | MemPack |
|---|---:|---:|
| bytes | 230.6 | 231.6 |
| decode to the node's `TxOut` | 2,545 | 2,664 |
| decode to the fields only | | 562 |
| encode from the node's `TxOut` | 2,929 (with its read-back check) | 2,017 |
| encode from the fields | | 1,632 |
<!-- /register -->

The whole table in one cold pass: 186.1 bytes an output in MemPack, 185.8 for the canonical CBOR value; to fields 982 ns, to
`TxOut` 2,747 ns, encode 2,339 ns. The MemPack value is no smaller and decoding to the `TxOut` is no faster, since the
original encoding that the rules re-read (`raw`) and the `Address` are rebuilt (2.1 of the 2.66 microseconds). Adoption is
not recommended; converting at an import or an export costs about 5 microseconds an output.

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

<!-- register:table perf-27.t1 -->
| Recorded target | Post-import blocks | Scala replay interval | Projection work included | Haskell full-validation/store interval |
|---|---:|---:|---:|---:|
| Epoch-320 first block 136,598,413 and immutable endpoint 136,602,570 | 3,980 | 66.307 s | 22.911 s + 22.526 s | 27.010 s to boundary, then 16.207 s to endpoint |
| Point reported 100% synced: 136,651,599 / block 5,307,009 | 6,141 | 55.148 s | 23.402 s | 37.821 s from imported anchor |
<!-- /register -->

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

<!-- register:table perf-28.t1 -->
| Operation | Wall interval | Result/scope |
|---|---:|---|
| Production full download, full certification, immutable conversion, authenticated ledger import and atomic publication | 247.896 s | fresh 125,660,129 database, 3,192,290 outputs |
| Restore untouched imported V5/checkpoint and write existing state/UTxO digest | 20.716 s | genuine Haskell-supported projection 0diff |
| Ordinary bootstrap follower startup, 345-block recorded replay, endpoint dump and graceful shutdown | 28.206 s | replay 20.467 s including 18.378 s projection; endpoint 125,669,042 |
| Production cached-copy/full verification with an artifact-substitution descriptor | 42.504 s | typed refusal, no publication/staging left |
<!-- /register -->

The independent genesis-derived continuation reaches the same anchor and recorded endpoint. Exact raw
Scala dumps retain two inactive Conway parameter differences; every field represented by Haskell's
Conway projection agrees (see [trust and acceptance scope](../design/mithril-bootstrap.md#acceptance)).
No heap/RSS sampling, ongoing public following or mainnet performance claim is made.

## 28. Acquired LocalStateQuery UTxO view (#158)

The read-view cost was measured on two already-local datasets. These are storage-cost
measurements; neither is a new network sync. The archived Preview checkpoint is a 3,201,055-output
copy, not mainnet scale. Its source archive remained unopened and unmodified. A separately
constructed eight-million-record database is synthetic scale only; it does not claim mainnet
state fidelity.

<!-- register:table perf-29.t1 -->
| Dataset | Read-view acquisition/release | Held-view write workload and retention observation |
|---|---:|---|
| Archived Preview checkpoint copy: 3,201,055 outputs; stamp 4,756,803; selected anchor 4,756,810 | 26 ms | During a 10,000-key write batch snapshot count was 0 → 1 → 0; the old view did not see the appended key. |
| Synthetic RocksDB: 8,000,000 fixed-size 34-byte keys with valid CBOR outputs | 36 ms initial; 30 ms after writes | 1,000,000 updates in 100 batches of 10,000 took 1,496 ms while the view was held. The pinned view returned prior values and live reads returned updates; snapshot count was 0 → 1 → 0. SST size grew 266,797,837 → 287,626,320 bytes and memtable size 2,048 → 33,556,736 bytes during the held-view workload. |
<!-- /register -->

The archived copy's checkpoint was 492,274,628 bytes and its paired V5 snapshot was
55,298,361 bytes. The synthetic initial database was 235,447,729 bytes. These figures show
measured acquisition and retention behavior for these datasets only; no query latency at eight
million entries, heap/RSS, or actual mainnet state fidelity was measured. Runs used bounded 10,000
entry writes and the approved owned copy under `/tmp/scarab/luna-158`; cost logs and exit records
are under `/tmp/scarab/luna-158/gates/cost-*`.

## 46. The UTxO store's scans: a fold against the loops (#650)

**2026-10-31.** `RocksUtxoStore` walks its RocksDB iterator for `size`, `all`, `select`, `selectAddresses` and `scan`.
Each walk was a `while` loop that put the entries into a mutable map builder (`scan` hands them to the caller instead).
#650 replaced them with one `@tailrec` fold whose result is an immutable `Map` grown with `+`. The fold is `inline`, so
each caller's per-entry code is compiled into its own loop: a shared fold that called a function per entry made `select`
about 5 percent slower and was not kept.

**Store and harness.** `UtxoScanBench` (storage tests) on a synthetic store of 4,500,000 outputs made by its `generate`
mode: plain addresses (about 100,000 distinct), lovelace only, with 45 addresses wanted by `select` and
`selectAddresses`. One JVM per run does six rounds of the five operations in turn; the first round is dropped, and a JVM
reports the median of the other five. Three JVMs per form, in the order loops, fold, fold, loops, loops, fold; the table
gives the median of the three and their range. Every round prints the count and an order-free hash of `all` and of
`select`, and checks that `select` and `selectAddresses` agree; the counts and hashes are the same in every JVM of both
forms. The loops are those of `5db649a9f`, the fold this section's commit. Euler (Intel Core Ultra 9 285K, WSL2), shared
with other containers (load average 1.3 to 3.0); Docker image `scarab-jdk:local`
`sha256:8f00ab6b6e1eed196c2b835bfd558507dfc29fac3153936fa38596248d7ebc76`, JVM 25.0.4.1+1-LTS,
`--cpuset-cpus=8-11 --memory=16g --network none`, `-Xms10g -Xmx10g -XX:+UseG1GC -XX:ActiveProcessorCount=4`.

<!-- register:table perf-52.t1 -->
| Operation (4,500,000 outputs) | Loops, ms (range) | Fold, ms (range) | Fold / loops |
|---|---:|---:|---:|
| `size`: count every entry | 555 (543 to 558) | 556 (553 to 568) | 1.00 |
| `all`: every output | 6,859 (6,698 to 7,020) | 7,417 (7,314 to 7,439) | 1.08 |
| `scan`: visit every output | 1,995 (1,976 to 2,076) | 1,919 (1,878 to 1,940) | 0.96 |
| `select`: decode every output | 1,942 (1,854 to 1,949) | 1,910 (1,910 to 1,940) | 0.98 |
| `selectAddresses`: decode candidates only | 1,079 (1,072 to 1,112) | 1,090 (1,085 to 1,092) | 1.01 |
<!-- /register -->

**Result.** The fold is within 2 percent of the loops for `size`, `select` and `selectAddresses`, and 4 percent faster
for `scan`. `all`, which builds the whole set, is 8 percent slower: a map that grows by `+` allocates where the builder
filled one in place. Collecting `all` into a `List` and building the map once was tried in a separate set of six JVMs
against the same loops and took 15 percent longer (7,619 against 6,645 ms), so it was not kept. `all` serves only
whole-set reads (the HTTP query endpoint's projection, LocalStateQuery's whole-UTxO query, `StateDump` and the snapshot
import); the block path reads by key. Every scan keeps the fold.

## 47. The collections of the Haskell state: builders against immutable forms (#650)

**2026-10-31.** Reading a Haskell node's ledger state (`HaskellLedgerFile.decode`: the state of a Mithril or Haskell
snapshot import) decodes its sets and maps with `Wire.setOf` and `Wire.mapOf`, and its sequences with `Wire.seqOf`. Each
puts the elements into a builder of its collection as they are read, with no vector of the nodes (section 12). The builders
are mutable, so AGENTS.md keeps them only with a measured benefit; the immutable forms were timed against them.

**The forms.** Each differs from the builders (`Wire.scala` of `5db649a9f`) only in `Wire.scala`:

- immutable fold: each element inserted into the immutable `SortedSet` or `SortedMap` (`+`, `updated`), and each element of
  a sequence appended to a `Vector` (`:+`);
- collect, then build: the elements of a set or map appended to a `Vector`, then `SortedSet.from` or `SortedMap.from`, which
  insert them into the immutable tree one by one; sequences as in the fold;
- `Vector` append for sequences only, sets and maps keeping their builders;
- list for sequences only: each element put in front of a `List`, which is turned into a `Vector` once at the end; sets and
  maps keep their builders.

**Input and harness.** `HaskellStateDecodeBench` (storage tests) on the `state` file of the node 11.1.3 preprod snapshot at
slot 136,511,997 (32,511,416 bytes, SHA-256 `7ecbc47624c3e6f6059520dc31f230971e77da8bd9ac29e894cdeaefb2002e96`, byte-identical
to the pinned original, section 25). One JVM per run decodes the file five times to warm up, then nine times timed, with a
collection before each; it reports the median time and the median allocation of its thread (`ThreadMXBean`). Every decode is
checked by writing the state again and comparing the bytes with the file, outside the timing. Each form was run against the
builders in its own set of six JVMs in ABBA order (two sets for the `Vector` append); the table gives the median of the JVMs
and their range. Euler (Intel Core Ultra 9 285K, WSL2), shared with other containers (load average 1.3 to 3.2); Docker image
`scarab-jdk:local` `sha256:8f00ab6b6e1eed196c2b835bfd558507dfc29fac3153936fa38596248d7ebc76`, JVM 25.0.4.1+1-LTS,
`--cpuset-cpus=8-11 --memory=10g`, `-Xms6g -Xmx6g -XX:+UseG1GC -XX:ActiveProcessorCount=4`.

<!-- register:table perf-53.t1 -->
| Form against the builders | JVMs | Builders, ms (range) | Form, ms (range) | Form / builders | MB allocated per decode, builders / form |
|---|---:|---:|---:|---:|---:|
| Immutable fold (sets, maps, sequences) | 6 + 6 | 361 (323 to 415) | 465 (433 to 523) | 1.29 | 645 / 1,295 |
| Collect, then build (sets and maps) | 6 + 6 | 377 (311 to 408) | 470 (444 to 484) | 1.25 | 638 / 1,389 |
| `Vector` append (sequences only) | 12 + 12 | 341 (310 to 382) | 371 (313 to 420) | 1.09 | 635 / 651 |
| List (sequences only), kept | 6 + 6 | 352 (312 to 377) | 327 (307 to 367) | 0.93 | 635 / 642 |
<!-- /register -->

**Result.** Both immutable forms of the sets and maps take 25 to 29 percent longer and allocate twice as much: an insertion
into the immutable tree copies the path to the new node, where the builder links it in place. `SortedSet.from` and
`SortedMap.from` insert in the same way unless they are given a sorted collection, so collecting first only adds the vector.
`setOf` and `mapOf` keep their builders, under a `@SuppressWarnings` that cites this section. For sequences, appending to a
`Vector` copies its last block on every append and measured 9 percent slower over twelve JVMs each, but the list measured no
slower than the builder, so `seqOf` uses the list.

## 49. Reading a decoded document: functional against imperative navigation, sharing and caching (#650)

**2026-10-31.** A decoded CBOR document (section 48) is read through views: `Document.valueAt` builds a container's
children, and `entriesAt` and `itemsAt` iterate over a map's entries and an array's items, each found in the index as
it is asked for. The children are collected into an array, and the iterators keep their place in mutable fields
(`Iterator.next` advances state). A decode shares the equal values it reads (credentials, hashes) through a table of its
own (`Interns`, section 12), found through a one-entry cache; and every `Bytes` keeps its hash and the first four of its
bytes once computed (sections 2 and 16). Each was measured against its functional form on the decode that uses them
most: the Haskell ledger state of a snapshot import.

**Input and harness.** `HaskellStateDecodeBench` and the preprod `state` file of section 47 (slot 136,511,997, 32.5 MB),
with the JVMs, limits and environment of that section (load average 2.1 to 3.0). The codec's classes are swapped, the
rest is the same. Each row is one set of twelve JVMs in ABBA order, against the codec before #650's rewrite or against
the kept form; the table gives the median of the six of each and their range.

<!-- register:table perf-55.t1 -->
| Form measured | Against | Against, ms (range) | Form, ms (range) | Form / against, time | MB allocated, against / form |
|---|---|---:|---:|---:|---:|
| Children and iterators with `Iterator.unfold` | the codec before #650's rewrite | 353 (327 to 376) | 445 (384 to 467) | 1.26 | 642 / 1,182 |
| Children with `Iterator.unfold`, iterators kept | the codec before #650's rewrite | 363 (326 to 407) | 446 (418 to 493) | 1.23 | 641 / 1,094 |
| Children as a `List`, iterators kept | the codec before #650's rewrite | 363 (330 to 433) | 404 (351 to 417) | 1.11 | 645 / 875 |
| Iterators with `Iterator.unfold`, children kept | the codec before #650's rewrite | 358 (322 to 414) | 393 (338 to 425) | 1.10 | 642 / 734 |
| Children and iterators kept, the helpers functional | the codec before #650's rewrite | 343 (326 to 412) | 391 (345 to 401) | 1.14 | 641 / 642 |
| The same, a second run | the codec before #650's rewrite | 375 (350 to 406) | 353 (326 to 404) | 0.94 | 642 / 644 |
| Sharing table as an immutable `HashMap` | the kept form | 369 (320 to 381) | 617 (580 to 647) | 1.67 | 644 / 995 |
| `Bytes` without its cached hash and prefix | the kept form | 366 (340 to 385) | 403 (391 to 413) | 1.10 | 678 / 673 |
| Last-table cache of the sharing as an `AtomicReference` of an `Option` | the codec before #650's rewrite | 320 (311 to 333) | 372 (350 to 379) | 1.16 | 644 / 689 |
| The kept form | the codec before #650's rewrite | 362 (332 to 400) | 384 (360 to 398) | 1.06 | 643 / 653 |
| The kept form, a second run | the codec before #650's rewrite | 390 (366 to 400) | 373 (354 to 400) | 0.96 | 647 / 642 |
<!-- /register -->

**Result.** Where the two forms allocate the same, their medians differ by -6 to +14 percent in either direction from
run to run: each JVM settles at about 330 or about 400 ms. Time differences within that spread are not results; the
allocation is, and so is a time difference whose ranges do not meet. Building the children as a `List` or with
`Iterator.unfold` allocates 36 to 71 percent more, and `Iterator.unfold` for the two iterators 14 percent more, for no
gain in time. An immutable `HashMap` as the sharing table takes 1.67 times as long with 1.54 times the allocation;
`Bytes` without its cached hash and prefix takes 10 percent longer (the ranges do not meet); the last-table cache as an
`AtomicReference` of an `Option` allocates on every lookup and took 16 percent longer. Each of these keeps its imperative
form, under a `@SuppressWarnings` that cites this section. The helpers of the walk (`Index.firstAt`, the argument reader,
the payloads of a chunked string), the pairing of a map's children and the loops of `Bytes` are functional, and the
codec as kept reads the state as the old one did.
