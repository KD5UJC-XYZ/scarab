# UTxO query answers

**Status: Current** — offline reference oracle for [#677](https://github.com/KD5UJC-XYZ/scarab/issues/677).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/utxo-answers/Dockerfile -t scarab-utxo-answers-oracle:local .
mkdir -p /tmp/scarab/utxo-answers
python3 scripts/oracle/utxo-answers/cases.py > /tmp/scarab/utxo-answers/cases.json
docker run --rm --network none --read-only -v /tmp/scarab/utxo-answers:/in:ro \
  scarab-utxo-answers-oracle:local /in/cases.json > /tmp/scarab/utxo-answers/answers.jsonl
cmp /tmp/scarab/utxo-answers/answers.jsonl fixtures/reference/utxo-answers/answers.jsonl
```

`cases.py` writes the inputs: UTxO maps of each Shelley-based era, with every output form the
era's `TxOut` holds and outputs with a part only a later era's holds. `Main.hs` reads each entry
with the era's released decoder at the era's lowest protocol version and writes the map with
`toCBOR (UTxO era)`, as the node-to-client query server does. Its output, one JSON line per case,
is the [fixture](../../../fixtures/reference/utxo-answers/README.md).
