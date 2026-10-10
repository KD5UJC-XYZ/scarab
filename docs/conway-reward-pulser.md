# Immutable supplied-input monetary reward pulser

`ConwayRewardPulser` adds bounded start/pulse/force progression to the existing
allocation, pool and member arithmetic. It returns immutable research states;
it is not a native reward update, runtime publication path or native parity proof.

## Frozen inputs and chunk size

`ConwayRewardStart.decodePulserGlobals` accepts a new scoped canonical projection:

```text
["conway-pv9-reward-pulser-globals-v1", epochLength, ascN, ascD, maxSupply, k]
```

k is a positive uint64. The existing globals decoder remains available but lacks
k and cannot start this pulser. The original projection bytes, hence k, enter
the frozen identity before allocation or pool calculation. Existing byte/depth/
item/rational/profile checks apply. The frozen reward timing window remains supplied
and identity-bound; this packet does not derive or authenticate network globals.

Start requires the exact frozen/allocation identities and a complete map of
checked frozen-go pool results. It computes:

```text
pulseSize = max(1, ceil(activeCredentialCount / (4*k)))
```

BigInt arithmetic prevents 4*k overflow. With at most 4096 active credentials and
positive k, the resulting Int chunk size is at most 1024.

The traversal is native credential order: ScriptHashObj before KeyHashObj, then
unsigned hash-byte order within each constructor. Fixed-width lowercase hex
comparison implements the latter. It is not the lexical order of display keys
such as `keyHash-...` and `scriptHash-...`. All active credentials consume a
position, including excluded owners and credentials whose calculated reward is
zero or whose pool did not produce blocks.

## Transitions

Let S = epochFirst + frozen.window and F = epochFirst + 2*frozen.window.

- Start at the frozen observed signal in S < slot <= F creates Pulsing with no
  processed members, even for empty input. A late start at slot > F processes
  all members and returns Complete immediately.
- `pulse(state, expectedId, slot)` requires S < slot <= F and a strictly newer
  signal in the same frozen epoch. It processes at most pulseSize credentials.
- Exhaustion is checked **before** processing. A pulse that consumes the final
  nonempty batch remains Pulsing. The next pulse sees an empty remainder and
  constructs completion. Empty input completes on its first pulse after start.
- `force(state, expectedId, slot)` requires slot > F in the same epoch and
  processes every remaining credential, then completes immediately.
- Signals applied to Complete preserve the completed monetary result while
  advancing the local signal cursor/identity. There is no reward recalculation.

Too-early starts are rejected by the frozen-input constructor; this module does
not model the native SNothing waiting state. The strict signal and expected-ID
checks fence a caller using its current state: stale IDs and repeated/backward
slots reject. Re-evaluating the same old immutable state with its own old ID is
deterministic and permitted. There is no global consumed-token store, atomic
runtime owner, or claimed prevention of replay outside the caller's state chain.

## Shared arithmetic and completion boundary

The whole-map distribution and pulser use the same member arithmetic and
completion path. An internal `Prepared` owns an opaque immutable `Progress`.
Only `advance` can add the next ordered prefix. It checks exact Prepared
ownership and bounded batch size; `finish` requires a complete same-owner cursor.
No helper can construct a calculated Distribution from an arbitrary member map.
Finishing does not recompute every member to validate a caller-supplied map.

Completion retains all prior per-pool remainder, member/leader identity,
4096-combined-entry, global remainder and monetary conservation checks. PV9
registration filtering remains deferred to application. A registration change
during pulsing cannot alter entitlement; current registration determines final
credits and unregistered treasury redistribution.

Each state holds at most the checked 4096-credential traversal and bounded
reward maps. Pulse member work is bounded by the chunk; force is bounded by the
remaining active credentials. Completion still performs bounded whole-map
aggregation. Old state retention is the caller's responsibility. No thread,
timer, I/O, unsafe execution, native fixture, checkpoint or runtime guard changes.

Excluded: recent reward events/provenance, likelihoods/non-myopic update,
native seed admission and parity, full RUPD/NEWEPOCH validation, persistence and
runtime publication. The completed Distribution remains a supplied-input
monetary artifact; its helper's `pulserExecuted=false` does not attest that no
external stepper visited it. This state machine separately exposes its processed
cursor and phase without claiming the full native pulser ran.

## Source and validation

Pinned ledger reference: `f649f9751074d2ab3de033fc3912f29c9862c1f5`.

- [PulsingReward.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs):
  startStep chunk formula, pulseStep pre-work exhaustion check, completeStep.
- [RewardUpdate.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/RewardUpdate.hs):
  RSLP Pulsable instance uses VMap.splitAt followed by foldlWithKey.
- [Rupd.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs):
  strict S/F timing and late start plus completion.
- [Credential.hs](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/libs/cardano-ledger-core/src/Cardano/Ledger/Credential.hs):
  constructor order with derived Ord. Cached vector-map 1.2.1.0 uses ascending
  vector storage/splitting/folding. Source paths and raw-source hashes are in
  `.cache/reward-pulser-source.json`, outside Git.

Focused tests compare chunked and forced completion against whole-map results;
check exact first-batch order, final-batch delay, empty input, huge k, strict
timing endpoints, late start, state/input identity substitution, stale/replayed
signals, opaque progress ownership and completion, and registration changes.
They run with existing stake, boundary, allocation, pool, member, and application
tests in offline Docker, at most 2 CPUs/2 GiB, using a private worktree cache.
Log: `.cache/reward-pulser-tests.log`. No blocked fixture or live action occurred.

## Handoff: native differential planning

This is the final pure-infrastructure packet. Next work should plan a separately
authorized native differential rather than add another pure abstraction:

1. Pin native previous PParams, globals including k/reward timing window, frozen go
   credentials/pools, previous blocks, reserves, fees and supply from one checked
   native environment. Keep provenance separate from these scoped projections.
2. Compare native start intermediates, exact credential order/chunk size, every
   monetary pulse prefix, the exhausted-but-Pulsing state, forced result, and
   completed maps/deltas. Use both key/script same-hash credentials, multiple
   pools, zero rewards, empty input and all S/F boundary cases.
3. Compare application with registration changes using the same frozen inputs.
   Keep event/non-myopic differences explicit; matching monetary values alone
   must not admit runtime epoch transitions.

No differential execution, denied fixture workaround, live cluster, public data
fetch, host install or runtime publication is part of this handoff.

Validation passed all 53 focused tests: 7 pulser, 8 member, 9 pool, 20 boundary/
allocation/completion, 5 application and 4 stake. Formatting and diff checks
passed; independent read-only review approved the final opaque progress boundary.
