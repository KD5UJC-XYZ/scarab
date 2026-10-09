# Unpublished Conway boundary preview

`ledger/ConwayEpochBoundary.preview` adds a pure, bounded boundary subrule preview
after the atomic stake coordinator. It has no runtime commit/select operation,
ledger state constructor, node command, persistence adapter or CLI switch.
The existing same-epoch runtime guards remain in force.

## Input and ordering contract

`Context` binds an explicit predecessor tuple identity, stake state and revision,
exclusive owner identity, supplied treasury/reserve/fee pots and maximum supply,
and previous/current issuer block maps. Pots and counts are bounded unsigned
integers; tracked balances, account/pool deposits, UTxO and pots cannot exceed the supplied
maximum. This is a partial tracked-supply check, not a claim that omitted epoch
state has zero value. `Signal` binds the exact context, revision, incoming header
hash and slot in exactly the next epoch. Gaps, same-epoch signals, overflows and
foreign or stale capabilities fail closed.

Reward phase is explicit:

- `Unknown` is unsupported. Missing export fields or JSON null must not become
  zero rewards.
- `Absent` needs a context-bound `suppliedAbsent` assertion and evidence identity.
  There is no native importer or authenticity check for that assertion; synthetic
  tests supply it explicitly. A future native adapter must establish actual
  absence from admitted state.
- `Pulsing` carries immutable frozen start inputs, but preview refuses it. There
  is no native pulser completion algorithm in this packet.
- `Completed` carries a `syntheticComplete` effect: frozen input identity, typed
  member/leader reward sets, signed treasury/reserve/fee deltas, and checked
  post-application account balances and pots. It is an explicit algebra input,
  not an entitlement calculation or native monetary-conformance witness.

The complete-effect constructor requires exact conservation:
`deltaTreasury + deltaReserves + deltaFees + sumRewards = 0`, then checks exact
conservation again after application to bounded account balances and pots. Reward
entries with equal type and pool cannot differ only by amount: the pinned Haskell
`Reward` ordering identifies set entries by type and pool. PV9 aggregation sums
the accepted set. Digest ordering is internal and is not native Haskell serialization or ordering. Unregistered-recipient redistribution is explicitly unsupported;
it is not silently discarded or sent to an invented destination. Pool membership,
registration, delegation and deposits do not change. Key and script credentials
with identical hashes remain separate accounts.

Conservation alone does not establish native reward calculation. For example, the
synthetic test supplies snapshot fees of 8 and `deltaFees = -5`; the pinned native
reward calculation uses the negative snapshot fees for that delta. This preview
deliberately validates supplied effects without implementing that calculation.
`syntheticComplete` compatibility checks also do not prove branch ancestry.

Only after applying the effect does a narrow package-private
`ConwayStake.previewRotationAfterRewards` build fresh mark with post-reward
balances and the post-reward fee pot. It reuses `previewRotation`, never mutates
the prior context, and returns no altered stake state. Old mark becomes set, old
set becomes go, and **pre-transition old mark** remains the leadership snapshot.
Historical snapshot identities and original objects are preserved.

Current issuer counts rotate to previous, and current becomes empty. The incoming
issuer is deliberately not counted: the enclosing coordinator must count it only
after the entire incoming block is accepted. Neither a successful preview nor a
rejected preview changes the input maps. This packet supplies no block-acceptance
capability and no second rollback store.

## Frozen reward-start inputs

`freeze` retains the supplied pre-tick context, go snapshot, snapshot fees,
previous parameter bytes, reserves, maximum supply, previous issuer counts and
registered accounts. It also records the observed start slot, stability window,
epoch and source tuple identity in its digest. Bounded original previous-parameter
bytes remain opaque: no reward parameter semantics are inferred. Frozen values
are not reconstructed from the later boundary's balances or rotated counts.

Timing follows the pinned RUPD inequalities: at or before first-slot plus the
window is too early; after that through first-slot plus twice the window starts
or pulses; strictly after the second threshold forces completion. This classifies
timing only. No phase is progressed by the helper. Start signals must be in the
supplied pre-tick epoch, and freezing before the start threshold is rejected.

The preview separately retains the boundary's **pre-tick** context as
`preTickRewardEnvironment`. This preserves the TICK dependency for a later RUPD
adapter after NEWEPOCH; it is not the rotated result. Synthetic effect binding
checks owner, fixed stake context, start epoch, unchanged maximum supply/reserves/previous counts, and revision/slot ordering. It does
not authenticate source lineage, prove branch ancestry after rollback/fork, or prove native reward-start execution. Those
remain requirements for a future admitted-state adapter.

## Explicit incompleteness and evidence

Every result reports `published=false`, `epochTransitionValidated=false` and
unresolved native reward conformance, pool reaping, governance, donations,
parameter rollover and header/block acceptance. Automatic epoch effects are not
proved absent by a lack of new transactions. There is no nonce reseeding, header
eligibility context switch, cross-epoch publication or checkpoint format change.

Focused synthetic tests cover changed balances/fees before SNAP, exact signed
conservation and unsupported cases, distinct key/script rewards, frozen inputs,
S/S+1/F/F+1 timing, count rotation and input stability, stale/foreign contexts,
epoch gaps, bounds, deterministic identities, and nonzero old-mark leadership
with empty fresh stake. The existing stake suite remains unchanged.

Sources are retained primary excerpts pinned to ledger commit
`f649f9751074d2ab3de033fc3912f29c9862c1f5`: Conway `Rules/NewEpoch.hs`, Shelley
`Rules/Tick.hs`, `Rules/Rupd.hs`, `Rules/Snap.hs`, Shelley `Rewards.hs`, and core
`Rewards.hs`. The source excerpts and checksum bundle remain in private evidence storage.

Only focused offline Docker tests run, limited to two CPUs and 2 GiB with a
private worktree cache. No live fixture, native conserving-fixture capture,
reference-state mutation, key generation, signing, public fetch or full suite is
part of this packet.

Validation passed all 12 focused tests: 8 boundary tests and 4 existing stake
tests. Formatting and diff checks passed. Independent read-only review found no
blocker for the explicitly synthetic, unpublished scope. The follow-up includes
known pool deposits in the tracked-supply bound and rejects changes in frozen
maximum supply, reserves or previous counts. The log is
`.cache/epoch-boundary-tests.log`, outside Git.
