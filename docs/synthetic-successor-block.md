# Internal synthetic successor block

The existing `CoherentSequence` now has package-private successor-block preparation.
It requires an owned current fence, a fresh `SyntheticSuccessor` preview, and an actual
`SequenceInput.Block` with exactly that preview's hash/slot. Preparation never mutates
state. The existing Candidate publication atomically selects boundary plus block, counts
the verified issuer once, and retains one whole-tuple undo receipt. Ordinary public
`prepare` and all CLI epoch guards remain unchanged. No live runtime epoch admission or
native boundary equivalence is claimed; both checkpoint formats still reject these states.

## Checked composition

1. Require owner, predecessor identity/revision, capacity 1..8, exact preview/block identity
   and exact successor epoch. Reward completion/application/SNAP comes only from the
   runtime-owned precursor preview; no caller supplies monetary completion or registration changes.
2. Derive the successor certificate window and registrations from **old mark** leadership.
   Preserve genesis/KES parameters, exact predecessor point/counters and original header
   bytes. Verify OpCert and KES through the existing implementation.
3. Derive the successor nonce context with unchanged geometry and window. Tick before VRF
   verification, preserving the original pre-boundary nonce state in the undo receipt.
   Verify leader eligibility against old-mark fractions and the ticked epoch nonce.
4. Privately build the post-boundary ledger environment with unchanged parameter objects,
   successor epoch and reward-adjusted fees; run the existing body checks there. The
   resulting BlockCandidate is fenced to the **original** ledger, not an exposed intermediate.
5. Select post-reward accounts and rotated snapshots, then update instantaneous stake from
   the accepted body's exact UTxO. SNAP therefore sees pre-body UTxO, while current stake
   sees the accepted body. Privately derive post-boundary RUPD before publication.
6. Atomically publish all components and one verified issuer count. A bad certificate,
   VRF, eligibility, acquisition/body commitment or ledger body leaves everything unchanged.

Package-private `prepareSyntheticBlock` permits subsequent blocks in the selected epoch
using its owned certificate/nonce/leadership context. Certificate branches append a checked
step incrementally, retaining original before/after objects across mixed-context receipts;
they do not replay a prior epoch under the new registration context. Undo and compaction
retain those checked steps and exact frozen capsules.

## Actual-slot RUPD ordering

The pinned TICK rule captures `bprev` and `es` before NEWEPOCH, then feeds them to RUPD
with the actual slot and the post-NEWEPOCH reward phase. `freezeAfterBoundary` accordingly
creates a **new** frozen capsule with successor timing and pre-tick go, snapshot fees,
previous counts, accounts, reserves and original parameters. Early first blocks remain
Absent; start-window blocks create Pulsing; late first blocks force completion at their
actual slot. There is no fabricated earlier signal and no reuse of the completed old
epoch's cursor. Subsequent signals use that frozen capsule until the next boundary.

The frozen capsule additionally binds the selected application epoch, stake context,
post-boundary reserves and rotated previous counts. These fields check later application
without replacing the preserved calculation inputs. They are bounded immutable components,
not a recursive predecessor State or receipt history. The capsule identity commits to the
boundary preview, application context, actual slot, window and original reward parameters/globals.

## Scope and fences

All seven no-effect assertions remain mandatory: pool reaping, refunds, governance,
enactment, donations, parameter rollover and non-myopic effects. They are synthetic
assumptions, not native-state proofs. The coordinator now also rejects reward ASC/security
globals inconsistent with its checked header context. Its supplied reward timing window
remains explicitly synthetic; it is not asserted to be an authenticated network global.
Parameter objects and reward profile do not roll over at this internal boundary.

Boundary plus block consumes one coordinator/ledger revision. Undo consumes one revision
and restores original epoch context, ledger/fees/UTxO, stake/accounts/snapshots, nonce,
certificate counters, reward phase/cursor/origin/pots and counts atomically. Candidate/fence
owner+identity+revision checks reject foreign, stale and sibling capabilities, including
when monetary values coincide. Compaction changes tuple attribution but preserves the
bounded frozen capsule. Replaying a newly started freeze after undo intentionally produces
a new identity; already accepted historical capsules restore exactly.

The persistence contract in [coherent-synthetic-rewards.md](coherent-synthetic-rewards.md)
now explicitly covers historical freezes that start **inside retained suffixes**, including
after undo and compaction. Future controller-authorized restore needs bounded authenticated
historical inputs and fresh owners while preserving historical identities; ordinary replay
is a new branch instance. Authenticated historical boundary provenance is also required when rewards remain
Absent. Ordinary and post-boundary frozen inputs need distinct schemas, and
controller-authorized restoration must validate historical component identities;
final revision minus retained count cannot reconstruct them. These requirements
are detailed in the linked coordinator contract. No storage codec, unbounded
transcript or aggregate memory claim is introduced. Collections remain bounded by their existing 4096-entry limits and retained
block capacity by eight; aggregate allocations still require measurement.

## Verification and evidence limits

Parallel implementation covered header/context transitions and ledger/stake selection;
an independent read-only agent reviewed the combined packet. Focused tests run in cached
`cardano-public-v023-check:local`, `--pull=never --network=none --cpus=2 --memory=2g
--memory-swap=2g --pids-limit=256`, JVM Xmx1200m, using a private cache copy. No host install,
key generation, source download, live cluster or public data fetch occurred.

App tests reuse two captured signed blocks with their exact block/header bytes. Their
**supplied anchor is deliberately synthetic**: epoch length becomes 1530, anchor epoch
becomes zero, and the tick source is selected so the first block's real VRF proof remains
verifiable. Tests exercise early/start/late windows, old-mark eligibility, ticked nonce,
bad KES with a matching preview, foreign/stale/sibling rejection, single issuer count,
body progression, cancellation, whole-tuple rollback/replay, successor compaction and
both codec refusals. Reconstructed snapshot content is compared by values, while undo
preserves the exact historical snapshots. Separate core tests use the existing pinned
public header; ledger tests include nonzero reward application and invalid body/fee bindings.
These are implementation tests, **not a native boundary differential**.

Captured coordinator tests require `STAKE_SEQUENCE_EVIDENCE=/evidence` and
`STAKE_WINDOW_EVIDENCE=/window`; existing sequence compaction tests also use
`COHERENT_WINDOW_EVIDENCE=/window`. The sources are the existing read-only directories
the private three-block sequence and four-original short-window captures. No captures are committed. The focused
commands are:

```text
core/testOnly lab.header.PraosCertificateStateSuite lab.header.PraosNonceEvolutionSuite lab.header.PraosEligibilitySuite
ledger/testOnly lab.ledger.ConwayEpochBoundarySuite lab.ledger.ConwayRewardPulserSuite
app/testOnly lab.SyntheticSuccessorBlockSuite lab.CoherentSyntheticEpochSuite lab.CoherentStakeSuite lab.CoherentSequenceSuite
```


Final focused result: **75 tests passed** (19 core, 30 ledger, 26 app), including 9 new
tests for this increment. `scalafmtAll` and `git diff --check` passed. Independent read-only
review approved the restricted in-memory synthetic scope with no remaining blockers.
Local run evidence is `.cache/synthetic-successor-tests.log` and
`.cache/synthetic-successor-test-report.json`; these ignored files are not repository fixtures.

The follow-up [bounded recovery model](synthetic-recovery-model.md) supports internal
controller-authorized opaque handoff with historical boundary/freeze provenance. It
does not add durable serialization or relax the runtime/CLI validation limits.
