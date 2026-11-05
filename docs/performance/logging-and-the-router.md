<!-- SPDX-License-Identifier: Apache-2.0 -->
# Logging and the router

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 54. The log router's one sink lock (#233)

`LogRouter` writes every sink under one `Mutex` (`node/src/main/scala/xyz/kd5ujc/scarab/node/log/LogRouter.scala`): a line is admitted per sink and kind, rendered once per format, written to every sink that takes it, and `tell` returns only after the writes. That is deliberate — a line is whole, the order is the same in every sink, and the last lines of a run are out before the process exits — but it is also one lock in front of every fiber that logs. The audit asked whether it can stall application fibers at saturation, and what would have to be true to replace it with per-sink queues.

**What was measured.** The router's own `tell`, driven by one, four and sixteen fibers writing at once, against two configurations of a file sink: `file`, which admits every line, renders it as JSON and appends it; and `silenced`, which admits nothing, leaving the lock, the admission test and the clock with no render and no write. The line is the per-block `Extended` event, built through the router's own `Traced` instance, so its size is the size the node actually logs. Each round writes 240,000 lines in total; a round's figure is the faster of two, after one warm-up round.

<!-- register:table perf-60.t1 -->
| Sink configuration | Writers | µs per line | Lines a second |
|---|---:|---:|---:|
| `file`: JSON render and append | 1 | 12.19 | 82,059 |
| `file`: JSON render and append | 4 | 11.89 | 84,092 |
| `file`: JSON render and append | 16 | 11.76 | 85,005 |
| `silenced`: lock, admission and clock only | 1 | 0.05 | 19,844,198 |
| `silenced`: lock, admission and clock only | 4 | 0.05 | 19,946,989 |
| `silenced`: lock, admission and clock only | 16 | 0.045 | 22,051,687 |
<!-- /register -->

**Setup.** The harness is `node/src/test/scala/xyz/kd5ujc/scarab/node/log/LogRouterBenchMain.scala`, a test-scope main outside `check`. It ran in one JVM (JDK 25.0.4.1, the pinned toolchain) on the build host, unpinned and without a cgroup CPU cap, with the rest of the host idle. A file sink appends to a temporary file with rotation set beyond the round, so no rotation happens inside a timing.

<!-- register:listing perf-60.c1 -->
```text
METRIC config=file writers=1 lines=240000 us_per_line=12.186 lines_per_s=82059
METRIC config=file writers=4 lines=240000 us_per_line=11.892 lines_per_s=84092
METRIC config=file writers=16 lines=240000 us_per_line=11.764 lines_per_s=85005
DIGEST config=file bytes=207357801
METRIC config=silenced writers=1 lines=240000 us_per_line=0.050 lines_per_s=19844198
METRIC config=silenced writers=4 lines=240000 us_per_line=0.050 lines_per_s=19946989
METRIC config=silenced writers=16 lines=240000 us_per_line=0.045 lines_per_s=22051687
DIGEST config=silenced bytes=0
```
<!-- /register -->

**The reading.** The lock is not what the path costs. With every line admitted, a line takes 11.8 to 12.2 µs whether one fiber writes or sixteen: throughput is flat across the writer counts, so sixteen fibers wait on the one lock without losing a measurable share, and the best figure is the sixteen-writer one. With nothing admitted — the same lock, the same admission test and the same clock read, with no render and no write — a line takes 45 to 50 ns, about 0.4 percent of the admitted case. The render and the append are the cost; the lock beside them is noise.

Each line is 864 bytes of JSON (the digest: 207,357,801 bytes over 240,000 lines), so 82,000 lines a second is about 71 MB/s of appends: the sink sets the ceiling, not the lock. Against a soak: the per-block line is one line per block, and the fastest follower record is 8.7 blocks a second (section 19 of [the mainnet follower page](mainnet-follower.md)), so a soak writes on the order of ten lines a second, four orders of magnitude below the single-writer figure. At ten lines a second a fiber spends about 120 µs of every second inside `tell`.

**What the number does not say.** The lock is not the only serialisation on the stdout and stderr paths: `System.out.println` and `System.err.println` take the stream's own monitor, so per-sink queues would not remove that one. The file sink is the path this measured, and it is the one the node runs with in a deployment.

**Not measured.** A soak run: `devnet.py soak --seconds 480` needs a Docker host and did not run for this change, so this is the router's ceiling, not a soak. The other event kinds' render cost: `Extended` is the largest line the per-block path writes, and the rest are smaller. Anything about allocation: the harness reports time, not bytes, and the rendering allocates per line whatever the lock does.

**When to revisit.** Keep the single lock. Per-sink queues would give up the two properties the router promises — one order shared by every sink, and a line written before `tell` returns — for a gain this measurement does not show, and a change of that kind is a behaviour change that only a soak scenario could justify. The threshold to reopen it: a sustained rate above about 850 lines a second, one percent of the measured single-writer rate and about one percent of one core spent in `tell`, or a follower profile in which the router appears among the hot frames. At that threshold the sink still has about a hundredfold headroom; below it the question does not arise.
