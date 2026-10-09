# ADA submission contract v1

Status: shared design contract for the next implementation milestone. The API,
pool and TxSubmission2 implementation do not exist yet. This contract does not
enable public runtime admission or claim full ledger validation.

## Ownership and dependencies

| Worker | Owns | Must not own |
| --- | --- | --- |
| Admission/pool | Core transaction identity, pure ADA profile gate/validator, volatile entries/reservations and revalidation | Chain publication or network protocol encoding |
| TxSubmission2 | Network codec/agency/accounting, runtime session, bounded immutable relay leases and protocol tests | Ledger imports, pool admission or inclusion claims |
| Integration | App state owner and mutation gate, loopback ingress, pool/relay bridge, follower inclusion and local end-to-end tests | Duplicate validators or a reference-CLI forwarding API |

The integration owner freezes shared signatures before workers implement adapters.
Core value types depend on `core` only. Network contracts must not import ledger
or app types; `network` currently depends only on `core`. Ledger admission types
may contain core identities and ledger `TxIn`. The app owns the coherent runtime.

## Original transaction identity

`SignedTransaction.checked(original)` returns a private-constructor immutable
value after bounded structural extraction:

- `original: Bytes`: the unchanged signed transaction CBOR, at most 65,536 bytes.
- `originalBody: Bytes`: the unchanged body span within that original.
- `transactionId: Bytes`: BLAKE2b-256 of that exact body span.
- `envelopeSHA256: Bytes` and `byteSize: Int`: full-original identity and size.

Never substitute projected or re-encoded bytes. The checker must establish the
body's membership in the original, not accept an independently supplied body.
Existing `ValidityInterval.decode` and `CardanoWitness` retain the relevant
originals and body identity; their module dependencies must remain acyclic.

Exact duplicate originals are idempotent. A different envelope with an already
held transaction ID is `Rejected(EnvelopeConflict)`; it never replaces stored
or leased bytes. A body ID alone does not identify a witness variant.

## State pin and atomic admission

`StatePin` is an immutable core value containing:

```text
ownerId: 32 bytes, fresh per volatile owner
generation: uint64, monotonically increasing; overflow stops admission
point: exact slot, block number and header hash
coherentStateId, ledgerStateId, environmentId: 32-byte identities
validationSlot: the pinned ledger slot, not a promised future inclusion slot
profileId: isolated-conway-pv9-ada-vkey-v1
```

Generation never derives from height and is never restored by rollback. Every
publication, rollback, anchor change, validation-view change or reset increments
it. Reset also creates a new owner ID.

One app-level gate must exclusively cover every relevant coherent-runtime mutation
and the pool's compare-and-reserve operation. Two snapshot reads are insufficient:
`CoherentSequence.Runtime.publish`, `rollbackTo` and `advanceAnchor` otherwise
operate independently. Workers must not retain mutation access that bypasses the
owner.

Admission obtains an immutable view/pin under that gate, validates outside it,
then reacquires it. If any pin field changed, return `Retry(currentPin)` without
installing entries or reservations. Otherwise check identity, capacity and every
spent input, and atomically install the entry and reservations.

Use `ClusterTransition.prepare(view, original, validationSlot)` for scoped
preflight. A private-constructor `AdmissionCandidate` exposes the exact transaction,
complete pin, spent `Set[TxIn]`, fee/minimum-output receipts and profile.
It does not expose a hypothetical post-state as chain state. Pool admission must
not call `ClusterTransition.commit` against the real chain state.

Results are typed:

```text
Accepted(receipt) | AlreadyPresent(receipt)
Rejected(reason) | Unsupported(feature)
Retry(currentPin) | Unavailable(reason)
```

`Accepted` means held in this volatile pool after the named scoped checks.
It does not mean relayed, included, durable or fully ledger-valid.
Every receipt carries `fullLedgerValidated=false`. Map typed existing failures;
do not classify English error strings. Preserve conservative Unsupported results
where existing decoding has no typed malformed/unsupported distinction.

## Initial ADA-only gate and limits

The profile gate precedes preflight. `ClusterTransition` already supports some
native-script spending, so calling it alone would silently broaden this milestone.

Initially support the existing coin-only vkey transfer/address/witness/validity
profile. Reject as Unsupported native and Plutus scripts, multiasset/mint,
collateral/reference inputs, datum/reference scripts, withdrawals, certificates,
governance actions, auxiliary data, false phase-two validity and pending-parent
dependencies. Resolve inputs only against the pinned chain UTxO. Existing typed
ledger checks remain authoritative for the represented predicates.

Initial local policy limits are 64 transactions/four MiB in the pool, eight
concurrent ingress requests, two validation jobs, and 60 seconds of local pool
retention. Local retention expiry is a policy drop, not a ledger-invalid verdict.
The integration owner must bound request bodies before full allocation.
Acquire the eight-request permit before reading a body; at most six admitted
requests may wait for the two validation jobs. Reject excess work rather than
create an unbounded queue. Bound body-read and response-write waits to five seconds
each. Retain at most 256 included/dropped status summaries for 60 seconds and at
most 128 relay-event summaries; neither history retains transaction originals.
All limits are explicit configuration values with validated ceilings.

