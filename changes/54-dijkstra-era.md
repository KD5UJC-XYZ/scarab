### Added (the Dijkstra era, named but not entered, #54)

- The node knows the Dijkstra era of the Leios testnet build (index 7, block tag 8; cardano-ledger
  9df9f98c, ouroboros-consensus 2416493b): its era witness takes Conway's ledger-state records, the
  hard-fork combinator lists it after Conway with first protocol version 12, and
  `TestDijkstraHardForkAtEpoch` is carried into the hard-fork configuration.
- The node does not enter Dijkstra: its trigger never fires while the maximum major protocol version
  is 11, so the chain stays in Conway and the era summary announces no end of Conway. A Dijkstra
  header in the closed header codec, a block of tag 8, a Dijkstra transaction, a Dijkstra ledger state
  in the Haskell `state` layout, the translation from Conway, a genesis start in Dijkstra and its
  EPOCH rule are refused with a typed `DijkstraNotYet` cause. The Leios header envelope keeps reading
  index 7 as raw bytes.

### Changed (storage, #54)

- Ledger snapshots are format V9 (`SCRBSNP9`): the V8 layout whose sidecar's eras include Dijkstra.
  V8 snapshots, and V5 and V7 snapshots through V8, migrate on open with their binding, sidecar values
  and Haskell bytes unchanged; the previous readers read eras with a frozen codec, so every previous
  file reads as it was written.
