### Added (the Dijkstra era, #54)

- The translation of a Conway ledger state into Dijkstra, as the Leios testnet build's
  `TranslateEra DijkstraEra` (cardano-ledger 9df9f98c): every set of protocol parameters takes the
  Dijkstra genesis values and its PlutusV4 cost model, the pools' VRF key hashes are counted afresh,
  and the native reference script of each output the state holds in memory is encoded anew, as
  Dijkstra's `upgradeTimelock` does, which changes the hash of a script that was written in another
  form. After a crossing block is validated, the same upgrade includes outputs in the backing
  store, with their old bytes retained for rollback (#815). The node still does not enter Dijkstra;
  a Conway state crossing into it without
  a Dijkstra genesis is refused.
- The protocol parameters Dijkstra adds are the node's parameters' own fields; the Dijkstra genesis
  holds their initial values.
