<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native blst export contract

**Status: Current fixture contract.**

blst-exports.txt is the expected native symbol surface used by the binding checks. It is an ABI regression fixture, not a cryptographic correctness oracle.

## Provenance and identity

See [the retained source notes](../../../../blst-native/native/README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../native/README.md) defines the executable assertion. Update the symbol expectation only with an independently inspected pinned native build and a reviewed ABI change. Signature/point correctness and portable startup checks are separate tests.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
