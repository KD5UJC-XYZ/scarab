### Changed (block selection, #1499)
- **The Plutus scripts of the blocks waiting in the add-block queue are evaluated ahead of the worker.** When a
  delivered block of a Shelley-based era before Dijkstra enters the queue, one speculator fiber collects its scripts'
  requests as the rules collect them, on the parameters, era history and UTxO set of the selected chain at the worker's
  tip and the outputs of the blocks queued before it, and evaluates them on threads of its own (a quarter of the
  processors, at most two, none below four), one block at a time in queue order. The results are kept, within bounds of
  blocks, entries and bytes, under their whole request (script, arguments with the script context, cost model,
  protocol version and budget), and the block rules' evaluator gives a kept result only for a request equal to it, so a
  speculation on another fork, an input that changed or parameters changed at an epoch boundary is simply not found and
  the script is evaluated as before. Answers that are no verdict are never kept. A block's results are dropped once the
  worker is done with it or passes it by. Five new counters, `scarab_plutus_ahead_hit_total`,
  `scarab_plutus_ahead_miss_total`, `scarab_plutus_ahead_stale_total`, `scarab_plutus_ahead_evaluated_total` and
  `scarab_plutus_ahead_skipped_total`, say how the rules found their scripts and what the speculator did.
