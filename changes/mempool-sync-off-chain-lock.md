### Changed (mempool, chain selection)

- The mempool is validated again at a new tip from a watcher of its own on the chain's tip, as the reference's
  mempool is from the watcher `openMempool` forks. Committing a block, adopting a block this node forged, the
  GSM's comparison of a candidate and a state query's acquire no longer wait for the mempool. The watcher looks
  once at start and then at each change of the tip, and the pool reads the chain once its lock is held, so a
  revalidation that waited applies the chain as it is then; the pool can lag the chain for as long as one waits,
  and the forger validates the held transactions again at its slot, as before. A failure of the revalidation
  still stops the node.
