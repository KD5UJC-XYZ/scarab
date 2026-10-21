<!-- SPDX-License-Identifier: Apache-2.0 -->
# Preview genesis and configuration

**Status: Current fixture contract.**

Public Preview network configuration and era genesis inputs used with the captured Preview blocks. Configuration file names and genesis hashes describe their original network identity.

## Provenance and identity

See [the retained source notes](../README.md) and adjacent manifests or checksum files.
Repository-wide fixture names and SHA-256 identities are pinned by
`scripts/check_test_coverage.py`; those identities detect changes and do not independently certify the expected outcomes.

## Assertions and limits

[The consuming code](../../../node/src/test/scala/xyz/kd5ujc/scarab/node/ConfigSuite.scala) defines the executable assertion. These are parsing, identity and replay inputs, not a runnable private network configuration. A referenced operational file need not be included in this bounded capture. Do not replace network identities to make a test pass.
Nested payload directories belong to this corpus unless they have their own documented contract.

## Changes and reproduction

Retain original payloads and expected results. Explain every intentional corpus or expected-output change,
including its independent evidence, under the [project evidence process](../../../docs/process.md).
Refreshing the repository inventory records identities only; it never regenerates expected results.
