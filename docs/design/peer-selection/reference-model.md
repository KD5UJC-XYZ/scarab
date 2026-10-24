<!-- SPDX-License-Identifier: Apache-2.0 -->
# The reference model

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

## 1. The reference model

### 1.1 What was read, and the pin

`cardano-node` 11.1.3 (`cabal.project` and `cardano-node/cardano-node.cabal` at that tag) asks for
`ouroboros-network ^>= 1.2` (`cardano-node.cabal:180`) and `cardano-diffusion ^>= 1.1.1`
(`cardano-node.cabal:181`), with the CHaP index state of 2026-09-23 (`cabal.project:15-17`). The sources read are
the GitHub tag `ouroboros-network-1.2.0.0` of IntersectMBO/ouroboros-network (`ouroboros-network.cabal:3`
version 1.2.0.0; the repository also holds `cardano-diffusion` 1.1.0.0, `cardano-diffusion.cabal:3`) and the
`11.1.3` tag of IntersectMBO/cardano-node. Caveat: the cabal bounds allow a later patch release than these tags
(`cardano-diffusion ^>= 1.1.1` is above the 1.1.0.0 in the tag's tree), and the CHaP tarballs were not fetched;
G1.1 starts by diffing the files cited here against the CHaP releases the reference image was built from
([reference-image.md](../../reference-image.md)) and correcting any citation that moved.

The code is split in two layers, and so are the citations. The generic governor lives in
`ouroboros-network` (`Ouroboros.Network.PeerSelection.*`). Everything Cardano-specific (bootstrap peers, the
ledger state judgement, Genesis targets, the churn governor that cardano-node runs) lives in `cardano-diffusion`
(`Cardano.Network.PeerSelection.*`) and plugs into the generic one through `extraState`, `extraPeers`,
`extraFlags` and `ExtraGuardedDecisions` (`Governor/Types.hs:461-543`).

Path abbreviations used below (all relative to the repository root of the named tag):

| Short | Path |
|---|---|
| `G/` | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/Governor/` |
| `PS/` | `ouroboros-network/lib/Ouroboros/Network/PeerSelection/` |
| `API/` | `ouroboros-network/api/lib/Ouroboros/Network/PeerSelection/` |
| `ON/` | `ouroboros-network/lib/Ouroboros/Network/` |
| `CD/` | `cardano-diffusion/lib/Cardano/Network/` |
| `CDA/` | `cardano-diffusion/api/lib/Cardano/Network/` |
| `CN/` | cardano-node 11.1.3, `cardano-node/src/Cardano/Node/` |
| `SPEC/` | `docs/network-spec/` of ouroboros-network (the "Shelley Networking Protocol" document) |
| `DESIGN/` | `docs/network-design/network-design.tex` (the design document) |
| `TEST/` | `cardano-diffusion/tests/lib/Test/Cardano/Network/` |

### 1.2 Three temperatures, three nested sets

Peers are *cold* (known, no connection), *warm* (a bearer is established and used for measurement, not for
consensus protocols) or *hot* (the consensus protocols run). The governor reasons about them in two ways at once:
the disjoint temperatures, and the nested sets *known* ⊇ *established* (warm and hot) ⊇ *active* (hot)
(`Governor.hs:273-316`). Six transitions: discover (cold appears), promote cold to warm, promote warm to hot,
demote hot to warm, demote warm to cold, forget a cold peer (`Governor.hs:306-313`). A peer that is found
adversarial is forgotten from every set (`Governor.hs:217-220`, an overview comment; the classification of the
exceptions into internal, network and adversarial is the `RethrowPolicy` and `ExitPolicy` of the caller and is
outside the files read).

`PeerStatus` has four values, not three: `PeerCold`, `PeerCooling`, `PeerWarm`, `PeerHot` (`Types.hs:23-40`).
`PeerCooling` is "cold, but the connection still lingers"; it ends in `PeerCold` when the connection manager
reports the connection terminated (`Types.hs:27-36`). It corresponds to `ConnectionState.Terminating` in Scarab.

Where the temperatures live on the wire, in cardano-node: hot is ChainSync, BlockFetch and TxSubmission2 (and
the two Peras protocols when supported); warm is empty ("reserved for tip-sample"); "established" is KeepAlive,
plus PeerSharing when the negotiated flag is on (`CD/NodeToNode.hs:254-325`). So a warm Cardano peer is a
connection with KeepAlive running and nothing else.

### 1.3 State

`PeerSelectionState` (`Types.hs:609-705`) holds: the `targets`; `localRootPeers` (groups); `publicRootPeers`
(ledger peers, big ledger peers, configured public roots and bootstrap peers, as one bundle); `knownPeers`,
`establishedPeers` (with the connection handle of each) and `activePeers`; the backoff counters and retry times
of the public-roots request and of the big-ledger-peers request; the in-progress sets
(`inProgressPeerShareReqs`, `inProgressPromoteCold`, `inProgressPromoteWarm`, `inProgressDemoteWarm`,
`inProgressDemoteHot`, `inProgressDemoteToCold`, lines 673-681); a `StdGen` for fuzz and random choices; the
inbound-peers retry time; the big-ledger snapshot; and `extraState`. The Cardano extension is `ExtraState`
(`CD/PeerSelection/Governor/PeerSelectionState.hs:33-61`): `ledgerStateJudgement`, `consensusMode`,
`bootstrapPeersFlag`, `hasOnlyBootstrapPeers`, `bootstrapPeersTimeout`, `minNumberOfBigLedgerPeers`.

What it knows per peer:

- `KnownPeers` (`PS/State/KnownPeers.hs:74-97`): all peers; `availableToConnect` (not backed off);
  `nextConnectTimes` (a priority queue of backoff expiries); `clearFailCountTimes`. Per peer
  (`KnownPeerInfo`, lines 100-145): `knownPeerFailCount`, `knownPeerTepid` ("was hot, then demoted"),
  `knownPeerSharing` (what the peer said in the handshake), `knownPeerAdvertise` (our own willingness to share
  it), `knownSuccessfulConnection`.
- `EstablishedPeers` (`PS/State/EstablishedPeers.hs:78-82`): the connection of each, `nextPeerShareTimes`,
  `nextActivateTimes` (a promoted-to-warm peer is "ready" for promotion to hot only after its activation time;
  `readyPeers`, line 130-134).
- `LocalRootPeers` (`PS/State/LocalRootPeers.hs:47-66`): the map peer to config (`peerAdvertise`,
  `diffusionMode`, `localProvenance`, extra flags = trustable) and the groups `(HotValency, WarmValency,
  Set peer)`. Invariants (lines 102-119): groups are disjoint, `0 < hot ≤ warm ≤ group size`. `fromGroups`
  repairs a bad configuration instead of rejecting it (lines 159-180) and `clampToLimit` keeps the roots within
  `targetNumberOfKnownPeers` (lines 213-220).

### 1.4 The loop and the order of decisions

`peerSelectionGovernor` runs `peerSelectionGovernorLoop` for ever (`Governor.hs:482-494`, `541-640`). Each pass:
publish the public state for PeerSharing (590); abort the node if the state is unsound (`abortGovernor`, 596);
set the current time in the known and established tables, which releases expired backoffs (603-606); evaluate
the guarded decisions (608); apply the chosen `Decision` (state, traces, jobs; 614-640) and fork its jobs on a
`JobPool`.

A *guarded decision* is either a blocking STM action waiting for an event (a connection changed, a job
finished, the targets changed, the local roots changed) or a non-blocking predicate on the state ("below target
and something to pick"), together with the earliest time it could become applicable (`Guarded`,
`Types.hs:1548-1610`). `guardedDecisions` combines them with `<>`, and **the order is the priority**
(`Governor.hs:666-727`):

1. extra `preBlocking`; for Cardano these are `monitorBootstrapPeersFlag`, `monitorLedgerStateJudgement` and
   `waitForSystemToQuiesce` (`CD/PeerSelection/Governor/Types.hs:161-165`), so the sensitive-state machine of
   section 1.15 runs before everything else;
2. `Monitor.connections` (asynchronous demotions: a connection that failed or closed), `Monitor.jobs` (a job
   finished), `Monitor.ledgerPeerSnapshotChange`;
3. targets changed (`Monitor.targetPeers`, or the Cardano replacement) and local roots changed
   (`Monitor.localRoots`, or the Cardano replacement), then extra `postBlocking`;
4. big ledger peers: `BigLedgerPeers.belowTarget`, `aboveTarget`;
5. `RootPeers.belowTarget` (request public roots);
6. `KnownPeers.belowTarget` (peer share or inbound peers), `KnownPeers.aboveTarget` (forget cold peers);
7. `EstablishedPeers.belowTarget` (cold to warm), `aboveTarget` (warm to cold);
8. `ActivePeers.belowTarget` (warm to hot), `aboveTarget` (hot to warm);
9. extra `postNonBlocking`.

Within a family the order is again big ledger peers, local roots, then the rest
(`ActivePeers.hs:80-82` and `649-651`, `EstablishedPeers.hs:66-100`). The comment at `Governor.hs:719-727` records
why the targets must be read before the local roots (the invariant that root peers do not exceed the known
target is false at start-up, when the targets are still zero).

If nothing is applicable the loop sleeps until the earliest wake-up time of the guards or the next event
(`evalGuardedDecisions`, `Governor.hs:642-664`; a timeout gives `wakeupDecision`, 730-737, which only reseeds
the generator).

A *job* is an asynchronous action (connect, activate, deactivate, close, request peers, share peers) that ends
in a `Completion`: a function from the *then-current* state to a new `Decision` (`Types.hs:1613-1644`). The
completion, not the decision that started the job, applies the result, so a job that finished after the world
changed (the peer was forgotten, the targets moved) detects that and aborts (for example
`ActivePeers.hs:495-558`, "TracePromoteWarmAborted").

### 1.5 Targets, the sanity rule, and the defaults

Seven targets (`Types.hs:189-233`): root, known, established, active, and the same three for big ledger peers.
They are independent: the big-ledger targets are *in addition* to the others (`Types.hs:193-226`). The root
target is one-sided: the governor asks for more public roots when below it, and never shrinks the set to meet it
(`Types.hs:181-183`, `RootPeers.hs:32-100`). The targets are sane when `0 ≤ active ≤ established ≤ known`, `0 ≤
root ≤ known`, the same chain for big ledger peers, and hard caps of 100 active, 1000 established and 10000
known for each of the two kinds (`Types.hs:247-265`); an insane update is ignored (`Monitor.hs:68-69`).

Defaults used by cardano-node 11.1.3:

| Target | Relay | Block producer | Sync (Genesis syncing) |
|---|---|---|---|
| root | 60 | 100 | 0 |
| known | 150 | 100 | 150 |
| established | 30 | 30 | 10 |
| active | 20 | 20 | 5 |
| known big ledger | 15 | 15 | 100 |
| established big ledger | 10 | 10 | 40 |
| active big ledger | 5 | 5 | 30 |

Sources: deadline targets `ON/Diffusion/Configuration.hs:61-72` (the relay or producer split at 66-67), chosen
by `hasProtocolFile` at `CN/Configuration/POM.hs:822-823`; sync targets
`CD/Diffusion/Configuration.hs:51-60`; each overridable from `config.json` (`TargetNumberOfRootPeers` ... and
`SyncTargetNumberOfRootPeers` ..., `POM.hs:378-391`), and validated with `sanePeerSelectionTargets`
(`POM.hs:938-943`). Other defaults on the same path: peer sharing off for a producer and on for a relay
(`ON/Diffusion/Configuration.hs:85-88`; in the node, `POM.hs:899-905`, keyed on `hasProtocolFile`, overridable
with `PeerSharing`); `ConsensusMode` defaults to `PraosMode` (`CDA/ConsensusMode.hs:28-29`);
`MinBigLedgerPeersForTrustedState` 5 (`CD/Diffusion/Configuration.hs:74`, `POM.hs:394`); default number of
bootstrap peers 30 (`CD/Diffusion/Configuration.hs:46`); accepted connections hard limit 512, soft limit 384,
delay 5 s (`ON/Diffusion/Configuration.hs:76-81`); block fetch concurrency 1 in both modes
(`CD/Diffusion/Configuration.hs:88-89`).

### 1.6 The transitions

Every decision below picks peers with a *pick policy* (section 1.8), never more than the shortfall, and marks
the picked peers in progress so that the next pass does not pick them again.

**Cold to warm** (`G/EstablishedPeers.hs`). `belowTarget` is local roots first (`belowTargetLocal`, 106-239),
then the rest (`belowTargetOther`, 265-311); a third, for big ledger peers, is listed first
(`belowTargetBigLedgerPeers`, 342). Local roots are promoted whenever a group has fewer established members than
its *warm valency*, even if that puts the node above its established target; the demotion side never picks a
local root (`EstablishedPeers.hs:48-63`). For the others the shortfall is `targetNumberOfEstablishedPeers -
established - connects in progress` (320-323), the candidates are `availableToConnect` minus established, in
progress and local roots (316-319), connections are opened as `InitiatorAndResponder` outbound (297-302). The job
`jobPromoteColdPeer` (470-640) calls `establishPeerConnection`; on success the peer joins the established set,
its `knownPeerSharing` is recorded from the handshake, it is marked successfully connected and its tepid flag
cleared, and a peer-share activation time of `now + policyPeerShareActivationDelay` (300 s) is set when the
peer shares peers (570-640); on failure `KnownPeers.reportFailures` increments the fail count and schedules a
retry at `5 · 2^min(failCount - 1, 5)` seconds plus a uniform fuzz in (-2, 2): 5, 10, 20, 40, 80, 160
(`EstablishedPeers.hs:461-466`, `521-532`); a peer whose fail count reaches `policyMaxConnectionRetries` (5) is
forgotten unless it is a local root or a configured/bootstrap root (`State/KnownPeers.hs:452-520`,
`Policies.hs:122`).

**Warm to cold** (`EstablishedPeers.aboveTarget`, 644-880; `jobDemoteEstablishedPeer`, 884-1013). Applies when
established exceeds the target; only warm peers that are not local roots, not being promoted, not already
demoting (`numPeersToDemote` at 700-745). The job calls `closePeerConnection` and then waits for the connection
manager to report the peer cold, with a 600 s timeout (`c_PEER_DEMOTION_TIMEOUT`, `Types.hs:268-274`); a
timeout leaves the peer in place and is a logic error in the traces.

**Warm to hot** (`G/ActivePeers.hs`). `belowTarget` is big ledger, then local, then others (80-82). Local: each
group with fewer active members than its *hot valency* promotes the shortfall from the group's *ready* warm
members (`belowTargetLocal`, 201-324). Others: shortfall `targetNumberOfActivePeers - active - promotions in
progress`, candidates are ready warm peers minus local roots and big ledger peers (`belowTargetOther`, 326-424).
The job (`jobPromoteWarmPeer`, 427-616) calls `activatePeerConnection`. On success the peer joins the active
set and a fail-count clear time of `now + policyClearFailCountDelay` (120 s) is set (576-577); on failure the
peer is **removed from the established set**, its fail count is incremented, and its next connect time is
`errorDelay + fuzz(-2, 2)`, with `errorDelay = repromoteErrorDelay = 10 s` (`ActivePeers.hs:496-509`,
`Policies.hs:82`). The job also sleeps one second first so a failing promotion cannot spin (476).

**Hot to warm** (`ActivePeers.aboveTarget`, 625-957; `jobDemoteActivePeer`, 959-1078). Same three families, but
local roots are demoted only when a *group* is above its hot valency (756-857); the general case never picks a
local root (859-957). The job calls `deactivatePeerConnection`, sets the peer's tepid flag (`setTepidFlag`),
and has the same 600 s wait on failure as the warm demotion.

**Forget** (`KnownPeers.aboveTarget`, `G/KnownPeers.hs:451-580`): above the known target, cold peers that are not
established, not local roots, not in progress, not big ledger peers, and (when the number of roots is at or
below the root target) not public roots, are forgotten, picked by the forget policy.

**Discover.** Two sources. `RootPeers.belowTarget` asks the actions for public roots (ledger peers or configured
roots, bootstrap peers in the sensitive state; `CD/PeerSelection/PeerSelectionActions.hs:43-109`,
`PS/PeerSelectionActions.hs:132-184`) with an exponential backoff of `2^min(n, 8)` seconds on failure and on
"no progress", the time to live of the answer otherwise (`RootPeers.hs:139-143`, `206-209`; the same shape for
big ledger peers, `BigLedgerPeers.hs`); and `KnownPeers.belowTarget`, when below the known target and
PeerSharing is enabled, flips a coin between *inbound peers* (the peers that connected to us and negotiated a
duplex connection, advertised as peer-sharing willing, at most `maxInboundPeers = 10` at once, not more often
than every `inboundPeersRetryDelay = 60 s`) and *peer share requests* (section 1.13), taking inbound peers when
the coin says so or when there is nobody to ask (`G/KnownPeers.hs:54-228`, `Policies.hs:62-75`).

**Asynchronous demotions** (`Monitor.connections`, `G/Monitor.hs:111-320`). The governor never polls a
connection; `monitorPeerConnection` returns each established peer's status, and a change not caused by the
governor itself is an asynchronous demotion: hot to warm (a hot protocol ended), warm or hot to cooling, or
cooling to cold (257-319). Demotions to cold increment the fail count (`reportFailures`, 186-198) with the delay
the exit policy gave plus a fuzz, and a peer whose fail count passes the retry limit is forgotten. The fuzz on
reconnect delays is uniform in (0.1, 10) s for peers that stayed warm and (0.1, 4) s for peers that went cold
(`Monitor.hs:145-161`). A peer demoted to cooling stays in `inProgressDemoteToCold` so it cannot be promoted
until it is cold (213-216).

### 1.7 Local roots changing, targets changing

`Monitor.localRoots` (`G/Monitor.hs:329-425`): on a change of the configured groups, insert the new peers into
the known set (not as ledger peers), remove them from the public roots (the two are disjoint), and demote to
warm any *active* removed root so a new one can take its place. `Monitor.targetPeers` (50-95): replace the
targets (if sane), clamp the local roots to the new known target, and keep local and big ledger peers disjoint.
The Cardano versions add the sensitive-state conditions (section 1.15).

### 1.8 Pick policies

`PeerSelectionPolicy` (`Types.hs:141-168`) holds seven pick functions and the timing constants. The one the
node uses is `simplePeerSelectionPolicy` (`ON/Diffusion/Policies.hs:99-124`):

| Pick | Rule |
|---|---|
| known peers for peer share, cold to promote, inbound | uniformly random: a random `Word32` per candidate, the smallest first (`simplePromotionPolicy` and `addRand`) |
| warm to promote | current-pin `warmPromotionPolicy`: quarter the unsigned draw for non-tepid peers; a recently demoted tepid peer retains the full draw and is less likely to return immediately (#149) |
| hot to demote | the lowest score first, where the score is `upstreamyness + fetchynessBlocks`, ties broken by the slot at which the peer joined the metric board, then by the random number (`hotDemotionPolicy`, 127-147) |
| warm to demote | random, with a peer that has the tepid flag *twice as likely* to be picked (its random number halved, 151-160, 192-199) |
| cold to forget | random, a peer with failures more likely to be picked (its random number divided by `failCount + 1`, 164-172, 184-190) |

Constants: `policyFindPublicRootTimeout` 5 s, `policyMaxInProgressPeerShareReqs` 2, `policyPeerShareRetryTime`
900 s, `policyPeerShareBatchWaitTime` 3 s, `policyPeerShareOverallTimeout` 10 s,
`policyPeerShareActivationDelay` 300 s, `policyMaxConnectionRetries` 5, `policyClearFailCountDelay` 120 s
(`Policies.hs:116-123`). Timeouts: `deactivateTimeout` 300 s, `closeConnectionTimeout` 120 s,
`churnEstablishConnectionTimeout` 60 s (`Policies.hs:31`, `41`, `49`).

### 1.9 Peer metrics: upstreamness and fetchy-ness

`PeerMetrics` (`PS/PeerMetric.hs:85-124`) keeps two boards, each ordered by slot, of the **earliest** arrival for
that slot: `headerMetrics` (which peer sent the header for slot s first, and when) and `fetchedMetrics` (which
peer delivered the block first, with its size). A board holds `maxEntriesToTrack = 180` slots, "one hour of
blocks on mainnet" (`Policies.hs:52-60`); a new slot beyond that evicts the oldest, and an entry is replaced
only by an earlier arrival (`metricsTracer`, 365-397). A *peer registry* records the slot at which a peer first
appeared (`insertPeer`, 262-306; `joinedPeerMetricAt`, 410-422).

- **Upstreamness** (`upstreamyness`, 425-478): for each peer, the number of slots in the board for which it was
  first to present the header. "A peer that is never first is likely downstream of us."
- **Fetchy-ness** (`fetchynessBlocks`, 534-590): the same count for blocks (and `fetchynessBytes` for bytes).
- The correction for peers that joined late (`adjustAvg`, 593-618) **is disabled in this release**: it is
  defined as `adjustAvg _ _ _ _ _ = 0` with the real formula commented out. The port must not implement the
  commented formula.

How the feedback reaches the governor: only the hot-demotion pick reads the metrics (`hotDemotionPolicy`,
section 1.8). A churn step lowers the active target (section 1.10); the governor then demotes the peers with the
lowest `upstreamyness + fetchynessBlocks`. The reporters are handed to consensus as `ReportPeerMetrics`
(`PeerMetric/Type.hs:29-37`; `reportHeader` per `(slot, time)` from ChainSync, `reportFetch` per `(size, slot,
time)` from BlockFetch); the wiring lives in ouroboros-consensus, which was not read.

### 1.10 The churn governor

The Cardano churn governor (`CD/PeerSelection/Churn.hs`, run next to the governor by the diffusion layer,
`ON/Diffusion.hs:700-709`) does not change peers. It changes the **targets** in a TVar the governor monitors
(`Monitor.targetPeers`), waits for the counters to reach them or a timeout, and moves on.

*Regimes* (`Churn.hs:89-115`): `ChurnDefault` (fetch mode deadline, or Genesis), `ChurnPraosSync` (Praos, fetch
mode bulk sync, no bootstrap peers) and `ChurnBootstrapPraosSync` (Praos, bulk sync, bootstrap peers in use).
Targets come from the deadline set, or from the sync set when `(GenesisMode, TooOld)` (98-107).

*Start* (`Churn.hs:167-214`): wait 3 s; initial targets, in Praos mode only (`Churn.hs:181-195`): in `ChurnDefault` and `ChurnPraosSync` the root target is
set to 0 (a head start for local roots and big ledger peers); in `ChurnBootstrapPraosSync` the root target is left
alone and the two active targets are cut to `min(max(1, localHot) + 1, active)` and `min(1, activeBigLedger)`;
in Genesis mode the targets are the base ones; then the first fuzzy delay.

*One round* (`churnLoop`, 561-654), twelve steps in this order, each setting the targets and blocking until the
counters meet them or a timeout fires:

| # | Step | Timeout |
|---|---|---|
| 1 | decrease active big ledger peers | `deactivateTimeout` 300 s |
| 2 | increase active big ledger peers | 60 s |
| 3 | decrease established big ledger peers | 1 + `closeConnectionTimeout` = 121 s |
| 4 | decrease known big ledger peers | 60 s |
| 5 | increase known big ledger peers | 2 · `pcaPeerRequestTimeout` + 60 s |
| 6 | increase established big ledger peers | `churnEstablishConnectionTimeout` 60 s |
| 7 | decrease active peers | 300 s |
| 8 | increase active peers | 60 s |
| 9 | decrease established peers | 121 s |
| 10 | decrease known peers (and root peers) | 60 s |
| 11 | increase known peers | 2 · request timeout + 60 s |
| 12 | increase established peers | 60 s |

*The amount* of a decrease is `decrease n = max 0 (n - max 1 (n div 5))`: "replace 20% or at least one"
(`Churn.hs:692-697`); the established and known decreases subtract the *larger* of that and the amount the step
before it achieved (`decreaseWithMin minDecrease`, 697), taken on the part of the target above the next lower
set (established minus active, known minus established; 364-385, 463-480). In `ChurnDefault`, active decreases
by `decrease active`. In the sync regimes the active target is instead `min(max 1 localHot, active - 1)` while
decreased and `min(max 1 localHot + 1, active)` while increased (`Churn.hs:285-312`), and the active big ledger
target is `min 1 base` in both directions (396-428); established is clamped to `active + 1`/`active` in
`ChurnBootstrapPraosSync` (322-385). The "increase" steps restore the base target.

*Intervals.* After a round the churn governor sleeps `interval + U(0, fuzz) - elapsed` (`fuzzyDelay'`,
`Churn.hs:670-676`). In fetch mode deadline, or in Genesis mode, the interval is
`defaultDeadlineChurnInterval = 3300 s` with fuzz 600 s (`longDelay`, 679-680); in bulk sync with Praos it is
`defaultBulkChurnInterval = 900 s` with fuzz 60 s (`shortDelay`, 682-683; `ON/Diffusion/Configuration.hs:90-94`;
wired at `CN/Run.hs:926-927`). The fetch mode itself comes from consensus (`readFetchMode`,
`Churn.hs:219`); the rule that sets it is outside the files read.

