# Bounded atomic same-epoch sequence

`CoherentSequence` extends the experimental composition to at most eight retained
original Conway blocks, each containing zero through sixteen supported
transactions. The profile is
`conway-pv9-header11-2-derived-nonce-bounded-sequence-v1`. The existing one-block
`CoherentBranch` API remains available. This is an in-memory restricted state
transition, not full ledger or consensus validation.

## Input and checkpoint provenance

`SequenceInput.Context` binds exactly seven original pre-anchor sources through
the strict `coherent-sequence-context-v1` manifest. They are genesis, tip brackets,
protocol state, ledger state, parameters, whole UTxO JSON and whole UTxO CBOR.
The context excludes candidate blocks and all post-state oracles. The CBOR map
supplies the ledger projection; the JSON map is retained attribution, without a
semantic reconciliation claim. Separate reference exports are unauthenticated
and non-atomic even when their tip brackets agree.

Construction checks the fixed isolated network magic, Conway/PV9.0 parameters,
epoch, certificate seed, nonce seed and supplied registration/stake context.
These inputs are frozen for the whole sequence. Crossing the supplied epoch is
unsupported. The supplied anchor has `scopedAppliedTip = None`; only locally
applied successors acquire a scoped applied tip. A downloaded acquisition
checkpoint cannot construct this state or masquerade as a validated tip.

Each `SequenceInput.Block` owns the original header envelope and block bytes.
Structural extraction requires observed header version 11.2, Conway era index 7,
matching original header bytes, equal body/witness counts, empty auxiliary data
and no invalid-transaction indices. Limits are one MiB per block, 64 KiB per
transaction memo and one MiB of memos in total. For each ordinal the ledger
adapter reconstructs `84 || originalBody || originalWitness || f5 || f6`.
Those memos preserve original body and witness spans; they are not claimed to be
the original submitted transaction envelopes. Structural extraction alone does
not establish body commitments, parent continuity or cryptographic validity.

## Preparation and publication

The runtime owns one Cats Effect `Ref` containing the complete state tuple and
its bounded undo stack. Preparation checks original-byte acquisition and parent
continuity, operational certificates/KES, nonce evolution and the original VRF
proof, eligibility under the derived nonce and frozen supplied stake, then the
restricted ledger block. Every result stays private until publication succeeds.
The tuple contains acquisition, certificate, nonce, eligibility and ledger state.

Ledger transactions execute in original ordinal order. Later transactions may
spend earlier outputs. Any failure discards the whole tentative block. Only one
block candidate and one block undo capability escape; no intermediate
transaction candidates or receipts are exposed. An empty block preserves UTxO
and fees while advancing slot, revision and branch identity. Block slots must
strictly increase. Supported key/native spending remains the existing closed
ledger profile; this does not add Plutus, assets or other ledger rules.

One committed block consumes one monotonic revision regardless of transaction
count. The ledger transition identity binds its predecessor checkpoint, content,
revision and head, the header hash, slot and ordered memos. Equal resulting
UTxO/fees therefore do not make alternate headers or transaction orders share
undo authority. The per-block reference adapter checks derived state afterward:
that block's surviving created outputs semantically, untouched before-state
outputs byte-for-byte, plus the full output key set and fee pot. It is not a
complete sequence oracle: using only the last block receipt can conservatively
reject a prior block's created output whose reference encoding differs. The
initial offline checkpoint exposed only this per-block adapter. The separate
full-sequence adapter described below tracks cumulative surviving creations.

## Rollback authority

Constructors for contexts, blocks, states, candidates, snapshots and fences are
private and provide no case-class copy path. A runtime has a unique owner token;
candidates and rollback fences from another runtime are rejected even when
content and revision match. A snapshot returns state and its fence from one read.
Content identity binds the source context and every composed state; revision is
the separate monotonic guard against returning to earlier content.

