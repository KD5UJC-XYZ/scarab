# Conway body and witness-set decoding oracle

**Status: Current** — optional offline reference oracle for
[AUD3-01/#327](https://github.com/KD5UJC-XYZ/scarab/issues/327).

Build the [existing context-reference image](../plutus/context/README.md) first, then:

```sh
python3 scripts/oracle/conway-decoding/cases.py --out /tmp/conway-decoding-cases.json
docker build -f scripts/oracle/conway-decoding/Dockerfile -t scarab-conway-decoding:local .
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/conway-decoding-cases.json > /tmp/conway-decoding-reference.json
python3 - <<'PY'
import json
from pathlib import Path
assert json.loads(Path('/tmp/conway-decoding-reference.json').read_text()) == json.loads(
    Path('fixtures/reference/conway-decoding/reference.json').read_text())
assert json.loads(Path('/tmp/conway-decoding-cases.json').read_text()) == json.loads(
    Path('fixtures/reference/conway-decoding/cases.json').read_text())
PY
```

The harness calls the released `decodeFullAnnotator` of `cardano-ledger-binary` on a Conway
`TxBody`, `TxWits` or `Tx` at decoder version 9, and on a Babbage `TxBody` at version 7, and records
the verdict and the complete failure. It runs no ledger rule. The [fixtures](../../../fixtures/reference/conway-decoding/README.md)
retain the original encodings, the verdicts, and the source and package pins.
