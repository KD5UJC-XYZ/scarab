<!-- SPDX-License-Identifier: Apache-2.0 -->
# Peers, connections and the network path

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 18. The cost of an extra hot session (G1.1)

The design asks what one more hot peer costs (D5): a hot session runs ChainSync, BlockFetch and TxSubmission2, so each one
is a header-validating client with its own pipelined reads.

**Devnet, steady state.** `devnet.py relay --seconds 300` with the Scala node following 1 and then 3 reference nodes (the
governor keeps every `--peer` hot), the container sampled every 5 s by `docker stats` (2 CPUs, 2 GB; the first minute dropped):

<!-- register:table perf-19.t1 -->
| hot peers | CPU mean (median) | resident memory mean (max) | blocks applied | relay scenario |
|---|---|---|---|---|
| 1 | 8.9% (6.6%) of the container | 275 (279) MiB | 194 | passed |
| 3 | 7.7% (6.4%) | 279 (291) MiB | 199 | passed |
<!-- /register -->

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

## 20. The cost of the peer metrics callbacks (G1.2)

The hot sessions report to `PeerMetrics` on the per-header and per-block path: one `Peer.Observation.HeadersArrived` per ChainSync
batch (the slots of its roll-forwards, one arrival time) and one `BlocksArrived` per BlockFetch request, each a single
`Ref.update` of an immutable value (two 180-slot sorted maps and two small registries). Mainnet applies about 25 blocks a second.

**Micro-benchmark** (`node/Test/runMain xyz.kd5ujc.scarab.node.PeerMetricsBench`, one JVM, three interleaved rounds, steady
state: full boards, 20 peers; median ns after warm-up; the machine was loaded by a devnet and two followers). The first round
is cold JIT, the last two are the figures:

<!-- register:table perf-21.t1 -->
| what | per header or block |
|---|---|
| a header, a batch of 128 (a pipelined round far behind), pure | 320 to 396 ns |
| a header, a batch of 16 | 330 to 416 ns |
| a header on its own (at the tip) | 478 to 556 ns |
| the same through `Ref.update` in IO, batch of 128 / of 1 | 333 to 343 ns / 508 to 542 ns |
| a block, a range of 100 / on its own | 307 to 393 ns / 781 to 866 ns |
| `hotScores` for 20 peers, once per governor pass that demotes | 21 to 26 us |
<!-- /register -->

At the tip a block and its header cost under 2 microseconds in all, against the 40 ms between mainnet blocks (0.005%); in a
catch-up at 300 blocks a second under 1 microsecond a block, against 3.3 ms (0.03%).

**Preview from genesis, base against final** (the method of section 6, D8): the build before the stage (`5f3b6174`) and this
one, started together, each `follow --genesis-dir fixtures/preview/genesis --peer preview-node.play.dev.cardano.org:3001`
in a container of 2 CPUs and 6 GB, `-Xms2g -Xmx3g`, G1 with a 20 ms pause goal, 660 s, a fresh database each, on a host
that also ran a devnet and `check`:

<!-- register:table perf-21.t2 -->
| | base | final |
|---|---|---|
| blocks applied after 120 s / 300 s / 600 s | 47,182 / 107,450 / 181,216 | 48,916 / 109,217 / 182,692 |
| blocks applied in 660 s | 188,373 | 188,979 (+0.3%) |
| reconnects, errors, stalls | 0 | 0 |
<!-- /register -->

The final build is not slower (the difference, +0.8% to +3.7%, is within the noise of two runs on a shared host); the callbacks
do not add a cost that can be measured on the apply path. One hot peer is all the public Preview topology offers, so the
update contends with no other session; the contention of 20 sessions on the one `Ref` was not measured (a compare-and-set that
retries on a 20-peer value of this size costs microseconds, once per batch).

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

<!-- register:table perf-23.t1 -->
| payload / credit window | baseline | fiber implementation |
|---|---:|---:|
| 1,400 bytes / 64 | 6.04 us | 7.71–8.62 us |
| 400 bytes / 64 | 5.23 us | 6.92–7.03 us |
| 65,000 bytes / 8 | 211.58 us | 202.73–223.06 us |
<!-- /register -->

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
[peer-selection design](../design/peer-selection.md), sections7d/7e.