*Why the order matters.* Active peers are decreased first so that the governor demotes the least useful ones
(by the metrics) and then increased so that it promotes others; establishing new cold peers happens last, after
the known set was refreshed. The doc comment of the generic churn governor: "at every churn interval decrease
active peers for a short while, so that we can pick new ones; then we churn non-active peers"
(`Churn.hs:117-123`).

### 1.11 Roots and the topology file

Local roots (`topology.json` `localRoots`, parsed in `ouroboros-network/orphan-instances/.../OrphanInstances.hs`
and the Cardano instance): a list of groups with `accessPoints`, `hotValency` (or `valency`; one of the two is
required, lines 146-148), `warmValency` (default = hot, 158), `advertise` (default false, 150), `behindFirewall`
(default false, 151; true makes the provenance `Inbound`: the peer is expected to connect to us), `diffusionMode`
(`InitiatorOnly` or `InitiatorAndResponder`, 159), and in Cardano `trustable` (default false,
`CD/orphan-instances/.../OrphanInstances.hs:119`). Top level: `publicRoots` (required), `useLedgerAfterSlot`
(number: greater than 0 is `After slot`, 0 is `Always`, below 0 is off; `OrphanInstances.hs:249-256`),
`peerSnapshotFile` (212-215), and in Cardano `bootstrapPeers` (a list, or null for none;
`CD/orphan-instances/.../OrphanInstances.hs:120`). The node reads the file in
`CN/Configuration/TopologyP2P.hs:56-108` and refuses a topology with an empty `bootstrapPeers` and no trustable
local root (`isValidTrustedPeerConfiguration`, `CD/Diffusion/Topology.hs:19-45`; the message says the node would
not be able to make progress in bootstrap mode), turns bootstrap peers off in Genesis mode (139-140 and 124-131
of `TopologyP2P.hs`), and warns when ledger peers are used by a producer or a relay without a snapshot in
Genesis mode.