On state movement, atomically mark entries ineligible for new relay, remove
observed inclusions, and begin bounded revalidation. Rebuild reservations in
admission order against the new pin; new submissions return Unavailable while
that generation is being rebuilt. A changed generation discards the rebuild
result. Invalid, expired, conflicting and unsupported survivors receive typed
drop reasons. Removal and shutdown release owned reservations. Cancellation
releases uncommitted request work and leases; it must not delete an entry whose
Accepted publication already linearized. A disconnected caller can later query
that transaction ID.
No speculative dependency graph or restart durability is promised.

## Relay contract

The network worker consumes only this ledger-independent boundary:

```scala
trait RelaySource[F[_]]:
  def acquireBatch(limits: RelayLimits): Resource[F, RelayLease[F]]

trait RelayLease[F[_]]:
  def offers: Vector[RelayOffer]
  def original(transactionId: Bytes): F[Option[Bytes]]
```

An offer carries the body transaction ID and protocol-defined advertised size.
The TxSubmission2 worker must establish wire framing, era/version encoding,
size semantics, agency and request/ack accounting from the pinned reference
implementation before transmitting. Do not equate original byte length with
wire size without that check.

Initial leases are bounded to eight originals/512 KiB/30 seconds, with at most
two concurrent leases. They pin exact bytes for their lifetime even if pool
eligibility changes. New leases select only current-generation eligible entries.
No request may retrieve unadvertised or replacement bytes.

Peer requests, successful writes and protocol acknowledgements are relay events,
not remote acceptance or inclusion receipts. TxSubmission2 relay must be Scala
owned; invoking reference CLI submission behind the API does not satisfy it.

## API and inclusion

The first ingress binds loopback only. Proposed versioned routes are
`POST /v1/transactions` with bounded `application/cbor`,
`GET /v1/transactions/{transactionId}`, and `GET /v1/state`.
Expose the typed result, profile and state pin. Accepted/duplicate responses must
explicitly say volatile/pending. Malformed, unsupported, conflict, stale state,
capacity and unavailable outcomes remain distinct machine-readable codes.

Statuses distinguish pending, relay activity, stale/revalidating, included and
dropped. Unknown/evicted IDs return unknown; absence is not proof of non-submission.
An inclusion receipt requires an applied follower block and exact chain point,
not a native CLI response. Remove the pool entry/reservations by included body
ID. Report witness/original-span matching separately: inclusion of a body ID
alone must not claim the submitted witness envelope was byte-identical.

The isolated ADA scenario must send its signed transaction through this API,
observe Scala pool admission and Scala TxSubmission2 delivery, then establish
reference inclusion and Scala follower application/removal. Record the matching
body and witness spans where available. No real funds or public network.

## Required concurrency evidence and extensions

Tests must demonstrate one winner for competing spends; Retry without reservation
when state changes during validation; old-generation invalidation on rollback;
immutable leased bytes across witness variants; bounded teardown/cancellation;
and no relay event promoted into an inclusion receipt.

Native-script and then Plutus support extend this same API with new explicit
profile versions. Scalus/reference agreement, phase-one/phase-two checks, budgets,
collateral, datums/redeemers and reference scripts remain future work described
in [the roadmap](scala-transaction-ingress-roadmap.md).

## Shared implementation checkpoint

The integration owner now supplies the core models in `lab.submission`.
`SignedTransaction.checked` has typed `InputLimit`, `DecodeRejected(detail)`
and `MalformedShape(detail)` failures. It also exposes unchanged
`originalWitnesses`, `originalAuxiliary` and structural `isValid`; none
confers signature or ledger validation. Parsing additionally bounds depth to 32
and item count to 16,384.

The shared effectful boundary lives in
`ledger-runtime/src/main/scala/lab/submission/AdmissionState.scala`:

```scala
trait AdmissionState[F[_]]:
  def current: F[AdmissionView]
  def withCurrent[A](expected: StatePin)(commit: F[A]): F[Either[StatePin, A]]

trait AdmissionStateObserver[F[_]]:
  def changed(change: AdmissionStateChange): F[Unit]
  def closed: F[Unit]
```

`AdmissionView.checked(pin, ledger)` binds the ledger/environment identities
and validation slot. `AdmissionStateChange` carries the view, a Published,
RolledBack, AnchorMoved or Reset kind, and included body IDs with optional
original body/witness spans. The observer runs under the mutation gate and must
only invalidate/update bounded memory and enqueue revalidation; it must not
re-enter the owner or await validation. `withCurrent` callbacks have the same
bounded in-memory, non-reentrant restriction.

The app's internal `CoherentDriver` lets follower execution use the admission
owner facade without receiving its raw runtime. Shared models/interfaces are
implemented and tested; the generation owner, pool, API and relay implementation
remain integration work in progress.
