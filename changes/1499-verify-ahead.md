### Changed (block selection, #1499)
- **The signature checks of the blocks waiting in the add-block queue are made ahead of the worker.** When a delivered
  block enters the queue, one verifier fiber checks its operational certificate, KES signature, VRF proofs and
  transaction witnesses into the verification cache, one block at a time in queue order and on at most half the
  verification threads (none on a machine with one processor), so that the block-selection worker finds the verdicts there instead of waiting for them (about
  6 ms of its 34 ms a block in mainnet Babbage). The verdicts are only cached: a bad signature in a queued block is
  refused when the worker applies that block, with the same refusal, order and blame as before, and a block the worker
  passes by is not checked or has its leftovers dropped. Three new counters,
  `scarab_preverify_ahead_hit_total`, `scarab_preverify_ahead_joined_total` and `scarab_preverify_ahead_miss_total`,
  say how the worker found each block it prepared.
