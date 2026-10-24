<!-- SPDX-License-Identifier: Apache-2.0 -->
# Stages

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7. Stages

Each stage lands on its own, with the gates of its level ([CONTRIBUTING.md](../../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)).
Each is sized for one session. All of them also update `docs/running.md` (flags, events, metrics) and carry a
`changes/` fragment; levels are 2 unless said.

### G1.1 Peer states and a governor with local and bootstrap roots

*Scope.* The `Status`/`PeerRecord`/`Targets`/`LocalRoots` types; `PeerSelection.decide` for the cold-to-warm,
warm-to-hot, hot-to-warm, warm-to-cold and forget families, the asynchronous demotions, the cold-promotion
backoff and the fail-count rules; the targets with the relay and producer defaults and the config keys; the
`PeerStateActions` interpreter and the `NodeToNode.warm`/`activate` split (section 4.5); local roots from
`--peer` (one group, `hot = warm = n`) and from `topology.json` valencies; bootstrap peers and the sensitive
state (the `LedgerStateJudgement` input from a tip-age rule, the 15-minute timeout); the loop in `Main` replacing
the `parTraverse_` of `supervise` (no switch back, open question 12); the counters and the
`Net.PeerSelection.*` events for these transitions; `GET /connections` shows temperature. No churn, no ledger
peers, no peer sharing: targets are static.

*Files.* `network/.../selection/{Status,Targets,LocalRoots,PublicRoots,GovernorState,PeerSelection,Policy,Picks,Rng}.scala`,
`network/.../NodeToNode.scala` (split), `node/.../PeerGovernor.scala`, `node/.../PeerStateActions.scala`,
`node/.../RootProvider.scala`, `node/.../Main.scala`, `node/.../Topology.scala` (valency now acted on),
`node/.../log/{NodeEvent,EventKind}.scala`, `node/.../metrics/{Catalogue,EventMetrics}.scala`,
`docs/adr/0016-...` (D10), tests `network/src/test/.../selection/*Suite.scala`, `node/src/test/.../GovernorSuite.scala`,
`MockEnvironment.scala`.

*Gates.* Level 2 (a behaviour change in the network area) with two additions because it replaces the code
that decides which peers are followed: `scripts/sbtw check` and `devnet.py relay --seconds 300`, and
`devnet.py peercut` (the stall and the cut-off peer must still be handled; the governor must demote the dead
peer, not the watchdog end the node). The decision function and the loop come with the layer-1 properties for
every family it implements and the layer-2 environment; no `Stateful` without a law test, per CONTRIBUTING.

*Evidence it must produce.* Layer 1 and 2 green; `relay` and `peercut` green; a `compatibility.md` row for
"Peer selection" (status: partial, G1.1); the per-hot-peer cost (D5): a 10-minute Preview run with the relay
default of 20 active peers against 1, recording CPU, heap and blocks/s in [performance.md](../../performance.md);
and the **level-4 row** for the issue's acceptance, recorded once at this stage's end: Preview from genesis with
the official `config.json` and `topology.json` and *no* `--peer`, 30 minutes, reaching the tip through bootstrap
peers only (commit, date, command, image versions, `GET /connections`
showing the temperatures, the `cold/warm/hot` counters at the end). Not claimed: ledger peers, churn.

### G1.2 Metrics and churn

*Scope.* `PeerMetrics` and the two new `Peer.Observation`s; the `hotDemotion` pick using them (before this
stage hot demotion is random); `Churn` (the three regimes, the twelve-step round, the head start, the intervals
3300/900 s with fuzz 600/60 s, the timeouts) and `ChurnLoop`; a `FetchMode` input (bulk sync or deadline) from
the node (open question 3); the Prometheus counters for the whole `peerSelection_*` group and the churn trace;
the `governor` devnet scenario of section 6. At this stage the fetch mode used the Praos regimes; #139 later
added Genesis sync targets to churn (section 7g).

*Files.* `network/.../selection/{PeerMetrics,Churn}.scala`, `node/.../ChurnLoop.scala`, `Peer.scala`
(observations), `node/.../metrics/*`, `scripts/devnet/scenarios/governor.py`, `scripts/devnet.py`, tests as above.

*Gates.* Level 2: `check`, `relay --seconds 300`, and the new `governor` scenario. The observation callbacks sit
on the per-header and per-block path, so the stage includes the measurement of their cost (the same method as
the D8 scheduling measurement).

*Evidence.* The `prop_governor_*` properties of layer 1 for the active targets with churn on; the churn
arithmetic table; the `governor` scenario's summary; the per-header cost in performance.md; the metrics
checked against a scrape of the reference image on the devnet (names present, same meanings), as J3 did, with
the evidence-log row.

### G1.3 Ledger peers and big ledger peers

*Scope.* A decoder for pool relays (single-host address, single-host name, multi-host name; SRV is resolved by the root provider, D9) from the ledger state's `relays: Bytes`; `LedgerPeers.accumulate`, the 90% quota, the two
weightings and the draw; `useLedgerAfterSlot` honoured; the ledger-peers worker's cache lifetimes (30 s empty,
1847 s); the big-ledger-peer families of `decide` and their targets (promotion, demotion, forgetting excluded,
churn steps 1 to 6); the big-ledger peer snapshot file (`peerSnapshotFile`: read, the slot rule of
`stakeMapWithSlotOverSource`); ledger peers as a `RootProvider` source and as `Source.Ledger`/`Source.BigLedger`;
the YoungEnough transition that lets ledger peers replace bootstrap peers.

