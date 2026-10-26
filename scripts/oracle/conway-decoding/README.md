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

The same harness reads the Shelley, Allegra, Mary, Alonzo and Babbage decoders at their versions
(2, 3, 4, 5, 7) for the [pre-Conway set corpus](../../../fixtures/reference/pre-conway-sets/README.md);
its cases come from `pre_conway_cases.py` and are run the same way.

The [definite-bytes corpus](../../../fixtures/reference/definite-bytes/README.md) (#373) runs on the
same harness, its cases from `definite_bytes_cases.py`: each place of a byte string in every era from
Shelley to Conway, written as a definite and as an indefinite string. It is committed as one file,
`cases.jsonl`, a line per case with its encoding and the verdict (a case with `"withdrawals": true`
also gets the withdrawals of an accepted body as decoded, for #494); the generator writes the harness
input and then merges the harness output into the lines:

```sh
python3 scripts/oracle/conway-decoding/definite_bytes_cases.py --inputs /tmp/definite-bytes-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/definite-bytes-inputs.json > /tmp/definite-bytes-reference.json
python3 scripts/oracle/conway-decoding/definite_bytes_cases.py --inputs /tmp/definite-bytes-inputs.json \
  --reference /tmp/definite-bytes-reference.json --out /tmp/definite-bytes-cases.jsonl
cmp /tmp/definite-bytes-cases.jsonl fixtures/reference/definite-bytes/cases.jsonl
```

The [era-forms corpus](../../../fixtures/reference/era-forms/README.md) (#379) runs on the same
harness too, its cases from `era_forms_cases.py`: the forms of an output and the keys of a body in
every era from Shelley to Conway. Its recipe is the one above with `era_forms_cases.py`,
`/tmp/era-forms-inputs.json` and `fixtures/reference/era-forms/cases.jsonl`.
