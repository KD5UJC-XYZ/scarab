<!-- SPDX-License-Identifier: Apache-2.0 -->
# Fuzz seed corpora

**Status: Current fixture contract.**

Valid seed inputs for the registered decoder fuzz targets. The early-era transaction directory retains original native-serialized Shelley, Allegra and Mary transactions.

## Provenance and identity

See [the retained source notes](early-era-transactions/README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../fuzz/src/test/scala/xyz/kd5ujc/scarab/fuzz/GuardSuite.scala) defines the executable assertion. Every registered target must accept seeds; mutation campaigns are bounded sampling, not proof over all byte strings. Seed construction and original expected acceptance must remain independently justified.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
