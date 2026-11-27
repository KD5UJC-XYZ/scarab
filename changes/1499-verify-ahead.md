### Changed (block selection, #1499)
- **The signature checks of the blocks waiting in the add-block queue are made ahead of the worker.** When a delivered
  block enters the queue, one verifier fiber checks its operational certificate, KES signature, VRF proofs and
  transaction witnesses into the verification cache, one block at a time in queue order and on at most half the
  verification threads (none on a machine with one processor). The block-selection worker then asks for a block's
  checks only where the cache does not hold every verdict of them (a block not checked ahead, or one whose verdicts a
  clear of the cache dropped), so a block checked ahead is no longer decoded and walked again on its path (about 6 ms of
  its 34 ms a block in mainnet Babbage were spent there). The verdicts are only cached: a bad signature in a queued
  block is refused when the worker applies that block, with the same refusal, order and blame as before, and a block
  the worker passes by is not checked or has its leftovers dropped. Four new counters,
  `scarab_preverify_ahead_hit_total`, `scarab_preverify_ahead_joined_total`, `scarab_preverify_ahead_miss_total` and
  `scarab_preverify_skipped_total`, say how the worker found each block it prepared and for how many it asked for no
  check.
