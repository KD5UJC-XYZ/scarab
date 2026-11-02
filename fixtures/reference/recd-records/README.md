# `RecD` records (decoder versions 2 to 9)

Status: Current. Evidence for [#668](https://github.com/KD5UJC-XYZ/scarab/issues/668).

Below decoder version 12 the reference decodes a record built with `RecD` through
`decodeCount (RecD cn) n = decodeRecordNamed "RecD" (const n) (pure (n, cn))`
(`libs/cardano-ledger-binary/src/Cardano/Ledger/Binary/Decoding/Coders.hs:482`, cardano-ledger
`f649f9751074d2ab3de033fc3912f29c9862c1f5`). The inner decoder reads nothing, so a non-empty
indefinite list meets the check for its break before its first field and fails with "Excess terms in
array" (`decodeListLikeT`, `Decoding/Decoder.hs:690-708`). Each
instance below reads `RecD` under `ifDecoderVersionAtLeast (natVersion @12)`, or with no guard, so
one decoder version per era is every version that reads these records from a block.

`cases.jsonl` holds 118 lines, one case each, written by
`scripts/oracle/conway-decoding/recd_cases.py`, each record as a definite and an indefinite list:
- the update proposal of a body, `Update` (`Shelley/PParams.hs:267`), Shelley to Babbage;
- the execution units of a redeemer, `ExUnits` (`Plutus/ExUnits.hs:195-215`), Alonzo to Conway, in
  the list form and in Conway's map form (`Alonzo/TxWits.hs:550-597`), also at and over
  `maxBound :: Int64` and over `Word64`;
- Conway's `GovActionId` (`Conway/Governance/Procedures.hs:182-190`) as a vote's key and as the
  previous action of each of the five actions that name one, also with its index (a `Word16`,
  `Procedures.hs:162`) at and over its bound; `VotingProcedure` (456-466); `ProposalProcedure`
  (522-533); `Constitution` (985-995); and `Anchor` (`BaseTypes.hs:996-1004`) in a proposal, a vote,
  a constitution and certificates 15, 16 and 18;
- the `[coin, multiasset]` value of an output, `MaryValue` (`Mary/Value.hs:287-311`), Mary to
  Conway, in an output of two items, of three (Alonzo on), in a map output and in a collateral
  return (Babbage on);
- the `[metadata, native scripts]` list of auxiliary data, `AllegraTxAuxDataRaw`
  (`Allegra/TxAuxData.hs:185-210`) and from Alonzo the `decodeAllegra` of `AlonzoTxAuxDataRaw`
  (`Alonzo/TxAuxData.hs:245-251, 302-312`), Allegra to Conway, in a whole transaction;
- for the value and the auxiliary data list, the order of the checks: an indefinite list whose first
  field has the wrong type fails at the break, and a definite list of three at its size, before any
  field is read.

The verdict (`accepted` and the complete `failure`) is what the unchanged released decoders did with
each: `decodeFullAnnotator` at the era's decoder version. In all, 46 accepted and 72 refused. Every
refusal names `rule`, a fragment of the reference failure, and `expect`, the Scarab error case.
`RecdRecordsReferenceSuite` requires the same verdict from `TxBody.decode`, `Witnesses.decode` or
`Tx.fromEnvelope`, and from the bootstrap ledger's `LiteLedger.effects` for a body of an era with
PPUP that holds an update, and for an acceptance that the retained bytes (and so the transaction
id) are the original ones. It also reads each output whose value a body refuses with the stored
rule (`TxOut.fromBytes`, ADR 0006), which keeps its bytes and gives the value of the definite list.
`provenance.json` records the package versions and the hashes of the harness, the generator, its
input and output, and the corpus.

## Regenerating

Build `scarab-conway-decoding:local` as [the oracle README](../../../scripts/oracle/conway-decoding/README.md)
describes (the harness with the transaction decoders of Allegra to Babbage, #668), then:

```sh
python3 scripts/oracle/conway-decoding/recd_cases.py --inputs /tmp/recd-inputs.json
docker run --rm --network none --read-only -v /tmp:/fixtures:ro \
  scarab-conway-decoding:local /fixtures/recd-inputs.json > /tmp/recd-reference.json
python3 scripts/oracle/conway-decoding/recd_cases.py --inputs /tmp/recd-inputs.json \
  --reference /tmp/recd-reference.json --out fixtures/reference/recd-records/cases.jsonl
```

## Not covered here

- The protocol-parameter `Prices` and `ExUnits` are in the
  [update-values corpus](../update-values/README.md).
- An empty indefinite list `9f ff` passes the reference's check for the break, and the record's
  fields are then read from the items after it, so the reference accepts such a record when they
  follow ([#687](https://github.com/KD5UJC-XYZ/scarab/issues/687)); every indefinite list here holds
  the fields.
- A Shelley-to-Mary transaction envelope (`RecD ShelleyTx`) is not in a block; it is
  [#688](https://github.com/KD5UJC-XYZ/scarab/issues/688).
- A record held in a stored ledger state is not read by these decoders: the governance state has
  its own codec. A stored output is read with the stored rule above, and no store holds auxiliary
  data.
