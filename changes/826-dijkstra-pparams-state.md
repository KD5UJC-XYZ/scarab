### Added (storage, #826)

- Dijkstra's protocol parameters in the Leios build's `state` layout: the 54 parameters of its
  `PParams` (cardano-ledger 9df9f98c), read and written byte for byte, as the governance state's
  current and previous parameters, its future parameters and its enact state's. The governance and
  enact state codecs take the parameters of the era and layout they are read in. Dijkstra's
  parameters of the 11.1 line, and the rest of the Dijkstra state, are not read yet.
