### Fixed (ChainSync server: an intersection found while the node switches forks)
- **A `MsgFindIntersect` is answered against the chain as it is when the follower moves.** The server read its
  chain once, checked the offered points against that copy (reading the immutable store for those outside the
  volatile chain), and then moved the follower to the point it found without looking again. A fork switch of the
  node's own chain selection in between could leave the follower on a block that had left the chain and was never
  stored: the first `MsgRequestNext` rolled the client back to it, and the one after stopped the node with a
  storage failure. As the reference's `checkIfPointOnChain` does (ouroboros-consensus 82ecba32,
  `Storage/ChainDB/Impl/Follower.hs` 457-525), each offered point of the volatile chain is now checked and the
  follower moved in one update; a point older than the volatile chain is looked up in the immutable store, which
  never drops a block, before the follower moves to it. The tip in the reply is read after the follower moved, as
  in the reference's `handleFindIntersect`. `IntersectForkSuite` holds the store's lookup of a point outside the
  volatile chain while the chain forks: before the change the node reported a storage failure, after it the reply
  is `IntersectFound` at the next offered point still on the chain, or `IntersectNotFound`.
