<!-- SPDX-License-Identifier: Apache-2.0 -->
# The Scala design

Part of [Peer selection: the P2P governor (G1)](../peer-selection.md).

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

An insane target update from configuration is a `ConfigError` at startup. Churn uses a separate
`Churn.Proposal` record because native arithmetic can temporarily produce negative active targets or a root
larger than the reduced known target. The churn writer retains that proposal for subsequent arithmetic and
counter predicates; the governor ignores it, matching `sanePeerSelectionTargets` in `Monitor.hs`. Validated
`Targets` therefore retain their construction invariant without clamping native proposals.

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
`Types.hs:261`). There is no recursion over outside data in the decision function ([ADR 0011](../../adr/0011-stack-safety.md)):
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

The churn governor is a second loop, `ChurnLoop.run`. Its pure part is `Churn.planRaw`, `decrease` and
`decreaseWithMin`. It writes a `Churn.Proposal` and root-generation pair atomically, then sends a target wake.
Counter publication and the churn before-counter/proposal update share one atomic cell. The governor consumes
its latest proposal rather than replaying superseded snapshots. Invalid proposals and
stale-generation wakes leave accepted targets unchanged. Counter waits observe a `SignallingRef` updated by the governor's counter publication
without polling and race the step's timeout; a predicate satisfied at the timeout boundary wins. All twelve
steps run regardless of ledger-query configuration. Changes of regime do not interrupt a fuzzy sleep. Both
loops stop with the node's `Resource`.

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
`reused` event) instead of dialling. An outbound connection configured as `InitiatorAndResponder` now offers the
duplex handshake and owns the negotiated responder protocols in the same connection resource. Outbound attempt
generations and link identity fence stale completion and cleanup after topology reload; an old attempt cannot
replace or close a newer link for the same address.

Limits. `ConnectionManager.Limits.maxOutbound` (64, not exposed) must be at least `established +
establishedBig + local warm`; the governor's default established target is 30 + 10 on a relay, so the default is
enough, and the configuration check (section 4.7) refuses targets above it. `AcceptedConnectionsLimit` of the configuration (`--max-inbound` overrides its hard limit) is
the reference's (hard 512, soft 384, delay 5 s, `Configuration.hs:76-81`), applied as in
`Server/RateLimiting.hs` (#339).

### 4.6 Roots and providers

`RootProvider[F]` has three operations, each returning a typed result: `localRoots: F[List[LocalRootGroup]]`
(from the topology and `--peer`; re-read on SIGHUP), `requestPublicRoots(kind, n, rng): F[(PublicRoots, Duration)]`
(configured public roots, bootstrap peers in the sensitive state, ledger peers when `UseLedgerPeers` allows),
and `ledgerPeers: F[Option[LedgerSnapshot]]`.
The node-owned `PeerDns` resource resolves topology and ledger host names to every successful A/AAAA answer.
Local groups are re-resolved at independent per-source deadlines and installed through the acknowledged
`RootsConfigured` owner event; a failed lookup retains the last successful addresses, while a successful empty
answer removes them. Public and bootstrap requests return a refresh TTL to the governor. SRV names query `_cardano._tcp.<domain>`, retain
only the lowest priority group, choose within that group by SRV weight, discard target `.`, resolve every address of
the selected target, and use the selected SRV TTL for those addresses. Lookup failures produce no addresses and the
reference's 60-second empty-answer retry.

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
owner's rule: once migrated, drop the dual copy; see open question 12). `PeerSharing` is no longer unsupported
(G1.4). `GenesisMode` is accepted and its governor hooks run, while its explicit partial-implementation diagnostic
remains until G3 supplies the density and ledger-dependent rules.

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
block, to be measured in G1.2 ([performance.md](../../performance.md)). `hotDemotion` reads them.
