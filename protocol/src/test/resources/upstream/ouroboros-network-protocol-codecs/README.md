<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original protocol codec vectors

**Status: Current fixture contract.**

Classpath copies of unchanged upstream messages, binary encodings, split offsets and annotated cases. Each adjacent manifest records its source/property, seed, exported count and hash.

## Provenance and identity

See [the retained source notes](../../../../../../fixtures/reference/ouroboros-network-protocol-codecs/COVERAGE.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../scala/xyz/kd5ujc/scarab/protocol/UpstreamGenericCodecCoverageSuite.scala) defines the executable assertion. The generic engine suite compares original constructor fields, encoded bytes, consumption and prefix/suffix behavior. Strict Cardano point/address acceptance remains a separate contract. Regeneration uses scripts/oracle/network-protocols; never normalize retained original inputs to fit a narrower adapter.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
