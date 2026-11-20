<!-- SPDX-License-Identifier: Apache-2.0 -->
# Network: servers (ChainSync, BlockFetch, KeepAlive, TxSubmission2, PeerSharing) and duplex

Part of the [compatibility matrix](../compatibility.md); the register entry is `network-servers-duplex` in [compatibility.json](../data/compatibility.json). The three sections below are the cells of the former matrix row, unchanged.

## Status as recorded

implemented: the first TxSubmission2 `Init` waits without a local deadline on an idle duplex bearer, matching the reference `StartOnDemand` behavior; active states remain bounded

## Evidence

unit/property (both directions, duplex, pipelining, timeouts, refusals; served-chain reconstruction property); devnet `relay` (Haskell relay synced solely from the Scala node, tip lag <= 1 in 59/59 samples)

## Explicitly unchecked or unconfirmed

on an inbound duplex connection the inbound governor runs our ChainSync, BlockFetch and KeepAlive clients as well (G2; devnet `relay` asserts it against the reference node); per-state byte/time, ingress and SDU bounds are recorded in [G5](../design/network-limits.md); PeerSharing runs on negotiated connections and publishes advertised, successful, zero-failure peers

## Differences decided on 2026-11-14 (#956)

- A responder that starts before the inbound governor records its connection is counted, as the counters exist from `NodeToNodeServer.Responders.create`. In ouroboros-network `c45735a5`, which cardano-node 11.1.3 links, the inbound governor's mux tracer finds no connection for such a responder and empties the connection's counters, after which its responders are no longer restarted nor its remote state updated. A race, fixed upstream by pull request [#5440](https://github.com/IntersectMBO/ouroboros-network/pull/5440), not reproduced ([#474 comment](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6747536849), [decisions](../decisions.md#networking-and-local-clients), [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md#amendment-2026-11-14-the-re-pins-deviations-decided)).

## BlockFetch serves the forks the node holds (#1237)

The BlockFetch server answers a range as the reference's ChainDB iterator streams it to its server
(ouroboros-consensus `82ecba329`, the same at `808e40054`: `Storage/ChainDB/Impl/Iterator.hs`,
`Storage/ChainDB/Impl/Paths.hs` and `MiniProtocol/BlockFetch/Server.hs` 87-146), over the blocks the
node holds: the immutable store, the selected volatile chain and the received log, which keeps every
block the node received or forged (as #1239 keeps forged blocks), held invalid too, until its file is
collected, as the reference's VolatileDB does. A block need not be on the selected chain to be served (`relay/HeldRange.scala`). ChainSync still
follows the selected chain alone, as the reference's follower does.

| The range asked for | Reference | Scarab |
|---|---|---|
| Upper bound newer than the immutable tip, and the walk back from it through the held blocks meets the lower bound | `StartBatch`, the blocks oldest first, `BatchDone` | the same |
| The walk leaves the held blocks at the immutable tip, or at a block that has reached the immutable chain since | the immutable chain's part, then the held part | the same |
| Upper bound not held, or a walk that cannot reach the immutable tip (`ForkTooOld`); upper bound no newer than the tip and not in the immutable chain (`MissingBlock`) | `UnknownRange`, answered `NoBlocks` | `NoBlocks` |
| Lower bound in a later slot than the upper, or a walk that reaches the start of the chain without meeting the lower bound | `InvalidIteratorRange` thrown | the connection ends (`RangeRefused`, `InvalidRange`) |
| Bounds the immutable chain refuses: a lower bound newer than the tip the walk joins, or a main block before the boundary block of its slot | the ImmutableDB's `InvalidIteratorRangeError` thrown | the connection ends (`InvalidRange`) |
| An end at Origin | `NoGenesisBlock` thrown | the connection ends (`NoGenesisBlock`) |
| A held block collected after the batch started | read from the immutable chain, its point checked against the path; if it is not there, `IteratorBlockGCed`, thrown as `BlockGCed`, with no `BatchDone` | the same (`Collected`) |

Each exception stops the reference's mux, which closes the connection (network-mux `Mux.hs` 458-463,
ouroboros-network `c45735a5`), and its rethrow policy makes each one `ShutdownPeer`
(`Node/RethrowPolicy.hs` 72-100): Scarab's server raises `NetworkError.RangeRefused`, the connection
ends, and the peer is tried again after a backoff (`FailurePolicy`). The received log's collection
deletes every file but the one being written whose newest slot is below the cutoff, and only those
files' blocks leave its index, as the reference's VolatileDB collects (`garbageCollectImpl`,
`garbageCollectFile`, `Storage/VolatileDB/Impl.hs` 453-543).

Evidence: `ChainServerSuite` (each outcome over a connection served by `NodeToNodeServer.start`, with a
held fork never selected, a block rolled back, a block held invalid, a range joining the immutable
chain, blocks collected during a batch, and a restart), `ServerSuite`, `ReceivedBlocksSuite` and
`PeerDefectPolicySuite`.

Not changed here: when the received log is collected. The reference collects its VolatileDB a delay after
blocks are copied to its ImmutableDB (`cdbsGcDelay`, 60 s, `Storage/ChainDB/Impl/Args.hs` 123) and
keeps 1,000 blocks to a file (`Storage/VolatileDB/Impl.hs` 186); Scarab collects when its immutable
store has made a batch of blocks durable (`volatileCollectBlocks`) and keeps 512 to a file, so a block
below the immutable tip may stay readable for a different time.
