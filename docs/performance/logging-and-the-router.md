<!-- SPDX-License-Identifier: Apache-2.0 -->
# Logging and the router

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 54. The log router's one sink lock (#233)

`LogRouter` writes every sink under one `Mutex` (`node/src/main/scala/xyz/kd5ujc/scarab/node/log/LogRouter.scala`): a line is admitted per sink and kind, rendered once per format, written to every sink that takes it, and `tell` returns only after the writes. That is deliberate — a line is whole, the order is the same in every sink, and the last lines of a run are out before the process exits — but it is also one lock in front of every fiber that logs. The audit asked whether it can stall application fibers at saturation, and what would have to be true to replace it with per-sink queues.

**What was measured.** The router's own `tell`, against two configurations of a file sink: `file`, which admits every line, renders it as JSON and appends it; and `silenced`, which admits nothing. The line is the per-block `Extended` event, built through the router's own `Traced` instance, so its size is the size the node actually logs. Each round writes 240,000 lines in total; a round's figure is the faster of two timed rounds, after one warm-up round.

<!-- register:table perf-60.t1 -->
| Sink configuration | Writers | µs per line | Lines a second |
|---|---:|---:|---:|
| `file`: JSON render and append | 1 | 12.19 | 82,059 |
| `file`: JSON render and append | 4 | 11.89 | 84,092 |
| `file`: JSON render and append | 16 | 11.76 | 85,005 |
| `silenced`: route filter only, no render, no lock | 1 | 0.05 | 19,844,198 |
| `silenced`: route filter only, no render, no lock | 4 | 0.05 | 19,946,989 |
| `silenced`: route filter only, no render, no lock | 16 | 0.045 | 22,051,687 |
<!-- /register -->

**What the listing is, and is not.** These figures came from the harness before the corrections described under Setup: it started a single fiber whatever the writer count, so the writer column records what the harness was asked for, not what it ran. The six rows are one sequential fiber's cost, repeated after successive warm-ups, and the spread from 12.19 to 11.76 µs across them is warm-up, not contention — contention at saturation is **not** among these figures. The `silenced` rows are not the lock either: with no sink admitting, `tell` returns before the lock is taken and before any clock is read, so 45 to 50 ns is the route filter and a return, the floor under the other rows rather than the lock's share of them.

**Setup.** The harness is `node/src/test/scala/xyz/kd5ujc/scarab/node/log/LogRouterBenchMain.scala`, a test-scope main outside `check`. It ran in one JVM (JDK 25.0.4.1, the pinned toolchain) on the build host, unpinned and without a cgroup CPU cap, with the rest of the host idle. A file sink appends to a temporary file with rotation set beyond the round, so no rotation happens inside a timing. As committed, the harness reads the clock and then starts one fiber per writer, so the starts fall inside the timing, and it empties the sink before every round, so a digest taken after a round is that round's bytes. The protocol is smaller than the house one: one warm-up and the faster of two timed rounds, where the house protocol is three warm-ups and five reps with each side's span reported. There is no old side and new side to span here — one configuration is timed, not compared — so the extra rounds would buy a tighter interval around a figure whose signal is a 12 µs render and append over a 45 ns floor; the cost is that each figure is a best of two, which is stated under the reading. Neither the writer counts nor the digest above were re-measured after that change, so the digest above covers the three rounds — a warm-up and two timed — that the earlier harness appended to one file.

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

**The reading.** What the file sink costs a fiber that writes alone is 12.2 µs a line, and that cost is the work under the lock — the render, the append, the lock itself and the clock. The 45 to 50 ns route-filter floor, under half a percent of it, excludes all four rather than separating them. The digest settles the sink's size — 207,357,801 bytes over the 720,000 lines that three rounds appended, so an average of about 288 bytes a line — this `extended` record plus the newline, whose length moves with the digits of the clock instant — and 82,000 lines a second at 288 bytes is about 24 MB/s of appends, so for a fiber writing alone the append, not the lock, sets the ceiling.

Against a soak: the per-block line is one line per block, and a sustained mainnet follower applies 22 to 27 blocks a second over epochs 326 to 328 (26.9, 22.2 and 22.4 by epoch in section 19 of [the mainnet follower page](mainnet-follower.md), whose summary sentence rounds the span to 24 to 27), so the per-block lines alone would be about twenty-five lines a second — about 3,300 times below the single-fiber figure, three and a half orders — and a fiber would spend about 0.27 to 0.33 ms of every second inside `tell` on those lines. That is a floor on a soak's line count and not the count: the router also carries every other event kind, and no run here counted them, so the margin is a bound on the headroom rather than a measured one.

**What the number does not say.** Contention: no row above had two fibers inside `tell` at once, so none of them says what sixteen writers cost. A soak: `devnet.py soak --seconds 480` needs a Docker host and did not run for this change. The other event kinds' render cost: `Extended` is the largest line the per-block path writes, and the rest are smaller. Allocation: the harness reports time, not bytes, and the rendering allocates per line whatever the lock does. Nor is the lock the only serialisation on the stdout and stderr paths: `System.out.println` and `System.err.println` take the stream's own monitor, so per-sink queues would not remove that one. The spread between the two timed rounds: the faster is reported and the slower was not kept, so each figure is a best of two.

**When to revisit.** Keep the single lock. At the per-block rate a soak writes — twenty-five lines a second, a floor on its total and not its total — a fiber spends about 0.3 of a millisecond of every second inside `tell` at the single-fiber cost, and a line arrives every forty milliseconds, so a fiber waits at most one `tell` and the lock is not what would stall it at that load — and per-sink queues would give up the two properties the router promises, one order shared by every sink and a line written before `tell` returns, for a gain no measurement here shows. The contention run is the open item, not the decision: as committed the harness starts one fiber per writer, and one run on a gate host would fill in the writer column. The threshold to reopen the decision: a sustained rate above about 820 lines a second — one percent of the 82,059-lines-a-second single-fiber figure, and about one percent of one core spent in `tell` — or a follower profile in which the router appears among the hot frames. At that threshold the sink still has about a hundredfold headroom.
