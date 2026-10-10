# Repeated-epoch dependency and invariant map

Assessed at published `c1e3dc99da88baf676c153045da78119b03f5f64`. This increment adds a bounded pure invariant test slice, not a repeated-epoch validator, boundary admission path or native conformance claim. No production guard or shared interface changes.

## Existing capability and actual gaps

| Component | Existing source and capability | Requirement before repeated operation |
|---|---|---|
| Source and initial state | `app/NativeEpochComponents.scala` derives narrowly checked early-state components; `SyntheticBoundaryState.seed` binds supplied complete governance/NM/parameter/pool relationships. | Retain authenticated source attribution and complete automatic-effect domains across epochs. Early empty set/go/NM state is not a perpetual invariant. A source digest alone does not authenticate state. |
| Monetary RUPD | `ConwayRewardStart`, `ConwayPoolReward`, `ConwayMemberRewards`, `ConwayRewardPulser`, completion and application are bounded pure components. `CoherentSequence.successor` completes its owned old pulser at the actual successor signal. | Carry prior/current counts, fees, registered accounts, previous parameters and frozen identities from the correct branch. Never reuse a completed old update or infer Absent from zero amounts. Productive reward evidence remains the documented finite synthetic profile, not general historical/repeated-epoch validity. |
| SNAP and leadership | `ConwayStake.previewRotationAfterRewards` builds fresh mark from post-reward accounts and pre-body instantaneous stake; old mark becomes set and supplies leadership, old set becomes go. | A registered pool remains in snapshot.pools even with no active stake. With initial registered mark and empty set/go, the second rotation puts that pool into go. Zero rewards, zero production and empty bodies do not preserve the empty-go assumption. |
| Non-myopic history | `ConwayNonMyopic.completeSupplied` preserves finite raw binary32 data and separately rounded decay/add/subtract. `generateForFrozen` derives only an empty go-pool map; nonempty generation refuses. | Establish a pinned-domain exact likelihood generator or separately authenticated supplied likelihoods before supporting nonempty go. Native binary64 pow/log followed by Float rounding is not proved equal to Java Math/StrictMath. Normalized equality is weaker than raw stored-bit parity; neither tolerances nor normalized comparison may replace raw history identity. |
| TICK/RUPD ordering | `ConwayEpochBoundary.freezeAfterBoundary` retains the pre-tick calculation environment while separately binding successor application epoch/context. | Late successor RUPD may use the previous go/count/history even though the selected state has new go/counts. A later ordinary freeze uses the selected state instead. Preserve actual slot, clock epoch, pre-tick environment, NM history and completion/allocation identities explicitly. |
| Empty governance | `ConwayEmptyGovernance.applyBoundary` consumes an old completed DRep/RATIFY state, rolls current to previous parameters, and creates fresh pulsing work. | FreshPulsing is not an old completion. Repeated operation needs bounded DRep pulse/force plus the supported RATIFY completion, with account/delegation/pool/treasury/enactment provenance. Empty proposals do not establish completed old work. Pool reaping, pending parameters, donations and unsupported governance actions remain separate blockers. |
| Parameter roles | Existing composition permits different full previous/current bytes only where its supported immutable reward projections agree. Governance replaces previous with current and resets future bookkeeping. | Define a checked post-boundary parameter-role result, preserve full source bytes and distinguish frozen previous-role reward inputs from new execution parameters. Existing no-update bookkeeping is not general parameter enactment. |
| Plutus environment | `PlutusEnvironment.bind` commits to the base environment ID, parameter source, genesis/time and network; `ClusterTransition.withPlutus` requires that exact base. | Rebuild the execution environment against successor parameter roles and epoch identity, even when V3 costs are unchanged. Bind fees, prices, tx/block units, collateral limits and actual time geometry together. `prepareSyntheticSuccessorBlock` and `ConwayStake.prepareSyntheticSuccessor` explicitly reject Plutus; rebinding alone cannot enable them. |
| Atomic publication and ingress | One-boundary composition carries header/nonce/certificate/ledger/stake/reward/governance/NM in one candidate and undo receipt. `SyntheticBoundaryState.atBoundary` and `CoherentSequence.successor` refuse a second composed boundary. | Compose all checked outputs into one successor candidate, then fence publication and rebuild pool eligibility/evaluation with a new full StatePin. Rejected/unsupported crossings must leave every committed component unchanged. Count the incoming issuer only after the full block is accepted. |
| Rollback, compaction and recovery | Whole-state undo preserves branch-bound frozen objects; existing opaque synthetic recovery retains historical freeze/boundary provenance. | Keep bounded provenance through repeated crossings, including freezes inside the retained suffix and late pre-tick freezes. Ordinary replay has new revision-derived identities. Existing opaque handoff is not a general durable codec or permission to omit new state. |

## Smallest implemented slice

`ledger/src/test/scala/lab/ledger/RepeatedEpochInvariantSuite.scala` uses existing pure APIs for at most three supplied, empty-body ledger/stake transitions. It does not invoke the guarded coherent successor coordinator, a node or a native helper. It adds six invariants:

