<!-- SPDX-License-Identifier: Apache-2.0 -->
# Auxiliary Plutus admission (#286)

Part of the [compatibility matrix](../compatibility.md); the register entry is `auxiliary-plutus-admission` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented for Alonzo and later: structural metadata/script decoding, original-byte hash checks and every auxiliary Plutus decoder verdict precede validity-tag execution; auxiliary scripts are never witnesses or evaluated

## Evidence

490 unchanged-Haskell observations across PV5–11; 505 focused ledger tests including both flags, hash precedence, typed outage, 20,000-depth small-stack metadata and late serial/prepared BBODY rollback; structured whole-transaction fuzz seeds. [As built](../plutus-conformance.md#auxiliary-data-admission-286), [corpus](../../fixtures/reference/auxiliary-scripts/README.md)

## Explicitly unchecked or unconfirmed

Finite API matrix plus 24 successful signed-reference auxiliary controls within the combined 44-case era-load gate; [measured receipts](../../fixtures/reference/plutus-admission/README.md). Shelley, Allegra and Mary auxiliary structure ([#290](https://github.com/KD5UJC-XYZ/scarab/issues/290)) uses the same reader at decoder versions 2-4 and replays 504 further unchanged-Haskell observations ([corpus](../../fixtures/reference/auxiliary-historical/README.md)); exact diagnostic strings remain outside this Plutus boundary; no persisted format change.
