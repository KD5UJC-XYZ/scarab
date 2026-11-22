### Fixed (ledger, #1334)

- The ledger's network is now the Shelley genesis's `networkId`, as the reference's `Globals` take it, and no longer
  one decided from the network magic. A genesis whose `networkId` disagrees with its magic (a test network with
  `networkId: Mainnet`, or mainnet's magic with `networkId: Testnet`) is judged on its `networkId`'s network by the
  wrong-network checks of outputs, withdrawals, reward accounts and proposals, the mainnet-only header check and
  the node-to-client pool queries. Mainnet, preprod, preview and the Leios testnet, where the two agree, are
  unchanged.
