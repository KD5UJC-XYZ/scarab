> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Sum6 supplied-message verification record

## Frozen JVM aggregate

The 0.19 implementation passed **543/543 Scala tests**: all 531 existing tests
plus ten core Sum6 tests and two bounded CLI/API-boundary tests.
`scalafmtCheckAll` and runtime classpath generation passed. Full output is in
`sum6-build-verification.log`; focused compilation/tests are in
`sum6-focused-verification.log`.

The new corpus test reconstructs four authentic originals and all 17,332 synthetic
controls from immutable-admitted TSV files. It checks exact outcomes and work
traces, including 3,752 leaf calls. Authentic positives remain restricted to
relative periods 28, 29 and 35. Every-period spy tests validate branch routing,
not additional authentic signatures. Focused corpus execution took approximately
six seconds; concurrent verification used a bounded four-thread pool and 32 calls.

Other controls cover null checked components and null byte storage; exact lengths
and 0..63 bounds; pre-copy array message cap; empty and maximum messages; input
and output defensive ownership; private construction and unavailable copy/seam
paths; each commitment short-circuit; unchanged supplied messages; repeatability;
nonfatal provider failures, typed leaf errors and fatal propagation.

The first focused run deliberately injected a `LinkageError` through a MUnit
`intercept` helper, which itself only intercepts nonfatal exceptions. That harness
therefore reported a failure and skipped subsequent tests. It was corrected to
catch the exact fatal type explicitly and assert throwable identity at both hash
and leaf boundaries, matching the existing operational-certificate harness.
Production exception handling did not change. The retained initial log SHA-256 is
`e189bb604df4b223305efd392cce34e697b502a587dc4e0280c1b94f7af5332c`.
The corrected focused and full aggregate runs passed.

## Execution profile and scope

All JVM acceptance processes use explicit `-XX:ActiveProcessorCount=4` sizing
(`JAVA_TOOL_OPTIONS` propagated to child JVMs, and the existing sbt launcher flag).
Existing per-case timeout guards and production deadlines are unchanged.
This is not a demonstrated fix for earlier JVM startup stalls or TCP resets.
Existing controlled localhost regressions are retained; no public relay endpoint,
new source download, native runtime or new signing/key-generation operation was
used. No `unsafeRunSync` was introduced.

The corpus's historical expected outcomes are source-derived tree observations
with a pinned native Ed25519 leaf, not an independent full KES oracle. The current
portable projector verifies immutable admission, exact projections and tree work
traces without executing native code. See [contract and evidence limits](sum6-supplied-message.md).

## Serial portable and runtime gates

All **23 serial scripts** exited successfully under the same explicit APC4
profile: the 20 previous CLI/source/runtime gates plus the new Sum6 positive
projector, 21 negative admission controls and seven direct-JVM CLI cases.
The complete script sequence and output are in `sum6-regression-verification.log`.
Existing strict TCP and KeepAlive gates used their controlled local/preflight
checks only. The runtime inventory still admits 26 native-free jars; all five
negative dependency checks passed. Baseline per-command evidence files were
preserved rather than overwritten by the rerun.

The portable projector checks exact source/fixture identities and the final
manifest, including retained Amaru parameter context sources. CLI controls prove
that the exact original TSV works in an independent working directory, while
extra arguments, mutation, edited manifest, oversize and missing input fail.
Independent source/API/provenance review reported no blocking findings after the
null-storage and test-admission hardening and final test-harness correction.
Fresh release-archive acceptance is a separate packaging gate.

## Later fresh-archive result and test-only patch

The fresh v0.19 archive subsequently failed two existing 30-second corpus test
budgets, despite the main-checkout result above. Those results are retained as
separate evidence. Version 0.19.1 partitions exhaustive work without changing
crypto, corpus coverage or timeouts. See [failure and batching record](corpus-batch-verification.md).
