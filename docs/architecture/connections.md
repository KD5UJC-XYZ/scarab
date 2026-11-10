<!-- SPDX-License-Identifier: Apache-2.0 -->
# Connections and outbound peer selection

Part of [Architecture](../architecture.md).

## Connections: the manager and the inbound governor (G2)

One connection serves both sides of a peer, as in the reference (ouroboros-network `2fb69829`,
`ConnectionManager/Core.hs`): the outbound side reuses a duplex connection the peer opened, and the
peer's protocols run on a duplex connection we opened. Our clients run only where the outbound governor
promotes a peer.

**`ConnectionState`** (`protocol`, pure). The reference connection manager's states without their
runtime fields (`ConnectionManager/State.hs:191-217`): `ReservedOutbound`, `Unnegotiated(provenance)`,
`OutboundUni`, `OutboundDup(Ticking|Expired)`, `OutboundIdle(flow)`, `InboundIdle(flow)`, `Inbound(flow)`,
`Duplex`, `Terminating` and `Terminated`. `transition(state, event)` takes the reference's named
transitions and returns a typed `Illegal` for anything else; `valid(from, to)` is the reference's
`verifyAbstractTransition` (`Test/Ouroboros/Network/ConnectionManager/Utils.hs`), `Counters` its
`ConnectionManagerCounters` as `connectionStateToCounters` computes them, and `isInboundConn` and
`ConnectionType` what pruning sees of a connection.

**`ConnectionManager`** (`network`). A table from the remote and the local address (`ConnMap.hs` 45-50) to one
connection's state, its negotiated handle (the mux, the agreed version and peer sharing, the transport's
identity) and its generation. Every operation acts on one generation of an entry, and every change is
reported (`ConnectionEvent`, logged as `connection` lines).
- `include(remote, local)` admits an accepted connection (`includeInboundConnection`): refused at the hard
  limit and the per-address limit (an approved deviation, ADR 0008), it overwrites an outbound
  reservation with its identity (`Overwritten`, `SelfConn`) and replaces one that is terminating.
- `acquire(remote, provenance)` is `acquireOutboundConnection`: it reuses an `InboundIdle Duplex`
  (`Awake^{Duplex}_{Local}`) or `Inbound Duplex` (`PromotedToWarm^{Duplex}_{Local}`) connection and returns
  its published handle; waits, without holding the table, for an unnegotiated accepted connection's
  handshake (and settles as `Disconnected` if it ends) and for a terminating one to leave the table; refuses
  a unidirectional one (`ForbiddenConnection`), an `OutboundIdle` one (`ForbiddenOperation`) and one the
  outbound side holds already (`AlreadyConnected`, the reference's `ConnectionExists`); never dials a peer
  of inbound provenance (`InboundConnectionNotFound`); and otherwise reserves the address to dial
  (`maxOutbound` limits the reservations).
- The owner of a connection's transport (the accept loop's handler, or the fiber that dialled it) holds its
  resource; releasing it ends the connection through time-wait when it was left `Terminating`, at once
  otherwise. A handshake that agreed no version takes the entry out of the table at once.
- `releaseOutbound` ends the outbound side's use: `Duplex` becomes `Inbound Duplex`, `OutboundDup Ticking`
  becomes `InboundIdle Duplex`, and `OutboundUni` or `OutboundDup Expired` becomes `OutboundIdle`, closed after
  the outbound idle timeout (5 s) unless the peer wakes it up. `awaitOutboundDemotion` is the reference's
  `waitForOutboundDemotion`: the peer is cold once the outbound side holds the connection no more.
- `promotedToWarmRemote`, `demotedToColdRemote` and `releaseInbound` follow the peer's activity; a remote
  promotion and an outbound release can prune inbound connections to stay within the hard limit, with the
  reference's prune policy (upstream connections last).

**The peer's activity** (`NodeToNodeServer.remoteActivity`). On every connection whose responders the
peer can start, the accepted ones and the duplex ones we dial: the first responder that starts is
`AwakeRemote`, the last that ends `WaitIdleRemote` (which arms the idle timer, 5 s), and an expired timer is
`CommitRemote`: the manager closes an idle connection, or keeps one the outbound side uses
(`OutboundDup Ticking` becomes `Expired`) until a later responder wakes it up (`InboundGovernor.hs` 329-335,
463-481, 499-570). The first start and the last end of the responders are queued in order, and a responder that
starts while an expired timer is being handled waits until it is settled, as the reference's mux tracer does
(`InboundGovernor.hs` 629-708).

The manager's changes and what they ask for (the log, the connections they end) are one step that cancellation
cannot split, and an acquisition hands over a reuse or a reservation without a cancelable gap; a reuse its caller
did not take over is released by the outbound side. A waiter for a negotiating connection that ends learns the state
and the error it ended with (`Disconnected`).

