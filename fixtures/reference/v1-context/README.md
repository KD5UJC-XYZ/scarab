<!-- SPDX-License-Identifier: Apache-2.0 -->
# Reference-submitted V1 context cases

**Status: Current fixture contract.**

Transactions submitted to the reference node across Alonzo, Babbage and Conway protocol versions, retaining original bodies/witnesses and observed acceptance or refusal. V2/V3 controls are retained beside V1 cases.

## Provenance and identity

See [the retained source notes](../README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../ledger/src/test/scala/xyz/kd5ujc/scarab/ledger/V1ContextSuite.scala) defines the executable assertion. The parent documents the reference version, JSON fields and devnet.py v1-context capture commands. Verdicts and selected refusal predicates are observed evidence; they do not establish every possible context or full diagnostic-text equivalence.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
