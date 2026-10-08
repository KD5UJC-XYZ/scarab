> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Durable restricted replay verification

Recorded 8 October 2026 UTC for 0.23.0. The final durable feature passes its complete
module and direct CLI checks. All project tests and all 31 scripts have passing
coverage across the retained attempts below. A clean uninterrupted final aggregate
or 31-script run is not claimed. Fresh-archive acceptance remains separate and pending. No external network access or publication was used, no new runtime
dependency was introduced, and no keys or signatures were generated. Existing
public fixture signatures are verified.

## Compilation and focused observations

- `./scripts/sbtw scalafmtAll 'ledgerRuntime/Test/compile' 'app/Test/compile'`
  passed. Initial compile log (private local evidence; omitted from this export).
- The first focused run executed all 148 then-present ledger-runtime cases:
  140 passed and eight cancellation assertions failed. All 50 independent-process
  cases passed: 48 abrupt `Runtime.halt` boundaries, exclusive process locking and
  the runtime/classpath profile. Preserved first run (private local evidence; omitted from this export).
- The eight failing tests asserted `Outcome.Canceled` immediately after a terminal
  masked call. Their state/reopen assertions had not yet been reached. Production
  publication code was unchanged. The corrected tests add an explicit cancelable
  caller continuation and confirm the cancellation request cannot finish while
  publication is paused; a separate test permits a terminal success or cancellation
  acknowledgment and checks coherent state either way.
- The corrected focused run passed 99 NIO tests and both app CLI unit tests, including
  all eight subsequent state/reopen checks. Correction log (private local evidence; omitted from this export).
  The separate-process matrix was unchanged. An explicit terminal-Outcome diagnostic
  was added afterward for the final aggregate; its observed result belongs below.

The process suite observed two available processors and maximum heap 536870912 bytes
in its forked runner, and two processors / 268435456 bytes in the child probe. All
48 halt cases completed within their fixed 30-second child deadlines. Individual
case wall times ranged from 3.420 to 25.166 seconds in the initial run; these include
cold JVM/class loading/crypto, filesystem and test work. They are not storage
latencies or throughput measurements. The whole initial ledger-runtime command took
499 seconds. The per-case MUnit guard remains 120 seconds. A timed-out owned child
has a separate five-second forced-termination wait. The later output-drain fix adds
a five-second normal drain join and, if needed, another five-second join after
forced close/interruption; cleanup is not represented as one combined five-second
limit. No production deadline or 30-second child deadline was changed.

## Direct CLI timeout and bounded diagnosis

The first standalone direct-JVM store CLI audit passed its first seven cases, then
hit its unchanged 30-second process timeout on repeated-transaction rejection.
The failure is retained in the original log (private local evidence; omitted from this export).
The original process's partial output and thread state were not retained by that
first version of the harness, so its cause is not established. The harness now
prints partial output on any later timeout and records per-case wall time.

A single bounded diagnostic recreated the exact fixture checkpoint, accepted
transaction, deterministic head and repeated-transaction request. It retained
its inputs separately and captured only its own processes. Initialization passed
in 5.017 seconds, first apply in 13.529 seconds, and duplicate rejection in
23.175 seconds with exit 1. The selected head bytes were unchanged. A SIGQUIT at
20 seconds showed runnable class loading under the trace parser; this is evidence
about the reproduction and is not a proven explanation of the original timeout.
The diagnostic still used the same 30-second process deadline and did not change
production behavior. Summary (private local evidence; omitted from this export),
measurements and process samples (private local evidence; omitted from this export),
thread dump (private local evidence; omitted from this export),
expected rejection (private local evidence; omitted from this export).

## Final aggregate

The first full attempt passed core 658, VM 13, network 100, network-runtime 52 and
ledger 96 tests. It then passed 148/149 durable tests and stopped on one child-process
timeout at ObjectDirectoryForced/apply. All 99 NIO tests passed, including the eight
cancellation checks; the terminal diagnostic printed Succeeded(Right(snapshot)).
Preserved aggregate (private local evidence; omitted from this export). The same unchanged
30-second halt case passed a targeted reproduction in 8.305 seconds; that pass does
not establish the earlier timeout's cause. Targeted retry (private local evidence; omitted from this export).
An earlier attempted filter used glob syntax where MUnit expects a regular expression;
it ran no cases and is retained as a command error, not a production failure, in
its log (private local evidence; omitted from this export).

Independent review identified a real test-harness risk: waiting for child exit before
draining its merged output can deadlock if diagnostic output fills the pipe. The
harness now drains concurrently, retains at most 64 KiB, counts/discards overflow,
retains timeout command/output/cleanup diagnostics, and uses bounded own-child and
drain cleanup. A dedicated child emits more than 1 MiB and must exit successfully;
its regression checks retained size and truncation accounting. This removes a possible
harness deadlock; the discarded output from the original timeout means it cannot
retrospectively prove that mechanism caused it. This mechanism does not explain the
separate Python communicate-based CLI timeout described above.

