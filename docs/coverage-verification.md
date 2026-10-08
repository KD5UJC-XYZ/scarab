# Milestone 0.6.0 verification

Verification date: 2026-10-08 UTC. Local checkout only; no publication. All commands were run against the final Scala implementation. Optional native reference binaries were explicitly supplied from separate research custody, never bundled or added to the JVM runtime.

## Scala build

Command:

    ./scripts/sbtw scalafmtAll check app/runtimeClasspathFile

Passed 163 tests, zero failures/errors: 39 core, 45 ledger, 13 VM, 25 network, 41 app. This retains the 133-test v0.5.0 baseline and adds 27 coverage-boundary tests plus three app integration tests. Formatting check passed. No unsafeRunSync, signing, or key generation was introduced.

The first intermediate run exposed a test helper expecting empty inputs to survive decode; production already correctly returned `EmptySpendingInputs`. The test was corrected to assert that boundary. The final complete run above passed after that correction and the genuine signature-mutation test addition.

## Coverage fixture and predicate evidence

- `python3 scripts/project-coverage-fixtures.py --check`: two genuine Haskell-derived Conway PV9 cases, all immutable archive/member/source/license/parameter pins, raw offsets, fixed generated hashes, and complete final UTxO address/value projection verified.
- `python3 scripts/verify-coverage-projector.py`: deterministic double projection, immutable-source tamper rejection, byte/depth/duplicate-CBOR bounds and Python optimization refusal passed.
- `python3 scripts/verify-coverage-cli.py`: seven direct-JVM checks passed: genuine pair, independent working directory, changed fixture, editable adjacent manifest, oversize, missing fixture, extra arguments.
- Hardcoded CLI projection SHA256: `a876e7ffd7bd00881a8e001a4a73151680f4d604ec426910ac9434dc3ecec4b4`.
- Accepted event: required/provided hash `88028438394946279f9ed8d66d718679b82f26b75391cb6df8107c8f`; empty missing set.
- Rejected event: exact missing payment hash `3c875ce0f647bdcc64b70e62814680fbd939f8faf0203b98236ede7b`. Both actual provided signatures verify independently; both values conserve.
- Mutating only the accepted case's signature leaves required/provided/missing sets unchanged but returns `SignatureRejected`.

Independent read-only reviewer reproduced the original raw research projection, production importer/checker, all seven direct-JVM coverage cases and source-level boundaries. No unresolved review blocker remains.

## Preserved surfaces and runtime

All 40 direct-CLI regression cases passed: 13 codec/VM, four network, six balance, ten witness and seven new coverage cases. Previous projector checks retain four balance cases and 2,219 public witness cases/eight ledger signatures. The resolved application inventory contains 26 JVM jars with no excluded native artifacts or bundled native libraries. All three runtime negative checks passed (injected native file, wrong arithmetic hash and missing arithmetic artifact); the original classpath was restored and remains unchanged.

## Optional offline reference evidence

- Existing `reference-cli-tests.py`: all 14 mocked failure/success harness tests passed.
- Existing `reference-cli-checks.py`: six live offline checks passed, covering three exact transaction IDs and three Conway JSON views.
- New `check-coverage-public-key-hashes.py --cli PATH`: both raw public keys match CLI Blake2b-224 output. Binary SHA256 `0ac45e874599fac4ee6ca4fd9602c0ddb9854a62be5f365dfa425eff9f4bd0ed`, CLI 11.2.3.0 from node 11.1.3. `/bin/true` is rejected by digest before execution; `python3 -O` is rejected before input processing.
- Native invocation uses only `address key-hash` on published fixture verification-key envelopes, minimal environment without node socket settings, closed inherited descriptors, stdin disabled and 20-second timeout.

The pinned public sodium signature observations are retained historical evidence; this milestone's normal projector does not load sodium or rerun Haskell ledger tests. JVM signature verification does execute independently in Scala. Public-key hashes and decode/txid operations are separate evidence classes from full ledger validation.

No external peers, node startup, socket retry, transaction submission, productive key material, full transaction-validity claim, fresh Haskell ledger differential run, or complete ledger-state transition occurred. Existing network tests use only bounded localhost simulation.
