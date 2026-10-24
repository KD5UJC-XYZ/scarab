<!-- SPDX-License-Identifier: Apache-2.0 -->
# Remaining POOL and early-era output predicates (#141)

Part of the [compatibility matrix](../compatibility.md); the register entry is `pool-and-early-era-predicates` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: metadata hashes over 32 bytes are refused from protocol 4.1; Shelley/Allegra/Mary enforce bootstrap attribute/network predicates in native order

## Evidence

[Released-source captures](../../fixtures/reference/remaining-predicates/README.md): 43 actual POOL transitions, 36 original early-era bodies and 72 native output-predicate calls; complete accepted pool states and exact typed refusal bytes; Scala signed LEDGER admission and metadata fuzz seeds

## Explicitly unchecked or unconfirmed

Native early-era output captures call the individual predicates, rather than complete native LEDGER admission. Scala retains its documented first-failure interface; complete native failure lists remain in the corpus
