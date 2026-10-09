# Owned synthetic reward candidates in CoherentSequence

The initial integration increment attached bounded reward progression and unpublished
successor previews to the existing in-memory coordinator. The subsequent internal
successor-block path is documented in [synthetic-successor-block.md](synthetic-successor-block.md).
It selects a checked boundary and actual block together only through package-private
synthetic APIs. The public prepare/CLI same-epoch guards remain unchanged; both durable
checkpoint formats still reject the enlarged stake-bearing tuple. There is no live
cluster, public runtime admission or native epoch-equivalence claim.

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
header validity. This preview type still has no direct publish method. A package-private successor-block
preparer consumes it with its original fence and exact actual block, verifies header,
nonce, eligibility and body, and returns the existing whole-tuple Candidate type.

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
The internal successor-block increment now preserves one receipt spanning boundary and
block, so its rollback also restores epoch contexts, pre-boundary pots, accounts and SNAP.

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

Future v3 recovery also needs bounded historical-freeze provenance for starts
**within the retained suffix**, not only for a freeze already present at the
anchor. Ordinary replay must create a fresh freeze identity at its current
revision. Restore may preserve authenticated historical identities only after
validating that provenance; deterministic re-execution alone does not grant that
authority. No v3 encoding, restore or durable admission is implemented here.

The internal successor-block increment implements selection, old-mark header eligibility,
ticked nonce verification, body checks and one whole-tuple receipt. Post-boundary RUPD
uses a new clock epoch and the original pre-tick environment at the actual block slot.
See its separate verification and limitations document; public guards stay in place.

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
the private three-block sequence capture and the private four-original short-window
capture read-only, respectively. Without the first
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


### Retained-suffix freeze provenance for future v3

Historical freeze provenance is required for starts **inside the retained suffix**, not
only the anchor capsule. Freeze identity binds predecessor tuple identity and stake
revision. Undo raises revision; compaction rewrites predecessor tuple identities while
preserving the accepted capsule. Consequently ordinary suffix replay can produce different
freeze, pulser and final tuple identities. A future controller-authenticated restore must
carry bounded historical freeze inputs for those suffix starts, establish fresh owners,
validate capsule content and preserve historical identity. Ordinary branch replay remains
a new freeze. Neither pathway may retain recursive State/receipt history. Required future
codec tests include an Absent anchor with a retained freeze after undo and after compaction.
No v3 codec or aggregate allocation measurement is implemented here.


### Historical boundary provenance and restoration limits

Future recovery also needs authenticated historical **boundary** provenance,
even when the reward phase remains Absent and no freeze was created. Boundary
selection embeds predecessor identities and revisions into ledger, stake,
certificate, nonce, epoch-context and reward-origin identities. The final revision
minus retained block count cannot reconstruct those historical revisions after
undo and compaction. Ordinary replay creates a new branch instance.

Ordinary frozen inputs and post-boundary frozen inputs need explicit distinct
schemas: the latter preserve pre-tick calculation inputs while separately binding
successor application context. A controller-authorized restoration seam would
need to validate bounded historical inputs and component identities, establish
fresh owners, and preserve authenticated historical identities. None of these
requirements grants authority to serialized hashes alone. Aggregate byte and
allocation limits must be measured in addition to existing per-collection bounds.
There is no recovery seam or wire codec in this packet; both existing checkpoint
formats continue rejecting the enlarged tuple.
