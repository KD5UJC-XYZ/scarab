<!-- SPDX-License-Identifier: Apache-2.0 -->
# Dijkstra translation: outputs

19 cases measured on 2026-11-07 against the unchanged Haskell ledger of the public Leios testnet build
(cardano-node `b004fe0c`, cardano-ledger `9df9f98c`, cardano-ledger-dijkstra 0.5.0.0), for
[#54](https://github.com/KD5UJC-XYZ/scarab/issues/54). The oracle source and
[recipe](../../../scripts/oracle/dijkstra-translation/README.md) are committed; `provenance.json` records the
image identity and the program's hash.

`cases.jsonl` holds one case per line: the CBOR of a Conway output (`txOut`), and what the reference does
with it. The output is decoded at Conway's version, upgraded by Dijkstra's `upgradeTxOut`
(`Dijkstra/TxOut.hs:33`, `upgradeBabbageTxOut`), which the translation applies to the UTxO
(`Dijkstra/Translation.hs:225-227`) and the consensus layer to the ledger tables (ouroboros-consensus
`2416493b` `Cardano/CanHardFork.hs:757-765`), and both outputs are written back at their era's version
(`conwayTxOut`, `dijkstraTxOut`), with the reference script's bytes and hash in each era (`hashScript`,
`Core.hs:615-620`).

| group | cases | what the reference does |
|---|---|---|
| a native reference script in a form Dijkstra keeps | a signature; an indefinite list of 24; a definite list of 23; `m` negative; slots past `Word32` and at the `Word64` maximum | the same bytes and hash |
| a native reference script in another form | an indefinite list of two, or empty; a definite list of 24; `m`, a slot, a tag and a key hash's length in longer heads than needed; nested mixtures; a nesting 1,000 deep; an inline datum beside it | the script encoded anew (`upgradeTimelock`, `Dijkstra/Scripts.hs:436-444`): new bytes, new hash |
| no native script | a PlutusV2 reference script; an output with no script | unchanged |

Scope: one output at a time. Not the rest of the new-epoch-state translation (parameters, governance,
pools), which `DijkstraTranslationSuite` checks against the reference's code, field by field: no Dijkstra
ledger-state reader exists yet to compare a whole translated state.
