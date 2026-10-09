# ADA ingress and volatile pool integration

This implementation is offline and scoped to `isolated-conway-pv9-ada-vkey-v1`.
It does not start an HTTP server, relay transactions, publish chain state, or claim
full ledger validation. `docs/ada-submission-contract-v1.md` remains authoritative.

## Available interfaces

- `lab.submission.SignedTransaction.checked(original)` checks bounded structure
  and retains exact original envelope/body spans, body ID and envelope SHA256.
- `lab.ledger.AdaAdmission.prepare[P](pin, view, original)` returns a private
  candidate containing exact identity, pin, spent inputs, fee/minimum-output
  receipts and the ledger/environment/slot used. The ADA whitelist precedes
  `ClusterTransition.prepare`; admission never commits its hypothetical state.
- `lab.ledger.AdaPool` provides immutable `empty`, `admit`, `expire`, `remove`,
  `move`, `revalidate`, `finish`, and `shutdown` transitions plus bounded statuses
  and eligible exact-byte snapshots.
- `lab.ledger.runtime.AdaIngressBudget` provides a nonqueuing request Resource
  (eight maximum) whose single-use `validate` acquires one of two validation
  permits. Releasing/canceling work releases permits without removing pool state.

## Main-owned integration requirements

No concrete shared `StatePin` signatures were present at the pinned base commit.
The pool and candidate therefore accept type parameter `P`; instantiate it with
main's complete immutable `StatePin`, never a slot/height or ledger ID alone.
Main must freeze that shared model and confirm the `lab.submission` identity
namespace before connecting adapters. No competing StatePin definition is added.

The same app owner gate must capture `(completePin, immutableLedgerView)`, publish
every coherent-runtime mutation, and install every returned pool state. Validation
runs outside the gate. Reacquire it and call `admit` using the CURRENT pool state.
Check the candidate ledger/environment/validation-slot provenance against the
captured pin. A pin mismatch returns Retry and reserves nothing. Preserve every
pin field in the serialized receipt. Main alone advances monotonic uint64
generation, generates fresh owner IDs on reset, and stops at generation overflow.

Acquire a request resource BEFORE reading/allocating a body. Keep it through the
response and use its validation capability once. Read at most 65,536 bytes and
enforce the contract's five-second read/write timeouts at the HTTP boundary.
Do not detach validation fibers from their resource lifetime. Translate existing
typed failures, preserving Unsupported where a decoder supplies only text.

On adoption/rollback/view change, increment the pin and call `move` atomically
with chain publication. Only applied follower inclusions may populate `included`.
New relay snapshots become empty immediately. Run at most one scheduled rebuild
at a time using the new captured ledger view and bounded validation capacity;
cancel superseded work. `finish` checks both complete pin and a private rebuild
token before installing survivors in original admission order. Removal/expiry
during rebuilding cannot resurrect entries. Periodically call `expire` under
the gate so inactive pools promptly release resources.

Relay owns bounded immutable leases (two leases, eight originals/512 KiB per
lease, 30 seconds) and protocol size calculation. `eligible` returns immutable
identities only; holding a snapshot is NOT a managed relay lease. Relay events
must use the main/protocol event history and cannot invoke inclusion transitions.
Pool summary history contains no signed originals; all pool limits have ceilings.

## Focused verification

Run with cached dependencies and bounded JVM resources:

```sh
JDK_JAVA_OPTIONS='-XX:ActiveProcessorCount=2 -Xmx1536m -Dsbt.offline=true' sbt -batch \
  'scalafmtCheckAll' \
  'ledger/testOnly lab.ledger.AdaAdmissionPoolSuite lab.ledger.ClusterTransitionSuite' \
  'ledgerRuntime/testOnly lab.ledger.runtime.AdaIngressBudgetSuite'
```

Tests cover exact identity, signature/fee/shape failures, ADA whitelist, envelope
conflicts, concurrent competing spends under the owner gate, stale-pin Retry,
rollback/rebuild fencing, no resurrection, retention/capacity/history bounds,
chain-only input resolution, request overflow, single-use validation, and permit
release on cancellation. Live follower/relay/API evidence is main-owned.

## Main integration

Main supplies the complete `lab.submission.StatePin` and shared structural identity.
`AdaSubmissionService` binds the generic pool to that pin and the exclusive
`SubmissionOwner` gate. The shared identity error name is `InputLimit`.
The current HTTP, lease and follower composition is documented in
[the implementation](ada-submission-implementation.md). The primitive-only
verification above does not itself prove live acceptance.
