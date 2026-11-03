# Plutus Data where a transaction carries it

Status: Current. Evidence for [#721](https://github.com/KD5UJC-XYZ/scarab/issues/721).

The reference reads every Plutus `Data` a transaction carries when it decodes the transaction: a
datum of the witness set and a redeemer's data are `Data era`, whose decoder is the `Serialise`
instance of Plutus's `Data` (`decodeData`, `PlutusCore/Data.hs:209-265`, plutus 507cab8c;
`decCBOR = fromPlainDecoder Cborg.decode`, `libs/cardano-ledger-core/src/Cardano/Ledger/Plutus/Data.hs:97-125`,
cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`), and an output's inline datum is
`BinaryData`, made by `makeBinaryData`, which decodes its bytes as `Data` in full
(`Plutus/Data.hs:217-239`). Data that is not `Data` fails the witness set or the body, and so the
transaction and its block.

`cases.jsonl` holds one case a line, written by `scripts/oracle/conway-decoding/data_cases.py` on
the [conway-decoding harness](../../../scripts/oracle/conway-decoding/README.md): in Alonzo, Babbage
and Conway, a witness-set datum and a redeemer's data in the list form (and in Conway the map form),
and from Babbage an inline datum of an output and of a collateral return, each holding data
`decodeData` reads (an integer, a constructor, an indefinite list, a map, a big number, a byte string
of 64 bytes, a chunked byte string) and data it refuses (null, text, a boolean, a byte string of 65
bytes, a constructor of no list, an unknown tag, a float), and an inline datum with bytes after the
data and of no bytes. The verdict is what `decodeFullAnnotator` did at the era's decoder version.
`PlutusDataPlacesReferenceSuite` requires the same verdict from `Witnesses.decode` and
`TxBody.decode`. `provenance.json` records the source and the hashes of the harness, the generator,
its input and output, and the corpus.

Not covered here: a stored output is read without reading its inline datum again (it was read when
the output was taken from a block).
