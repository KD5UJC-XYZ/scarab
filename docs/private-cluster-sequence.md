# Bounded same-epoch sequence reference observation

This explicit fixture extends the [fresh supported genesis profile](private-cluster-coherent.md)
with two independent key transactions. It retains the [bounded coordinator](coherent-sequence.md)
limits: at most eight original successor blocks, zero through sixteen restricted
transactions per block, and one frozen supplied epoch/context. Plutus execution
and valid competing Praos branch switching are separate work.

## Capture contract

The owned isolated network retains complete original pre/post UTxO CBOR and JSON,
ledger state, protocol state, parameters and stable tip brackets. Seven pre-only
source pins construct `SequenceInput.Context`. The independent post oracle pins
five endpoint sources, both submitted transaction envelopes and the exact original
capture. Original headers/blocks are acquired in chain order through the exact
post endpoint. Hash pins establish byte ownership, not source authenticity.

Two distinct genesis inputs fund the transactions, so no setup funding transaction
enters the window. Transaction construction and signing happen before producer
pauses. After the complete pre-state, producers resume until at least one
successor is observed. A second short pause queues both transactions through the
relay, followed by immediate resumption. Both transaction-specific relay and
producer admissions are retained. Full post-state is captured after inclusion.
Every pause resumes producers in a finally path; acquisition and JVM verification
run with producers resumed.

The original blocks must contain at least one empty block and both submitted
body/witness pairs together in one actual two-transaction block. Ordered original
body and witness bytes, rather than reconstructed-envelope equality, establish
inclusion. Separate one-transaction blocks do not satisfy the claim. Missing
grouping, epoch crossing, gaps or bound violations fail the scenario; the original
run and failure evidence remain retained without an automatic retry.

## Derivation and comparison

The observer applies every acquired original through `CoherentSequence` from the
exact source-bound supplied checkpoint. Certificate/KES, derived nonce, supplied
stake eligibility and restricted ledger stages publish together. Post-state
oracle parsing/comparison happens after derivation. Endpoint registration/stake
and parameter equality remains an observed endpoint condition, not proof of
continuity or authority derivation.

`ClusterTransition.compareBlockSequenceReference` accepts one through eight
opaque block receipts. Their checkpoint, environment, content, monotonic revision
and private branch heads must form one continuous sequence. It tracks surviving
creations across all receipts, removing outputs spent later. Those final outputs
are compared semantically; surviving original checkpoint outputs remain bytewise
comparisons. The full final output key set and fee pot must match. The command
also binds the first receipt to its supplied checkpoint and requires one receipt
per original, preventing an arbitrary matching subchain from becoming a whole
window claim.

The final oracle comparison includes the complete certificate counter map, tip,
last slot and all five exported nonce fields. An absent previous-epoch nonce
remains unknown and is not invented for comparison. Rollback to every retained
prefix and reapplication check the full tuple and monotonic revision. Without an
actual intermediate reference snapshot, those prefix checks are internal
invariants; only captured endpoints support reference-state comparison.

The runner inherits the 600-second overall bound and owned cleanup/watchdog.
The reference cluster uses 3 CPUs/6 GiB and its single observer 1 CPU/1 GiB.
Keys remain disposable and private; no public peers, production keys or real
funds participate. Separate paused queries remain non-atomic snapshots. This
experiment cannot establish full ledger/consensus validity or durable state.

## Reproduction

The CLI separates original acquisition (`coherent-sequence capture PORT CONTEXT
POST`) from offline observation (`coherent-sequence observe CONTEXT ORACLE`).
The public launcher is `scripts/private_cluster_sequence.py` with explicit
`--fixture-profile conway-pv9-empty-byron-allocations-coherent-v1`, pinned
`--reference-image`, fresh private `--output`, compiled private `--scala-repo`
and bounded `--seconds`. Source and launcher must be reviewed before a live run.
Raw captures, signing keys, node state and logs stay outside Git.

## Observed result

The first reviewed attempt passed in 276.197 seconds and left no owned
containers or networks. The supplied anchor was block 61/slot 1511
in epoch 3; two original successors ended at block 63/slot
1589 in the same epoch. The first was empty; the second contained both
submitted transactions in submission order. The complete UTxO grew from
6 to 8 entries and fees changed from 0 to 400000.
The cumulative comparator matched all final outputs and fees, while certificate
counters and all five exported nonce fields also matched. Previous-epoch nonce
remained unknown and was not reference-compared.

All three retained prefixes passed internal full-tuple rollback/reapply checks;
published revisions advanced from 0 through 2 to final replay revision 8.
Only pre/final endpoints have captured reference state. Endpoint stake/registration
and parameter equality is not continuity proof. This is one positive bounded
scenario, not general ledger agreement or successful competing-branch switching.

Validation passed 1,013 public Scala tests, 25 serial gates and 132
compiler-inclusive Python guards. Seventy focused Scala checks and nine focused
launcher guards passed before live execution. The separate retained suites passed
81 checks (38 public reruns and 43 retained/synthetic cases), including changed
post fees/nonce, reordered originals and omitted originals. Fresh offline replay
of the unchanged retained capture also succeeded.

Original observer receipt SHA-256: `6af7f511dbf15c74ebe8956c1763dccb04da6437eefffedd240df039ae9143b7`.
Private evidence is retained outside Git at
`/home/<user>/cardano-sequence-live1-20261009`; checks and reviews are retained at
`/home/<user>/cardano-sequence-evidence-integration-20261009`.
