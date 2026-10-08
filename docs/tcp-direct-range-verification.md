> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# TCP direct-range acceptance evidence

Release: 0.16.0. Execution date: 2026-10-08 UTC. JDK 21, Scala 3.3.8,
Cats Effect 3.6.3. Final aggregate `scalafmtAll check app/runtimeClasspathFile` passed: **455/455 tests**,
including all 414 pre-existing tests and 41 added tests. All 16 selected offline/local
CLI, fixture, provenance and runtime verifiers passed (95 direct-JVM CLI cases in
total, including four new TCP help/preflight cases). The runtime inventory remains
26 jars; all five negative dependency-admission checks passed.

## Execution boundary

Only finite controlled IPv4 localhost acceptors and IPv6 loopback `::1` were used.
No public DNS, external endpoint, dev-host, HTTP fallback, reference node startup,
live relay interoperability check, ledger execution or consensus validation was
performed. The implementation can connect to an explicit numeric endpoint when
invoked by its user; capability does not imply external execution evidence.

## New local gates

- Numeric address parsing, canonical IPv6 identity, malformed/ambiguous/unspecified/
  multicast/mapped addresses and transport hard caps
- Deterministic callback partial and zero I/O, synchronous registration failures,
  EOF, read/write/connect cancellation and deadlines, admission-wait cancellation,
  late connect completion, immediate close without direction permits. Queue admission
  is witnessed by negative semaphore counts; real read registration is witnessed
  after the JDK registration returns, not inferred from sleeps
- Cleanup-failure tests preserve the exact primary error, report secondary finalizer
  failure through the owned runtime reporter, and never turn failing cleanup after
  successful use into success
- Real nonreading localhost peer: sustained awaited writes, small socket buffers,
  finite attempted-byte ceiling, write timeout and closed connection; owned group
  and fixed executor termination asserted after release
- Real IPv6 loopback binding/connect/cleanup: passed on this environment, not skipped
- Strict descriptor and CLI preflight, UInt64/UInt32 boundaries, actual bounded
  provenance hash, changed filenames, duplicate/unknown fields, canonical optional
  pin order, impossible/out-of-range/duplicate pins and complete point-list checks
- Real TCP CLI handler runs for Shelley, Allegra and Babbage: original bytes unknown
  to preflight, literal independent wire scripts, fragmented messages (including
  one-byte fragmentation), exact stored bytes, observed SHA256, full and partial resume
- BatchDone withheld with an explicit synchronization signal: no objects before
  completion; wrong magic/version/query, protocol 2/8, direction, wrapper and length,
  missing/short/extra/duplicate/reordered bytes, EOF and NoBlocks fail closed
- Independent subset pins and full-pin pure policy checks; unmatched and mismatched
  pins fail. Same-header synthetic body mutation passes first structural-only
  unpinned acquisition but fails committed overlap replay without replacing objects
- Connector spy proves malformed input and changed resume endpoint/budgets open no
  connection; real acquisition cancellation preserves an empty checkpoint
- Virtual-time acquisition bound includes waiting for the transport before session
  allocation. Whole-deadline errors have typed TCP budget mapping; local fixture
  compatibility retains its existing error semantics

Existing shared-core tests also cover one-MiB framing, retained suffix rejection,
state/whole/trickle deadlines, append cancellation and locked atomic store recovery.
They remain inherited evidence for the same extracted core, not separate assertions
that arbitrary relays or bodies are valid.

## Reproduction

```sh
./scripts/sbtw scalafmtAll check app/runtimeClasspathFile
python3 scripts/verify-chain-fetch-cli.py
python3 scripts/verify-block-fetch-cli.py
python3 scripts/verify-post-byron-fixtures.py
python3 scripts/runtime-inventory.py
python3 scripts/verify-runtime-inventory.py
python3 scripts/verify-tcp-direct-range.py
```

The final build log is `tcp-direct-range-build-verification.log`; portable audit and
CLI regression output is `tcp-direct-range-regression-verification.log`. Cleanup
observation is bounded but no hard real-time guarantee is asserted for arbitrary
native JVM/OS or filesystem operations. Network/body/header-signature/ledger/
consensus/Mithril/reference replay truth flags remain false.
