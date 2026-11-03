# Node-to-client query arguments

**Status: Current** — offline reference oracle for [#709](https://github.com/KD5UJC-XYZ/scarab/issues/709).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/ntc-query-arguments/Dockerfile -t scarab-ntc-query-arguments:local .
python3 scripts/oracle/ntc-query-arguments/cases.py --inputs /tmp/ntc-query-arguments-inputs.json
docker run --rm --network none --read-only -v /tmp:/in:ro \
  scarab-ntc-query-arguments:local /in/ntc-query-arguments-inputs.json > /tmp/ntc-query-arguments.jsonl
python3 scripts/oracle/ntc-query-arguments/cases.py --inputs /tmp/ntc-query-arguments-inputs.json \
  --reference /tmp/ntc-query-arguments.jsonl --out /tmp/cases.jsonl
cmp /tmp/cases.jsonl fixtures/reference/ntc-query-arguments/cases.jsonl
```

`Main.hs` reads each argument with the decoder `decodeShelleyQuery` uses for it: `fromCBOR` of
cardano-binary, or the ledger's `decCBOR` at Conway's version 9, and writes the verdict and the
values read. `MemberStatus` is declared in the harness with the same three constructors and the
same `decodeEnumBounded`, as the image does not build cardano-ledger-api. The output, merged with
the inputs, is the [fixture](../../../fixtures/reference/ntc-query-arguments/README.md).