`rollbackTo(fence, point)` accepts the supplied anchor or an exact retained
applied point. It preflights revision capacity and folds the runtime-owned undo
stack privately in reverse order. Every ledger undo uses the current revision,
restores the prior branch head and advances revision again. Certificate rollback
restores each receipt's actual predecessor, nonce undo restores its predecessor,
and eligibility restores the retained prior observation. Only a complete fold
replaces the Ref and stack. Current-tip rollback is an explicit no-op; its fence
remains valid. Stale fences and pre-rollback candidates remain stale after
reapplication. There is no history eviction, implicit reanchor or persistence.

## Original offline checkpoint and remaining limits

Public synthetic ledger tests cover dependent transactions, late rejection,
empty blocks, competing header/order identities, rollback and reapplication.
Structural block tests do not claim valid Praos signatures. Retained tests are
opt-in and keep all original private evidence outside Git.

The existing complete one-block positive capture supplies a real key transaction
and whole pre/post ledger observations. The separate freeze capture supplies two
real same-epoch empty-block originals, but no whole UTxO snapshots. Its sequence
tests explicitly bind an in-memory synthetic empty ledger seed; they cannot
establish real multi-block ledger agreement. Evidence from these runs is not
merged into a claimed full reference sequence. No reusable valid competing
Praos-header generator is available, so successful switching between two valid
consensus branches remains untested. Ledger-level competing synthetic branches
and coordinator rejection of mutated originals are separate claims.

No new cluster was launched by the initial offline checkpoint. The smallest proposed live
follow-up uses one fresh supported isolated cluster and a bounded same-epoch
window: capture the full pre-state, admit two independent supported key
transactions, retain every original through a post-state containing at least one
empty block and one actual two-transaction block, then compare full UTxO, fees,
certificate counters and nonce fields. Inclusion of both transactions in one
original must be observed; separate inclusion is a different result. Replay and
rollback to every retained prefix happen offline afterward. Before claiming final
sequence agreement, the observation adapter must track surviving creations across
all applied blocks, compare those outputs semantically, and compare survivors of
the original checkpoint byte-for-byte, with exact keys and fee pot. The run must stay
within eight blocks and the established single-cluster resource/time budget.
This proposal does not establish a valid competing branch, epoch/stake evolution,
durable state, full consensus or public-network behavior.

## Reproducing checks

Run the public checks with `bash scripts/sbtw scalafmtCheckAll check`, then
`python3 scripts/check-public-gates.py` and
`python3 -m unittest discover -s scripts -p 'test_private_cluster*.py'`.
Use the established isolated offline build with private caches. The final local
run passed 999 public Scala tests, 25 public gates and 123 compiler-inclusive
Python guards. The separate opt-in suites passed 65 tests: 28 public reruns and
37 retained/synthetic cases. Focused ledger/input/sequence checks passed 56 tests.

`SEQUENCE_FREEZE_EVIDENCE` selects the retained two-empty-header capture;
`COHERENT_POSITIVE_INPUT` selects the existing complete one-transaction input;
`COHERENT_BRANCH_EVIDENCE` selects the unchanged unsupported Byron checkpoint.
Run `app/testOnly lab.SequenceInputSuite lab.CoherentSequenceSuite` with these
paths to enable their 15 additional cases. Existing one-block regression suites
use their documented retained variables separately. No evidence paths or files
are part of the public repository.

Revision-capacity preflight is source-reviewed. Reaching the uint64 revision
ceiling from a legitimate fresh checkpoint is infeasible for a bounded test;
no constructor bypass or production test hook was introduced to manufacture it.
The sixteen-dependent-transaction test verifies one externally visible block
revision, while overflow and late transaction failures remain checked errors.


The [full-sequence reference observation](private-cluster-sequence.md) adds a
continuous receipt-chain comparator that tracks surviving creations across all
blocks. Its live grouping and endpoint claims are reported separately from the
original offline sequence checkpoint described above.
