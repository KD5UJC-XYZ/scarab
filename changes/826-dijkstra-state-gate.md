### Added (the Leios build, #826)

- In the `state` layout of `--reference-build leios-b004fe0c`, a whole `state` file in Dijkstra is
  read and written: its telescope, its `NewEpochState`, the header state's Dijkstra tip and its
  Praos2 chain-dependent state. The devnet's first Dijkstra state and the public Leios testnet's at
  epoch 100 are written again byte for byte, and `import-ledger-snapshot` and
  `export-ledger-snapshot` take a Dijkstra snapshot under that build. A Dijkstra snapshot of a real
  database cannot be imported yet, because `import-haskell-db` still refuses a Dijkstra block. A
  Peras epoch context with Dijkstra's voting committee is refused as not read yet. The `11.1` build
  still refuses a Dijkstra state.
