# Pre-Conway instant stake (native `addShelleyInstantStake` / `deleteShelleyInstantStake`)

**Status: Current** — optional offline reference oracle for [#299](https://github.com/KD5UJC-XYZ/scarab/issues/299).

Build the [context-reference image](../plutus/context/README.md) first (it carries the pinned
cardano-ledger-shelley 1.19.0.1 from CHaP), then:

```sh
python3 scripts/oracle/instant-stake/inputs.py scripts/oracle/instant-stake/inputs.json
docker build -f scripts/oracle/instant-stake/Dockerfile -t scarab-instant-stake299-oracle:local .
docker run --rm --network none --read-only \
  -v "$PWD/scripts/oracle/instant-stake:/inputs:ro" \
  scarab-instant-stake299-oracle:local /inputs/inputs.json > /tmp/instant-stake.json
python3 - <<'PY'
import json
from pathlib import Path
assert json.loads(Path('/tmp/instant-stake.json').read_text()) == json.loads(
    Path('fixtures/reference/instant-stake/results.json').read_text())
PY
```

The harness applies the released `addShelleyInstantStake` to the produced outputs and then
`deleteShelleyInstantStake` to the consumed outputs, the sequence of `updateUTxOAndInstantStake`
(`Shelley/Rules/Utxo.hs:613-624`), for the credential and the pointer map under the Shelley and
Babbage `TxOut`. It also measures the released `toCompact` on the output-amount bounds. The
[fixture](../../../fixtures/reference/instant-stake/README.md) retains the results, the source and
package pins, and the scope. `fixtures/reference/instant-stake/results.json` is the harness output
with sorted keys and indentation; its parsed value is unchanged.
