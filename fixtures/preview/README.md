# Preview testnet block fixtures

Raw node-to-node BlockFetch blocks (`[era_tag, block]`), one hex-encoded block per line, captured
with `lab.node.CaptureMain` from the public relay `preview-node.play.dev.cardano.org:3001`
(network magic 2) on 2026-10-10. Every captured block was at least 10 blocks deep.

| File | Contents |
|---|---|
| `alonzo-first-20.hex` | The first 20 blocks from Origin (heights 0-19). Alonzo era (tag 5), TPraos headers, protocol 6. Contiguous. Includes two blocks with one transaction each. |
| `alonzo-txs.hex` | Two single Alonzo blocks (heights 4330 and 8645) that each contain one transaction. Not contiguous. |
| `babbage-transition.hex` | 30 contiguous blocks starting at height 13011: the last Alonzo block (TPraos) followed by Babbage blocks (tag 6, Praos headers, protocol 8). |
| `conway.hex` | 30 contiguous Conway blocks (tag 7, Praos headers) starting just after height 2645975, captured with `--from 64195073.f1ce3cbb...`. Includes blocks with transactions. |

Preview has no Byron, Shelley, Allegra or Mary blocks: the chain forks through those eras at
epoch 0 and the first block is already Alonzo.
