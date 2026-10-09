# Owned synthetic reward candidates in CoherentSequence

This first integration increment attaches bounded reward progression to the existing
in-memory coordinator. It does **not** admit a cross-epoch block. It creates an
unpublished successor-epoch candidate so the monetary/SNAP ordering and ownership
contract can be reviewed independently of header, nonce and ledger environment wiring.
The CLI same-epoch guard is unchanged; both durable checkpoint formats still reject
the enlarged stake-bearing tuple. No live cluster or runtime epoch admission is claimed.

## Explicit synthetic model

`syntheticRewardProfile` requires checked original reward parameters and pulser globals,
a positive window with `2*window < epochLength`, and seven explicit assertions:
pool reaping absent, refunds zero, governance absent, enactment absent, donations zero,
parameter rollover absent, and non-myopic effects absent. Missing or active assertions
are rejected. These are **synthetic assumptions**, not evidence that these effects are
inert in a real ledger. In particular an empty native non-myopic map does not establish
that native likelihood updates have no effect. The fixed profile domain binds all seven
accepted assertions; checked parameter/global identities and the window bind its ID.

`createWithSyntheticRewards` is opt-in. It reuses the exact pinned stake seed and requires
an explicit synthetic absent-reward evidence identity. It checks epoch/supply geometry,
bounded pots/counts, tracked supply, and exact equality of the supplied fee pot and ledger
fees. It never infers Absent from missing native reward JSON. Existing factories are
unchanged. There is no API to supply a foreign pulser or completion to this runtime.

## Accepted predecessor and atomic publication

The State identity now includes an optional owned `SyntheticRewards` identity alongside
the existing ledger/stake/certificate/nonce identities. That capsule carries the boundary
owner, profile, pots, previous/current block counts, optional frozen inputs and pulser,
and a compact origin commitment. Accounts and mark/set/go snapshots remain in the owned
stake state; frozen inputs preserve their original pre-tick view and original parameters.
The frozen capsule contains bounded components, not a predecessor CoherentSequence State
or recursive receipt history.

`prepare` first performs the existing certificate, nonce, eligibility, ledger and stake
checks. Reward start/pulse/force is then derived solely from the accepted predecessor and
the checked incoming slot. Start freezes pre-block fees, snapshots, counts and accounts.
Preparing changes nothing. Publication selects the existing block/stake candidate,
copies fees from the committed ledger, increments the **verified certificate issuer**
exactly once, and installs the entire tuple in the existing single Ref modification.
Empty accepted blocks count. Rejected, canceled-before-publication, duplicate and stale
candidates do not add counts or advance rewards. Every count map remains <=4096 entries
with aggregate counts bounded by uint64. Existing retained capacity remains 1..8.

## Successor candidate, without admission

`prepareSyntheticSuccessor(fence, headerHash, slot)` requires the original runtime owner,
current tuple identity and revision, and exactly the successor epoch. It consumes only
the owned reward phase. An owned pulser is completed with the new pure
`ConwayRewardPulser.completeAtBoundary` seam, which uses the actual successor signal;
it never fabricates an earlier in-epoch force slot. The seam drains initial, partial or
exhausted-but-still-Pulsing prefixes and preserves frozen/allocation identities.
An explicitly seeded Absent phase remains absent when no accepted signal started work.

The resulting checked monetary completion feeds existing PV9 reward application, then
post-reward SNAP. Leadership is old mark; current counts rotate to previous and the next
current map is empty. The incoming issuer is **not counted**, because no successor block
has been accepted. The preview retains its pre-tick reward environment. The distinct
opaque `SyntheticSuccessor` reports published/headerAndBlockChecked/epochTransitionValidated
false. `checkSyntheticSuccessor` checks owner and current identity/revision only, never
header validity. There is intentionally no publish method for this type.

## Revisions, rollback and compaction

Reward work has no separate coordinator mutation event. Each accepted block increments
the existing ledger/coordinator revision once; each block undone increments it once.
Preparation, successor preview and freshness checking increment nothing. The pure pulser
has its own local signal revision/identity; this is not the coordinator revision.
Compaction changes anchor/tuple identity without advancing the revision.

