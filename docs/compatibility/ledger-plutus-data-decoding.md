<!-- SPDX-License-Identifier: Apache-2.0 -->
# Ledger: Plutus Data read at decode (#721)

Part of the [compatibility matrix](../compatibility.md); the register entry is `ledger-plutus-data-decoding` in [compatibility.json](../data/compatibility.json). The sections below record the implementation, evidence and remaining limits.

## Status as recorded

implemented: the reference reads every Plutus `Data` a transaction carries when it decodes the transaction. A witness-set datum and a redeemer's data are `Data era`, decoded by the `Serialise` instance of Plutus's `Data` (`decCBOR = fromPlainDecoder Cborg.decode`, `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:97-125`, cardano-ledger `f649f975`; `decodeData`, `PlutusCore/Data.hs:209-265`, plutus `507cab8c`); an inline datum is `BinaryData`, made by `makeBinaryData`, which decodes its bytes as `Data` in full (`Plutus/Data.hs:217-239`). `Witnesses.decode` reads each datum and each redeemer's data (list and map forms), and `TxOut.decode` each inline datum of an output or a collateral return read from a block or a transaction, with `PlutusData.fromNode` / `PlutusData.decode` (`ledger/.../Data.scala`); data that is not `Data` is the typed `LedgerDecodeError.PlutusData` of its place, and the body, the witness set, the transaction and the block are refused. The original bytes are kept and hashed as before.

## Evidence

`PlutusDataPlacesReferenceSuite`: 137 original encodings (`fixtures/reference/plutus-data-places/cases.jsonl`, oracle `scripts/oracle/conway-decoding/data_cases.py`, harness unchanged) of each place in Alonzo, Babbage and Conway with data `decodeData` reads and data it refuses; 65 accepted, 72 refused, Scarab's verdict equal to the reference's on each.

## Explicitly unchecked or unconfirmed

A stored output is read without reading its inline datum again: it was read when the output was taken from a block, and the stores are not read by the reference.
