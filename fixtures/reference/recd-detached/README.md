# Detached `RecD` records (decoder versions 2 to 9)

Status: Current. Evidence for [#687](https://github.com/KD5UJC-XYZ/scarab/issues/687).

Below decoder version 12 the reference reads a `RecD` record's list head with `decodeRecordNamed`
around a decoder that reads nothing (`decodeCount (RecD cn) n`,
`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`) and reads the record's fields after it (`ApplyD`,
499-502). An indefinite list's break is checked at once (`decodeListLikeT`,
`Decoding/Decoder.hs:690-708`): a non-empty one fails ([the definite-records corpus](../recd-records/README.md)),
but an empty one `9f ff` passes, and the fields are then read from the items after the break. Every
enclosing decoder counts the record as one item, and every span the decoders hash (a body for its
transaction id, the parts of a block body) runs over the fields.

`cases.jsonl` holds 190 lines, one case each, written by
`scripts/oracle/conway-decoding/detached_cases.py`, each record written as `9f ff` followed by its
fields:
- the update proposal `Update` (`Shelley/PParams.hs:267`), Shelley to Babbage, and the prices and
  execution units of a parameter update (`Plutus/ExUnits.hs:195-231`) in an Alonzo or Babbage update
  and in a Conway parameter change;
- the value of an output, `MaryValue` (`Mary/Value.hs:286-306`), Mary to Conway: in the list form,
  with a datum hash, in indefinite outputs and outputs, in Babbage's map form (with a reference
  script) and in a collateral return; before Mary no value is a record;
- a redeemer's execution units, Alonzo to Conway, in the list form, in indefinite redeemers and
  lists, and in Conway's map form;
- Conway's `GovActionId` (as a vote's key and as the previous action of each action that names one),
  `VotingProcedure`, `ProposalProcedure`, `Constitution` and `Anchor` (in a proposal, a vote, a
  constitution and certificates 15, 16 and 18), nested;
- the list form of auxiliary data (`Allegra/TxAuxData.hs:185-210`, `Alonzo/TxAuxData.hs:215-262`),
  Allegra to Conway; Shelley reads only a map;
- whole transactions (`Alonzo/Tx.hs:532-553`), and a Shelley-to-Mary transaction that is itself a
  detached `ShelleyTx` (`Shelley/Tx.hs:184-192`); Conway transactions with records in the body, the
  witness set and the auxiliary data, in definite and indefinite envelopes, and four refused ones, as
  the node-to-node transaction submission carries them ([#702](https://github.com/KD5UJC-XYZ/scarab/issues/702));
- block bodies, the parts of a block after its header (`Alonzo/BlockBody/Internal.hs:215-240`),
  with the records in definite and indefinite lists of bodies and witness sets and in the
  auxiliary data.

Next to them are the refusals: fields that run past the end of the input or into the next item of
another type, an output, a redeemer or a certificate that counts the record's fields as its own
items, a witness set that counts one entry more, and empty indefinite lists where no record is read
(redeemers, vkey witnesses, certificates), whose items after them are read by the enclosing decoder.

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did
with each: `decodeFullAnnotator` at the era's decoder version. Every case is read with `ids` set, so
for an accepted body or transaction the harness also records the transaction id (`txid`), and for an
accepted block body its hash (`bodyHash`) and its transactions' ids (`txids`), each hashed from the
bytes the decoders read. In all, 140 accepted and 50 refused. `DetachedRecordsReferenceSuite`
requires the same verdict from `TxBody.decode`, `Witnesses.decode`, `AuxiliaryScripts.read`,
`Tx.fromEnvelope`, or `Block.decodeShelley` and `Tx.fromBlock` of a block built around the block
body, and the same ids and hash; `RelayedTransactionsSuite` (node) requires of each Conway transaction that
the TxSubmission2 codec and the mempool read it, and that its id is the reference's, or that it be
refused. `provenance.json` records the package versions and the hashes of
the harness, the generator, its input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes, then:

```sh
python3 scripts/oracle/conway-decoding/detached_cases.py --inputs /tmp/detached-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/detached-inputs.json > /tmp/detached-reference.json
python3 scripts/oracle/conway-decoding/detached_cases.py --inputs /tmp/detached-inputs.json \
  --reference /tmp/detached-reference.json --out fixtures/reference/recd-detached/cases.jsonl
```

## Not covered here

- Block headers: Babbage's and Conway's are `RecD` records too; a block's header is in
  [the header decoding corpus](../header-decoding/README.md) (#698), whose harness has their
  decoders.
- A transaction of Alonzo or later without its validity flag, which the reference reads as valid,
  and the other forms of a whole transaction: [the envelope corpus](../tx-envelope/README.md)
  ([#699](https://github.com/KD5UJC-XYZ/scarab/issues/699)).
- Node-to-client queries that carry a record, and failure answers that write an output
  ([#700](https://github.com/KD5UJC-XYZ/scarab/issues/700)).
- A ledger state the reference writes holds no detached record: its encoders write definite lists.
