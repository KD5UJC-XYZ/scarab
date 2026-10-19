# Incremental reward calculation

**Status: Proposal.** D15 / [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135), shaped and
profiled on 2026-10-19. This document specifies implementation acceptance; it does not enable a
pulser or close #135. The current implementation remains eager.

## Problem and scope

`ledger/rules/Rupd.scala` calls `Rewards.update` on the first tick after the reward start point,
`epochFirstSlot + 4k/f`. That pure call computes all pool and member rewards before returning.
It creates a long individual block-validation step even when the surrounding ticks are short.
The historical approximately 1.1-second observation is workload and machine dependent; the
fresh Preview measurements are in [performance.md](../performance.md#29-reward-update-shaping-135).

Replace that eager step with the reference's pure, resumable reward calculation. Preserve the
attributed rewards, protocol-version gates, exact rational arithmetic and treasury/reserves/fee
accounting. This is scheduling work inside the ledger transition, independent of parallel Plutus
evaluation. It does not parallelise rewards, introduce a background fiber, or authorise a new
public-network run.

## Reference contract

The reference is `cardano-node 11.1.3`, with the already frozen oracle solver plan selecting
`cardano-ledger-shelley 1.19.0.1`. Its source revision is
`4c81e909df7555b6a58a000db8f8f4f89c7acdc4`. Relevant upstream definitions:

- [Rupd.hs:101–152](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Rupd.hs#L101):
  timing and start/pulse/force transitions.
- [PulsingReward.hs:89–237](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs#L89):
  `startStep`, captured inputs and `pulseStep`.
- [PulsingReward.hs:242–283](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/LedgerState/PulsingReward.hs#L242):
  `completeRupd`, merging member and leader rewards, pots and events.
- [RewardUpdate.hs:165–348](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/RewardUpdate.hs#L165):
  `RewardSnapShot`, `FreeVars`, `RewardAns`, `RewardPulser`, its `Pulsable` instance and encodings.
- [NewEpoch.hs:156–164](https://github.com/IntersectMBO/cardano-ledger/blob/4c81e909df7555b6a58a000db8f8f4f89c7acdc4/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/NewEpoch.hs#L156):
  force completion of pending work at the epoch boundary before applying rewards.

Let `start = epochFirstSlot + randomnessStabilisationWindow` and
`force = start + randomnessStabilisationWindow`, with the window `4k/f`.

| Signal/state | Reference transition |
|---|---|
| `slot <= start` | No update. |
| `start < slot <= force`, absent | Start and freeze inputs; do **not** calculate member rewards on this call. |
| Same interval, pulsing with remaining source | Process at most `max(1, ceil(activeCredentialCount / (4k)))` credentials in credential order. |
| Same interval, pulsing with empty source | Complete on this call; the preceding pulse still returned `Pulsing`. |
| `slot > force`, absent or pulsing | Start if necessary, then complete all remaining work. |
| Completed update | Keep it unchanged. |
| Next epoch with pending work | Complete before crediting the update. |

Freeze the previous-epoch blocks, `go` snapshot, previous parameters, reserves, fee pot and
registered credentials at `startStep`. Later registration changes must not alter the captured
pre-Babbage prefilter. From protocol version 7 the reference forgoes that prefilter. Preserve the
pre-Allegra reward-set selection and subsequent aggregate-reward behaviour as well.

## State and ownership

Use Haskell's existing decomposition: `RewardSnapShot` for completion data, `FreeVars` for member
inputs, ordered remaining active stake, and `RewardAns` for accumulated and recent rewards. A
pure `start` / `pulse` / `complete` API returns typed failures; RUPD runs it through its existing
`Ask`, `Stateful` and `Raise` capabilities. Pulse size is a checked positive value, not a runtime
configuration knob. All folds must be stack safe, including forced completion and decoding.

This proposal would replace the eager-only decision D8 in
[ledger-mirror-step2.md](ledger-mirror-step2.md), including J3.5's suggestion of deriving a pulser
from an eager result and progress counter. That change becomes current only with the implementing
PR. Do not compute every reward eagerly and then reveal a prefix: that preserves the original
pause and fails this work's purpose.

J3.5 of the existing mirror design specifies the common reward vocabulary. Coordinate its typed
`RewardPulser` replacement with this work; do not add a second private pulser beside
`NewEpochState.ru`. [#77](https://github.com/KD5UJC-XYZ/scarab/issues/77) is a compatibility
stakeholder for canonical-state projection/import, rather than a separate runtime-pulser owner. Today `RewardPulser(item: Bytes)` preserves an
imported CBOR item and `NewEpoch` refuses imported pending work. The implementation must resume
that work, not discard it and restart from the current ledger state.

[#83](https://github.com/KD5UJC-XYZ/scarab/issues/83) owns versioned snapshot evolution. Audit both
the Haskell reward encoding and Scarab's snapshot fingerprint before implementation. A typed
decoder for an existing wire layout need not change those bytes; any actual persisted-format
change requires a version bump, migration and a previous-version read test under
[ADR 0006](../adr/0006-versioned-formats-migrate-on-open.md). Run the restart scenario when storage
code changes. Prove that restart and rollback resume the same prefix with the same frozen inputs.

J3.6 specifies the existing non-myopic ranking gap;
[#129](https://github.com/KD5UJC-XYZ/scarab/issues/129) tracks preservation of the live gap mask. Compare reward-accounting fields independently of that gap and retain its explicit mask.
Full reference-state byte equality also requires real likelihoods and the reference's non-myopic
update; do not claim it from reward-amount equality or fill missing fields with an unlabelled default.

## Implementation stages and acceptance

1. **Shape and profile (this stage).** Pin the reference, retain the measured workload and commands,
   distinguish standalone update from tick and whole-block latency, and record this plan. This is
   level 0: no runtime behaviour, schema or defaults change; relative documentation links resolve.
2. **Typed resumable state and reference oracle.** Agree ownership with J3.5, implement the common
   vocabulary and codecs, and execute the unchanged pinned Haskell `startStep`, `pulseStep` and
   `completeRupd` against shared fixtures. Compare frozen inputs, remaining credential order,
   accumulated/recent rewards, completion pots and protocol-version behaviour at every transition.
   Include empty state, zero reward, owner/script credentials, multiple leader rewards to one
   account, registration changes, final empty pulse, forced completion and imported mid-pulse state.
   Check that completing after any pulse partition equals eager reward accounting; do not use
   the old Scala implementation alone as a correctness oracle.
3. **RUPD/NEWEPOCH integration and performance.** Enable the resumable machine, including epoch
   jumps, rollback and import/restart. Run `check` plus `e2e`; typed decoder changes also run the
   decoder fuzz gate, and storage changes run `restart`. Record compatibility, the as-built design
   and a changelog fragment. Keep #135 open until implementation acceptance and the required
   nightly evidence pass.

For stage 3, freeze and publish a representative offline window around `4k/f`, the normal
completion tick, `8k/f`, and the next boundary. Include both an active largest Preview-sized
workload and a cheap/zero-member-reward control. Compare matched original blocks and complete
ledger results against the eager baseline, using the same JVM, CPU allowance and input state;
rotate conditions to reduce JIT and host-contention bias. Loading, state hashing and comparison
are outside the timed interval. Report allocation, peak retained heap and total reward CPU too.

The proposed normal-schedule gate makes "a small factor of an ordinary block" measurable:

- Through the normal reward interval, full-block p99 and maximum latency are at most twice those
  of the same workload's ordinary-block control, with absolute times and control selection shown.
- Separately, start, normal pulse and normal completion ticks each target no more than
  `max(5 ms, 2 × median ordinary full-block time)` on the frozen benchmark machine. This is an
  acceptance target to verify, not a hard real-time promise or a result of the current profile.
- Total reward-calculation CPU is no more than 1.25 times eager calculation on the same work;
  no full scan or complete eager member calculation occurs on each pulse.

There is an unavoidable qualification: with too few blocks between `start` and `force`, the
reference must finish the remaining work synchronously. Report sparse-chain, first-block-after-
force and epoch-jump latency separately. Do not claim a universal per-block bound that changes
the reference's forced-completion semantics. Test the required number of regular pulse calls
(`ceil(n / pulseSize)`, plus start and completion) explicitly.

The initial JFR profile points at pool lookups/byte hashing and exact rational arithmetic. These
are optimisation leads, not permission to change reward formulas or introduce mutation. Start
with immutable ordered state and library folds. Any local imperative optimisation needs a
separate measured comparison and written reason under the repository's performance exception.
