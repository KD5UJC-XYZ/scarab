### Changed (chain database, #1499)
- **Blocks are copied to the immutable store by a copy runner of their own, off the block-selection worker.** As the
  reference's ChainDB does, the worker now only logs each selected block and, once the new chain is published, hands
  the blocks it aged past `k` to the copy runner. The runner copies every block handed over so far in one round,
  promotes the endorser blocks they certify, schedules one collection of the received-block log, brings the UTxO store
  forward and takes a snapshot when one is due, and collects the selected-chain log. The worker no longer waits for the
  immutable append, the UTxO store's write or the collection's schedule. Until a block is copied, the chain served to
  peers and local clients keeps it, so a follower or a block range never misses it. A failure of the runner stops the
  node, as a failed collection does. After a kill that found the runner behind, the next start resumes `k` blocks past
  the immutable store's tip and selects the rest again from the received-block log (all of them in Praos mode, at most
  `k` past the tip with the limit on eagerness), as the reference's initial chain selection does. Under a backlog,
  one collection is now scheduled for each copy rather than for each extension of the chain, as in the reference
  (#1262). Nothing persisted changes format.
