<!-- SPDX-License-Identifier: Apache-2.0 -->
# Byron genesis data

**Status: Current** — optional offline reference oracle for
[#734](https://github.com/KD5UJC-XYZ/scarab/issues/734).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/byron-genesis-data/Dockerfile -t scarab-byron-genesis-data:local .
python3 scripts/oracle/byron-genesis-data/cases.py --inputs /tmp/byron-genesis-data-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro scarab-byron-genesis-data:local \
  /fixtures/byron-genesis-data-inputs.json > /tmp/byron-genesis-data-reference.json
python3 scripts/oracle/byron-genesis-data/cases.py --inputs /tmp/byron-genesis-data-inputs.json \
  --reference /tmp/byron-genesis-data-reference.json --out /tmp/byron-genesis-data-cases.jsonl
cmp /tmp/byron-genesis-data-cases.jsonl fixtures/reference/byron-genesis-data/cases.jsonl
```

`Main.hs` runs the unchanged released Byron genesis reader as `readGenesisData` applies it
(`Cardano/Chain/Genesis/Data.hs:169-181`, cardano-ledger `f649f975`): `parseCanonicalJSON` of the
case's bytes, the genesis hash (`hashRaw` of `renderCanonicalJSON`), then `fromJSON @GenesisData`.
It reports the hash where the bytes parse; an acceptance reports every field of `GenesisData` (the
balances by their count, which the [balances oracle](../byron-genesis-balances/README.md) covers
entry by entry), a refusal the reader's error. `cases.py` takes the genesis files of the repository
unchanged and the devnet genesis with literal edits; the
[fixture](../../../fixtures/reference/byron-genesis-data/README.md) lists the cases and the scope.
No public-network connection, node or key is needed.
