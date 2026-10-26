# Era-specific output forms and body keys (decoder versions 2 to 9)

Status: Current. Evidence for [#379](https://github.com/KD5UJC-XYZ/scarab/issues/379).

`cases.jsonl` holds 231 lines, a case each: its original transaction body (`bytes`, written by
`scripts/oracle/conway-decoding/era_forms_cases.py`) and the verdict of the reference, 89 accepted
and 142 refused. The verdict (`accepted` and the `failure` text) is what the unchanged released
decoders of cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5` did with each:
`decodeFullAnnotator` at the era's decoder version (Shelley 2, Allegra 3, Mary 4, Alonzo 5, Babbage
7, Conway 9), with the complete failure text.

- Output forms, in each era, as an output and (Babbage, Conway) as the collateral return: the
  two-element array, the three-element array with a datum hash, the map, each also written with
  indefinite length, and four elements, one element and an integer. `ShelleyTxOut` (Shelley,
  Allegra, Mary) reads the two-element array alone; `AlonzoTxOut` adds the datum hash;
  `BabbageTxOut` adds the map (`Shelley/TxOut.hs`, `Alonzo/TxOut.hs`, `Babbage/TxOut.hs`).
- Body keys 3 to 24 and 100, each with a value some era accepts, so a refusal is for the key. Each
  era refuses the keys outside its `boxBody` or `bodyFields` (`not a valid key`): Shelley knows 0
  to 7, Allegra and Mary 0 to 9, Alonzo adds 11 and 13 to 15, Babbage 16 to 18, Conway drops 6 and
  adds 19 to 22. Allegra reads the key 9 as `()`: it accepts `null` and refuses a map, and Mary
  to Conway the reverse.
- The required keys: 0, 1 and 2 in every era, and the time to live (3) in Shelley alone.
- A key twice, and keys beyond 32 bits (`2^32 + 3`, which a truncation would read as the time to
  live, and `2^64 - 1`).

`cases.jsonl` names for every refusal `rule` (a fragment of the reference failure) and `expect` (the
Scarab error case). `EraFormsReferenceSuite` requires the same verdict, and for an acceptance that the
retained bytes (and so the transaction id) are the original ones. `provenance.json` records the
package versions, the hashes of the harness, the generator and the files, and the counts.

Scope: wire decoding of transaction bodies only; no ledger rule, signature or script is run, and the
values of a key (certificates, for instance) are the ones the other corpora measure.

Regenerate with the [oracle recipe](../../../scripts/oracle/conway-decoding/README.md).
