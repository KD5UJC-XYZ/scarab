<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron genesis balances

**Status: Current** — optional offline reference oracle for
[#728](https://github.com/KD5UJC-XYZ/scarab/issues/728),
[#729](https://github.com/KD5UJC-XYZ/scarab/issues/729) and
[#730](https://github.com/KD5UJC-XYZ/scarab/issues/730).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/byron-genesis-balances/Dockerfile -t scarab-byron-genesis-balances:local .
python3 scripts/oracle/byron-genesis-balances/cases.py --inputs /tmp/byron-genesis-balances-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro scarab-byron-genesis-balances:local \
  /fixtures/byron-genesis-balances-inputs.json > /tmp/byron-genesis-balances-reference.json
python3 scripts/oracle/byron-genesis-balances/cases.py --inputs /tmp/byron-genesis-balances-inputs.json \
  --reference /tmp/byron-genesis-balances-reference.json --out /tmp/byron-genesis-balances-cases.jsonl
cmp /tmp/byron-genesis-balances-cases.jsonl fixtures/reference/byron-genesis-balances/cases.jsonl
```

`Main.hs` runs the unchanged released Byron genesis reader as `readGenesisData` applies it
(`Cardano/Chain/Genesis/Data.hs:169-181`, cardano-ledger `f649f975`): `parseCanonicalJSON` of the
case's bytes, the genesis hash (`hashRaw` of `renderCanonicalJSON`), then `fromJSField` of
`nonAvvmBalances` and `avvmDistr`, then `UTxO.fromBalances` of each field, the AVVM balances at
redeem addresses without a network magic. It reports the hash where the bytes parse; an acceptance
reports each output's id, index, address and amount, a refusal the reader's error. `cases.py` writes
genesis objects with the two balance fields, as text or, where they are not UTF-8, as hex; the [fixture](../../../fixtures/reference/byron-genesis-balances/README.md)
lists the cases and the scope. No public-network connection, node or key is needed.
