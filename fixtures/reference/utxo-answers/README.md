# UTxO query answers in every Shelley-based era

One hundred and twenty-seven synthetic UTxO maps, measured on 2026-11-02 and 2026-11-03 against the unchanged released Haskell
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
  an array), with a datum hash, an inline datum, a reference script, and both;
- from Babbage, a datum option and a reference script in forms the decoders take and the encoders
  do not write (an indefinite datum option, an indefinite script pair or native script, a Plutus
  script): the reference writes the option and the pair anew and keeps the bytes of the data and
  of a native script.

- from Mary, a value written as a `RecD` record detached from its fields, an empty indefinite list
  `9f ff` with the coin and the assets after it, as the decoders read a record below version 12
  (#700): in the list form, with a datum hash and, from Babbage, in the keyed form; the answer
  writes the value as decoded, `[coin, assets]`, or the bare coin when it holds no asset.

Before Babbage, an output with a part only a later era's `TxOut` holds is refused by the era's
decoder: 24 such cases in Shelley, Allegra, Mary and Alonzo. Before Mary a value is a coin, and a
detached one is refused: 4 more cases, in Shelley and Allegra.

`UtxoAnswersReferenceSuite` reads each entry with the node's stored-output reader (`TxOut.fromBytes`,
which reads a detached value) and writes the map with `UtxoCodecs.utxoIn`. Every answer is written
byte for byte, and every refused output that is not a detached value is refused by the era's
codec. `DetachedOutputFailureSuite` (node) requires the refusals that write an output (a small
output's, Mary to Conway) to write a detached-value output as these answers do.

Scope: the answer's encoding only. It does not run a query server, acquire a point or read a
ledger state. The `e2e` devnet scenario compares the whole UTxO of both nodes at the same point.
