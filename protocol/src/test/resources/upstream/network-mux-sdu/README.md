<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native Mux SDU encodings

**Status: Current fixture contract.**

Forty deterministic SDU codec rows from the pinned upstream network corpus. The consuming suite pins the complete cases.tsv hash and validates the recorded generator fields and independent wire encodings.

## Provenance and identity

See [the retained source notes](../../../../../../fixtures/reference/ouroboros-network-protocol-codecs/README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../scala/xyz/kd5ujc/scarab/protocol/MuxUpstreamConformanceSuite.scala) defines the executable assertion. All forty low-level encoder rows run, including six empty-payload encodings. Native streaming admission rejects those empty SDUs; their encoder equality must not be described as decoder acceptance. Capture with scripts/oracle/network-mux/export.py and review the exact source/seed identities.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
