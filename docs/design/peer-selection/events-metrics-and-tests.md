<!-- SPDX-License-Identifier: Apache-2.0 -->
# Events, metrics and test strategy

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 5. Events and metrics

Following [ADR 0014](../../adr/0014-typed-log.md): `PeerSelectionEvent` is a closed enum next to `InboundEvent`, with
a `Traced` instance per case and `EventKind` entries under `Area.Net` (so the namespaces are
`Net.PeerSelection.*`; kinds, severities and fields are in the catalogue of [running.md](../../running.md) and
checked by `LogDocsSuite`). The reference's trace is `TracePeerSelection` (`Types.hs:1646-1840`); we mirror the
cases that carry a decision, with the same information:

| Case (reference) | Meaning | Severity |
|---|---|---|
| `PromotedCold/Warm`, `Demoted…` (`TracePromoteColdPeers`, `…Done`, `…Failed`, `TracePromoteWarmPeers`, `…Done`, `…Failed`, `…Aborted`, `TraceDemoteWarmPeers`, `TraceDemoteHotPeers`, `TraceDemoteAsynchronous`, each with the big-ledger and local-root variants) | one decision or one completion, with target, count, the peers and the delay | Info (Failed: Warning) |
| `ForgottenPeers`, `ForgetColdPeers` | peers dropped | Info |
| `TargetsChanged`, `LocalRootPeersChanged` | monitor events | Notice |
| `PublicRootsRequest/Results/Failed`, `BigLedgerPeersRequest/Results/Failed`, with the backoff | discovery | Info |
| `PeerShareRequests`, `PeerShareResults`, `PickInboundPeers` | G1.4 | Info |
| `ChurnAction`, `ChurnTimeout`, `ChurnWait`, `ChurnMode` (`Types.hs:1843-1855`, `Churn.hs:269-276`) | G1.2 | Info |
| `LedgerStateJudgementChanged`, `BootstrapPeersCriticalTimeout` | G1.1/G1.5 | Notice / Error |
| `GovernorWakeup` | the sleep ended | Debug |

*Counters.* After every pass the governor publishes `PeerSelectionCounters` (`Types.hs:851-1060`) and traces it
when it changed (`Governor.hs:617-633`). The metric names are the reference's
(`Tracing/PeerSelection/Governor/PeerSelectionCounters.hs:66-125`), exported as the J3 catalogue exports the
others (the Prometheus form with the `cardano_node_metrics_` prefix, checked against a scrape of the reference
image in the same way J3 did): `peerSelection.Cold`, `Warm`, `Hot`, `ColdBigLedgerPeers`, `WarmBigLedgerPeers`,
`HotBigLedgerPeers`, `WarmLocalRoots`, `HotLocalRoots` (the deprecated group, lines 66-90, which dashboards
still use), and `RootPeers`, `KnownPeers`, `ColdPeersPromotions`, `EstablishedPeers`, `WarmPeersDemotions`,
`WarmPeersPromotions`, `ActivePeers`, `ActivePeersDemotions`, the `…BigLedgerPeers…` and `…LocalRootPeers…` and
`…NonRootPeers…` families (96-125), and the bootstrap-peer views the Cardano extension adds (known, cold promotions, established, warm
demotions and promotions, active, active demotions; `CD/PeerSelection/ExtraRootPeers.hs:50-70`). This closes the "`peerSelection_*` not exported" gap in
[running.md](../../running.md) line 807 (and the J3 remainder "a peer-selection analogue"). Scarab adds
`scarab_peer_selection_churn_rounds_total`, `…_backoff_seconds` (histogram of cold-promotion delays),
`…_peer_metrics_board_size`; `GET /connections` gains the peer's temperature, fail count and source.

## 6. Test strategy

Three layers, each answering a question the others cannot.

**1. Pure properties of the decision function** (`network` tests, ScalaCheck, no effects, runs in `check`). Each
mirrors a reference property by name, with the reference's generator idea (`TEST/PeerSelection.hs`):

