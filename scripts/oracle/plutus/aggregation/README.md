# Released ledger multi-script result oracle

This thin, optional wrapper calls unchanged released `collectPlutusScriptsWithContext`,
`evalPlutusScriptsWithLogs`, `evaluatePlutusWithContext` and predicate `EncCBOR` APIs. It exports every
passing/failing script, the ordinary ledger's Quiet logs, verbose per-script trace logs, successful
actual execution units, declared units, complete reconstruction contexts and both validity-tag
predicate byte vectors. It changes no reference evaluator or ledger implementation.

The base is the [pinned context image](../context/README.md), including its GHC 9.10.3/Cabal 3.14.1.1,
native crypto, full dependency freeze, released ledger packages and Plutus 1.70.0.0. The original
reference node was built with GHC 9.6; this is released-source API correspondence, not a claim of an
identical executable. The source/archive binding is in the fixture provenance.

From the repository root:

```sh
python3 scripts/oracle/plutus/aggregation/prepare.py --out /tmp/aggregation-inputs.json
python3 scripts/oracle/plutus/aggregation/run.py --verify-only
python3 scripts/oracle/plutus/aggregation/run.py --build --out /tmp/aggregation-reference.json
```

The `--build` path stops on any build failure, binds Docker's successful `--iidfile` to the
resolved image, and executes by immutable image ID. Existing-image replay independently compares
the embedded Main/Cabal/freeze hashes against the checkout before execution, rejecting a stale
wrapper. An output `.manifest.json` binds that image, executable and wrapper hashes to the exact
corpus and output digests. Successful numeric budgets are required; missing/null/noninteger fields
fail schema validation. A failed evaluation has explicit null units because the API supplies none.

Replay runs offline with a read-only container and byte-compares the complete returned JSON,
including the unmodified reference diagnostic text and full predicate CBOR. A failed reference
process, missing/extra result, changed trace/order/budget or changed byte vector fails the replay.
The [fixture README](../../../../fixtures/reference/script-results/README.md) identifies which
original signed admission is reused and which transactions are API-only derivations. No new network,
operational-node restart, signing keys or public-chain synchronization is needed.

Scarab's deterministic tests compare all verdicts, failure order, successful consumed units, verbose
logs, declared budgets and every reconstruction payload. Complete failing-tag diagnostic text is
retained as reference evidence, with the remaining presentation difference explicitly recorded in
[the as-built design](../../../../docs/design/script-results.md). The original all-pass invalid-tag
predicate is compared byte-for-byte. The reference restricting API returns no consumed budget on
failure; this wrapper does not manufacture one.
