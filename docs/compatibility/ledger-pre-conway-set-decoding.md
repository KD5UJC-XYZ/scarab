<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: pre-Conway set decoding, decoder versions 2 to 8 (audit, AUD3-03/#329)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-pre-conway-set-decoding` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: below version 9 a set of the body (inputs, collateral, required signers, reference inputs, pool owners) and of the witness set keeps one element for elements that are equal once decoded, whatever their encodings (`Set.fromList <$> decodeCollection`, cardano-ledger f649f975 `Decoder.hs` `decodeSet`), and a set wrapped in tag 258 is refused; version 9 refuses a repeated element (previous row). The original body and witness-set bytes are retained, so a transaction id hashes them unchanged

## Evidence

`PreConwaySetsReferenceSuite`: 60 original encodings for Shelley, Allegra, Mary, Alonzo and Babbage (`fixtures/reference/pre-conway-sets`), each with what the unchanged released decoder of the era did with it: 50 accepted (repeated elements in identical and different encodings collapse to one) and the 10 tagged sets refused; the replays of recorded Shelley to Babbage blocks in `check` are unchanged.

The same corpus also holds the parameter-update key sets and, for #374, empty collections, tag 258 on the other witness lists, the witness set's key set of each era, the script languages of each era (witness scripts and a Babbage output's reference script) and a repeated proposer: 227 cases in all, every verdict the reference's ([corpus README](../../fixtures/reference/pre-conway-sets/README.md)). A Shelley, Allegra or Mary witness set reads keys 0 to 2 only, and a Plutus script of a language newer than the era's is refused (`UnsupportedLanguage`).

## Explicitly unchecked or unconfirmed

only sets of the body inputs and the vkey witnesses are measured per era; other pre-Conway list rules are not ported
