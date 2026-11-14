### Changed (Dijkstra genesis, #54)

- With `ExperimentalHardForksEnabled`, the node now reads the Dijkstra genesis file named by `DijkstraGenesisFile` at start-up, as the Leios testnet build of the reference does, and refuses to start when the file is one the reference refuses: a field missing, of another JSON type or out of its type's range, or a PlutusV4 cost model without exactly 251 parameters. The node still does not enter Dijkstra.
