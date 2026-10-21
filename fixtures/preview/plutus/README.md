<!-- SPDX-License-Identifier: Apache-2.0 -->
# Recorded Preview Plutus transactions

**Status: Current fixture contract.**

Public transaction bodies, witnesses, cost models and spent/reference inputs used to reconstruct recorded Preview phase-two evaluations. Some spent outputs were reconstructed from public explorer data; their provenance differs from original transaction bytes.

## Provenance and identity

See [the retained source notes](../README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/PlutusCaseSuite.scala) defines the executable assertion. Replay checks the recorded validity flag and the assertions in the consuming suite. Explorer-derived outputs and Scala context cross-checks are not independent native goldens for every intermediate value. Regenerate with scripts/preview_plutus_cases.py or the documented capture command, retaining source points and cost models.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
