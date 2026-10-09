# Supplied frozen member distribution

`ConwayMemberRewards.distribute` completes a pure PV9 member/leader distribution
from frozen go and checked pool results, then calls the existing monetary
completion constructor. No runtime state is published and no native provenance
or parity is asserted.

## Input completeness and identity

The caller supplies the exact frozen/allocation capabilities and their expected
identities, plus one checked `ConwayPoolReward.Result` for **every** pool in
frozen go. Missing, extra, miskeyed, null or cross-frozen/cross-allocation results
fail closed. Ranking-only nonproducing pools are required too. No caller-supplied
member stake, reward amount or registration filter is accepted.

Pool constructors are private. Each supplied result binds the same frozen and
allocation identities, pool id and snapshot. The distribution identity includes
the sorted pool identities and completed monetary identity. Traversal order
cannot affect the result. The frozen go snapshot and original scoped parameter
bytes remain supplied, unverified input evidence.

## Member calculation

The reference is pinned commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`,
[Rewards.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rewards.hs):
`calcStakePoolMemberReward` (152–157), `notPoolOwner` and
`rewardOnePoolMember` (282–321).

Each active credential is read once from frozen go and routed through its
snapshot pool. A key credential in that pool's self-delegated owner set is
excluded. A script credential is never excluded by a same-hash key owner. An
owner of another pool who delegates here remains eligible as a member here.

For an eligible member with stake c in a producing pool with stake S,
pool reward f, cost k and margin m:

```text
memberReward = 0                              if f <= k
             = floor((f-k) * (1-m) * c / S)   otherwise
```

This is the native expression with `(c/circulation)/(S/circulation)` cancelled
exactly. Pool calculation already checked positive circulation; no intermediate
rounding is introduced. Every member is floored separately, and zero results
are omitted. Two rewards of 0.8 therefore yield no member entries, not a shared
reward of one. BigInt products preserve precision before the single floor.

PV9 bypasses registration prefiltering. The cached Shelley `Era.hs` defines
`hardforkBabbageForgoRewardPrefilter` as protocol major greater than 6. Member
calculation does not consult frozen or current account registration. This is
essential when registration changes between the snapshot, freeze and boundary.

## Leader collection and completion

Leader collection follows pinned
[PulsingReward.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs)
`collectLRs`. Producing pools contribute their typed leader reward to the frozen
pool reward account, without a PV9 registration filter. Zero leader entries are
retained, unlike zero members. Pools sharing a reward account are combined by
set union. A recipient may have one member reward and multiple leader rewards;
role and pool identities remain separate.

For each producing pool, member total plus leader reward must not exceed its
pool reward. `poolTotals` retains that pool's unused remainder. Nonproducing
pools have no entry in this summary. Whole-map completion checks its existing
4096-entry limit, aggregate coin bounds, reward-pot bound, unused remainder and
signed conservation:

```text
deltaR2 = allocation.rewardPot - totalRewards
deltaR = -allocation.deltaR1 + deltaR2
deltaF = -frozen.snapshotFees
deltaT = allocation.treasuryDelta
deltaT + deltaR + deltaF + totalRewards = 0
```

The result exposes `completed` for `ConwayEpochBoundary.completeFromFrozen` and
the existing application-time registration checks. Registered recipients receive
credits; unregistered amounts go to treasury. Tests compose this through an
unpublished post-reward SNAP preview. The calculation itself does not infer or
validate registration transitions.

## Bounds and limits

Frozen stake and pool maps are already bounded to 4096 each. Distribution makes
one active-stake pass and one pool pass plus sorted identity encoding; it does
not execute a pulser or loop according to coin values. All returned coin sums
and rewards are checked within uint64 through pool and completion checks. Local
working maps contain at most the bounded member and pool counts; oversized
combined output fails the existing completion-entry limit.

This extends [pool reward calculation](conway-pool-reward.md) and
[monetary completion](conway-reward-completion.md). It does not implement pulser
scheduling/continuation, non-myopic updates, native seed admission, ancestry,
native execution parity, full epoch validation or runtime publication. Existing
runtime guards, checkpoint formats, Main and launchers are untouched.

## Validation and source evidence

All 46 focused tests passed: 8 member distribution, 9 pool, 20 boundary/start/
completion, 5 application and 4 stake. New cases cover owner-key/script
distinction, independent tiny floors, zero omission/retention, frozen and current
registration differences, complete multi-pool maps, shared recipients,
misbinding/domain rejection, empty/nonproducing pools, deterministic identity,
and completion/application conservation. Formatting and diff checks passed.

```text
ledger/testOnly lab.ledger.ConwayStakeSuite lab.ledger.ConwayEpochBoundarySuite lab.ledger.ConwayRewardApplicationSuite lab.ledger.ConwayPoolRewardSuite lab.ledger.ConwayMemberRewardsSuite
```

Testing used the existing Docker image offline with at most 2 CPUs/2 GiB and a
private worktree cache. Log: `.cache/member-rewards-tests.log`. Source archive
paths and raw-source SHA256 checksums are in `.cache/member-rewards-source.json`
(outside Git). Cached Rewards.hs and PulsingReward.hs hashes match the retained
pinned source index. No public data fetch, host install, live or blocked fixture
was used.
