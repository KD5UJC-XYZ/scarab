### Added (Dijkstra block, header and transaction codecs, #54)

- The node's block decoder reads a Dijkstra block (tag 8) as the Leios testnet's build does:
  `[header, [transactions, leios_cert / nil, peras_cert / nil]]`, each transaction decoded whole at
  decoder version 12. The node-to-node header codec and the header reader take a Dijkstra header, the
  twelve-field Leios header, hashed over its original bytes and signed over its body's.
- A Dijkstra transaction in the mempool form decodes (`[body, witnesses, auxiliary data]`, or with a
  validity flag before the auxiliary data, which must be `true`).
- Fuzz targets for each Dijkstra decoding entry point (`dijkstra-block`, `dijkstra-header`,
  `dijkstra-tx-submitted`, `dijkstra-tx-in-block`, `dijkstra-tx-body`, `dijkstra-tx-witnesses`).

### Changed (#54)

- A Dijkstra block, header or transaction is no longer refused when decoded. The node still does not
  enter Dijkstra: a decoded Dijkstra block is refused by the ledger rules (`DijkstraNotYet.Rules`),
  as is a Dijkstra transaction read into the transaction types the rules consume, and BlockFetch
  checks a Dijkstra block against its header as the reference's `blockMatchesHeader` does.
