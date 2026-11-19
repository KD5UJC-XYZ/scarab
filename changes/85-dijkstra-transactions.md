### Added (era types, #85)

- Dijkstra's transactions on the era types, of the Leios testnet's build (cardano-ledger 9df9f98c): the body of both levels, a sub-transaction's without fee, collateral or sub-transactions, with the body classes up to `DijkstraEraTxBody`; the witness set and auxiliary data with Dijkstra's scripts; and the transaction, its sub-transactions by id in the order read. They are read from what the block decoder reads and agree with it on every Dijkstra transaction the repository holds. No rule uses them yet.
