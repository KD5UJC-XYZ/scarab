<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra crossing: transactions

188 cases measured on 2026-11-08 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0, with the mempool
path of ouroboros-consensus `2416493b`), for [#54](https://github.com/KD5UJC-XYZ/scarab/issues/54). The
oracle source and [recipe](../../../scripts/oracle/dijkstra-crossing/README.md) are committed;
`provenance.json` records the image identity, the program's hash and the hashes of the inputs.

`cases.jsonl` holds one case per line: a whole Conway transaction in its mempool form (`tx`) and what
the reference does with it:

- `conway`: decoded at Conway's version, and `toCbor`, the bytes Conway's `toCBOR` writes;
- `parts`: the body, the witness set and the auxiliary data each carried alone by
  `translateEraThroughCBOR` (a failure of an annotator is labelled `Annotator`, not by the part);
- `translateEra`: `TranslateEra DijkstraEra (Tx TopTx)`;
- `rematch`: Conway's `toCBOR` read whole by Dijkstra's mempool decoder (`decodeDijkstraTopTx`), as
  the hard-fork mempool carries a held transaction into a new era.

An accepted transaction has each part's bytes, the flag, the id and its Dijkstra mempool form
(`toCBORForMempoolSubmission`); a refused one the reference's `DecoderError`.

| group | cases | what the reference does |
|---|---|---|
| `preview/<body hash>` | the 62 distinct transactions of `fixtures/preview/conway.hex` and `conway-governance.hex`, as their blocks hold them | 53 carried both ways, unchanged; 9 refused by their witness set (redeemers in the list form, refused from version 12) |
| `decoding/<id>` | the 125 Conway bodies, witness sets and whole transactions of `../conway-decoding` the Conway decoder accepts, a body beside an empty witness set and a witness set beside the baseline body | 98 carried; 15 refused by the body (certificates without deposits, rationals with signed or bignum parts) and 12 by the witness set (repeated set elements, list redeemers) |
| `flag/false` | the baseline body with an empty witness set, its flag false | `translateEra` keeps the flag; the rematch refuses it, since the Dijkstra mempool form cannot say false |

Scope: the translation of the transaction alone. Not the mempool's reapplication of the carried
transaction to a Dijkstra ledger, which needs Dijkstra's rules.