**`InboundGovernor`** (`node`). `serve(bearer, remote)` is the inbound handler of `--listen`: it admits the
connection to the manager, runs `NodeToNodeServer.accept` (handshake, our servers, the peer's activity),
publishes the negotiated handle and reports the data flow (`inbound` log events `accepted`, `duplex`,
`unidirectional`, `closed`, `refused`). It runs none of our clients.

**Outbound.** `OutboundPeers.establish` acquires through the manager with the peer's provenance. A reused
connection's handle gets our initiators attached (`NodeToNode.attach`: KeepAlive, PeerSharing; the hot
protocols on activation), with no handshake and no responder installed again. A reservation is dialled by a
fiber of its own under the outer supervisor, which owns the transport as the reference's connection thread
does: `NodeToNode.negotiate` runs the handshake, publishes the handle and, on a duplex connection, our
responders and the peer's activity. A clean close releases the outbound use only; a protocol failure or a
close timeout stops the shared mux (`PeerStateActions.hs` 1079-1104). Outbound peers configured with
`InitiatorAndResponder` offer duplex; `InitiatorOnly` peers do not. What the hot sessions saw of each peer (its last
intersection, the headers received, the tip it announced) is `sessions` in `GET /connections`.

Tests: `ConnectionStateSuite` (every move against `valid`, the counters); `ConnectionManagerSuite`
(admission and its limits, inbound-first and outbound-first acquisition, a reservation racing an accept, a
self-connection, the wait for a handshake and its failure, the wait for a time-wait and the replacement
during one, the releases of both sides, an inbound close ending the outbound use, pruning, and a property over
random sequences of all of these that checks every reported change against `valid` and the counters against
the table and the hard limit); `ProtocolIdleTimeoutSuite` (the peer's activity and the idle timer, expiry
before and after an outbound release, remote reactivation); `InboundGovernorSuite`; `OutboundReuseSuite`
(the outbound governor's use of a peer's own connection). The devnet `relay` scenario asserts reuse against
the reference node (see [running.md](../running.md)).

## Peer selection: the outbound peers (G1.1)

[ADR 0016](../adr/0016-peer-states-and-the-governor.md), [design](../design/peer-selection.md). The outbound peers are chosen by
a governor that mirrors `Ouroboros.Network.PeerSelection.Governor`.

**Pure part** (`network/.../selection`, no effects). `Status` (`Cold`, `Connecting`, `Warm`, `Activating`, `Hot`,
`Deactivating`, `Closing`, `Cooling`) is the state of one `PeerRecord` in `GovernorState`; the known, established and active
sets of the reference are views of the map (`view`), `invariant` states what must hold. `PeerSelection.decide(state, now)`
returns `Step.Act(decision)` (jobs and the state with them in progress) or `Step.Idle(state, wakeAt)`, in the reference's
order; `Events.apply` applies what happened (a job completed, a connection observed, new targets, roots, a judgement, a
wake-up) and is the only place that does failure accounting. `Rng` is a splittable SplitMix64, so a run is a function of its
seed. `Targets`, `LocalRoots` (`HotValency`, `WarmValency` opaque), `Sensitivity` (the bootstrap regime and
`OutboundConnectionsState`), `Regime`, `Churn` (the twelve steps, their targets, timeouts and the sleep), `PeerMetrics` (the two boards of the first arrival per slot, the hot-demotion scores) and `Gsm` (the Genesis State Machine and `FetchMode`) are pure too.

**Effects** (`node`). `PeerGovernor.run[F]` is the loop on `Stateful[F, GovernorState]`, `Ask` (clock, configuration), `Raise`
(`GovernorError`) and `Tell` (`Trace`); it waits on its event queue only, timers are fibers that offer `Event.Wake`.
`PeerStateActions` is its interface to the world; `OutboundPeers` interprets it over the `ConnectionManager`, with one fiber per
connection and one per hot session, whose `Resource` owns the claims on blocks in the `BlockPool`, so a hot session that ends
releases them. `NodeToNode.warm` opens the connection with KeepAlive and `activate` starts ChainSync, BlockFetch and
TxSubmission2 on it; `finish` ends them with `MsgDone` where the protocol allows it. `PeerNetwork.resource` wires the roots, the
governor, the churn loop (`ChurnLoop`, the one writer of the targets), the GSM runner (`GsmRunner`, the marker file) and the fetch mode, which `BlockPool` reads to decide whether a claimed block may be requested again; hot sessions report headers and blocks to the shared `PeerMetrics` (one update per batch), which the governor reads through `Ask` once per pass; `Main` runs it beside the node, and the governor
ends the node only by a fault.

**Tests.** `PeerSelectionSuite` (the decision by example), `PrimitivesSuite`, `GsmSuite`, `WarmHotSuite` (activate, finish and
reactivate on one connection), the properties of the reference on `TestControl` against a mock environment
(`GovernorPropertiesSuite`), 40 in-memory peers (`FortyBearersSuite`), and a 256 KiB thread (ADR 0011).
