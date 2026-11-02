# UTxO query answers in every Shelley-based era

One hundred synthetic UTxO maps, measured on 2026-11-02 against the unchanged released Haskell
ledger libraries used by node 11.1.3. `answers.jsonl` holds one case per line: its era, its
entries as the original input and output bytes, and either the reference's answer, as hex, or why
the era's decoder refused an output. The [oracle source and recipe](../../../scripts/oracle/utxo-answers/README.md)
are committed. `provenance.json` records the package versions and the hashes of the encoder
source files, which match pinned cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`
byte for byte.

The reference answers `GetUTxOWhole`, `GetUTxOByAddress` and `GetUTxOByTxIn` with `toCBOR` of the
era's `UTxO` (`encodeShelleyResult`, `Ouroboros/Consensus/Shelley/Ledger/Query.hs:1007-1030`,
ouroboros-consensus `82ecba32`), which is `toEraCBOR @era` (`State/UTxO.hs:95-96`). The oracle
reads each entry with the era's decoder at the era's lowest protocol version and writes that map.

Coverage in each era from Shelley to Conway:
- base, enterprise, script, pointer and Byron bootstrap addresses, and the largest coin;
- entries out of order across transactions, the empty map, and 30 entries, past the length one
  head byte holds (the map is then written with indefinite length);
- multi-asset values, datum hashes and, from Babbage, the keyed form: without a datum (written as
  an array), with a datum hash, an inline datum, a reference script, and both.

Before Babbage, an output with a part only a later era's `TxOut` holds is refused by the era's
decoder: 24 such cases in Shelley, Allegra, Mary and Alonzo.

`UtxoAnswersReferenceSuite` reads each entry with the node's stored-output reader and writes the
map with `UtxoCodecs.utxoIn`. Every answer is written byte for byte, and every refused output is
refused by the era's codec.

Scope: the answer's encoding only. It does not run a query server, acquire a point or read a
ledger state. The `e2e` devnet scenario compares the whole UTxO of both nodes at the same point.
