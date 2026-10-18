# Alonzo and Babbage output/collateral predicates

**Status: Current** — optional offline reference oracle for [#178](https://github.com/KD5UJC-XYZ/scarab/issues/178).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
docker build -f scripts/oracle/pre-conway-utxo/Dockerfile -t scarab-pre-conway178-oracle:local .
docker run --rm --network none --read-only \
  -v "$PWD/fixtures/reference/pre-conway-utxo:/fixtures:ro" \
  scarab-pre-conway178-oracle:local /fixtures/predicates.json > /tmp/pre-conway-predicates.json
python3 - <<'PY'
import json
from pathlib import Path
assert json.loads(Path('/tmp/pre-conway-predicates.json').read_text()) == json.loads(
    Path('fixtures/reference/pre-conway-utxo/predicates.json').read_text())
PY
```

The unchanged released libraries decode the original bodies and execute
`validateOutputBootAddrAttrsTooBig`, `validateWrongNetwork` and
`validateTooManyCollateralInputs` separately. Babbage takes `allSizedOutputsTxBodyF`, including
the collateral return; Alonzo takes its ordinary outputs. Every body has no redeemers or
witnesses: these are predicate API measurements, not transaction admission or signature tests.
The harness also records decoded collateral set cardinality and actual `sumAllCoin` over a
map assigning the first ordinary output to each collateral key, matching the reference
`Map.restrictKeys` accounting shape. This is a controlled UTxO model, not a measured full UTXOS transition.
It also calls the actual collateral predicate on a basic Conway body with the same decoded
set, keeping this API check separate from pre-Conway wire decoding.
The [fixtures](../../../fixtures/reference/pre-conway-utxo/README.md) retain complete diagnostics,
source/archive pins and scope. No public-network connection, node, or key is needed.
