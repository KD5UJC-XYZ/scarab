### Fixed (Leios block transactions as the Leios testnet's build decodes them, #750)

- Each transaction of a Leios block is decoded as the build of the public Leios testnet decodes it
  (`decodeDijkstraTopTxInBlock` at decoder version 12, cardano-ledger `9df9f98c`): its body with the
  Dijkstra fields (guards, sub-transactions, required guards, direct deposits, account balance
  intervals), outputs, certificates (a pool's voting key included), votes, proposals and Dijkstra
  protocol parameter updates, its witness set (Plutus V4 scripts, redeemers as a map only, no
  duplicate in any set) and its auxiliary data. A block whose transaction that build refuses, such
  as `[0, 0, null, true]`, is refused; before, only the four items and the flag were checked.
- Every part keeps its original bytes: a transaction's and a sub-transaction's id is the hash of its
  body's bytes, and a script's and datum's hash is over its own. No Dijkstra ledger rule is applied.
- The Leios testnet oracle reads a transaction alone, and its corpus holds the testnet's
  transactions and synthetic ones at each decoder's boundary, compared case by case.
- Embedded CBOR in a reference script or inline datum has no additional byte or item budget;
  its enclosing input bounds the decoder and the ledger applies its size rules afterwards.
