<!-- SPDX-License-Identifier: Apache-2.0 -->
# G1.2 as built

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 7b. G1.2 as built

Branch `g1/2-churn`, on `main` after G1.1. Sources: the reference files of section 1.1; the churn and metrics files of the
copy of ouroboros-network `main` in the workspace (1.3.0.0) are identical to the pinned tag at the cited lines
(`Churn.hs` 167-214 and 561-697, `PeerMetric.hs` 85-618); `Policies.hs` and the BlockFetch `Decision.hs` were read in the copy of
`main`, which differs from the pin in `Policies.hs` (below).

**Placement.** `network/.../selection/` gains `PeerMetrics` (the two boards) and `Churn` (the steps, their targets, timeouts and
the sleep, as pure functions); `node` gains `ChurnLoop` (the effect, over the algebra `ChurnWorld` with `Ask` for its settings and
`Tell` for its trace) and `BlockPool` takes the fetch mode. `PeerSelection.decide(state, now, metrics)` takes the metrics as an
argument; `PeerGovernor` reads them through `Ask[F, PeerMetrics[A]]` once per pass.

**Peer metrics.** `Peer.Observation` gains `HeadersArrived(slots, at)` (the slots of the roll-forwards of one ChainSync batch, one
arrival time) and `BlocksArrived(blocks, at)` (the `(slot, size)` of one BlockFetch request). One observation per batch, not per
header (the reference reports per header; the headers of a pipelined batch arrive together, so the arrival time is the same, and one
`Ref` update per accepted group keeps the callback bounded). `PeerMetricsObserver` attaches the peer's remote address for outbound and inbound duplex client sessions and updates one shared `Ref[PeerMetrics]`.
Header usefulness is reported only after header acceptance, using its captured arrival timestamp;
traffic counters remain independent. Inbound serving-only connections contribute no client arrivals. The board is the reference's: earliest arrival wins, 180 slots, an older slot on a
full board is dropped, the join and last-seen registries are pruned at the oldest slot of the boards, and `adjustAvg` is not
implemented (it is `0` in the pinned release). The hot-demotion pick ranks `(upstreamness + fetchynessBlocks, joined slot, random)`;
a peer the board does not know ranks before all that it does (`Map.findWithDefault (0, Nothing)`), and both the local-root and the
other hot demotions use it.

**Churn.** The twelve steps in the reference's order, with its timeouts (300, 60, 121, 60, 80, 60, 300, 60, 121, 60, 80, 60 s; the
request timeout of steps 5 and 11 is `policyPeerShareOverallTimeout`, 10 s), the three regimes, `decrease` and `decreaseWithMin`,
the mode-specific head start, and the sleep (3300 s plus up to 600 s in Genesis or Praos deadline mode,
900 s plus up to 60 s in Praos bulk sync, less the time the round took).
`ChurnLoop` is the one writer of the governor's targets; the 1 s regime loop of G1.1 is now only the publisher of the fetch mode.

**Deviations, in one place.**

1. **Resolved by #311.** All twelve steps run regardless of ledger-query configuration, retaining the configured
   big-ledger targets instead of rewriting them to zero.
2. **Resolved by #311.** Raw proposals preserve native negative active targets and root/known mismatches.
   The peer governor ignores an insane latest proposal; queued wakes never replay a superseded valid snapshot.
3. **Resolved by #311.** Counter waits observe published-counter notifications rather than polling, preserving
   the counter value that satisfies the predicate and taking the success branch at the timeout boundary.
4. **Resolved by #311.** Regime changes do not interrupt the native sleep. Genesis always uses the default
   churn regime and long interval, retains complete selected targets at startup, and selects sync targets while
   `TooOld`. Fuzz conversion uses native binary-rational floor to picoseconds, retaining signed waits; only the
   final host timer is rounded to nanoseconds.
5. **Resolved by #150.** Inbound duplex and outbound client sessions report into the same metrics
   board. The key is the remote socket address; activation/session identity and connection direction
   do not partition scores. An ephemeral inbound source port remains that connection's remote
   address; it is not guessed to be a separately advertised listening endpoint. First arrivals
   compete across both directions, and equal timestamps retain the existing winner.
   Pinned consensus `82ecba329`,
   `ouroboros-consensus-diffusion/.../Consensus/Network/NodeToNode.hs:795–921`,
   installs metric tracers for every client connection. Pinned network `2fb69829`,
   `ouroboros-network/.../PeerSelection/PeerMetric.hs:242–254`, projects its remote address.
   `MiniProtocol/ChainSync/Client.hs:1487–1520` retains receipt time through successful
   candidate admission before reporting usefulness; `BlockFetch/Client.hs:249–281` reports
   after requested-point, block-integrity and fetched-block admission checks.

