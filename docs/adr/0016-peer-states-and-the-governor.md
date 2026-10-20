# 0016. Peer states carry the in-progress transitions, and the governor is a pure decision function under a thin loop

- **Status**: Accepted
- **Date**: 2026-10-17
- **Issue**: [#31](https://github.com/KD5UJC-XYZ/scarab/issues/31)

## Context

The Haskell node chooses its outbound peers with the peer selection governor
(`Ouroboros.Network.PeerSelection.Governor`, ouroboros-network 1.2.0.0 at the pins in
[the design](../design/peer-selection.md)). It keeps three nested sets of peers (known, established,
active) and, next to them, sets of peers whose promotion or demotion is in flight (`inProgressPromoteCold`,
`inProgressPromoteWarm`, `inProgressDemoteWarm`, `inProgressDemoteHot`, `inProgressDemoteToCold`), each with
its own invariant. A peer can then be in the active set and in an in-progress set at once, and the invariants
are checked as assertions at run time. Scarab needs the same behaviour (the reference is the design, ADR 0001)
and the owner asks for types that make the wrong state unrepresentable, effects on cats-mtl capabilities, and
code that is tested without a network.

## Decision

1. **One record per peer, with a closed status.** `Status` is `Cold | Connecting | Warm | Activating | Hot |
   Deactivating | Closing | Cooling` (D2 of the design): the reference's five in-progress sets are the transient states
   of one enum, so a peer is in exactly one state and the invariants that tied the sets together are the type.
   The three nested sets are views computed from the map (`GovernorState.view`); `GovernorState.invariant`
   states what remains (counters add up, local roots are known, targets pass `Targets.validated`) and is
   checked by the tests, not by the node.
2. **The decision is a pure function.** `PeerSelection.decide(state, now)` returns the next `Decision`
   (jobs to run and the state with the jobs marked in progress) or `Step.Idle(state, wakeAt)`, in the order of
   the reference (sensitive state, judgement, targets, local roots, public roots, forget, connect local, connect
   other, close other, activate local, activate other, deactivate local, deactivate other). Randomness comes from
   a splittable `Rng` inside the state, time is an argument, so the function is deterministic and is tested
   with examples and properties and without effects.
3. **Events are applied before the decision.** Everything the outside says (an observation of a connection, a
   completed job, new targets, a new judgement, roots, a wake-up) is an `Event`; `Events.apply` is pure too.
4. **The loop is thin.** `PeerGovernor` (module `node`) holds the state in `Stateful`, reads the clock and the
   configuration from `Ask`, reports `Trace`s through `Tell`, raises `GovernorError` through `Raise`, and waits only
   on its event queue; timers are fibers that offer `Event.Wake`, deduplicated by time. `PeerStateActions` is the
   interface to the world; `OutboundPeers` is its interpreter over the `ConnectionManager`, the `Follower`
   sessions and the `BlockPool`, one fiber per connection and one per hot session, whose resources own the
   claims on blocks.
5. **Failure classes are decided in one place.** `FailurePolicy.classify(PeerError): FailureClass` maps a
   protocol violation, an invalid header or an oversize message to *forget the peer* (unless its origin is
   Local or Bootstrap, which are never forgotten) and a network failure or a timeout to *back off* (`FailureClass.Misbehaved` and `Transient`).
   Owner decision of 2026-10-17 (design section 8a, item 8), not an open question.
6. **Placement.** The pure part (state, decision, events, targets, roots, sensitive state, the Genesis
   State Machine and the fetch mode, the random generator) is `network/.../selection`, with no dependency
   on cats-effect. The loop, the interpreter, the network wiring (`PeerNetwork`), the GSM runner and the
   marker file are in `node`, next to the follower and the connection manager they drive.
7. **Faithful to the reference, with the differences written down**: the Genesis State Machine is ported from
   `Ouroboros.Consensus.Node.GSM` (ouroboros-consensus 4.2.1.0) and the fetch mode is derived from it; outbound
   connections were initially initiator-only; `OutboundConnectionsState` is published as a gauge and a log line only;
   there is no switch to turn the governor off. The list of deviations is in the design document (G1.1 as
   built).

**Implementation addendum (#142).** Outbound connections configured for
`InitiatorAndResponder` now negotiate duplex and serve the peer on the same bearer.
Big-ledger selection, churn and PeerSharing participate in `PeerSelection`'s ordered decision
families. The [as-built design](../design/peer-selection.md) records these extensions and their
acceptance scope; they retain the pure decision function and single status record per peer.

## Consequences

- The governor is tested on virtual time: the properties `prop_governor_*` of the reference run on
  `TestControl` against a mock environment, and a given seed gives the same trace.
- A new state is a new case of the enum and the compiler points at every decision that must handle it.
- The state is larger than the reference's (one record per peer plus the views), and the views are computed per
  decision; at the sizes of the targets (hundreds of known peers) this costs microseconds, and the decision is
  not on the block path.
- The loop never gives up, with one exception: a node in the sensitive state that has not reached the clean one
  in 15 minutes stops (`BootstrapPeersCriticalTimeout`), as the reference does. The old "fatal after 30
  consecutive failures" of the outbound supervisor is gone.
- Revisit if peer sharing or churn (G1.2 to G1.4) need state the record cannot carry without a second source of
  truth, or if the per-decision views show up in a profile.
