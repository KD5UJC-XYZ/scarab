<!-- SPDX-License-Identifier: Apache-2.0 -->
# Native and formal Conway CERT judgment

**Status: Current fixture contract.**

Original CERT environment, context, signal, input state and native/formal outputs exported from pinned cardano-ledger conformance hooks. manifest.json contains exact source identities, invocation details and artifact hashes.

## Provenance and identity

See [the retained source notes](manifest.json) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../scala/xyz/kd5ujc/scarab/node/UpstreamCertConformanceSuite.scala) defines the executable assertion. The suite consumes the original judgment through production rules and compares its complete applicable result. One retained judgment does not establish all certificate constructors. Regeneration follows scripts/oracle/ledger-govcert and the design inventory; preserve failed upstream invocations as non-results.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
