# Alonzo and Babbage cost-model decoding

**Status: Current** — optional offline reference oracle for [#332](https://github.com/KD5UJC-XYZ/scarab/issues/332).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/cost-models/Dockerfile -t scarab-cost-models332-oracle:local .
python3 scripts/oracle/cost-models/run.py            # offline: inputs, categories and hashes
python3 scripts/oracle/cost-models/run.py --replay   # run the image (no network, read-only), compare every verdict
python3 scripts/oracle/cost-models/run.py --record   # rewrite the fixture and its provenance
```

The unchanged released libraries decode each whole transaction `[body, {}, true, null]` as
`Tx TopTx AlonzoEra` at decoder version 5 and as `Tx TopTx BabbageEra` at version 7 with
`decodeFullAnnotator`. The body carries one genesis-key update proposal that sets only protocol
parameter 18. A refusal keeps the full `DecoderError`; an acceptance keeps the known languages
with the parameter values the ledger kept and the unknown ones, read back through
`ppuCostModelsL`, so that a repeated language shows which entry survives. This is a decoder
measurement: it does not run UTXO or LEDGER, does not apply the update and does not evaluate a
script. The [fixture](../../../fixtures/reference/cost-models/README.md) records the cases, the
source and archive pins and the scope. No public-network connection, node or key is needed.
