### Changed (endorser block closure application, #1380)

- An endorser block's closure is applied without evaluating any predicate, as the reference's
  `ValidateNone` never evaluates a predicate's condition. The rules' judgment carries its
  validation policy, and every check of the Dijkstra rules runs only under `ValidateAll`.
- Every member of a Dijkstra annotated transaction but the transaction itself is made when first
  read, as the reference's are lazy: the scripts needed and provided, the resolved Plutus scripts,
  the languages, the legacy mode, the cache and the sub-transactions. A closure's transaction reads
  none of them but its sub-transactions.