Trustable local roots matter in the sensitive state (section 1.15): `clampToTrustable` keeps only trustable
local roots there (`CD/PeerSelection/Governor/Monitor.hs:166-169` and `249-252`). Scarab's `Topology.scala` already parses
every one of these fields (and refuses the same malformed files); what it does not do is act on valencies,
`advertise`, `behindFirewall`, `diffusionMode`, `trustable` and `useLedgerAfterSlot`.

### 1.12 Ledger peers and big ledger peers

A *ledger peer* is a relay published in the registration of a stake pool. The consensus interface hands the
governor `[(PoolStake, NonEmpty RelayAccessPoint)]` and the latest slot (`LedgerPeersConsensusInterface`,
`API/LedgerPeers/Type.hs:527-534`). `getLedgerPeers` returns the relays only if the latest slot is at or after
the `useLedgerAfterSlot` slot (`PS/LedgerPeers.hs:97-112`).

*The draw* (`pickPeers`, `PS/LedgerPeers.hs:158-211`): build a map keyed by the accumulated relative stake; draw
a uniform `Word64`, scale to [0, 1] (or [0, 0.9] for big ledger peers), take the entry at or above it
(`Map.lookupGE`), then a uniformly random relay of that pool. Two weightings
(`recomputeRelativeStake`, `API/LedgerPeers/Utils.hs:62-92`): ordinary ledger peers are weighted by the
**square root of the pool's relative stake** (87), renormalised, to flatten the distribution; big ledger peers
are weighted by the stake itself. *Big ledger peers* are the largest pools that together hold 90% of the stake
(`bigLedgerPeerQuota = 0.9`, `Utils.hs:28`; `accumulateBigLedgerStake`, 30-57: sort by stake descending,
accumulate, keep entries until the accumulated stake passes 0.9, including the pool that crosses it). The
ledger peer list is cached for 1847 s, or 30 s while it is empty (`PS/LedgerPeers.hs:213-223`). When a peer
snapshot file is given and is not older than the ledger's slot, the snapshot is used for big ledger peers
instead of the ledger (`stakeMapWithSlotOverSource`, 403-445); in Genesis mode a `jobVerifyPeerSnapshot` checks
it against the ledger (`CD/PeerSelection/Governor/Monitor.hs:684-708`).

