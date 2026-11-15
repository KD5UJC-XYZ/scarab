### Changed (performance, #1024)

- A block producer builds the VRF proof only for a slot it leads. Each slot's leader check computes the VRF output alone and compares it with the threshold of the pool's stake; only a won slot is then proved. Every slot's decision, and a forged header's VRF output and proof, are the same bytes as before, and the traces, logs and metrics are unchanged; a lost slot no longer spends work on a proof it does not use.
