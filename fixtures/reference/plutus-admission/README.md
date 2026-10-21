# Signed Plutus admission: integrated gate observations

**Historical: 2026-10-21 UTC, source `5b290879b8ca8fe12350696e8ad7cc102d4d61f8`.**

This compact evidence fixture retains 44 original signed synthetic transactions, their SHA-256
identities, reference acquisition points/protocols and queried normal/collateral/output effects.
`admissions.json` has twenty V1/V2 spending/minting/validity cases and twenty-four auxiliary-script
cases. The reference is the official cardano-node 11.1.3 / CLI 11.2.3.0 Docker image; full immutable
image identities, commands, source/runtime binding and gate outcomes are in `gates.json`.
`manifest.json` hashes both source groups of JSON documents; each group retains its own measured source identity.

The normal driver generates and signs these transactions with disposable private devnet keys.
Those keys, private genesis, databases and logs are not fixtures. This is a measured admission/effect
receipt, not a standalone replayable initial ledger state. Reproduce the workload with the pinned
source and `scripts/devnet.py --name <private-name> e2e`; new genesis/timing produces new signatures,
transaction identities and points. The independent API corpora have their own reproducible inputs:
[costing](../plutus-costing/README.md), [contexts](../context-matrix/README.md) and
[auxiliary decoding](../auxiliary-scripts/README.md). Original synthetic observations are Apache-2.0,
like the repository's driver; upstream executable/source licensing is recorded in
[reference-image.md](../../../docs/reference-image.md).

`ledger-comparisons.json` retains all 66 successful Shelley-through-Conway comparison points.
Byron is not directly queried by this CLI; its UTxO is checked in Shelley. These are the existing
scenario's ledger projections, not a newly asserted byte-for-byte equality of every internal state.
The separate K1 projection limitations remain in [compatibility.md](../../../docs/compatibility.md).
Nine pending-proposal observations preserve both actual cost-model CBOR encodings: definite and
indefinite arrays differ in bytes, but all 166 V1 plus 175 V2 ordered parameters agree. Comparison
normalizes only PPUP field 18 after strict full decoding; it does not omit that field or change any
transaction/hash/persisted encoding. Diagnostics are excluded; reference CLI output identities and
raw refusal CBOR, where supplied, remain in the transaction receipts.

The gate passed with 1,691 block applications, 45 handled rollbacks and seven reconnects. No ledger
comparison mismatch/error/skip or validation/fatal error occurred. The reconnects are a separate
[KeepAlive lifecycle investigation](https://github.com/KD5UJC-XYZ/scarab/issues/292), not evidence of
continuous network stability. No production database or node was accessed by this workload.

`check` passed 6,449 tests with 17 optional-data skips; all 331 Python tests pass. Extended fuzz ran
84 targets / 33,600,000 cases on `b991c9413`; only Python comparison/tests and a changelog fragment
changed before `5b290879b`, so the decoder/fuzz implementation is identical. Private full manifests
bound all 6,363 tracked files, 12,725 runtime files and 64 jars; their digests and successful
before/after audits are retained in `gates.json`. These inventory digests identify the measured local
artifacts; the public source commit and fixture/tool manifests supply reproducible source identities.

## Rebased integration gate

Main advanced with BlockFetch coordination (#289), requiring a fresh integration gate. Source
`0c94e76a460bf5471c66857fba2111c903f97a2c` is rebased onto main
`0a6693fb01ca4ddb4aca7ef2f21eacd98361ce9b`. The only rebase conflict was the compatibility evidence table's
two independent run records; both were preserved. The `rebased-*.json` documents retain the second
run's 44 signed transactions/effects, 66 matching ledger points and complete gate/source identities.
Docker check passed 6,781 tests with 17 optional-data skips; Python331/331; source-head CI is green.
The scenario passed through Conway epoch17 with 1,656 applications,36 handled rollbacks and seven
reconnects. Zero comparison mismatches/errors/skips, validation/fatal errors or starvation warnings.
All nine pending-cost-model observations match again, preserving both actual encodings.

All 6,389 tracked source files,12,804 runtime files and64 jars were bound and audited. A separate
source comparison binds the unchanged Plutus/context/auxiliary and existing fuzz implementation:
5,494 scoped files agree before/after rebase, including4,810 code files identical to the extended-fuzz
source. The historical 33.6-million-case fuzz run is not relabeled as an exact-head run of the new
BlockFetch scheduling, lifecycle, configuration or RTT sampling; fresh check and e2e verify this
combined integration. The earlier source group and failure diagnoses remain historical.
