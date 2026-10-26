# Peer selection: the P2P governor (G1)

**Status**: current implementation on main: G1.1–G1.5 and the #142 topology/DNS/duplex implementation, including atomic DNS-root refresh (#240). The original parent acceptance
and private reference evidence are recorded below. Full Genesis chain-density and ledger-dependent fork choice
remain G3. Tracked in
[#31](https://github.com/KD5UJC-XYZ/scarab/issues/31) (roadmap G1). Written against `main` at 6ce5bb77 and the
reference sources named in section 1.1, which were read in full for the governor, the churn governor, the peer
metrics, ledger peers, PeerSharing and the Cardano configuration; every claim about the reference carries its
file and line. Sections 0–7 preserve the original decisions and staged plan; their future tense
describes that planning baseline. The sections headed "as built" and "acceptance run", together
with section 8a's owner decisions, record implementation and evidence. Read those alongside the
current [compatibility matrix](../compatibility.md) when assessing remaining work.

The owner's priority for this item: a peer controller with the feedback mechanism that manages hot, warm and
cold peers per the ouroboros-network specification, as `cardano-node` does. "Feedback" is the pairing this
document is built around: the governor decides from the counts of peers in each temperature against targets
(feedback from the connections), and the churn governor and the peer metrics feed back into the governor's
targets and choices (feedback from the chain).

## 0. Decisions for the owner (my recommendation first)

| # | Decision | Recommendation |
|---|---|---|
| D1 | How closely the governor mirrors the reference | Mirror what is observable: the three temperatures and the nested known, established and active sets, the seven targets and their defaults, the order in which the decisions are tried, the backoffs, the churn steps and intervals, the PeerSharing wire and policy, the counters and their metric names. Take the internal structure from Scarab's own style. The one structural departure, with its reason, is D2. **Superseded**: see the note below the table. |
| D2 | State representation | One `Map[A, PeerRecord]` whose status is an enum with the transient states in it (`Connecting`, `Activating`, `Deactivating`, `Closing`), instead of the reference's separate `inProgress*` sets next to the established and active sets (`Governor/Types.hs:673-681`). The reference's sets and counters stay as derived views (`PeerSelectionView`, `Types.hs:851`), so the metrics and the tests compare against the reference unchanged. The reason: several of the reference's invariants are "this in-progress set is a subset of that set" (`assertPeerSelectionState`, `Types.hs:1328`); with the status in the record they cannot be violated by construction ([AGENTS.md](../../AGENTS.md): lean on the compiler). Needs the written reason ADR 0001 asks for: this paragraph, and an ADR when G1.1 lands. |
| D3 | What `--peer` means once a governor exists | Today the node follows every `--peer` (one supervised session each, `Main.scala` 700-740). Under the governor `--peer` becomes one local-root group with `hotValency = warmValency = n`, which keeps today's behaviour (all of them are hot) and makes it the reference's behaviour. A topology's `hotValency` and `warmValency`, which `Topology.scala` reads and ignores, are honoured for the first time. This is a behaviour change visible to anyone who gave a topology with `valency` smaller than its access points. |
| D4 | Defaults when no targets are configured | The reference's defaults, picked as `cardano-node` picks them: the block-producer set when `--forge` is given (its `hasProtocolFile`), else the relay set (section 1.5). Targets are overridable by the `config.json` keys the reference reads (`TargetNumberOfRootPeers` and the rest). PeerSharing: off for a producer, on for a relay (section 1.13). |
| D5 | Hot peers and the ledger work | A hot peer is a peer with a running `Follower.session`. The reference's default of 20 active peers on a relay means 20 header-validating sessions. Header-first validation shares verdicts through `VerificationCache`, but the cost per extra hot peer on Preview and mainnet is not measured. Recommendation: G1.1 ships the reference defaults and the stage's evidence includes the measurement; if it is too dear, the stage lowers the Scarab default for `targetNumberOfActivePeers` (a configuration difference, recorded in [compatibility.md](../compatibility.md)), not the mechanism. |
| D6 | Where the Genesis boundary is | G1 ends at hooks: the `ConsensusMode` and `LedgerStateJudgement` inputs, the sync targets, the bootstrap-peers sensitive state. The density and fork-comparison rules (GDD, LoE, LoP) are G3 ([#33](https://github.com/KD5UJC-XYZ/scarab/issues/33)). The bootstrap-peers state is in G1.1, not G1.5, because the official Preview topology depends on it (section 1.15). |
| D7 | How the "feedback" metrics reach the governor | A `PeerMetrics` component fed by two new observations from the `Peer` (the first header of each slot, the first block of each slot, each with its arrival time and its peer) and read by the hot-demotion pick (section 1.9). The reference feeds the same two events from consensus through `ReportPeerMetrics` (`PeerMetric/Type.hs:29-37`). |
| D8 | Test oracle | The reference's governor tests are property tests over a simulated environment (`GovernorMockEnvironment`, `Test/Cardano/Network/PeerSelection/MockEnvironment.hs:139-163`) run by `IOSim`. We port the environment and the properties by name (section 6) on `TestControl`, as `FollowerSuite` already runs on it. The Haskell tests themselves are not run (the sources are not in the reference image). |
| D9 | SRV and DNS root selection | #142 implements topology and ledger SRV roots, all successful A/AAAA answers, TTL refresh, and SIGHUP topology reload. DNS follows the pinned `ouroboros-network` RootPeersDNS policy; see section 6.1 and the remaining limits in section 8. |
| D10 | ADR | One ADR at G1.1: "Outbound peer selection is a pure decision function over a status-carrying state" (D2), so the next sessions do not reopen it. |

**Superseded (2026-10-25).** D1's internal structure "from Scarab's own style", D2's state
representation and D10's ADR were decided under the earlier ADR 0008, which let networking keep its
own structure. The current [ADR 0008](../adr/0008-networking-mirrors-ouroboros-network.md) requires the reference's structure as well as its behaviour, so
those structural decisions no longer stand: each structural difference from the reference governor
is alignment work under [#477](https://github.com/KD5UJC-XYZ/scarab/issues/477).

## Pages

Each component of the design has a page of its own; the section names below are the original headings.

| Page | Sections |
|---|---|
| [The reference model](peer-selection/reference-model.md) | 1. The reference model |
| [Scarab before G1 and the module map](peer-selection/before-g1-and-module-map.md) | 2. Scarab before G1; 3. Reference module to Scala component |
| [The Scala design](peer-selection/scala-design.md) | 4. The Scala design |
| [Events, metrics and test strategy](peer-selection/events-metrics-and-tests.md) | 5. Events and metrics; 6. Test strategy |
| [Stages](peer-selection/stages.md) | 7. Stages |
| [G1.1 as built and its acceptance run](peer-selection/g1-1-as-built.md) | 7a. G1.1 as built; 7b. G1.1 acceptance run |
| [G1.3 as built and its acceptance run](peer-selection/g1-3-as-built.md) | 7c. G1.3a as built; 7d. G1.3b as built; 7e. G1.3b acceptance run |
| [G1.2 as built](peer-selection/g1-2-as-built.md) | 7b. G1.2 as built |
| [G1.4 as built](peer-selection/g1-4-as-built.md) | 7c. G1.4a as built; 7f. G1.4b governor and wiring |
| [G1.5 and the topology, DNS and duplex work as built](peer-selection/g1-5-as-built.md) | 7g. G1.5 (#139) and topology/DNS/duplex (#142) as built |
| [PeerStateActions result handles and monitor](peer-selection/peer-state-actions.md) | 7h. PeerStateActions result handles and monitor |
| [Open questions, owner decisions and risks](peer-selection/decisions-and-risks.md) | 8. Open questions for the owner; 8a. Owner decisions on the open questions (2026-10-17); 9. Risks, in one place |
