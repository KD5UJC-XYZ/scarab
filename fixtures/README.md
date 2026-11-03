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

## Provenance records

A reference corpus that records its own provenance does so in a `provenance.json` beside its cases. Schema 1
of that record is enforced rather than decorative: `python3 scripts/check_test_coverage.py` validates every
record it finds under `fixtures/reference` in preflight and CI, and a defect names the record and the field.
A record is one JSON object:

- `schemaVersion`: `1`.
- `scope`: a non-empty string, or a non-empty object, saying which behaviour the corpus measures.
- The source revision, as a hexadecimal string under one of `ledgerSourceCommit`, `ledgerSourceRevision`,
  `ledgerPin`, `consensusSourceCommit`, `plutusSourceCommit`, `sourceCommit` or `referenceGitRevision`; a
  record that keeps it nested as `ledger.revision` also passes. `measuredAt`, or `capturedAt` or `date`, is
  optional, because a corpus that never recorded one cannot be given one honestly; when present it must be a
  real ISO 8601 date.
- The case count: `cases` as an integer, or a `cases` object naming `file`, `count` and `sha256`, or
  `caseCount`, or `lines` for a JSONL corpus. When the corpus holds a `cases.jsonl` or `cases.json` file, the
  recorded count must equal the records in that file and `casesSha256` must be its digest. A corpus whose
  cases live elsewhere (a submission directory, a batch of transactions) records its own count, and the gate
  can only check its shape: it has nothing to compare it with, and says so here rather than implying a
  comparison it does not make.
- Wherever a count and an `accepted` plus `refused` split are recorded together, at any depth of the record,
  the split must add up to the count.
- Every `*Sha256` string, at any depth of the record, is a lowercase hexadecimal digest. It is compared with
  a file only where the field names a corpus file — `casesSha256`, or the `file` of a `cases` object; a digest
  of an upstream source, image or harness names nothing in this repository and is checked for shape alone.
- `notCovered`, when present, maps each uncovered topic to a sentence naming the issue that owns the gap, and
  that issue must also appear in `limitations.issues` of the one entry in
  [the compatibility register](../docs/data/compatibility.json) that cites the corpus. A gap with no owning
  entry fails: a gap nobody tracks is how a limit quietly becomes permanent.

The record describes a measurement; it never certifies one. Its hashes detect a corpus that moved under an
unchanged expectation, and for a corpus that keeps its cases in a case file the counts detect a case added or
dropped without the record saying so. A corpus that records no provenance at all is not checked, and this
schema is not a claim that it has one.