Big ledger peers are a separate population in every set (the `*BigLedgerPeers` targets, counters and
transitions), excluded from the ordinary promotion and forgetting rules, because they are the peers a node uses
to sync when it cannot trust a small set of bootstrap peers (eclipse resistance: pick from where the stake is).

### 1.13 PeerSharing

*Wire.* Mini-protocol 10 (`CD/NodeToNode.hs:487`; `SPEC/miniprotocols.tex:1612-1620`): `StIdle` (client agency),
`StBusy` (server agency), `StDone`. Messages `MsgShareRequest amount` (`[0, word8]`), `MsgSharePeers
[address]` (`[1, [* address]]`), `MsgDone` (`[2]`); an address is `[0, word32, port]` for IPv4 and `[1, word32 ×
4, port]` for IPv6 (`cardano-diffusion/protocols/cddl/specs/peer-sharing-v14.cddl`; codec
`ouroboros-network/protocols/lib/.../PeerSharing/Codec.hs:46-70`; type `Type.hs:26-100`). Limits: 5760 bytes in
each state, a 60 s timeout in `StBusy` (`SPEC/miniprotocols.tex:1662-1700`). It is a protocol error to send more
peers than requested (`Type.hs:80-82`, enforced by the client at `ON/PeerSharing.hs:126-131`). The server should
share only peers with which it has or recently had a successful session. Negotiation: the handshake version
data carries a `PeerSharing` flag, and the connection's flag is the **conjunction** of both sides' flags
(`API/PeerSharing.hs:19-40`, the `Semigroup` instance at 29); Scarab's `Handshake.Data` already has `peerSharing: Boolean` and combines it the
same way (`Handshake.scala:50`, `134-135`), but always proposes it false (`NodeToNode.scala:59`).

