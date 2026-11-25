### Changed (Mempool, #1380)

- The mempool keeps what its transactions count for against its capacity as they are added and removed, and their ids in a set, as the reference keeps its sequence's measure and `isTxIds`. Before, each admission measured every held transaction again (summing each one's execution units) and looked a transaction up by scanning the pool. On the public Leios testnet that was 12.5% of all of pool1's CPU samples while the pool held a few thousand transactions.
