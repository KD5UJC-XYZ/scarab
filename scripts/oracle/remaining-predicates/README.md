# Remaining ledger predicates

**Status: Current** — offline native reference captures for [#141](https://github.com/KD5UJC-XYZ/scarab/issues/141).

After building the [pre-Conway reference image](../pre-conway-utxo/README.md):

```sh
docker build -f scripts/oracle/remaining-predicates/Dockerfile -t scarab-predicates141-oracle:local .
docker run --rm --network none --read-only \
  -v "$PWD/fixtures/reference/remaining-predicates:/fixtures:ro" \
  scarab-predicates141-oracle:local /fixtures/pool.json > /tmp/pool-predicates.json
docker run --rm --network none --read-only \
  -v "$PWD/fixtures/reference/remaining-predicates:/fixtures:ro" \
  --entrypoint /usr/local/bin/scarab-output-predicates \
  scarab-predicates141-oracle:local /fixtures/outputs.json > /tmp/output-predicates.json
python3 - <<'PY'
import json
from pathlib import Path
for captured, fixture in [('pool-predicates', 'pool'), ('output-predicates', 'outputs')]:
    assert json.loads(Path(f'/tmp/{captured}.json').read_text()) == json.loads(
        Path(f'fixtures/reference/remaining-predicates/{fixture}.json').read_text())
PY
```

`Pool.hs` executes released POOL transitions, including an optional prior registration. The
only non-default parameters are protocol version and minimum cost; the global network is testnet.
`Outputs.hs` decodes each original body as Shelley, Allegra or Mary and calls the actual
`validateOutputBootAddrAttrsTooBig` and `validateWrongNetwork` APIs. It does not run native UTXO
or LEDGER admission. Both tools preserve the full native diagnostics and emitted failure CBOR.
The [fixture provenance](../../../fixtures/reference/remaining-predicates/provenance.json)
records package/source pins and byte-identical rule bodies. No upstream library source is patched.
