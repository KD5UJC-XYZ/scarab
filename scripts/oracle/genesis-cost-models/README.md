# Genesis cost models and cost-model lists of every length

**Status: Current** — optional offline reference oracle for [#369](https://github.com/KD5UJC-XYZ/scarab/issues/369).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/genesis-cost-models/Dockerfile -t scarab-genesis-cost-models369-oracle:local .
python3 scripts/oracle/genesis-cost-models/run.py            # offline: inputs, categories and hashes
python3 scripts/oracle/genesis-cost-models/run.py --replay   # run both images (no network, read-only), compare every verdict
python3 scripts/oracle/genesis-cost-models/run.py --record   # rewrite the fixture and its provenance
```

`Main.hs` runs the unchanged released libraries (cardano-ledger-alonzo 1.16.0.0, -conway 1.23.0.0,
-core 1.21.0.0) on JSON given by `cases.py`: `FromJSON AlonzoGenesis` and `FromJSON ConwayGenesis`
(whose cost models go through `parseCostModels` and `parseCostModelAsArray`), `parseCostModels`
itself and `mkCostModel`. A refusal keeps aeson's error; an acceptance keeps the cost models the
ledger kept (the values and the unknown languages). The evaluation part feeds the cost-model lists
to the [costing oracle](../plutus/costing/README.md) of #287, which builds the evaluation context
with Plutus `mkEvaluationContext` and evaluates the programs. The
[fixture](../../../fixtures/reference/genesis-cost-models/README.md) records the cases, the pins and
the scope. No public-network connection, node or key is needed.
