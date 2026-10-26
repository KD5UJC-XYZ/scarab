<!-- SPDX-License-Identifier: Apache-2.0 -->
# Era rules: the differential harness and the epoch boundary

Part of [Performance](../performance.md). The tables and listings are copied from the register [`docs/data/performance-log.json`](../data/performance-log.json) by `scripts/render_performance.py`.

## 9. The differential harness: what the conversion costs (J2.2)

`--rules differential` (and `PreviewDifferentialMain`) converts the old state to the mirror (`toMirror` of the retired `StateMirror`) at
the sampling points: the first block, the first block of every epoch and era, every 2,160th block. While the new side is
not ported the conversion is the whole cost. Linux x86-64, JDK 21, 8 CPUs, `-Xmx16g`, commit of the J2.2 harness,
`PreviewDifferentialMain <K1 db> 72` (21 epochs of the Preview sync, each replayed from its snapshot and UTxO checkpoint, once
with the old rules alone and once with the harness):

<!-- register:table perf-09.t1 -->
| | |
|---|---|
| blocks replayed | 70,183 in 21 epochs (1, 73, 145, ..., 1441) |
| replay, old rules alone / with the harness | 305.4 s / 317.4 s (+3.9%) |
| conversions | 73; median 302 ms, p99 and maximum 1.39 s |
| conversion per epoch | median 1.13 s, maximum 5.15 s (epoch 1441: 4 conversions of a state of about 3.2 M UTxO entries and 112 k accounts) |
| eras fixture, every block sampled | 731 blocks, 0.40 ms median, 0.64 ms mean, 57.9 ms maximum per conversion |
<!-- /register -->

The cost grows with the state (121 ms at epoch 1, about 1.3 s at epoch 1441). Sampling is the design's D7 interval; a
conversion after every block is out of the question on a real chain (twenty thousand blocks an epoch). Memory: the new side's
states ride beside the old ones for the volatile window; while nothing is ported the harness keeps none.

## 10. The tick of every era: new rules against the old (J2.3)

`NewRules.tick` (the era-indexed `Tick`) runs beside `NodeRules.tick` (the old `OldTick`) at the eventful ticks and at the
sampling points of `--rules differential`. Linux x86-64, JDK 21, `-Xmx16g`, a host loaded to 25 (so absolute
times are pessimistic), `PreviewDifferentialMain <K1 db> 72`, 21 epochs, 70,183 blocks, 0 divergences. "Ticks" are the
compared ticks of the epoch (four or five), summed:

<!-- register:table perf-11.t1 -->
| epoch | old ms | new ms | seed (conversion) ms |
|---|---|---|---|
| 73 | 9 | 50 | 1,089 |
| 289 | 29 | 121 | 2,637 |
| 649 | 79 | 185 | 4,874 |
| 1009 | 198 | 311 | 8,947 |
| 1297 | 191 | 358 | 9,189 |
| 1369 | 1,010 | 2,492 | 22,977 |
| 1441 | 997 | 2,540 | 28,528 |
<!-- /register -->

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

<!-- register:table perf-12.t1 -->
| run | the blocks | old, mean per block | new, mean per block |
|---|---|---|---|
| `PreviewDifferentialMain` epoch 701 (Conway, protocol 10), no sampling | all 3,068 are Conway | 2.14 ms | 2.75 ms |
| the forks run (snapshot indices 1, 2, 20, 21, 644, 645, 741, 742, 1288 to 1291, sampling every 500 blocks) | the Conway blocks of 41,474 | 0.64 ms | 0.95 ms |
| stride 72, 21 epochs, sampling every 500 blocks | the Conway blocks of 70,183 | 1.26 ms | 1.65 ms |
<!-- /register -->

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

## 15. The epoch boundary of the new rules against the old, on the largest real state (J2.9 step 0)

