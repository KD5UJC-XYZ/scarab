<!-- SPDX-License-Identifier: Apache-2.0 -->
# Plutus Data CBOR chunk bounds

Part of the [compatibility matrix](../compatibility.md); the register entry is `plutus-data-chunk-bounds` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: each original definite byte-string chunk is at most 64 bytes, including tagged positive/negative bignum payloads; longer joined strings remain supported

## Evidence

Twelve original native CLI parser/hash probes: definite 64 and chunked 64+1 accepted, definite 65 and an indefinite 65-byte chunk refused, across bytes/tag 2/tag 3; the new suite reproduces six incorrect pre-fix admissions and passes after correction. [Inputs and provenance](../../fixtures/reference/plutus-data-chunks/README.md); the same bytes seed decoder fuzz

## Explicitly unchecked or unconfirmed

Parser/hash evidence, not complete transaction admission. The generic CBOR decoder has a broader contract; Plutus-specific validation reads its retained original bytes.
