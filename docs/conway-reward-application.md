# PV9 completed reward application

`ConwayRewardApplication.applyPv9` applies the account/pot subrule of an explicitly supplied
completed monetary effect. It does not calculate entitlement, complete a pulser, validate a
native reward seed, or publish ledger/epoch state.

The rule follows pinned [IncrementalStake.hs, `applyRUpdFiltered` and
`filterAllRewards'`](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/IncrementalStake.hs):
partition typed rewards using the account registration domain at **application
time**, credit registered accounts, add unregistered rewards to treasury after
its signed delta, and apply signed reserve/fee deltas. Delegation is not the
registration predicate. PV9 keeps all member/leader entries; the Shelley-ignored
summary is empty.

The immutable result retains registered and unregistered typed reward maps,
credited amounts, unregistered credential set and total, post accounts and pots,
and a deterministic application identity. Key and script credentials remain
distinct. Empty reward sets and zero entries retain their recipient identity.
Duplicate reward type/pool pairs with different amounts fail because native
reward-set identity excludes the amount. Historical reward pool hashes need not
name a currently registered pool. Internal digest order is not native encoding.

Checks bound account/reward counts, credential/hash widths, amounts and signed
deltas. Both the completed update and its application must conserve exactly.
Each signed pot result is checked before redistribution; a later unregistered
credit cannot repair negative treasury. Post balances and pots must remain
bounded. The standalone supply bound covers account balances plus these pots;
the boundary context additionally checks known UTxO and account/pool deposits.
Neither establishes complete monetary obligations or native parity.

## Boundary integration

`ConwayEpochBoundary.syntheticComplete` delegates to this rule. `Complete.applied`
and `Preview.rewardApplication` expose the summary, while post balances and fees
feed the existing SNAP preview. Old mark remains leadership.

`contextAtApplication` adds a checked, explicitly supplied `ConwayStake.Context`
for application-time accounts. Its identity is included in the boundary context,
so changing it invalidates old signals/effects. Pool parameters and epoch geometry
remain fixed; account membership, balances, delegation and the corresponding pool
delegator indexes may differ in this supplied view. The existing `context` uses
the original stake context. Frozen start accounts remain untouched. This is an
algebra input, not evidence that registration, withdrawal or deposit transitions
occurred; no changed stake state or runtime context is returned.

The reward-start compatibility check still does not prove branch ancestry.
`syntheticComplete` still accepts arbitrary **conserving** deltas. Native
completion must later derive them from frozen inputs, including native
`deltaF = -snapshotFees`; an earlier synthetic example with snapshot fees 8 and
deltaF -5 is not a native completion vector. Non-myopic state, entitlement math,
pulser progression and the remaining epoch effects are not implemented here.

## Evidence

18 focused tests passed: 5 application, 9 boundary and 4 stake tests. Cases include
mixed registration, registration changed after freezing, multiple member/leader
entries, undelegated accounts, key/script distinction, empty sets, identity,
negative pots, overflow and post-reward SNAP. Docker had networking disabled,
two CPUs, 2 GiB and a private cache. No native/live fixture or full suite ran.

The exact pinned source was inspected directly, alongside the already cached
`cardano-ledger-shelley-1.19.0.1` source archive. Local source-hash evidence and the
test log live outside Git in `.cache/reward-application-source.json` and
`.cache/reward-application-tests.log`.

Independent read-only review found no blocker. Native non-myopic replacement
remains excluded. A valid new signal cannot reuse a completion bound to a
different application-account view.
