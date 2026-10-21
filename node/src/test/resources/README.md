<!-- SPDX-License-Identifier: Apache-2.0 -->
# Node test resource index

**Status: Current fixture contract.**

rewards-pending-v5.bin is a frozen previous-writer pending-reward snapshot. Its provenance and intended compatibility assertions are recorded in rewards-pending-v5.md. The upstream directory contains separate named conformance corpora.

## Provenance and identity

See [the retained source notes](rewards-pending-v5.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../scala/xyz/kd5ujc/scarab/storage/SnapshotGoldenSuite.scala) defines the executable assertion. Retain the previous-writer bytes unchanged; rewriting a frozen compatibility fixture with the current writer defeats the migration/read regression. Consult each upstream corpus README for its native pins and exact assertion scope.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
