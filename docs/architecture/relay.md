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

**Handshake** (`HandshakeServer`). Reads the proposal, answers through the pure
`Handshake.responder` (highest common version of 14 and 15 here, 16 to 23 for node-to-client) and
reports the agreed data. `initiatorOnly` in the agreed data is false only if both sides allow a
duplex connection. `NodeToNode.over(..., serve = Some(handlers))` offers InitiatorAndResponder and
runs our servers next to our clients when the peer agrees; `NodeToNodeServer.accept` is the inbound
side: the node's inbound connections are served, and on a duplex one the inbound governor also
runs our clients (see "Connections" below).

**Servers.** `ChainSyncServer.run` answers FindIntersect and RequestNext in order (so pipelining
clients work), sends AwaitReply at the tip and then waits for the chain to change. It reads through
a `Source` that holds one client's cursor. `BlockFetchServer` streams an inclusive range from a
`Blocks` source and answers NoBlocks for anything that is not a contiguous stretch of our chain (or
longer than `maxBlocks`). `KeepAliveServer` echoes cookies. `TxSubmissionClient` and
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
client behind the volatile chain is served from the `ImmutableStore`. `ChainServer` is a
`ChainPersistence`, composed after the storage persistence so a block is stored before it leaves the
volatile chain. Without `--db` only the volatile chain can be served.

**Mempool** (`mempool/`). `MempoolState` is pure: the transactions are kept in arrival order, and
the invariant is that they apply in order to the ledger state at the tip through the real
the ledger's `Ledger` rule (`ledger/rules/Ledger.scala`: phase 1, phase 2 and the Conway rules). `add` validates against the state after all held
transactions, so a transaction may spend the output of one before it. Rejections are typed
(`Malformed`, `AlreadyHeld`, `Ledger(failure)`, `Full`) and returned as values. `add` takes the
`Source` of the transaction, as the reference's `WhetherToIntervene`: a transaction from a local client
(`Local`) is validated as received, one from a peer (`Remote`, TxSubmission2) is validated with
`is_valid` forced to true and, when only that flag is wrong because a script fails, held with
`is_valid` false (the submitter loses the collateral); its relayed bytes are then the received ones with
the flag byte changed ([IN-80](../implementer-notes.md)). `sync(ledger, slot)`
runs on every new tip, rollback or fork switch, keeps what still applies and reports the rest;
transactions that a block contains leave as adopted. Before the transaction rules, `add` applies the
ledger's MEMPOOL rule: a transaction none of whose inputs is unspent is refused with that alone
(`ConwayMempoolFailure`, "All inputs are spent..."), as is a vote of an unelected committee member
before protocol version 11. Before either, `add` measures the transaction as `Mempool/Update.hs` does
(`Capacity`): its size against `maxTxSize` and its execution units against `maxTxExUnits` (phase 1, both failures
reported when both are exceeded, `Rejection.Limits`), then its reference-script bytes in the state after the held
transactions against 200 KiB (phase 2, `TxRefScriptsSizeTooBig`); a refusal there is the only failure returned and
the ledger does not run. The capacity is a vector of bytes (size plus 4), execution-unit memory, steps and
reference-script bytes, `computeMempoolCapacity` of the parameters of the ticked base: two blocks without
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
wait stalls that peer's TxSubmission2 replies, as in the reference, outside every protocol deadline. A transaction is validated in the slot after the tip within the tip's epoch (it is not ticked over an epoch
boundary). Transactions of rolled-back blocks are not put back. `Mempool[F]` serialises the
state changes (one state lock, held for one validation or one revalidation, never while an add waits), offers `source`/`sink` for TxSubmission2 and remembers recently refused ids so peers
that offer them again are not asked for the body.

**Chain selection** (`consensus/ChainSelection.scala`, `Follower.scala`). A peer session keeps a
candidate (the headers it announced after the point where it joined our chain). The Praos rule: the
longer chain wins; at equal length, tips issued by the same pool compare the operational-certificate
issue number (higher wins) when their slots are also equal; otherwise the lower VRF output of the
tips wins (the VRF certificate of the header, TPraos: the leader one). From Conway the VRF tie-breaker only applies to tips at most 5
slots apart. A candidate that is not strictly preferred changes nothing (so a peer's rollback or
equal fork leaves our chain alone). A switch rolls back at most `k` blocks (the volatile chain is
the limit; a candidate that no longer joins it is dropped as a protocol error of that peer, not of
the node). The blocks of a preferred candidate are fetched from that peer, validated on a copy of
the chain and committed together, so a rule violation leaves the chain at the last valid block
(still fatal, as before). The headers of a candidate have already passed the header gate (see
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