## 37. Coordinated BlockFetch service model (#148)

2026-10-20, JDK21 Docker, `SCARAB_BENCH=1 node/testOnly *FetchCoordinationBenchSuite`,
fixed TestControl seed 13. The production coordinator serves 24 advertised 32KiB
blocks through 20ms and 400ms RTT virtual peers, fixed 4Mbps serialization and at
most three outstanding requests per peer. Each peer has one serialized body writer;
parallel requests cannot multiply its link bandwidth. The comparison baseline is an
explicit idle-worker/serial-request model, not a run of the historical executable.

<!-- register:table perf-42.t1 -->
| Policy | All blocks available (virtual ms) | Fully drained (virtual ms) | Duplicate bytes |
|---|---:|---:|---:|
| Modeled idle/serial baseline | 1324.288 | 1324.288 | 0 |
| Bulk, target 1 | 1186.432 | 1186.432 | 0 |
| Bulk, target 2 | 1186.432 | 1186.432 | 0 |
| Deadline, target 1 | 1592.864 | 1592.864 | 0 |
| Deadline, target 2 | 1592.864 | 1710.720 | 655360 |
<!-- /register -->

Bulk's preferred-peer handover briefly has two active peers even with target 1,
as permitted upstream. The default deadline target stays on the fast peer; target 2
can duplicate traffic without improving this workload's tip time. Base payload is
786432 bytes. Per-peer peaks are three requests and 393216 estimated bytes, within
the GSV window including complete-block overshoot; all credit drains. These are
controlled service-model results, not host throughput, a mainnet forecast or an
argument to change the reference defaults. They support retaining default target 1
and exposing `MaxConcurrencyBulkSync`/`MaxConcurrencyDeadline` for measured tuning.
The former real two-peer observations above remain historical evidence for the old
range-per-idle-connection policy.

