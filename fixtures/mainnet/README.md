# Mainnet Byron fixtures

Captured with `lab.node.tools.CaptureMain` from `backbone.cardano.iog.io:3001` (magic 764824073) on
2026-10-10 and 2026-10-11.

| File | Contents |
|---|---|
| `byron-ebb-header.hex` | Raw header of the genesis epoch-boundary block (block 0). Its full block is 648 KB, so only the header is kept. |
| `byron-main-blocks.hex` | The next three blocks (Byron main blocks, tag 1), full `[1, block]` encoding, contiguous. |
| `byron-tx-blocks.hex` | Three Byron main blocks with transactions, full `[1, block]` encoding, not contiguous: slot 3313 (epoch 0, block 3314: the redemption of an AVVM balance, one redeem witness, no fee), slot 432001 (epoch 20, one transaction with a verification-key witness) and slot 432004 (epoch 20, two transactions, one with two inputs). Each was captured with `--blocks 1 --from <parent slot>.<parent hash> --byron-epoch-slots 21600`. |
| `byron-tx-inputs.json` | The outputs those transactions spend (transaction id, index, address encoding, lovelace), as the Koios API reports them (`tx_info`; its `outputs` array is not in index order, the `tx_index` field is). The AVVM entry is the genesis balance of the redeem key. |
| `byron-genesis-trimmed.json` | The public mainnet `byron-genesis.json` (https://book.play.dev.cardano.org/environments/mainnet/) with `avvmDistr` cut to the one entry the slot 3313 block spends (the full file has 14,505 entries, 1 MB). Its canonical hash is therefore not the real genesis hash. |

`ByronMainnetSuite` decodes the blocks, checks the body proofs against the headers, derives the AVVM
redeem address and the genesis UTxO (the pseudo transaction id and address equal what the chain
spent), and validates every transaction: witness signatures under the Byron signing tags and the
protocol magic, address roots, value conservation and the minimum fee.

`genesis/` holds the public mainnet node configuration from
https://book.play.dev.cardano.org/environments/mainnet/ unchanged, downloaded 2026-10-14: `config.json`,
`topology.json`, `shelley-genesis.json`, `alonzo-genesis.json` and `conway-genesis.json`. The Byron genesis file
(1 MB) is not kept: `ConfigSuite` checks the Shelley, Alonzo and Conway hashes against the files, and the
Byron hash against the full file when one is present at .workspace/data/2026-10-14-configs/mainnet.

## Provenance and licence

Public Cardano mainnet chain data, fetched over node-to-node from the public IOG backbone relay and
stored unchanged. `byron-tx-inputs.json` contains data from the Koios public API
(https://koios.rest), which itself reports public chain state; Koios is credited as the source and is
not affiliated with this project. Blockchain data is public; no private data and no third-party code
is included. The files carry no separate licence beyond the repository's Apache-2.0 for our own
selection and packaging. Not affiliated with or endorsed by any Cardano organisation (see
`../../NOTICE`).
