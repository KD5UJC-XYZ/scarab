### Fixed (node-to-client UTxO queries before Conway, #677)
- **`GetUTxOWhole`, `GetUTxOByAddress` and `GetUTxOByTxIn` are answered in every Shelley-based era**, as the
  reference answers them, each output in its era's encoding (`toCBOR (UTxO era)`). They were refused before
  Conway. An output with a part its era's `TxOut` does not hold is refused, not written.
- **A new offline oracle, `scripts/oracle/utxo-answers`,** writes 100 UTxO maps with the released ledger encoders;
  `UtxoAnswersReferenceSuite` replays them byte for byte.
- **`devnet.py e2e` compares the whole UTxO.** Each same-point comparison asks `GetUTxOWhole` beside
  `DebugNewEpochState` on both nodes, compares every byte in every Shelley-based era and fails the run on a
  difference. `ledgerComparison.wholeState` in `e2e-summary.json` is now keyed by query, then era.
