<!-- SPDX-License-Identifier: Apache-2.0 -->
# Fixture contracts

**Status: Current.**

Each logical corpus has a README describing its source, consuming tests, assertions and limitations.
Nested case directories belong to that corpus unless they have a separate contract. Module-local
`src/test/resources` corpora follow the same rule. Original native judgments and historical captures
must remain distinguishable from locally generated regression expectations.

The [fixture inventory](../docs/data/fixture-inventory.json) pins the exact payload paths and SHA-256
identities, including module-local resources. Run `python3 scripts/check_test_coverage.py` to check
identities, corpus documentation and the [comparison register](../docs/data/test-coverage-register.json).
An intentional fixture change updates its provenance and consuming assertions first; then
`python3 scripts/check_test_coverage.py --refresh-fixtures` records the reviewed identities. Refreshing
this inventory never regenerates oracle outputs or demonstrates that changed expectations are correct.

The register records scope, rationale, owner, tracking issue and resolution criteria. It cannot disable
a test or turn a divergence into a passing comparison. `covered` means the assertion proves the invariant
the entry's scope names. `bounded` means the assertion holds but proves less than that, for example failure
membership instead of list equality, or a projection instead of a whole state. The rationale states the
gap, and the tracking issue owns the stronger contract. `open` and `pending-integration` mean the assertion
is not yet enforced. `--candidate` additionally rejects unresolved
entries designated as candidate requirements. External datasets still require their own executed
acceptance receipts; their absence is not evidence of compatibility. The maintained overview and
run evidence remain in [compatibility.md](../docs/compatibility.md).