*Server policy* (`ON/PeerSharing.hs:135-145` and `203-240`, `PS/State/KnownPeers.hs:580-592`): the shareable set is the known
peers with `DoAdvertisePeer`, a successful connection at some time, and a fail count of 0. A response is a
deterministic-for-a-while sample: the set is sorted by `hashWithSalt salt peer` and truncated to
`min(amount, ps_POLICY_PEER_SHARE_MAX_PEERS = 10)`, and the salt changes only every
`ps_POLICY_PEER_SHARE_STICKY_TIME = 823 s` so that the same asker gets the same answer and cannot map the
network quickly (`PeerSharing.hs:168-174`, `SPEC/miniprotocols.tex:1727-1737`).

*Client policy* (`G/KnownPeers.hs:95-228`, job 243-450): when below the known target and fewer than
`policyMaxInProgressPeerShareReqs = 2` requests are in flight, pick from established peers that allow
peer sharing, were not asked within `policyPeerShareRetryTime = 900 s` and are past their activation delay, ask
each for `min 255 (max 8 (objective div requests))` peers (the shortfall split between the askers, at least 8),
in two stages: wait `policyPeerShareBatchWaitTime = 3 s` for all, then the rest of the
`policyPeerShareOverallTimeout = 10 s` for the stragglers; results are filtered of peers already known and of
big ledger peers and inserted as cold, non-root known peers. `PeerSharingNotRegisteredYet` (the mini-protocol
has not started on that connection) is treated as no answer (`Types.hs` and `PS/PeerSelectionActions.hs:104-121`).

