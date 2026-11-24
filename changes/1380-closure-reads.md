### Changed (endorser block closure application, #1380)

- A closure's transaction reads its inputs' stored outputs once for its annotation and Dijkstra's
  LEDGER both, as the reference annotates and judges against one UTxO set; UTXO's update still
  reads the outputs it spends.
- Collecting a transaction's scripts with their contexts builds no cost-model lookup and reads no
  redeemers when the transaction uses no Plutus script.
