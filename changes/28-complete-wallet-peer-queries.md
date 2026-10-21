### Added (complete wallet peer queries, #28)

- Implement the remaining Conway wallet reward and ledger peer snapshot queries with native Haskell
reward ranking, current-state stake, exact monetary arithmetic and negotiated legacy/SRV encodings.
Retain typed all/big peer kinds and require the actual network magic in the query environment.