| Group | Reference properties (lines in `TEST/PeerSelection.hs`) |
|---|---|
| structure | `prop_peerSelectionView_sizes` (223), invariants after every step (`assertPeerSelectionState`, `Types.hs:1328`) |
| basic | `prop_governor_hasoutput` (453), `…nofail` (514), `…nolivelock` (554: at most N decisions at one simulated instant), `…nobusyness` (676) |
| targets, known | `…target_known_below` (1579), `…known_1_valid_subset` (1612), `…2_opportunity_taken` (1688), `…3_not_too_chatty` (1856), `…4_results_used` (2069), `…5_no_shrink_below` (2154), `…known_above` (2340) |
| targets, established/active | `…established_below` (2573), `…established_above` (3064), `…active_below` (2811), `…active_above` (3266) and the public variants (1258, 1407) |
| local roots | `…established_local` (3422), `…active_local_below` (3555), `…active_local_above` (3688) |
| big ledger | the `…big_ledger_peers…` variants (1592, 2254, and the four in the "big ledger peers" group, 164-179) |
| bootstrap / Genesis | `…only_bootstrap_peers_in_fallback_state` (3759), `…no_non_trustable_peers_before_caught_up_state` (3824), `…only_bootstrap_peers_in_clean_state` (3900), `…stops_using_bootstrap_peers` (4010), `…uses_ledger_peers` (4086), `…association_mode` (4139) |
| connection status | `prop_governor_connstatus` (1108): the status events in the trace are consistent with the connections |
| repromote | `prop_governor_repromote_delay` (4562): a failed peer is not retried before its fuzzed delay |
| peer share | `prop_governor_peershare_1hr` (1053): a graph reachable by peer sharing is discovered in one simulated hour |
| regressions | `prop_issue_3233`, `3494`, `3515`, `3550` (4501, 4462, 4419, 4370): the four fixed scenarios, ported as fixed scripts |

Unit properties beside them: `LocalRootPeers.fromGroups` repairs any input to a valid value and
`clampToLimit` has `size == min limit size` (`PS/State/LocalRootPeers.hs:159-220`; reference tests in
`ouroboros-network/tests/lib/.../PeerSelection/LocalRootPeers.hs`); the known-peers table properties
(`.../PeerSelection/KnownPeers.hs`); the peer-metrics board (`.../PeerSelection/PeerMetric.hs`: earliest
arrival wins, 180-entry bound, the join-time correction is zero); the ledger-peers draw (`Test/Ouroboros/Network/LedgerPeers.hs`:
the draw is proportional to `sqrt` of stake, big ledger peers are the top 90%); the churn arithmetic (`decrease`
table: `decrease 1 = 0`, `decrease 5 = 4`, `decrease 10 = 8`, `decrease 100 = 80`; the twelve steps and the
sync-regime clamps).

**2. A simulated environment** (`MockEnvironment`, the analogue of `GovernorMockEnvironment`, `TEST/PeerSelection/MockEnvironment.hs:139-163`):
a graph of peers with scripted connection outcomes, scripted targets, local roots, public roots, the ledger
judgement and the ledger peers, and scripted pick results; the governor loop runs on `TestControl` (virtual time,
as `FollowerSuite` runs on it, with its 64 join/pause orders and seeds), with `PeerStateActions` and the
providers replaced by the script. This is the issue's "deterministic simulation properties for the state
machine": the same seed and script give the same trace, byte for byte (a golden for one scenario). The
properties of layer 1 that need time (backoffs, wake-ups, no livelock, the one-hour peer-share discovery) run
here; the reference runs them in `IOSim`, we run them in `TestControl`. `check` runs it in a few seconds with a
bounded number of cases; a longer run (`SCARAB_PROPERTY_CASES`) is for CI.

**3. Real peers.** The devnet has three Haskell pools; "many peers" is therefore built two ways.
*In-memory:* a `PeerSuite`-style test with 40 in-memory bearers (the `InboundGovernorSuite` pattern, a
`Peer`-level fake on each end) so the governor, the real `ConnectionManager`, `Outbound` and `Follower.session`
run together against a population larger than the targets and the test asserts convergence to the targets,
that churn rotates the hot set within a bounded time (virtual), and that killing hot peers is repaired.
*Live:* the existing `relay --seconds 300` (the one scenario for the network area, CONTRIBUTING) is the gate for
G1.1 to G1.4, plus a new devnet scenario `governor` (G1.2): the Scala node gets the three pools as a topology
(one local root group `hotValency 2, warmValency 3`, one public root) and a lag proxy
(`scripts/devnet/lagproxy.py`, as `peercut` uses) in front of one pool; it asserts the node reaches the targets
(`warm == 3`, `hot == 2` from `/metrics`), that after the proxy is silenced the governor demotes that peer and
promotes the spare one (hot set changes, the node keeps extending), that the demoted peer comes back after its
backoff, and, with `--churn-interval` shortened by a test-only flag, that one churn round replaces a hot peer.
The Preview run (below) is the "reaches the tip from root peers only" evidence.

