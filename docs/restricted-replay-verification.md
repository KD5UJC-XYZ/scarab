> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# Restricted replay verification

## Status

Verified in the main checkout for 0.22.0 on 2026-10-08 UTC. Formatting, first
compilation and all 39 focused tests passed. The full formatted aggregate passed
all 1,138 tests. All 30 serial CLI/provenance/runtime gates passed in one
uninterrupted first attempt. **Fresh-archive acceptance remains a separate gate.**

Logs: `restricted-replay-focused-verification.log` and
`restricted-replay-build-verification.log`. Runs use JDK21 with explicit
`JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`; original test/CLI deadlines are
unchanged. Sbt log clock labels use UTC-05:00; orchestration dates are UTC.

Aggregate module totals are 13 VM, 93 ledger, 52 network-runtime, 100 network,
658 core, 86 fetcher and 136 app tests. The new focused suites are 32 ledger and
7 app tests. No source corrections were needed after the independent isolated
review and its assembled-state cap fix.

## Checks completed before integration

- Deterministic offline projector generation and `--check`, using pinned archive,
  sequence, decoder, parameter and source-assertion inputs
- 23 portable/reproduction/tamper projector checks
- Independent source review of private construction, same-state predicate ownership,
  atomic failure, checked undo, revision fencing, CLI resources and evidence hierarchy
- Concrete resource-closure correction: an admitted 65,534-item checkpoint could
  previously produce 65,540 items after a genuine setup. Assembled state now passes
  the same bounded decoder before publication; the exact regression is included.

The archive-based expectations cover both final output maps byte-for-byte. Fee
accumulator expectations remain source-derived arithmetic. No fresh Haskell ledger,
new native executable, network access, key creation or signing was performed.

## Commands run under exclusive build ownership

```sh
./scripts/sbtw scalafmtAll 'ledger/testOnly lab.ledger.RestrictedReplaySuite' 'app/testOnly lab.RestrictedReplayCommandSuite'
./scripts/sbtw check 'app/runtimeClasspathFile'
python3 scripts/project-restricted-replay-fixtures.py --check
python3 scripts/verify-restricted-replay-projector.py
python3 scripts/verify-restricted-replay-cli.py
```

The exact 30-script list is retained in `restricted-replay-regression-command.sh`;
its completed output is `restricted-replay-regression-attempt1.log`, exit 0. No
retry, altered expectation, deadline change, dependency change or production
correction occurred in this acceptance run. All 27 previous gates and three new
replay gates passed. This slice adds no dependency or mutable runtime store.

- New direct-JVM replay CLI: 19 cases passed
- Portable replay reproduction/tamper checks: 23 passed
- Existing block-evidence CLI: 52 unchanged cases passed
- Existing body-commitment CLI: 25 unchanged cases passed
- Runtime inventory: 26 native-free jars; all five negative admission guards passed
- All 13 fixture SHA256SUMS manifests passed, logged in
  `restricted-replay-fixture-verification.log`

Historical CLI/runtime diagnostic files were restored byte-for-byte. The 26
changed rerun variants are retained under `restricted-replay-rerun-artifacts/`.
No old verification evidence was silently replaced.

Independent review found one final packaging-only issue: nine pinned text source
dependencies lacked byte-preserving Git attributes. Narrow `-text` attributes now
protect those exact files from CRLF checkout conversion. Source bytes remain
unchanged; attribute checks, source hashes and projector reproduction pass in
`restricted-replay-attribute-verification.log`. No JVM rerun is claimed for this
attribute/documentation-only change. The independent reviewer also exercised
`git -c core.autocrlf=true cat-file --filters` for all nine dependencies and verified
unchanged hashes. Final integrated source, log and portability review passed.

Final tested production source hashes:

- `RestrictedReplay.scala`: `f9fb054c8391d3c79195bf502511b834d04af915f839ac42538496b98f36d17d`
- `RestrictedReplayCommand.scala`: `08cbfe874c8b3106fef261939fb861f78dfbb30c10ace420745cb4f0cad7d468`
- Profile SHA256: `add3a990f26a7a0948485db668dc6fca15fa902137ef3cf12f1580830825b46f`

## Explicit non-claims and future gates

No persisted replay store, concurrent writer owner, cancellation/crash boundary,
reopen/recovery or hardware durability test is present. No successful dependent
multi-transaction archived fixture is invented. The selected traces establish
accepted setup followed by rejected dependency, branch rollback, exact output
restoration and no partial batch publication.

Module-label correction in 0.22.1: ledger/network labels above were previously
swapped when reading concurrent summary lines. The 1,138 total and all original
logs are unchanged. See [the later correction and execution-policy evidence](restricted-replay-sort-key-fix.md).
