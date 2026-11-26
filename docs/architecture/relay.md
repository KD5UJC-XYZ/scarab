<!-- SPDX-License-Identifier: Apache-2.0 -->
# Relay

Part of [Architecture](../architecture.md).

## Relay (`follow --listen`, `--local-socket`)

With `--listen <host>:<port>` the node accepts node-to-node connections (versions 14 and 15) and
serves its own chain; with `--local-socket <path>` local clients can submit transactions over a Unix
socket. Everything it serves comes from its own validated chain and mempool, never from what a peer
announced. The pieces, bottom up:

**Multiplexer and duplex** (`network/Mux.scala`). Every channel has a side per
mini-protocol, `Initiator` (client) or `Responder` (server), and an SDU carries the mode bit of its
sender. `Mux.Config(duplex = true)` registers both sides of every protocol on one bearer, so a
client and a server of the same protocol number never see each other's traffic;
`mux.channel(protocol, role)` picks the side. Without `duplex` only the side opposite to the peer's
is registered and the other mode bit closes the connection. `Mux.flush` waits until everything
queued has been written, used so that a handshake refusal is not lost when the connection closes.

The multiplexer's fibers and its channels follow [ADR 0017](../adr/0017-fibers-end-with-values.md). A channel
holds no error capability: `send`, `recv` and the clients over it (`ChainSyncClient`, `BlockFetchClient`,
`KeepAliveClient`) raise through the `Raise` of the fiber that calls them. The reader routes each SDU into the
ingress queue of its mini-protocol and side, counted in bytes with the limit of the reference
(`IngressLimits`: ChainSync 462,000, BlockFetch 23,068,694, TxSubmission2 721,424, KeepAlive 1,408, from
`Cardano.Network.NodeToNode` of ouroboros-network 1.2.0.0); it never waits for a slow protocol, and a peer past
the limit ends the connection with `NetworkError.IngressLimit`, the reference's `IngressQueueOverRun`. The
writer's queue is bounded in messages and `send` waits for room. `Mux.closed` is the typed reason the
connection ended with (a closed bearer, a framing error, an overrun, a limit of the protocol), and every
pending operation raises it. Each fiber the connection starts (the KeepAlive pinger, the TxSubmission2 client,
the four server fibers) runs in an error scope of its own (`Fibers.scoped`) and, when it fails, aborts the
multiplexer with the error as a value.

