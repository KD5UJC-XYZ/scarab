# Pre-Conway set decoding (decoder versions 2 to 7)

Status: Current. Evidence for [AUD3-03/#329](https://github.com/KD5UJC-XYZ/scarab/issues/329).

`cases.json` holds 60 original encodings written by
`scripts/oracle/conway-decoding/pre_conway_cases.py`: for each era from Shelley to Babbage a
transaction body and a witness set whose set repeats an element (identical, or written with a wide
index or a wide array header, among other elements, in an indefinite-length array) or carries the
tag 258, each next to a twin without the fault.

`reference.json` is what the unchanged released decoders of cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5` did with each: `decodeFullAnnotator` at the era's
decoder version (Shelley 2, Allegra 3, Mary 4, Alonzo 5, Babbage 7), with the complete failure
text. Below version 9 the reference collapses a repeated element by decoded value and does not
read the tag (`decodeSet`, `Decoder.hs`): 50 encodings are accepted, the 10 tagged sets refused with
`expected list len or indef`. `provenance.json` records the package versions and the hashes of the
decoder source, harness, generator and both fixtures.

`cases.json` names for every refusal `rule` (a fragment of the reference failure) and `expect` (the
Scarab error case), and for every acceptance `size`, the elements that must remain after the repeats
collapse. `PreConwaySetsReferenceSuite` requires the same verdict and, for an acceptance, that the
retained bytes (and so the transaction id) are the original ones.

Scope: wire decoding only; no ledger rule, signature or script is run. Sets of the body and the
vkey witnesses are covered; the Conway rules are in the [conway-decoding corpus](../conway-decoding/README.md).

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
