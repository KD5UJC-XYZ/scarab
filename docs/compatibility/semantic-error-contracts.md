<!-- SPDX-License-Identifier: Apache-2.0 -->
# Semantic error contracts (M11-E/#189)

Part of the [compatibility matrix](../compatibility.md); the register entry is `semantic-error-contracts` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: script evaluator causes survive until rejection encoding; closed Byron selectors and forge skip cases; connection/session endings retain typed causes; generator configuration, resolution, emission and sampling return typed values

## Evidence

fixed rejection bytes, original cause identity, compiler rejection of raw skip strings, metric/report goldens, 21 unchanged generated-file fingerprints, 384 unchanged fixed-seed samples and small-stack/termination controls; level-3 evidence below

## Explicitly unchecked or unconfirmed

compiler/AST enforcement and Scalus callback ABI remain M11-E; broader effect/fiber/recovery policy remains #180; generated-model adoption remains #87