6. **Resolved by #149.** The current network pin `2fb69829a19b5c04e4fb212bdd78fb63f9d2d776`
   weights warm promotion with `promoteWeight` (`Diffusion/Policies.hs:201–250`): non-tepid
   peers receive one quarter of their unsigned Word32 draw. Ordinary, local-root and big-ledger
   warm activation now use this policy. Ordinary and big-ledger hot-demotion traces retain the
   native optional score for every eligible peer, distinguishing an absent metric from zero;
   local-root traces follow the native discarded-score contract. The
   [912 native observations](../../../fixtures/peer-selection/policies/README.md) compare all five
   production ranking functions and complete score maps using identical explicit draws. Native
   and Scala PRNG streams are not equated; the selected sets and score fields are compared without
   masks or changed outcomes.
7. **BlockFetch (#144/#148).** A shared coordinator now applies the pinned peer rankings,
   global bulk/deadline targets with preferred-peer handover, GSV request/byte windows and
   FIFO outgoing pipelining. Inbound duplex and recovery use the same accounting. The
   [as-built contract](../block-fetch-coordination.md) and original native decision corpus
   supersede the former per-block-only deadline cap. Full Genesis remains G3.

**Properties and tests.** `ChurnSuite` (the `decrease` table 1 to 0, 5 to 4, 10 to 8, 100 to 80; the twelve steps, their order and
timeouts; a round at the relay defaults in each regime; invalid raw boundary proposals; signed sleeps),
`PeerMetricsSuite` (`prop_insert_peer`, `prop_metrics_are_bounded`, `prop_bounded_size` of `PeerMetric.hs`, the earliest-arrival
and 180-slot rules, `adjustAvg = 0`, the registry, the ranking), `PeerSelectionSuite` (a demotion by the metrics), `ChurnLoopSuite`
(`TestControl`: head start, the first round after the interval, targets step by step, timeouts, bulk sync interval, regime change),
`BlockPoolSuite` (both modes), `FortyBearersSuite` (six peers reach the same tip in bulk sync and in deadline mode with one and with
two peers per block, and the boards fill), and 256 KiB-stack tests of the metrics and of the churn arithmetic (ADR 0011). The
reference's `prop_governor_*` properties with churn on are not ported separately: the reference runs its churn only in the
diffusion tests; here the properties of G1.1 run with the metrics in the decision (the default is empty) and the churn properties are
the pure ones above.

**Evidence.** Rows in the evidence log of `docs/compatibility.md`; the cost of the callbacks in `docs/performance.md` section 19.
Gates (level 2): `check` with `scalafixAll --check` (all modules green), `devnet.py relay --seconds 300`, `peercut`, `governor`.

- `governor` (three pools as public roots, pool 1 behind a proxy that delays every chunk by 0.4 s so that it is never first;
  targets 3/3/3/2; `--churn-interval 12`): the targets were reached (3 established, 2 hot, 1 warm, 3 known; the reference's gauges);
  5 churn rounds in about 300 s, each with the six ordinary steps in order, the active decrease reached and one hot peer
  demoted, the hot count back at 2 after the increase; the slow peer was hot at 2 of the 5 demotions and was the peer demoted both
  times (the lowest score), and the hot set changed between rounds; the proxy then went silent while the slow peer was hot: the
  node lost it (the deactivation the governor asked for failed on the dead connection), held two other hot peers, extended its
  chain (block 188 at the cut, 195 later, the reference at 190), and once the proxy was released the peer came back (3
  established, 2 hot). `scarab_peer_selection_churn_rounds_total` equals the rounds in the log, both metric boards hold 180 slots, 0
  errors, 0 stalls. An earlier run of the same scenario, with the waits capped at the interval, found that the active decrease takes
  as long as the hot session needs to end (the peer's next TxSubmission2 blocking request, about a minute on the devnet), so
  `--churn-interval` now caps only the waits for connecting and for the known set.
- `relay --seconds 300` passed (3 hot peers, tips equal, 0 errors); `peercut` passed on the second of two runs. The first lost the
  slow peer as `diverged` instead of by the ChainSync timeout ([#152](https://github.com/KD5UJC-XYZ/scarab/issues/152), a path of
  G1.1).
- The Preview comparison of the build before and after: 188,373 and 188,979 blocks in 660 s, no reconnect or error (performance.md 19).
- Not done: a scrape of the reference image for the new metrics. The reference has no churn metric (the churn governor only traces,
  `Cardano/Network/Tracing/Churn.hs`), so `scarab_peer_selection_churn_*` and `_peer_metrics_board_size` are Scarab's; the
  `peerSelection_*` gauges were scraped in J3 and are unchanged.
