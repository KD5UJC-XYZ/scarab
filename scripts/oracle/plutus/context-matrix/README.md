# Era-specific Plutus context matrix (#288)

**Current: independent API evidence, not a whole-transaction admission oracle.**

The thin observer calls unchanged released `collectPlutusScriptsWithContext` separately at the
`AlonzoEra`, `BabbageEra` and `ConwayEra` types. Successful rows retain the complete serialized
`PlutusWithContext`: protocol major, language and original script, the complete ordered argument
list/context, declared budget and cost model. Each collected script also executes through the
unchanged released evaluator. Failure rows retain closed collector categories and original error
CBOR. Diagnostic prose is deliberately not compared.

Pins: cardano-node11.1.3, cardano-ledger `f649f9751074d2ab3de033fc3912f29c9862c1f5`,
Plutus1.70.0.0 `507cab8cf6192e5143f12be5b2d3005a2928dc6d`. The existing
[context oracle](../context/README.md) supplies the cached pinned dependency base; the new build is
offline. No implementation source is patched. GHC9.10.3 differs from the official node's GHC9.6;
package/source agreement, rather than executable identity with that node, is the claim.

```sh
python3 scripts/oracle/plutus/context-matrix/prepare.py --out /tmp/context-matrix-inputs.json
python3 scripts/oracle/plutus/context-matrix/run.py --verify-only
python3 scripts/oracle/plutus/context-matrix/run.py --build --out /tmp/context-matrix-results.json
python3 -m unittest discover -s scripts/tests -p test_context_matrix_oracle.py
# In the normal private-socket Docker JDK container:
bash scripts/sbtw 'node/testOnly *ContextMatrixSuite'
```

`--build` first checks the accepted immutable base, aborts on any build error and binds the result's
image ID. Every replay verifies embedded observer, Cabal pins and the accepted executable hash before
running offline/read-only. The optional output manifest binds image/executable/source/input/output
identities. Fixture hashes and exact regeneration are checked before execution; stale images never
supply replacement evidence.

The [fixture README](../../../../fixtures/reference/context-matrix/README.md) gives the finite
coverage, signed-evidence distinction and remaining limits. API inputs deliberately retain original
signed bytes where available and mark every changed transaction as unsigned/unvalidated. Regenerating
an API input never revalidates its original signature and never proves its ledger admission.
