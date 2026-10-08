# Conway PV9 validity interval: restricted captured-transfer profile

`ValidityInterval` implements the pure half-open predicate `lower <= slot < upper`.
Either bound may be absent; an absent bound imposes no constraint. Equal or reversed
bounds decode but cannot pass. Slots are unsigned 64-bit integers, including zero
and `2^64 - 1`. Negative, null, tagged-bignum, and other non-uint bounds reject.
Duplicate and unsupported body keys reject. Non-minimal CBOR integer encodings
remain original bytes; transaction identity hashes the original body span.

The syntax profile is `conway-pv9-cluster-validity-interval-v1`. It admits only
required body keys 0/1/2 and optional keys 3 (upper) and 8 (lower), with the existing
four-field true/null envelope. It does not independently validate the projected
inputs, outputs, signatures, or state transition.

## Composition and capture binding

`ClusterIntervalTransfer.compare` is the public composition, with profile
`conway-pv9-cluster-ada-interval-transition-v1`. It binds original submitted body
and witness bytes to the actual containing captured block before evaluating the
interval. The post-snapshot slot is never substituted for the inclusion slot.
The captured range must contain 1–8 Conway blocks, pass existing original-byte
body-commitment inspection (maximum 1 MiB per block), start at the supplied pre
hash/slot, advance by parent hash, slot, and successive block number, and end at
the supplied post hash/slot. The context has no pre block number, so the first
block number cannot be checked against that anchor. Exactly one transaction is
allowed across the range, with no auxiliary data or invalid transaction indexes.

After binding, an unsatisfied interval fails with `OutsideValidityIntervalUTxO`.
The existing minimum-output and transfer comparison predicates then run using
internal interval-aware projections. Coverage.scala and Balance.scala remain byte-for-byte pinned. An internal stripped envelope is used only for semantic projection through these historical decoders; retained fields, outputs, and witness spans are copied verbatim. Signatures, transaction/output identities, and fee memo size always use the original unstripped body. The stripped envelope is never evidence of those properties. Their original public entry points remain
closed to keys 3/8. `RestrictedReplay`, including its historical fixed-slot
environment, is unchanged. The `private[ledger]` opt-in helpers are partial
syntax/projection checks; callers must use the public composition to enforce the
interval and minimum-output predicates together. The nested transfer receipt
retains its legacy identity; consumers must report the outer profile.

This is a restricted acquisition-backed comparison, not consensus or full ledger
validation. Body commitments do not establish header authenticity or chain
selection. Captured reference snapshots are not made atomic by this API. Both
`fullLedgerValidated` and `referenceSnapshotAtomic` remain false. No scripts,
collateral, mint, multiasset, withdrawals, certificates, or governance support is
added. The pure API introduces no runtime, cancellation, or resource ownership.

## Pinned rule evidence and phase order

The source comparison uses cardano-ledger commit
`f649f9751074d2ab3de033fc3912f29c9862c1f5`, the existing node 11.1.3 research pin.
Node binary/package provenance does not include a complete resolved Cabal plan;
this is source-level conformance evidence, not a claim of reproducible binary
identity. The inspected private node reports Conway 1.23.0.0 and Babbage 1.14.0.0.

- [Allegra Scripts, `ValidityInterval` and `inInterval`](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/allegra/impl/src/Cardano/Ledger/Allegra/Scripts.hs#L465): lower inclusive, upper exclusive, independent absent bounds.
- [Conway TxBody decoding](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/TxBody.hs#L184): optional upper key 3, lower key 8, absent defaults; body memo bytes supply transaction identity.
- [Conway CDDL](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/cddl/data/conway.cddl#L72): slot is `uint .size 8`; transaction body interval fields use slot.
- [Allegra UTXO predicate](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/allegra/impl/src/Cardano/Ledger/Allegra/Rules/Utxo.hs#L211): checks the environment slot and reports `OutsideValidityIntervalUTxO`.
- [Babbage UTXO validation](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/babbage/impl/src/Cardano/Ledger/Babbage/Rules/Utxo.hs#L326): interval follows disjoint-reference-input checking and precedes forecast, empty inputs, fees, bad inputs, conservation, and minimum-output checks.
- [Conway UTXO delegation](https://github.com/IntersectMBO/cardano-ledger/blob/f649f9751074d2ab3de033fc3912f29c9862c1f5/eras/conway/impl/src/Cardano/Ledger/Conway/Rules/Utxo.hs#L224): delegates the applicable validation before UTXOS and state update.

The new composition guarantees interval failure before its downstream predicates.
It does **not** reproduce the complete UTXOW/UTXO predicate or failure ordering:
the existing restricted composition checks minimum outputs before the legacy
transfer checks, including fee/size/signature checks. Unsupported transaction
features are rejected, not silently treated as validated.

## Integration and verification

`validity-interval-integration.patch` is a small **unapplied** proposal for the
parent-owned `ClusterTransferCommand.scala`. It routes the capture comparison
through the new explicit profile and emits the actual inclusion slot and block
hash. Applying it changes the command's reported profile; the integration owner
must select this opt-in deliberately. The existing legacy library APIs stay
unchanged. Patch applicability was checked against base
`2eda20c098ad2897d770ed471e47e2b9c2b55662`; command integration is not included in
this packet's compiled sources.

The new suite covers boundary grids, absent/extreme bounds, malformed/duplicate
fields, truncation, original non-minimal encoding identity, signed composition,
expired-interval precedence, legacy rejection, witness substitution, commitment
mutation, and incomplete/reversed/oversized captured ranges. A signed transfer
included at slot 20 with upper bound 21 passes even when the post snapshot is at
slot 30, exercising actual inclusion-slot binding.

Tests run offline in the existing Docker image with two CPUs, 2 GiB memory, a
1200 MiB JVM heap, and this worktree's private cache. No live cluster, network
fetch, shared-cache write, production keys, host install, or push is performed.

Final offline result: `scalafmtAll scalafmtCheckAll ledger/test app/test` passed:
118 ledger tests (13 interval tests), 156 app/dependency tests. Historical replay
source-pin and behavior tests passed unchanged. `git diff --check` and
`git apply --check docs/validity-interval-integration.patch` passed. Independent
read-only review found no blocking production issue after the pinned-source
refactor; its requested original-size boundary regression is included. Test log:
worktree-local ignored `.cache/interval-tests.log`. Live node acceptance and
application integration remain for the integration owner and resource slot.
