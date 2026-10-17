# Peer selection: the P2P governor (G1)

**Status**: G1.1 and G1.2 are built (2026-10-17, sections 7a and 7b); the other stages in section 7 are not scheduled. Tracked in
[#31](https://github.com/KD5UJC-XYZ/scarab/issues/31) (roadmap G1). Written against `main` at 6ce5bb77 and the
reference sources named in section 1.1, which were read in full for the governor, the churn governor, the peer
metrics, ledger peers, PeerSharing and the Cardano configuration; every claim about the reference carries its
file and line. The decisions in section 0 are the owner's to take before G1.1 starts.

The owner's priority for this item: a peer controller with the feedback mechanism that manages hot, warm and
cold peers per the ouroboros-network specification, as `cardano-node` does. "Feedback" is the pairing this
document is built around: the governor decides from the counts of peers in each temperature against targets
(feedback from the connections), and the churn governor and the peer metrics feed back into the governor's
targets and choices (feedback from the chain).

## 0. Decisions for the owner (my recommendation first)

| # | Decision | Recommendation |
|---|---|---|
| D1 | How closely the governor mirrors the reference | Mirror what is observable: the three temperatures and the nested known, established and active sets, the seven targets and their defaults, the order in which the decisions are tried, the backoffs, the churn steps and intervals, the PeerSharing wire and policy, the counters and their metric names. Take the internal structure from Scarab's own style ([ADR 0008](../adr/0008-networking-as-implemented.md): "peer governance (G1) follows the Haskell behaviour where it is observable"). The one structural departure, with its reason, is D2. |
| D2 | State representation | One `Map[A, PeerRecord]` whose status is an enum with the transient states in it (`Connecting`, `Activating`, `Deactivating`, `Closing`), instead of the reference's separate `inProgress*` sets next to the established and active sets (`Governor/Types.hs:673-681`). The reference's sets and counters stay as derived views (`PeerSelectionView`, `Types.hs:851`), so the metrics and the tests compare against the reference unchanged. The reason: several of the reference's invariants are "this in-progress set is a subset of that set" (`assertPeerSelectionState`, `Types.hs:1328`); with the status in the record they cannot be violated by construction ([AGENTS.md](../../AGENTS.md): lean on the compiler). Needs the written reason ADR 0001 asks for: this paragraph, and an ADR when G1.1 lands. |
| D3 | What `--peer` means once a governor exists | Today the node follows every `--peer` (one supervised session each, `Main.scala` 700-740). Under the governor `--peer` becomes one local-root group with `hotValency = warmValency = n`, which keeps today's behaviour (all of them are hot) and makes it the reference's behaviour. A topology's `hotValency` and `warmValency`, which `Topology.scala` reads and ignores, are honoured for the first time. This is a behaviour change visible to anyone who gave a topology with `valency` smaller than its access points. |
| D4 | Defaults when no targets are configured | The reference's defaults, picked as `cardano-node` picks them: the block-producer set when `--forge` is given (its `hasProtocolFile`), else the relay set (section 1.5). Targets are overridable by the `config.json` keys the reference reads (`TargetNumberOfRootPeers` and the rest). PeerSharing: off for a producer, on for a relay (section 1.13). |
| D5 | Hot peers and the ledger work | A hot peer is a peer with a running `Follower.session`. The reference's default of 20 active peers on a relay means 20 header-validating sessions. Header-first validation shares verdicts through `VerificationCache`, but the cost per extra hot peer on Preview and mainnet is not measured. Recommendation: G1.1 ships the reference defaults and the stage's evidence includes the measurement; if it is too dear, the stage lowers the Scarab default for `targetNumberOfActivePeers` (a configuration difference, recorded in [compatibility.md](../compatibility.md)), not the mechanism. |
| D6 | Where the Genesis boundary is | G1 ends at hooks: the `ConsensusMode` and `LedgerStateJudgement` inputs, the sync targets, the bootstrap-peers sensitive state. The density and fork-comparison rules (GDD, LoE, LoP) are G3 ([#33](https://github.com/KD5UJC-XYZ/scarab/issues/33)). The bootstrap-peers state is in G1.1, not G1.5, because the official Preview topology depends on it (section 1.15). |
| D7 | How the "feedback" metrics reach the governor | A `PeerMetrics` component fed by two new observations from the `Peer` (the first header of each slot, the first block of each slot, each with its arrival time and its peer) and read by the hot-demotion pick (section 1.9). The reference feeds the same two events from consensus through `ReportPeerMetrics` (`PeerMetric/Type.hs:29-37`). |
| D8 | Test oracle | The reference's governor tests are property tests over a simulated environment (`GovernorMockEnvironment`, `Test/Cardano/Network/PeerSelection/MockEnvironment.hs:139-163`) run by `IOSim`. We port the environment and the properties by name (section 6) on `TestControl`, as `FollowerSuite` already runs on it. The Haskell tests themselves are not run (the sources are not in the reference image). |
| D9 | What to do about SRV and DNS | Out of scope for G1: `Topology` refuses a DNS SRV access point with `UnsupportedTopology` today, and ledger relays that are SRV or multi-host names are skipped when drawing ledger peers. A host name that resolves to addresses is resolved once at the time of the connection attempt (section 4.6). Tracked in section 8. |
| D10 | ADR | One ADR at G1.1: "Outbound peer selection is a pure decision function over a status-carrying state" (D2), so the next sessions do not reopen it. |

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
([reference-image.md](../reference-image.md)) and correcting any citation that moved.

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
| known peers for peer share, cold to promote, warm to promote, inbound | uniformly random: a random `Word32` per candidate, the smallest first (`simplePromotionPolicy`, 174-182, `addRand`, 202-217) |
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
peers, no local roots; J1 evidence in [compatibility.md](../compatibility.md)) can only be run faithfully with
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
may consider itself caught up. Scarab has no consumer for it until G3; G1.1 computes it and exports it as a
gauge and a log line, so the output exists and is tested before anything depends on it.

*What a `LedgerStateJudgement` is.* `TooOld` or `YoungEnough`, a function of the age of the ledger tip, supplied
by consensus (the Genesis State Machine); the rule is in ouroboros-consensus, which was not read. Scarab needs a
rule for it (open question 2).

*Genesis mode, only enough to stage it.* `ConsensusMode` is `PraosMode` (default) or `GenesisMode`
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

## 2. Scarab today

| Piece | What it does | Where |
|---|---|---|
| `--peer host:port` (repeatable), `--topology` | The peer list is `--peer` entries followed by the topology's local roots, then bootstrap peers (only if neither names one: public roots), without repeats (`Topology.peers`). No peer is chosen, ranked, rotated or dropped. | `Main.scala` 290-301, `Topology.scala` |
| One supervised session per peer | `options.peers.parTraverse_(address => Follower.supervise(...))`. `supervise` reconnects after a `PeerError` with a fixed 2 s delay; one peer: fatal after 30 consecutive failures; several: for ever. A session that applied a block resets the count. | `Main.scala` 623-627 and 700-740, `Follower.scala` 385-414 |
| `Peer.nodeToNode` / `Peer.over` | A connection we open = handshake (version 14, initiator-only unless serving) + KeepAlive client + TxSubmission2 client + ChainSync and BlockFetch clients; `Peer.over` starts the pipelined producer. Observations: `Round`, `Fetched`, `Announced(tip)`. | `NodeToNode.scala` 40-103, `Peer.scala` 41-100, 181-240 |
| `Follower.session` | The hot-protocol run: intersect, headers, header gate, chain selection, fetch through the `BlockPool`, adoption. One candidate chain per session. | `Follower.scala` 85-134 |
| `BlockPool` | Claims by header hash across sessions, so a block is fetched from one peer (the reference's `filterNotAlreadyInFlightWithOtherPeers`); releases on session end (#124, #130). Does not choose peers. | `BlockPool.scala` |
| `StallWatchdog` | Logs and counts a stall (no block extended while a peer announced a longer tip); `--stall-exit-after` ends the node (#130). Its `announced` input is the highest tip any peer announced. | `StallWatchdog.scala` |
| `ConnectionManager` / `ConnectionState` | A table of connections by remote address and direction; limits `maxInbound` (`--max-inbound`, default 64) and `maxOutbound` (64, not exposed); typed refusals; release on any end. Lifecycle `Reserved`, `Unnegotiated`, `Established(flow)`, `Terminating`, `Terminated`. | `ConnectionManager.scala`, `ConnectionState.scala` |
| `Outbound.connect` | A dial through the manager; waits for the peer's own inbound duplex connection instead of dialling a second one (`reuseInbound`). | `Outbound.scala` |
| `InboundGovernor` | Accepts inbound connections; on a duplex one runs our clients and a follower session, so an inbound peer is a block source. | `InboundGovernor.scala` |
| Topology fields | All parsed; valencies, `advertise`, `behindFirewall`, `diffusionMode`, `trustable`, `useLedgerAfterSlot` and `peerSnapshotFile` are recorded and not acted on. `NodeConfig.unsupported` names `GenesisMode` and `PeerSharing` as not implemented. | `Topology.scala`, `NodeConfig.scala` 70-83 |
| Metrics and events | `Node.Stalled`, `Net`-area connection and inbound events; no `peerSelection_*` series: [running.md](../running.md) line 807 lists them as not exported "(the node has no peer selection governor)". | `log/NodeEvent.scala`, `metrics/` |
| PeerSharing | No mini-protocol 10, the handshake flag is always proposed false. | `Protocols` in `NetworkError.scala` |
| Ledger relays | Pool registrations keep `relays` as raw CBOR bytes in the ledger state; there is no decoder. | `CertState.scala` 86, `Tx.scala` 51 |

What this means for the design: the governor *replaces* the fan-out in `Main` and the fixed-delay retry in
`Follower.supervise` (for outbound peers: a peer that fails becomes a backed-off cold peer, section 1.6), and
sits *above* `ConnectionManager`, `Outbound`, `Peer`, `Follower.session` and `BlockPool`, which keep their
roles. Two gaps in the lower layers are the main code risk and are named in section 4.5: there is no *warm*
state (a connection is either not open or running everything), and a connection we open is initiator-only
(G2's known limit), while the reference's warm connection on a duplex bearer is what makes an inbound peer
useful as a known peer.

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
| `PeerSelectionActions.hs`, `RootPeersDNS*` | `RootProvider[F]` (topology, DNS, ledger, bootstrap) | `node` | DNS: resolve once (D9) |
| `Cardano/.../Governor/Monitor.hs`, `PeerSelectionState.hs` (`ExtraState`) | `Sensitivity` (the judgement and bootstrap machine) in `GovernorState` | `network` | G1.1 (bootstrap), G1.5 (Genesis hooks) |
| `Cardano.Node.Configuration.TopologyP2P` | `Topology` (exists) + validation of the trustable rule | `node` | already refuses the same files |
| `POM.hs` targets and defaults | `PeerSelectionConfig` read from `config.json` and flags | `node` | section 4.7 |
| `Tracing/PeerSelection/*` | `NodeEvent`/`EventKind` additions, `EventMetrics` | `node` | section 5 |
| `Test/.../PeerSelection.hs`, `MockEnvironment.hs` | `GovernorSuite`, `MockEnvironment` | `network` tests | section 6 |

## 4. The Scala design

### 4.1 Where the code lives

The decisions are pure, so they belong below `node`. The peer address is a type parameter `A` (the reference's
`peeraddr`), so the pure code is tested with `Int` or `String` peers and run with `SocketAddress[Host]`. A new
package `xyz.kd5ujc.scarab.network.selection` holds the state, the targets, the decision function, the policies,
the churn calculations and the metrics board. It depends on nothing effectful (not even cats-effect `IO`). The
effect loop, the actions and the providers are in `node` (they need `Peer`, `Follower.session`, the ledger and
the topology). The PeerSharing state machine and codec go in `protocol` next to `KeepAlive` and `ChainSyncWire`,
its client and server in `network` next to `KeepAliveClient`.

### 4.2 Typed state

```scala
// network/selection/PeerStatus.scala  (names are proposals)
enum Temperature { case Cold, Warm, Hot }

/** The status of one known peer. The transient states carry what the reference keeps in the inProgress* sets. */
enum Status[+C]:
  case Cold(next: Retry)                  // Retry = Now | At(time)   (availableToConnect / nextConnectTimes)
  case Connecting                         // inProgressPromoteCold
  case Warm(conn: C, readyAt: Instant)    // established, not active; readyAt = nextActivateTimes
  case Activating(conn: C)                // inProgressPromoteWarm
  case Hot(conn: C)                       // activePeers
  case Deactivating(conn: C)              // inProgressDemoteHot
  case Closing(conn: C)                   // inProgressDemoteWarm
  case Cooling(conn: C)                   // PeerCooling / inProgressDemoteToCold

final case class PeerRecord[+C](
    status: Status[C],
    source: Source,                       // Local(group) | Public | Ledger | BigLedger | Bootstrap | PeerShare | Inbound
    failCount: Int, tepid: Boolean, successfulConnection: Boolean,
    sharing: Option[PeerSharing], advertise: Advertise,
    peerShareAt: Option[Instant], clearFailAt: Option[Instant])
```

`Source` replaces the reference's separate `localRootPeers`/`publicRootPeers` membership sets
(`Types.hs:620-625`): a peer has one source (local roots win over public ones, as `Monitor.localRoots` enforces,
`Monitor.hs:381-386`), and big ledger is a source so it cannot be both ordinary and big. The sets the decisions
need are derived once per pass from the map (`PeerSelectionView`); the counters the metrics export are derived
from the same view, so that `numberOfWarmPeersPromotions` is `count(Activating)` exactly as the reference's
`viewWarmPeersPromotions`. The reference's `assertPeerSelectionState` conditions that remain (disjoint groups,
root peers within the known target, targets sane) are checked by the smart constructors and by a
`GovernorState.invariant` the property tests call after every step; a violation at run time is
`GovernorError.InvariantBroken`, which ends the governor like the reference's `abortGovernor`.

```scala
opaque type HotValency  = Int   // > 0, <= warm
opaque type WarmValency = Int   // >= hot, <= group size
final case class LocalRootGroup[A](hot: HotValency, warm: WarmValency, members: NonEmptySet[A], ...)

/** Seven targets; the only constructor is `Targets.validated`, which is `sanePeerSelectionTargets`. */
final case class Targets private (root: Int, known: Int, established: Int, active: Int,
                                  knownBig: Int, establishedBig: Int, activeBig: Int)
object Targets:
  def validated(...): Either[TargetsError, Targets]   // closed ADT: NotNested(set), AboveCap(set, cap), Negative(set)
  val relay: Targets; val blockProducer: Targets; val sync: Targets   // section 1.5
```

An insane target update from configuration is a `ConfigError` at start-up; from the churn governor it cannot
happen (churn builds targets from validated ones with the clamping the reference has), and the property test
checks that. (The reference silently ignores an insane update, `Monitor.hs:68-69`; we keep the same behaviour at
the boundary, where the update arrives as untyped numbers from the outside, and refuse earlier where we can.)

### 4.3 The decision function

```scala
object PeerSelection:
  /** Everything one pass of the reference's guardedDecisions reads. */
  final case class Input[A](now: Instant, inbound: Map[A, PeerSharing], policy: Policy, rng: Rng)

  /** The first applicable decision in the reference's order (section 1.4), or the time to wake up. */
  def decide[A: Ordering](st: GovernorState[A], in: Input[A]): Step[A]

  enum Step[A]:
    case Act(decision: Decision[A])
    case Idle(wakeAt: Option[Instant])      // Guarded's Maybe Time: min over the guards that failed only on time

  final case class Decision[A](state: GovernorState[A], jobs: List[Job[A]], traces: List[Trace[A]])

  /** A job's result: a function of the state at the time it finishes (the reference's Completion). */
  type Completion[A] = (GovernorState[A], Instant) => Decision[A]

  /** The blocking monitors, as events: the loop turns each into a state change through `apply`. */
  def apply[A: Ordering](st: GovernorState[A], ev: Event[A], in: Input[A]): Decision[A]
  enum Event[A]:
    case ConnectionsChanged(statuses: Map[A, PeerObservation])   // Monitor.connections
    case JobFinished(done: Completion[A])                        // Monitor.jobs
    case TargetsChanged(t: Targets)                              // Monitor.targetPeers
    case LocalRootsChanged(groups: List[LocalRootGroup[A]])      // Monitor.localRoots
    case JudgementChanged(j: LedgerStateJudgement)               // Cardano monitorLedgerStateJudgement
    case UseBootstrapChanged(b: UseBootstrapPeers)
```

`decide` is a left-to-right `orElse` over the families in the order of section 1.4; each family is a function
`GovernorState[A] => Option[Decision]` plus a `wakeAt`, and `Step.Idle` carries the minimum of the wake-ups of
those that were applicable except for time (the reference's `GuardedSkip (Just t)` cases, for example
`EstablishedPeers.hs:305-311`, `ActivePeers.hs:186-187`). Random picks take `Rng` explicitly and return the
advanced generator inside the new state (the reference's `stdGen` field), so a run is a pure function of its
seed and its event script. `Rng` is a small pure splittable generator (SplitMix), not `scala.util.Random`.

A pick is `pickPeers(candidates: Set[A], n: Int, score: A => Score, rng)`: a sort of the candidates by
`(score, random)`, taking `n`, as a fold over a set bounded by the target caps (at most 10,000 known peers,
`Types.hs:261`). There is no recursion over outside data in the decision function ([ADR 0011](../adr/0011-stack-safety.md)):
the families are a fixed list, the groups of local roots are folded, and the only unbounded inputs are the
peer-share response (at most 255 addresses by the wire) and the ledger relay list, which the draw consumes with
a fold or `tailRecM`, and a test runs on a small-stack thread with a ledger of 100,000 pools.

### 4.4 The effect loop

```scala
final class PeerGovernor[F[_]: Temporal] private (...):
  def run(using Stateful[F, GovernorState[A]], Ask[F, GovernorConfig], Raise[F, GovernorError],
                Tell[F, Trace[A]], Actions: PeerStateActions[F, A]): F[Nothing]
```

`run` is `tailRecM` over a small typed state `(GovernorState, nextDebugAt)`; each pass: derive the input; call
`decide`; on `Act` apply the decision (put the state in `Stateful`, trace, fork the jobs); on `Idle(wakeAt)`
wait for the first of *an event arrives* (a `Queue[F, Event]` fed by the job fibers, the connection monitor,
the targets variable and the local-roots provider) and *the timer* (`Temporal.sleep` to `wakeAt`, giving the
reference's `wakeupDecision`). Jobs are fibers started under a `Supervisor`, each of which ends by offering its
`Completion` to the queue, so a job that is cancelled at shutdown cannot leak a claim: the same rule `BlockPool`
already follows (issue 130). A job's failure is a value, not an exception: `Job.run: F[Completion]` handles its
own typed errors (`PeerError`, `ConnectionError`) and returns the failure completion the reference's `handler`
returns (for example `ActivePeers.hs:465-558`). The only errors that reach the loop's `Raise` are
`GovernorError.InvariantBroken`, `DemotionTimedOut(peer)` (the 600 s wait, `Types.hs:268-274`, which the
reference only traces and continues; we keep it a trace and make it an error only if the same peer repeats it)
and, from G1.1, `BootstrapPeersCriticalTimeout` (15 minutes, `Types.hs:1863-1869`). `IO` appears nowhere in
the governor; `Main` supplies the interpreters.

The churn governor is a second loop, `ChurnLoop.run`, with the same shape. Its pure part is the target
arithmetic of section 1.10: `Churn.regime`, `Churn.step(round: ChurnStep, regime, localHot, base, current):
Targets`, `decrease`, `decreaseWithMin`. It writes the targets to a `Ref[F, Targets]` that is read as the
`TargetsChanged` event, and waits for the `Counters` (published by the governor after every pass, as the
reference's `countersVar`) to meet them or for the step's timeout, using `Temporal.timeoutTo`. Both loops stop
with the node's `Resource`.

### 4.5 Connections and the warm state

`PeerStateActions[F, A]` has the reference's five operations:

```scala
trait PeerStateActions[F[_], A]:
  def establish(peer: A, mode: DiffusionMode, provenance: Provenance, big: IsBigLedgerPeer): F[Either[PeerError, WarmConnection[F]]]
  def activate(conn: WarmConnection[F], big: IsBigLedgerPeer): F[Either[PeerError, HotConnection[F]]]
  def deactivate(conn: HotConnection[F]): F[WarmConnection[F]]
  def close(conn: WarmConnection[F]): F[Unit]
  def observe(peer: A): F[PeerObservation]        // non-blocking; Monitor.connections reads it
```

with the interpreter in `node`: `establish` is `Outbound.connect` (the existing `ConnectionManager.outbound`
plus handshake) followed by KeepAlive only; `activate` starts TxSubmission2 and `Peer.over` and forks
`Follower.session` under the connection's scope; `deactivate` ends them (the producer fiber, the session,
TxSubmission2) and keeps the connection and KeepAlive; `close` releases the manager's resource.

**This is the main code risk of G1.1.** Today `NodeToNode.over` (via `clients`) starts KeepAlive and TxSubmission2
at once and creates the ChainSync and BlockFetch clients (idle until driven), and `Peer.over` starts the
pipelined producer when the `Peer` resource is acquired. Decision: split `NodeToNode.clients` into
`NodeToNode.warm` (handshake, multiplexer, KeepAlive; returns a `WarmConnection` that owns the multiplexer) and
`WarmConnection.activate: Resource[F, NodeToNodeConnection]` (TxSubmission2 client, ChainSync and BlockFetch
channels). `activate` is a **new entry point in `network`**; `Peer.over` and `Follower.session` stay in `node`
and run inside the `activate` resource, so releasing it is the deactivation. Existing callers
(`NodeToNode.over`, `InboundGovernor`) become `warm` followed by `activate`, with no change in behaviour, which
the existing suites (`PeerSuite`, `InboundGovernorSuite`, `NodeToNode` tests) check. Open point for G1.1's
review: on deactivate the reference lets the hot protocols finish within 300 s (`spsDeactivateTimeout`);
ours ends ChainSync with `MsgDone` and a short timeout, then closes the channels, since our client is the
only agency holder.

Inbound connections. The reference treats a duplex inbound connection as one the governor can adopt: the
`readInboundPeers` input are the inbound peers that negotiated duplex (`Types.hs:311-313`) and the governor
may add them as known peers (section 1.6). Scarab's `InboundGovernor` already runs clients on duplex inbound
connections and `Outbound.connect` already reuses them, so in G1.1 an inbound duplex peer is reported to the
governor as `Inbound` known peers and, when it is promoted, `establish` finds the existing connection (the
`reused` event) instead of dialling. A connection that Scarab opens is still initiator-only (G2); making the
warm connection duplex, as the reference's `InitiatorAndResponder` is, is a G4/G2 follow-up and is not
required by G1.

Limits. `ConnectionManager.Limits.maxOutbound` (64, not exposed) must be at least `established +
establishedBig + local warm`; the governor's default established target is 30 + 10 on a relay, so the default is
enough, and the configuration check (section 4.7) refuses targets above it. `--max-inbound` stays what it is
(the inbound limit, not the reference's `acceptedConnectionsLimit`, a different knob: hard 512, soft 384,
delay 5 s, `Configuration.hs:76-81`; mapping it is G5's).

### 4.6 Roots and providers

`RootProvider[F]` has three operations, each returning a typed result: `localRoots: F[List[LocalRootGroup]]`
(from the topology and `--peer`; re-read on SIGHUP is the reference's behaviour and is a follow-up),
`requestPublicRoots(kind, n, rng): F[(PublicRoots, Duration)]` (configured public roots, bootstrap peers in
the sensitive state, ledger peers when `UseLedgerPeers` allows), and `ledgerPeers: F[Option[LedgerSnapshot]]`.
Host names are resolved at connect time to an address by `ConnectionManager.resolve` (it exists), and a
resolution failure is a connect failure like any other (counted, backed off), not a provider failure. DNS SRV
stays refused (D9).

### 4.7 Configuration

```scala
final case class GovernorConfig(
    deadline: Targets, sync: Targets, consensus: ConsensusMode, peerSharing: PeerSharing,
    useBootstrap: UseBootstrapPeers, useLedger: UseLedgerPeers, minBigLedgerForTrusted: Int,
    policy: Policy, deadlineChurn: FiniteDuration, bulkChurn: FiniteDuration)
```

built from the topology, `config.json` (`TargetNumberOf...`, `SyncTargetNumberOf...`, `PeerSharing`,
`ConsensusMode`, `MinBigLedgerPeersForTrustedState`; `NodeConfig` already reads `ConsensusMode` and `PeerSharing`, not the rest) and
flags, with the defaults of section 1.5 chosen by `--forge`. Errors are `ConfigError` with new
`ConfigProblem` cases (`TargetsNotSane`, `OutboundLimitBelowTargets`). No new flag is proposed: the reference has none for
this, and a switch that kept the old fan-out in the running node would be a second copy of the logic (the
owner's rule: once migrated, drop the dual copy; see open question 12). The
`ConfigSettings.unsupported` entries for GenesisMode and PeerSharing are removed by G1.5 and G1.4.

### 4.8 Peer metrics

```scala
final class PeerMetrics[A: Ordering] private (headers: Board[A], blocks: Board[A], joined: Map[A, Slot])
object PeerMetrics:
  def reportHeader(slot: Slot, at: Instant, peer: A): PeerMetrics[A] => PeerMetrics[A]
  def reportFetch(slot: Slot, at: Instant, peer: A, size: Long): ...
  def upstreamness: Map[A, Int]; def fetchynessBlocks: Map[A, Int]; def joinedAt: Map[A, Slot]
```

A pure value in a `Ref`, 180 entries per board (section 1.9), earliest arrival wins, no `adjustAvg`. It is fed
from the `Peer`: `Peer.Observation.Announced(tip)` today carries the tip of a batch; the header board needs
`(slot, arrival time)` of each *header* a peer sends, and the block board `(slot, size, time)` of each block it
delivers, so `Peer.Observation` gains `HeaderArrived` and `BlockArrived`. These are emitted where the pipelined
producer already decodes headers and receives blocks; the hot path cost is one `Ref.update` per header and per
block, to be measured in G1.2 ([performance.md](../performance.md)). `hotDemotion` reads them.

## 5. Events and metrics

Following [ADR 0014](../adr/0014-typed-log.md): `PeerSelectionEvent` is a closed enum next to `InboundEvent`, with
a `Traced` instance per case and `EventKind` entries under `Area.Net` (so the namespaces are
`Net.PeerSelection.*`; kinds, severities and fields are in the catalogue of [running.md](../running.md) and
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
[running.md](../running.md) line 807 (and the J3 remainder "a peer-selection analogue"). Scarab adds
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

## 7. Stages

Each stage lands on its own, with the gates of its level ([CONTRIBUTING.md](../../CONTRIBUTING.md#gates-and-ceremony-by-level-of-change)).
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
default of 20 active peers against 1, recording CPU, heap and blocks/s in [performance.md](../performance.md);
and the **level-4 row** for the issue's acceptance, recorded once at this stage's end: Preview from genesis with
the official `config.json` and `topology.json` and *no* `--peer`, 30 minutes, reaching the tip through bootstrap
peers only (commit, date, command, image versions, `GET /connections`
showing the temperatures, the `cold/warm/hot` counters at the end). Not claimed: ledger peers, churn.

### G1.2 Metrics and churn

*Scope.* `PeerMetrics` and the two new `Peer.Observation`s; the `hotDemotion` pick using them (before this
stage hot demotion is random); `Churn` (the three regimes, the twelve-step round, the head start, the intervals
3300/900 s with fuzz 600/60 s, the timeouts) and `ChurnLoop`; a `FetchMode` input (bulk sync or deadline) from
the node (open question 3); the Prometheus counters for the whole `peerSelection_*` group and the churn trace;
the `governor` devnet scenario of section 6. Churn needs a sync-versus-deadline signal; until G1.5 the sync
targets are used only through the Praos regimes of section 1.10.

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

*Scope.* A decoder for pool relays (single-host address, single-host name, multi-host name; the last is SRV and
is skipped, D9) from the ledger state's `relays: Bytes`; `LedgerPeers.accumulate`, the 90% quota, the two
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
cddl cross-check (`scripts/cddl_crosscheck.py`) and a fuzz target ([CONTRIBUTING](../../CONTRIBUTING.md): a decoder
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

### G1.5 Genesis hooks

*Scope.* Hooks only. `ConsensusMode` as an input; the sync targets chosen while `TooOld` in `GenesisMode`; the
Genesis monitors (`monitorLedgerStateJudgement` in Genesis mode, the stale-targets rule at
`Monitor.hs:114-140`); `minNumberOfBigLedgerPeers` as the output "trusted state" boolean for consensus (an
`Ask`/`Tell` seam, unused until G3); the `jobVerifyPeerSnapshot`; the `OutboundConnectionsState` output with its Genesis case (G1.1 has the bootstrap and local-root cases); refusing `bootstrapPeers` in Genesis mode as
`TopologyP2P.hs:104-108`; removing the `GenesisMode` entry of `ConfigSettings.unsupported` only when G3 lands
(until then `GenesisMode` is accepted but logs that the density rule is not implemented).

*Files.* `network/.../selection/Sensitivity.scala` (extended), `node/.../PeerGovernor.scala`, `NodeConfig.scala`,
tests: the bootstrap/Genesis properties of section 6 in Genesis form.

*Gates.* Level 2; `check` and `relay`. No stage-boundary run: the Genesis end-to-end evidence belongs to G3.

*Evidence.* The properties of the "bootstrap / Genesis" group of section 6 in both modes; a unit test that the
sync targets are installed on the flip to `TooOld` and restored on `YoungEnough`; every gap that is left
(GDD, LoE, LoP, the trusted-state consumer) is [#33](https://github.com/KD5UJC-XYZ/scarab/issues/33).

### When G1 is done

[Roadmap](../roadmap.md) G1 is marked done after G1.4 (the issue's scope: targets per peer set, churn, PeerSharing,
simulation properties, the Preview acceptance); G1.5 is the hand-over to G3 and may follow. The compatibility
matrix row "Network" gains "outbound peer selection" with the evidence log rows of G1.1 and G1.3; the issue's
closing comment lists the commits and the evidence of each stage; the architecture chapter gains
"Peer selection (G1)" next to "Connections" in the same change as each stage; `docs/running.md` loses the
"the node has no peer selection governor" sentence at line 807.

## 7a. G1.1 as built

Branch `g1/1-governor`, on `main` after J2.9. The decisions of section 8a are all implemented; [ADR 0016](../adr/0016-peer-states-and-the-governor.md)
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

**Fetch mode.** Derived as the reference does under `PraosMode` (`readFetchModeDefault`: bulk sync in the sensitive state, else by
the distance of the tip from the wall clock, 1000 slots). `ConsensusMode: GenesisMode` in the official configuration is still
ignored and logged as unsupported, so the GSM-state-based `readFetchMode` of the Genesis mode is not used; the GSM runs, drives the
judgement and so the sensitive state, and is published. The mode is published (`fetchModeChanged`, `scarab_fetch_mode`) and picks
the regime of the targets; **BlockFetch does not read it yet** (follow-up issue).

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
2. The fetch mode follows the Praos function, not the GSM state (above); no consumer yet.
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

## 7c. G1.3a as built

Branch `g1/3a-ledger-peers`; the pure part of [#137](https://github.com/KD5UJC-XYZ/scarab/issues/137): new files only, nothing of the
decision function, the governor loop, `Peer` or `Main` is touched. The governor integration (G1.3b) is below.

**Placement.** `ledger/.../PoolRelays.scala`: `PoolRelay` (the reference's `StakePoolRelay`: `SingleHostAddr`, `SingleHostName`,
`MultiHostName`), `LedgerRelay` (`Address`, `Domain`, `Srv`; `Srv.skipped` is D9), `Ipv4`/`Ipv6`/`RelayIp`, `RelayPort`, `PoolRelayError`
(ten cases, no strings in control flow), `PoolRelays.decode`/`encode`/`ledgerRelays`/`connectable`/`peerRelays`.
`network/.../selection/LedgerPeers.scala`: the stake maps (`StakeMap[K, R]` with the phantom kinds `LedgerKind.All`/`Big`, `StakeMaps`),
the `Kind` type class (weighting, draw scale, which map) and `object LedgerPeerStake` with `recompute`, `weighted`
(`accPoolStake`), `accumulated`/`accumulate` (`accumulateBigLedgerStake`, the quota), `draw` (`pickPeers`), `answer`,
`supply` (`getLedgerPeers`), `snapshotBeatsLedger`/`stakeMapWithSlotOverSource`, `lifetime`/`expired`/`refresh` (the worker's cache) and
the snapshot types (`LedgerPeerSnapshot.V2`/`V23`, `SnapshotPool`, `SnapshotPoint`). The relay type is a parameter `R`, as the peer address
`A` is, so the governor's tests use strings and the node uses `LedgerRelay`. `node/.../PeerSnapshot.scala`: the JSON reader of
`peerSnapshotFile` (circe is in `node`, not in `network`), with `PeerSnapshotError` (six cases).

**Facts the reading established** (the reference is the pinned set of section 1.1, plus cardano-ledger-core 1.21.0.0 rev f649f97,
cardano-ledger-binary 1.9.1.0 rev 048b78d and ouroboros-consensus 4.2.1.0 rev 82ecba3 of the pin file):

1. *Wire format of a relay* (`State/StakePool.hs:389-421`): `[0, port/null, ipv4/null, ipv6/null]`, `[1, port/null, dns_name]`, `[2, dns_name]`;
   the port is a `Word16`, a DNS name is text of at most 128 bytes (64 before protocol 9, the larger bound reads both).
2. *IPv4 is four octets in network order.* cardano-ledger-binary 1.9.1.0 writes `putWord32be` and its changelog says "Fix IPv4 encoding
   order to use network order instead of swapped"; the tree at rev f649f97 still has `putWord32le` of the numeric word (the swapped write),
   which the release corrects. The registration in `fixtures/mainnet/shelley-pool-network-block.hex` (slot 4,495,760) carries
   `4403652f85` with port 3001: 3.101.47.133, an address in the cloud range, not 133.47.101.3. The decoder reads network order.
3. *IPv6 is sixteen octets in network order* by the same reading (the four host-address words, each written little-endian,
   `Encoder.hs:569-575`), **not pinned by a real registration**: no registration with an IPv6 relay is in the repository. Open item (below).
4. *The relays a pool offers* (`relayToLedgerRelayAccessPoint`, ouroboros-consensus `PeerSelection.hs:37-66`): with both an IPv4 and an
   IPv6 address the IPv4 one is the relay; an address without a port, or a `SingleHostAddr` without an address, is unusable; **a
   `SingleHostName` without a port is an SRV domain** as well as every `MultiHostName`; a pool without a usable relay is no ledger peer.
   The ledger's list is ordered by stake, largest first, and its relays are the pool's current relays followed by its future relays
   (`queryStakePoolRelays`, cardano-ledger-api `State/Query.hs:720-736`), with the stake of the pool distribution (`nesPd`, the *set* snapshot).
5. *SRV pools stay in the maps.* D9 skips SRV relays when connecting, but the reference weighs and accumulates the pools that have them,
   so the maps keep them (the relay is `LedgerRelay.Srv`, `skipped == true`) and the 90% set is the reference's. A draw can return an SRV
   relay; G1.3b discards it (the governor asks again after the 5 s `ttl`).
6. *The snapshot replaces both maps* (`stakeMapWithSlotOverSource`, `LedgerPeers.hs:419-431`): section 1.12 says "for big ledger peers"; the
   ordinary map is weighted (square root) from the snapshot's pools as well. The snapshot wins when it is at a block (not the origin), at
   or after the ledger's slot (origin = 0) and at or after `useLedgerAfterSlot` (`Always` = 0); the maps already made from a snapshot are
   kept while its slot is the cached one.
7. *The draw* (`LedgerPeers.hs:158-211`): a 64-bit number scaled to `[0, 1]`, or `[0, 0.9]` for big ledger peers, then `lookupGE`. The pool
   that crosses the 90% line is therefore drawn with only the part of its stake below 0.9 (in `LedgerPeersSuite`: pools of 64, 25 and 11 per
   cent give 71.1, 27.8 and 1.1 per cent). A number that finds no pool still uses up a draw, draws are with replacement, and the empty
   *ordinary* map ends a draw of either kind (line 169).
8. *Cache* (`LedgerPeers.hs:213-223, 270-278`): 30 s only while the ordinary map is empty **and** ledger peers are on, 1847 s otherwise;
   expired when the age is strictly more.

**Deviations from the reference, in one place.**

1. The object is `LedgerPeerStake`, not `LedgerPeers`: `node/Topology.scala` already has an enum `LedgerPeers` (the setting of
   `useLedgerAfterSlot`), and `PeerNetwork` imports the package of the governor with a wildcard, so a second `LedgerPeers` breaks it. G1.3b
   should replace the enum by `UseLedgerPeers` (same cases) and may then rename the object.
2. The square root goes through a `Double` as the reference's does (`Utils.hs:87`), converted from a 40-digit quotient: it differs from GHC's
   `fromRational` only for a stake within 1e-40 of a rounding tie. The conversion back is exact.
3. The random streams are `Rng` (SplitMix64) draws: the Word64 of a draw is the generator's `nextLong`, the relay index is `Rng.between`
   (the reference's `randomR`). Proportions are the reference's; the byte stream is not.
4. A total weight of zero gives empty maps (the reference divides by zero and fails: ouroboros-network issue 5091).
5. The reference wraps a snapshot port outside 0 to 65535 into a `Word16`; the reader refuses it. Stakes are read as exact decimals.
6. A draw returns the relays in the order drawn (the reference's list is reversed; it is made a set afterwards).

**Tests.** `PoolRelaysSuite` (19: hand-built vectors of each relay shape, ten refusals by case, the 128-byte bound in UTF-8, the mainnet
registration read back and re-encoded to the same bytes, properties: round trip, the consensus mapping, arbitrary and mutated bytes never
throw, 2,000 relays on a 256 KiB stack); `LedgerPeersSuite` (34: the quota by example including the exact-90% boundary and by property,
`recomputeRelativeStake` sums to one, draw proportions over 200,000 draws with a fixed seed against the reference's formulas for both
weightings (4.5 sigma), `prop_pick100`, `prop_pick`, determinism, the cache lifetimes and the strict comparison, the slot rule by example and
by property over all slot combinations, a 1,500-pool Zipf distribution checked against an independent count in doubles, and 100,000 pools
accumulated, weighted and drawn on a 256 KiB stack: ADR 0011); `PeerSnapshotSuite` (8: the Leios testnet file as the official version-23
example, versions 1 and 2, the relay forms, exact stakes, the refusals by case); two fuzz targets, `pool-relays` and `peer-snapshot`.
`check` runs all of them.

**Not done, and why.**

- *No real distribution of a large network is in the repository*, so the 90% quota is shown on a synthetic Zipf distribution and a 100,000
  pool one; a mainnet or Preview pool distribution fixture (from `GetPoolDistr` of a node) is for G1.3b's `LedgerPeersSource` test.
- *No Preview registration with relays*: the repository has none and the public Preview pools were not fetched. The real registration used
  is mainnet's (provenance in `fixtures/mainnet/README.md`); the devnet registers its three pools without relays (`relays: []` in the genesis),
  so the devnet cannot show a non-empty decode until a pool is registered with relays in a scenario.
- *IPv6 is read from the code, not from a registration* (fact 3): the first real IPv6 relay (a Preview or mainnet registration, or a devnet
  pool registered with `cardano-cli ... --pool-relay-ipv6`) pins it; tracked on #137.

**What G1.3b must wire.**

1. `node/.../LedgerPeersSource.scala`: read the pool distribution (`nesPd`) and the registered pools' relays (current, then future) from the
   volatile chain's ledger state through a read interface; build `Vector[LedgerPool[LedgerRelay]]` in stake order from
   `PoolRelays.decode(relays)` and `PoolRelays.peerRelays`; log and skip a pool whose relays do not decode (`PoolRelayError`), do not fail.
2. A ledger-peers worker over `LedgerPeersCache`/`LedgerPeerStake.refresh`/`answer`, with the monotonic clock, `UseLedgerPeers` from the
   topology (replace the enum in `Topology.scala`, `useLedgerAfterSlot` > 0: `After`, 0: `Always`, absent or negative: `Disabled`), the
   latest slot of the ledger as `WithOrigin`, and `PeerSnapshot.read` of `peerSnapshotFile` (read once at start; a bad file stops the node
   with the typed error); an `Rng` split from the governor's.
3. The `RootProvider`: `requestPublicRoots` answers from `answer[All]`, and `Source.Ledger`/`Source.BigLedger` for the peers; drop
   `LedgerRelay.Srv` picks (`skipped`) and resolve `Domain` at connect time; `ttl` is `LedgerPeerStake.ReRequestInterval`.
4. In `decide`: the big-ledger families, their targets, the churn steps 1 to 6 (G1.2's `Churn`), and the YoungEnough transition that lets
   ledger peers replace the bootstrap peers.
5. The level-4 row of section 7: Preview, official files, no `--peer`, past `useLedgerAfterSlot`, 30 minutes.

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
`Ref` update per batch keeps the callback off the per-header path). `OutboundPeers` attaches the peer's address where the hot session
starts and updates the shared `Ref[PeerMetrics]`. The board is the reference's: earliest arrival wins, 180 slots, an older slot on a
full board is dropped, the join and last-seen registries are pruned at the oldest slot of the boards, and `adjustAvg` is not
implemented (it is `0` in the pinned release). The hot-demotion pick ranks `(upstreamness + fetchynessBlocks, joined slot, random)`;
a peer the board does not know ranks before all that it does (`Map.findWithDefault (0, Nothing)`), and both the local-root and the
other hot demotions use it.

**Churn.** The twelve steps in the reference's order, with its timeouts (300, 60, 121, 60, 80, 60, 300, 60, 121, 60, 80, 60 s; the
request timeout of steps 5 and 11 is `policyPeerShareOverallTimeout`, 10 s), the three regimes, `decrease` and `decreaseWithMin`,
the head start, and the sleep (3300 s plus up to 600 s in deadline mode, 900 s plus up to 60 s in bulk sync, less the time the round took).
`ChurnLoop` is the one writer of the governor's targets; the 1 s regime loop of G1.1 is now only the publisher of the fetch mode.

**Deviations, in one place.**

1. The six big-ledger steps (1 to 6) are left out of a round until there are big ledger peers (G1.3): their counters are zero, so each
   increase would wait out its timeout (200 s in all) for nothing. `Churn.round(bigLedger = true)` has all twelve and is tested.
2. A target that would be insane is clamped (the root target within the known target, no negative active target) where the reference's
   `Monitor` silently ignores the update; a property shows no step needs it for sane base targets.
3. The counters are polled every 500 ms where the reference blocks in STM; the amount a step achieved is the change at the moment the
   poll sees the targets met (or the timeout).
4. A change of regime during the sleep (the node left bulk sync) asks for the new regime's targets at once; the reference waits for the
   next round, up to an hour away.
5. Peer metrics come from outbound hot sessions only ([#150](https://github.com/KD5UJC-XYZ/scarab/issues/150)).
6. The pin's warm promotion is uniform; `main` weights it by the tepid flag ([#149](https://github.com/KD5UJC-XYZ/scarab/issues/149)).
7. **BlockFetch (#144).** `BlockPool.claim` takes the fetch mode: in bulk sync a block another session holds is not claimed (the
   reference's `filterNotAlreadyInFlightWithOtherPeers`, `Decision.hs` 659-690); in deadline mode it can be, by up to
   `MaxConcurrencyDeadline` sessions (the reference bounds the peers fetching at once by it, `fetchRequestDecision` 1100-1120), and the
   first delivery wins. cardano-node's default is 1 for both modes (`Configuration.hs` 89-100), so the modes fetch alike unless the key
   is set; the devnet's `configuration.yaml` sets 2. Not ported, with the reasons in [#148](https://github.com/KD5UJC-XYZ/scarab/issues/148):
   the peer priorities of `prioritisePeerChains`, `maxConcurrencyBulkSync = 1` (ours is a range per idle connection, as measured in
   performance.md), the per-peer request and byte windows.

**Properties and tests.** `ChurnSuite` (the `decrease` table 1 to 0, 5 to 4, 10 to 8, 100 to 80; the twelve steps, their order and
timeouts; a round at the relay defaults in each regime; every step sane for any sane base and any amounts achieved; the sleep),
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

## 8. Open questions for the owner

1. **D3, the meaning of `--peer`.** One local-root group with `hot = warm = n` keeps today's behaviour. Is that
   the intent, or should a bare `--peer` list be public-root-like (so only `targetNumberOfActivePeers` of them
   are followed)? A topology with `valency` below its access points will, from G1.1, follow fewer peers than it
   does now.
2. **The ledger-state judgement.** The reference gets `TooOld`/`YoungEnough` from the Genesis State Machine of
   ouroboros-consensus (not read for this document). Scarab has no GSM. Proposal for G1.1: `YoungEnough` when the
   tip's slot time is within a configured age of the wall clock (the age a flag and a documented default),
   `TooOld` otherwise, flipping with hysteresis; to be replaced by a faithful GSM port when the consensus sources
   are read. Does the owner want the GSM read and mirrored first (it also feeds G3)?
3. **Fetch mode.** Churn needs bulk-sync versus deadline (section 1.10), which consensus supplies. Scarab's
   `BlockPool` and the follower have the notion implicitly (far ahead of the tip: deep pipeline). Proposal: derive
   it from the same tip age as the judgement, with its own threshold. Acceptable?
4. **D5, the default of 20 active peers.** If the measured cost per extra header-validating session on Preview
   is high, do we ship Scarab with a lower default active target (a recorded deviation) or accept the cost?
5. **D9, SRV and DNS.** Out of scope as proposed: SRV access points and ledger SRV relays are skipped, host
   names are resolved at connect time. Is DNS-based root re-resolution (the reference's `RootPeersDNS`, TTL-driven)
   needed for the Preview and mainnet use the owner has in mind, or can it wait for a separate item?
6. **Warm connections are not duplex.** G2's initiator-only limit stays. The reference opens
   `InitiatorAndResponder` for non-local-root peers (`EstablishedPeers.hs:297-302`), so a Haskell peer that we
   dial can use the connection to follow us. Making Scarab's outbound connections duplex touches the mux and
   G2's reuse logic. Separate item before or after G1.4? PeerSharing works without it.
7. **Reload.** The reference re-reads the topology (local roots, public roots, ledger and bootstrap flags) on
   SIGHUP (the P2P SIGHUP handler, `CN/Run.hs:413` and `574-700`, which re-reads the topology file and can reconfigure the network). Is topology reload in scope for G1, or a follow-up
   after the KES/credentials reload (ADR 0015) pattern is reused?
8. **Misbehaving peers.** The reference's overview says an adversarial peer is dropped from all sets
   immediately, and the classification lives in consensus's `RethrowPolicy`/`ExitPolicy` (not read). Scarab's
   `PeerError.InvalidHeader` ends the session today. Proposal: an `InvalidHeader`, protocol violation or
   oversize message maps to *forget* (and a short ban against re-learning from peer sharing: a gap the
   reference has too), network failures to the fail-count backoff. Needs the owner's yes before G1.1 because it
   decides the `PeerError` to `Status` mapping.
9. **ADR.** One ADR at G1.1 for D2 and the module split (D10), or two (state representation; module placement)?
10. **Evidence runs.** The two level-4 Preview runs (G1.1, G1.3) are 30-minute runs against the public relay. Is
    that acceptable to run from the usual box, or should they run on the long-run machine of the owner's
    choosing (not named here)?
11. **The trusted-state output.** The governor's `OutboundConnectionsState` (section 1.15) is consensus's input for
    deciding it is caught up. Scarab has no GSM, so G1.1 only publishes it (gauge and log line). Confirm that the
    consumer (a faithful GSM, which also yields the ledger-state judgement of question 2) is a separate item to
    schedule with G3, not part of G1.
12. **No escape hatch.** Replacing the fan-out in `Main` with the governor in G1.1 leaves no `--governor off`; the
    safety net is the layer-2 simulation, `relay` and `peercut`. A flag that kept the old path for a release would be
    a dual copy in the running node, which contradicts the rule that a migrated path replaces its predecessor.
    Confirm, or ask for the flag for exactly one release.

## 8a. Owner decisions on the open questions (2026-10-17)

Answers to section 8, taken before G1.1 started. They override the proposals above where they differ.

1. `--peer` is one local-root group with `hotValency = warmValency = n` (today's behaviour). A topology's
   `hotValency` and `warmValency` are honoured.
2. The ledger-state judgement is the reference's Genesis State Machine, ported faithfully
   (`Ouroboros.Consensus.Node.GSM`, ouroboros-consensus 4.2.1.0, the release cardano-node 11.1.3 pins), with
   citations. No ad-hoc tip-age rule.
3. The fetch mode (bulk sync or deadline) is derived from the GSM, as the reference does.
4. The reference default targets are kept (relay 60/150/30/20/15/10/5); the cost per extra hot session is
   measured in the G1.1 relay run and reported.
5. and 7. SRV records and topology reload on SIGHUP are out of scope; follow-up issues.
6. Outbound connections stay initiator-only for now (G2).
8. **Decided by the owner (confirmed 2026-10-17, no longer pending):** a protocol violation, an invalid header or
   an oversize message means the peer is forgotten; a network failure means backoff. The classification is one
   typed function (`FailurePolicy.classify`), so a later change touches one place.
9. One ADR covers D2 (in-progress states in the peer status) and the module placement.
10. The G1.1 evidence run is on the development machine: a Preview follower from the official topology and
    config with no `--peer`, 30 minutes, read-only towards the public network.
11. `OutboundConnectionsState` is published only as a gauge and a log line.
12. No `--governor off` flag.

## 9. Risks, in one place

- The warm/hot split touches the `network` entry points that every connection uses (section 4.5): the existing
  suites are the net, and the split lands first inside G1.1 as its own commit with no behaviour change.
- 20 hot sessions multiply header validation and memory per candidate chain (D5): measured in G1.1.
- The reference's `adjustAvg` is stubbed, and a few behaviours (the exception classification, the fetch mode,
  the judgement) live outside the sources read; each is an open question above, not a guess in the code.
- Citation drift: the pin caveat in section 1.1; G1.1's first commit re-checks the cited lines against the
  reference image's release and corrects this document.