*Where it is on.* Off for block producers, on for relays by default (section 1.5). In a topology file
`advertise` on a local or public root says whether we may tell others about it.

### 1.14 How the governor drives the connection manager and the protocols

The governor sees the connection manager through `PeerStateActions` (`Types.hs:396-435`):
`establishPeerConnection`, `activatePeerConnection`, `deactivatePeerConnection`, `closePeerConnection`,
`monitorPeerConnection` (non-blocking, in STM) and `errorDelay`. The implementation is
`withPeerStateActions` (`PS/PeerStateActions.hs:590-`):

- *Establish* (777-905): `acquireOutboundConnection` from the connection manager with the diffusion mode and
  provenance (791), which may return a fresh connection or an existing one (the inbound duplex connection of the
  same peer); then start the *warm* and the *established* mini-protocols only (828-829) and set the status to
  `PeerWarm`.
- *Activate* (952-982): start the *hot* protocols (979), set `PeerHot`; remember the time for the
  hot-duration trace.
- *Deactivate* (986-1046): stop the hot protocols (a control variable tells them to terminate), wait up to
  `spsDeactivateTimeout` (300 s) for them to finish, back to `PeerWarm`.
- *Close* (1048-1110): terminate everything, `releaseOutboundConnection`, status `PeerCooling`, then `PeerCold`
  when the connection manager says the connection is gone.