The gate of design `ledger-mirror-step2.md` 2.3 is `epochBoundary` p99 within +25% of the old rules. J2.8 left it unmeasured. A
copy of the Preview harness (`PreviewDifferentialMain`, snapshots 1400 to 1449 of the K1 run: epochs 1401 on, protocol 11, about
112,000 accounts, the largest real state held locally) times the tick of the first block of each epoch, which crosses the boundary,
on the old rules (`NodeRules.tick`, the flat state) and on the new (`NewRules.tick`, the mirror), each from its own state, in one
JVM (`-Xmx12g`, G1, a host at load 6 to 9). The run was stopped after 23 of the 50 boundaries because the result was
decisive; the database was the K1 copy mounted read-only.

<!-- register:table perf-16.t1 -->
| 23 boundaries | old ms | new ms | new / old |
|---|---|---|---|
| mean | 271 | 406 | 1.50 |
| p99 (the maximum at this count) | 389 | 568 | 1.46 |
| max | 389 | 568 | 1.46 |
<!-- /register -->

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

<!-- register:table perf-17.t1 -->
| 200 ticks, concurrent runs | before | after | after / before |
|---|---|---|---|
| new rules, mean ms | 704 | 497 | 0.71 |
| median | 696 | 445 | 0.64 |
| p99 | 1,467 | 974 | 0.66 |
| the old rules in the same JVM, mean / median / p99 | 479 / 479 / 790 | 481 / 468 / 870 | |
| **new / old**, mean | 1.47 | **1.03** | |
| new / old, median | 1.45 | 0.95 | |
| new / old, p99 | 1.86 | **1.12** | |
<!-- /register -->

Two rounds of the same builds run one after the other (one JVM at a time) agree: new / old mean, median, p99 of 1.38, 1.35, 1.49
and 1.36, 1.41, 1.74 before; 0.96, 0.87, 1.08 and 1.01, 0.99, 1.23 after (the build of those rounds differs from the final one
only in the tool). The gate (p99 within +25% of the old rules) holds in all four, with little room in the worst (1.23). The first
tick of a snapshot is slower than the later ones in both builds (556 against 422 ms best of four after); the best of four is
0.7 to 0.9 times the old rules.

**After the rebase onto J2.9** (the old rules, the flat state and `seed` of the retired `Relations` gone; the tool reads the K1 snapshots with the
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
not a new performance comparison. See [Storage migrations](../storage-migrations.md).

## 17. Per-block ledger application in late Alonzo (#133)

**The question.** The mainnet follower in late Alonzo (epoch 327, block 7.0 M) applied about 26 blocks/s on 1.5 of 12 cores:
`scarab_block_apply_seconds` averaged 31 ms per block (33,520 blocks; 69% between 16 and 32 ms), and the Haskell node does this
era at about 13 ms per block. Thread dumps had put the one busy thread in `PreConwayRules.apply`.

**Profile** (JFR, 10 minutes, `settings=profile`, 45,793 execution samples, read only on the running follower; the deep
stacks of the rules are cut at 64 frames, so shares are by the frames that remain; JFR also samples few threads per interval,
so these are shares of samples, not of wall time). `jdk.ThreadStart` counted 133,584 thread starts in the 600 s.

<!-- register:table perf-18.t1 -->
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
<!-- /register -->

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

<!-- register:table perf-18.t2 -->
| per transaction | before | after |
|---|---|---|
| `PreConwayRules.apply` as a whole (measured before J2.9 removed it) | 30.5 us (1.36 ms/block) | 20.3 us (0.90 ms/block) |
| sum of the inputs and outputs' values | 9.5 us | 2.6 us |
| sum of the real output values | 4.3 us | 1.2 us |
| sort of the inputs | 0.5 us | 0.1 us |
| script integrity hash | 1.9 us | 0.5 us |
| phase 2 of the 45 recorded Preview cases (90 scripts, median of 40 passes) | 69.9 ms | 55.9 ms |
<!-- /register -->

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

