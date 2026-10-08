# Independently derived restricted cluster state

`ClusterTransition.scala` adds the pure profile
`conway-pv9-ada-interval-native-replay-v1`. It derives UTxO and fee state from a
checked before-state, original transaction bytes, and a supplied inclusion slot.
No observed post-state is required. `RestrictedReplay`, its source pins and
historical parameter/slot rules remain unchanged.

The branch was created from main `fda92367f45b510a454d935cf9f7e0c158eb40e4`.
It carries the reviewed native-spending dependency as `9664920`, a cherry-pick of
`af22d7b1f8349a891d116fc4550472d7ef92ebc2`. Integrate that packet first; the final
transition commit adds only `ClusterTransition.scala`, `ClusterTransitionSuite.scala`,
this document and an unapplied comparison patch. No application/launcher change
or live run is included.

## API and checked scope

1. `environment(...)` checks Conway PV9.0, positive private network magic, numeric
   fee/size/minimum-output parameters, epoch and 32-byte source digests. Its
   identity binds every supplied parameter, network, epoch, source digest and
   profile. These checks do not authenticate those source digests or derive epoch
   boundaries; parameter provenance and epoch applicability remain caller duties.
2. `checkpoint(env, rawUTxO, fees, slot, attributionDigest)` validates the whole
   supplied state against the closed output profile and constructs a private
   checked `State`. The checkpoint identity binds environment, attribution,
   canonical reference-map framing, original output spans, fee pot and slot.
3. `prepare(state, original, inclusionSlot)` composes every applicable restricted
   transaction check and returns a constructor-restricted `Candidate`. It exposes
   candidate output bytes, fees, slot and identity, but no unchecked state handle.
4. `commit(current, candidate)` verifies checkpoint/environment/content/revision/
   head equality and returns an `Applied` state plus checked `Undo`.
   `applyTransaction` composes preparation and commit in one pure call.
5. `undo(current, expectedRevision, undo)` requires the current transition head
   and content, then restores the exact before UTxO/fee/slot content with a new
   revision. `compareReference(applied, recordedUTxO, recordedFees)` is optional
   and only checks an already-derived result.

This profile supports both ordinary key-input transfers and native-script spends
in successive calls, so a native spend's ordinary key output can be spent next.
Only testnet network-ID 0 scalar ADA outputs with exactly address/coin fields are
allowed. State inputs may use base key/key kind 0, enterprise key kind 6, and
enterprise native-script kind 7; created outputs are key kinds 0/6. Entire
checkpoint closure, including unspent entries, is stricter than the old comparison
code's opaque untouched-output handling. All historical APIs remain unchanged.

Required body fields are 0/1/2 with optional interval fields 3/8. Only native/vkey
witnesses are supported. No datum, attached reference script, reference input,
minting, Plutus, collateral, certificates, withdrawals, governance, explicit
signer field or auxiliary data is admitted. Present witness sets follow the
evaluator's PV9 nonempty/duplicate semantics. Key-only transactions in this new
profile also use those PV9 semantics; this is not a change to historical Coverage.
Any script witness on a key-only spend is an extraneous-script rejection.

## Checks and original-byte authority

Preparation validates slot/interval, bounded body semantics, duplicate/empty
input rules, input resolution, payment credentials, every supplied vkey signature,
required native scripts, original-span minimum outputs, ADA conservation, complete
original witness memo fee/size, output collisions and resulting state limits.
`NativeSpending.check` supplies native credential evidence only; it is never
treated as whole transaction validation. The key-only path likewise requires
verified payment keys and rejects extra scripts before proceeding.

The actual slot is supplied by the caller, must be uint64 and nondecreasing, and
is checked against the original transaction interval. Equal successive slots are
allowed for multiple transactions in one block. The API does not prove inclusion,
block order or epoch membership. A network owner must supply those facts from its
checked acquisition path and manage environment changes at epoch boundaries.

Original body bytes determine Ed25519 verification, transaction ID and created
output IDs. The complete original witness map, including native scripts, tags,
duplicate elements and nonminimal encodings, contributes to fee size. Semantic
projection may omit interval/witness fields only for input/output/fee parsing;
its bytes are never identity or authentication evidence.

New state encoding sorts transaction references and writes canonical map/reference
framing, then copies every output's original CBOR span verbatim. It never uses
`Cbor.encode` to reconstruct original outputs. Undo retains the exact checked
before-state output spans, fee pot and slot. Snapshot container/key framing is a
local deterministic format, not an on-chain ledger root or exact original file.

For optional reference comparison, all UTxO references and the fee pot must match.
Created outputs compare address and coin (reference tooling may re-encode them).
Untouched outputs must match byte-for-byte. A failed comparison never changes the
candidate or influences its derivation.

## Identity, rollback and ownership