Each existing owned receipt stores the complete predecessor State. Undo restores its
reward capsule exactly, including absent/pulsing/completed phase, frozen origin, cursor,
pots/counts and historical pulser identity. Existing stake undo restores accounts and
snapshots while rebinding the increased ledger revision; ledger, nonce, certificate and
context state are restored atomically. Old fences/candidates remain stale after undo.
Rollback across an admitted epoch is not yet possible because this increment never
publishes a successor epoch.

A newly started freeze after undo/replay has a different identity because the frozen
context includes the new revision. This is intentional branch-instance attribution;
equal monetary values do not authorize sibling completions. Replaying from an already
restored frozen prefix preserves its historical identities. Compaction retains that
bounded original capsule even if the originating block falls before the retained anchor.

## Future persistence/header integration contract

No storage is implemented. A future explicit format must reconstruct fresh owners from
an authorized anchor and retained replay, retaining original/frozen branch capsule,
profile/parameter/global originals, pots, accounts, snapshots, counts, reward phase,
cursor and signal/local revisions. Traversal must include zero/excluded credentials and
preserve exhausted Pulsing. Current cursor/last-slot fields cannot recreate historical
pulser IDs, whose hashes chain signals; an authorized bounded anchor restoration must
validate content while preserving those IDs, rather than retaining an unbounded transcript.
Do not assume existing v2 revision-minus-depth parity if later work adds separate mutation
events. Aggregate memory/serialized limits need measurement before any such format.

Next increment must select the unpublished reward/SNAP result into the owned stake and
ledger environment, verify successor headers with old-mark leadership and derived epoch
nonce, accept the block before counting its issuer, and retain a whole-tuple undo receipt.
It must also correctly schedule post-boundary RUPD using the preserved pre-tick environment.
This packet intentionally does not loosen those guards.

## Verification

Focused tests run offline in `cardano-public-v023-check:local`, pull disabled, network none,
2 CPUs / 2 GiB memory / 2 GiB memory+swap, 256 PIDs, JVM Xmx1200m. Build caches are a private
copy in this worktree. Two pre-existing exact-byte capture directories are mounted read-only;
no node/cluster is started and their bytes are not new native reward evidence.
The new coordinator suite covers all omitted-effect rejections, preparation/cancellation,
duplicate publication, actual issuer and fee accounting, pure successor completion and
count/SNAP ordering, foreign/stale/sibling fences, rollback/replay, compaction, empty blocks,
late completion and both checkpoint refusals. Pure pulser tests cover nonzero initial,
partial and exhausted boundary completion plus incorrect identity/epoch/replay rejection.


Final result: **28 tests passed**, 20 across `CoherentSyntheticEpochSuite`,
`CoherentStakeSuite`, `CoherentSequenceSuite`, and 8 in `ConwayRewardPulserSuite`.
This includes 7 new coordinator tests and one new pulser boundary-completion test.
`scalafmtAll` and `git diff --check` passed. Independent read-only review found no blockers;
its null-input and two-retained-suffix follow-ups were addressed and tested.

The evidence-enabled coordinator suites require **both** `STAKE_SEQUENCE_EVIDENCE` and
`STAKE_WINDOW_EVIDENCE`; the existing sequence compaction suite uses
`COHERENT_WINDOW_EVIDENCE`. This run mounted the already captured directories
`/home/<user>/cardano-sequence-live1-20261009` and
`/home/<user>/cardano-live-runner-live2-20261009` read-only, respectively. Without the first
environment variable only the public synthetic profile test runs in the new coordinator
suite; the 8 pure pulser tests have no capture dependency. No captures were committed.
The exact test commands after formatting were:

```text
app/testOnly lab.CoherentSyntheticEpochSuite lab.CoherentStakeSuite lab.CoherentSequenceSuite
ledger/testOnly lab.ledger.ConwayRewardPulserSuite
```

The successor completion seam follows the retained pinned Conway `NewEpoch.hs`
Pulsing/Complete dispatch (`completeRupd`, then `updateRewards`). This is source-informed
composition, not a new native boundary differential test. This packet does not change the
separately recorded finite synthetic monetary comparison claim.
