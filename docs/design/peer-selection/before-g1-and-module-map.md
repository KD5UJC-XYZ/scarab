<!-- SPDX-License-Identifier: Apache-2.0 -->
# Scarab before G1 and the module map

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 2. Scarab before G1

| Piece | What it does | Where |
|---|---|---|
| `--peer host:port` (repeatable), `--topology` | The peer list is `--peer` entries followed by the topology's local roots, then bootstrap peers (only if neither names one: public roots), without repeats (`Topology.peers`). No peer is chosen, ranked, rotated or dropped. | `Main.scala` 290-301, `Topology.scala` |
| One supervised session per peer | `options.peers.parTraverse_(address => Follower.supervise(...))`. `supervise` reconnects after a `PeerError` with a fixed 2 s delay; one peer: fatal after 30 consecutive failures; several: for ever. A session that applied a block resets the count. | `Main.scala` 623-627 and 700-740, `Follower.scala` 385-414 |
| `Peer.nodeToNode` / `Peer.over` | A connection we open = handshake (version 14, initiator-only unless serving) + KeepAlive client + TxSubmission2 client + ChainSync and BlockFetch clients; `Peer.over` starts the pipelined producer. Observations: `Round`, `Fetched`, `Announced(tip)`. | `NodeToNode.scala` 40-103, `Peer.scala` 41-100, 181-240 |
| `Follower.session` | The hot-protocol run: intersect, headers, header gate, chain selection, fetch through the `BlockPool`, adoption. One candidate chain per session. | `Follower.scala` 85-134 |
| `BlockPool` | Claims by header hash across sessions, so a block is fetched from one peer (the reference's `filterNotAlreadyInFlightWithOtherPeers`); releases on session end (#124, #130). Does not choose peers. | `BlockPool.scala` |
| `StallWatchdog` | Logs and counts a stall (no block extended while a peer announced a longer tip); `--stall-exit-after` ends the node (#130). Its `announced` input is the highest tip any peer announced. | `StallWatchdog.scala` |
| `ConnectionManager` / `ConnectionState` | A table of connections by remote address and direction; limits `accepted` (the configuration's `AcceptedConnectionsLimit`, hard 512, soft 384 and delay 5 s by default; `--max-inbound` overrides the hard limit) and `maxOutbound` (64, not exposed); typed refusals; release on any end. Lifecycle `Reserved`, `Unnegotiated`, `Established(flow)`, `Terminating`, `Terminated`. | `ConnectionManager.scala`, `ConnectionState.scala` |
| `Outbound.connect` | A dial through the manager; waits for the peer's own inbound duplex connection instead of dialling a second one (`reuseInbound`). | `Outbound.scala` |
| `InboundGovernor` | Accepts inbound connections; on a duplex one runs our clients and a follower session, so an inbound peer is a block source. | `InboundGovernor.scala` |
| Topology fields | All parsed; valencies, `advertise`, `behindFirewall`, `diffusionMode`, `trustable`, `useLedgerAfterSlot` and `peerSnapshotFile` are recorded and not acted on. `NodeConfig.unsupported` names `GenesisMode` and `PeerSharing` as not implemented. | `Topology.scala`, `NodeConfig.scala` 70-83 |
| Metrics and events | `Node.Stalled`, `Net`-area connection and inbound events; no `peerSelection_*` series: [running.md](../../running.md) line 807 lists them as not exported "(the node has no peer selection governor)". | `log/NodeEvent.scala`, `metrics/` |
| PeerSharing | No mini-protocol 10, the handshake flag is always proposed false. | `Protocols` in `NetworkError.scala` |
| Ledger relays | Pool registrations keep `relays` as raw CBOR bytes in the ledger state; there is no decoder. | `CertState.scala` 86, `Tx.scala` 51 |

What this means for the design: the governor *replaces* the fan-out in `Main` and the fixed-delay retry in
`Follower.supervise` (for outbound peers: a peer that fails becomes a backed-off cold peer, section 1.6), and
sits *above* `ConnectionManager`, `Outbound`, `Peer`, `Follower.session` and `BlockPool`, which keep their
roles. This section records the pre-governor baseline; the current warm/duplex connection behavior and
generation-safe reload are described in sections 4.5 and 7g.

## 3. Reference module to Scala component

| Reference | Scala component | Module | Notes |
|---|---|---|---|
| `Governor.hs` (loop, `guardedDecisions` order) | `PeerSelection.decide` (pure) and `PeerGovernor.run` (effect loop) | `network` (pure), `node` (loop) | section 4 |
| `Governor/Types.hs` state, targets, `PeerSelectionView`, counters, `Guarded`, `Decision`, `Completion` | `GovernorState[A]`, `Targets`, `Counters`, `Wake`, `Decision[A]`, `Completion[A]` | `network` | status-carrying records (D2); `Counters` derived |
| `Governor/RootPeers.hs` | `Decide.rootPeers` + `Job.RequestPublicRoots` | `network` / `node` | backoff `2^min(n,8)` |
| `Governor/KnownPeers.hs` | `Decide.known` (peer share, inbound, forget) + `Job.PeerShare` | same | G1.4 for the peer-share half |
| `Governor/EstablishedPeers.hs` | `Decide.established` + `Job.Connect`, `Job.Close` | same | |
| `Governor/ActivePeers.hs` | `Decide.active` + `Job.Activate`, `Job.Deactivate` | same | |
| `Governor/BigLedgerPeers.hs` | `Decide.bigLedger` + `Job.RequestBigLedger` | same | G1.3 |
| `Governor/Monitor.hs` | the events the loop feeds to `decide`: `ConnectionsChanged`, `JobFinished`, `TargetsChanged`, `LocalRootsChanged` | `network` | blocking monitors become events (section 4.3) |
| `State/KnownPeers.hs`, `State/EstablishedPeers.hs` | `PeerRecord` fields and `Backoffs` | `network` | `availableToConnect` is derived from `nextConnectTime` |
| `State/LocalRootPeers.hs` | `LocalRoots[A]` with `HotValency`, `WarmValency` opaque types and `fromGroups`/`clampToLimit` | `network` | invariants as smart constructors |
| `PublicRootPeers.hs` + Cardano `ExtraRootPeers` | `PublicRoots[A]` (ordinary, ledger, big ledger, bootstrap as one enum per source) | `network` | |
| `Churn.hs` (Cardano) | `Churn` (pure: regime, `step`, `decrease`) and `ChurnLoop.run` | `network` / `node` | section 4.4 |
| `PeerMetric.hs` | `PeerMetrics` (pure board) + `PeerMetrics.Ref[F]` | `network` | fed by `Peer.Observation` |
| `Diffusion/Policies.hs` | `Policy` (constants) and `Picks` (`hotDemotion`, `warmDemotion`, `coldForget`, `random`) | `network` | pure over an explicit `Rng` |
| `LedgerPeers.hs`, `LedgerPeers/Utils.hs` | `LedgerPeers` (pure: `accumulate`, `draw`, `bigQuota`) + relay decoder | `ledger`/`network` | G1.3 |
| `PeerSharing.hs`, `Protocol/PeerSharing/*` | `protocol/PeerSharing.scala` (state machine, codec), `network/PeerSharingClient/Server` | `protocol`, `network` | G1.4 |
| `PeerStateActions.hs` | `PeerStateActions[F]` algebra + interpreter | `node` | wraps `ConnectionManager`, `NodeToNode`, `Peer`, `Follower.session` |
| `PeerSelectionActions.hs`, `RootPeersDNS*` | `RootProvider[F]` (topology, DNS, ledger, bootstrap) | `node` | DNS refresh follows the pinned root-peer TTL policy (D9) |
| `Cardano/.../Governor/Monitor.hs`, `PeerSelectionState.hs` (`ExtraState`) | `Sensitivity` (the judgement and bootstrap machine) in `GovernorState` | `network` | G1.1 (bootstrap), G1.5 (Genesis hooks) |
| `Cardano.Node.Configuration.TopologyP2P` | `Topology` (exists) + validation of the trustable rule | `node` | already refuses the same files |
| `POM.hs` targets and defaults | `PeerSelectionConfig` read from `config.json` and flags | `node` | section 4.7 |
| `Tracing/PeerSelection/*` | `NodeEvent`/`EventKind` additions, `EventMetrics` | `node` | section 5 |
| `Test/.../PeerSelection.hs`, `MockEnvironment.hs` | `GovernorSuite`, `MockEnvironment` | `network` tests | section 6 |
