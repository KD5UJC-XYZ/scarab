### Added (era types, #85)

- Dijkstra's accounts and certificate state on the era types, of the Leios testnet's build (cardano-ledger 9df9f98c): Conway's records and lenses, and the deposits and refunds of a top-level transaction with its sub-transactions, counted over the whole batch. On the 176 transactions without sub-transactions, recorded on the public Leios testnet or among the reference's UTXOW cases, that the existing Conway decoder reads, they equal the existing path's; crafted batches check the sums over sub-transactions. The node still judges with the existing rules.
