# Scala transaction ingress milestones

The restricted live boundary follower and isolated ADA submission milestones
have passed. The app-private loopback API, bounded volatile pool and Scala
TxSubmission2 relay are implemented for `isolated-conway-pv9-ada-vkey-v1`.
See [implementation and acceptance evidence](ada-submission-implementation.md),
including the explicit zero reference initialization-delay setting. General
public-node admission, default-delay interoperability and full ledger/consensus
validation remain unproved. The opt-in native-script signature-only acceptance
case has also passed; see [native implementation and evidence](native-submission-implementation.md).
Wider native reference agreement and a separately versioned Plutus profile remain next.

## Sequence

1. Completed within the restricted profile: live follower with an exact-point
   native endpoint comparison.
2. Completed within the isolated ADA profile: accept transactions through a
   Scala-owned API, apply explicitly scoped
   validation, store accepted transactions in a bounded volatile pool, relay with
   Scala TxSubmission2, and observe reference inclusion through the Scala follower.
   Remove included transactions from the pool.
3. Implemented: the bounded native-script profile uses the same API, generation
   fencing, original bytes, reservations and relay limits. Its isolated
   signature-only positive passed inclusion and exact endpoint comparison. Next:
   extend reference agreement across valid and invalid witness/validity
   combinations, with authoritative evaluation-slot evidence for negatives.
4. Then add a separately versioned Plutus profile using Scalus and reference
   conformance tests for phase-one/phase-two, budgets, contexts and collateral.
   The same Scala API remains the ingress; each profile requires its own isolated
   admission, relay, inclusion and endpoint evidence.

Forwarding an API request to a reference CLI is not the Scala admission, pool or
relay milestone. API acceptance, peer delivery and chain inclusion are separate
observable states. A relay acknowledgement does not prove inclusion or validity.

## Submission contract

Preserve bounded original transaction CBOR and its ledger transaction ID. Return
a structured accepted, rejected or unsupported result, with the validation
profile/version and state point used for admission. Accepted means accepted into
the scoped volatile pool; it does not mean confirmed. Duplicate requests should
return the existing transaction status. Conflicting inputs, capacity, expiry and
stale-state revalidation need explicit outcomes.

Admission and pool mutation must be atomic with respect to the selected state
view and competing pool inputs. Bound transaction bytes, pool bytes/count,
request concurrency and lifetimes. Keep delivery attempts separate from the
immutable transaction bytes. Expose pending, relayed, included and dropped states
with reasons and exact inclusion points. Restart durability and rollback recovery
must either be implemented and tested or explicitly remain unsupported.

## Script extension

The versioned validation profile should make supported eras, script languages and
features explicit. Reject unsupported combinations before claiming acceptance.

Phase one covers the supported transaction structure, witnesses and native
scripts, input/reference-input resolution, value preservation, fees, validity
intervals and required signers. Phase two evaluates supported Plutus scripts with
Scalus against the correct language version, cost model, protocol parameters and
execution context. Enforce declared execution budgets and transaction/block
limits; retain structured evaluation failures rather than treating evaluation as
successful submission.

The scope must explicitly cover datum hashes and inline datums, redeemer purpose
and indexing, script-data hashes, reference scripts and their charging rules,
collateral inputs, total collateral, collateral return and phase-two-invalid
transaction behavior. Missing or unsupported semantics reject explicitly.
Reference agreement is required for both accepted and rejected cases, including
budget boundaries and collateral outcomes.

All integration scenarios use disposable keys and isolated local Docker clusters.
No real funds, production keys or public-network submissions are part of these
milestones.

The initial shared worker boundary is implemented in [ADA submission contract v1](ada-submission-contract-v1.md). Restricted ADA and native signature-script acceptance evidence is recorded; wider native reference agreement and Plutus remain pending.