1. Two rotations move a registered zero-stake pool into go; NM generation then rejects despite zero account rewards and zero production.
2. A late second successor's RUPD freeze retains pre-tick empty go and an explicit zero previous count, while the selected state has nonempty go and a supplied seven-block previous count. An ordinary subsequent freeze uses the latter and rejects likelihood generation.
3. Explicit conserving synthetic credits of 7 and 11 enter fresh mark, then set, then go with historical snapshot object preservation; the second/third leadership snapshots remain old mark. These supplied credits are not calculated productive rewards or native oracle values.
4. Undo after the second pure transition restores the prior snapshot objects and content identities, increases revision and rejects stale ledger/stake/signal capabilities; replay creates a new preview identity.
5. Empty governance starts fresh pulsing work and rolls parameter roles. Neither that pulsing marker nor the old completion with obsolete parameter roles can be substituted as next-epoch completion.
6. Even unchanged Plutus model bytes require a different epoch-bound execution environment. A stale environment cannot attach to the successor base; creating a fresh binding still leaves synthetic Plutus crossing explicitly disabled.

This is useful additional regression coverage for future composition. It does not expand the runtime's accepted state or transaction domain.

## Next pure contract to freeze

The smallest next production candidate is a bounded completion path for the genuinely empty-account/empty-proposal DRep pulser, with an independently checked reference rule for its completed snapshot and RATIFY/enactment treasury/parameter fields. It should accept the existing fresh pulser and exact source identity, produce an immutable completion, and retain false native/full-epoch flags until independently compared. Do not manufacture OldDRep.Complete by copying the previous epoch's object. This narrowly solves one governance dependency; it does not solve nonempty-go NM or productive-validator readiness.

Before a repeated coordinator is designed, main needs explicit contracts for: (a) old and new monetary/DRep phase ownership; (b) exact current/previous parameter-role rollover; (c) pre-tick versus successor calculation/application views; (d) raw-bit NM history and generation authority; (e) successor Plutus environment plus header eligibility context; (f) one atomic commit/undo/recovery identity and ingress revalidation. New pure components must not accept a caller boolean claiming those prerequisites hold.

## Evidence limits retained

The recorded reward comparison covers eleven finite cases with five credentials, two pools, rho=tau=a0=0 and nOpt=k=1; the recorded boundary comparison covers seven supplied cases and excludes governance/NM and coherent successor execution. See [reward evidence](synthetic-reward-native-comparison.md), [boundary evidence](synthetic-boundary-differential.md), [raw-Float limitations](conway-non-myopic.md), and [one-boundary composition](synthetic-boundary-composition.md). The existing one-boundary empty-go case delivers no member-account rewards. None establishes repeated productive native epochs.

Verification uses only isolated offline Scala tests with a private cache, no network, at most two CPUs/two GiB and no additional swap. Exact command/results and log digest are retained in the worker's local Markdown handoff. No live/native job, public push, shared interface edit or change to epoch/empty-go guards is part of this increment.

## Direct source entry points

- [SNAP construction and pool-domain retention](../ledger/src/main/scala/lab/ledger/ConwayStake.scala): `fromActive`, `previewRotationAfterRewards`, `previewRotation`.
- [Boundary and late-freeze ordering](../ledger/src/main/scala/lab/ledger/ConwayEpochBoundary.scala): `preview`, `freezeAfterBoundary`.
- [Non-myopic generation refusal](../ledger/src/main/scala/lab/ledger/ConwayNonMyopic.scala): `generateForFrozen`, `completeFrozen`.
- [Fresh governance work and old-completion gate](../ledger/src/main/scala/lab/ledger/ConwayEmptyGovernance.scala): `applyBoundary`.
- [Plutus environment source binding](../ledger/src/main/scala/lab/ledger/PlutusEnvironment.scala): `bind`; [ledger attachment and successor refusal](../ledger/src/main/scala/lab/ledger/ClusterTransition.scala): `withPlutus`, `prepareSyntheticSuccessorBlock`.
- [One-boundary component gate](../app/src/main/scala/lab/SyntheticBoundaryState.scala): `atBoundary`; [owned coherent successor](../app/src/main/scala/lab/CoherentSequence.scala): `successor`.

## Verification result

54 tests passed across RepeatedEpochInvariantSuite, ConwayEpochBoundarySuite, ConwayNonMyopicSuite, ConwayEmptyGovernanceSuite and PlutusParametersSuite. After strengthening the stale-signal assertion to require its specific rejection reason, all six new tests passed again. Ledger test formatting/checks passed. No capture-dependent tests or native helpers were selected.

Regression log SHA256: `dc7caa36bbde3c18926003743035c66cf44c8fd408a80accf67b75bbec48c984`. Final six-test log SHA256: `d6c039775eeb8601651b77d4bbc0393f72c5c4504d96e0aaca60b380ba3ee68a`. Logs and the bounded runner remain private; this document retains only Markdown results and digests.