- *Monitor* (908-950, `peerMonitoringLoop` 636-780): a first-to-finish over all mini-protocol results; an error
  in any temperature moves the peer to `PeerCooling` (the multiplexer closes the bearer); a clean end of a hot
  protocol deactivates the peer; the result is what `Monitor.connections` reads.

The specification's statement of the contract (`SPEC/connection-manager.tex:414-452`): the governor needs two
interactions with connections, *acquire* (cold to warm: a fresh or existing connection; the client side of the
warm protocols starts) and *release* (demotion to cold: client-side protocols end), and "does not require
exclusive access to the TCP bearer", only to its half of the multiplexer. That is exactly Scarab's `Outbound`
plus `ConnectionManager` (G2), which is why the governor sits above them without changing them.

### 1.15 Bootstrap peers and Genesis: what G1 needs of them

*Bootstrap peers and the sensitive state.* With `bootstrapPeers` configured, the node is in a *sensitive state*
when its ledger is `TooOld`: only trustable peers (bootstrap peers and trustable local roots) may be used
(`requiresBootstrapPeers`, `CDA/PeerSelection/Bootstrap.hs:44-46`). The Praos-mode monitor
`monitorLedgerStateJudgement` (`CD/PeerSelection/Governor/Monitor.hs:474-595`): on the flip to `TooOld` set all
targets to 0 and drop the local roots, start a 15-minute timer (`governor_BOOTSTRAP_PEERS_TIMEOUT = 15 * 60`,
line 65); `waitForSystemToQuiesce` (613-682) sets `hasOnlyBootstrapPeers` when every non-trustable peer is gone,
which releases `monitorTargets` and `monitorLocalRoots` (they require `isNodeAbleToMakeProgress`,
`CDA/PeerSelection/Bootstrap.hs:53-55`; `CD/PeerSelection/Governor/Monitor.hs:145` and `236`); if the node does not converge to a clean state within the 15
minutes the governor throws `BootstrapPeersCriticalTimeoutError` and the node stops (`Types.hs:1863-1869`). On
the flip to `YoungEnough` the non-established bootstrap peers are forgotten and the public-roots backoff is
reset (545-595), so ledger peers can take over. `requestPublicRootPeersImpl`
(`CD/PeerSelection/PeerSelectionActions.hs`) returns bootstrap peers when in the sensitive state and ledger
peers or configured public roots otherwise. Consequence for Scarab: the official Preview topology (bootstrap
peers, no local roots; J1 evidence in [compatibility.md](../../compatibility.md)) can only be run faithfully with
this state machine, so it is in G1.1.

