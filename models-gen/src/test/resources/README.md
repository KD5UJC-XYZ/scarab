<!-- SPDX-License-Identifier: Apache-2.0 -->
# Generated output regression identities

**Status: Current fixture contract.**

pinned-output.sha256 records expected generated-source identities for the generator regression suite. These are Scala source-generation expectations, not native ledger verdicts.

## Provenance and identity

See [the retained source notes](../../../../docs/models.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../../docs/models.md) defines the executable assertion. Regenerate only after reviewing the actual generated-source diff and the generator change. Preserve independent CDDL cross-checks; a new generator digest cannot establish native codec correctness.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
