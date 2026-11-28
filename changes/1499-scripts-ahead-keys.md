### Changed (block selection, #1499)
- **The rules take a transaction's script collection made ahead of the selection worker, and the speculator keeps
  up.** The speculator keeps each transaction's collection of its scripts (the flat rules' requests, or Conway's
  scripts with their contexts) with everything it was collected from: the era, the transaction, the parameters, the
  scripts it has and the outputs it spends and reads (for Conway the protocol version, the outputs, the transaction,
  the scripts used and the cost models), and the answers the clock gave to the slots it asked for. The rules take a
  kept collection only where their own inputs are equal and their clock gives the same answers, which makes it their
  own collection; then they neither build nor compare the script contexts, and their evaluator finds each result by
  the request itself. Otherwise they collect as before. The speculator now reads and collects the next block while
  the scripts of the one before run, and evaluates two blocks' scripts at once, on the same threads.
  New counters: `scarab_plutus_ahead_collection_offered_total`, `scarab_plutus_ahead_collection_miss_total`,
  `scarab_plutus_ahead_hit_by_request_total`, `scarab_plutus_ahead_collected_total`,
  `scarab_plutus_ahead_collections_full_total`, and `scarab_plutus_ahead_reached_total` by where the speculation of
  a block was when the worker reached it.
