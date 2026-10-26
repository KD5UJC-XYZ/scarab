<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Conway UTXO against `babbageUtxoValidation` (audit, #163)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-conway-utxo-babbage-validation` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: the rule makes, for every transaction whatever its validity tag, each predicate the reference makes (cardano-ledger f649f975, `Babbage/Rules/Utxo.hs:342-412`, called by `conwayUtxoTransition`): disjoint reference inputs, validity interval, input set, fee and collateral (`feesOK`), every input exists (spent, collateral and reference inputs), value conserved, outputs too small / too big, bootstrap-address attributes (at most 64 bytes), output network (a bootstrap address has the network its attributes give), withdrawal network, body network, transaction size, execution units, number of collateral inputs; the failure is encoded as the reference does (`ConwayUtxoPredFailure` tag 8 for a withdrawal on another network)

## Evidence

unit: `ConwayLedgerSuite` (a withdrawal of another network and one of the network, under each validity tag, as a transaction (LEDGER) and as a block (LEDGERS), protocol 10 and 11; the collateral taken only where the rules of UTXO hold; collateral inputs that do not exist or are too many without redeemers; bootstrap-address outputs), `PredicateFailureCborSuite` (the reference's reasons as bytes); devnet `ntc`: the withdrawal case under each validity flag refused as the reference refuses it (raw and text), and a transaction with the flag false whose collateral both nodes take (one input stays)

## Explicitly unchecked or unconfirmed

`validateOutsideForecast` has no counterpart: after `unsafeLinearExtendEpochInfo` the conversion is total, so it cannot fail; for a transaction with the flag true the reference lists the failure of LEDGER's check of the withdrawals and then this one, the Scala rules the first (the known limit of the failure list); Alonzo/Babbage now enforce these collateral and bootstrap-output predicates under #178; Shelley/Allegra/Mary now enforce bootstrap-output bounds and network predicates under #141; protocol-9 set, map and ordered-set uniqueness at decoding is the next row (#327)
