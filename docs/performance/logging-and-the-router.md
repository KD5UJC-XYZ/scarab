<!-- SPDX-License-Identifier: Apache-2.0 -->
# Logging and the router

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 54. The log router's one sink lock (#233)

`LogRouter` writes every sink under one `Mutex` (`node/src/main/scala/xyz/kd5ujc/scarab/node/log/LogRouter.scala`): a line is admitted per sink and kind, rendered once per format, written to every sink that takes it, and `tell` returns only after the writes. That is deliberate — a line is whole, the order is the same in every sink, and the last lines of a run are out before the process exits — but it is also one lock in front of every fiber that logs. The audit asked whether it can stall application fibers at saturation, and what would have to be true to replace it with per-sink queues.

**What was measured.** The router's own `tell`, against two configurations of a file sink: `file`, which admits every line, renders it as JSON and appends it; and `silenced`, which admits nothing, so `tell` returns after the route filter without taking the lock or reading the clock. The line is the per-block `Extended` event, built through the router's own `Traced` instance; at about 288 bytes with the fixture's small block number and slot, it is shorter than a mainnet line. Each round issues 240,000 lines, split evenly over one, four or sixteen fibers that start after the clock is read. A figure is the faster of two timed rounds after one warm-up, and the whole harness ran twice (run 1 and run 2 below).

<!-- register:table perf-60.t1 -->
| Sink configuration | Writer fibers | µs per line, run 1 / run 2 | Lines a second, run 1 / run 2 |
|---|---:|---:|---:|
| `file`: admitted — render, append, lock and clock | 1 | 2.31 / 2.17 | 433,564 / 460,331 |
| `file`: admitted — render, append, lock and clock | 4 | 15.23 / 16.11 | 65,649 / 62,061 |
| `file`: admitted — render, append, lock and clock | 16 | 16.13 / 15.91 | 62,005 / 62,844 |
| `silenced`: route filter only, no lock | 1 | 0.016 / 0.016 | 61,228,160 / 64,195,720 |
| `silenced`: route filter only, no lock | 4 | 0.007 / 0.005 | 153,739,423 / 198,950,206 |
| `silenced`: route filter only, no lock | 16 | 0.005 / 0.004 | 213,605,229 / 274,349,991 |
<!-- /register -->

Each figure is a round's wall time divided by the lines it issued. With one fiber that is the cost of one `tell`. With several fibers it is a line's share of the aggregate, so the four- and sixteen-fiber rows give the router's throughput under contention, not the latency of one call.

**Setup.** The harness is `tools/src/main/scala/xyz/kd5ujc/scarab/node/log/LogRouterBenchMain.scala` at 122786612, a test-scope main outside `check`. Both runs used one container of `scarab-jdk:local` (the pinned JDK) with `--cpuset-cpus=0-7`, so the JVM saw eight processors (the `HOST` line), on an otherwise idle host, and the sink was emptied before every round, so each `DIGEST` is one round's bytes. Command, inside the container described in [CONTRIBUTING.md](../../CONTRIBUTING.md#build-docker-only):

```sh
bash scripts/sbtw "tools/runMain xyz.kd5ujc.scarab.node.log.LogRouterBenchMain"
```

<!-- register:listing perf-60.c1 -->
```text
HOST processors=8
METRIC config=file writers=1 lines=240000 us_per_line=2.306 lines_per_s=433564
METRIC config=file writers=4 lines=240000 us_per_line=15.232 lines_per_s=65649
METRIC config=file writers=16 lines=240000 us_per_line=16.128 lines_per_s=62005
DIGEST config=file bytes=69119303
METRIC config=silenced writers=1 lines=240000 us_per_line=0.016 lines_per_s=61228160
METRIC config=silenced writers=4 lines=240000 us_per_line=0.007 lines_per_s=153739423
METRIC config=silenced writers=16 lines=240000 us_per_line=0.005 lines_per_s=213605229
DIGEST config=silenced bytes=0
HOST processors=8
METRIC config=file writers=1 lines=240000 us_per_line=2.172 lines_per_s=460331
METRIC config=file writers=4 lines=240000 us_per_line=16.113 lines_per_s=62061
METRIC config=file writers=16 lines=240000 us_per_line=15.912 lines_per_s=62844
DIGEST config=file bytes=69119229
METRIC config=silenced writers=1 lines=240000 us_per_line=0.016 lines_per_s=64195720
METRIC config=silenced writers=4 lines=240000 us_per_line=0.005 lines_per_s=198950206
METRIC config=silenced writers=16 lines=240000 us_per_line=0.004 lines_per_s=274349991
DIGEST config=silenced bytes=0
```
<!-- /register -->

An earlier listing in this section came from a version of the harness that ran every writer count on one fiber, on a host whose processors were not recorded. These runs replace it; its figures are not comparable with them.

**The reading.**

- One fiber writing alone pays 2.2 to 2.3 µs a line, about 430,000 to 460,000 lines a second. That is the render, the append, the lock and the clock together. The harness does not separate them.
- Four or sixteen fibers writing at once get 62,000 to 66,000 lines a second between them, about a seventh of what one fiber gets alone. Concurrent writes therefore add about 13 to 14 µs to each line's share of the wall time. Four and sixteen fibers give similar throughput.
- These runs do not say how those 13 µs divide between handing the lock over, the blocking pool's scheduling (the append inside the lock is a `Sync.blocking` call) and the render on shared cores. The `silenced` rows do rise with the fibers, from 61 to 274 million lines a second, but they leave out all the work of the admitted path, so they do not isolate the lock either.

**At soak load.** `devnet.py soak --seconds 480` on the same host passed: 253 blocks applied, all 23 ledger comparisons matched, and no errors. The Scala node wrote to stdout, the router's default sink. It wrote 421 lines over the 490 seconds between its first line and its last, all event kinds together. That is 0.86 lines a second on average, with a maximum of 34 in one whole-second bin (the second after it started). On a mainnet follower, the per-block line alone is one line per block, and section 19 of [the mainnet follower page](mainnet-follower.md) records 22 to 27 blocks a second over epochs 326 to 328. Lines of other kinds were not counted there. The contended file-sink throughput of about 62,000 lines a second is over 1,800 times the soak's busiest second, and over 2,000 times that mainnet per-block rate. These comparisons do not measure the stdout sink's capacity or how long a fiber waits.

**The decision.** Keep the single lock: no measured load comes near the contended file-sink throughput. A fire-and-forget queue per sink would give up a line being written before `tell` returns. Keeping one order across every sink would need coordinated enqueueing, and keeping synchronous completion would need an acknowledgement; neither alternative was benchmarked. The threshold chosen for looking again is a sustained rate above 620 lines a second, which is one percent of the contended file-sink throughput and not a measured capacity, or the router appearing among the hot frames of a follower profile. Per-sink queues would not remove all serialisation on the console paths: `System.out.println` and `System.err.println` also take the stream's own monitor.

**What the figures do not say.** They do not split the 2.3 µs between the render, the append, the lock and the clock. They do not measure the throughput of the stdout and stderr sinks; the harness times the file sink, and the soak used stdout. They do not cover the render cost of the other event kinds: only the fixture's `Extended` event was timed. And they report time, not allocation.
