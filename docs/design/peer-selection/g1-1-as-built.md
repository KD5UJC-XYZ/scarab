<!-- SPDX-License-Identifier: Apache-2.0 -->
# G1.1 as built and its acceptance run

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7a. G1.1 as built

Branch `g1/1-governor`, on `main` after J2.9. The decisions of section 8a are all implemented; [ADR 0016](../../adr/0016-peer-states-and-the-governor.md)
records D2 (the in-progress states are cases of `Status`) and the placement. What differs from the plan above:

**Placement.** `network/.../selection/` holds the pure part: `Status`, `Sensitivity`, `GovernorState`, `Policy`, `Vocabulary`
(`Event`, `Job`, `Outcome`, `Trace`, `Step`), `PeerSelection` (`decide`, `abort`, `apply`), `Events`, `Targets`, `LocalRoots`,
`Rng`, `Regime`, `Gsm`. `node` holds `PeerGovernor` (the loop and `PeerStateActions`), `OutboundPeers` (the interpreter),
`PeerNetwork` (roots, settings, the regime loop, `ChainView`), `GsmRunner`, `FailurePolicy`. `Outbound.scala` and
`Follower.supervise`/`Retry` are gone. The plan's `RootProvider`/`PublicRoots` files are `PeerGovernor.RootProvider` and
`Vocabulary.PublicRoots`.

**Loop.** The loop waits on its queue only. A wake-up is a timer fiber that offers `Event.Wake`, deduplicated by time in a `Ref`.
An earlier version raced a sleep against `take` and lost a completed job; a second kept the pending time in the loop state
and went stale when a drain consumed the wake-up. `Step.Idle` carries `wakeAt`, and `decide` never returns a time in the past
(a past time made the loop spin).

**Warm and hot.** `NodeToNode.warm` (`WarmConnection`) opens a connection with KeepAlive and the handshake; `activate` starts
ChainSync, BlockFetch and TxSubmission2; `finish` ends them with `MsgDone` (ChainSync and BlockFetch only when idle, so a
client in the middle of a request is not finishable and the connection is closed instead; TxSubmission2 at the peer's next
blocking request). The server loops run `foreverM`, so a second conversation can start on the same bearer. `Peer.Controlled`
has `quiesce`; a hot session is a `Resource` and the claims on blocks in the `BlockPool` belong to it.

