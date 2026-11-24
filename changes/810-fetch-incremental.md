### Fixed (BlockFetch candidate bookkeeping, #810)
- **A ChainSync batch no longer rebuilds what it knows of the session's whole candidate.** Each batch built a set
  of every retained point to filter the heights FindIntersect may offer, and each announcement checked the slot
  order of the whole candidate. A session now carries the candidate's index from batch to batch: the slot order,
  the retained points and each header as BlockFetch sizes it change only by the headers the batch adds or
  removes. A candidate put in place from outside, or a rollback to a point it does not hold, is scanned once, as
  before.
- **The BlockFetch decision no longer rebuilds each peer's fragments or our chain's point set at every round.**
  The fragments are slices of the sized headers the candidate's index already holds. The view of our chain is
  built once per chain the selection publishes. A peer whose candidate and our chain are those of the last
  decision keeps its plausibility and intersection. The suffix after the intersection is no longer asked whether
  each header is on our chain, which none of them can be. The fetched blocks are still asked about at every
  decision. The decisions are unchanged.
