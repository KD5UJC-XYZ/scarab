### Changed (endorser block closure application, #1380)

- A closure's transaction reads its inputs' stored outputs once, and both its annotation and
  Dijkstra's LEDGER use that one UTxO set, as the reference annotates and judges against one set.
- Collecting a transaction's scripts with their contexts reads its redeemers and the cost models only
  when it uses a Plutus script.
