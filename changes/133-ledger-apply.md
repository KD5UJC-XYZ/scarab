### Changed (ledger application on mainnet, #133)
- **The follower's time per block in late Alonzo fell in the places the profile named.** A 10-minute JFR of the mainnet follower
  (epoch 327) put 10.5% of the samples in `ChainSelection.decide` (the points of every header of a lagging peer's ever-growing
  candidate were built per header; now the answer comes from the first probe from the tip: 9,200 us -> 35 to 57 us at 50,000
  headers), 23% in the Plutus machine, whose `plutus-evaluator` threads (a 256 MB stack each) were started per script, 220 a
  second (now pooled), and 11% in the transaction rules: `Value.+` rebuilt both maps (now folds the entries of the right
  operand into the left), `TxIn`, `Credential` and byte orders built hex strings or `Vector[Byte]`s per comparison (now
  `Bytes.compareTo`, also the `Ordering[Bytes]` given), and the script integrity views were encoded again per transaction (kept
  per cost model). Results are unchanged: property tests keep the old definitions as the reference. Offline on 400 captured
  epoch-320 blocks, the rules take 30.5 -> 20.3 us per transaction and phase 2 of the recorded cases 69.9 -> 55.9 ms; the
  effect on the mainnet rate is for a JFR after deploy (docs/performance.md section 17). `ApplyBenchMain` and
  `DecideBenchMain` (tests) repeat the measurements.
