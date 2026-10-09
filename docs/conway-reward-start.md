# Checked frozen reward-start allocation

`ConwayRewardStart` derives the monetary allocation at reward start using exact
`BigInt` rational arithmetic. It consumes a checked frozen capability and its
expected identity, with no later parameter or global overrides. It returns
checked inputs for `ConwayRewardCompletion`; neither operation publishes state.

## Source and equations

The reference is `startStep`, lines 99–144 of pinned
[PulsingReward.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs).
At that same commit, Conway's
[PParams.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/PParams.hs)
defines `ppDG = L.to (const minBound)`. Consequently this PV9.0 profile fixes
decentralization at zero and does not expose the legacy nonzero-d branch.

```text
expectedBlocks = floor(epochLength * activeSlotCoefficient)
blocksMade     = sum(frozen previous-epoch block counts)
eta            = blocksMade / expectedBlocks
deltaR1        = floor(min(1, eta) * rho * frozen reserves)
grossPot       = frozen snapshot fees + deltaR1
deltaT1        = floor(tau * grossPot)
rewardPot      = grossPot - deltaT1
```

Each floor occurs at the native equation boundary, without floating point or
intermediate truncation. Raw and capped performance are both retained as reduced
fractions. Zero expected blocks fails closed; zero production with a positive
denominator contributes no reserves, while snapshot fees remain available.
Coin/count inputs and aggregate outputs are bounded to unsigned 64-bit values.
Intermediate rational products use arbitrary precision and are bounded in size
by the checked inputs. No loop depends on coin or slot magnitudes.

The retained private excerpt records the original Haskell source SHA256 as
`d351328c783e33552f73ee6cb7435fb5b08e0df9eaab28415990786e00cb3f4e`.
Its Markdown wrapper is a different file, with SHA256
`18a8d13cd5aa2d7c6c2663cdca5e4e6bd495e39ff1292254a3e3455db1c47e16`.
This is source-grounded arithmetic, not a native execution parity result.

## Checked supplied input boundary

The two decoders accept scoped canonical CBOR projections, **not native PParams
or genesis CBOR**. Each input is at most 1024 bytes, with bounded CBOR depth,
items and strings; integers must fit uint64 and unit rationals must be reduced.
The allocation-only array schemas are:

```text
["conway-pv9-reward-start-parameters-v1", 9, 0, rhoN, rhoD, tauN, tauD]
["conway-pv9-reward-start-globals-v1", epochLength, ascN, ascD, maxSupply]
```

Denominators, epoch length, maximum supply and active slot coefficient must be
positive. Rho and tau may be zero. Original bytes are retained and committed to
their typed identities. `freezeForAllocation` binds those identities alongside
the existing frozen state, timing, previous blocks, reserves, go snapshot and
snapshot fees. Global epoch length and maximum supply must match the checked
context. Changing parameters or globals therefore changes the frozen identity,
even when final amounts coincide. The older opaque `freeze` remains available
for supplied-effect research, but cannot authorize this calculator.

Identity binding prevents accidental substitution; it does not authenticate
the supplied projection, prove it represents the native previous parameters,
or establish branch ancestry. Native seed admission remains false. The maximum
supply is bound for context consistency. Security parameter k affects the
native pulse size, which is outside this allocation-only operation.

The additional [pool reward projection](conway-pool-reward.md) includes a0 and
nOpt in the original parameter bytes before freezing. It supports the same
allocation calculation and is required for pure pool entitlement.

## Composition and limits

The returned `completionInputs` carry the derived reserve contribution, reward
pot, treasury allocation and frozen fees through the existing checked completion
constructor. Supplied member/leader rewards can then produce conserving deltas,
application-time recipient filtering and an unpublished post-reward SNAP preview.
See [completion](conway-reward-completion.md) and
[boundary preview](conway-epoch-boundary-preview.md).

Excluded: per-pool/member entitlement, leader reward calculation, pulser
execution, non-myopic state update, native seed admission, full epoch validation
and runtime publication. No CLI, Main, checkpoint or runtime epoch guard changes.

## Validation

All 29 focused tests passed: 20 boundary/completion/allocation, 5 application and
4 stake tests. New cases cover zero production, floors, saturation, invalid and
noncanonical rationals, wrong profiles, global/context mismatch, stale identity,
zero expected-block denominator, exact large-coin products, gross-pot overflow,
and composition through completion into an unpublished preview.

Command: `ledger/testOnly lab.ledger.ConwayStakeSuite lab.ledger.ConwayEpochBoundarySuite lab.ledger.ConwayRewardApplicationSuite`.
Formatting ran with `scalafmtAll`. Docker used the existing local image with no
network, at most 2 CPUs and 2 GiB, and this worktree's private cache. Log:
`.cache/reward-start-tests.log` (outside Git). No live cluster, blocked native
fixture, host installation or public data fetch was performed for testing.

The [scoped pulser globals](conway-reward-pulser.md) additionally bind positive
security parameter k before freezing. Existing allocation-only globals remain
supported but cannot authorize the monetary pulser.
