### Removed (fiber boundary, #132)
- **`PeerFailure`, `Peer.carrying`, `Peer.carryingNetwork` and `Peer.landed`.** The exception that carried a typed
  peer error across the fibers of a connection (the bridge of #130) is gone. A fiber runs its body in an error
  scope of its own (`Fibers.scoped`, `Fault.fiber`) and finishes with an `Either`; the value crosses the fiber
  boundary in a `Deferred`, the session's queue of batches or `Mux.closed`, and the fiber's owner raises it again in
  its own fiber. A multiplexer channel and the clients over it (`ChainSyncClient`, `BlockFetchClient`,
  `KeepAliveClient`) no longer hold the `Raise` of whoever opened the connection: `send`, `recv` and the clients'
  operations take the `Raise` of their caller, and so do `Peer.intersect`, `next` and `fetch`. The fibers changed are
  the multiplexer's reader and writer, the KeepAlive pinger, the TxSubmission2 client, the four server fibers of
  `NodeToNodeServer`, the node-to-client servers, the producer and the block-fetching fibers of a session, the
  connection and the hot session of `OutboundPeers` and the session of `InboundGovernor`. The rule is in `AGENTS.md`
  and [ADR 0017](../docs/adr/0017-fibers-end-with-values.md); `FailsOnUncaught` now guards every suite of the network
  module, `FollowerStallSuite`, `SessionFailureSuite` and `PipelineBackpressureSuite`.

### Changed (multiplexer ingress queues, #132)
- **The ingress queue of a mini-protocol is counted in bytes, has the limit of the reference, and never waits for
  its reader.** It was 16 SDUs, and a full queue stopped the reads of the socket for every protocol of the
  connection. Now a peer that has more unread bytes queued on a protocol than its limit (ChainSync 462,000,
  BlockFetch 23,068,694, TxSubmission2 721,424, KeepAlive 1,408, Handshake 5,760; node-to-client unbounded;
  `IngressLimits`, from ouroboros-network 1.2.0.0, network-mux 0.11.0.0) ends the connection with
  `NetworkError.IngressLimit`, a protocol violation, and the governor forgets the peer. What holds a fast peer back
  is the protocol and the session's pipeline: a session applying slowly keeps `Peer.Depth` batches in flight at most
  (`PipelineBackpressureSuite`). `Mux.closed` is a typed `NetworkError` and `Mux.abort` takes one; the
  connection's close reason reaches the governor as the peer's error that it is (a framing error, an overrun and a
  closed bearer are classified differently).

### Fixed (session outcomes and the tip, #154)
- **A session ends with a closed set of outcomes, and none of them defaults to "the peer is unavailable".** The
  catch-all around the hot session classified a block our rules refused, a storage failure and a bug in the node as a
  lost connection, tried again like any other. `SessionEnd` is `Returned | Failed | Rejected | Storage | Internal |
  Defect | Cancelled | Stopped`; what the node's own scope raises (rules, stores) is caught in the session's fiber and
  placed by `SessionEnd.of`; `FailurePolicy.disposition` gives each a policy. A peer that sent a block our rules
  refuse is forgotten (`PeerError.InvalidBlock`); storage, an internal failure and an exception that is not ours
  stop the node with a `fatal` event (`Node.Fatal`, severity Error), after the shutdown snapshot, with exit code 5
  (storage), 6 (internal) or 7 (defect), as the reference stops its node for a storage failure
  (`consensusRethrowPolicy`, `ShutdownNode`). A restarting supervisor resumes from the database.
- **The tip is published after the blocks are stored.** `Follower.commit` wrote the chain, then the storage; a
  storage failure left a published tip that was not on disk. It persists first, as the reference's ChainDB writes to
  the VolatileDB before chain selection moves the tip.
- **A block refused by the rules is blamed on the session that delivered it.** The pool of fetched blocks is shared
  by the sessions of all peers, so the session that applied a bad block was not always the peer that sent it. The pool
  now remembers who delivered each block; the session that took a refused block ends as `Diverged` (tried again, it
  fetches the block itself), the blocks that the deliverer left in the pool are dropped, and the deliverer is told
  and ends as `InvalidBlock`.
