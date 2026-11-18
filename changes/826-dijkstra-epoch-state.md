### Added (the Leios build, #826)

- In the `state` layout of `--reference-build leios-b004fe0c`, Dijkstra's `NewEpochState` is read
  and written at version 12, byte for byte on the devnet's first Dijkstra state and on the public
  Leios testnet's state at epoch 100, whose 154 pools all hold voting keys. A pool's margin, a
  committee's threshold and a proposal's parameter update are read as the Leios build reads them
  from version 12, a bounded ratio as two `Word64` numbers; a pool's future parameters may have a
  `null` third item. A pool's BLS key in its parameters, and a proposal that updates a parameter
  Dijkstra adds, are refused as not read yet. A whole `state` file in Dijkstra is still refused, and
  the `11.1` build is unchanged.
