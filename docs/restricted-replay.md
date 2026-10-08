# Restricted reversible Conway replay

## Exact claim

`RestrictedReplay` composes the project's existing closed Conway PV9 required-key,
strict public Ed25519, value-conservation and memo-byte fee/size predicates into a
bounded immutable ADA transfer UTxO/fee projection. Success is `ProjectionApplied`.
It is not complete `UTXO`, `UTXOW`, `LEDGER`, block or consensus validation.

All inputs resolve from the same current state. The engine receives the original
transaction bytes and one admitted checkpoint, never per-transaction resolved maps
or expected-success flags. Scope is checked by `Coverage`; every supplied witness
is verified against the original body. Each `Right` predicate result is inspected
for its actual satisfied/rejected case. Unsupported mint, scripts and other fields
are rejected by presence, even when empty. Scalar coin encodings only are supported;
empty or zero-quantity multiasset encodings remain unsupported before normalization
can erase their presence.

The profile binds one archived parameter record, protocol 9.0, fixed research slot
3883681, the declared fee-accumulator origin zero, local limits and all listed
omissions. The canonical ASCII `ProfileDescriptor` and its SHA256 are exposed.
Changes to admission, predicate or crypto semantics, limits, identity framing or
omissions require a revised profile descriptor. Profile/content/checkpoint IDs are
project diagnostics, not Cardano state roots or source-authentication proofs.

Missing rules include selected-chain network matching, minimum UTxO and output-value
limits, instant stake, slot/tick/epoch state, parameter updates, scripts, complete
ledger rules and consensus. No node is contacted, no key is generated, and nothing
is signed. No acquired block or unrelated chain checkpoint is relabeled as a trace.

## Pure ownership and operation order

All successful evidence, checkpoints, states, deltas, prepared batches and revision
tokens have private constructors with no `copy` path. No verifier callback or caller
assertion can manufacture success. The code uses immutable owned bytes/maps and has
no filesystem, clock, network or Cats Effect imports.

1. Enforce byte limits and bounded CBOR preflight, then input/output/witness counts.
2. Decode and enforce the closed `Coverage` scope plus scalar-coin output encoding.
3. Resolve spending inputs from current state and require key coverage.
4. Verify all provided signatures using the exact original body.
5. Check value conservation using the same state's value projection.
6. Check fee and size against the same state-derived, exact-output map.
7. Reject created-reference collisions and enforce next-state bounds.
8. Construct an opaque prepared result; commit checks its full starting snapshot.

This is a deterministic local failure order, not Haskell's complete failure order.
A multi-failure mutation is not treated as an independently referenced first-failure
oracle. Unsupported/malformed/resource failures and predicate rejection never expose
a partially committable batch or change an input state.

`applyBatch` is one all-or-nothing local commit, at most 64 transactions, folded in
order over provisional state. Later transactions resolve earlier created outputs.
An empty batch returns the exact input state with no delta and no new revision.
The CLI instead observes each transaction as a separate local commit: an accepted
setup remains applied after the later rejected corpus event.

`prepareBatch` and `commitPrepared` separate the potentially longer pure checking
from revision-fenced application. Content identity excludes revision: undo restores
the exact semantic identity, UTxO output bytes and fee value, while revision increases.
A preparation from before apply/undo cannot commit afterward despite identical
content. Each commit's transition identity also binds revision and prior transition;
undo requires the current branch's top transition and an explicitly current revision.
It reverses per-transaction deltas in reverse order. Reused, wrong-branch and stale
undo reject instead of silently merging state.

Revision fencing is a property of one immutable in-memory lineage. This does not
provide a concurrent store, process ownership, persisted monotonicity, cross-process
fencing or crash recovery. Callers may deliberately fork a pure snapshot. Those
forks do not represent concurrent writes to a durable shared owner.

## Local resource policy

- 1 MiB per transaction and encoded checkpoint/state
- 4096 UTxO entries, transaction inputs and outputs
- 128 provided VKey witnesses per transaction
- 64 transactions and 8 MiB aggregate transaction bytes per atomic batch/CLI trace
- 32 MiB accounted retained transaction/delta evidence per atomic batch
- CBOR preflight depth 12, 65,536 items and 1 MiB string/input bytes
- Nonnegative 128-bit fee accumulator and monotonically increasing uint64 revision
- Read-only CLI trace file at most 20 MiB, including hexadecimal expansion

