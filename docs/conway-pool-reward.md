# Supplied frozen pool entitlement

`ConwayPoolReward.calculate` computes pool reward intermediates and a leader
reward for one pool from checked frozen inputs. It is pure and unpublished;
go-snapshot provenance remains supplied and unverified.

## Bound inputs

`ConwayRewardStart.decodePoolParameters` accepts the scoped canonical projection:

```text
["conway-pv9-reward-pool-parameters-v1", 9, 0,
 rhoN, rhoD, tauN, tauD, a0N, a0D, nOpt]
```

This is an alternative full reward parameter projection, not a patch over an
already frozen input. Existing `decodeParameters` remains allocation-only.
Both decoders return checked parameters for `freezeForAllocation`. Original
bytes, including a0 and nOpt, are bound to the frozen identity before allocation.
The pool calculator rejects the older projection with no pool parameters.
This is not a native PParams codec or proof of previous-parameter provenance.

Rho/tau remain reduced unit rationals; a0 is a reduced nonnegative rational
with uint64 numerator and positive uint64 denominator, and may exceed one.
nOpt is positive Word16 (1–65535). The existing byte, depth, item, canonicality
and PV9.0 restrictions apply. The calculator requires exact frozen/allocation
identities and their mutual binding, plus a 28-byte pool id in frozen go.
There are no loose pool, circulation, stake, block-count or parameter overrides.

## Equations and floors

The pinned source is commit `f649f9751074d2ab3de033fc3912f29c9862c1f5`:

- [Rewards.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rewards.hs),
  `mkPoolRewardInfo` (331–393), `mkApparentPerformance`, and
  `calcStakePoolOperatorReward`.
- [SnapShots.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/State/SnapShots.hs),
  `maxPool'` (119–138).
- [PulsingReward.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs),
  `startStep` supplies frozen go, previous blocks and circulation; `circulation`
  subtracts reserves from maximum supply.

Conway fixes d=0. Let r be the derived available reward pot, C circulation,
A frozen active stake denominator, S pool stake, O self-delegated owner stake,
P pledge, b pool blocks, and B total previous blocks.

```text
C = frozen maxSupply - frozen reserves
sigma = S/C; sigmaA = S/A; ownerShare = O/C; pledgeShare = P/C
z0 = 1/nOpt; s = min(sigma,z0); p = min(pledgeShare,z0)
eligible = P <= O
maxPool = floor(r/(1+a0) * (s + p*a0*(s - p*(z0-s)/z0)/z0))
maximumReward = if eligible then maxPool else 0
beta = b/max(1,B)
apparentPerformance = if S == 0 then 0 else beta/sigmaA
poolReward = floor(apparentPerformance * maximumReward)
leaderReward = if poolReward <= cost then poolReward
               else cost + floor((poolReward-cost) *
                    (margin+(1-margin)*ownerShare/sigma))
```

**Apparent performance is not capped at one.** It differs from the capped
performance used for reserve contribution in reward-start allocation. The
maximum-pool, apparent-performance and operator floors occur separately, with
exact BigInt fractions between them. One test has maximum reward 67, apparent
performance 3, pool reward 201 and leader reward 110; combining the first two
floors would incorrectly produce 203.

## Missing, zero and bounded cases

A pool missing from go is rejected. A pool present in go but missing from the
block map returns its shares with `production=None`, preserving the native
ranking-only branch. An explicit zero block count returns `Some(Production)`
with zero pool/leader rewards. Boundary count validation now permits bounded
nonnegative entries and retains zeros in context identity; it does not erase
them into missing entries. Negative and aggregate-overflow counts still reject.

Zero pool stake produces zero apparent performance and avoids operator division
by zero. Empty active stake uses the existing snapshot denominator-one sentinel.
Actual active stake must not exceed circulation; owner stake cannot exceed pool
stake. This profile requires positive circulation even for a missing block
entry. Native `%?` can return zero for zero circulation on the ranking branch;
the checked profile deliberately rejects that input instead of claiming full
native-domain equivalence. Pledge failure, zero reward pot, cost above reward
and margin endpoints are handled directly. Coin/count outputs remain uint64;
intermediate products are exact and their sizes are bounded by checked inputs.
One call performs bounded snapshot summation and a constant number of rational
operations, with no work proportional to numerical magnitudes.

## Composition and exclusions

The leader output is a typed pool-identified `Leader` reward. It can be supplied
to `ConwayRewardCompletion` with the same allocation's checked inputs. This does
not imply that leaders alone form the full epoch reward map; omitted member
rewards become unused remainder in that synthetic composition.

The result identity commits to the frozen identity, allocation identity and pool
id. Snapshot state and all parameters are transitively bound. This protects
against substitution and is not native seed admission or ancestry evidence.
Excluded: member distribution, pool ranking/non-myopic updates, pulser execution,
native parity, full epoch validation and runtime publication. No Main, launcher,
checkpoint or runtime guard changes.

## Evidence and validation

Pinned Markdown-wrapped sources remain in private evidence storage. For this packet the raw
SnapShots.hs extracted from the existing local `cardano-ledger-core-1.21.0.0`
archive has SHA256
`1e99f896676bfdf567409b2680927ddd377fc80a78e9b78470c152cae6aab9f7`,
matching the pinned source checksum index. Raw-source and Markdown-wrapper
checksums are different; both are recorded separately in
`.cache/pool-source/evidence.json` outside Git. No public source fetch was needed.

Focused validation covers staged floors, performance above one, pledge equality
and failure, stake/pledge saturation, script-vs-key owner distinction, missing vs
zero counts, empty stake, cost/margin endpoints, malformed projections,
stale/cross-frozen identities, positive circulation/active-stake bounds, exact
large products, 25 small a0-zero cases checked against a simplified independent
formula, and leader composition. Command:

```text
ledger/testOnly lab.ledger.ConwayStakeSuite lab.ledger.ConwayEpochBoundarySuite lab.ledger.ConwayRewardApplicationSuite lab.ledger.ConwayPoolRewardSuite
```

Docker is offline using an existing image, limited to 2 CPUs/2 GiB and this
worktree's private cache. Test log: `.cache/pool-reward-tests.log` outside Git.
No live or blocked fixture, public data fetch, host install or publication.

All 38 focused tests passed (9 pool, 20 boundary/allocation/completion, 5
application and 4 stake). Formatting and diff checks passed.

[Pure member distribution](conway-member-rewards.md) now derives the complete
member and leader maps from the same checked frozen go/pool/allocation inputs,
then uses the existing monetary completion and application path. Native
provenance, pulser execution and runtime authority remain excluded.
