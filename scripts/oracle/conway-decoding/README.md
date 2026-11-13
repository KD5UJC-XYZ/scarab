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

The [MIR amounts corpus](../../../fixtures/reference/mir-amounts/README.md) (#707) runs on the same
harness, its cases from `mir_amount_cases.py`: the amount of a MIR certificate to stake credentials
in each integer form, Shelley to Babbage. Its recipe is the one above with `mir_amount_cases.py`,
`/tmp/mir-amounts-inputs.json` and `fixtures/reference/mir-amounts/cases.jsonl`.

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

The [transaction envelope corpus](../../../fixtures/reference/tx-envelope/README.md) (#688, #699)
runs on the same harness, its cases from `tx_envelope_cases.py`: the forms of a whole transaction in
every era from Shelley to Conway, before Alonzo the `RecD` record `ShelleyTx` definite, indefinite
and detached, from Alonzo `AlonzoTx` with and without its validity flag. Its cases set `wire` as well
as `ids`, which has the harness also report, for an accepted transaction, its `encCBOR` at the era's
version: the bytes a node relays for a transaction it holds. Its recipe is the one above with
`tx_envelope_cases.py`, `/tmp/tx-envelope-inputs.json` and `fixtures/reference/tx-envelope/cases.jsonl`.

The [Plutus Data places corpus](../../../fixtures/reference/plutus-data-places/README.md) (#721) runs on
the same harness, its cases from `data_cases.py`: each place a transaction carries Plutus `Data` with
data the reference reads and data it refuses. Its recipe is the one above with `data_cases.py`.

The [address headers corpus](../../../fixtures/reference/address-headers/README.md) (#723) runs on
the same harness. Its cases come from `address_cases.py`: every header of an address and of a reward
account, read alone in each era with the kinds "address" and "account" (`DecCBOR Addr` and
`DecCBOR AccountAddress` of the CBOR byte string), and bodies that hold one of network 1 and 2 in
each place a body reads one. Its recipe is the one above with `address_cases.py`,
`/tmp/address-inputs.json` and `fixtures/reference/address-headers/cases.jsonl`.

The [era decoders corpus](../../../fixtures/reference/era-decoders/README.md) runs on the same
harness, its cases from `era_decoder_cases.py`: certificate tags in the bodies of Shelley to Conway,
time-lock native scripts in Shelley and Allegra witness sets, a repeated auxiliary data index in the
block bodies of Mary to Conway, and invalid-transaction lists in the block bodies of Alonzo to
Conway. An invalid list that repeats an index or decreases makes the harness throw instead of
returning a verdict, so each of those cases is an input file of its own; its recipe is in the
corpus README.

The [delegation hashes corpus](../../../fixtures/reference/delegation-hashes/README.md), the class of
the November 2025 mainnet partition, runs on the same harness, its cases from
`delegation_hash_cases.py`: a whole Conway transaction holding each certificate that delegates
(tags 2 and 9 to 13) with one hash of the stake credential, the pool id or the DRep 27 or 29 bytes
long, each pool id also 56 bytes long, and each certificate with every hash of 28 bytes. Its recipe
is the one above with `delegation_hash_cases.py`, `/tmp/delegation-hashes-inputs.json` and
`fixtures/reference/delegation-hashes/cases.jsonl`.

The [bootstrap chain codes corpus](../../../fixtures/reference/bootstrap-chain-codes/README.md) runs
on the same harness, its cases from `bootstrap_chain_code_cases.py`: a witness set, a whole
transaction and a block body of every era from Shelley to Conway holding a bootstrap witness whose
chain code is 0, 31, 32, 33 or 64 bytes, and one bootstrap witness read alone at each decoder version
from 2 to 12. A case of kind "bootstrap" is read alone with `DecCBOR BootstrapWitness` at its
`decoderVersion`, and a case with `bootstraps` set also reports the `bootstrapWitKeyHash` of each
bootstrap witness it holds. Its recipe is the one above with `bootstrap_chain_code_cases.py`,
`/tmp/bootstrap-chain-codes-inputs.json` and `fixtures/reference/bootstrap-chain-codes/cases.jsonl`.

The [bootstrap duplicates corpus](../../../fixtures/reference/bootstrap-duplicates/README.md) runs on
the same harness, its cases from `bootstrap_duplicate_cases.py`: whole transactions of every era from
Shelley to Conway whose bootstrap witnesses share an address root and differ in their signatures,
valid and invalid, in each order, and the witness set alone at each decoder version from the era's to
12. A case with `kept` set also reports the `encCBOR` of each vkey and bootstrap witness the sets of
an accepted witness set or transaction hold, in the order of each set; a transaction with `verify`
set also reports the result of the released `validateVerifiedWits` on it and the keys it found
invalid; and a `decoderVersion` given to a case read with `ids` replaces the era's version. Its
recipe is the one above with `bootstrap_duplicate_cases.py`, `/tmp/bootstrap-duplicates-inputs.json`
and `fixtures/reference/bootstrap-duplicates/cases.jsonl`.
