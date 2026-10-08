> Public export note: historical run logs and raw diagnostic output are retained only in the private research archive. Their mentions below document prior evidence; they are not bundled public artifacts or newly executed public checks.

# v0.20 body commitment verification

## Current status

Scala compilation, formatting and **423/423 focused tests** passed. The full
aggregate then passed **1042/1042 tests**, preserving all 646 v0.19.1 cases and
adding 396 body-commitment/CLI registrations. Runtime classpath generation passed.
All 25 serial old/new CLI/projector/runtime gates now have passing completions:
the first 10 in the original attempt and the remaining 15 in the resumed attempt.
The original attempt failed at gate 11; that failure and its bounded diagnosis
remain explicitly preserved below. Pristine v0.20 archive acceptance is still a
separate pending gate, not established by these main-checkout results.

Offline Python projection has reproduced 36 original body hashes, 35 original
sizes, all 16 additional raw mainnet-source samples, 347 synthetic edge outcomes
and five parser/resource tests. The pinned source Git blobs and copied test
resources are also checked by the portable verifier. The toy Conway size negative
is preserved. No exact-release reference Haskell body decoder was run.

The execution profile is `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`, JDK21
and the existing sbt launcher sizing. No MUnit, protocol or CLI timeout was
changed. The complete records are `body-commitment-focused-verification.log`
(SHA-256 `42569e3db0eae30a09be2d8d5ef6523523c87a531c39f442a2857d6ba7abbf6c`)
and `body-commitment-build-verification.log`
(SHA-256 `65ffc632b1a5a0390b3036541d516e4e89240dc442241049e85c66d68b26e801`).
There were no compilation or test failures in these two runs.

The independent source/API/resource review found no blocker. It checked all six
era mappings, unchanged structural acceptance, exact original-byte bindings,
immutable constructors and the portable projector/source/resource-copy audit.
Requested hard-cap, cumulative-chunk and coordinated-header assertions were added
before compilation; they passed in both focused and aggregate runs.

## Reproduction

```
./scripts/sbtw scalafmtAll scalafmtCheckAll 'core/testOnly lab.chain.CardanoBlockIndexSuite lab.chain.PostByronIndexSuite lab.chain.CardanoBodyCommitmentSuite' 'app/testOnly lab.BodyCommitmentCommandSuite'
./scripts/sbtw check 'app/runtimeClasspathFile'
python scripts/verify-body-commitment-projector.py
python scripts/verify-body-commitment-cli.py
```

Run all previous CLI/provenance/runtime gates serially as well, preserving their
budgets and expected fixtures. Record Java processor sizing, actual test totals,
commands, logs and any failures separately. Verify a fresh frozen extraction
before declaring packaging accepted. Runtime/dependency inventory must remain
native-free under the existing narrow application profile; no dependency is added.

The acceptance claim is a bounded original-byte comparison against a supplied
header. It is not ledger/consensus/source authenticity, reference decoder parity,
a stronger acquisition policy, live interoperability or a complete validator.

## Preserved serial-attempt failure and bounded diagnosis

The first ordered serial run passed gates 1–10, then the unchanged
`verify-chain-fetch-cli.py` timed out at its path-traversal rejection case under
the existing 30-second subprocess guard. This failed attempt is retained in
`body-commitment-regression-attempt1-failure.log`; it is not relabelled success.
The launcher explicitly exported `JAVA_TOOL_OPTIONS=-XX:ActiveProcessorCount=4`,
corroborated by earlier child stderr. The old gate did not retain that failed
child's stdout/stderr/thread state, so its cause remains unproven.

A single isolated reproduction used the same malformed manifest path, explicit
APC4, unchanged 30-second deadline, captured stdout/stderr and a diagnostic SIGQUIT
thread-dump trigger at 20 seconds if still running. It correctly rejected with
exit 2 and `unsafe relative source path` in 10.274 seconds, before the diagnostic
trigger; no thread dump was therefore produced. Exact observations and diagnostic
code are `body-commitment-chain-fetch-diagnostic.json` and
`body-commitment-chain-fetch-diagnostic.py`. This result does not establish that
the first failure was a transient startup stall, or that APC4 cures prior stalls.

Independent source tracing confirms this input rejects in LocalConfig/LocalFiles
path preflight before opening a source or reaching block indexing/body checking.
Those production files and the old gate are unchanged. The full failed gate was rerun once as the first of the 15-gate suffix and passed
all 21 cases; the other 14 gates also passed, exit 0. The complete resumed record
is `body-commitment-regression-attempt2.log`. Together with the first 10 completed
gates in attempt 1, this covers all 25 planned scripts. It is not a claim that
the original uninterrupted serial attempt passed.

The new direct-JVM gate passed 25 cases, including six eras, the toy size negative,
separate mismatch outcomes, malformed input, independent working directories,
regular-file/symlink/FIFO preflight and repeat read-only checks. Across old/new
gates, 138 direct-JVM CLI/preflight cases passed. Runtime remains 26 native-free
jars; all five negative dependency-admission checks passed. Historical per-command
logs were restored unchanged, with this version's rerun evidence retained under
its own filenames. The original and resumed shell command lists are also retained.

Evidence digests:

- Initial failed attempt: `4207fbde333141aaed3cd702bb07d91b183add013dd82dc2e75cd16219a2a910`
- Successful resumed suffix: `632d99c453add3bf93040aa34b8191aeb776a20f469f8ca27e80dd1cf21efff5`
- Isolated diagnostic JSON: `ded2f26561e8c7e9eec559a231f109eedc0a76e7d9577e71f7b451a056ddd370`

No new dependency, public network request, signature/key creation, source identity,
fetch/store policy, expected vector or timeout change is part of this milestone.
