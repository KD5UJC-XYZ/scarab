### Changed (performance, #85)
- **The node prepares a block's scripts for its parallel workers only when the redeemers declare enough work for it
  to pay.** Below 200,000,000 declared CPU steps, summed over the block, the block's body runs serially: on the
  recorded Leios testnet blocks, whose two or three scripts are cheap, preparing them was 5 to 30% slower than the
  serial run. The floor applies to Dijkstra's and Conway's era block runs and to the earlier eras' rules alike, and
  changes no verdict, state or counter; ADR 0018's amendment and the parallel-Plutus design record the
  measurements, made with the new `PlutusThresholdBench` (tools) and `ConwayBodiesReplaySuite`.
