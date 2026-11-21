### Added (the Leios build, #54)

- `import-haskell-db` takes `--reference-build`. Under `leios-b004fe0c` the network setup is that build's, and
  the chain's Dijkstra blocks are stored as they are, with every other block's checks; applying one stays
  refused by the rules. A release build still refuses a Dijkstra block before storing it (`EraNotYet`).
- `LeiosReplayMain` (tools): the offline replay of a window of the public Leios testnet against the
  reference's ledger states (gate G1). It imports the chain and the start state, applies the blocks with the
  node's own rules, exports the state it reaches and compares `state`, `tables` and `meta` byte for byte with
  the end state, reporting the first difference. Until the rules apply Dijkstra blocks it halts at the first
  block after the start, with that block's slot.
