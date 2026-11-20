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
node holds: the immutable store, the selected volatile chain and the received log. The received log
keeps the originals admitted to it: a block received from a peer, or forged (#1239), unless it is held
already, known invalid or no younger than the immutable tip when it arrives, as the reference's ChainDB
adds a block to its VolatileDB (`Storage/ChainDB/Impl/ChainSel.hs` 425-451). It keeps them, refused
by validation later or not, until their file is collected. A block need not be on the selected chain to be
served (`relay/HeldRange.scala`): the walk follows each held block's predecessor by its hash, whatever
its slot, and reads the selected chain and the received log as they were at one instant, as the
reference walks one snapshot of its VolatileDB's index (read under the lock the chain's changes are
published under when the chain keeps changing). A walk that comes back to a block already on its path
before it meets the lower bound is answered `NoBlocks`, where the reference's walk would not end (`Paths.hs` 188-218 and 336-342): an Improvement
of class (a) ([#474](https://github.com/KD5UJC-XYZ/scarab/issues/474#issuecomment-6880015076)). ChainSync still follows the selected chain
alone, as the reference's follower does.

| The range asked for | Reference | Scarab |
|---|---|---|
| Upper bound newer than the immutable tip, and the walk back from it through the held blocks meets the lower bound | `StartBatch`, the blocks oldest first, `BatchDone` | the same |
| The walk leaves the held blocks at the immutable tip, or at a block that has reached the immutable chain since | the immutable chain's part, then the held part | the same |
| Upper bound not held, or a walk that cannot reach the immutable tip (`ForkTooOld`); upper bound no newer than the tip and not in the immutable chain (`MissingBlock`) | `UnknownRange`, answered `NoBlocks` | `NoBlocks` |
| Lower bound in a later slot than the upper, or a walk that reaches the start of the chain without meeting the lower bound | the ChainDB's `InvalidIteratorRange` thrown | the connection ends (`RangeRefused`, `InvalidIteratorRange`) |
| Bounds the immutable chain refuses: a lower bound newer than the tip the walk joins, or a main block before the boundary block of its slot | the ImmutableDB's `InvalidIteratorRangeError` thrown | the connection ends (`InvalidIteratorRangeError`) |
| An end at Origin | `NoGenesisBlock` thrown | the connection ends (`NoGenesisBlock`) |
| A held block collected after the batch started | read from the immutable chain, its point checked against the path; if it is not there, `IteratorBlockGCed`, thrown as `BlockGCed`, with no `BatchDone` | the same (`BlockGCed`) |

Each exception stops the reference's mux, which closes the connection (network-mux `Mux.hs` 458-463,
ouroboros-network `c45735a5`), and its rethrow policy makes each one `ShutdownPeer`
(`Node/RethrowPolicy.hs` 72-100). Scarab's server raises `NetworkError.RangeRefused` and the connection
ends; the peer's failure is `Transient` (`FailurePolicy`): it is tried again after the ordinary error
delay, and like any failing peer it is forgotten once its failures reach the normal retry limit. The
received log's collection deletes every file but the one being written whose newest slot is below the
cutoff, and only those files' blocks leave its index, as the reference's VolatileDB collects
(`garbageCollectImpl`, `garbageCollectFile`, `Storage/VolatileDB/Impl.hs` 453-543).

Evidence: `ChainServerSuite` asserts each outcome above over a connection served by
`NodeToNodeServer.start`, with a held fork never selected, a block rolled back, a block held invalid,
a path whose slots do not rise, a range joining the immutable chain, a tip that moves between the walk
and the join, blocks collected during a batch, a change between the reads of the held blocks, and a
restart; `ServerSuite`, `ReceivedBlocksSuite` and `PeerDefectPolicySuite`. These assert the reference's
outcomes as its source at the pins gives them; no running reference node is asked for a fork.

The received log is collected and recovered as the reference's VolatileDB (#1247). Each copy of
blocks to the immutable store schedules a collection below the new immutable tip's slot, timed from the
end of the copy, run 60 s later, rounded up to a 10 s mark; a copy for the same 10 s mark as the last
one scheduled replaces it, and nothing of the schedule survives a restart, where the next copy's
collection covers it (`cdbsGcDelay`, `cdbsGcInterval`, `Storage/ChainDB/Impl/Args.hs` 119-124;
`copyToImmutableDBRunner`, `scheduleGC` and `gcScheduleRunner`, `Storage/ChainDB/Impl/Background.hs`
257-296 and 466-652, ouroboros-consensus `82ecba329`; the same code at `808e40054`). The copy that
schedules a collection is not the reference's: Scarab copies, and schedules, for each persisted extension
that ages blocks past `k`, where the reference's copy runner copies the whole prefix then eligible and
schedules once, so under a backlog the two can schedule other cutoffs at other times, a Gap
([#1262](https://github.com/KD5UJC-XYZ/scarab/issues/1262)). Each schedule and each collection is logged
(`scheduledGC`, `performedGC`), at Debug, as the reference traces `ScheduledGC` and `PerformedGC`
(`Storage/ChainDB/Impl/Types.hs` 1049-1054 at `82ecba329`, 1082-1087 at `808e40054`; cardano-node 11.1.3
`Cardano/Node/Tracing/Tracers/ChainDB.hs` 1183-1184). Collections run one
at a time, so one an interval is a goal, as the reference calls it (`Background.hs` 486-492): a
collection that runs past the next entry's time leaves the entries due meanwhile queued, one to a 10 s
mark, and they run back to back after it. Scarab forces its immutable store to disk before each
collection, an approved Deviation ([decisions register](../decisions.md#genesis-storage-and-keys),
2026-11-20): the reference forces nothing and relies on the 60 s delay to let the copy reach the disk
(`Storage/ChainDB/Impl/Args.hs` 80-82; `garbageCollectBlocks`, `Background.hs` 431-448). A collection
that fails, the force included, or an exception in the fiber that runs them, stops the node. A file
holds 1,000 blocks, and the next one is started as soon as one fills, so a full file can be collected
before another block arrives (`volMaxBlocksPerFile`, `putBlockImpl`, `Storage/VolatileDB/Impl.hs` 186 and
387-399); files an earlier build wrote keep their 512-block boundaries, and the last of them, the one
being written, is reopened under the 1,000-block limit and filled to 1,000. The selected-chain log shares the
writer: its files of 512 blocks are now rolled at the block that fills them too, so a full one can be
collected at the next 4,096-block trigger before another record is written. When the log is opened, the
files of its directory whose names it does not read are logged and left alone, as the reference traces
`InvalidFileNames` (`receivedInvalidFileNames`; `mkOpenState`, `State.hs` 319-339), and a name that
reads as a file number but is not the name the log writes for it (`1.log` for `00000001.log`) refuses
the log before any file changes, in either log, since a block's file is found by the name written for
its number: a simple rule for an operational edge, as a name in a locale's digits is refused
(`FileNameProblem`). Then each file is validated on its own (`mkOpenStateHelper`,
`validateFile`, `Storage/VolatileDB/Impl/State.hs` 372-436): it is read up to its first record that is
damaged or whose block the start-up check refuses, as the reference stops at the first block it cannot
read (`BlockReadErr`: here a damaged record or a block that does not decode) or, validating, finds
corrupt (`BlockCorruptedErr`: here a block that decodes and fails the check) (`parseBlockFile`,
`Storage/VolatileDB/Impl/Parser.hs` 52-107), its blocks are
indexed up to the first one already indexed, in an earlier file or earlier in the same one, whatever the
bytes of its record (`addToReverseIndex`, `DuplicatedBlock`, `State.hs` 464-492), the file is cut at the
earlier of the two, and the files after it are read all the same. Each cut is logged just before it is
made, before the next file is read, with its file, offset and cause, as the reference traces `Truncate` with
its `ParseError` (`receivedTruncated`; `Storage/VolatileDB/Impl/Types.hs` 77-99; `validateFile` and
`truncateError`, `State.hs` 412-450): a file cut where its parse stopped and then at an earlier repeat
is logged twice, in that order. The severity is Debug, the one cardano-node 11.1.3 gives `Truncate`
(`Cardano/Node/Tracing/Tracers/ChainDB.hs` 2714; the same in ouroboros-consensus `808e40054`'s tracing,
`tracing/Ouroboros/Consensus/Tracing/ChainDB.hs` 3288). The open's totals are logged as
`receivedOpened`. No path writes a second record of a
block the log holds: a receipt and the start's repair of the selected chain's blocks
(`ReceivedBlocks.restoreSelected`) write only a block the log lacks, as `putBlockImpl` skips a block
whose hash it holds, and each such skip is logged (`receivedBlockAlreadyHere`, at Debug, as
`BlockAlreadyHere`). A record that fails its checksum when it is read back later leaves the index and
can be written again; the next open cuts the damaged record's file there, or, reading it whole, cuts
the later record's. The selected-chain log keeps its in-order recovery, since it replays rollback
records in order.

The start-up check differs from the reference's validation policy. It decodes the block, checks that
its header names the point the record stores and checks the body against the header, at every open.
The reference only decodes by default (`NoValidation`, `Storage/VolatileDB/Impl.hs` 188) and checks a
block's integrity, its header's own and its body against its header, only after an unclean shutdown
or when the operator asks for it (`ensureValidateAll`, `Storage/ChainDB/Impl/Args.hs` 154-167;
`Ouroboros/Consensus/Node.hs` 515-521 and 1118-1128 in ouroboros-consensus-diffusion). Scarab keeps no
clean-shutdown marker, and its open does not check a header's own integrity.

Evidence: `GcScheduleSuite` and `ReceivedCollectionSuite` (the schedule and its traces, the storage's
collections, a copy whose backing-store work moves the clock, a restart, under TestControl; the start-up
check through the storage's open, on a block whose header is not its record's, bytes that do not decode
and a block whose body does not match its header), `ReceivedBlocksSuite` (the files, a damaged file, a
block that does not decode or is corrupt, repeats in the same and a later file with the same and other
bytes, the cuts and other traces, a name that is not the log's own, the repair, and the scan on a small stack) and
`VolatileStoreSuite` (the selected-chain log's recovery), asserting the reference's outcomes as its source
at the pins gives them; none compares with a running reference node's VolatileDB
([#1261](https://github.com/KD5UJC-XYZ/scarab/issues/1261)).
