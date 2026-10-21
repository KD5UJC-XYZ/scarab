<!-- SPDX-License-Identifier: Apache-2.0 -->
# Original network behavior conversations

**Status: Current fixture contract.**

Classpath copies of finite conversations and observed outcomes from pinned upstream network properties. Adjacent manifests retain seeds, source/dependency identities, observer changes and fixture hashes.

## Provenance and identity

See [the retained source notes](../../../../../../fixtures/reference/ouroboros-network-protocol-codecs/README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../scala/xyz/kd5ujc/scarab/network/UpstreamLocalStateQueryBehaviorSuite.scala) defines the executable assertion. Production protocol engines replay the original mock-domain conversations; strict Cardano-domain refusals have separate controls. Finite final-state comparisons do not establish every scheduler interleaving. Upstream precondition non-verdicts must remain distinct from passes. Regenerate through scripts/oracle/network-protocols with the original pins.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