The final-harness aggregate executed all 1,294 tests: core 658, VM 13, network 100,
network-runtime 52, ledger 96, ledger-runtime 150 and fetcher 87 all passed. App
passed 137/138 and hit a 30-second MUnit timeout in the unchanged tagless VM
orchestration test (33.362 seconds observed). The preceding full app suffix had
passed all 138, including that exact test in 1.071 seconds. This is passing coverage
across preserved runs, not a clean uninterrupted aggregate. The 150 durable tests
passed together on the final concurrent-drain harness. Final-harness aggregate (private local evidence; omitted from this export),
targeted harness and full fetcher/app suffix (private local evidence; omitted from this export).
The same-bound targeted VM reproduction passed the selected test in 5.985 seconds
(one executed, four ignored). Targeted VM retry (private local evidence; omitted from this export).
This establishes the current result without explaining the original timing failure.

The explicit `check` alias compiles all eight test modules, then executes all eight
module test tasks serially. All fixed test/child deadlines and production behavior
remain unchanged by these timing observations.

## Serial gates and archival acceptance

The complete new durable-store CLI gate passed all 19 direct-JVM cases under the
unchanged limits. It checks initialization, reopen, exact state/undo preservation,
atomic rejection, duplicate/stale/ABA fences, rollback, explicit reapplication,
input admission and corrupt-head refusal. Final direct gate (private local evidence; omitted from this export).
This was a separate run, not part of an uninterrupted 31-script pass.

All 31 scripts have complete passing coverage across these preserved scopes:

- Attempt 2: script 1 passed its 13 cases. Attempt 1 had stopped at a 30-second
  legacy unknown-option JVM timeout; an identical bounded diagnostic returned the
  expected exit 2 in 4.013 seconds. Attempt 1 (private local evidence; omitted from this export),
  attempt 2 (private local evidence; omitted from this export),
  diagnostic (private local evidence; omitted from this export).
- Attempt 3: scripts 2–10 passed. Attempt 2 had stopped because network-demo returned
  exit 2 with `ERROR: 3 seconds`, its unchanged application deadline, rather than a
  launcher timeout. An identical invocation passed all four checks in 6.010 seconds
  overall with internal deadlines intact. Attempt 3 (private local evidence; omitted from this export),
  network diagnostic (private local evidence; omitted from this export).
- Attempt 4: scripts 11–15 passed. Attempt 3 had stopped on a 30-second legacy
  corrupt-store inspection timeout. Script 11's full 21-case retry passed; scripts
  12–15 include block-fetch/fixture audits, all 26 runtime jars and five negative
  dependency checks. Attempt 4 (private local evidence; omitted from this export).
- Attempt 5: scripts 16–30 passed in one clean suffix. Attempt 4 had stopped on the
  legacy TCP command's help invocation under its original 20-second process guard.
  The suffix retained that bound, completed all 15 scripts and exited 0.
  Final suffix (private local evidence; omitted from this export).
- Script 31: all 19 durable-store CLI cases passed separately, as linked above.

Passing retries do not establish the earlier timing failures' causes. Neither
production deadlines nor existing script guards were increased. The retained
[full command](restricted-replay-store-serial-command.sh) and each suffix command
record exact scope. Machine-readable receipts (historical local evidence; not distributed)
map every script to its complete passing run, while retaining failed attempts.

There are 29 retained rerun artifact versions for 26 distinct historical files.
Every retained version matches its recorded SHA256; every historical original was
restored byte-for-byte and rechecked. Original fixture and pure/acquisition source
bytes are unchanged. Tested source hashes (historical local evidence; not distributed)
cover all 15 relevant source/build/script files. Independent final source/claims
review passed: all 15 source hashes, all 31 gate-script hashes, all 29 retained
artifact versions and all restored originals were verified against their receipts.
The reviewed source-manifest SHA256 is
`9723535aa11c24fea13b74f06d7637edafcdfeba8b9f0f5b3e9309072867d7df`.
Review metadata is recorded in the machine-readable receipt before the local commit.
The captured JVM thread dump retains its original whitespace verbatim; one narrow
`-text -whitespace` attribute covers that diagnostic file. No production/test bytes
or previously recorded evidence were rewritten for this packaging-only change.

Fresh-archive execution is an independent acceptance gate and has not been run for
this version. No source publication, push, deployment or live-chain operation occurred.

## Claim boundaries

These tests cover local JVM process interruption, injected exceptions, cancellation,
serialization and checked recovery on the test filesystem. They do not establish
hardware power-loss behavior, hostile-filesystem-race resistance, complete ledger
or consensus correctness. Original acquisition admission/storage semantics, the
pure projection profile and genuine archived fixtures are unchanged. The missing
two-success dependent fixture remains missing; none is synthesized for this slice.
See the [complete storage/API contract](restricted-replay-store.md).