*Files.* `ledger/.../PoolRelays.scala` (the decoder), `network/.../selection/LedgerPeers.scala`,
`node/.../RootProvider.scala`, `node/.../LedgerPeersSource.scala` (reads the pool distribution and registrations
from the volatile chain's ledger state through a read interface, not the follower), tests: draw proportions with a
fixed seed against the reference's formula, the quota on the mainnet distribution fixture, a small-stack run over
100,000 pools (ADR 0011).

*Gates.* Level 2 (it reads ledger state: confirm it only reads). `check`, `relay`, and one of `e2e` (the devnet
ledger has pools with registered relays) if the decoder is shared with ledger code. The 100,000-pool test is in
`check`.

*Evidence.* Properties for the big-ledger families; the decoder checked against relays of the devnet and Preview
registrations from the reference (a fixture with provenance); the **level-4 row**: Preview from genesis, official
files, no `--peer`, past the `useLedgerAfterSlot` slot of the official file, 30 minutes: the node reaches the
tip, the metrics show big ledger peers known/established/active within their targets, and the peers were taken
from the ledger, not only from the bootstrap list. This completes the issue's "reaches the tip on Preview from
root peers only" with ledger peers in play.

### G1.4 PeerSharing

*Scope.* `protocol/PeerSharing.scala` (states, messages, the CDDL codec, size and time limits) with a
cddl cross-check (`scripts/cddl_crosscheck.py`) and a fuzz target ([CONTRIBUTING](../../../CONTRIBUTING.md): a decoder
gets a fuzz target); `PeerSharingClient` and `PeerSharingServer` in `network` (mini-protocol 10, registered on
the established temperature); the handshake flag proposed from `PeerSharing` in the configuration and the
conjunction at negotiation (the field and the combine exist); the governor's `KnownPeers.belowTarget` (the coin
flip, the inbound peers, the two-stage requests, the 900 s retry, the 300 s activation delay, 2 in flight, the
`min 255 (max 8 …)` split); the server policy (shareable set, sticky salt 823 s, 10 peers max); removing the
`PeerSharing` entry of `ConfigSettings.unsupported`; `advertise` honoured.

*Files.* `protocol/.../PeerSharing.scala`, `network/.../PeerSharing{Client,Server}.scala`,
`network/.../NodeToNode.scala` (offer), `network/.../selection/PeerShare.scala`, `fuzz/...`, tests.

*Gates.* Level 2 plus the decoder rule: `check`, `scripts/sbtw "fuzz 100000 1 4"`, `relay --seconds 300`. Scarab
speaks to the Haskell relay of the `relay` scenario; whether that relay has peer sharing on depends on the devnet
configuration (the reference's default is on for a node without producer credentials, `POM.hs:899-905`), which
the stage checks and, if needed, sets, so that this is a live two-way check.

*Evidence.* The CDDL cross-check; the layer-2 property `prop_governor_peershare_1hr` and the "not too chatty"
and "results used" properties; a live check that a Scarab node started with only one local root learns other addresses from the Haskell
relay (`known` grows past the local roots; the relay shares only peers it advertises and has connected to), and that Scarab, as a
server, answers the Haskell relay's request with peers that are `DoAdvertisePeer`, successfully connected and
with fail count 0 and never more than asked; a row in the compatibility matrix for the mini-protocol.

### G1.5 Genesis hooks (#139, implemented)

*Delivered scope.* Hooks only. `ConsensusMode` as an input; the sync targets chosen while `TooOld` in `GenesisMode`; the
Genesis monitors (`monitorLedgerStateJudgement` in Genesis mode, the stale-targets rule at
`Monitor.hs:114-140`); `minNumberOfBigLedgerPeers` as the output "trusted state" boolean for consensus (an
`Ask`/`Tell` seam, unused until G3); the `jobVerifyPeerSnapshot`; the `OutboundConnectionsState` output with its Genesis case (G1.1 has the bootstrap and local-root cases); refusing `bootstrapPeers` in Genesis mode as
`TopologyP2P.hs:104-108`; `GenesisMode` remains accepted with a partial-implementation diagnostic until G3 lands.

*Files.* `network/.../selection/Sensitivity.scala` (extended), `node/.../PeerGovernor.scala`, `NodeConfig.scala`,
tests: the bootstrap/Genesis properties of section 6 in Genesis form.

*Gates.* Level 2; `check` and `relay`. Genesis chain-density and ledger-dependent fork-choice evidence belongs to G3.

*Evidence.* Section 7g records the focused suites, frozen-source gates and private relay result. The relay's network and
duplex checks pass, but its transaction-inclusion gate fails on two attempts; this acceptance gap remains open. The remaining GDD, LoE, LoP and trusted-state consumer are
[#33](https://github.com/KD5UJC-XYZ/scarab/issues/33).

### When G1 is done

[Roadmap](../../roadmap.md) G1 is marked done after G1.4 (the original issue's scope: targets per peer set, churn,
PeerSharing, simulation properties, the Preview acceptance). G1.5's #139 hooks and the #142 topology/DNS/duplex
follow-ups are also implemented; the remaining Genesis consensus rules are G3. The compatibility
matrix row "Network" gains "outbound peer selection" with the evidence log rows of G1.1 and G1.3; the issue's
closing comment lists the commits and the evidence of each stage; the architecture chapter gains
"Peer selection (G1)" next to "Connections" in the same change as each stage; `docs/running.md` loses the
"the node has no peer selection governor" sentence at line 807.
