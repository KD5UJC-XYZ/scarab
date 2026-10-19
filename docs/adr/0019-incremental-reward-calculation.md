# 0019. Rewards advance through the reference's resumable pure machine

- **Status**: Accepted; supersedes only D8 of [ADR 0009](0009-ledger-mirror-step-2.md).
- **Date**: 2026-10-19
- **Issue**: [#135](https://github.com/KD5UJC-XYZ/scarab/issues/135)
- **Design**: [incremental reward calculation](../design/reward-pulser.md)

## Context

ADR 0009 accepted the mirror design's D8: retain an eager reward calculation and represent a
pulser as its result plus a prefix counter. Profiling shows that the eager calculation still
concentrates hundreds of milliseconds in the first tick after `4k/f` on a large Preview workload.
An eager result with a progress counter does not remove this pause. The owner authorised an
incremental implementation under #135.

## Decision

Mirror `cardano-ledger-shelley 1.19.0.1`'s actual `startStep`, `pulseStep` and `completeRupd`
machine. `NewEpochState.ru` holds the single immutable pending state: frozen free variables,
ordered remaining stake and accumulated/recent rewards. Each ordinary pulse consumes at most
`max(1, ceil(active credentials / (4k)))` credentials. Starting captures inputs without evaluating
all members; the call after the final member pulse completes. RUPD and NEWEPOCH synchronously
finish pending work at the same force points as the reference.

Use the existing Haskell `PulsingRewUpdate` layout, with typed codecs instead of opaque pulser
bytes. Imported pending work resumes from its captured inputs. Positive Haskell-Int pulse sizes,
unbounded natural pool counts and captured protocol versions are checked before use. Rewards
retain exact rational arithmetic; no background fiber, additional private pulser or mutable
calculation state is introduced.

## Consequences

- The Haskell wire layout and native V5 sidecar fingerprint remain unchanged. A fixture actually
  rewritten by the pre-change opaque V5 writer is read and resumed without migration.
- Unchanged Haskell API fixtures compare every monetary field and intermediate member prefix.
  Imported likelihood histories also compare in full. New likelihood generation remains the
  existing explicit J3.6 gap, so complete local reference-state byte equality is not claimed.
- A sparse chain may provide fewer than the required pulse calls before `8k/f`. Forced completion
  must still finish the suffix synchronously; performance evidence reports this case separately
  and never promises a hard bound that changes reference semantics.
- Invalid imported relative stake is refused with a typed vocabulary error only when an eligible
  member's positive distributable pot requires division by it; harmless zero-pot state is retained.
- Normal completion still reduces all delivered rewards. On 2026-10-19 the owner accepted the
  measured 15.99 ms API median (19.30 ms maximum), superseding the original proposed 5 ms target.
  The owner also accepted the measured tens-of-milliseconds start and sparse forced completion;
  this stage requires no further latency follow-up.
- ADR 0009's other decisions and historical acceptance remain in force.
