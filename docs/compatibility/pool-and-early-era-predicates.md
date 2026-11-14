<!-- SPDX-License-Identifier: Apache-2.0 -->
# Remaining POOL and early-era output predicates (#141)

Part of the [compatibility matrix](../compatibility.md); the register entry is `pool-and-early-era-predicates` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: metadata hashes over 32 bytes are refused from protocol 4.1; Shelley/Allegra/Mary enforce bootstrap attribute/network predicates in native order

## Evidence

[Released-source captures](../../fixtures/reference/remaining-predicates/README.md): 43 actual POOL transitions, 36 original early-era bodies and 72 native output-predicate calls; complete accepted pool states and exact typed refusal bytes; Scala signed LEDGER admission and metadata fuzz seeds

Bootstrap attributes over 64 bytes (#832, 2026-11-09): the native captures above hold Shelley's predicate before an era's injection, so from Allegra `RemainingPredicateReferenceSuite` applies `shelleyToAllegraUtxoPredFailure` (`OutputTooBigUTxO`) to them. The [reference-refusal corpus](../../fixtures/reference/refusals/README.md) (`<era>-bootstrap-attributes`) records the 11.1.3 node's complete refusal in every era from Shelley to Conway, and Scarab's refusal bytes match it in all six. The Leios build keeps `OutputBootAddrAttrsTooBig` in every era; Scarab selects that by `--reference-build`, from the source (cardano-ledger 9df9f98c) only, with no recorded Leios refusal yet (#826).

## Explicitly unchecked or unconfirmed

Native early-era output captures call the individual predicates, rather than complete native LEDGER admission, so they are compared by their first failure. The rules record every failure in the reference's order since #412; complete native LEDGER refusals are compared in the [reference-refusal corpus](../../fixtures/reference/refusals/README.md)
