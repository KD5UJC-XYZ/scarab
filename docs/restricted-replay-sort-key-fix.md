> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Restricted replay sort-key correction, 0.22.1

## Preserved failure and bounded diagnosis

The fresh 0.22 ZIP aggregate failed the new 4096-entry next-state-bound test with
MUnit's unchanged 30-second timeout (reported elapsed time 42.753 seconds). Other
tests passed; the parent had not started serial scripts. The complete failure is
preserved in `restricted-replay-sort-key-diagnostics/fresh-022-aggregate-failure.log`.
The passing 0.22 main-checkout aggregate and 30-script acceptance remain historical
evidence for their exact source, not acceptance of this patch.

One unchanged focused reproduction in the same frozen 0.22 source root passed the
exact same test in 11.585 seconds with the original 30-second guard. That does not
explain away the aggregate failure or establish an environmental cause. The bounded
reproduction is retained as `unchanged-focused.log` in the same diagnostics folder.

A separate, isolated public-input probe compared the original and cached sorting
expressions over the same 4096 checked entries. It counted 88,030 original key
computations versus exactly 4096 cached computations and asserted identical order.
Observed times were 16.320 seconds and 0.201 seconds respectively. Original ran
first, so JIT/warmup and scheduling were uncontrolled; these timings are contextual,
not a controlled benchmark or proof of the earlier timeout's cause. The deterministic
key-call reduction is the robust evidence. Probe source and output are retained.

## Minimal correction

`encodeOutputs` now decorates each immutable entry with its fixed `(id.hex, index)`
key once, sorts those cached keys with the same tuple ordering, and emits the same
original key/output framing. The package-private generic collection helper returns
only ordinary entries; it cannot construct or authorize replay state/evidence.
The production caller supplies its fixed key, with no pluggable verifier.

There is no change to accepted states, output bytes, state/profile identities,
diagnostic priority, fees, immutable checks, decoder closure or the 4096-entry cap.
No production timeout is increased. The separately authorized test-watchdog split
below changes one test timing boundary explicitly. No dependency or crypto
implementation is changed.
Initialization reuse and earlier next-state count rejection remain possible separate
optimizations; neither is included because they are unnecessary to remove the
proven repeated sort-key work and an earlier rejection could change diagnostics.

New deterministic regressions check exactly one key evaluation per entry, equivalent
ordering, independently assembled exact bytes for mixed IDs and indices
0/23/24/255/256/65535, and fixed prepatch profile/checkpoint/state identities.
The original 4096-boundary and near-item-cap tests remain unchanged.

## Declared test resource policy

The first patched default aggregate failed five unchanged tests across core, VM
and fetcher. Replay passed all 35 tests, including the unchanged 4096 boundary at
5.796 seconds. The complete failed attempt is `restricted-replay-sort-key-aggregate.log`.
The failures were the eight-witness test (34.652 seconds, 30-second guard),
`addInteger-01` (50.480 seconds, 30-second guard), a retained Draft03 helper corpus
(75.684 seconds, 30-second guard), a KeepAlive outgoing-budget case (19.333 seconds,
existing five-second guard), and a Shelley resume case (30.243 seconds, 30-second
guard). No production guard or expected result is changed. The later, explicitly authorized
test-watchdog split is documented below.

Read-only effective sbt inspection confirmed all seven projects used Test/fork=false
and Test/parallelExecution=true, with global All=4 and forked-test-group=1 but no Test
limit. The wrapper fixes ActiveProcessorCount=4, Xms256m and Xmx2g. A nearby warning
reported 35.4% GC time in a 17-second interval; later passive five-second host samples
observed 66.3% exposed aggregate CPU steal ticks. The cgroup counters were unavailable
in the observation namespace. These are contextual observations, not proof of every
timeout's cause; the preserved logs and raw samples distinguish them from evidence
about the sorting algorithm.

The reproducible default `check` now compiles all seven test modules explicitly
before seven explicit sequential module test commands, sets `ThisBuild / Test / parallelExecution := false`, and adds
`Tags.limit(Tags.Test, 1)` to existing global restrictions. Explicit module command sequencing prevents project-level task overlap; the false
parallelExecution settings serialize suites, while preserving CPU/forked-group restrictions,
heap, APC4 and all cases. The specifically authorized watchdog split below is
reported separately; no production deadline changes. Test-internal concurrency assertions
remain unchanged. The normal README command still invokes this default profile.
This test-only policy does not claim stability of the earlier parallel configuration.