State content identity binds environment/checkpoint, UTxO bytes, fee pot and slot.
Revision and head are separate fences; they intentionally do not enter content
identity, so undo can restore the prior content ID while advancing revision.
Transition identity additionally binds the prior revision/head, next content,
original transaction and evaluation slot. Commit checks all those before-state
fences. Undo requires the current head, preventing wrong-branch or non-top undo.
After undoing a dependent transaction, an earlier top undo remains usable with
the new expected revision. After rollback/reapplication, old candidates and old
undo heads remain stale even if state contents repeat.

These are immutable in-memory lineage checks. A pure API cannot make a receipt
globally single-use: two independent holders of the same old state can each
derive a branch. A runtime/store must exclusively own and atomically replace its
current state, sequence transactions and bound retained undo history. No
cross-process locking, persistence, crash recovery or cancellation mechanism is
introduced. Computation is bounded and synchronous; there is no effect runner or
`unsafeRunSync`.

State stores an opaque head hash, not its undo record. Each `Undo` retains one
bounded before snapshot; no recursive state-owned history is constructed.
Candidate retains the bounded before/after snapshots, original transaction and
bounded predicate evidence. Caller-owned vectors of candidates or undo records
must be limited by the runtime.

## Local limits and outcomes

State and transaction inputs are capped at 1 MiB, state entries at 4,096, CBOR
depth at 16 and items at 65,536. Inputs/outputs are capped at 128 each; inherited
native diagnostics cap vkeys at 128 and native witnesses at 32 before deduplication.
Each resulting state is re-encoded and decoded under the same byte/item/depth
limits before a candidate is returned. Fees use a locally bounded uint128 pot;
slots and revisions are uint64. Revision exhaustion and fee overflow reject.
These are local limits, not protocol-consensus claims.

`Failure` separates `Unsupported`, `DecodeRejected`, `Malformed`, `ResourceLimit`,
typed `Rejected(NativeSpending.Error)`, `StaleState` and `InternalFailure`.
Null/broken caller inputs are contained at checked API boundaries; unexpected
nonfatal implementation exceptions remain distinct internal failures. Parser and
profile exclusions must not be displayed as proof of reference-ledger invalidity.
Successful Candidate/Applied values identify this restricted profile and retain
`fullLedgerValidated=false`; they cannot be publicly constructed or copied into
checked success.

## Source scope and comparison patch

The source pin remains cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`; the existing interval, native and
spending documents provide detailed predicate references. The pinned
[UTxO update](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/shelley/impl/src/Cardano/Ledger/Shelley/Rules/Utxo.hs#L564)
removes consumed references, adds transaction outputs and adds the fee to the fee
pot. It also updates instant stake and deposit-related state, which this restricted
projection does not implement. No ticking, rewards, epoch transitions, instant
stake, full UTXOW failure order, consensus or full ledger validation is claimed.

`cluster-transition-integration.patch` adapts the interval and native comparison
functions to bind the captured original transaction, derive a candidate from the
before-state at the actual containing-block slot, and only then compare the
candidate against reference UTxO/fees. Existing return shapes remain usable, but
the comparison profile IDs change to explicit `cluster-derived-...-comparison-v1`
profiles because checkpoint closure is stronger and the implementation now
derives state. The key-only comparison retains its key-only gate; the native
comparison retains its requirement for a consumed script input. Neither wrapper
widens default CLI routing. The direct transition API needs no post-state or
`ClusterTransfer.Context`; `fromContext` is a convenience for that observation
adapter only.

The patch is delivered unapplied. It has been exercised temporarily only within
the isolated worktree; the final source files are restored for owner integration.
No shared application edit is required by this proposal.

## Verification result

`scalafmtAll scalafmtCheckAll ledger/test app/test` passed offline with **168 ledger
tests** (20 new transition tests) and **174 app tests**. Both comparison adapters
were temporarily applied and exercised, including their existing capture/native
regressions. Their formatted diff is retained as the unapplied patch and both
existing files were restored byte-for-byte afterward. Historical replay pins and
behavior tests passed. `git diff --check` and patch applicability checks passed.

Independent read-only review found no remaining blocker. It prompted checked null
boundary handling, native-empty-key/max-size regressions, and clear internal-error
diagnostics in the native adapter. Tests also cover different original witness
maps yielding identical state content but distinct undo heads, nonminimal output
restoration, rollback/reapply ABA, changed parameters/checkpoint attribution,
same-slot dependent transactions and state/fee limits.

The existing Docker image ran with network disabled, two CPUs, 2 GiB memory,
1200 MiB JVM heap, a PID limit of 256 and this worktree's private cache. Logs remain
ignored at `.cache/cluster-transition-final-tests.log` and
`.cache/cluster-transition-integration-tests.log`. No live cluster, host install,
shared-cache write, production credentials or push was performed. Integration
and a bounded live reference comparison remain owner-controlled next steps.