These are local research limits, not ledger-invalidity rules. Collection lengths
are checked before typed projection and next-map construction. CBOR decoding itself
is bounded before nested parsing; the shared parser retains nested original spans,
so memory can grow by input size times bounded nesting depth. Evidence accounting
covers retained original transactions, spent/created output framing and fixed record
allowance; it is not a serialized durable journal format or exact JVM heap bound.

## Two independently archived traces

The offline projector pins the archive, sequence bytes, parameter bytes, existing
research decoder and source assertions before decoding. It verifies selected archive
members against the retained sequences without extracting arbitrary archive paths.
The initial maps are byte-identical, SHA256
`3403e98e8bbdb656b05ce8082a78a0c60670ed2466696a494c5eff5b7a834896`.

- Value conservation: accepted setup, then genuine `ValueNotConservedUTxO` rejection
- Required keys: accepted setup, then genuine `MissingVKeyWitnessesUTXOW` rejection

Both accepted setups pay 167041 lovelace. Their expected final two output bytes are
extracted independently from archived final states. Runtime-generated final output
maps are compared by reference and exact original output bytes. Both final expected
outputs are independent archived evidence; the fee accumulator initialized at zero
and ending at 167041 is a separate source-derived arithmetic expectation. The raw
state cell `[state][3][1][1][2]` remains explicitly unclassified and is not advertised
as a semantically established fee-pot golden.

`attribution.json` contains provenance and recorded tick context.
`expectations.json` contains archived booleans, failure-source references, final
output spans/bytes and separately classified fee arithmetic. Runtime `.trace.tsv`
inputs contain neither acceptance booleans nor resolved inputs. The archive tick is
`RecordedButNotExecuted`; tick/epoch event rows are unsupported rather than assumed
to be general no-ops.

The selected traces do not provide two successful dependent transactions. Tests
establish genuine setup/rejection ordering, atomic rejection, branch switching,
round-trip undo, original-byte preservation, unsupported cases and ABA fences.
A successful multi-transaction dependent reverse-order undo law remains a future
fixture gate. No success is synthesized by disabling signatures or accepting a
mutated body with stale witnesses.

## Read-only CLI

Dispatch: `restricted-replay <trace.tsv>`.

The first LF-terminated ASCII TSV row contains exactly:

`restricted-replay-v1`, profile ID, fixed research slot, original parameter hex,
original initial UTxO hex, attribution-manifest SHA256.

Zero to 64 following rows contain exactly `tx` and original transaction hex. Blank
rows, comments, expected-success fields, missing/default context and other event
kinds are rejected. The attribution digest identifies supplied provenance; the
arbitrary-file CLI does not authenticate the source or independently fetch its
manifest. The projector and immutable tests establish the shipped packet's origin.

Each stdout event reports outcome, before/after semantic identities and revision.
Final stdout includes exact current output-map bytes and fee accumulator. Rejections
are observed sequentially without changing state. No files are written by the CLI.

Exit codes: 0 if all events applied (including a no-op trace), 1 for predicate
rejection, 2 for malformed/resource/stale input, 3 for unsupported profile, and 4 for
internal failure. Error precedence is 4, 3, 2, 1 when multiple events fail. Shipped
accepted-then-rejected traces deliberately return 1. This is observation, not a
command that is forced to agree with archived expected booleans.

## Verification and staged gates

The 0.22 source snapshot passed all 39 focused tests, the 1,138-test formatted
aggregate and all 30 serial gates in one uninterrupted run. Its fresh archive
subsequently exposed a boundary-test timeout; see [the 0.22.1 correction and
current acceptance](restricted-replay-sort-key-fix.md). The original
[verification record](restricted-replay-verification.md) is retained.

A future effectful revision owner requires an atomic consistent snapshot, serialized
commit/rollback, cancellation boundary and explicit concurrency tests. Persistence
requires real immutable object writes plus an atomic head, checked reopen/replay,
monotonic revisions after rollback/restart and fault injection around every promised
durability boundary. No crash, cancellation, power-loss, restart or durable-ledger
claim is made by this slice.
