# Offline selected negative comparison

`lab.ClusterNegativeObservation` is a standalone Cats Effect `IOApp`; no `Main.scala`
integration is required. It reads local evidence only, makes no network connection,
and performs no transaction submission. It uses the strict source-bound context
loader from `ClusterTransferCommand` and first requires the original positive
`ClusterTransfer.compare` transition to pass.

For the three scenarios produced by `private_cluster_scenarios.py`, it checks exact
transaction identity, the successful Python guard receipt, nonzero reference
submission status, and the exact expected reference error constructor/message and rejection layer. It binds each
negative pre/post UTxO CBOR export to the relevant checked positive state and also
compares parameter/ledger JSON and tip bracket fields. Changed or malformed evidence
fails closed. Whole ledger JSON equality is deliberately strict; a live run may
require diagnosing irrelevant reference export differences before relaxing it.

The body transaction ID does not commit to witnesses. The runner therefore records
SHA-256 of the **complete submitted CBOR** in the separate submission record and
scenario receipt. It reads those bytes immediately before submission and verifies
that the transaction file is unchanged afterward. The offline adapter checks the
saved CBOR against that digest, binds status and separate streams to the named
submission record, checks the exact command, and rejects path traversal. Replacing
only witnesses, even if the same body ID and rejection class survive, fails this
binding. These digests establish consistency within a trusted evidence directory;
they do not authenticate a directory whose entire contents have been rewritten.

The runner retains the producer pause after the inherited positive `post` snapshot.
The inherited final resume is deferred until both post-inclusion negatives finish;
the scenario's outer `finally` always clears the hold and resumes producers, including
on cancellation or positive-comparison failure. There is no intermediate resume
and re-pause window. The initial pre-transfer window still resumes normally so the
valid transaction can be included. No main-owned runner change is required.

It then reuses `ClusterTransfer.compare` with that state as both state arguments.
Only the exact early failures `missing required witness keys` and `unresolved
spending inputs` map to selected rejection classes. All other errors are unsupported
by this adapter, never a matching rejection. The reused context describes the
positive advancing transition; it does not manufacture an advancing or atomic
context for a stationary negative observation. These two rejection branches occur
before transition/output/fee-pot delta evaluation in the existing pipeline.

Wrong-key requires the exact original body; repeated-included requires the entire
original transaction; conflicting-spend requires a different body identity and the
same spending input set. This observes missing required-key coverage, not malformed
signature rejection. Reference rejection remains local-submission rejection, not
invalid-block rejection or full ledger validity. The adapter does not independently
reacquire inclusion blocks: the original positive harness does that, and its raw
evidence remains separate. This offline tool verifies the selected state transition.

After the coordinator integrates the new classes and prepares a runtime classpath:

```sh
java -XX:ActiveProcessorCount=2 -Xmx1g \
  -cp "$(cat app/target/runtime-classpath.txt)" \
  lab.ClusterNegativeObservation "$PRIVATE_EVIDENCE_DIRECTORY"
```

Default unit tests use synthetic public CBOR and no reference node. Optional
`CLUSTER_NEGATIVE_EVIDENCE` enables an original-evidence integration test; its live result must be recorded separately after a complete corrected evidence packet passes. Do not
set this variable to partial or unrelated evidence and interpret skipped integration
as reference conformance. Test keys, original state, logs and receipts stay outside
Git; no raw private evidence is distributed.

`ClusterNegativeInspectionSuite` builds complete temporary evidence directories,
including a passing signed positive transition, using randomly generated keys held
only in memory. Every fixture first passes the complete `inspect` path. Mutations
then exercise witness-only replacement, a rewritten scenario digest against an
unchanged submission record, cross-directory substitution, changed points/UTxO/
parameters/ledger state, and altered submission paths and commands. These are
synthetic evidence-binding regressions, not fabricated reference conformance claims.

The scenario lifecycle test still means cancellation then a **fresh harness run**,
not retained-state reference node recovery. The original scenario commit remains
immutable; this follow-up also restores the inherited live resource allocation.


## Mempool versus selected Scala rejection

The two post-inclusion scenarios require the exact all-inputs-spent
`ConwayMempoolFailure` observation, with `referenceRejectionLayer: "mempool"` and
`recognizedLedgerRejection: false`. Wrong-key evidence remains explicitly a
`MissingVKeyWitnessesUTXOW` observation with layer `ledger-rule`; it demonstrates
missing required-key coverage, not an invalid-signature experiment. The output
preserves the reference layer and sets `ledgerRuleAgreement: false`: a related
Scala rejection is not proof that reference LEDGER evaluated or rejected that rule.
No fallback from an unexpected reference error is permitted. See [source and
preserved first-run failure](private-cluster-scenarios.md#reference-mempool-distinction).
