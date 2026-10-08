# Public-only strict witness corpus

This is an **experimental source-profile research corpus**, not proof of Cardano
release-binary equivalence or complete transaction/witness validation. See
[`manifest.json`](manifest.json) for machine-readable provenance and
[`../../docs/witness-provenance.md`](../../docs/witness-provenance.md) for scope.

## Contents

- `vectors.tsv`: 2,219 public-only rows, no header, columns `id`,
  `public_key_hex`, `signature_hex`, `message_hex`. Preserve the empty trailing
  fourth field for empty messages. SHA256:
  `f79f0a87efbd99ebe159c18211497f6b0d282e7946f1839216dc31bbf99ef918`.
- `status.tsv`: header followed by one row per vector, with columns `id`,
  `source_expected`, `expected_kind`, `expected_result`, `system_sodium`,
  `bc_raw`, `strict_archived`.
- `projection.json`: full status/source details, including Wycheproof comments,
  flags and source indices; BC subgroup diagnostics remain archival diagnostics.
- `ledger-witnesses.tsv`: eight rows, no header, columns `id`,
  `transaction_hex`, `witness_index`, `body_hex`, `body_hash`.
- `ledger-witnesses.json`: complete original transactions, exact body bytes,
  public witnesses, source event/transaction/body/witness/key/signature offsets,
  and separate source ledger expectations. Offsets are zero-based, half-open.
- `inputs/`: seed-free sodium public projection, original public-only speccheck
  and Wycheproof inputs, and two byte-identical original ledger sequences.
- `archive/`: prior public verification observations and Scala 2.12 diagnostic
  runner source. These files are not project runtime code and are not executed
  by the projector. `archive/pinned-source-oracle/` separately records a fresh
  exact-source non-COMPAT native run: all 2,207 fixed-size cases match, eight
  ledger witnesses pass, and 12 malformed signatures never cross the native ABI.
  No native binary or original seed-bearing source is vendored. The strict prototype's broad catch is a historical
  limitation, not the integrated research error-handling contract.
- `provenance/`: exact release metadata/flake lock, pinned public verification
  implementation, and historical speccheck comparison table.
- `licenses/`: complete ISC, Apache-2.0, BC license, and Weavechain artifact
  license with MIT, CC0/eddsa-java, and BSD-3-Clause/dalek notices.

## Expectations are not interchangeable

There are 1,024 upstream sodium positives, 1,024 upstream non-COMPAT S+L
negatives, 12 speccheck cases, 151 Wycheproof cases and 8 ledger witnesses.
Of 2,219 rows, 2,207 have fixed sizes. The 12 malformed Wycheproof signatures
are typed input errors. Their native status is `NotRunMalformedLength`, not
`false`; both historical JVM harnesses reported `MalformedLength`.

For fixed-size rows, `expected_result` is derived from the archived system
sodium observation, cross-checked with strict archive and independently supplied
source expectations where available. `source_expected` is `true`, `false` or
`unknown`; unknown does not mean failure.

Three witnesses belong to accepted Haskell ledger setup transactions and have
positive source-supported expectations. Five belong to transactions rejected for
value conservation: their signatures still verify, but these are differential
observations without separately stored Haskell signature goldens. Their
`source_expected` is `unknown`, with the source ledger boolean preserved
separately. All eight verify the 32 raw BLAKE2b-256 hash bytes of the original
body slice, not CBOR-encoded hashes or re-encoded transaction bodies.

BC 1.85.2 raw verification accepts `speccheck-2`, `speccheck-4`, `speccheck-5`
while system sodium and the strict prototype reject them. All accept mixed-order
`speccheck-3`; prime-subgroup-only validation would over-reject that control.

## Portable offline reproduction

From the repository, with Python 3.9+ standard library only:

```sh
python3 scripts/project-witness-fixtures.py --check
```

Without `--check`, the script regenerates the five derived files and
`SHA256SUMS`. Source/archive/license/provenance hashes are immutable constants in
the script, checked before parsing. Derived hashes are fixed too. Reads are
bounded; no network, external process, native library, signing or key generation
is involved. No library installation or original secret-bearing source is needed.

Optional stronger source-to-projection verification uses an already downloaded
original `sign.c` **outside this repository**:

```sh
python3 scripts/project-witness-fixtures.py --check --sodium-source /tmp/sign.c
```

The original file SHA256 is checked before parsing:
`fa3f37d292782694d721a218d0adf2b7de633552d41bbdc1d0ae0a708e32a21d`.
The extractor discards the published seed field and compares only public output
against the immutable seed-free projection hash. It never executes upstream code.
Original source acquisition is a separate optional step, documented in the
provenance guide. Do not add `sign.c` or upstream seed tables to this deliverable.

The original installed native oracle was libsodium 1.0.18 with unknown complete
build provenance. The separate exact-source non-COMPAT self-build now strengthens
that evidence: all 2,207 fixed-size rows agree. It is not the shipped Cardano node
binary, whose compiler/flags and native linkage remain unestablished. Offline
reproduction checks both archives without executing or loading either library.
