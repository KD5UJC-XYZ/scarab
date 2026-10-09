# Supplied frozen reward completion equations

`ConwayRewardCompletion` constructs the monetary part of a completed reward
update from explicit frozen allocation inputs and supplied reward results. It is
pure and unpublished. It does not compute entitlement, run or finish a pulser,
prove branch ancestry, or replace non-myopic state.

`checkedInputs` binds the exact `ConwayEpochBoundary.Frozen` identity and checks:

- Supplied snapshot fees equal the frozen snapshot fee value.
- Reserve contribution (`deltaR1`), reward pot, treasury allocation and fees are
  bounded nonnegative coin values; reserve contribution cannot exceed frozen
  reserves.
- Reward pot plus treasury allocation equals snapshot fees plus reserve
  contribution, with bounded aggregate arithmetic.

These are consistency checks on **supplied** allocation values.
[ConwayRewardStart](conway-reward-start.md) can now derive those values from
checked parameter/global projections bound to the frozen identity, using the
source's exact rho/tau/eta allocation equations. Native input admission and
pulser reconstruction remain outside both constructors.

`complete` binds the checked input identity, accepts at most one typed member
reward per credential plus typed leader reward sets, and combines them with the
shared PV9 shape/set-identity checks. Type/pool identity is preserved; conflicting
amounts for the same identity are rejected. Reward totals cannot exceed the
frozen reward pot. It then derives:

```text
deltaR2 = rewardPot - sumRewards
deltaR  = -deltaR1 + deltaR2
deltaF  = -snapshotFees
deltaT  = frozen treasury allocation
deltaT + deltaR + deltaF + sumRewards = 0
```

This follows `completeRupd` in pinned
[PulsingReward.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs).
The retained source excerpt and checksum index remain in private evidence storage. The preceding source
allocation sets the available reward pot after the treasury allocation. Native
completion also updates non-myopic state and reward events; this module does not.

`ConwayEpochBoundary.completeFromFrozen` applies the derived effect through the
existing application-time recipient filtering and pot checks. It feeds the
result into the existing post-reward SNAP preview. The effect and preview
identities include the entire completion identity: two allocations that happen
to yield identical final deltas are still distinguished. Current account
registration controls credits and unregistered treasury redistribution; frozen
membership remains untouched.

The older `syntheticComplete` remains available for explicit conserving-delta
algebra tests and is not native completion. The new constructor guarantees the
equations above, but supplied member/leader amounts and frozen allocations still
need independent native evidence. No runtime epoch guard, CLI, checkpoint or
publication API changes.

Focused cases cover unused, entirely unused and fully allocated reward pots;
fee snapshot mismatch; stale input identities; malformed roles; duplicate reward
identities; coin/aggregate bounds; rewards exceeding the pot; insufficient fees
at application; owner mismatch; and completion identity despite equal final
deltas. Existing boundary, application and stake tests run alongside these cases
in offline Docker, limited to two CPUs and 2 GiB with a private cache. No live or
blocked fixture is involved.

Validation passed all 23 focused tests: 14 boundary/completion, 5 application and
4 stake tests. Formatting and diff checks passed. Independent read-only review
found no blockers. The test log is `.cache/reward-completion-tests.log`, outside
Git. No native monetary-parity claim follows from these supplied-input tests.

[Pure member distribution](conway-member-rewards.md) now derives the complete
member and leader maps from the same checked frozen go/pool/allocation inputs,
then uses the existing monetary completion and application path. Native
provenance, pulser execution and runtime authority remain excluded.
