# Preview testnet block fixtures

Raw node-to-node BlockFetch blocks (`[era_tag, block]`), one hex-encoded block per line, captured
with `xyz.kd5ujc.scarab.node.tools.CaptureMain` from the public relay `preview-node.play.dev.cardano.org:3001`
(network magic 2) on 2026-10-10. Every captured block was at least 10 blocks deep.

| File | Contents |
|---|---|
| `alonzo-first-20.hex` | The first 20 blocks from Origin (heights 0-19). Alonzo era (tag 5), TPraos headers, protocol 6. Contiguous. Includes two blocks with one transaction each. |
| `alonzo-txs.hex` | Two single Alonzo blocks (heights 4330 and 8645) that each contain one transaction. Not contiguous. |
| `babbage-transition.hex` | 30 contiguous blocks starting at height 13011: the last Alonzo block (TPraos) followed by Babbage blocks (tag 6, Praos headers, protocol 8). |
| `conway.hex` | 30 contiguous Conway blocks (tag 7, Praos headers) starting just after height 2645975, captured with `--from 64195073.f1ce3cbb...`. Includes blocks with transactions. |

Preview has no Byron, Shelley, Allegra or Mary blocks: the chain forks through those eras at
epoch 0 and the first block is already Alonzo.

`babbage-mint.hex`: one Babbage block (slot 730119, epoch 8) with a native-script mint transaction
that has auxiliary data (CIP-25 metadata); it pins the transaction size used for the minimum fee.

## Conway governance and Plutus V3

`conway-governance.hex`: 23 Conway blocks (protocol version 10 and 11, epochs 1047 to 1447) captured
with `xyz.kd5ujc.scarab.node.tools.CaptureMain --from <slot>.<hash>` from the relay on 2026-10-11. The points were found
with the Koios API (`proposal_list`, `vote_list`, `drep_updates`, `tx_info`, `block_info`): a block
window around each of two proposals of every action type (parameter change, treasury withdrawal,
hard fork, new constitution, no confidence, committee update, info), the DRep, pool and committee
votes of three transactions and a DRep registration. Most proposals carry the constitution's
guardrails script (a Plutus V3 script in the witness set).

`conway-governance.json`: what Koios reported for those transactions (action type, deposit, return
account, previous action, the explorer's rendering of the action, the votes) and the protocol
version and cost models of the nine epochs involved. `PreviewConwayGovernanceSuite` decodes the
blocks, checks the transaction ids and all signatures, compares the proposals, votes and the DRep
registration with the explorer data and recomputes the script data hash with those cost models.

`plutus/slot125...json` and the other `plutus/` cases dated slot 90526940 and later: Plutus
transactions of the blocks above in the format `follow --plutus-cases` writes (the spent and
referenced outputs come from Koios `utxo_info`, re-encoded). They are replayed by `PlutusCaseSuite`
(script data hash, phase-2 verdict equal to `is_valid`, budgets) and `ScriptContextOracleSuite`
(contexts equal Scalus's). They include `Proposing` redeemers of parameter changes and treasury
withdrawals running the real guardrails script, `Voting` redeemers of script committee members and
V3 spends, mints and rewards. `scripts/preview_plutus_cases.py` rebuilds them.

`plutus/slot12924758-e5e7f3de.json` (epoch 149, recorded by `follow --plutus-cases` while syncing
from genesis): a PlutusV1 mint whose witness set lists six redeemers for the one purpose `(mint, 0)`
(data 01, 01, 02, 02, 03, 03). The ledger keeps redeemers in a map, so the last entry counts; the
script fails on 01 and 02 and passes on 03 with exactly the declared units. `Witnesses.decode` keeps
the last of repeated `(tag, index)` entries of the array form.

`empty-redeemers.hex`: six whole blocks (one per line) found by scanning the entire Preview chain for
transactions with a `script_data_hash`, datums and no redeemers or Plutus language: Babbage slots
936549 (redeemers key absent) and 1291038 (explicit `0x80`), Conway slots 56231298, 56408384, 111388018 and 122786727 (header protocol majors 9, 10, 11
and 12; the header carries the producer's version, which may exceed the ledger's), captured from a fully synced node's immutable
store on 2026-10-11. They pin the `0x80` (Babbage) and `0xa0` (Conway) empty-redeemers encodings in
the script data hash (`PreviewEmptyRedeemersSuite`).

## Provenance and licence

The blocks are public Preview testnet chain data fetched from the public relay named above and stored
unchanged. `conway-governance.json`, the `plutus/` cases and the points used to find the blocks
contain data from the Koios public API (https://koios.rest: `proposal_list`, `vote_list`,
`drep_updates`, `tx_info`, `block_info`, `utxo_info`, `/tip`), which itself reports public chain state;
Koios is credited as the source and is not affiliated with this project. `genesis/` is the public Preview network configuration from
https://book.play.dev.cardano.org/environments/preview/ unchanged (`config.json`, `topology.json`,
`byron-genesis.json`, `shelley-genesis.json`, `alonzo-genesis.json`, `conway-genesis.json`, downloaded
2026-10-14); `ConfigSuite` reads them with `--config`/`--topology` and verifies every hash. No private data and no third-party code is included. The repository's
Apache-2.0 applies to our own selection, re-encoding and packaging. See `../../NOTICE`.