The first tag-fenced aggregate passed all 1,141 tests but its JUnit timestamp
semantics did not establish whole-module exclusion. That pass is retained separately
as `restricted-replay-sort-key-tag-fenced-check.log`. The final default therefore
uses explicit module test commands instead of relying on aggregate tag scheduling.

Effective before/after settings and verification logs are retained with this patch.
All fourteen fork/parallel booleans are false and Test limit 1 is present after the
change. Seven explicit compile phases precede the first test suite.

The old 0.22 module breakdown had ledger/network labels swapped when reading
concurrent summaries. Correct totals are 93 ledger and 100 network, with the
1,138 overall count unchanged. This patch adds three ledger regressions, bringing
ledger to 96 and the planned complete total to 1,141; original logs remain intact.

## Scripted-budget watchdog split

The first explicit sequential check passed core 658, VM 13, network 100,
network-runtime 52, ledger 96 and 85 fetcher tests, but the existing outgoing-budget
case exceeded its five-second IO watchdog (7.818 seconds reported). App was not
reached because the command stopped on failure. This is preserved as
`restricted-replay-sort-key-explicit-check-failure.log`; serialization is not
claimed to have cured all timing failures.

One unchanged isolated case then passed, with 5.070 seconds reported for the whole
MUnit test, followed by all 136 app tests. That overall duration includes setup
outside the inner IO watchdog. The retained log is
`restricted-replay-sort-key-remaining-module.log`; this does not establish the
cause of the failed run or an uninterrupted default-check pass.

The reviewed, explicitly authorized split preserves the same actual
KeepAliveTcpDirectRangeSource→runOwned outgoing-budget path in two tests:

- A deterministic TestControl test uses the correctly bound selection/source
  identities and an append-forbidden in-memory SegmentStore. The existing
  five-second watchdog now measures virtual protocol time. It asserts the same
  outgoingWireBudget result, writes=0, append calls=0 and empty records.
- The actual Nio test retains real temporary-directory creation, locked-store
  allocation/recovery/force, the same result reason, writes=0 and reopened persisted
  records=0, plus cleanup. It runs under the existing MUnit 30-second guard.

The old end-to-end five-second watchdog included filesystem setup, force and cleanup;
that timing boundary is intentionally split, not described as unchanged. No real
filesystem work is put under virtual time. All production timeouts and budgets are
unchanged. Separate existing runOwned stalled-store/recovery-deadline coverage also
remains unchanged. This adds one test, so the complete expected total is 1,142
(fetcher 87, ledger 96, all other module counts unchanged).

## Acceptance status

Final main-checkout patch verification passed on 2026-10-08 UTC:

- Normal default `check`: 1,142 tests passed in the explicit module sequence,
  with 658 core, 13 VM, 100 network, 52 network-runtime, 96 ledger, 87 fetcher and
  136 app tests. Formatting and runtime-classpath generation passed.
- Focused replay/CLI checks: 42 passed; focused watchdog-split suite: five passed.
- Pinned replay projector reproduction plus 23 portable/tamper checks passed.
- All 19 direct-JVM replay CLI cases passed.
- Prepatch profile, checkpoint and accepted A-state identities match exactly.

The final normal-command log is `restricted-replay-sort-key-default-check.log`;
its executable command record is `restricted-replay-sort-key-default-command.sh`.
Replay gates are in `restricted-replay-sort-key-replay-gates.log`. All prior
failures/intermediate passes remain separately named above; the final successful
run does not erase them or establish their complete cause.

The 4096-boundary case took 2.275 seconds in the final run. That observation is
not a portable latency promise or controlled speedup claim. The deterministic
one-key-per-entry property, exact output ordering and identity regressions are the
algorithmic acceptance evidence.

Final tested hashes:

- `RestrictedReplay.scala`: `f8f95e87b3927aa6b8e23b415a2de45e7cf9a56382477e67e5bd90d4a0c4497f`
- `build.sbt`: `8cc56d5906a4ea7f7dc3ee517559af6e8f6038a55ca794fbea00b30c553488c5`
- `KeepAliveTcpDescriptorSuite.scala`: `24920198116ae4b5663ceeeb0051edbedd2722bc38c7cedca13e3e7386a2b896`

Independent final source, log, build-policy and watchdog-split review passed.
Fresh-ZIP acceptance and the
complete 30-script list remain separate parent gates. The old 30-script run is
not reattributed to 0.22.1.
