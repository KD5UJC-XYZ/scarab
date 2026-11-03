# Map heads: the eight-byte length of an output or redeemer map (decoder versions 3 to 9)

Status: Current. Evidence for [#701](https://github.com/KD5UJC-XYZ/scarab/issues/701).

Two decoders of a block choose between a map form and a list form with cborg's `peekTokenType`
and send only `TypeMapLen` and `TypeMapLenIndef` to the map form (cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`): an output from Babbage, `decodeBabbageTxOut`
(`eras/babbage/impl/src/Cardano/Ledger/Babbage/TxOut.hs:549-553`), and the redeemers from decoder
version 9, `RedeemersRaw` (`eras/alonzo/impl/src/Cardano/Ledger/Alonzo/TxWits.hs:548-559`). A map
whose length is written in eight bytes (head `0xbb`) is `TypeMapLen64`, so it is read as a list and
refused with "expected list len or indef"; a length in the head or in one, two or four bytes is
`TypeMapLen` on a 64-bit machine.

`cases.jsonl` holds a line per case, written by `scripts/oracle/conway-decoding/map_head_cases.py`:
- the map form of an output and of a collateral return in Babbage and Conway, and of Conway's
  redeemers, with each width of length (in the head, one, two, four and eight bytes); in Alonzo
  (outputs) and Alonzo and Babbage (redeemers), which have no map form, the map in the head and with
  eight bytes;
- controls the reference reads with an eight-byte head: the list form of an output and of redeemers
  (`0x9b`), the multi-asset map of a value (Mary to Conway), and the metadata map and a metadatum map
  of auxiliary data (Allegra to Conway).

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did with
each: `decodeFullAnnotator` at the era's decoder version; the counts are in `provenance.json`.
Every refusal names `rule`, a fragment of the reference failure, and `expect`, the Scarab error
case. `MapHeadsReferenceSuite` requires the same verdict from `TxBody.decode`, `Witnesses.decode` or
`Tx.fromEnvelope`, and for an acceptance that the original bytes are retained. It also reads each
refused eight-byte output map with the stored rule (`TxOut.Forms.Stored`, ADR 0006), which an
earlier build accepted.

Scope: wire decoding only; no ledger rule, signature or script is run.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