The Haskell tests themselves are not run: the reference image has no test binaries. Where a reference test has a
fixed script (the four `prop_issue_*`, the `LocalRootPeers` examples) the script is ported as data and
cited by line in the test.

### 6.1 DNS roots and re-resolution

`PeerDns` is a node-owned resource backed by dnsjava 3.6.5's query-aware `LookupSession`; its asynchronous
lookups are cancelable, each A, AAAA, or SRV request has a five-second timeout, and its four-thread executor closes with the network resource. A and AAAA requests run concurrently, so a hostname lookup is bounded by the same five-second budget. Host roots retain every
successful A and AAAA address and source provenance, including aliases that resolve to the same socket. Local
group valencies, per-root flags, and the original hostname/SRV specification remain available for refresh. A
valid topology reload replaces that specification and its resolved view through the acknowledged
`RootsConfigured` event; a failed parse does not publish a partial replacement. Each configured DNS source has
an independent cache and deadline. A successful empty answer removes that source's old addresses and retries
after 60 seconds; a failed answer retains its last successful addresses. Name errors retry after 900 seconds,
and other errors retry after `clip(previous interval * 2 + 5 seconds, 60, 900)`. DNS refreshes carry a root
generation, so a late answer cannot replace a newer topology or answer.

SRV queries for topology and ledger relay names use `_cardano._tcp.<domain>`. Scarab follows the pinned
`ouroboros-network` behavior: discard target `.`, retain the numerically lowest priority, choose by advertised
weight within that priority, resolve all A/AAAA records for the chosen target, and use the chosen SRV record's
TTL instead of target-address TTLs. Successful RR TTLs are clipped to 60–900 seconds; the refresh interval is
the maximum successful clipped TTL, or 60 seconds if there are no successful answers. Fixed IP roots do not
contribute a DNS TTL. DNSSEC validation is not implemented or claimed.

Reference: `ouroboros-network` pin `c45735a56c567fa977969173d18943bac6bb3821`,
[`RootPeersDNS/DNSActions.hs`](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/src/Ouroboros/Network/PeerSelection/RootPeersDNS/DNSActions.hs#L406-L460)
for SRV filtering/priority/weight and selected-target TTL, the same file's lines 614–635 for TTL clipping,
and [`RootPeersDNS/LocalRootPeers.hs`](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network/src/Ouroboros/Network/PeerSelection/RootPeersDNS/LocalRootPeers.hs#L195-L253)
for retaining prior addresses on failure and independent per-source monitoring, lines 295–308 for TTL selection,
clipping, and failure retry policy. The ledger relay prefix comes from
[`RelayAccessPoint.hs`](https://github.com/IntersectMBO/ouroboros-network/blob/c45735a56c567fa977969173d18943bac6bb3821/ouroboros-network-api/src/Ouroboros/Network/PeerSelection/RelayAccessPoint.hs#L312-L320).
The resolver dependency is pinned to `dnsjava:dnsjava:3.6.5`; this artifact is beyond the fix in the
[GHSA-cfxw-4h78-h7fw advisory](https://github.com/dnsjava/dnsjava/security/advisories/GHSA-cfxw-4h78-h7fw)
and corresponds to the [3.6.5 release](https://github.com/dnsjava/dnsjava/releases/tag/v3.6.5-1)
(`ca25720`).

The fake-DNS evidence is in `PeerDnsSuite`: it distinguishes unequal SRV weights and TTLs, lowest-priority
selection, root-target filtering, selected port/TTL, multiple A/AAAA answers, alias provenance, fixed-IP
coexistence, and local group refresh. The owner-event tests cover generation supersession. The private relay gate
is recorded in section 7g when available.