A channel's `recv` takes the message's decoder as an `IncrementalDecoder`: while a message has not all arrived
the decoder is resumed from its cursor on each longer prefix, after every SDU when the queue runs dry, as the
reference's driver feeds each chunk to its decoder's continuation (`runDecoderWithLimit`,
`Driver/Limits.hs:281-331` at ouroboros-network c45735a5 and 4646039f). The decoders of the node's mini-protocols
(`ProtocolWire.incremental` and each codec's `incremental`) keep in their cursors what they read whole: the heads
of an item, the elements of a list, a handshake's terms, a query's detached records and envelopes. Each one's
verdict on a prefix is its codec's `decodePrefix` on it, and `BaseDifferentialSuite` compares those verdicts with
the decoders' before they resumed. The Leios clients, which only the hand-run Leios probe uses, read each prefix
from its start. The ingress queue counts payload bytes against its limit as the reference's does
(`Network/Mux/Ingress.hs:119-133`, both pins); the heap a queued SDU takes beside its payload is not measured
against the reference's.

**Handshake** (`HandshakeServer`). Reads the proposal, answers through the pure
`Handshake.responder` (highest common version of 14 and 15 here, 16 to 23 for node-to-client) and
reports the agreed data. `initiatorOnly` in the agreed data is false only if both sides allow a
duplex connection. `NodeToNode.over(..., serve = Some(handlers))` offers InitiatorAndResponder and
runs our servers next to our clients when the peer agrees; `NodeToNodeServer.accept` is the inbound
side: the node's inbound connections are served, and on a duplex one our clients run when the
outbound governor promotes the peer and reuses the connection (see "Connections" below).

**Servers.** `ChainSyncServer.run` answers FindIntersect and RequestNext in order (so pipelining
clients work), sends AwaitReply at the tip and then waits for the chain to change. It reads through
a `Source` that holds one client's cursor. `BlockFetchServer` streams an inclusive range from a
`Blocks` source, which answers as the reference's ChainDB `stream` does: an `UnknownRange`, answered
NoBlocks, or the range's iterator, its `IteratorResult`s sent as they come (`Exhausted` is BatchDone);
what the reference throws (an Origin end, bounds that are not a range, `BlockGCed`) is raised as
`NetworkError.RangeRefused` and ends the connection, a batch without BatchDone; the length of a range is not bounded, as the reference's server streams any range it
holds. `KeepAliveServer` echoes cookies. `TxSubmissionClient` and
`TxSubmissionServer` are the two roles of TxSubmission2: the client offers a `Source` of
transactions (each once per connection, by ticket number) and hands out bodies on request; the
server asks for ids with a blocking request, fetches the bodies it does not know (verifying the id
against the body hash) and passes them to a `Sink`; one batch is fully processed and acknowledged
before the next request. `LocalTxSubmissionServer` is the node-to-client submission protocol,
`LocalStateQueryServer` the query protocol, `LocalTxMonitorServer` the mempool monitor and the node-to-client
ChainSync the `ChainSyncServer` with whole blocks (see "Node-to-client" below); `NodeToClientServer.accept`
runs the handshake (versions 16 to 23) and then those on one connection.

**Served chain** (`relay/ServedChain.scala`, `relay/ChainServer.scala`). The ouroboros-consensus
`ChainProducerState`: the volatile chain (last `k` blocks with their headers and raw bytes) plus
the read pointer of every connected client. A rollback rewrites exactly the pointers of the clients
that had read past the new tip (they get a RollBackward to it and then follow the new branch). A
FindIntersect checks each offered point against the chain as it is and moves the client's pointer in
the same update, as the reference's follower does (`checkIfPointOnChain`); a point older than the
volatile chain is looked up in the store's index, which never drops a block, before the pointer moves to
it; the block is not read. A client behind the volatile chain is served from the `ImmutableStore`, one read
of each block, whose header is decoded without decoding the contents of its body. ChainSync follows the selected chain
alone; BlockFetch serves any fork the node holds (`relay/HeldRange.scala`), as the reference's ChainDB iterator does: a
range newer than the immutable tip is a path walked back from its upper bound through the held blocks (the volatile
chain and the received log, whose index names each block's predecessor), joined to the store only at its tip, and an
older range is the store's; its ends are found in those indexes, no block read, and each block is read once, when it
is sent, a held block collected meanwhile from the store if it is there. `ChainServer` is a
`ChainPersistence`, composed after the storage persistence so a block is stored before it leaves the
volatile chain. Without `--db` only the volatile chain can be served.

**Mempool** (`mempool/`). `MempoolState` is pure: the transactions are kept in arrival order, and
the invariant is that they apply in order to the ledger state at the tip, ticked to the slot after it by the
hard-fork combinator's tick (`PoolLedger.at`, `Hfc.tick`), so in the era that slot is in. Every era from Shelley to
Conway has a pool (#934): each applies a transaction by its own rule, LEDGER from Shelley to Babbage
(`EarlierLedger` over the rule `ShelleyLedger`) and MEMPOOL then LEDGER on the era's types in Conway (`ConwayPoolLedger`), always at
transaction index 0 as the reference's `mkMempoolEnv` does, and every held transaction is of the pool's era; a
Byron or Dijkstra ledger has no pool (`NoLedger`). A submission names its era (`Submission`); one of another era is
brought into the pool's era as the selected reference build does (`EraInjection`): under `11.1` it is translated
forward through each era between, as cardano-node 11.1.3's `hardForkInjectTxs` does, and a later era's transaction,
or a Byron payload the decoder of its kind reads whole (`MempoolState.read`; one it refuses is `Malformed`), is
refused with the combinator's era mismatch; under `11.2` and the Leios build it is serialised in its
era and decoded in the pool's, earlier or later. When `sync` brings a ledger of a later era, the held transactions
follow it the same way and keep their tickets: reapplied under `11.1`, applied with every check as from a peer under
the other builds; one that does not reach the era leaves the pool with the mismatch. `add` validates against the state after all held
transactions, so a transaction may spend the output of one before it. Rejections are typed
(`Malformed`, `AlreadyHeld`, `Ledger(failure)`, `Full`) and returned as values. `add` takes the
`Source` of the transaction, as the reference's `WhetherToIntervene`: a transaction from a local client
(`Local`) is validated as received, one from a peer (`Remote`, TxSubmission2) is validated with
`is_valid` forced to true and, when only that flag is wrong because a script fails, held with
`is_valid` false (the submitter loses the collateral), from Alonzo on, as `applyAlonzoBasedTx` does
([IN-80](../implementer-notes.md)); before Alonzo a peer's transaction is validated as received. A transaction
may come without its flag, and is then valid, as the reference reads it (`Tx.envelopeParts`, #699);
whatever its form, it is held and relayed as the reference relays it (`EraInjection.envelope`): from Alonzo a
definite list of four with the flag of the verdict and the other parts' original bytes, before Alonzo the record of
three. TxSubmission2 carries each transaction and id under its era's index (`TxSubmission2.eraCodec`): the pool
offers a peer each held transaction under the era it holds it in, and reads a peer's with the decoders of the era
it names; ids compare by their hash, whatever the era; a Byron `GenTx` from a peer ends the connection (#934). `sync(ledger, slot)`
runs on every new tip, rollback or fork switch, keeps what still applies and reports the rest;
transactions that a block contains leave as adopted. In Conway, before the transaction rules, `add` applies the
ledger's MEMPOOL rule: a transaction none of whose inputs is unspent is refused with that alone
(`ConwayMempoolFailure`, "All inputs are spent..."), as is a vote of an unelected committee member
before protocol version 11. Before either, `add` measures the transaction as `Mempool/Update.hs` does
(`Capacity`): its size against `maxTxSize` and, from Alonzo, its execution units against `maxTxExUnits` (phase 1,
both failures reported when both are exceeded, `Rejection.Limits`), then, in Conway, its reference-script bytes in the
state after the held transactions against 200 KiB (phase 2, `TxRefScriptsSizeTooBig`; trivial before Conway); a refusal there is the only failure returned and
the ledger does not run. The capacity is a vector of bytes (size plus 4), execution-unit memory, steps and
reference-script bytes, of which an era has the components its measure has (bytes alone to Mary, execution units
from Alonzo, reference scripts in Conway: `Capacity.Shape`), `computeMempoolCapacity` of the parameters of the ticked base: two blocks without
`--mempool-bytes`, the override rounded up to whole blocks with it, recomputed at every `sync` and never trimming a
pool that a parameter change left over it. A transaction that would exceed any component finds no room; the
check runs after phase 1 (without the reference-script component) and again after phase 2, as `doAddTx` and
`pureTryAddTx` do ([IN-81](../implementer-notes.md)). `Mempool.add` then waits, as the reference's `implAddTx` does
([IN-82](../implementer-notes.md)): it releases the state lock and retries from the first measure whenever the pool
changes (`version`: an add, a `sync`, a removal), until it is held or refused for another reason, and only a
transaction larger than the whole capacity is refused with `Rejection.Full`. Waiters are served through two FIFO
queues, a peer's transaction taking the remote queue and then the common one and a local client's only the common
one, so a local client passes the peers in line and only the head of the line waits for room. The wait is
cancelable (a closed client or a lost peer leaves the line) and the `mempoolWaiting` event marks it. A peer's
wait stalls that peer's TxSubmission2 replies, as in the reference, outside every protocol deadline. A transaction is validated in the slot after the tip, on the ledger ticked to that slot, over an epoch boundary
and into the next era when the slot is past them (`PoolLedger.at`). Transactions of rolled-back blocks are not put back. `Mempool[F]` serialises the
state changes (one state lock, held for one validation or one revalidation, never while an add waits) and offers `source`/`sink` for TxSubmission2.
The pool is validated again at a new tip by its watcher, `MempoolSync`, a fiber of its own beside the selection
worker, as the reference's mempool is by the watcher `openMempool` forks (`forkSyncStateOnTipPointChange`,
ouroboros-consensus `82ecba32` and `808e4005`, `Mempool/Init.hs` 47-82). The follower's commit, of a peer's block or
our own, only publishes the chain (`Follower.committedChain`); the watcher reads its tip as a fingerprint, once at
start and then whenever it is not the one it saw last, taking the latest (`blockUntilChanged`), and each time the pool
reads the chain again once its state lock is held and decides there where to revalidate (`Mempool.synchronise`), as
the reference's `implSyncWithLedger` reads the ledger in the step that takes the pool's state (`Mempool/Update.hs`
558-625 at `82ecba32`): a revalidation that waited behind an admission applies the chain as it is then, never a tip
the chain has left meanwhile, and does nothing when the pool is at the tip already. The transactions of the blocks the
pool was not revalidated past leave it as adopted; those blocks are read from the chain's volatile window, whose bytes
the chain server holds, and a transaction in a block that is no longer volatile when the pool catches up leaves it
with its reason, as the reference reports every transaction it removes. Chain selection, the adoption of a forged
block, the GSM's comparison and a state query's acquire therefore never wait for the state lock, and the pool may lag
the chain for as long as a revalidation waits, as the reference's does; the forger validates the held transactions
again at its slot, so it does not depend on the pool having caught up. A failure of the revalidation stops the node,
as the reference's watcher thread is linked to the node. Admissions stay serial under the state lock, as in the
reference.
The sink knows an id only while the pool holds its transaction, as the reference's `mempoolHasTx` reads the current
snapshot (ouroboros-consensus `82ecba32`, `NodeKernel.hs` 929): a transaction refused or adopted earlier and offered
again is asked for again.

**Chain selection** (`consensus/ChainSelection.scala`, `Follower.scala`). A peer session keeps a
candidate (the headers it announced after the point where it joined our chain). The Praos rule: the
longer chain wins; at equal length, tips issued by the same pool compare the operational-certificate
issue number (higher wins) when their slots are also equal; otherwise the lower VRF output of the
tips wins (the VRF certificate of the header, TPraos: the leader one). From Conway the VRF tie-breaker only applies to tips at most 5
slots apart. A candidate that is not strictly preferred changes nothing (so a peer's rollback or
equal fork leaves our chain alone). A switch rolls back at most `k` blocks (the volatile chain is
the limit; a candidate that no longer joins it is dropped as a protocol error of that peer, not of
the node). The blocks of a preferred candidate are fetched from that peer, validated on a copy of
the chain and committed together, so a rule violation leaves the chain at the last valid block; the
session that fetched the block ends `Rejected` and its peer is forgotten, and the node goes on
(`FailurePolicy.disposition`). The headers of a candidate have already passed the header gate (see
"Header-first validation (G6)" below), so a bad header never reaches the fetch.
Several sessions can run at once; adoption is serialised. The sessions of all peers share one `BlockPool`
(`BlockPool.scala`): a session claims the blocks of a range when its connection is free to send the request, so a
block is fetched from one peer only (ouroboros-network `Ouroboros.Network.BlockFetch.Decision`: in bulk sync
`filterNotAlreadyInFlightWithOtherPeers` removes blocks in flight with another peer), the ranges of a batch go to
whichever connection is idle, and the session that applies a block takes it from the pool whichever peer delivered
it (waiting if the claim is still in flight, fetching it itself if the claim was released). The reference limits the
peers fetching at once with `maxConcurrencyBulkSync`; ours is limited by the number of peers given.

**Wiring** (`Main`). `--peer` may be given several times (with more than one peer a peer that
keeps failing is retried forever, with one it is fatal after 30 attempts), `--listen` starts the
inbound server, `--local-socket` the local one, `--mempool-bytes` overrides the capacity. The followed
peers get our mempool through TxSubmission2. The JSON log gains `listening`, `inbound`,
`mempoolAdded`, `mempoolRejected`, `mempoolWaiting`, `mempoolAdopted`, `mempoolRemoved`, `mempoolDropped` and `mempoolSynced`.
