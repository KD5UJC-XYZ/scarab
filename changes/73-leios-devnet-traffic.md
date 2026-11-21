### Added (reference Leios devnet traffic, #73)

- `devnet.py leios-traffic` starts the reference-only Dijkstra cluster with pools that register BLS keys on chain and vote with them, and loads it past the ranking block's capacity, so the reference nodes announce endorser blocks, vote on them and certify them. It passes only when the chain every node holds has an announcing block and a certifying block, every node's LeiosDB holds the certified endorser block's closure, and the logs show votes. No Scala node takes part yet.
