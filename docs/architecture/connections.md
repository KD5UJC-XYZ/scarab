<!-- SPDX-License-Identifier: Apache-2.0 -->
# Connections and outbound peer selection

Part of [Architecture](../architecture.md).

## Connections: the manager and the inbound governor (G2)

A peer that connects to us is also a source of blocks. Before this, an inbound duplex connection
(InitiatorAndResponder) was accepted and served, but only the connections the node opened ran our
clients. Now:

**`ConnectionState`** (`network`, pure). The life of one connection: `Reserved` (an outbound slot
is taken), `Unnegotiated(provenance)`, `Established(provenance, flow)` with the flow the handshake
agreed (`Unidirectional` or `Duplex`), `Terminating`, `Terminated`; `transition(state, event)`
allows `Connected`, `Negotiated(flow)`, `Close` (from any live state, idempotent) and `Released`
and returns a typed `Illegal` for anything else. `canRunClients` is the rule for who may run our
clients: every established outbound connection and an inbound one that agreed duplex. `Counters.of`
gives inbound, outbound, duplex and established counts from a table. `ConnectionStateSuite`
checks the transitions, the idempotent close, that nothing but `Close`/`Released` is allowed once
terminating, and the counters.

**`ConnectionManager`** (`network`). A `Ref` table from the peer's socket address to its
connection (the key is the address and the direction). `inbound(remote)` and `outbound(remote)` are
`Resource`s: admission is atomic with the checks (one live connection per address and direction, and
none in the other direction while our clients use it: an outbound connection waits for an inbound
duplex one that runs our clients, and with `--inbound-sync off` (the default) the two coexist; an inbound
connection is never refused because we have an outbound one, see below; `accepted` (the reference's
`AcceptedConnectionsLimit`), `maxInboundPerAddress` and `maxOutbound`), the
refusals are typed (`ConnectionError.InboundLimit`, `AddressLimit`, `OutboundLimit`, `AlreadyConnected`) and raised
through `Raise`, and releasing the resource, however it ends (normally, by failure or by
cancellation), always runs `Close` and `Released` and removes the entry, so the accounting cannot
leak. Every change of state is reported (`ConnectionEvent`, logged as `connection` lines);
`lookup` lets a caller wait for a connection to end; `snapshot` is the table for `GET /connections`.
A peer that dials from its own listening port (the reference node does) is thereby the same
address we would dial; one that dials from an ephemeral port is not recognised as the peer we also
dial (the limits still apply).

**`InboundGovernor`** (`node`). `serve(bearer, remote)` is the inbound handler of `--listen`: it
admits the connection to the manager, runs `NodeToNodeServer.accept` (handshake, our servers) and,
when the handshake agreed duplex and `--inbound-sync on` was given (the default is off), starts in the `negotiated` hook the
same pieces that run on a connection we open: `NodeToNode.clients` (ChainSync, BlockFetch,
KeepAlive and the TxSubmission2 client on the side of the mini-protocols that is ours), then
`Peer.over` (the pipelined producer, header-first validation, ranges of blocks) and the follower
session of `Follower`, so the peer's chain competes in chain selection like any other candidate.
The session is a background fiber of the connection: when the multiplexer closes the clients, the
producer and every supervised fiber are released with it. A session that ends because the peer broke
the protocol, announced an invalid header or sent a block our rules refuse aborts the connection; one that ends
for any other reason of the peer (the peer does not serve ChainSync, say) leaves the serving side running. A
session ends with a `SessionEnd` (a value: `SessionEnd.scoped` runs it in an error scope of its own and catches
caller-scoped rule and store capabilities), and `FailurePolicy.disposition` says what is
done about it: forget the peer, try it again later, or stop the node (storage, an internal failure,
a defect or implementation unavailability; exit codes 5, 6, 7 and 8,
`running.md` "Failures that stop the node"). Each session counts the
headers and rollbacks it saw and the peer's tip (`GET /connections`, `inbound` log events
`accepted`, `duplex`, `following`, `sessionEnded`, `sessionDropped`, `closed`).

**Outbound.** `OutboundPeers` puts the connections we open through the same manager. If the
peer we are asked to dial already has a duplex connection to us on which our client runs, no second
connection is opened: the dialler waits until that connection ends (the `reused` log event) and then
dials; a manager refusal becomes a recoverable connection failure. Outbound peers configured with
`InitiatorAndResponder` receive the node's responder handlers and can read our chain on that same
bearer when duplex is negotiated. `InitiatorOnly` peers do not receive those handlers.
Admission distinguishes address and direction, permits an inbound connection alongside an outbound
one, and permits the reverse when inbound sync is disabled. When two sessions do follow one peer,
chain selection treats their candidates independently.

Tests: `ConnectionManagerSuite` (admission, one connection per address, limits per direction,
release after failure and cancellation, illegal events, waiting for a connection to end, and a
model-based property over random open/negotiate/release sequences that checks the counters against
the table and the limits after every step); `InboundGovernorSuite` (a duplex peer that only connects
to us over in-memory bearers is followed to its tip and its blocks applied; an initiator-only peer is
served and not followed; a second connection from one address is refused; the table is empty again
when the peer lets go). The devnet `relay` scenario asserts it against the reference node (see
[running.md](../running.md)).

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
