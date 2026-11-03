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

The [update-values corpus](../../../fixtures/reference/update-values/README.md) (#470) runs on the
same harness, its cases from `update_value_cases.py`: each value of a Shelley-to-Babbage parameter
update at and over the bound of its type, and Conway's `Prices` and `ExUnits` records. Its recipe is
the one above with `update_value_cases.py`, `/tmp/update-values-inputs.json` and
`fixtures/reference/update-values/cases.jsonl`.

The [map-heads corpus](../../../fixtures/reference/map-heads/README.md) (#701) runs on the same
harness, its cases from `map_head_cases.py`: an output, a collateral return and Conway's redeemers
written as a map with each width of length head, and the eight-byte heads the reference reads. Its
recipe is the one above with `map_head_cases.py`, `/tmp/map-heads-inputs.json` and
`fixtures/reference/map-heads/cases.jsonl`.

The [`RecD` records corpus](../../../fixtures/reference/recd-records/README.md) (#668) runs on the
same harness, its cases from `recd_cases.py`: each `RecD` record a block can hold (the update
proposal, a redeemer's execution units, Conway's governance records, an output's value and the list
form of auxiliary data), definite and indefinite. For the auxiliary data the harness decodes a whole
transaction of each era from Allegra to Conway (`kind` "transaction"), which it did for Conway
alone before #668.
Its recipe is the one above with `recd_cases.py`, `/tmp/recd-inputs.json` and
`fixtures/reference/recd-records/cases.jsonl`.

The [detached `RecD` records corpus](../../../fixtures/reference/recd-detached/README.md) (#687) runs
on the same harness, its cases from `detached_cases.py`: each `RecD` record a block or a transaction
holds, written as an empty indefinite list with its fields after it. Its cases set `ids`, which has
the harness read a body, witness set, auxiliary data, whole transaction or block body (the parts of a
block after its header) of any era and report, for an accepted one, the transaction ids and block
body hash it hashes from the bytes it read. Its recipe is the one above with `detached_cases.py`,
`/tmp/detached-inputs.json` and `fixtures/reference/recd-detached/cases.jsonl`.