**Failures.** `FailurePolicy.classify` is the one function (decision 8): `Protocol` (a malformed or oversize message, a message
the state does not allow) and `InvalidHeader` are `Misbehaved`, so the peer is forgotten unless it is a local root or a bootstrap
peer; `Unavailable`, `Handshake`, `NoIntersection` and `Diverged` (a new `PeerError`: the peer rolled back beyond what we keep, or
our chain moved on so that its candidate no longer joins it, nobody's fault) are `Transient`, so the peer is backed off.
"Peer does not have the announced block" used to be a `Protocol` error and is now `Unavailable`.

**Fetch mode.** Praos mode uses `readFetchModeDefault`: bulk sync in the sensitive state, else by the distance of the tip from
the wall clock (1000 slots). The earlier G1.1 implementation ignored `GenesisMode`; #139 now supplies its GSM judgement and sync
targets to churn and peer selection. The mode is published (`fetchModeChanged`, `scarab_fetch_mode`) and picks the regime of the
targets; BlockFetch follows the current mode as described in section 7g.

**Properties.** Ported by name from `Test.Cardano.Network.PeerSelection` (ouroboros-network 1.2.0.0) onto `TestControl`, with a
mock environment and `Signals` (the reference's `signalProperty`): `prop_peerSelectionView_sizes`, `prop_governor_hasoutput`,
`prop_governor_nofail`, `prop_governor_nolivelock`, `prop_governor_nobusyness`, `prop_governor_connstatus`,
`prop_governor_target_root_below`, `_known_above`, `_established_below`, `_established_above`, `_established_local`, `_active_below`,
`_active_above`, `_active_local_below`, `_active_local_above`, `prop_governor_repromote_delay`,
`prop_governor_only_bootstrap_peers_in_fallback_state`, `prop_governor_no_non_trustable_peers_before_caught_up_state`,
`prop_governor_only_bootstrap_peers_in_clean_state`, `prop_governor_association_mode`, and the fixed-environment regressions
`prop_issue_3233`, `3494`, `3515`, `3550`. Not ported: `prop_governor_stops_using_bootstrap_peers` (as stated in the reference the
condition reads inverted; "young enough again: the bootstrap peers that were not connected are forgotten" checks what the
code does at the flip); the properties of big ledger peers, peer sharing and churn (later stages). The suite has a fixed
ScalaCheck seed: `prop_governor_nobusyness` is a credit heuristic that the doubling cold backoff of one failing peer (5, 10, 20,
40, 80 s with gaps under 45 s) exceeds by a few seconds in about one environment in 300 (found at tests 10 and 273 of two random
runs), as it would for the reference, which has the same backoff. A determinism test shows that one seed and one environment
give one trace (`TestControl` is given the environment's seed). The decision function has 17 examples (`PeerSelectionSuite`), the
Genesis State Machine 8, the primitives 6; a 256 KiB-stack thread decides, completes and checks the invariant for 4000
local roots (ADR 0011); `FortyBearersSuite` runs the governor, the connection manager, the interpreter, the follower and the block
pool against 40 in-memory peers: 30 established and 20 hot reached, the chain reaches the tip, and after 8 hot peers are killed
the hot set is repaired.

**Deviations, in one place.**

1. Decisions 1 to 12 of section 8a hold; `GovernorFault` and `PeerError.Diverged` are new error cases.
2. At the G1.1 baseline, the fetch mode followed the Praos function rather than the GSM state; #139 adds Genesis sync-target selection through churn. Full Genesis consensus remains G3.
3. The node does not stop after 30 failed attempts any more; its only governor stop is `BootstrapPeersCriticalTimeout`.
4. The big ledger targets are carried and published but nothing uses them (no ledger peers, G1.3). The public roots request returns
   the bootstrap peers and the configured public roots only.
5. `GET /connections` has the governor's view in `peerSelection`; the temperature of each connection is there, not in the
   connection table. `OutboundConnectionsState` is a gauge and a log line (decision 11).
6. Targets are static: no churn (built in G1.2, section 7b).

**Evidence.** Rows in the evidence log of `docs/compatibility.md`; the cost per hot session in `docs/performance.md` section 18.
Gates: `check` and `scalafixAll --check`, `devnet.py relay --seconds 300` (3 peers and 1 peer), `peercut`, `pool`.
**The level-4 acceptance was not met on the first attempt and was met on the second (section 7b).** The first Preview run from the official files with no `--peer` ran 30
minutes but from genesis: the K1 database could not be replayed by this build ([#145](https://github.com/KD5UJC-XYZ/scarab/issues/145)),
so the node applied 517,605 blocks (epoch 132) and did not reach the tip. Promotions, demotions and the targets being met (30 established,
20 hot, repair after losses) are shown by `FortyBearersSuite`, the properties, and the devnet scenarios (`peercut` made the silent peer
lose its place).

## 7b. G1.1 acceptance run

The level-4 run of section 7 ("Evidence it must produce"), after the #145 fix: a Preview follower from the official
`config.json` and `topology.json` with no `--peer`, 30 minutes of wall time, read-only towards the public network.
Commit 5f3b6174 (G1.1 and the #145 fix), built in the `scarab-jdk:local` image (JDK 21.0.11), the classes frozen and run in a
container of 3 CPUs, `-Xmx3g`, `MALLOC_ARENA_MAX=2`, `--metrics-listen`. The database was a copy of a Preview database (format 1,
2026-10-15, immutable tip slot 125,430,904, newest snapshot at slot 125,404,831): immutable chunks hard-linked except the newest
two, which were copied, the newest snapshot and its UTxO checkpoint copied. The node therefore had to migrate the formats, restore
the snapshot, replay the immutable tail and then catch up to the tip from the public network. The official topology has
one bootstrap peer, no local roots, no public roots and a `peerSnapshotFile` (not used before G1.3). The database copy was removed afterwards.

**Catch-up.** Opening the database took 28 s (formats migrated: identity file, 5,807 index files, snapshot to format 5), the
snapshot was restored and 975 blocks replayed in 3.5 s, and the node started with the governor at +28 s:

| Time after start | Event |
|---|---|
| +28.8 s | `peerSelectionTargets` active 2 (the bootstrap regime: the judgement is `TooOld`), `outboundConnections` `UntrustedState`, `peerSelectionSensitive` judgement `TooOld`, then `clean` |
| +28.8 s | `peerSelectionRoots` asked for 60: the bootstrap peer was found; cold to warm decided and done within 0.3 s, warm to hot within 16 ms; `outboundConnections` `TrustedStateWithExternalPeers` |
| +29.2 s | ChainSync intersection at slot 125,442,004; at +29.7 s GSM `preSyncingToSyncing` |
| +29.9 to +46.0 s | 6,653 blocks applied in 16.1 s: 412 blocks/s on average over the catch-up, 618/s at the best second (two epoch boundaries validated on the way, slots 125.45 M and 125.54 M) |
| +46.0 s | GSM `enterCaughtUp`, `peerSelectionSensitive` judgement `YoungEnough`, the sensitive state ends |
| +46.7 s | `fetchModeChanged` `Deadline` (the only change event of the run), `peerSelectionTargets` active 20 (the relay regime: established 30, known 150, root 60) |

**Steady state (29 minutes).** One peer, hot, origin `Bootstrap`, fail count 0: cold 0, warm 0, hot 1, known 1, root 1 at every scrape. 70
further blocks were applied (the tip followed the peer's tip within one block at each scrape). The governor asked for public roots 15
times with exponential backoff from 2 s doubling to 256 s (found none, as the topology has none) and decided nothing else: no
demotion, no forgetting, no reconnect, no second promotion. 0 events of severity Error, 0 stalls (`scarab_follower_stalls_total 0`), 0
reconnects. The shutdown on SIGTERM wrote a snapshot and closed the connection.

**Metrics, scraped every 70 s, three quoted** (`/metrics`, at +1, +13 and +31 minutes):

```
cardano_node_metrics_peerSelection_Cold_int 0   Warm_int 0   Hot_int 1   RootPeers_int 1
scarab_peer_selection_targets{target="Root"} 60   {Known} 150   {Established} 30   {Active} 20
scarab_peer_selection_sensitive 0
scarab_peer_selection_transitions_total{transition="PromotedWarm"} 1   {PromotedHot} 1
scarab_gsm_state{state="CaughtUp"} 1      scarab_fetch_mode{mode="Deadline"} 1
scarab_outbound_connections_trusted 1
slotNum 125616739 / 125617496 / 125618612   (peer tip slot 125616739 / 125617437 / 125618558)
```

`GET /connections` at the end: `peerSelection` counters `cold 0, warm 0, hot 1, known 1, root 1`, targets `root 60, known 150, established 30, active 20`,
judgement `YoungEnough`, `sensitive false`, one peer, `hot`, origin `Bootstrap`, `failCount 0`.

**Cost.** CPU (container, 100% is one CPU): the first minute (restore, replay and catch-up) mean 127%, peak 277%; the 29 minutes after it mean
5.4%, peak 6.2%; the whole run mean 9.9% (the process's own CPU time was 171 s of 1,890 s). Memory (the container's usage as `docker stats`
shows it): mean 1.74 GiB, peak 2.50 GiB (during the catch-up), 1.63 GiB anonymous at the end, heap in use 0.84 GB, the process's peak resident 2.55 GiB.
These are the cost of one hot peer on the tip, not the D5 measurement (20 against 1 active peers, `performance.md` section 18).

**What it shows and what it does not.** Shown: the reference's sequence for a node that starts behind: sensitive state with the bootstrap
regime of the targets, cold to warm to hot for the bootstrap peer, the GSM leaving PreSyncing and reaching CaughtUp, the judgement
`TooOld` then `YoungEnough`, the end of the sensitive state, the fetch mode changing to `Deadline`, the targets switching from the
bootstrap regime (active 2) to the relay regime (active 20), the tip reached from a restored snapshot, and nothing wrong for 29 minutes.
Not shown: demotions, forgetting, the targets being met, churn, a second source of peers. The public network, for this configuration, offers one
peer: the official topology lists one bootstrap peer and no roots, and the other peers (the ledger peers and the `peerSnapshotFile` big ledger
peers) need G1.3, so known stays 1 against a target of 150 and the node is below its targets for the whole run by design. The 20-active
target is therefore exercised only by the properties, `FortyBearersSuite` and the devnet. The catch-up was short (about two and a half days of slots) because
the newest snapshot was used; a longer catch-up (older snapshot) was not run.
