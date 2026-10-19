# Incremental reward calculation

**Status: Current.** D15 / [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135), implemented
2026-10-19. [ADR 0019](../adr/0019-incremental-reward-calculation.md) supersedes the eager-only
D8 choice. The required gates and measurements are recorded below and in the compatibility log.

## Problem and scope

Before this change, `ledger/rules/Rupd.scala` called `Rewards.update` on the first tick after the
reward start point, `epochFirstSlot + 4k/f`. That pure call computed all pool and member rewards
before returning.
It created a long individual block-validation step even when the surrounding ticks were short.
The historical approximately 1.1-second observation is workload and machine dependent; the
fresh Preview measurements are in [performance.md](../performance.md#29-reward-update-shaping-135).

The implementation replaces that eager step with the reference's pure, resumable reward calculation. It preserves the
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

[ADR 0019](../adr/0019-incremental-reward-calculation.md) replaces D8 in
[ledger-mirror-step2.md](ledger-mirror-step2.md), including the suggestion of deriving a pulser
from an eager result and progress counter. The runtime performs actual incremental computation;
there is no eager member result hidden behind the prefix. `Rewards.update` remains an eager
convenience for tools, implemented by starting and completing this same machine.

J3.5 of the existing mirror design specifies the common reward vocabulary. Coordinate its typed
`RewardPulser` replacement with this work; do not add a second private pulser beside
`NewEpochState.ru`. [#77](https://github.com/KD5UJC-XYZ/scarab/issues/77) is a compatibility
stakeholder for canonical-state projection/import, rather than a separate runtime-pulser owner.
The earlier `RewardPulser(item: Bytes)` preserved an imported CBOR item and `NewEpoch` refused
imported pending work. Both RUPD and NEWEPOCH now resume that work from its frozen fields.

[#83](https://github.com/KD5UJC-XYZ/scarab/issues/83) owns versioned snapshot evolution. The implemented codec audit checks both
the Haskell reward encoding and Scarab's snapshot fingerprint. A typed
decoder for an existing wire layout need not change those bytes; any actual persisted-format
change requires a version bump, migration and a previous-version read test under
[ADR 0006](../adr/0006-versioned-formats-migrate-on-open.md). Run the restart scenario when storage
code changes. Prove that restart and rollback resume the same prefix with the same frozen inputs.

J3.6 specifies the existing non-myopic ranking gap;
[#129](https://github.com/KD5UJC-XYZ/scarab/issues/129) tracks preservation of the live gap mask. Compare reward-accounting fields independently of that gap and retain its explicit mask.
Full reference-state byte equality also requires real likelihoods and the reference's non-myopic
update; do not claim it from reward-amount equality or fill missing fields with an unlabelled default.

## Implementation and acceptance

1. **Shape and profile (completed).** Pin the reference, retain the measured workload and commands,
   distinguish standalone update from tick and whole-block latency, and record this plan. This original stage was level 0; the implementation below is level 3.
2. **Typed resumable state and reference oracle (implemented).** The common
   vocabulary and codecs execute the unchanged pinned Haskell `startStep`, `pulseStep` and
   `completeRupd` against shared fixtures. Compare frozen inputs, remaining credential order,
   accumulated/recent rewards, completion pots and protocol-version behaviour at every transition.
   Include empty state, zero reward, owner/script credentials, multiple leader rewards to one
   account, registration changes, final empty pulse, forced completion and imported mid-pulse state.
   Check that completing after any pulse partition equals eager reward accounting; do not use
   the old Scala implementation alone as a correctness oracle.
3. **RUPD/NEWEPOCH integration and performance (implemented).** The resumable machine handles epoch
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

The accepted normal-schedule gates make "a small factor of an ordinary block" measurable:

- Through the normal reward interval, full-block p99 and maximum latency are at most twice those
  of the same workload's ordinary-block control, with absolute times and control selection shown.
- Separately report start, normal pulse and normal completion API latency. The original shaping
  target was `max(5 ms, 2 × median ordinary full-block time)`. On 2026-10-19 the owner explicitly
  accepted the measured 15.99 ms normal-completion median (19.30 ms maximum), relative to the
  historical approximately 1.1-second reward update, superseding the proposed 5 ms API target.
  The owner also accepted the measured tens-of-milliseconds start and sparse forced-completion
  costs; the remaining linear reduction requires no further latency follow-up.
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

## Recorded implementation evidence

The runtime is `ledger/rules/Rewards.scala` (`start`, `pulse`, `complete`), called by RUPD and
NEWEPOCH. `ledger/state/Rewards.scala` models `RewardPulseSize`, `RewardBlockCount`,
`LeaderOnlyReward`, `PoolRewardInfo`, `RewardFreeVars`, `RewardAns` and `RewardPulser`; the
Haskell codecs retain the existing four-field `RSLP` layout. The registration key set shares the
immutable accounts tree. Remaining stake uses a persistent ordered map, split at the pulse bound.
The private `SingletonReward` representation enforces one reward while sharing its singleton
`RewardSet` between accumulated and recent maps. Completion widens the covariant immutable map
in constant time instead of allocating a new tree and singleton set for every rewarded account.
The wire codec still encodes one `Reward` per accumulated entry, including imported leader
rewards. This pure optimisation follows the measured completion allocation recorded in
[performance.md](../performance.md). No imperative exception is used.

Fifteen executed unchanged-reference cases retain the initial state plus eleven API operations
per case: 180 encoded-state comparisons in total, and events for normal pulses. They include
positive members, script/owner credentials, version-2/3 aggregation and prefiltering, imported
pool-identifier discrepancies, a count above 2^64, the full positive Haskell `Int` range, and
non-myopic history/empty-map/identity-length cases. See the
[oracle fixtures](../../storage/src/test/resources/rewards-1.19.0.1/README.md). Only local newly
computed likelihood vectors are masked. Codec-write failures fail the comparison.

The old opaque V5 writer rewrote a 20,227-byte pending state to identical bytes. The new reader
loads it, checkpoints a prefix, restores and resumes it, and reproduces the original prefix after
rollback to the immutable earlier state. `RewardSnapshotSuite` retains that regression;
[fixture provenance](../../node/src/test/resources/rewards-pending-v5.md) records the exact writer
and SHA-256. The sidecar fingerprint golden remains unchanged; no persisted-format migration is
introduced.


The five pending-sensitive flat-state regressions now live in `NativeRewardSemanticsSuite`:
reward-start timing, delegated owner pledge, Shelley/Allegra aggregation, captured prefilter versus
registration at application, and the exact mainnet epoch 235 AVVM/reserves reward regression. The
flat adapter remains strict; it no longer completes a pending value merely to fit a flat test
projection. Maintained snapshot fixtures and recorded-data tools use native V5, with pinned
conversion provenance in [their README](../../fixtures/snapshot-v5/README.md). The old V5 pending
regression is already in the current persisted format.

Codec-valid imported relative stake can still be an invalid calculation input. When an eligible
member has a positive distributable pool pot, nonpositive relative stake returns
`VocabError.InvalidRewardRelativeStake` instead of dividing by zero. A zero-stake pool with no
distributable reward remains harmless; owners, filtered credentials and zero-total-stake inputs
retain the reference's early-return behaviour. This guard does not narrow the Haskell wire codec.

The controlled API measurements remove the eager first-call work and reduce total reward CPU,
but normal completion still performs the reference's linear delivered-reward reduction. Its
15.99 ms median exceeded the original proposed 5 ms API target; the owner accepted that measured
result on 2026-10-19. #135 follows the normal main-plus-nightly definition of done, with no
remaining 5 ms follow-up requirement. [performance.md](../performance.md#30-incremental-reward-implementation-135)
records measured scope and limitations, separately from the final recorded block replay.
