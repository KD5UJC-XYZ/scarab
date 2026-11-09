### Added (the crossing into Dijkstra, #823)

- The pieces of the crossing from Conway into Dijkstra besides the ledger state, as pure functions
  mirroring the Leios testnet build: a Conway transaction carried into Dijkstra, both as the ledger's
  `TranslateEra DijkstraEra (Tx TopTx)` translates it and as the hard-fork mempool rematches a held
  transaction (which, unlike the first, refuses one whose validity flag is false); the
  chain-dependent state translated from Praos into Praos2; and the Dijkstra ledger view forecast
  from a Conway ledger state across the boundary. None is wired: the node still does not enter
  Dijkstra, and its mempool accepts Conway transactions only.