The workload was rerun unchanged on final runtime `e67e5117e` (2026-10-21 UTC),
with identical numbers above; benchmark-log SHA-256
`57050133c4f648ae8e6798cc753130fea0bf718713066797c9098e76708ae732`.
The separate private 300-second relay passed: 53/58 samples within one block,
peak lag seven, equal final tips and transaction inclusion. Twelve reconnects
were observed (eleven KeepAlive client-idle timeouts and one no intersection);
[#292](https://github.com/KD5UJC-XYZ/scarab/issues/292) tracks classification.
This finite passing gate is not a zero-reconnect or old/new throughput comparison.


## 38. Genesis integration observations (#33)

The private stage runs on Euler (2026-10-21 UTC) used frozen runtime `3819a43af`,
Docker JDK21 and cardano-node 11.1.3. These are bounded compatibility and lifecycle
observations, not a production sync-throughput benchmark or a comparison with an
older Scarab build.

<!-- register:table perf-43.t1 -->
| Scenario | Observed result |
|---|---|
| Genesis trusted-local-root relay, 300 seconds of load | All 59 tip samples within one block; equal final tips, 266 transaction inclusions and reverse duplex headers 1→148; zero errors or reconnects. Initial observation also permits the native 120-second no-intersection repromotion delay. |
| Genesis soak, 600 seconds | 318 block applications across epochs 0–12; 30/30 ledger comparisons matched, zero errors/reconnects; 231 metric series validated on both endpoints. |
| Genesis V5 restart | Both processes stopped in 0.7 and 0.6 seconds; snapshot restoration passed. |
| Genesis seven-era e2e | 1,706 block applications, 66/66 Shelley–Conway ledger comparisons matched, zero validation/fatal errors or reconnects. Byron UTxO is checked at handover because the reference CLI cannot query a Byron ledger directly. |
<!-- /register -->

Commands and association requirements are in the [Genesis design](../design/genesis.md).
The local-root relay disables peer sharing to match native `LocalRootsOnly` honest
availability; it does not change production thresholds. Earlier unrestricted-HAA
and premature-observation failures remain failed observations. The relay’s strict
final-tip, lag, transaction inclusion and continued inbound-following assertions
are unchanged. Completed run databases and owned containers were removed after
retaining compact source, input and result receipts. The subsequent negotiated-Mux correction on `43d940871` passed independent source
review, 8,379 standard tests (16 existing optional-input skips), extended fuzz and
two corrected-build 300-second relays: Genesis/no peer sharing and Praos/peer sharing.
Both had 59/59 samples within one block, equal final tips, 270 transaction inclusions,
zero errors/reconnects and continuing reverse sessions (headers 1→160 and 2→168).
These later runs use the same assertions; the earlier observations above remain
bound to `3819a43af`.


## 39. Received-forest selection and idle decisions (#309 review)

Measured on Euler on 2026-10-22 UTC, Docker JDK 21.0.11 / Scala 3.3.8 / sbt 1.10.7,
using [SelectionReviewBenchmark](../../node/src/test/scala/xyz/kd5ujc/scarab/node/SelectionReviewBenchmark.scala)
at `b507f895b`. Reproduce with
`bash scripts/sbtw "node/Test/runMain xyz.kd5ujc.scarab.node.SelectionReviewBenchmark"`
in the repository's JDK container. These are synthetic header forests: a linear
path or independent one-header forks. Each condition has 20 warmup and 100 measured
calls to production `AvailableBlocks.candidates` and `CandidateSelection.project`.
Admission keeps paths containing the last hash; full rescans model startup or LoE
reconsideration. Neither condition runs ledger checks, body IO or network traffic.

<!-- register:table perf-44.t1 -->
| Forest | Originals | Mean admission elapsed / thread CPU | Mean full rescan elapsed / thread CPU |
|---|---:|---:|---:|
| Linear | 128 | 0.398 / 0.399 ms | 0.803 / 0.756 ms |
| Independent forks | 128 | 0.674 / 0.670 ms | 0.612 / 0.613 ms |
| Linear | 1,024 | 1.885 / 1.878 ms | 3.706 / 3.710 ms |
| Independent forks | 1,024 | 2.017 / 1.996 ms | 4.796 / 4.778 ms |
| Linear | 4,096 | 10.055 / 10.015 ms | 10.117 / 10.085 ms |
| Independent forks | 4,096 | 11.895 / 11.855 ms | 16.620 / 16.564 ms |
<!-- /register -->

The scan has measurable cost and grows with the retained forest. These observations
cover the stated input sizes, not a latency bound, whole-node throughput, production
memory use or a speed comparison with Haskell. JVM compilation and collection can
influence this single-process microbenchmark. Native consensus `82ecba329`
`ChainSel.hs:115–211` starts selection from the immutable-root forest, caps initial
paths at `k` with LoE enabled, and sorts eligible differences at line 1143. Receipt
caps would discard still-selectable postponed forks, so this review introduces none.
The received index and processed forest retain raw originals in memory until durable
immutable progress permits collection; this does not promise a disk-only body index's
memory footprint or bounded memory during a long LoE stall.

A separate real-storage control opens and resumes a selected suffix, acquires the
production selector, then reopens it again. The selected suffix stays unchanged and
selection performs zero additional ledger applications when it is already the native
winner. A different recovered winner still switches through its validated common
prefix. The control's 1.099-second test duration includes initial fixture/database
work and is not a restart-only latency measurement.

The native decision cadence remains 10 ms for Praos and 40 ms for Genesis. A controlled
test establishes that 100 unchanged refreshes do not wake the decision watcher.
Forced-refresh measurements, after 1,000 warmup and over 10,000 measured refreshes,
were 88.92 ms elapsed / 390 ms process CPU with zero peers and 367.04 ms elapsed /
940 ms process CPU with 32 peers. Process CPU includes parallel JVM compilation and
collection; these batches do not measure whole-node steady-state idle CPU. The
coordinator serializes input observations, reservations and dynamo demotion with GSM
phase publication. A controlled test blocks demotion, requests CaughtUp, and requires
demotion completion before the phase write can proceed.

## 23. Mux receive buffer (#356)

`Mux.channel.recv` kept the bytes of a message not yet decoded as one `Bytes` and appended every SDU with
`held ++ more`, which copies the whole buffer each time: a message of n bytes arriving in 16 KiB reads costs
about n squared / 32,768 byte copies. It is now a `ReceiveBuffer`: one array that doubles when full (amortised
constant time per appended byte), shown to the decoder as a window without a copy. This is the
performance-critical imperative exception of AGENTS.md: the mutation is local to one channel, whose `recv`
holds the channel lock, behind `append`, `view`, `consume` and `clear`; bytes once shown are never written
again. The decoder also runs once per doubling of the buffer (or when the queue is empty), not after each SDU,
so a decoder that rereads a long array is also linear overall. The handshake channel still decodes after every SDU.

`network/Test/runMain xyz.kd5ujc.scarab.network.MuxThroughputBench` gained `one message of N MiB`: one message
sent through two node-to-client muxes over an in-memory bearer; milliseconds from send to decoded, median of five
after a warm-up. Docker JDK 21.0.11, same container image and source for both builds, shared host with a load average
of about 20 on 24 processors (October 22 2026), so the absolute figures are pessimistic and the small-message rows
are noise of the same size as their difference.

<!-- register:table perf-45.t1 -->
| message | before (`held ++ more`) | after (`ReceiveBuffer`) |
|---|---:|---:|
| 4 MiB | 52 ms | 69 ms |
| 16 MiB | 507 ms | 210 ms |
| 32 MiB | 1,877 ms | 420 ms |
| 64 MiB | 10,109 ms | 882 ms |
| 1,400 bytes / window 64 | 57.9 us | 65.7 us |
| 400 bytes / window 64 | 43.5 us | 40.8 us |
| 65,000 bytes / window 8 | 680 us | 925 us |
<!-- /register -->

Before, doubling the message size multiplied the time by 4 to 5 (quadratic); after, by 2 (about 13 ms a MiB).
The small-message rows are within the spread this loaded host gave between runs of the unchanged code (the
section 22 figures for the same rows were 5 to 8 us on an idle host).

The capacity decision of `append` was then made a pure function in `Long` arithmetic (`end + incoming` could wrap
as an `Int` near the 2 GiB array limit). On the same loaded host the two builds ran back to back (load average about
45), baseline then change: 4 MiB 85.7 / 73.5 ms, 16 MiB 351 / 258 ms, 32 MiB 587 / 660 ms, 64 MiB 1,240 / 1,484 ms,
65,000 bytes 1,349 / 1,287 us; differences are within this host's run-to-run spread. The decision allocates nothing
unless the array grows.

Batching only covers SDUs that are already queued. When the queue runs dry after every SDU the decoder runs after
each, so a decoder that rescans its prefix still does quadratic work overall; the buffer removes the copying, not
that cost. An SDU that would overflow the buffer while batching is held until the complete message already buffered
has been decoded (`Mux.Config.receiveBufferLimit` lets a test use a small limit).

## 41. Mux stream decoder partial frames

`protocol.Mux.Decoder.feed` appended each feed's share of a frame to the payload received so far with
`payload ++ more`, copying it on every feed: a frame of n bytes fed in pieces of c bytes cost about
n squared / 2c bytes. The nightly fuzz target `mux-stream` feeds its input in pieces of 1 to 23 bytes derived
from the input's first eight bytes; when all of them are 0 modulo 23 the pieces are single bytes. The decoder now
keeps the parts of the active frame, newest first, and joins them once when the frame completes, as network-mux
0.11.0.0 `Network/Mux/Bearer/Socket.hs` `recvLen'` concatenates the chunks of one SDU; a carried part is compacted
so it does not pin the caller's read buffer. Production ingress (`network.Mux`, `readPayload`) already collects
parts and does not call `feed`.

Allocation on the test thread (`ThreadMXBean.getThreadAllocatedBytes`) for the decode loop of that target, Docker
JDK 21.0.11, October 25 2026; the fuzz budget is 24 MiB plus 2,000 bytes per input byte:

<!-- register:table perf-47.t1 -->
| input | before | after |
|---|---:|---:|
| 64 frames of 4,096 bytes fed a byte at a time (262,917 bytes) | 630.5 MB (2,398 B per byte) | 107.5 MB (408 B per byte) |
| 5,797 frames of 138 bytes, pieces of 1 and 16 bytes (800,016 bytes) | 41.6 MB | unchanged |
<!-- /register -->

The nightly failure on seed 20751332 was the target's own oracle, not the decoder: it joined the re-encoded frames
with a fold over `Bytes.++`, quadratic in the frame count (1.09 GB of the 1.12 GB measured for 3,980 frames). The
oracle now compares each frame with the input slice at its offset.
