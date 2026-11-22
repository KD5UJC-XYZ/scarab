### Added (Leios transaction cache, #54)

- Under `--reference-build leios-b004fe0c` the node keeps the reference's LeiosTxCache: the
  announcements of the 128 newest endorser blocks, their bodies and the transactions those list, each
  transaction recorded as fetched or as validated under the parity of a protocol version, by the
  mempool (the transactions a body pulls from it) or by the voting thread (those it validated in
  full), and evicted before each collection of the endorser-block store.
- The closure check before a vote reapplies each transaction the cache holds validated under the
  parity of the state it meets, without the static checks (witnesses, scripts), as the reference's
  `reapplyTx` does, and validates every other one in full: on the testnet's recorded closure of 323
  transactions, about half the time of the full check when all are cached.
- The log line `leiosEbValidated` (`ebHash`, `ebSlot`, `reapplied`, `applied`, `duration`) for each
  closure checked valid before a vote.
