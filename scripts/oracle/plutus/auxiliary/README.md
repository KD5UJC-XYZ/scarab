# Auxiliary decoder and script-validity oracle (#286)

**Current: unchanged released Haskell API observer.**

The observer instantiates the era-indexed auxiliary decoder and its script-validity predicate at
Alonzo, Babbage and Conway. Structure refusals expose no validity result. Successfully decoded
auxiliary data is checked with an empty supported-script cache, using actual protocol versions.
The implementation is unchanged; only a thin observer calls exported APIs. Its dependency pins are
those of the existing [context oracle](../context/README.md), built offline on that accepted image.

```sh
python3 scripts/oracle/plutus/auxiliary/prepare.py --out /tmp/auxiliary-inputs.json
python3 scripts/oracle/plutus/auxiliary/run.py --verify-only
python3 scripts/oracle/plutus/auxiliary/run.py --build --out /tmp/auxiliary-results.json
python3 -m unittest discover -s scripts/tests -p test_auxiliary_script_oracle.py
# Within the private-socket JDK Docker container:
bash scripts/sbtw 'ledger/testOnly *AuxiliaryScriptsSuite *LedgerDecodeErrorSuite' 'fuzz/testOnly *GuardSuite'
```

Replays verify original input regeneration and fixture/source hashes, then bind immutable image,
embedded observer/Cabal pins and the accepted executable before executing read-only/offline.
A failed build cannot replay an old tagged executable. Output manifests bind image, sources, corpus
and complete outcome digests. The [fixture README](../../../../fixtures/reference/auxiliary-scripts/README.md)
distinguishes decoder, predicate and signed-admission evidence and records the finite coverage.
