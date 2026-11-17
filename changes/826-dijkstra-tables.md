### Added (ledger, #826)

- The MemPack `TxOut` of a ledger snapshot's `tables` file is read and written in the Dijkstra era
  as the Leios build (cardano-ledger `9df9f98c`) packs it: Babbage's output with Dijkstra's script,
  a PlutusV4 reference script as language tag 3 and a native script, a guard among its forms, as
  the bytes the output holds it in. The earlier eras still refuse PlutusV4. The Leios devnet's first
  Dijkstra `tables` and every output of the Leios testnet corpus pack back byte for byte.
