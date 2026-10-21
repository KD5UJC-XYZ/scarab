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
among the native observations. This is single-cause membership, not equality of the complete
accumulated failure list, order or multiplicity. It does not branch on rendered diagnostic messages.

`manifest.json` pins all retained bytes, observer patches, source/dependency revisions,
runner arguments and source trace manifest. This is bounded native coverage, not a formal
LEDGERS pass or a broad generated-block campaign. The later separate
[formal repair corpus](../formal-ledgers-repair/README.md) replays these unchanged batches:
both accepted translated states match; both bad batches reject with differing failure text
retained. The original native-only observations and formal SIGSEGV remain unchanged.
Raw upstream trace CBOR is not a native snapshot format; no persisted format changes.
Upstream license notices remain in `fixtures/witness/licenses/`.

## Reproduction

Use a disposable checkout of the source pin and the pinned formal/generator checkouts.
Apply the repository patches in this order (the first also points Cabal at the local
`/formal` and `/constrained` mounts):

```sh
git -C /path/to/disposable/cardano-ledger apply /path/to/scarab/scripts/oracle/ledger-govcert/exporter.patch
git -C /path/to/disposable/cardano-ledger apply /path/to/scarab/scripts/oracle/ledger-govcert/multi-ledgers.patch
```

Start an isolated container from the manifest's exact oracle image, overriding its
application entrypoint. Mount the disposable source at `/source`, a private output/build
directory at `/probe`, the pinned formal source at `/formal`, constrained generators at
`/constrained`, and the CLS source selected by `exporter.patch` at `/clssrc`. Mount the
existing dependency caches at `/root/.cabal` and `/root/.local/state/cabal`. The original
`conway-native-refund/cases` resource directory is mounted **read-only** at `/input`.
Keep `/source` as the working directory and use `--network none`. With the container
named `scarab-49-ledgers-native`:

```sh
docker exec scarab-49-ledgers-native cabal build cardano-ledger-conformance:test:tests \
  --builddir=/probe/builddir --offline --jobs=2 --extra-lib-dirs=/usr/local/lib
docker exec -e SCARAB_BATCH_INPUT_DIR=/input -e SCARAB_BATCH_EXPORT_DIR=/probe/cases \
  scarab-49-ledgers-native \
  /probe/builddir/build/x86_64-linux/ghc-9.10.3/cardano-ledger-conformance-9.9.9.9/t/tests/build/tests/tests \
  --match 'Conformance/Conway/Imp/EPOCH/Proposals/Expired proposal deposit refunded' \
  --seed=498301 --qc-max-success=1 --qc-max-size=30
docker rm -f scarab-49-ledgers-native
```

The manifest includes the exact image identity, executed binary hash, patched source
hashes, dependency-plan hash and build/run arguments. Build caches are optional; they
must resolve the same pinned dependency plan. The observer's expected final-state
controls and native rejection assertions must pass before copying the four cases.
