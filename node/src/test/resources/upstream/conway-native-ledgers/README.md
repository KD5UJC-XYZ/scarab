# Native multi-transaction LEDGERS observations

Pinned `cardano-ledger`: `f649f9751074d2ab3de033fc3912f29c9862c1f5`.
The export-only observer in `scripts/oracle/ledger-govcert/multi-ledgers.patch`, applied
following `exporter.patch`, reuses the three original signed transactions and coherent
states of the registered proposal-refund integration test (seed 498301). It calls the
actual native Conway LEDGERS rule, without invoking its separate crashing formal adapter.
The registered integration test and its original LEDGER/NEWEPOCH formal hooks still pass.

Four supplemental native judgments are retained, including the full native inputs,
outputs or accumulated typed predicate failures:

| Batch | Native outcome | Purpose |
|---|---|---|
| ordered-three | Accepted | Three different original transactions, including registration and scripted proposal, applied in order. |
| dependent-two | Accepted | Original payment followed by the proposal spending its newly created outputs. |
| reversed-dependency | Rejected | The proposal's required payment outputs do not yet exist. |
| double-spend | Rejected | The second copy spends the input already consumed by the first payment. |

Both accepted native states are independently checked against the original integration
trace's final state before export. Scarab runs its production `Ledgers` transition on the
original transaction body and witness bytes, retaining valid signatures and script data.
It compares every input/output field, UTxO accounting and all remaining native state.
MemPack has no original `TxOut.raw` or Scala change-history bookkeeping; those local
representations are the only state-comparison exclusions, as in the original trace.

For rejected batches, native Haskell accumulates several predicate failures while Scarab
returns the first failure. The test encodes Scarab's typed cause using its production
Conway predicate-failure encoder and requires that complete structured payload to occur
among the native observations. It does not branch on rendered diagnostic messages.

`manifest.json` pins all retained bytes, observer patches, source/dependency revisions,
runner arguments and source trace manifest. This is bounded native coverage, not a formal
LEDGERS pass or a broad generated-block campaign. The separate formal evaluator SIGSEGV
and broader coverage remain tracked by [#49](https://github.com/KD5UJC-XYZ/scarab/issues/49).
Raw upstream trace CBOR is not a native snapshot format; no persisted format changes.
Upstream license notices remain in `fixtures/witness/licenses/`.
