# Mainnet Byron fixtures

Captured with `lab.node.tools.CaptureMain` from `backbone.cardano.iog.io:3001` (magic 764824073) on
2026-10-10.

| File | Contents |
|---|---|
| `byron-ebb-header.hex` | Raw header of the genesis epoch-boundary block (block 0). Its full block is 648 KB, so only the header is kept. |
| `byron-main-blocks.hex` | The next three blocks (Byron main blocks, tag 1), full `[1, block]` encoding, contiguous. |

## Provenance and licence

Public Cardano mainnet chain data, fetched over node-to-node from the public IOG backbone relay and
stored unchanged. Blockchain data is public; no private data and no third-party code is included. The
files carry no separate licence beyond the repository's Apache-2.0 for our own selection and
packaging. Not affiliated with or endorsed by any Cardano organisation (see `../../NOTICE`).