*Progress is switched off in the sensitive state.* Cardano passes `enableProgressMakingActions = not
(requiresBootstrapPeers ...)` (`CD/PeerSelection/Governor/Types.hs:170-172`), which disables
`BigLedgerPeers.belowTarget`, `KnownPeers.belowTarget` (peer sharing and inbound peers) and the below-target
promotion of big ledger peers to warm and hot (the list is in the doc comment of `enableProgressMakingActions`, `G/Types.hs:522-533`; the guards are at
`Governor.hs:697-698` and `704-705` and `ActivePeers.hs:161`): a node waiting for the bootstrap peers
does not discover or connect to anything else. After every pass the governor also publishes an `OutboundConnectionsState` to consensus
(`updateWithState`, `CD/PeerSelection/Governor/Types.hs:150-159`; the function `outboundConnectionsState`,
57-109): `TrustedStateWithExternalPeers` when the node is connected only to trusted peers (in the
`LocalRootsOnly` association mode, `Types.hs:177-203`: no ledger peers, no peer sharing, no bootstrap peers in
use, only trustable local roots; in bootstrap mode, only trustable local roots and bootstrap peers with at least
one bootstrap peer active; in Genesis mode, at least `minNumberOfBigLedgerPeers` active big ledger peers), and
`UntrustedState` otherwise (and always in Praos mode with public roots). Consensus uses it to decide whether it
may consider itself caught up. G1.1 computes and exports it as a gauge and log line; #139 exposes the typed
trusted-state output for a future consumer. Full Genesis consensus and that consumer remain G3.

*What a `LedgerStateJudgement` is.* `TooOld` or `YoungEnough`, supplied by the Genesis State Machine. Scarab
ports the pinned reference state machine; see section 7g and the source citations there.

*Genesis mode.* `ConsensusMode` is `PraosMode` (default) or `GenesisMode`
(`POM.hs:396`, `CDA/ConsensusMode.hs:28-29`). In Genesis mode the sync targets apply while `TooOld`
(`Churn.hs:98-107`, `CD/PeerSelection/Governor/Monitor.hs:118-143`), bootstrap peers are not used (`TopologyP2P.hs:104-108`), the node
needs `MinBigLedgerPeersForTrustedState` (5) active big ledger peers before consensus is told the state is
trusted (`CD/Diffusion/Configuration.hs:62-74`), and the big-ledger peer snapshot file is verified against the
ledger when the state flips to `TooOld` (`CD/PeerSelection/Governor/Monitor.hs:515-531`, `684-708`). The density comparison between candidate chains is consensus, not the
governor: G3.

### 1.16 What the specification chapters say

The ouroboros-network repository's `docs/network-spec` ("Shelley Networking Protocol", the document the issue's
pointer asks about) has **no** chapter on peer selection: its chapter "Peer Discovery and Peer Selection" is a
hidden work-in-progress stub (`SPEC/rest.tex:2-9`). What it does contain that bears on G1: the outbound governor
in the connection-manager chapter (`connection-manager.tex:414-452`), the PeerSharing mini-protocol with its
client and server implementation notes (`miniprotocols.tex:1612-1773`), and the time and size limits
(`limits.tex`). The *design* document (`DESIGN/`) has the rationale: the decentralisation design
(`network-design.tex:1710-1964`), data diffusion targets (2965-3005), the peer-with-peer principle (3270-3404).
The authoritative statement of the algorithm is therefore the module header of `Governor.hs` (lines 80-448),
which is where the numbers above come from; this document cites the code, as ADR 0001 prescribes.
