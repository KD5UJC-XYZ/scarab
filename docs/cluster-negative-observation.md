# Offline selected negative comparison

`lab.ClusterNegativeObservation` is a standalone Cats Effect `IOApp`; no `Main.scala`
integration is required. It reads local evidence only, makes no network connection,
and performs no transaction submission. It uses the strict source-bound context
loader from `ClusterTransferCommand` and first requires the original positive
`ClusterTransfer.compare` transition to pass.

For the three scenarios produced by `private_cluster_scenarios.py`, it checks exact
transaction identity, the successful Python guard receipt, nonzero reference
submission status, and the expected reference error constructor. It binds each
negative pre/post UTxO CBOR export to the relevant checked positive state and also
compares parameter/ledger JSON and tip bracket fields. Changed or malformed evidence
fails closed. Whole ledger JSON equality is deliberately strict; a live run may
require diagnosing irrelevant reference export differences before relaxing it.

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
`CLUSTER_NEGATIVE_EVIDENCE` enables an original-evidence integration test; it has not
been run because live workloads remain blocked pending direct user approval. Do not
set this variable to partial or unrelated evidence and interpret skipped integration
as reference conformance. Test keys, original state, logs and receipts stay outside
Git; no raw private evidence is distributed.

The scenario lifecycle test still means cancellation then a **fresh harness run**,
not retained-state reference node recovery. The original scenario commit remains
immutable; this follow-up also restores the inherited live resource allocation.
